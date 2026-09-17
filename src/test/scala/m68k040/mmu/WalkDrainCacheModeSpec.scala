package m68k040.mmu

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.{CacheMode, DTranslationToken, TranslationReq, TranslationRsp}
import m68k040.services.DTranslationService
import m68k040.sim.DcacheClientMemAgent
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** A CACR.DE WRITE BETWEEN A U/M DRAIN'S RE-READ AND ITS STORE SPLIT THE TWO HALVES
  * OF ONE READ-MODIFY-WRITE ACROSS TWO CACHE MODES.
  *
  * `LsEuPlugin.walkCacheMode` used to be stamped onto both walker legs LIVE, with the
  * claim that "the read half and the write half use the SAME expression from the SAME
  * signal". That is SPATIAL agreement. One table search spans the three descriptor
  * reads, the deferred U/M drain's re-read and that drain's merged store -- hundreds of
  * cycles -- so the two legs sample `CACR.DE` at different TIMES, and a MOVEC to CACR
  * can retire in between.
  *
  * `quiesceHold` makes the split MORE likely, not less: a CACR write is a sysOp whose
  * `S_DRAIN`/`S_APPLY` deny the walker a fresh STORE grant, so a drain that has already
  * finished its re-read is parked until after the write lands -- and then stamped with
  * the NEW mode.
  *
  * DE 1->0 is the damaging direction and is exactly the case the old comment called
  * safe: the re-read ran WRITETHROUGH and ALLOCATED the descriptor line, then the store
  * ran INHIBITED and "never touches the cache array" -- memory gets the U/M update while
  * the resident copy keeps the PRE-update byte. Re-enable DE without a CINV and that
  * copy answers the next descriptor read: M is lost and a dirty page is later evicted
  * as clean.
  *
  * This drives that window directly: flip the exported policy the cycle the drain's
  * re-read completes, and require the store that follows to carry the mode the re-read
  * used, not the new one.
  */
class WalkDrainCacheModeSpec extends AnyFunSuite {

  /** Drives the DTLB's U/M hooks and, unlike `UmProbePlugin`, the exported descriptor
    * cache-mode POLICY -- the signal `LsEuPlugin` drives in the full core. */
  class ProbePlugin extends FiberPlugin {
    val logic = during build new Area {
      val xlate = host[DTranslationService]
      val dtlb  = host[DtlbPlugin]
      val reqIn  = in(TranslationReq())
      val rspOut = out(TranslationRsp())
      val accessRobId = in UInt (m68k040.Global.ROB_ID_W_DEFAULT bits)
      val commitValid = in Bool ()
      val commitId    = in UInt (m68k040.Global.ROB_ID_W_DEFAULT bits)
      /** The arbiter's live policy. True => INHIBITED (CACR.DE = 0). */
      val policyInhibited = in Bool ()
      val walkDone    = out Bool ()
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
      dtlb.umAccessRobId := accessRobId
      dtlb.umCommitValid := commitValid
      dtlb.umCommitId    := commitId
      dtlb.umFlush       := False
      dtlb.flushAll      := False
      // Exactly how LsEuPlugin drives it: a live policy, not a latched one.
      dtlb.walkCmodePolicy := Mux(policyInhibited, CacheMode.INHIBITED, CacheMode.WRITETHROUGH)
      walkDone := dtlb.logic.walker.io.done
    }
  }

