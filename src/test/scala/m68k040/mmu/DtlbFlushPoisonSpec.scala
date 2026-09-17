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

/** THE D-SIDE MIRROR of `ItlbFlushPoisonSpec`: is `DtlbPlugin.walkFlushPoison`
  * COMPLETE, or does the D side have its own straddle hole?
  *
  * The I-side gap was found by reading the D side's own comment admitting a residual,
  * so the D side's coverage deserves the same measurement rather than the same trust.
  * Under Mac OS a root change is `_SwapMMUMode`, occasional. Under A/UX it is every
  * context switch -- thousands per second -- so a straddle pattern that is merely
  * improbable today becomes routine.
  *
  * Three straddle positions are exercised against two INDEPENDENT SRP trees that map
  * the same VA to different physical pages, exactly as `_SwapMMUMode`'s two config
  * records do:
  *
  *   (a) PFLUSHA lands mid-walk, several cycles AFTER launch and BEFORE completion
  *       -- the case `walkFlushPoison` exists for.
  *   (b) PFLUSHA lands in the PRE-LAUNCH cycle, while the miss is captured in
  *       `missReqReg` but the walker has not started -- handled by a different arm
  *       (`when(missReqReg.valid) { missReqReg.valid := False; missPending := False }`),
  *       so it needs its own test.
  *   (c) PFLUSHA lands in the SAME cycle the walk completes -- the `!atcFlush` term.
  *
  * In every case the requirement is identical and is checked the same way: after the
  * flush, a fresh request for that page must be answered from the NEW tree, and must
  * have re-walked to get there. */
class DtlbFlushPoisonSpec extends AnyFunSuite {

