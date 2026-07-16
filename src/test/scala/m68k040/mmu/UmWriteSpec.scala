package m68k040.mmu

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.{TranslationReq, TranslationRsp}
import m68k040.services.DTranslationService
import m68k040.ls.BehavioralMemAgent
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Test-only plugin: drives the DTranslationService request + the DtlbPlugin's U/M
  * queue hooks (access robId, commit, flush), exposes the response. */
class UmProbePlugin extends FiberPlugin {
  val logic = during build new Area {
    val xlate = host[DTranslationService]
    val dtlb  = host[DtlbPlugin]
    val reqIn = in(TranslationReq())
    val rspOut = out(TranslationRsp())
    val accessRobId = in UInt (6 bits)
    val commitValid = in Bool ()
    val commitId    = in UInt (6 bits)
    val flush       = in Bool ()
    xlate.req.valid      := reqIn.valid
    xlate.req.vpn        := reqIn.vpn
    xlate.req.supervisor := reqIn.supervisor
    xlate.req.write      := reqIn.write
    rspOut.ready     := xlate.rsp.ready
    rspOut.ppn       := xlate.rsp.ppn
    rspOut.cacheMode := xlate.rsp.cacheMode
    rspOut.fault     := xlate.rsp.fault
    dtlb.umAccessRobId := accessRobId
    dtlb.umCommitValid := commitValid
    dtlb.umCommitId    := commitId
    dtlb.umFlush       := flush
  }
}

/** Directed tests for the deferred U/M descriptor-write queue:
  *  - a walk for a WRITE access queues a U+M descriptor write; on COMMIT it drains
  *    (RMW the descriptor byte in the page-table memory) -> the byte is updated
  *  - a queued write that is FLUSHED (mispredict) is discarded -> memory unchanged */
class UmWriteSpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val dtlb = new DtlbPlugin()
    val probe = new UmProbePlugin()
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, dtlb, probe)) }
    def walkerAxi = dtlb.walkerAxi
  }

  val ROOT = 0x10000L
  val PTRT = 0x11000L
  val PAGT = 0x12000L

  def pokeWordLE(mem: BehavioralMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * i)) & 0xff).toInt)
  def rootIdx(va: Long): Int = ((va >> 25) & 0x7f).toInt
  def ptrIdx(va: Long): Int  = ((va >> 18) & 0x7f).toInt
  def pageIdx(va: Long): Int = ((va >> 12) & 0x3f).toInt
  def vpnOf(va: Long): Long  = (va >> 12) & 0xfffff

  def buildTable(mem: BehavioralMemAgent, va: Long, ppn: Long): Long = {
    pokeWordLE(mem, ROOT + rootIdx(va) * 4, (PTRT & 0xfffffff0L) | 0x3L)
    pokeWordLE(mem, PTRT + ptrIdx(va) * 4, (PAGT & 0xfffffff0L) | 0x3L)
    val pageAddr = PAGT + pageIdx(va) * 4
    pokeWordLE(mem, pageAddr, ((ppn << 12) & 0xfffff000L) | 0x1L)  // PDT resident, U/M=0
    pageAddr
  }

  def walk(dut: Dut, cd: ClockDomain, va: Long, write: Boolean, robId: Int): Unit = {
    dut.probe.logic.accessRobId #= robId
    dut.probe.logic.reqIn.valid #= true
    dut.probe.logic.reqIn.vpn   #= vpnOf(va)
    dut.probe.logic.reqIn.write #= write
    dut.probe.logic.reqIn.supervisor #= false
    cd.waitSampling()
    var guard = 0
    while (!dut.probe.logic.rspOut.ready.toBoolean && guard < 300) { cd.waitSampling(); guard += 1 }
    assert(dut.probe.logic.rspOut.ready.toBoolean, "walk resolved")
    dut.probe.logic.reqIn.valid #= false
    cd.waitSampling(2)
  }

  def init(dut: Dut): (ClockDomain, BehavioralMemAgent) = {
    val cd = dut.clockDomain
    cd.forkStimulus(10)
    val mem = new BehavioralMemAgent(dut.walkerAxi, cd)
    dut.probe.logic.reqIn.valid #= false
    dut.probe.logic.reqIn.vpn #= 0; dut.probe.logic.reqIn.write #= false; dut.probe.logic.reqIn.supervisor #= false
    dut.probe.logic.accessRobId #= 0
    dut.probe.logic.commitValid #= false; dut.probe.logic.commitId #= 0
    dut.probe.logic.flush #= false
    cd.waitSampling(4)
    dut.ctrl.logic.mmuEnable #= true
    dut.ctrl.logic.urp   #= ROOT
    dut.ctrl.logic.srp   #= ROOT
    cd.waitSampling(2)
    (cd, mem)
  }

  test("write-access walk queues U+M; commit drains -> descriptor byte updated", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val (cd, mem) = init(dut)
      val va = 0x00802000L
      val pageAddr = buildTable(mem, va, ppn = 0xABCDEL)
      assert(mem.peekByte(pageAddr) == 0x01, "initial descriptor low byte = PDT resident")

      // a WRITE access (robId 7) -> walk queues U+M descriptor write (low byte 0x01|U|M = 0x19)
      walk(dut, cd, va, write = true, robId = 7)
      // not yet committed -> memory must be UNCHANGED (speculative, not drained)
      cd.waitSampling(5)
      assert(mem.peekByte(pageAddr) == 0x01, "U/M write must NOT drain before commit")

      // commit robId 7 -> drain RMW
      dut.probe.logic.commitValid #= true
      dut.probe.logic.commitId    #= 7
      cd.waitSampling()
      dut.probe.logic.commitValid #= false
      // let the drain write-through land
      cd.waitSampling(20)
      assert(mem.peekByte(pageAddr) == 0x19, f"descriptor byte after commit-drain = 0x${mem.peekByte(pageAddr)}%x expected 0x19")
    }
  }

  test("flushed U/M write is discarded -> memory unchanged", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val (cd, mem) = init(dut)
      val va = 0x00C04000L
      val pageAddr = buildTable(mem, va, ppn = 0x12300L)
      assert(mem.peekByte(pageAddr) == 0x01, "initial descriptor")

      // a WRITE access (robId 3) queues a U+M write...
      walk(dut, cd, va, write = true, robId = 3)
      // ...then a flush (mispredict) discards it (speculative, uncommitted)
      dut.probe.logic.flush #= true
      cd.waitSampling()
      dut.probe.logic.flush #= false
      cd.waitSampling(20)
      assert(mem.peekByte(pageAddr) == 0x01, "flushed U/M write must NOT drain (memory unchanged)")

      // a subsequent commit of robId 3 must do nothing (entry was discarded)
      dut.probe.logic.commitValid #= true; dut.probe.logic.commitId #= 3
      cd.waitSampling(); dut.probe.logic.commitValid #= false
      cd.waitSampling(20)
      assert(mem.peekByte(pageAddr) == 0x01, "no drain after a discarded entry")
    }
  }
}