  /** @param latch `DtlbPlugin.latchDrainCmode`. False = the PRE-FIX behaviour, where the
    *   drain's store re-derives the live policy instead of the value its own re-read ran
    *   under. Kept in the suite so the guarantee stays checked rather than asserted once. */
  class Dut(latch: Boolean) extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val dtlb = new DtlbPlugin(latchDrainCmode = latch)
    val probe = new ProbePlugin()
    val walkPort = new m68k040.sim.WalkerDcacheSimIo(dtlb, "dtlbWalk")
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, dtlb, probe, walkPort)) }
  }

  private val ROOT = 0x10000L
  private val PTRT = 0x11000L
  private val PAGT = 0x12000L

  private def pokeWord(mem: DcacheClientMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
  private def rootIdx(vpn: Long) = ((vpn >> 13) & 0x7f).toInt
  private def ptrIdx(vpn: Long)  = ((vpn >> 6) & 0x7f).toInt
  private def pageIdx(vpn: Long) = (vpn & 0x3f).toInt

  private def body(latch: Boolean): Unit = {
    SimConfig.withVerilator.compile(new Dut(latch)).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new DcacheClientMemAgent(dut.walkPort, cd)
      val p = dut.probe.logic
      p.reqIn.valid #= false; p.reqIn.vpn #= 0
      p.reqIn.write #= false; p.reqIn.supervisor #= false
      p.accessRobId #= 0; p.commitValid #= false; p.commitId #= 0
      p.policyInhibited #= false            // CACR.DE = 1 -> WRITETHROUGH
      cd.waitSampling(4)

      val va  = 0x02001000L
      val vpn = (va >> 12) & 0xfffffL
      pokeWord(mem, ROOT + rootIdx(vpn) * 4, (PTRT & 0xfffffff0L) | 0x3L)
      pokeWord(mem, PTRT + ptrIdx(vpn) * 4, (PAGT & 0xfffffff0L) | 0x3L)
      // Resident, U and M CLEAR -> a write walk queues a real U+M descriptor write,
      // which is the drain whose read/write pair this test is about.
      val pageAddr = PAGT + pageIdx(vpn) * 4
      pokeWord(mem, pageAddr, (0x33333L << 12) | 0x1L)

      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp #= ROOT
      dut.ctrl.logic.srp #= ROOT
      cd.waitSampling(2)

      // A WRITE access: the walk sets U and M and queues the deferred descriptor write.
      // Derived, not a literal: the top of the id space, valid at any ROB depth.
      val rob = m68k040.Global.ROB_DEPTH_DEFAULT - 1
      p.accessRobId #= rob
      p.reqIn.valid #= true; p.reqIn.vpn #= vpn
      p.reqIn.write #= true; p.reqIn.supervisor #= false
      var g = 0
      while (!p.rspOut.ready.toBoolean && g < 400) { cd.waitSampling(); g += 1 }
      assert(p.rspOut.ready.toBoolean, "the write walk must resolve")
      p.reqIn.valid #= false
      cd.waitSampling(2)

      // Record the drain store's mode the CYCLE IT IS PRESENTED, from a fork started
      // BEFORE the drain can begin. Polling after the fact races the sim memory agent,
      // which accepts the store the cycle it appears -- `drainArmed` rises one cycle
      // after the re-read response and `st.valid` drops as soon as it fires, so a poll
      // that starts even one cycle late misses it entirely (measured twice).
      val d = dut.dtlb.logic
      var seenStoreMode: Option[String] = None
      var armedMode: Option[String] = None
      val watch = fork {
        while (true) {
          cd.waitSampling(); sleep(1)
          if (armedMode.isEmpty && d.drainNeedRead.toBoolean) {
            armedMode = Some(if (d.drainCmode.toEnum == CacheMode.WRITETHROUGH) "WRITETHROUGH"
                             else "INHIBITED")
          }
          if (seenStoreMode.isEmpty && dut.walkPort.logic.st.valid.toBoolean) {
            seenStoreMode = Some(dut.walkPort.logic.st.payload.cacheMode.toEnum.toString)
          }
        }
      }

      // Commit it so the queued entry becomes drainable.
      p.commitValid #= true; p.commitId #= rob
      cd.waitSampling()
      p.commitValid #= false

      // Wait until the drain has ARMED -- the cycle `drainCmode` latches the policy --
      // then clear CACR.DE. Everything the drain still has to do (issue the re-read,
      // consume its response, present the merged store) happens after this point, so a
      // store that follows the LIVE policy will come out INHIBITED and one that follows
      // the latch will come out WRITETHROUGH. Arming is a much wider window than the
      // single cycle between the re-read response and the store, and it tests the same
      // property: the latch is taken at `drainArmingNow`.
      var g2 = 0
      while (armedMode.isEmpty && g2 < 400) { cd.waitSampling(); g2 += 1 }
      assert(armedMode.isDefined, "the U/M drain must arm (walk queued a U+M write, commit landed)")

      // THE EVENT: a MOVEC to CACR clears DE, after the drain armed, before its store.
      p.policyInhibited #= true

      g2 = 0
      while (seenStoreMode.isEmpty && g2 < 400) { cd.waitSampling(); g2 += 1 }
      watch.terminate()
      assert(seenStoreMode.isDefined, "the drain's store must be presented")
      val modeAtReRead = armedMode.get
      val storeMode = seenStoreMode.get

      info(s"[latchDrainCmode=$latch] re-read ran $modeAtReRead, CACR.DE cleared before the " +
           s"store, store presented as $storeMode")

      if (latch) {
        assert(storeMode == "WRITETHROUGH",
          s"the drain's store must carry the mode its own re-read used (WRITETHROUGH); got " +
          s"$storeMode. A split pair leaves the descriptor line allocated by the WRITETHROUGH " +
          s"re-read holding the PRE-update byte while only memory gets the U/M update.")
      } else {
        // NEGATIVE CONTROL: without the latch the store follows the LIVE policy, which is
        // the split this fix exists to remove. If this ever reads WRITETHROUGH the window
        // is no longer being reached and the positive case above proves nothing.
        assert(storeMode == "INHIBITED",
          s"[control] without the per-search latch the store must follow the LIVE policy " +
          s"(INHIBITED); got $storeMode -- the test no longer reaches the window")
      }
    }
  }

  test("a CACR.DE write between a U/M drain's re-read and its store must not split the pair",
       VerilatorTest) {
    body(latch = true)
  }

  test("control: WITHOUT the per-search latch the same sequence splits the RMW across two modes",
       VerilatorTest) {
    body(latch = false)
  }
}
