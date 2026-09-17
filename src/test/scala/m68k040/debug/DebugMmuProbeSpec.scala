package m68k040.debug

import m68k040.{M68kParams, M68kSim}
import m68k040.core.ParamPlugin
import m68k040.cache.{DTranslationToken, TranslationReq}
import m68k040.mmu.{DtlbPlugin, ItlbPlugin, MmuControlPlugin}
import m68k040.services.{DTranslationService, MmuControlService, TranslationService}
import m68k040.sim.{DcacheClientMemAgent, WalkerDcacheSimIo}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** OFF_MMU_PROBE_STATUS / _REKEY_COUNT / _IPOISON_COUNT / _DPOISON_COUNT / _CTL,
  * exercised over the REAL `dbg_axi` slave against the REAL `DtlbPlugin`,
  * `ItlbPlugin` and `MmuControlPlugin` -- no stubs anywhere in the producer path.
  *
  * WHY THIS SHAPE. This project is full of debug registers that read zero and lie:
  * `pc_live`, `exc_count` before it was served, `OFF_MISPRED_COUNT` / `OFF_FLUSH_COUNT`
  * (confirmed reading 0x00000000 on live silicon), `wedge-status` (refused outright on
  * cpu040 because it reads v1 probes the mux never implemented), and `OFF_CYCLE_LO`,
  * which had no `is()` arm at all so a healthy 200 MHz core reported a dead clock.
  * A probe that is trusted on hardware has to be proven here, and "proven" means all
  * five of:
  *
  *   1. every register reads a DISTINCT value, so no arm can pass by accident;
  *   2. the immediate NEIGHBOURS of the block read their own values and are not
  *      disturbed, so an arm cannot be aliasing its neighbour;
  *   3. the block reads CLEAN ZERO before any event, so a stuck-high probe fails;
  *   4. the CLEAR actually clears -- the trap here is a clear arm that lands in a READ
  *      region `switch`, which elaborates cleanly, reads back perfectly and does
  *      nothing at all; and
  *   5. the counters count the RIGHT NUMBER of events, not merely "nonzero".
  *
  * The PRESENT bits [9:8] are the fifth guard: with them set, a zero in [3:0] is a
  * measurement; with them clear it is an absent MMU. On hardware, read those first. */
class DebugMmuProbeSpec extends AnyFunSuite {

