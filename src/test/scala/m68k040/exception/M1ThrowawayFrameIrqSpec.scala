package m68k040.exception

import m68k040.VerilatorTest
import m68k040.fuzz.{FuzzDut, FuzzCoreDut}
import m68k040.lockstep.ArchStateProbe
import m68k040.oracle.ProgramAssembler
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

/** Part 135 (2026-09-04): the M=1 / format-`$1` THROWAWAY interrupt-frame path.
  *
  * Part 134 (`docs/superpowers/specs/2026-09-04-p134-midmacro-irq-an-corruption-negative.md`)
  * cleared the mid-macro-IRQ hypothesis for the p133 "SP exactly 2 bytes low" silicon
  * boot failure and closed by naming three coverage gaps. The biggest was this one:
  * every scenario there ran with `SR.M = 0`, so the 68040's DUAL-STACK interrupt entry
  * — two frames, two stack pointers, one `RTE` — was never exercised at all. Part 134's
  * "A7 is exactly balanced across 761 interrupt entries" therefore covered only the
  * M=0, format-`$0` path, and said so.
  *
  * ── The architectural sequence under test ────────────────────────────────────────────
  * (MC68040 UM §8.4.2/§8.4.3; Musashi `m68ki_exception_interrupt` / `rte` case 1;
  * mirrored by this core at `ExceptionUnit.scala:450-466, 1363-1368, 1518-1526,
  * 1549-1556, 1735-1752`.)
  *
  * An INTERRUPT taken while `S=1, M=1`:
  *   1. stacks a NORMAL four-word format-`$0` frame on the currently-active stack, which
  *      is the MASTER stack (M is still 1)  ->  MSP -= 8;
  *   2. clears M, which re-banks A7 to the INTERRUPT stack;
  *   3. stacks a four-word format-`$1` "throwaway" frame there  ->  ISP -= 8. Same
  *      {SR, PC} content as the first frame; only the format nibble differs.
  *   4. The handler runs on the ISP with M=0 and the mask raised to the IRQ level.
  *
  * `RTE`, within ONE instruction:
  *   5. pops the format-`$1` frame off the ISP (ISP += 8), applies its SR — which still
  *      carries M=1, so A7 re-banks back to the MSP — and DISCARDS its PC;
  *   6. loops back and pops the real format-`$0` frame now at the top of the MSP
  *      (MSP += 8), using ITS PC and SR for the actual return.
  *
  * Net: BOTH stack pointers must end exactly where they started. That two-frame,
  * two-stack, one-instruction unwind is exactly the shape in which a 2-byte accounting
  * error hides, and it had no test in this repo (the only M=1 exception test,
  * `exc_msp_mode_round_trip.s`, uses a TRAP — which keeps M — and its own header says
  * the IRQ-clears-M version is "TBD").
  *
  * DIRECTION NOTE: informal restatements of this sequence often put the throwaway frame
  * on the MASTER stack and the real frame on the interrupt stack. That is backwards.
  * These tests assert the correct direction explicitly — T1 would fail its format-word
  * checks if the frames were swapped.
  *
  * ── Detection channels (three, deliberately independent) ─────────────────────────────
  *
  *  1. GUEST-SIDE architectural assertions. The program re-derives every fact from
  *     `%a7`, `MOVEC %msp`, `MOVEC %isp` and `%sr`, plus the stacked frame words read
  *     back through `%a7@(n)`. A violation writes a DISTINCT numeric code to
  *     `ResultAddr`, so a failure names the exact check that broke.
  *  2. SIM-SIDE frame-address audit. With the MMU off both stacks are WRITETHROUGH, so
  *     every frame word is its own AXI AW beat. For a bare-`RTE` handler the ONLY
  *     addresses that may ever be written inside either stack's low window are
  *     {MSP-8,-6,-4,-2} and {ISP-8,-6,-4,-2}. A stack pointer that drifts by 2 bytes
  *     writes 2 bytes off, and it drifts CUMULATIVELY, so entry 2 is unmistakable.
  *     This channel does not depend on the guest program being correct at all.
  *  3. The committed `ss.msp` / `ss.isp` / `ss.srSys` bank registers, read directly at
  *     the end of the run, plus the architectural loop counter in D7 read through
  *     `ArchStateProbe` (liveness: proves the guest really looped rather than wedging).
  */
class M1ThrowawayFrameIrqSpec extends AnyFunSuite {
  private val loadAddr   = ProgramAssembler.DefaultLoadAddress
  private val IspBase    = 0x00100000L    // M=0 supervisor bank
  private val MspBase    = 0x00120000L    // M=1 supervisor bank
  private val ResultAddr = 0x000A0000L    // guest result / fail-code word
  private val Scratch    = 0x000A0010L    // per-iteration marker store (sweep anchor)
  private val PassWord   = 0xC0FFEE00L

  /** autovector level 1 -> vector 25 -> vector-table offset 25*4 = 0x64 (VBR = 0). */
  private val VecOff     = 0x64L
  private val Fmt0Word   = 0x0064          // format/vector word of the REAL frame
  private val Fmt1Word   = 0x1064          // format/vector word of the THROWAWAY frame

  private def hex(v: Long): String = "0x%08x".format(v & 0xffffffffL)

  // ── guest-side MMU bring-up: real 8 KB page tables, TTRs OFF ─────────────────────
  // Verbatim recipe from the proven, passing `tst_abs_beqw_mmu_on.s` /
  // `mmu_swapmode_cpush_race.s` corpus tests: a 1:1 three-level 8 KB map covering RAM
  // 0x000000-0x3FFFFF (the vector table, both stacks, the result/scratch words and the
  // table region itself) and code 0x40800000-0x4083FFFF, with all four TTRs zeroed so
  // EVERY first touch is a genuine table walk. TC = 0x0000C000 is the exact value read
  // back over JTAG from the failing p133 silicon (E=1, 8 KB pages).
  private val MmuSetup =
    """
      |    lea     0x00200000, %a5
      |    bsr     _build_tables
      |    move.l  #0x00200000, %d0
      |    movec   %d0, %urp
      |    moveq   #0, %d0
      |    movec   %d0, %itt0
      |    movec   %d0, %itt1
      |    movec   %d0, %dtt0
      |    movec   %d0, %dtt1
      |    move.l  #0x00200000, %d0
      |    movec   %d0, %srp
      |    move.l  #0x0000C000, %d0          | TC: E=1, 8 KB pages -- the p133 value
      |    movec   %d0, %tc
      |    pflusha
      |""".stripMargin

