package m68k040.mmu

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.{TranslationReq, TranslationRsp}
import m68k040.services.TranslationService
import m68k040.sim.DcacheClientMemAgent
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** AN MMU CONTROL WRITE LANDING IN THE ITLB's MISS-CAPTURE CYCLE LEAVES AN
  * UNPOISONED WALK CARRYING THE PRE-WRITE TCR.P.
  *
  * Every I-side walk poison keys off `missPending`, which the capture arm itself
  * writes:
  *
  *     ItlbPlugin  when(needWalk && !missReqReg.valid && !umQueueFull) {
  *                   missReqReg.valid := True
  *                   walkFlushPoison  := False          // <- cleared here
  *                   missReqReg.is8K  := is8K           // <- LIVE, i.e. PRE-write
  *                   missPending      := True           // <- register write
  *                 }
  *                 val flushPoisonArm = atcFlush && missPending   // <- reads the OLD value
  *
  * So on the capture cycle `flushPoisonArm` cannot arm, and the capture arm has
  * just cleared `walkFlushPoison`. That walk is the one walk in the plugin with no
  * poison of any kind. The D side has no such window because `_req.ready` itself
  * carries `&& !atcFlush`, so `_req.fire` is impossible on a flush cycle; the I
  * side's `needWalk` had no equivalent term. That asymmetry is the defect.
  *
  * WHY IT MISTRANSLATES. `missReqReg.is8K` and `walkKey(_req.vpn)` both read the
  * LIVE TCR.P -- on the write cycle still the PRE-write value -- while every later
  * lookup forms its array key from the POST-write one (`tlbKey`). The walk slices
  * the tables under the old page size and `tlb.io.fillVpn := tlbKeyOf(walkVpn,
  * walkIs8K)` files the result under the old key form. That is precisely the TCR.P
  * re-keying `pageSizeRekey`/`atcFlush` was added to prevent (`TlbPageSizeRekeySpec`),
  * reopened through a one-cycle window: a 4 KB-keyed entry for VPN v becomes a clean
  * ONE-HOT match for the 8 KB lookup of VPN 2v -- wrong PPN, no fault, and invisible
  * to `Tlb.dbgHitCount` and the multi-hot fail-safe.
  *
  * REACHABILITY. `ExceptionUnit.S_APPLY` writes `mmuCtrl.setPageSize` and pulses
  * `sysFlushAllValid`; `redirectValid` is only pulsed in `S_REDIR`, the NEXT cycle,
  * so `doFlush` is low on the write cycle. And the front end does not stop for
  * `excActive` either -- `FetchAlignPlugin.ic.cmd.valid` has no `excActive` term
  * ("the frontend free-runs for the WHOLE maintenance walk"). So an ITLB miss can
  * and does land in the same cycle as the MOVEC-to-TC that changes the page size.
  *
  * The straddle position itself is NOT new coverage -- `ItlbFlushPoisonSpec`'s
  * "prelaunch" case already places a PFLUSHA one cycle later. What is new is the
  * EVENT: PFLUSHA only invalidates and leaves the tree and the key form alone, so a
  * walk captured beside it still produces a correct answer. A TCR.P write changes
  * the key form under the walk, and nothing stops it.
  */
class ItlbCaptureRaceSpec extends AnyFunSuite {