  class ProbePlugin extends FiberPlugin {
    val logic = during build new Area {
      val xlate = host[DTranslationService]
      val dtlb  = host[DtlbPlugin]
      val reqIn = in(TranslationReq())
      val rspOut = out(TranslationRsp())
      val pflusha = in Bool ()
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
      dtlb.flushAll     := pflusha
      // Routed to real OUT ports rather than poked through `simPublic`: these are a
      // sub-component IO pin and a plain Reg, and the ItlbSpec probe already
      // establishes this pattern (`walkDone := itlb.logic.walker.io.done`).
      val missCaptured = out Bool (); missCaptured := dtlb.logic.missReqReg.valid
      val walkDone     = out Bool (); walkDone     := dtlb.logic.walker.io.done
      when(xlate.req.fire) { requestIssued := True }
      when(!reqIn.valid)   { requestIssued := False }
    }
  }

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val dtlb = new DtlbPlugin()
    val probe = new ProbePlugin()
    val walkPort = new m68k040.sim.WalkerDcacheSimIo(dtlb, "dtlbWalk")
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, dtlb, probe, walkPort)) }
  }

  private val ROOT_A = 0x10000L; private val PTRT_A = 0x11000L; private val PAGT_A = 0x12000L
  private val ROOT_B = 0x20000L; private val PTRT_B = 0x21000L; private val PAGT_B = 0x22000L

  private def pokeWord(mem: DcacheClientMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
  private def buildTree(mem: DcacheClientMemAgent, root: Long, ptrt: Long, pagt: Long,
                        vpn: Long, ppn: Long): Unit = {
    pokeWord(mem, root + ((vpn >> 13) & 0x7f) * 4, (ptrt & 0xfffffff0L) | 0x3L)
    pokeWord(mem, ptrt + ((vpn >> 6) & 0x7f) * 4, (pagt & 0xfffffff0L) | 0x3L)
    // resident + U + M so nothing queues a deferred descriptor writeback.
    pokeWord(mem, pagt + (vpn & 0x3f) * 4, ((ppn << 12) & 0xfffff000L) | 0x19L)
  }

  /** @param straddle "mid" | "prelaunch" | "atdone" */
  private def body(straddle: String): Unit = {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new DcacheClientMemAgent(dut.walkPort, cd)
      val p = dut.probe.logic
      p.reqIn.valid #= false; p.reqIn.vpn #= 0; p.reqIn.write #= false
      p.reqIn.supervisor #= true; p.pflusha #= false
      cd.waitSampling(4)

      val vpn  = 0x02003L
      val ppnA = 0x33333L    // the OLD (pre-swap) tree
      val ppnB = 0x44444L    // the NEW (post-swap) tree
      buildTree(mem, ROOT_A, PTRT_A, PAGT_A, vpn, ppnA)
      buildTree(mem, ROOT_B, PTRT_B, PAGT_B, vpn, ppnB)

      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.srp #= ROOT_A
      dut.ctrl.logic.urp #= ROOT_A
      cd.waitSampling(2)

      var ar = 0
      fork { while (true) { cd.waitSampling()
        if (dut.walkPort.logic.cmd.valid.toBoolean && dut.walkPort.logic.cmd.ready.toBoolean) ar += 1 } }

      // ── a speculative access launches a walk under the OLD root ──────────────
      p.reqIn.vpn #= vpn
      p.reqIn.valid #= true
      var g = 0
      straddle match {
        case "prelaunch" =>
          // Catch the cycle where the miss is CAPTURED but the walker has not started:
          // `missPending` is set and `missReqReg.valid` is still high.
          while (!p.missCaptured.toBoolean && g < 200) { cd.waitSampling(); g += 1 }
          assert(p.missCaptured.toBoolean, "never observed the pre-launch cycle")
        case "mid" =>
          while (ar < 1 && g < 200) { cd.waitSampling(); g += 1 }
          assert(ar >= 1, "the walk must have started before the flush")
        case "atdone" =>
          // Ride up to the completion cycle and flush exactly on it.
          while (!p.walkDone.toBoolean && g < 400) { cd.waitSampling(); g += 1 }
          assert(p.walkDone.toBoolean, "never observed walker.done")
      }

      // ── the root changes and PFLUSHA retires ────────────────────────────────
      dut.ctrl.logic.srp #= ROOT_B
      dut.ctrl.logic.urp #= ROOT_B
      p.pflusha #= true
      cd.waitSampling()
      p.pflusha #= false
      val arAtFlush = ar
      cd.waitSampling(80)
      p.reqIn.valid #= false
      cd.waitSampling(6)
      val arAfterFlush = ar

      // ── the access re-issues. It MUST be answered from the NEW tree. ─────────
      p.reqIn.vpn #= vpn
      p.reqIn.valid #= true
      cd.waitSampling()
      g = 0
      while (!p.rspOut.ready.toBoolean && g < 400) { cd.waitSampling(); g += 1 }
      sleep(1)
      val ready = p.rspOut.ready.toBoolean
      val ppn   = p.rspOut.ppn.toLong
      val fault = p.rspOut.fault.toBoolean
      val reWalk = ar - arAfterFlush
      info(f"[$straddle] ARs at flush=$arAtFlush, after=$arAfterFlush; " +
           f"post-flush request -> ppn 0x$ppn%05x (new tree 0x$ppnB%05x, PRE-FLUSH tree 0x$ppnA%05x), " +
           f"fault=$fault, re-walk reads=$reWalk")
      assert(ready, "the post-flush access must resolve")
      assert(!fault, "no fault either way")
      assert(ppn == ppnB,
        f"[$straddle] STALE TRANSLATION SURVIVED PFLUSHA: vpn 0x$vpn%05x served ppn 0x$ppn%05x " +
        f"from the PRE-FLUSH page tables; the post-swap tree maps it to 0x$ppnB%05x " +
        f"(re-walk reads after the flush = $reWalk)")
      assert(reWalk > 0,
        f"[$straddle] the post-flush access must RE-WALK, not hit a surviving entry")
    }
  }

  test("D side: PFLUSHA mid-walk must not install the pre-flush translation", VerilatorTest) {
    body("mid")
  }
  test("D side: PFLUSHA in the pre-launch cycle must not install the pre-flush translation", VerilatorTest) {
    body("prelaunch")
  }
  test("D side: PFLUSHA in the walk-completion cycle must not install the pre-flush translation", VerilatorTest) {
    body("atdone")
  }
}