  private val MmuTableBuilder =
    """
      |_build_tables:
      |    move.l  %a5, %a0
      |    move.w  #(0x1100/4)-1, %d0
      |.zero_loop:
      |    clr.l   (%a0)+
      |    dbra    %d0, .zero_loop
      |    move.l  %a5, %d0
      |    addi.l  #0x0200+0x0A, %d0
      |    move.l  %d0, 0x000(%a5)
      |    move.l  %a5, %d0
      |    addi.l  #0x0400+0x0A, %d0
      |    move.l  %d0, 0x080(%a5)
      |    lea     0x0200(%a5), %a0
      |    move.l  %a5, %d0
      |    addi.l  #0x0600+0x0A, %d0
      |    moveq   #15, %d2
      |.l2ram_loop:
      |    move.l  %d0, (%a0)+
      |    addi.l  #0x80, %d0
      |    dbra    %d2, .l2ram_loop
      |    move.l  %a5, %d0
      |    addi.l  #0x0E00+0x0A, %d0
      |    move.l  %d0, 0x0400+0x080(%a5)
      |    lea     0x0600(%a5), %a0
      |    move.l  #0x00000039, %d0
      |    move.w  #511, %d2
      |.l3ram_loop:
      |    move.l  %d0, (%a0)+
      |    addi.l  #0x2000, %d0
      |    dbra    %d2, .l3ram_loop
      |    lea     0x0E00(%a5), %a0
      |    move.l  #0x40800039, %d0
      |    moveq   #31, %d2
      |.l3rom_loop:
      |    move.l  %d0, (%a0)+
      |    addi.l  #0x2000, %d0
      |    dbra    %d2, .l3rom_loop
      |    rts
      |""".stripMargin

  // ── shared guest prologue ────────────────────────────────────────────────────────
  // Boot posture is S=1, M=0, A7 = ISP (the harness seeds arch-15 and ss.isp). The MSP
  // is given a distinct value via MOVEC while M=0, i.e. written into the INACTIVE bank,
  // then M is flipped to 1 with ORI-to-SR and the IRQ mask is dropped to 0.
  private def prologue(mmu: Boolean, m1: Boolean = true): String =
    s"""
       | .text
       | .org 0
       |_start:
       |    move.l  #${hex(IspBase)}, %a7
       |    move.l  #_irq_handler, ${hex(VecOff)}
       |    move.l  #${hex(MspBase)}, %d0
       |    movec   %d0, %msp
       |${if (mmu) MmuSetup else ""}
       |    | poison both frame windows: a frame that is never stacked reads back 0x5A5A
       |    move.l  #0x5A5A5A5A, ${hex(MspBase - 8)}
       |    move.l  #0x5A5A5A5A, ${hex(MspBase - 4)}
       |    move.l  #0x5A5A5A5A, ${hex(IspBase - 8)}
       |    move.l  #0x5A5A5A5A, ${hex(IspBase - 4)}
       |    moveq   #0, %d6
       |    moveq   #0, %d7
       |""".stripMargin +
    (if (m1)
      s"""
         |    ori.w   #0x1000, %sr              | M=1  -> A7 re-banks to the MSP
         |    move.l  %a7, %d0
         |    cmp.l   #${hex(MspBase)}, %d0
         |    bne     _f_m_flip
         |""".stripMargin
     else "") +
    """
      |    andi.w  #0xf8ff, %sr              | IRQ mask 0 (M unchanged)
      |""".stripMargin

  /** Fail-code trailer. Every `_f_*` label preserves the OBSERVED value that failed its
    * check (always left in `%d0` by the check site) into `%d5`, then writes the numeric
    * code to `ResultAddr` and the observed value to `ResultAddr+8`.
    *
    * Recording the observed value is what makes a failure diagnosable rather than merely
    * reportable: if a check fails while the value it compared is CORRECT, the defect is
    * in the CONDITION CODES the branch consumed (i.e. the exception entry / `RTE` failed
    * to preserve the CCR across the interrupt), not in the architectural value itself.
    * No handler in this file may use `%d5`. */
  private def failCodes(codes: Seq[(String, Long)], mmu: Boolean): String =
    codes.map { case (lbl, code) =>
      s"""
         |$lbl:
         |    move.l  %d0, %d5
         |    move.l  #${hex(code)}, %d0
         |    bra     _do_fail
         |""".stripMargin
    }.mkString +
    s"""
       |_do_fail:
       |    move.l  %d0, ${hex(ResultAddr)}
       |    move.l  %d5, ${hex(ResultAddr + 8)}
       |_halt_fail:
       |    bra     _halt_fail
       |""".stripMargin +
    (if (mmu) MmuTableBuilder else "")

  private lazy val compiled = m68k040.M68kSim().withVerilator.compile(new FuzzCoreDut)