  /** Drives both TLBs' request ports, their PFLUSHA inputs, and a real commit-time
    * MOVEC-to-TC write port -- the same shape `ExceptionUnit` drives. */
  class MmuProbeDriverPlugin extends FiberPlugin {
    val logic = during build new Area {
      val dxlate = host[DTranslationService]
      val ixlate = host[TranslationService]
      val dtlb   = host[DtlbPlugin]
      val itlb   = host[ItlbPlugin]
      val mctrl  = host[MmuControlService]

      val dReqIn   = in(TranslationReq())
      val iReqIn   = in(TranslationReq())
      val pflusha  = in Bool ()
      val tcWriteValid = in Bool ()
      val tcWriteP     = in Bool ()

      dxlate.req.valid              := dReqIn.valid
      dxlate.req.payload.vpn        := dReqIn.vpn
      dxlate.req.payload.supervisor := dReqIn.supervisor
      dxlate.req.payload.write      := dReqIn.write
      dxlate.req.payload.token      := U(0, DTranslationToken.Width bits)
      dxlate.rsp.ready              := True

      ixlate.req.valid      := iReqIn.valid
      ixlate.req.vpn        := iReqIn.vpn
      ixlate.req.supervisor := iReqIn.supervisor
      ixlate.req.write      := False

      dtlb.flushAll := pflusha
      itlb.flushAll := pflusha

      when(tcWriteValid) {
        mctrl.setPageSize.valid   := True
        mctrl.setPageSize.payload := tcWriteP
      }

      val dRspReady = out Bool (); dRspReady := dxlate.rsp.valid
      val iRspReady = out Bool (); iRspReady := ixlate.rsp.ready
    }
  }

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val mmu   = new MmuControlPlugin
    val dtlb  = new DtlbPlugin
    val itlb  = new ItlbPlugin
    val drv   = new MmuProbeDriverPlugin
    val dWalk = new WalkerDcacheSimIo(dtlb, "dtlbWalk")
    val iWalk = new WalkerDcacheSimIo(itlb, "itlbWalk")
    // Stage 2 makes the 0x01xxx counter region real. Every stage-2 service lookup in
    // DebugCtrlPlugin is `host.get` (Option), so a DUT with no ROB still elaborates --
    // which is the point: this DUT contains the MMU and nothing else.
    val dbg = new DebugCtrlPlugin(buildId = BigInt(0x4D4D5501L), porCycles = 4, stage = 2)
    db.on {
      host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), mmu, dtlb, itlb, drv,
        dWalk, iWalk, dbg))
    }
    def axi: DbgAxiLite = dbg.logic.dbgAxi
  }

  // Page-table geometry, matching TableWalker: root = vpn[19:13], pointer = vpn[12:6],
  // page = vpn[5:0] at 4 KB / vpn[5:1] at 8 KB.
  private val ROOT_A = 0x10000L; private val PTRT_A = 0x11000L; private val PAGT_A = 0x12000L
  private val ROOT_B = 0x20000L; private val PTRT_B = 0x21000L; private val PAGT_B = 0x22000L

  private def pokeWord(mem: DcacheClientMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
  private def buildTree(mem: DcacheClientMemAgent, root: Long, ptrt: Long, pagt: Long,
                        vpn: Long, ppn: Long): Unit = {
    pokeWord(mem, root + ((vpn >> 13) & 0x7f) * 4, (ptrt & 0xfffffff0L) | 0x3L)
    pokeWord(mem, ptrt + ((vpn >> 6) & 0x7f) * 4, (pagt & 0xfffffff0L) | 0x3L)
    // resident + U + M so no walk queues a deferred descriptor writeback.
    pokeWord(mem, pagt + (vpn & 0x3f) * 4, ((ppn << 12) & 0xfffff000L) | 0x19L)
  }

  private def rd(dut: Dut, cd: ClockDomain, off: Int): Long =
    DbgAxiDriver.read(dut.axi, cd, off.toLong)

  private def idleReqs(dut: Dut): Unit = {
    val d = dut.drv.logic
    d.dReqIn.valid #= false; d.dReqIn.vpn #= 0; d.dReqIn.write #= false; d.dReqIn.supervisor #= true
    d.iReqIn.valid #= false; d.iReqIn.vpn #= 0; d.iReqIn.write #= false; d.iReqIn.supervisor #= true
    d.pflusha #= false
    d.tcWriteValid #= false; d.tcWriteP #= false
  }

  private val OFF = DebugRegMap

  test("MMU probe CSRs: present bits, clean zero, distinct values, no neighbour aliasing, real clear") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val b = dut.axi
      DbgAxiDriver.idle(b)
      dut.dbg.logic.initDoneSeen #= false
      val dMem = new DcacheClientMemAgent(dut.dWalk, cd)
      val iMem = new DcacheClientMemAgent(dut.iWalk, cd)
      idleReqs(dut)
      cd.waitSampling(20)

      // ── GUARD 1: the PRESENT bits. Without these a zero below is ambiguous. ──────
      val st0 = rd(dut, cd, OFF.OFF_MMU_PROBE_STATUS)
      assert(((st0 >> 8) & 1) == 1, f"status bit 8 (D-side probe present) must be set: 0x$st0%08x")
      assert(((st0 >> 9) & 1) == 1, f"status bit 9 (I-side probe present) must be set: 0x$st0%08x")

      // ── GUARD 2: clean zero before any event ────────────────────────────────────
      assert((st0 & 0xF) == 0, f"no sticky bit may be set before any event: 0x$st0%08x")
      assert(rd(dut, cd, OFF.OFF_MMU_REKEY_COUNT) == 0, "rekey count starts at zero")
      assert(rd(dut, cd, OFF.OFF_MMU_IPOISON_COUNT) == 0, "I poison count starts at zero")
      assert(rd(dut, cd, OFF.OFF_MMU_DPOISON_COUNT) == 0, "D poison count starts at zero")
      assert(rd(dut, cd, OFF.OFF_MMU_PROBE_CTL) == 0, "W1P control reads back zero")

      // ── NEIGHBOUR SEEDING. The block sits at 0x0104C-0x0105C. Its low neighbour is
      // OFF_STALL_ARB (0x0102C, the last allocated offset below it) and the words in
      // between (0x01030-0x01048) and above (0x01060-0x0107C) are UNALLOCATED and must
      // read zero. Give the free-running counters real, distinct, non-zero values by
      // simply letting the clock run, so an arm that aliased onto OFF_CYCLE_LO or
      // OFF_INST_LO would be caught by a mismatch rather than by a lucky zero. ──────
      cd.waitSampling(50)
      val cyc = rd(dut, cd, OFF.OFF_CYCLE_LO)
      assert(cyc != 0, "OFF_CYCLE_LO must be non-zero (it is the neighbour-aliasing bait)")

      // ── EVENT A: three TCR.P changes through the real MOVEC write port ───────────
      // 0->1, 1->0, 0->1. A write that does NOT change P must NOT count, so a fourth
      // write repeating the current value is issued and must be invisible.
      def tcWrite(p: Boolean): Unit = {
        dut.drv.logic.tcWriteP #= p
        dut.drv.logic.tcWriteValid #= true
        cd.waitSampling()
        dut.drv.logic.tcWriteValid #= false
        cd.waitSampling(4)
      }
      tcWrite(true); tcWrite(false); tcWrite(true)
      tcWrite(true)   // no change -- must not count
      cd.waitSampling(4)

      val stA = rd(dut, cd, OFF.OFF_MMU_PROBE_STATUS)
      assert((stA & 0x3) == 0x3, f"both re-key sticky bits must be set: 0x$stA%08x")
      assert(((stA >> 4) & 1) == 1, f"status bit 4 must mirror the live TCR.P (now 1): 0x$stA%08x")
      val nRekey = rd(dut, cd, OFF.OFF_MMU_REKEY_COUNT)
      assert(nRekey == 3, s"exactly three TCR.P CHANGES (the repeat must not count); got $nRekey")
      assert(rd(dut, cd, OFF.OFF_MMU_IPOISON_COUNT) == 0, "a TCR.P change is not a walk poison")
      assert(rd(dut, cd, OFF.OFF_MMU_DPOISON_COUNT) == 0, "a TCR.P change is not a walk poison")

      // ── EVENT B: a PFLUSHA landing on an in-flight walk, both sides ──────────────
      tcWrite(false)                                   // back to 4 KB for simple tables
      dut.mmu.logic.mmuEnable #= true
      dut.mmu.logic.srp #= ROOT_A
      dut.mmu.logic.urp #= ROOT_A
      val vpnD = 0x02003L
      val vpnI = 0x02005L
      val vpnI2 = 0x02007L   // a SECOND, not-yet-resident I-side page for EVENT C
      buildTree(dMem, ROOT_A, PTRT_A, PAGT_A, vpnD, 0x33333L)
      buildTree(iMem, ROOT_A, PTRT_A, PAGT_A, vpnI, 0x55555L)
      buildTree(dMem, ROOT_B, PTRT_B, PAGT_B, vpnD, 0x44444L)
      buildTree(iMem, ROOT_B, PTRT_B, PAGT_B, vpnI, 0x66666L)
      buildTree(iMem, ROOT_A, PTRT_A, PAGT_A, vpnI2, 0x77777L)
      buildTree(iMem, ROOT_B, PTRT_B, PAGT_B, vpnI2, 0x88888L)
      cd.waitSampling(4)
      val rekeyAfterTc = rd(dut, cd, OFF.OFF_MMU_REKEY_COUNT)
      assert(rekeyAfterTc == 4, s"the 1->0 change counts too; got $rekeyAfterTc")

      var dAr = 0; var iAr = 0
      fork { while (true) { cd.waitSampling()
        if (dut.dWalk.logic.cmd.valid.toBoolean && dut.dWalk.logic.cmd.ready.toBoolean) dAr += 1
        if (dut.iWalk.logic.cmd.valid.toBoolean && dut.iWalk.logic.cmd.ready.toBoolean) iAr += 1 } }

      dut.drv.logic.dReqIn.vpn #= vpnD; dut.drv.logic.dReqIn.valid #= true
      dut.drv.logic.iReqIn.vpn #= vpnI; dut.drv.logic.iReqIn.valid #= true
      var g = 0
      while ((dAr < 1 || iAr < 1) && g < 300) { cd.waitSampling(); g += 1 }
      assert(dAr >= 1 && iAr >= 1, s"both walks must have started (D=$dAr I=$iAr)")
      dut.drv.logic.pflusha #= true
      cd.waitSampling()
      dut.drv.logic.pflusha #= false
      cd.waitSampling(60)
      dut.drv.logic.dReqIn.valid #= false
      dut.drv.logic.iReqIn.valid #= false
      cd.waitSampling(10)

      val stB = rd(dut, cd, OFF.OFF_MMU_PROBE_STATUS)
      assert(((stB >> 2) & 1) == 1, f"I-side poison sticky must be set: 0x$stB%08x")
      assert(((stB >> 3) & 1) == 1, f"D-side poison sticky must be set: 0x$stB%08x")
      val nI = rd(dut, cd, OFF.OFF_MMU_IPOISON_COUNT)
      val nD = rd(dut, cd, OFF.OFF_MMU_DPOISON_COUNT)
      info(s"status=0x${stB.toHexString} rekey=$nRekey iPoison=$nI dPoison=$nD (walk ARs D=$dAr I=$iAr)")
      assert(nI == 1, s"exactly one I-side poison arm; got $nI")
      assert(nD == 1, s"exactly one D-side poison arm; got $nD")

      // ── EVENT C: an I-SIDE-ONLY poison. `pflusha` drives BOTH plugins' flushAll,
      // but only a side with a walk actually IN FLIGHT arms its poison -- so leaving
      // the D request idle must advance the I counter and leave the D counter alone.
      // This is what separates "the two poison counters are independent registers"
      // from "0x01054 and 0x01058 are aliases of one another", which is precisely the
      // neighbour-aliasing failure these adjacent offsets are exposed to. It also
      // makes the four words mutually distinct for GUARD 3 below. ──────────────────
      // A DIFFERENT page: `vpnI` is resident by now (the poisoned walk re-ran and
      // filled it correctly -- that is the fix working), so re-requesting it would hit
      // the ATC and never launch a walk to poison.
      val iArBefore = iAr
      dut.drv.logic.iReqIn.vpn #= vpnI2; dut.drv.logic.iReqIn.valid #= true
      g = 0
      while (iAr < iArBefore + 1 && g < 300) { cd.waitSampling(); g += 1 }
      assert(iAr > iArBefore, "the I-side re-walk must have started")
      assert(!dut.dtlb.logic.missPending.toBoolean, "the D side must be idle for this event")
      dut.drv.logic.pflusha #= true
      cd.waitSampling()
      dut.drv.logic.pflusha #= false
      cd.waitSampling(60)
      dut.drv.logic.iReqIn.valid #= false
      cd.waitSampling(10)
      val nI2 = rd(dut, cd, OFF.OFF_MMU_IPOISON_COUNT)
      val nD2 = rd(dut, cd, OFF.OFF_MMU_DPOISON_COUNT)
      assert(nI2 == 2, s"the I-side-only event must advance ONLY the I counter; got I=$nI2")
      assert(nD2 == 1, s"the D counter must be untouched by an I-side event; got D=$nD2")

      // ── GUARD 3: DISTINCT values. All four now differ from one another, so no arm
      // can be satisfied by reading its neighbour. ─────────────────────────────────
      val words = Seq(stB, rekeyAfterTc, nI2, nD2)
      assert(words.distinct.size == words.size,
        s"the four probe words must be mutually distinct: ${words.map(_.toHexString)}")

      // ── GUARD 4: NEIGHBOUR NON-ALIASING. The allocated neighbour still reads its own
      // source; the unallocated words around the block still read zero. ─────────────
      val cyc2 = rd(dut, cd, OFF.OFF_CYCLE_LO)
      assert(cyc2 > cyc, "OFF_CYCLE_LO must still advance and must not be aliased by the block")
      assert(rd(dut, cd, OFF.OFF_STALL_ARB) == 0, "OFF_STALL_ARB (no AxiDMerge here) stays zero")
      for (off <- Seq(0x01030, 0x01034, 0x01038, 0x0103C, 0x01040, 0x01044, 0x01048,
                      0x01060, 0x01064, 0x01068, 0x0106C, 0x01070, 0x01074, 0x01078, 0x0107C)) {
        val v = rd(dut, cd, off)
        assert(v == 0, f"unallocated offset 0x$off%05X must read zero, got 0x$v%08x")
      }

      // ── GUARD 5: THE CLEAR ACTUALLY CLEARS. A clear arm that landed in a READ-region
      // switch elaborates, reads back perfectly, and does nothing -- so this is checked
      // by writing and then RE-READING every one of the four, not by inspecting RTL. ──
      val resp = DbgAxiDriver.write(dut.axi, cd, OFF.OFF_MMU_PROBE_CTL.toLong, 0x1L)
      assert(resp == 0, s"the control write must return OKAY; got $resp")
      cd.waitSampling(4)
      val stC = rd(dut, cd, OFF.OFF_MMU_PROBE_STATUS)
      assert((stC & 0xF) == 0, f"the clear must clear every sticky bit: 0x$stC%08x")
      assert(((stC >> 8) & 3) == 3, f"the PRESENT bits are NOT clearable: 0x$stC%08x")
      assert(rd(dut, cd, OFF.OFF_MMU_REKEY_COUNT) == 0, "the clear must zero the rekey count")
      assert(rd(dut, cd, OFF.OFF_MMU_IPOISON_COUNT) == 0, "the clear must zero the I poison count")
      assert(rd(dut, cd, OFF.OFF_MMU_DPOISON_COUNT) == 0, "the clear must zero the D poison count")

      // ...and a clear with bit 0 LOW must do nothing (it is W1P, not "any write").
      tcWrite(true)
      cd.waitSampling(4)
      assert(rd(dut, cd, OFF.OFF_MMU_REKEY_COUNT) == 1, "one event after the clear")
      DbgAxiDriver.write(dut.axi, cd, OFF.OFF_MMU_PROBE_CTL.toLong, 0x2L)
      cd.waitSampling(4)
      assert(rd(dut, cd, OFF.OFF_MMU_REKEY_COUNT) == 1,
        "a write without bit 0 must NOT clear (W1P semantics)")
    }
  }
}