  /** A private copy of `ItlbProbePlugin` with a commit-time MOVEC-to-TC write port.
    * Poking `ctrl.logic.pageSize8K` directly would change the key WITHOUT telling the
    * MMU that TC was written -- i.e. without raising `atcFlush` at all -- which is not
    * something the real core can do and would test nothing. Driving
    * `setPageSize.valid` is exactly the shape `ExceptionUnit.S_APPLY` drives. */
  class ProbePlugin extends FiberPlugin {
    val logic = during build new Area {
      val xlate = host[TranslationService]
      val itlb  = host[ItlbPlugin]
      val mctrl = host[m68k040.services.MmuControlService]
      val reqIn  = in(TranslationReq())
      val rspOut = out(TranslationRsp())
      val tcWriteValid = in Bool ()
      val tcWriteP     = in Bool ()
      val walkStart    = out Bool ()
      val missCaptured = out Bool ()
      when(tcWriteValid) {
        mctrl.setPageSize.valid   := True
        mctrl.setPageSize.payload := tcWriteP
      }
      xlate.req.valid      := reqIn.valid
      xlate.req.vpn        := reqIn.vpn
      xlate.req.supervisor := reqIn.supervisor
      xlate.req.write      := reqIn.write
      rspOut.ready     := xlate.rsp.ready
      rspOut.ppn       := xlate.rsp.ppn
      rspOut.cacheMode := xlate.rsp.cacheMode
      rspOut.fault     := xlate.rsp.fault
      itlb.umCommitValid := False
      itlb.umCommitId    := U(0, m68k040.Global.ROB_ID_W_DEFAULT bits)
      itlb.umFlush       := False
      itlb.flushAll      := False
      walkStart    := itlb.logic.walker.io.start
      missCaptured := itlb.logic.missReqReg.valid
    }
  }

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val itlb = new ItlbPlugin()
    val probe = new ProbePlugin()
    val walkPort = new m68k040.sim.WalkerDcacheSimIo(itlb, "itlbWalk")
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, itlb, probe, walkPort)) }
  }

  private val ROOT  = 0x10000L
  private val PTRT  = 0x11000L
  private val PAGT0 = 0x12000L   // page table reached by the 4K walk
  private val PAGT1 = 0x13000L   // page table reached by the 8K walk

  private def pokeWord(mem: DcacheClientMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)

  // Descriptor index slices, matching TableWalker (and TlbPageSizeRekeySpec):
  // root = vpn[19:13], pointer = vpn[12:6], page = vpn[5:0] (4K) / vpn[5:1] (8K).
  private def rootIdx(vpn: Long): Int   = ((vpn >> 13) & 0x7f).toInt
  private def ptrIdx(vpn: Long): Int    = ((vpn >> 6) & 0x7f).toInt
  private def pageIdx4k(vpn: Long): Int = (vpn & 0x3f).toInt
  private def pageIdx8k(vpn: Long): Int = ((vpn >> 1) & 0x1f).toInt

  // resident + U + M, so no walk queues a deferred U writeback (this DUT has no
  // commit port to drain one and the drain shares the descriptor read port).
  private def pageDesc(ppn: Long): Long = ((ppn << 12) & 0xfffff000L) | 0x19L

  /** @param alignTcWithCapture true  = the TC.P write lands in the miss-CAPTURE cycle
    *                                   (the race under test);
    *                           false = it lands well before the fetch is presented
    *                                   (the negative control -- same stimulus, same
    *                                   tables, same aliasing VPN pair, no collision).
    *   The false arm is what proves the true arm is not simply a broken test: if the
    *   assertion fired for both, the aliasing construction itself would be wrong. */
  private def body(alignTcWithCapture: Boolean): Unit = {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new DcacheClientMemAgent(dut.walkPort, cd)
      dut.probe.logic.reqIn.valid #= false
      dut.probe.logic.reqIn.vpn #= 0
      dut.probe.logic.reqIn.write #= false
      dut.probe.logic.reqIn.supervisor #= true
      dut.probe.logic.tcWriteValid #= false
      dut.probe.logic.tcWriteP #= false
      cd.waitSampling(4)

      val vpnLo = 0x00402L        // VA 0x00402000 -- walked and filled under 4 KB keying
      val vpnHi = 0x00804L        // VA 0x00804000 -- 8 KB key = 0x00804 >> 1 = 0x00402
      val ppnLo = 0x12340L
      val ppnHi = 0x5678AL
      assert((vpnHi >> 1) == vpnLo, "the two VPNs must collide under 8K keying")

      pokeWord(mem, ROOT + rootIdx(vpnLo) * 4, (PTRT & 0xfffffff0L) | 0x3L)
      pokeWord(mem, PTRT + ptrIdx(vpnLo) * 4, (PAGT0 & 0xfffffff0L) | 0x3L)
      pokeWord(mem, PTRT + ptrIdx(vpnHi) * 4, (PAGT1 & 0xfffffff0L) | 0x3L)
      pokeWord(mem, PAGT0 + pageIdx4k(vpnLo) * 4, pageDesc(ppnLo))
      pokeWord(mem, PAGT1 + pageIdx8k(vpnHi) * 4, pageDesc(ppnHi))
      // The 8 KB walk of vpnLo lands on PAGT0 too (root/pointer indices are page-size
      // independent), at page index vpnLo[5:1] -- give it a descriptor so the FIXED
      // build's re-walk under the NEW page size resolves rather than faulting.
      pokeWord(mem, PAGT0 + pageIdx8k(vpnLo) * 4, pageDesc(ppnLo))

      dut.ctrl.logic.mmuEnable  #= true
      dut.ctrl.logic.pageSize8K #= false     // 4 KB epoch
      dut.ctrl.logic.urp #= ROOT
      dut.ctrl.logic.srp #= ROOT
      cd.waitSampling(2)

      var arCount = 0
      var walkStarts = 0
      val counter = fork { while (true) { cd.waitSampling()
        if (dut.walkPort.logic.cmd.valid.toBoolean && dut.walkPort.logic.cmd.ready.toBoolean) arCount += 1
        if (dut.probe.logic.walkStart.toBoolean) walkStarts += 1 } }

      if (!alignTcWithCapture) {
        // Control: the SAME architectural event, just not straddling a capture.
        dut.probe.logic.tcWriteP     #= true
        dut.probe.logic.tcWriteValid #= true
        cd.waitSampling()
        dut.probe.logic.tcWriteValid #= false
        cd.waitSampling(6)
      }

      // ── The race: present a cold ITLB miss and write TCR.P in the SAME cycle. ──
      // `xlate.req.valid` is combinational on `reqIn.valid` in the probe, and
      // `needWalk` is combinational on `_req.valid`, so both the capture condition and
      // `ctrl.setPageSize.valid` hold at the SAME clock edge -- the exact collision.
      dut.probe.logic.reqIn.valid #= true
      dut.probe.logic.reqIn.vpn   #= vpnLo
      dut.probe.logic.reqIn.supervisor #= true
      if (alignTcWithCapture) {
        dut.probe.logic.tcWriteP     #= true
        dut.probe.logic.tcWriteValid #= true
      }
      cd.waitSampling()
      dut.probe.logic.tcWriteValid #= false

      // Let whatever this produced settle: either the unpoisoned 4 KB walk (buggy) or
      // the one-cycle-later re-derived 8 KB walk (fixed).
      var guard = 0
      while (!dut.probe.logic.rspOut.ready.toBoolean && guard < 400) { cd.waitSampling(); guard += 1 }
      sleep(1)
      val primeReady = dut.probe.logic.rspOut.ready.toBoolean
      dut.probe.logic.reqIn.valid #= false
      cd.waitSampling(6)
      assert(dut.ctrl.logic.pageSize8K.toBoolean,
        "the MOVEC-to-TC write port must have flipped TCR.P to 8 KB")
      assert(primeReady, "the straddling fetch must resolve one way or the other")
      val arAfterPrime = arCount

      // ── The victim: an architecturally DIFFERENT page, correctly mapped in the
      // tables for 8 KB, whose 8 KB key collides with the entry the straddling walk
      // may have filed under the 4 KB key form.
      dut.probe.logic.reqIn.valid #= true
      dut.probe.logic.reqIn.vpn   #= vpnHi
      dut.probe.logic.reqIn.supervisor #= true
      cd.waitSampling()
      guard = 0
      while (!dut.probe.logic.rspOut.ready.toBoolean && guard < 400) { cd.waitSampling(); guard += 1 }
      sleep(1)
      val ready = dut.probe.logic.rspOut.ready.toBoolean
      val ppn   = dut.probe.logic.rspOut.ppn.toLong
      val fault = dut.probe.logic.rspOut.fault.toBoolean
      val hitCount = dut.itlb.logic.tlb.dbgHitCount.toInt
      val reWalkReads = arCount - arAfterPrime
      counter.terminate()

      info(f"[alignTcWithCapture=$alignTcWithCapture] vpn 0x$vpnHi%05x -> ppn 0x$ppn%05x " +
           f"(correct 0x$ppnHi%05x, the 4K-keyed neighbour is 0x$ppnLo%05x), fault=$fault, " +
           f"re-walk reads=$reWalkReads, tlb hitVec popcount=$hitCount, walk launches=$walkStarts")

      assert(ready, "the post-TCR.P-change fetch must resolve")
      assert(!fault, "no fault is reported either way")
      if (reWalkReads == 0) {
        assert(hitCount == 1,
          s"the stale answer is a ONE-HOT hit (popcount=$hitCount) -- invisible to the " +
          s"multi-hot fail-safe, which is what makes this silent")
      }
      assert(ppn == ppnHi,
        f"MISTRANSLATION: vpn 0x$vpnHi%05x served ppn 0x$ppn%05x -- the PPN of vpn 0x$vpnLo%05x, " +
        f"filed under the PRE-write 4 KB key form by a walk captured in the same cycle as the " +
        f"MOVEC-to-TC. Correct is 0x$ppnHi%05x. Re-walk reads after the change = $reWalkReads.")
    }
  }

  test("ITLB: a TC.P write in the miss-CAPTURE cycle must not leave an unpoisoned pre-write walk",
       VerilatorTest) {
    body(alignTcWithCapture = true)
  }

  // Negative control for the test itself: identical tables, identical aliasing VPN
  // pair, identical final lookup -- the TC.P write simply does not straddle a capture.
  // This must pass both before and after the fix. If it ever fails, the aliasing
  // construction above is wrong and the positive case proves nothing.
  test("ITLB control: the same TC.P change away from a capture cycle is already handled",
       VerilatorTest) {
    body(alignTcWithCapture = false)
  }
}