  // ── the shared simulation driver ─────────────────────────────────────────────────
  /** @param mode        "once" = one held IPL pulse; "storm" = a growing-gap pulse train;
    *                    "sweep" = deterministic per-cycle arrival enumeration anchored on
    *                    the guest's per-iteration Scratch store.
    * @param auditFrames enforce the exact 8-address frame-write set. Valid only for a
    *                    bare-`RTE` handler with the MMU off (a handler that uses the
    *                    stack writes elsewhere; a COPYBACK page does not emit an AW per
    *                    store).
    */
  private def runScenario(simName: String, src: String, mode: String,
                          auditFrames: Boolean,
                          pulses: Int = 600,
                          sweepPhases: Int = 192, sweepPasses: Int = 2,
                          minIrqEntries: Int = 1,
                          minIterations: Int = 0,
                          expectResult: Long = PassWord,
                          mmuOn: Boolean = false): Unit = {
    val image = ProgramAssembler.assemble(src, loadAddr).getOrElse(fail(s"assemble failed for $simName"))
    compiled.doSim(simName, seed = 1) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      FuzzDut.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      // The two walker AXI agents that used to be attached here are GONE: since
      // `2db5bd3` the table walkers have no AXI master of their own and reach memory
      // through the D-cache, so their traffic already lands in `dmem`. (These agents
      // shared `dmem.mem` anyway, so removing them changes no observable behaviour.)
      for (i <- image.bytes.indices) dmem.mem.write(loadAddr + i, image.bytes(i).toByte)
      for (i <- 0 until 16) dmem.mem.write(ResultAddr + i, 0.toByte)

      var irqTaken   = 0
      var prevIrqPnd = false
      var awMarks    = 0
      var iterMark   = false
      var resultWritten = false
      val mspFrameHits = mutable.Map.empty[Long, Int]
      val ispFrameHits = mutable.Map.empty[Long, Int]

      cd.onSamplings {
        val axi = dut.dcache.logic.axi
        if (axi.aw.valid.toBoolean && axi.aw.ready.toBoolean) {
          val a = axi.aw.payload.addr.toLong & 0xffffffffL
          if (a >= MspBase - 256 && a < MspBase) mspFrameHits(a) = mspFrameHits.getOrElse(a, 0) + 1
          if (a >= IspBase - 256 && a < IspBase) ispFrameHits(a) = ispFrameHits.getOrElse(a, 0) + 1
          if (a == Scratch) { awMarks += 1; iterMark = true }
          if (a == ResultAddr) resultWritten = true
        }
        val irqNow = dut.rob.logic.interruptPending.toBoolean
        if (irqNow && !prevIrqPnd) irqTaken += 1
        prevIrqPnd = irqNow
      }

      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.urp #= 0
      dut.ctrl.logic.srp #= 0
      dut.intCtrl.logic.iplIn #= 0
      dut.intCtrl.logic.iackAvec #= true
      dut.intCtrl.logic.iackVector #= 0
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false
      dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true; cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.rob.logic.exc.ss.isp #= IspBase
      // DE|IE -- "firmware already enabled the caches", the posture every other
      // full-core harness in this repo states explicitly (design doc section 5.2).
      // Stated here too as of 2026-09-09: CACR.IE acquired its FIRST reader that
      // day (IcachePlugin), so a bespoke sim that leaves CACR at its reset value 0
      // now runs with the instruction cache DISABLED. Correct, but not what this
      // test means to exercise -- and silently so.
      dut.rob.logic.exc.ss.cacr #= 0x80008000L
      dut.rob.logic.exc.ss.msp #= 0L
      dut.rob.logic.exc.ss.usp #= 0L
      dut.wire.logic.seedValid #= true; dut.wire.logic.seedAddr #= 15; dut.wire.logic.seedData #= BigInt(IspBase)
      cd.waitSampling(2); dut.wire.logic.seedValid #= false
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false

      // Let the prologue run to the spin/loop. The MMU variant builds a 4 KiB table set
      // one longword at a time first, so it needs far longer.
      cd.waitSampling(if (mmuOn) 60000 else 900)

      if (mmuOn) {
        // Fail closed rather than silently degrading to an MMU-off run.
        assert(dut.ctrl.logic.mmuEnable.toBoolean,
          "the guest's MOVEC to %tc did not enable translation -- this scenario would be vacuous")
        assert(dut.ctrl.logic.pageSize8K.toBoolean,
          "TC.P (8 KB pages) did not take -- this scenario would not be the p133 posture")
      }

      var fired = 0
      mode match {
        case "once" =>
          dut.intCtrl.logic.iplIn #= 1
          var hold = 0
          while (irqTaken == 0 && hold < 6000) { cd.waitSampling(); hold += 1 }
          cd.waitSampling(2)
          dut.intCtrl.logic.iplIn #= 0
          fired = 1
          cd.waitSampling(6000)

        case "storm" =>
          // 2-cycle level-1 pulse; the inter-pulse gap grows by one cycle every pulse so
          // the arrival walks EVERY cycle offset within the loop body instead of aliasing
          // onto a fixed phase (Part 134's storm shape).
          var gap = 11
          while (fired < pulses && !resultWritten) {
            dut.intCtrl.logic.iplIn #= 1
            cd.waitSampling(2)
            dut.intCtrl.logic.iplIn #= 0
            cd.waitSampling(gap)
            gap += 1; if (gap > 190) gap = 11
            fired += 1
          }
          dut.intCtrl.logic.iplIn #= 0
          cd.waitSampling(800)

        case "sweep" =>
          // Anchor on the guest's per-iteration Scratch store, wait exactly `phase`
          // cycles, then HOLD the level until the ROB recognises it. Holding is
          // mandatory: IPL is level-sensitive and recognition is deferred to the next
          // macro boundary, so a short pulse arriving mid-macro can LAPSE, silently
          // turning "arrived at offset N" into "never arrived" (Part 134 §3 measured
          // only ~23% conversion with a bare 2-cycle pulse; holding gives 100%).
          for (_ <- 0 until sweepPasses; phase <- 0 until sweepPhases if !resultWritten) {
            iterMark = false
            var guard = 0
            while (!iterMark && guard < 8000) { cd.waitSampling(); guard += 1 }
            cd.waitSampling(phase)
            dut.intCtrl.logic.iplIn #= 1
            val before = irqTaken
            var hold = 0
            while (irqTaken == before && hold < 2000) { cd.waitSampling(); hold += 1 }
            cd.waitSampling(2)
            dut.intCtrl.logic.iplIn #= 0
            cd.waitSampling(6)
            fired += 1
          }
          dut.intCtrl.logic.iplIn #= 0
          cd.waitSampling(800)
      }

      def rd32(a: Long): Long = {
        var v = 0L
        for (i <- 0 until 4) v = (v << 8) | (dmem.mem.read(a + i).toLong & 0xffL)
        v & 0xffffffffL
      }
      val result = rd32(ResultAddr)
      val observed = rd32(ResultAddr + 8)   // the value the failing check actually saw
      val msp    = dut.rob.logic.exc.ss.msp.toBigInt.toLong & 0xffffffffL
      val isp    = dut.rob.logic.exc.ss.isp.toBigInt.toLong & 0xffffffffL
      val srSys  = dut.rob.logic.exc.ss.srSys.toBigInt.toInt
      val probe  = new ArchStateProbe(dut.ren, dut.rfInt, dut.rfNzvc, dut.rfX)
      val d7     = probe.readArch(7)          // guest loop counter
      val a7     = probe.readArch(8 + 7)

      def fmtSet(m: mutable.Map[Long, Int]): String =
        if (m.isEmpty) "(none)"
        else m.toSeq.sortBy(_._1).map { case (a, n) => f"0x$a%08x x$n" }.mkString(" ")

      info(f"$simName: fired=$fired irqEntries=$irqTaken loopIters(D7)=$d7 awMarks=$awMarks " +
           f"result=0x$result%08x observed=0x$observed%08x A7=0x$a7%08x ss.msp=0x$msp%08x " +
           f"ss.isp=0x$isp%08x ss.srSys=0x$srSys%02x")
      info(f"$simName: MASTER-stack frame writes:    ${fmtSet(mspFrameHits)}")
      info(f"$simName: INTERRUPT-stack frame writes: ${fmtSet(ispFrameHits)}")

      assert(irqTaken >= minIrqEntries,
        s"$simName: only $irqTaken interrupt entries were recognised (needed >= $minIrqEntries) -- the sweep proved nothing")
      if (minIterations > 0)
        assert(d7 >= minIterations,
          s"$simName: the guest completed only $d7 loop iterations (needed >= $minIterations) -- " +
          "it likely wedged, so a clean result would prove nothing")

      if (auditFrames) {
        val wantMsp = Set(MspBase - 8, MspBase - 6, MspBase - 4, MspBase - 2)
        val wantIsp = Set(IspBase - 8, IspBase - 6, IspBase - 4, IspBase - 2)
        assert(mspFrameHits.keySet.toSet == wantMsp,
          f"$simName: the MASTER stack's frame-word address set DRIFTED. expected exactly " +
          f"${wantMsp.toSeq.sorted.map(a => f"0x$a%08x").mkString(",")}, observed ${fmtSet(mspFrameHits)}")
        assert(ispFrameHits.keySet.toSet == wantIsp,
          f"$simName: the INTERRUPT stack's frame-word address set DRIFTED. expected exactly " +
          f"${wantIsp.toSeq.sorted.map(a => f"0x$a%08x").mkString(",")}, observed ${fmtSet(ispFrameHits)}")
      }
      // Both stacks must have been used at all: the real frame on the MASTER stack, the
      // throwaway on the INTERRUPT stack. An empty window means the dual-stack path never
      // ran and the scenario proved nothing. (Checked even without the strict audit, as
      // long as the stacks are writethrough.)
      if (!mmuOn) {
        assert(mspFrameHits.nonEmpty, s"$simName: NOTHING was ever written to the MASTER stack -- the M=1 entry path did not run")
        assert(ispFrameHits.nonEmpty, s"$simName: NOTHING was ever written to the INTERRUPT stack -- no throwaway frame was stacked")
      }

      assert(result == expectResult,
        f"$simName: guest result word 0x$result%08x, expected 0x$expectResult%08x. " +
        f"(0x00000000 = no verdict written; 0xbad000NN = the numbered guest check that failed.) " +
        f"OBSERVED VALUE at that check = 0x$observed%08x -- if that value is the CORRECT one, " +
        f"the architectural state was fine and the branch took the wrong way, i.e. the CONDITION " +
        f"CODES did not survive the interrupt. " +
        f"A7=0x$a7%08x ss.msp=0x$msp%08x ss.isp=0x$isp%08x ss.srSys=0x$srSys%02x")
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════════
  // T1 -- ONE M=1 interrupt, every architectural fact of the two-frame entry checked.
  // ══════════════════════════════════════════════════════════════════════════════════
  private def t1Src(mmu: Boolean) = {
    val body =
      s"""
         |_spin:
         |    addq.l  #1, %d7
         |    tst.b   %d6
         |    beq     _spin
         |_spin_end:
         |    | ---- post-RTE: both banks must be back exactly where they started ----
         |    cmp.l   #${hex(MspBase)}, %a7
         |    bne     _f_post_a7
         |    movec   %msp, %d0
         |    cmp.l   #${hex(MspBase)}, %d0
         |    bne     _f_post_msp
         |    movec   %isp, %d0
         |    cmp.l   #${hex(IspBase)}, %d0
         |    bne     _f_post_isp
         |    move.w  %sr, %d0
         |    andi.w  #0xff00, %d0
         |    cmp.w   #0x3000, %d0             | S=1, M=1 restored, mask back to 0
         |    bne     _f_post_sr
         |    move.l  #${hex(PassWord)}, %d0
         |    move.l  %d0, ${hex(ResultAddr)}
         |_halt:
         |    bra     _halt
         |
         |_irq_handler:
         |    | (1) the HANDLER runs on the INTERRUPT stack, 8 bytes down
         |    move.l  %a7, %d0
         |    cmp.l   #${hex(IspBase - 8)}, %d0
         |    bne     _f_h_a7
         |    | (2) M is CLEARED and the mask was raised to the interrupt level (1)
         |    move.w  %sr, %d0
         |    andi.w  #0xff00, %d0
         |    cmp.w   #0x2100, %d0
         |    bne     _f_h_sr
         |    | (3) the frame on the INTERRUPT stack is the THROWAWAY, format $$1
         |    move.w  %a7@(6), %d0
         |    cmp.w   #${Fmt1Word}, %d0
         |    bne     _f_h_fmt1
         |    | (4) its SR field is the PRE-interrupt SR (S=1, M=1, mask 0)
         |    move.w  %a7@(0), %d0
         |    andi.w  #0xff00, %d0
         |    cmp.w   #0x3000, %d0
         |    bne     _f_h_sr1
         |    | (5) the MASTER stack moved down by exactly 8
         |    movec   %msp, %d0
         |    cmp.l   #${hex(MspBase - 8)}, %d0
         |    bne     _f_h_msp
         |    movea.l %d0, %a0
         |    | (6) and the frame there is the REAL one, format $$0
         |    move.w  %a0@(6), %d0
         |    cmp.w   #${Fmt0Word}, %d0
         |    bne     _f_h_fmt0
         |    | (7) with the same pre-interrupt SR
         |    move.w  %a0@(0), %d0
         |    andi.w  #0xff00, %d0
         |    cmp.w   #0x3000, %d0
         |    bne     _f_h_sr0
         |    | (8) both frames carry the SAME saved PC
         |    move.l  %a0@(2), %d0
         |    move.l  %a7@(2), %d1
         |    cmp.l   %d0, %d1
         |    bne     _f_h_pcmatch
         |    | (9) which is EVEN and inside the spin loop
         |    btst    #0, %d0
         |    bne     _f_h_pcodd
         |    cmp.l   #_spin, %d0
         |    blt     _f_h_pcrange
         |    cmp.l   #_spin_end, %d0
         |    bgt     _f_h_pcrange
         |    moveq   #1, %d6
         |    rte
         |""".stripMargin

    prologue(mmu) + body + failCodes(Seq(
      "_f_m_flip"     -> 0xBAD00001L,
      "_f_post_a7"    -> 0xBAD00010L,
      "_f_post_msp"   -> 0xBAD00011L,
      "_f_post_isp"   -> 0xBAD00012L,
      "_f_post_sr"    -> 0xBAD00013L,
      "_f_h_a7"       -> 0xBAD00020L,
      "_f_h_sr"       -> 0xBAD00021L,
      "_f_h_fmt1"     -> 0xBAD00022L,
      "_f_h_sr1"      -> 0xBAD00023L,
      "_f_h_msp"      -> 0xBAD00024L,
      "_f_h_fmt0"     -> 0xBAD00025L,
      "_f_h_sr0"      -> 0xBAD00026L,
      "_f_h_pcmatch"  -> 0xBAD00027L,
      "_f_h_pcodd"    -> 0xBAD00028L,
      "_f_h_pcrange"  -> 0xBAD00029L), mmu)
  }

  test("T1: one M=1 interrupt stacks format-$0 on the MASTER stack and format-$1 on the INTERRUPT stack, and RTE returns both exactly", VerilatorTest) {
    runScenario("m1_throwaway_single", t1Src(mmu = false), mode = "once", auditFrames = true)
  }

  // ══════════════════════════════════════════════════════════════════════════════════
  // The balance loop -- the direct analogue of Part 134's "A7 balanced across 761
  // entries", but for BOTH banks. Every iteration re-asserts A7 == MSP_BASE,
  // MOVEC %msp == MSP_BASE, MOVEC %isp == ISP_BASE and SR == {S=1, M=1, mask 0}.
  // ══════════════════════════════════════════════════════════════════════════════════
  private def balanceLoop(handler: String, mmu: Boolean = false, m1: Boolean = true): String =
    prologue(mmu, m1) +
    s"""
       |_outer:
       |    addq.l  #1, %d7                    | architectural liveness counter
       |    move.w  #0x1234, ${hex(Scratch)}   | per-iteration anchor for the sweep
       |    move.l  %a7, %d0
       |    cmp.l   #${hex(if (m1) MspBase else IspBase)}, %d0
       |    bne     _f_bal_a7
       |    movec   %msp, %d0
       |    cmp.l   #${hex(MspBase)}, %d0
       |    bne     _f_bal_msp
       |    movec   %isp, %d0
       |    cmp.l   #${hex(IspBase)}, %d0
       |    bne     _f_bal_isp
       |    move.w  %sr, %d0
       |    andi.w  #0xff00, %d0
       |    cmp.w   #${if (m1) "0x3000" else "0x2000"}, %d0
       |    bne     _f_bal_sr
       |    | a little instruction variety so the arrival phase sees different macros
       |    move.l  #0x11223344, %d1
       |    add.l   %d1, %d2
       |    lea     ${hex(Scratch)}, %a1
       |    move.w  %a1@, %d3
       |    bra     _outer
       |
       |$handler
       |""".stripMargin +
    failCodes(Seq(
      "_f_m_flip"   -> 0xBAD00001L,
      "_f_bal_a7"   -> 0xBAD00030L,
      "_f_bal_msp"  -> 0xBAD00031L,
      "_f_bal_isp"  -> 0xBAD00032L,
      "_f_bal_sr"   -> 0xBAD00033L,
      "_f_hh_a7"    -> 0xBAD00040L,
      "_f_hh_msp"   -> 0xBAD00041L,
      "_f_hh_fmt1"  -> 0xBAD00042L,
      "_f_hh_fmt0"  -> 0xBAD00043L,
      "_f_hh_isp"   -> 0xBAD00044L,
      "_f_hh_bal"   -> 0xBAD00045L,
      "_f_ccr"      -> 0xBAD00050L), mmu)

  private val bareHandler =
    """
      |_irq_handler:
      |    rte
      |""".stripMargin

  test("T2: an M=1 interrupt STORM leaves MSP and ISP each at their exact entry value, every iteration", VerilatorTest) {
    // The loop runs forever and never writes a PASS word; a violation writes a fail
    // code. `expectResult = 0` therefore means "no guest check ever failed".
    runScenario("m1_throwaway_storm", balanceLoop(bareHandler), mode = "storm",
                auditFrames = true, pulses = 700, minIrqEntries = 100,
                minIterations = 200, expectResult = 0L)
  }

  test("T3: deterministic per-cycle IRQ-arrival enumeration under M=1", VerilatorTest) {
    runScenario("m1_throwaway_sweep", balanceLoop(bareHandler), mode = "sweep",
                auditFrames = true, sweepPhases = 192, sweepPasses = 2,
                minIrqEntries = 300, minIterations = 200, expectResult = 0L)
  }

  // ══════════════════════════════════════════════════════════════════════════════════
  // T4 -- the handler checks the entry state on EVERY interrupt, not just once. This
  // catches a drift that would self-correct before the mainline loop top.
  // ══════════════════════════════════════════════════════════════════════════════════
  // HANDLER REGISTER DISCIPLINE (learned the hard way -- the first revision of this
  // handler used %d0/%a0 and produced a SPURIOUS `_f_bal_msp`): a handler must leave
  // %d0-%d3, %d5, %d7 and %a1 exactly as it found them on the SUCCESS path, because an
  // interrupt can land BETWEEN a mainline check's `movec`/`move` and its `cmp`/`bne`, and
  // a clobbered %d0 then fails a check that never had anything wrong with it. %d4, %d6
  // and %a0 are the handler scratch set; %d0 is written ONLY on a failure path, to carry
  // the observed value into the fail-code trailer.
  private val checkingHandler =
    s"""
       |_irq_handler:
       |    move.l  %a7, %d6
       |    cmp.l   #${hex(IspBase - 8)}, %d6
       |    beq     1f
       |    move.l  %d6, %d0
       |    bra     _f_hh_a7
       |1:  move.w  %a7@(6), %d6
       |    cmp.w   #${Fmt1Word}, %d6
       |    beq     2f
       |    move.l  %d6, %d0
       |    bra     _f_hh_fmt1
       |2:  movec   %msp, %d6
       |    cmp.l   #${hex(MspBase - 8)}, %d6
       |    beq     3f
       |    move.l  %d6, %d0
       |    bra     _f_hh_msp
       |3:  movea.l %d6, %a0
       |    move.w  %a0@(6), %d6
       |    cmp.w   #${Fmt0Word}, %d6
       |    beq     4f
       |    move.l  %d6, %d0
       |    bra     _f_hh_fmt0
       |4:  rte
       |""".stripMargin

  test("T4: every M=1 interrupt entry checked from inside the handler, under the storm", VerilatorTest) {
    // This handler does not push anything, so the strict frame audit still holds.
    runScenario("m1_throwaway_checked", balanceLoop(checkingHandler), mode = "storm",
                auditFrames = true, pulses = 700, minIrqEntries = 100,
                minIterations = 200, expectResult = 0L)
  }

  // ══════════════════════════════════════════════════════════════════════════════════
  // T5 -- NESTING. The handler re-sets M=1 (so A7 re-banks to the master stack, on top
  // of the frame the entry just pushed there) and drops the mask, so a SECOND level-1
  // interrupt nests -- and that nested entry is ITSELF an M=1 entry, i.e. a nested
  // THROWAWAY: format-$0 at MSP-16, format-$1 at ISP-16. This is the deepest shape the
  // path has.
  // ══════════════════════════════════════════════════════════════════════════════════
  // NESTING-SAFE STACK-BALANCE CHECK. The first revision saved the entry A7 in %d4 and
  // compared against it -- which a NESTED invocation of the same handler overwrites, so it
  // reported a spurious imbalance. A marker longword pushed on the handler's own stack is
  // correct at every nesting depth: whatever the nested entries push and pop, this
  // invocation must find its OWN marker back on top.
  private val nestingHandler =
    s"""
       |_irq_handler:
       |    move.l  #0xA5A5A5A5, %sp@-        | entry state: ISP bank, M=0, mask=1
       |    ori.w   #0x1000, %sr              | M=1 -> A7 re-banks to the MSP
       |    andi.w  #0xf8ff, %sr              | mask 0 -> a nested level-1 IRQ may land here
       |    nop
       |    nop
       |    nop
       |    nop
       |    ori.w   #0x0700, %sr              | mask 7 -> close the nesting window
       |    andi.w  #0xefff, %sr              | M=0 -> back onto the ISP
       |    move.l  %sp@+, %d6
       |    cmp.l   #0xA5A5A5A5, %d6
       |    beq     1f
       |    move.l  %d6, %d0
       |    bra     _f_hh_bal
       |1:  rte
       |""".stripMargin

  test("T5: nested M=1 interrupts (a throwaway inside a throwaway) unwind both stacks exactly", VerilatorTest) {
    runScenario("m1_throwaway_nested", balanceLoop(nestingHandler), mode = "storm",
                auditFrames = false, pulses = 700, minIrqEntries = 100,
                minIterations = 100, expectResult = 0L)
  }

  // ══════════════════════════════════════════════════════════════════════════════════
  // T6 -- a LONG, stack-heavy, NESTING handler (MOVEM save/restore, LINK/UNLK, a nested
  // subroutine call, the mask reopened mid-handler). Part 134 §8 named "the IRQ handler
  // is a bare RTE" as its second coverage gap; the Mac ROM's handler is long and nests.
  // ══════════════════════════════════════════════════════════════════════════════════
  private val longHandler =
    s"""
       |_irq_handler:
       |    move.l  #0xA5A5A5A5, %sp@-        | nesting-safe stack-balance marker
       |    movem.l %d0-%d3/%a0-%a2, %sp@-
       |    link    %a6, #-32
       |    andi.w  #0xf8ff, %sr              | open the nesting window mid-handler
       |    bsr     _sub
       |    move.l  #0x0BADF00D, %a6@(-8)
       |    move.l  %a6@(-8), %d3
       |    bsr     _sub
       |    ori.w   #0x0700, %sr              | close it again
       |    unlk    %a6
       |    movem.l %sp@+, %d0-%d3/%a0-%a2
       |    move.l  %sp@+, %d6
       |    cmp.l   #0xA5A5A5A5, %d6
       |    beq     1f
       |    move.l  %d6, %d0
       |    bra     _f_hh_isp
       |1:  rte
       |
       |_sub:
       |    move.l  %d0, %sp@-
       |    move.l  %d1, %sp@-
       |    moveq   #7, %d0
       |    moveq   #9, %d1
       |    add.l   %d1, %d0
       |    move.l  %sp@+, %d1
       |    move.l  %sp@+, %d0
       |    rts
       |""".stripMargin

  test("T6: a long, nesting, stack-heavy handler under an M=1 IRQ storm", VerilatorTest) {
    // NOTE: the nested entries in this scenario are taken with M=0 (the handler does not
    // re-set M), so their frames are plain format-$0 on the ISP -- the deliberate
    // contrast with T5, where the nested entry is itself a throwaway.
    // The handler is long and nests, so a single mainline iteration spans many
    // interrupts -- the liveness bar is on ENTRIES (80), with only a token bar on loop
    // iterations. A measured run does ~120 entries in ~32 iterations.
    runScenario("m1_throwaway_longhandler", balanceLoop(longHandler), mode = "storm",
                auditFrames = false, pulses = 700, minIrqEntries = 80,
                minIterations = 15, expectResult = 0L)
  }

  // ══════════════════════════════════════════════════════════════════════════════════
  // T9a / T9b -- A7 CHANGED BY THE INSTRUCTION IMMEDIATELY BEFORE `RTE`.
  //
  // `ExceptionUnit.scala:727-733` carries an explicit, still-open caveat on the
  // `committedA7In` PRF readback that feeds `ss.writeA7`:
  //
  //   "the ACTIVE bank tracks A7 with ~1-2 cycle lag. This is invisible to real
  //    consumers: the exc FSM reads the bank only after E_DRAIN (settled) ... A RAPID M
  //    re-toggle (M=0->1->0 within the settle window) is NOT validated here ... Add a
  //    directed test for that BEFORE Slice B (interrupt throwaway frame) relies on
  //    cross-toggle preservation."
  //
  // That directed test was never written. `R_DRAIN` waits on `sqDrained && dcQuiesced`
  // -- both STORE-side predicates -- before it latches `frameBase` from the live bank.
  // An A7 update that is REGISTER-ONLY (`subq.l #2,%sp`, `lea %sp@(2),%sp`) puts nothing
  // in the store queue at all, so `sqDrained` is already true and `R_DRAIN` can fall
  // through in a single cycle -- potentially reading a bank shadow that still holds the
  // PRE-update A7. A stale-by-one-`subq` shadow is a stack pointer off by EXACTLY the
  // size of that last adjustment, which for a word-sized one is EXACTLY 2 BYTES: the
  // p133 silicon signature. T9a uses the register-only shape (no store queue involved at
  // all), T9b the memory push/pop shape.
  // ══════════════════════════════════════════════════════════════════════════════════
  private val a7TouchRegHandler =
    """
      |_irq_handler:
      |    subq.l  #2, %sp
      |    addq.l  #2, %sp                 | A7 written by the instruction just before RTE
      |    rte
      |""".stripMargin

  private val a7TouchMemHandler =
    """
      |_irq_handler:
      |    move.w  %d0, %sp@-
      |    move.w  %sp@+, %d0              | A7 written (via memory) just before RTE
      |    rte
      |""".stripMargin

  test("T9a: M=1 RTE whose immediately-preceding instruction changed A7 by 2 (register-only)", VerilatorTest) {
    runScenario("m1_throwaway_a7touch_reg", balanceLoop(a7TouchRegHandler), mode = "storm",
                auditFrames = true, pulses = 700, minIrqEntries = 100,
                minIterations = 200, expectResult = 0L)
  }

  test("T9b: M=1 RTE whose immediately-preceding instruction changed A7 by 2 (memory push/pop)", VerilatorTest) {
    // The word push lands at ISP-10, one word below the frame, so the strict
    // frame-address audit is relaxed here.
    runScenario("m1_throwaway_a7touch_mem", balanceLoop(a7TouchMemHandler), mode = "storm",
                auditFrames = false, pulses = 700, minIrqEntries = 100,
                minIterations = 200, expectResult = 0L)
  }

  // ══════════════════════════════════════════════════════════════════════════════════
  // T10 -- the ENTRY-side mirror of T9: the MAINLINE constantly moves A7 by 2 in tight
  // pairs, so an interrupt can be recognised in the window immediately after an A7
  // update. If `E_DRAIN` released before the bank shadow settled, the format-$0 frame
  // would be stacked from the PRE-update A7 while the architectural A7 is the
  // POST-update one -- and the `RTE` would then return A7 exactly 2 bytes off, which the
  // loop-top balance check catches on the very next iteration.
  // ══════════════════════════════════════════════════════════════════════════════════
  private val a7ChurnExtra =
    """
      |    subq.l  #2, %sp
      |    addq.l  #2, %sp
      |    move.w  %d0, %sp@-
      |    move.w  %sp@+, %d0
      |    pea     0x00000010
      |    addq.l  #4, %sp
      |""".stripMargin

  private def a7ChurnLoop(handler: String): String =
    balanceLoop(handler).replace(
      "    | a little instruction variety so the arrival phase sees different macros",
      a7ChurnExtra + "    | a little instruction variety so the arrival phase sees different macros")

  test("T10: M=1 entry taken while the mainline is moving A7 by 2 in tight pairs", VerilatorTest) {
    runScenario("m1_throwaway_a7churn", a7ChurnLoop(bareHandler), mode = "storm",
                auditFrames = false, pulses = 700, minIrqEntries = 100,
                minIterations = 200, expectResult = 0L)
  }

  test("T11: the same A7 churn under the deterministic per-cycle arrival enumeration", VerilatorTest) {
    runScenario("m1_throwaway_a7churn_sweep", a7ChurnLoop(bareHandler), mode = "sweep",
                auditFrames = false, sweepPhases = 192, sweepPasses = 2,
                minIrqEntries = 300, minIterations = 200, expectResult = 0L)
  }

  // ══════════════════════════════════════════════════════════════════════════════════
  // T12 .. T15 -- the CCR-across-the-interrupt discriminator, and its M=0 CONTROL.
  //
  // The first revision of T9b failed with `_f_bal_sr` while every architectural value it
  // could see was correct. The mainline's checks are all `cmp` + `bne` PAIRS, and an
  // interrupt can be recognised between the two: the entry stacks the CCR the `cmp`
  // produced, the handler then clobbers the CCR, and `RTE` has to put it back before the
  // `bne` re-executes. Handlers that never touch the flags (a bare `rte`; `subq/addq` to
  // an ADDRESS register, which on the 68000 family does not set condition codes at all)
  // cannot exercise that; handlers that do, can.
  //
  // T12 isolates exactly that variable: a handler that ONLY disturbs the flags -- no
  // memory access, no A7 change, no MOVEC. T13 is its M=0 CONTROL: identical handler,
  // mainline never sets M, so entry takes the plain single-frame format-$0 path. If T12
  // fails and T13 passes, the defect is specific to the M=1 / format-$1 throwaway RTE.
  // If BOTH fail, it is a general RTE CCR-restore defect that Part 134 could not see
  // because every handler it ran was a bare `RTE`. T14/T15 do the same pair for the
  // memory-touching handler of T9b.
  // ══════════════════════════════════════════════════════════════════════════════════
  // The FIRST revision of this handler was `tst.l %d6` with %d6 == 0, which leaves
  // {N=0,Z=1,V=0,C=0} -- byte-for-byte what the mainline's own successful `cmp` leaves,
  // so it could not detect a lost CCR at all and passed vacuously. It now forces the
  // OPPOSITE flags (%d6 = -1 -> N=1, Z=0), which is what a broken restore would leave and
  // what makes the mainline's `beq`-shaped `bne` go the wrong way.
  private val flagOnlyHandler =
    """
      |_irq_handler:
      |    moveq   #-1, %d6
      |    tst.l   %d6                       | forces N=1, Z=0; no memory, no A7, no MOVEC
      |    rte
      |""".stripMargin

  test("T12: M=1, handler disturbs ONLY the condition codes", VerilatorTest) {
    runScenario("m1_ccr_handler_m1", balanceLoop(flagOnlyHandler), mode = "storm",
                auditFrames = true, pulses = 700, minIrqEntries = 100,
                minIterations = 200, expectResult = 0L)
  }

  test("T13: M=0 CONTROL -- same flag-disturbing handler, single format-$0 frame", VerilatorTest) {
    // 2x the pulses of the M=1 arm, deliberately: a control that clears the bar only
    // because it ran less is worth nothing.
    runScenario("m0_ccr_handler_control", balanceLoop(flagOnlyHandler, m1 = false), mode = "storm",
                auditFrames = false, pulses = 1400, minIrqEntries = 200,
                minIterations = 400, expectResult = 0L)
  }

  test("T14: M=1, handler pushes and pops a word (T9b's shape, register-safe)", VerilatorTest) {
    runScenario("m1_pushpop_handler_m1", balanceLoop(a7TouchMemHandler), mode = "storm",
                auditFrames = false, pulses = 700, minIrqEntries = 100,
                minIterations = 200, expectResult = 0L)
  }

  // ══════════════════════════════════════════════════════════════════════════════════
  // T16 / T17 -- the CCR-survival probe that reports the WRONG VALUE, not just a wrong
  // branch. A branch-outcome failure only tells you "the flags were wrong"; this reads
  // the condition codes back architecturally with MOVE-from-SR and stashes what it
  // actually saw, so the failure message carries the corrupted CCR itself.
  //
  //   moveq #0,%d1 ; cmp.l #0,%d1     -> {N=0, Z=1, V=0, C=0}, i.e. CCR bits = 0x04
  //   nop x4                          -- a wide window for the interrupt to land in
  //   move.w %sr,%d0 ; andi.w #0x0f   -> the NZVC the interrupt+RTE actually left behind
  //
  // MOVE-from-SR does not itself alter the CCR, so anything other than 0x04 here is a
  // condition-code state that did not survive the interrupt. The handler deliberately
  // forces the opposite ({N=1, Z=0} = 0x08), so a "handler's flags leaked through the
  // RTE" defect reads back as exactly 0x08.
  // ══════════════════════════════════════════════════════════════════════════════════
  private val ccrProbeExtra =
    """
      |    moveq   #0, %d1
      |    cmp.l   #0, %d1
      |    nop
      |    nop
      |    nop
      |    nop
      |    move.w  %sr, %d0
      |    andi.w  #0x000f, %d0
      |    cmp.w   #0x0004, %d0
      |    bne     _f_ccr
      |""".stripMargin

  private def ccrProbeLoop(handler: String, m1: Boolean): String =
    balanceLoop(handler, m1 = m1).replace(
      "    | a little instruction variety so the arrival phase sees different macros",
      ccrProbeExtra + "    | a little instruction variety so the arrival phase sees different macros")

  test("T16: M=1 -- do the condition codes survive an interrupt whose handler disturbs them?", VerilatorTest) {
    runScenario("m1_ccr_survival", ccrProbeLoop(flagOnlyHandler, m1 = true), mode = "storm",
                auditFrames = true, pulses = 700, minIrqEntries = 100,
                minIterations = 200, expectResult = 0L)
  }

  test("T17: M=0 CONTROL -- the same CCR-survival probe on the single-frame path", VerilatorTest) {
    runScenario("m0_ccr_survival_control", ccrProbeLoop(flagOnlyHandler, m1 = false), mode = "storm",
                auditFrames = false, pulses = 1400, minIrqEntries = 200,
                minIterations = 400, expectResult = 0L)
  }

  test("T15: M=0 CONTROL -- same push/pop handler, single format-$0 frame", VerilatorTest) {
    runScenario("m0_pushpop_handler_control", balanceLoop(a7TouchMemHandler, m1 = false), mode = "storm",
                auditFrames = false, pulses = 1400, minIrqEntries = 200,
                minIterations = 400, expectResult = 0L)
  }

  // ══════════════════════════════════════════════════════════════════════════════════
  // T7 / T8 -- the MMU ON, 8 KB pages, TTRs disabled (a REAL table walk on every first
  // touch), which is the p133 silicon posture (TC = 0x0000c000). Part 134 §8 called
  // MMU-off "the single biggest coverage gap in the negative".
  // ══════════════════════════════════════════════════════════════════════════════════
  test("T7: the full single-interrupt frame audit with the MMU ON and 8 KB pages", VerilatorTest) {
    runScenario("m1_throwaway_single_mmu", t1Src(mmu = true), mode = "once",
                auditFrames = false, mmuOn = true)
  }

  test("T8: M=1 interrupt storm with the MMU ON and 8 KB pages", VerilatorTest) {
    runScenario("m1_throwaway_storm_mmu", balanceLoop(bareHandler, mmu = true), mode = "storm",
                auditFrames = false, pulses = 700, minIrqEntries = 60,
                minIterations = 60, expectResult = 0L, mmuOn = true)
  }
}
