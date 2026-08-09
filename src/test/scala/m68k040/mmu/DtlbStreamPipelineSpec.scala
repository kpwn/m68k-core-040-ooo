package m68k040.mmu

import m68k040.{M68kParams, VerilatorTest}
import m68k040.cache.{DTranslationCmd, DTranslationRsp}
import m68k040.core.ParamPlugin
import m68k040.ls.BehavioralMemAgent
import m68k040.services.DTranslationService
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Direct Stream shell: unlike the legacy DtlbProbePlugin, this exposes every
  * request/response ready/valid/fire and token without translating the protocol
  * back into a level-style `ready` result. */
class DtlbStreamProbePlugin extends FiberPlugin {
  val logic = during build new Area {
    val xlate = host[DTranslationService]
    val reqIn  = slave(Stream(DTranslationCmd()))
    val rspOut = master(Stream(DTranslationRsp()))
    xlate.req << reqIn
    rspOut << xlate.rsp
  }
}

/** Non-vacuous elastic/protection checks for the registered DTLB hit pipeline. */
class DtlbStreamPipelineSpec extends AnyFunSuite {
  class Dut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val dtlb = new DtlbPlugin()
    val probe = new DtlbStreamProbePlugin()
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), ctrl, dtlb, probe)) }
    def walkerAxi = dtlb.walkerAxi
  }

  val ROOT = 0x10000L
  val PTRT = 0x11000L
  val PAGT = 0x12000L

  def pokeWord(mem: BehavioralMemAgent, addr: Long, value: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((value >> (8 * (3 - i))) & 0xff).toInt)
  def rootIdx(va: Long): Int = ((va >> 25) & 0x7f).toInt
  def ptrIdx(va: Long): Int  = ((va >> 18) & 0x7f).toInt
  def pageIdx(va: Long): Int = ((va >> 12) & 0x3f).toInt
  def vpnOf(va: Long): Long  = (va >> 12) & 0xfffff

  def buildPage(mem: BehavioralMemAgent, va: Long, ppn: Long,
                writeProtect: Boolean = false, supervisor: Boolean = false): Unit = {
    pokeWord(mem, ROOT + rootIdx(va) * 4, (PTRT & 0xfffffff0L) | 0x3L)
    pokeWord(mem, PTRT + ptrIdx(va) * 4, (PAGT & 0xfffffff0L) | 0x3L)
    // U is already set so warming these entries does not consume U/M queue space.
    var desc = ((ppn << 12) & 0xfffff000L) | 0x1L | 0x8L
    if (writeProtect) desc |= 0x4L
    if (supervisor)   desc |= 0x80L
    pokeWord(mem, PAGT + pageIdx(va) * 4, desc)
  }

  def driveReq(dut: Dut, cd: ClockDomain, vpn: Long, token: Int,
               write: Boolean, supervisor: Boolean): Unit = {
    val req = dut.probe.logic.reqIn
    req.valid #= true
    req.payload.vpn #= vpn
    req.payload.token #= token
    req.payload.write #= write
    req.payload.supervisor #= supervisor
    var guard = 0
    while (!req.ready.toBoolean && guard < 300) { cd.waitSampling(); guard += 1 }
    assert(req.ready.toBoolean, s"request token=$token never became ready")
    cd.waitSampling() // the request fires here
    req.valid #= false
  }

  def consumeRsp(dut: Dut, cd: ClockDomain): (Int, Long, Boolean) = {
    val rsp = dut.probe.logic.rspOut
    rsp.ready #= true
    var guard = 0
    while (!rsp.valid.toBoolean && guard < 300) { cd.waitSampling(); guard += 1 }
    assert(rsp.valid.toBoolean, "response never arrived")
    sleep(1)
    val got = (rsp.payload.token.toInt, rsp.payload.ppn.toLong, rsp.payload.fault.toBoolean)
    cd.waitSampling() // consume
    got
  }

  test("registered hits hold stable and turn over while permissions follow each token", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.walkerAxi, cd)
      val req = dut.probe.logic.reqIn
      val rsp = dut.probe.logic.rspOut
      req.valid #= false
      req.payload.vpn #= 0
      req.payload.token #= 0
      req.payload.write #= false
      req.payload.supervisor #= false
      rsp.ready #= true
      cd.waitSampling(4)

      val wpVa  = 0x00802000L
      val supVa = 0x00803000L
      val wpPpn = 0x34567L
      val supPpn = 0x45678L
      buildPage(mem, wpVa, wpPpn, writeProtect = true)
      buildPage(mem, supVa, supPpn, supervisor = true)
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp #= ROOT
      dut.ctrl.logic.srp #= ROOT
      cd.waitSampling(2)

      var arCount = 0
      fork { while (true) { cd.waitSampling()
        if (dut.walkerAxi.ar.valid.toBoolean && dut.walkerAxi.ar.ready.toBoolean) arCount += 1
      } }

      // Warm by legal accesses, then freeze the walker count. These are real cold
      // walks; the timed section below must be resident hit-only.
      driveReq(dut, cd, vpnOf(wpVa), 1, write = false, supervisor = false)
      assert(consumeRsp(dut, cd) == ((1, wpPpn, false)))
      driveReq(dut, cd, vpnOf(supVa), 2, write = false, supervisor = true)
      assert(consumeRsp(dut, cd) == ((2, supPpn, false)))
      val warmArCount = arCount
      assert(warmArCount >= 6, s"two cold three-level walks expected, got $warmArCount ARs")

      // A is a legal read hit. Hold its response while B (a write to the same WP
      // page) changes every live request-class input. A's payload/token must remain
      // stable and B must not enter until the response slot turns over.
      rsp.ready #= false
      driveReq(dut, cd, vpnOf(wpVa), 0x21, write = false, supervisor = false)
      var guard = 0
      while (!rsp.valid.toBoolean && guard < 20) { cd.waitSampling(); guard += 1 }
      assert(rsp.valid.toBoolean)
      sleep(1)
      assert(rsp.payload.token.toInt == 0x21 && rsp.payload.ppn.toLong == wpPpn &&
             !rsp.payload.fault.toBoolean, "held A response association")

      req.valid #= true
      req.payload.vpn #= vpnOf(wpVa)
      req.payload.token #= 0x22
      req.payload.write #= true
      req.payload.supervisor #= false
      for (_ <- 0 until 3) {
        sleep(1)
        assert(!req.ready.toBoolean, "full response slot must backpressure B")
        assert(rsp.valid.toBoolean && rsp.payload.token.toInt == 0x21 &&
               rsp.payload.ppn.toLong == wpPpn && !rsp.payload.fault.toBoolean,
               "held response changed under backpressure")
        cd.waitSampling()
      }

      // Pop A and push B on exactly the same edge (accept-last). B's registered hit
      // must carry B's write permission result, not A's or a live younger class.
      rsp.ready #= true
      sleep(1)
      assert(req.ready.toBoolean && req.valid.toBoolean && rsp.valid.toBoolean)
      cd.waitSampling()
      req.valid #= false
      sleep(1)
      assert(rsp.valid.toBoolean && rsp.payload.token.toInt == 0x22 &&
             rsp.payload.ppn.toLong == wpPpn && rsp.payload.fault.toBoolean,
             "resident WP write must fault with B's token")

      // Repeat accept-last across a supervisor-only page: user hit faults, then a
      // supervisor hit succeeds. Both are resident and must issue no walker reads.
      req.valid #= true
      req.payload.vpn #= vpnOf(supVa)
      req.payload.token #= 0x23
      req.payload.write #= false
      req.payload.supervisor #= false
      cd.waitSampling()
      req.valid #= false
      sleep(1)
      assert(rsp.valid.toBoolean && rsp.payload.token.toInt == 0x23 &&
             rsp.payload.ppn.toLong == supPpn && rsp.payload.fault.toBoolean,
             "resident supervisor page must fault for user token")

      req.valid #= true
      req.payload.vpn #= vpnOf(supVa)
      req.payload.token #= 0x24
      req.payload.write #= false
      req.payload.supervisor #= true
      cd.waitSampling()
      req.valid #= false
      sleep(1)
      assert(rsp.valid.toBoolean && rsp.payload.token.toInt == 0x24 &&
             rsp.payload.ppn.toLong == supPpn && !rsp.payload.fault.toBoolean,
             "resident supervisor page must pass for supervisor token")
      cd.waitSampling()

      assert(arCount == warmArCount,
        s"timed resident permission hits unexpectedly walked: $warmArCount -> $arCount")
    }
  }
}
