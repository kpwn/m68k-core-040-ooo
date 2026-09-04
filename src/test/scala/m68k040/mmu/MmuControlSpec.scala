package m68k040.mmu

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.{CacheMode, TranslationReq, TranslationRsp}
import m68k040.services.{DTranslationService, MmuControlService}
import m68k040.sim.{DcacheClientMemAgent, WalkerDcacheSimIo}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Task 1: the shared MmuControlService (one 68040 MMU — mmuEnable/rootPtr) is read
  * by the DTLB (and, later, the ITLB). The DTLB no longer owns its own regs: poking
  * the shared control changes what the DTLB sees. Default off => identity. */
class MmuControlSpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val dtlb = new DtlbPlugin()
    val probe = new DtlbProbePlugin()
    val walkPort = new m68k040.sim.WalkerDcacheSimIo(dtlb, "dtlbWalk")
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, dtlb, probe, walkPort)) }
    // The table walker is a DcacheService CLIENT now, not an AXI master. This DUT hosts
    // no DcachePlugin, so it exposes the walker's client port pair as its own IO and lets
    // `DcacheClientMemAgent` answer it out of a SparseMemory -- the direct replacement for
    // attaching a DcacheClientMemAgent to the retired `walkerAxi`.
  }

  val ROOT = 0x10000L
  val PTRT = 0x11000L
  val PAGT = 0x12000L

  // Task #194: BIG-ENDIAN byte order (byte at the lowest address = the descriptor's
  // MSB) — matches TableWalker.selectWord's corrected convention. Kept the name.
  def pokeWordLE(mem: DcacheClientMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
  def rootIdx(va: Long): Int = ((va >> 25) & 0x7f).toInt
  def ptrIdx(va: Long): Int  = ((va >> 18) & 0x7f).toInt
  def pageIdx(va: Long): Int = ((va >> 12) & 0x3f).toInt
  def vpnOf(va: Long): Long  = (va >> 12) & 0xfffff
  def buildTable(mem: DcacheClientMemAgent, va: Long, ppn: Long): Unit = {
    pokeWordLE(mem, ROOT + rootIdx(va) * 4, (PTRT & 0xfffffff0L) | 0x3L)
    pokeWordLE(mem, PTRT + ptrIdx(va) * 4, (PAGT & 0xfffffff0L) | 0x3L)
    pokeWordLE(mem, PAGT + pageIdx(va) * 4, ((ppn << 12) & 0xfffff000L) | 0x1L)
  }
  def lookup(dut: Dut, cd: ClockDomain, vpn: Long, supervisor: Boolean = false): (Boolean, Long, Boolean) = {
    dut.probe.logic.reqIn.valid #= true
    dut.probe.logic.reqIn.vpn   #= vpn
    dut.probe.logic.reqIn.write #= false
    dut.probe.logic.reqIn.supervisor #= supervisor
    cd.waitSampling()
    var guard = 0
    while (!dut.probe.logic.rspOut.ready.toBoolean && guard < 300) { cd.waitSampling(); guard += 1 }
    sleep(1)
    (dut.probe.logic.rspOut.ready.toBoolean, dut.probe.logic.rspOut.ppn.toLong, dut.probe.logic.rspOut.fault.toBoolean)
  }

  test("shared MmuControl: default off => DTLB identity", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      new DcacheClientMemAgent(dut.walkPort, cd)
      dut.probe.logic.reqIn.valid #= false
      dut.probe.logic.reqIn.vpn #= 0; dut.probe.logic.reqIn.write #= false; dut.probe.logic.reqIn.supervisor #= false
      cd.waitSampling(4)
      val (ready, ppn, fault) = lookup(dut, cd, 0x54321L)
      assert(ready && !fault && ppn == 0x54321L, f"default-off must be identity (ppn=0x$ppn%x)")
    }
  }

  test("shared MmuControl: poke enable+root => DTLB translates", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new DcacheClientMemAgent(dut.walkPort, cd)
      dut.probe.logic.reqIn.valid #= false
      cd.waitSampling(4)
      val va = 0x00802000L
      buildTable(mem, va, ppn = 0xABCDEL)
      // poke the SHARED control (not a DTLB-owned reg). The probe drives
      // reqIn.supervisor=false (user access) -> the walk selects URP, not SRP.
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp       #= ROOT
      cd.waitSampling(2)
      val (r, p, f) = lookup(dut, cd, vpnOf(va))
      assert(r && !f && p == 0xABCDEL, f"shared-enabled DTLB must translate (ppn=0x$p%x)")
    }
  }

  test("task #131: supervisor access walks SRP, not URP", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new DcacheClientMemAgent(dut.walkPort, cd)
      dut.probe.logic.reqIn.valid #= false
      cd.waitSampling(4)
      val va = 0x00802000L
      // Build a SRP-rooted table mapping va -> 0xABCDE; leave urp pointed at a
      // completely different (unmapped) root so a wrong urp/srp selection would
      // either fault or translate to the WRONG ppn.
      val SRP_ROOT = 0x20000L; val SRP_PTRT = 0x21000L; val SRP_PAGT = 0x22000L
      pokeWordLE(mem, SRP_ROOT + rootIdx(va) * 4, (SRP_PTRT & 0xfffffff0L) | 0x3L)
      pokeWordLE(mem, SRP_PTRT + ptrIdx(va) * 4,  (SRP_PAGT & 0xfffffff0L) | 0x3L)
      pokeWordLE(mem, SRP_PAGT + pageIdx(va) * 4, ((0xABCDEL << 12) & 0xfffff000L) | 0x1L)
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp       #= 0x1L   // bogus/unmapped — must NOT be used for a supervisor access
      dut.ctrl.logic.srp       #= SRP_ROOT
      cd.waitSampling(2)
      val (r, p, f) = lookup(dut, cd, vpnOf(va), supervisor = true)
      assert(r && !f && p == 0xABCDEL, f"supervisor access must walk SRP (ppn=0x$p%x, fault=$f)")
    }
  }
}
