package m68k040.mmu

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.{DTranslationToken, TranslationReq, TranslationRsp}
import m68k040.services.DTranslationService
import m68k040.sim.DcacheClientMemAgent
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** AN EXCEPTION-EPISODE WALK OWNS NO robId EITHER.
  *
  * The commit-time exception sequencer's frame/vector/RTE accesses are translated
  * through the DTLB's ordinary port while `excActive` is held. A write-walk for one of
  * them queued its U/M descriptor write tagged with `lsEu.xlateRobId` -- whatever robId
  * the SQUASHED LS pipe last held, which has no relationship to the exception. The
  * entry then committed at an arbitrary time, or (more often) was discarded by the next
  * flush so the M bit never reached memory at all. `ExceptionUnit.scala` has named this
  * fix in a comment since 2026-09-09; `UmWriteAlloc.preCommitted` is it.
  *
  * TWO ARMS, so the flag is proven to be what changes the outcome rather than some
  * other difference in the setup:
  *
  *   preCommitted = 0 : an ordinary LS access. NO commit pulse is issued, so the entry
  *                      must stay queued and memory must be UNCHANGED. This is the
  *                      existing, correct behaviour and it must not regress.
  *   preCommitted = 1 : an exception-episode access. It owns no robId, so it must drain
  *                      with no commit pulse ever issued.
  *
  * HONEST NOTE ON "FAILS BEFORE": `umAccessPreCommitted` does not exist on the unfixed
  * RTL, so this spec cannot be compiled against it -- the pre-fix evidence is the
  * borrowed `lsEu.xlateRobId` tag itself and the comment that named it. What these two
  * arms DO establish is that the new flag is the sole cause of the new behaviour, which
  * is the part a future edit could silently break. */
class ExcWalkPreCommittedSpec extends AnyFunSuite {

  class Probe extends FiberPlugin {
    val logic = during build new Area {
      val xlate = host[DTranslationService]
      val dtlb  = host[DtlbPlugin]
      val reqIn = in(TranslationReq())
      val rspOut = out(TranslationRsp())
      val preCommitted = in Bool ()
      val requestIssued = RegInit(False)
      xlate.req.valid              := reqIn.valid && !requestIssued
      xlate.req.payload.vpn        := reqIn.vpn
      xlate.req.payload.supervisor := reqIn.supervisor
      xlate.req.payload.write      := reqIn.write
      xlate.req.payload.token      := U(0, DTranslationToken.Width bits)
      xlate.rsp.ready   := !reqIn.valid
      rspOut.ready      := xlate.rsp.valid
      rspOut.ppn        := xlate.rsp.payload.ppn
      rspOut.cacheMode  := xlate.rsp.payload.cacheMode
      rspOut.fault      := xlate.rsp.payload.fault
      when(xlate.req.fire) { requestIssued := True }
      when(!reqIn.valid)   { requestIssued := False }
      // THE PORT UNDER TEST. Everything else is left at its idle default -- in
      // particular NO commit pulse is ever driven, on either arm.
      dtlb.umAccessPreCommitted := preCommitted
      val walkDone = out Bool (); walkDone := dtlb.logic.walker.io.done
    }
  }

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val dtlb = new DtlbPlugin()
    val probe = new Probe()
    val walkPort = new m68k040.sim.WalkerDcacheSimIo(dtlb, "dtlbWalk")
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, dtlb, probe, walkPort)) }
  }

  private val ROOT = 0x10000L
  private val PTRT = 0x11000L
  private val PAGT = 0x12000L

  private def pokeWord(mem: DcacheClientMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)

  private def body(preCommitted: Boolean): Unit = {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new DcacheClientMemAgent(dut.walkPort, cd)
      val p = dut.probe.logic
      p.reqIn.valid #= false; p.reqIn.vpn #= 0
      p.reqIn.write #= false; p.reqIn.supervisor #= true
      p.preCommitted #= preCommitted
      cd.waitSampling(4)

      val vpn = 0x02003L
      pokeWord(mem, ROOT + ((vpn >> 13) & 0x7f) * 4, (PTRT & 0xfffffff0L) | 0x3L)
      pokeWord(mem, PTRT + ((vpn >> 6) & 0x7f) * 4, (PAGT & 0xfffffff0L) | 0x3L)
      val leafAddr = PAGT + (vpn & 0x3f) * 4
      // resident, U and M both CLEAR, so a WRITE walk must queue a real U+M set.
      pokeWord(mem, leafAddr, 0x00033000L | 0x01L)
      assert(mem.peekByte(leafAddr + 3) == 0x01, "descriptor starts with U and M clear")

      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.srp #= ROOT
      dut.ctrl.logic.urp #= ROOT
      cd.waitSampling(2)

      // ONE write access. No commit pulse is ever issued, on either arm.
      p.reqIn.vpn #= vpn
      p.reqIn.write #= true
      p.reqIn.valid #= true
      var g = 0
      while (!p.walkDone.toBoolean && g < 400) { cd.waitSampling(); g += 1 }
      assert(p.walkDone.toBoolean, "the write walk must complete")
      p.reqIn.valid #= false

      g = 0
      while (mem.peekByte(leafAddr + 3) != 0x19 && g < 400) { cd.waitSampling(); g += 1 }
      val b = mem.peekByte(leafAddr + 3)
      info(f"preCommitted=$preCommitted -> descriptor low byte 0x$b%02x with NO commit pulse")
      if (preCommitted)
        assert(b == 0x19,
          f"an exception-episode walk owns no robId and must drain unaided: byte 0x$b%02x, expected 0x19 (PDT|U|M)")
      else
        assert(b == 0x01,
          f"an ORDINARY access DOES own a robId and must still wait for its commit: byte 0x$b%02x, expected 0x01")
    }
  }

  test("exception-episode walk (preCommitted=1) drains its U/M write with no commit pulse", VerilatorTest) {
    body(preCommitted = true)
  }
  test("ordinary access (preCommitted=0) still waits for its owning robId to commit", VerilatorTest) {
    body(preCommitted = false)
  }
}
