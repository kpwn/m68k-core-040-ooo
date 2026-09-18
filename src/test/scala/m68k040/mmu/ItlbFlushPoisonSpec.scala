package m68k040.mmu

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.sim.DcacheClientMemAgent
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** PFLUSHA DURING AN IN-FLIGHT I-SIDE WALK INSTALLS A PRE-FLUSH TRANSLATION.
  *
  * `DtlbPlugin` carries a `walkFlushPoison` latch: a PFLUSHA that lands while a
  * D-side walk is in flight marks that walk, and its late completion is then
  * barred from filling the ATC (`when(!walkFlushPoison && !flushAll)`).
  * `ItlbPlugin` has NO such latch -- its fill is gated only on `!flushAll`, which
  * is a ONE-CYCLE pulse, so it only covers the exact-same-cycle collision:
  *
  *     ItlbPlugin.scala   when(!walker.io.rsp.fault && !walkUmPoison && !flushAll) {
  *                          tlb.io.fillValid := True }
  *     DtlbPlugin.scala   when(!walker.io.rsp.fault && !walkFlushPoison &&
  *                             !flushAll && !walkUmPoison) { ... }
  *
  * (ItlbPlugin's own comment admits this: "that residual gap is pre-existing on
  * this walk-completion path ... and is out of scope for this fix".)
  *
  * That is exactly the Q700 ROM's `_SwapMMUMode` shape at 0x40803f4e:
  *
  *     movec %a0,%srp        <- the page-table ROOT changes
  *     movec %d1,%tc
  *     pflusha               <- one-cycle flushAll
  *     move.w (%sp)+,%sr
  *     rts
  *
  * The front end runs far ahead of retirement, so an I-side walk launched under
  * the OLD SRP can still be reading descriptors when the PFLUSHA retires. Its
  * result is then installed into BOTH the ATC array and the sticky one-entry
  * `latchValid` result cache, and serves later fetches -- a correct PC answered
  * with a physical page out of the PREVIOUS address map, one-hot and with no
  * fault, so the multi-hot tripwire cannot see it either.
  *
  * This test drives that sequence directly. */
class ItlbFlushPoisonSpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val itlb = new ItlbPlugin()
    val probe = new ItlbProbePlugin()
    val walkPort = new m68k040.sim.WalkerDcacheSimIo(itlb, "itlbWalk")
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, itlb, probe, walkPort)) }
  }

  // Two INDEPENDENT 3-level trees, exactly like _SwapMMUMode's two SRP roots.
  private val ROOT_A = 0x10000L; private val PTRT_A = 0x11000L; private val PAGT_A = 0x12000L
  private val ROOT_B = 0x20000L; private val PTRT_B = 0x21000L; private val PAGT_B = 0x22000L

  private def pokeWord(mem: DcacheClientMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
  private def rootIdx(va: Long) = ((va >> 25) & 0x7f).toInt
  private def ptrIdx(va: Long)  = ((va >> 18) & 0x7f).toInt
  private def pageIdx(va: Long) = ((va >> 12) & 0x3f).toInt
  private def vpnOf(va: Long)   = (va >> 12) & 0xfffffL

  private def buildTree(mem: DcacheClientMemAgent, root: Long, ptrt: Long, pagt: Long,
                        va: Long, ppn: Long): Unit = {
    pokeWord(mem, root + rootIdx(va) * 4, (ptrt & 0xfffffff0L) | 0x3L)
    pokeWord(mem, ptrt + ptrIdx(va) * 4, (pagt & 0xfffffff0L) | 0x3L)
    // resident + U (bit3) so no deferred U writeback is queued (no commit port here).
    pokeWord(mem, pagt + pageIdx(va) * 4, ((ppn << 12) & 0xfffff000L) | 0x9L)
  }

  /** @param straddle "mid" | "prelaunch" | "atdone" -- WHERE the one-cycle PFLUSHA
    *   pulse lands relative to the walk. Under Mac OS a root change is
    *   `_SwapMMUMode`, occasional; under A/UX it is every context switch, so every
    *   straddle position is reached routinely and each needs its own coverage.
    *   "prelaunch" is deliberately included even though it is expected to be safe by
    *   a DIFFERENT mechanism (the walk has not started, so it launches under the NEW
    *   root and never reads the old tree at all) -- that is reasoning, and reasoning
    *   about this exact interaction is what produced the I-side gap in the first
    *   place. */
  private def body(straddle: String): Unit = {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new DcacheClientMemAgent(dut.walkPort, cd)
      dut.probe.logic.reqIn.valid #= false
      dut.probe.logic.reqIn.vpn #= 0
      dut.probe.logic.reqIn.write #= false
      dut.probe.logic.reqIn.supervisor #= true
      dut.probe.logic.accessRobId #= 0
      dut.probe.logic.commitValid #= false; dut.probe.logic.commitId #= 0
      dut.probe.logic.flush #= false
      dut.probe.logic.pflusha #= false
      cd.waitSampling(4)

      val va    = 0x02003000L
      val ppnA  = 0x33333L      // what the OLD (pre-swap) tree says
      val ppnB  = 0x44444L      // what the NEW (post-swap) tree says
      buildTree(mem, ROOT_A, PTRT_A, PAGT_A, va, ppnA)
      buildTree(mem, ROOT_B, PTRT_B, PAGT_B, va, ppnB)

      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.srp #= ROOT_A
      dut.ctrl.logic.urp #= ROOT_A
      cd.waitSampling(2)

      var arCount = 0
      fork { while (true) { cd.waitSampling()
        if (dut.walkPort.logic.cmd.valid.toBoolean && dut.walkPort.logic.cmd.ready.toBoolean) arCount += 1 } }

      // ---- a speculative fetch launches a walk under the OLD root ----
      dut.probe.logic.reqIn.valid #= true
      dut.probe.logic.reqIn.vpn   #= vpnOf(va)
      dut.probe.logic.reqIn.supervisor #= true
      var g = 0
      straddle match {
        case "prelaunch" =>
          while (!dut.probe.logic.missCaptured.toBoolean && g < 200) { cd.waitSampling(); g += 1 }
          assert(dut.probe.logic.missCaptured.toBoolean, "never observed the pre-launch cycle")
        case "mid" =>
          while (arCount < 1 && g < 200) { cd.waitSampling(); g += 1 }
          assert(arCount >= 1, "the walk must have started before the flush")
        case "atdone" =>
          while (!dut.probe.logic.walkDone.toBoolean && g < 400) { cd.waitSampling(); g += 1 }
          assert(dut.probe.logic.walkDone.toBoolean, "never observed walker.done")
      }

      // ---- _SwapMMUMode retires: new root installed, then PFLUSHA ----
      dut.ctrl.logic.srp #= ROOT_B
      dut.ctrl.logic.urp #= ROOT_B
      cd.waitSampling()
      dut.probe.logic.pflusha #= true
      cd.waitSampling()
      dut.probe.logic.pflusha #= false
      val arAtFlush = arCount

      // ---- let the pre-flush walk complete ----
      g = 0
      while (!dut.probe.logic.walkDone.toBoolean && g < 400) { cd.waitSampling(); g += 1 }
      cd.waitSampling(2)
      dut.probe.logic.reqIn.valid #= false
      cd.waitSampling(4)
      val arAfterWalk = arCount
      info(s"walk started before flush (ARs at flush = $arAtFlush), completed after it " +
           s"(ARs after = $arAfterWalk)")
      info(s"[$straddle] ARs at flush=$arAtFlush, after=$arAfterWalk")

      // ---- the fetch re-issues after the swap. It must RE-WALK the NEW tree. ----
      dut.probe.logic.reqIn.valid #= true
      dut.probe.logic.reqIn.vpn   #= vpnOf(va)
      dut.probe.logic.reqIn.supervisor #= true
      cd.waitSampling()
      g = 0
      while (!dut.probe.logic.rspOut.ready.toBoolean && g < 400) { cd.waitSampling(); g += 1 }
      sleep(1)
      val ready = dut.probe.logic.rspOut.ready.toBoolean
      val ppn   = dut.probe.logic.rspOut.ppn.toLong
      val fault = dut.probe.logic.rspOut.fault.toBoolean
      val walksAfter = arCount - arAfterWalk
      info(f"[$straddle] post-PFLUSHA fetch of vpn 0x${vpnOf(va)}%05x -> ppn 0x$ppn%05x " +
           f"(new tree says 0x$ppnB%05x, PRE-FLUSH tree said 0x$ppnA%05x), " +
           f"fault=$fault, re-walk reads=$walksAfter")
      assert(ready, "the post-flush fetch must resolve")
      assert(!fault, "no fault either way")
      // A re-walk must happen SOMEWHERE after the flush -- but not necessarily on the
      // final request. With the fetch request still asserted across the flush, the I
      // side legitimately re-walks immediately (under the NEW root) and the later
      // request then hits that FRESH entry with zero reads. Measured in `[atdone]`:
      // ARs 3 -> 6 inside the window, then 0 on the re-request. Counting only the
      // final request's reads would call that a stale hit, which it is not; what
      // matters is that no PRE-flush entry survived to answer, and the PPN check
      // below is what proves that.
      val totalReWalk = (arAfterWalk - arAtFlush) + walksAfter
      assert(totalReWalk > 0,
        f"[$straddle] a re-walk must occur after the flush; no entry may simply survive it " +
        f"(in-window=${arAfterWalk - arAtFlush}, on-request=$walksAfter)")
      assert(ppn == ppnB,
        f"[$straddle] STALE TRANSLATION SURVIVED PFLUSHA: vpn 0x${vpnOf(va)}%05x served ppn 0x$ppn%05x " +
        f"from the PRE-FLUSH page tables; the post-swap tree maps it to 0x$ppnB%05x. " +
        f"re-walk reads after the flush = $walksAfter")
    }
  }

  test("I side: PFLUSHA mid-walk must not install the pre-flush translation", VerilatorTest) {
    body("mid")
  }
  test("I side: PFLUSHA in the pre-launch cycle must not install the pre-flush translation", VerilatorTest) {
    body("prelaunch")
  }
  test("I side: PFLUSHA in the walk-completion cycle must not install the pre-flush translation", VerilatorTest) {
    body("atdone")
  }
}
