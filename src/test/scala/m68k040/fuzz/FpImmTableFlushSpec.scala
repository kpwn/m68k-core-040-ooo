package m68k040.fuzz

import m68k040.M68kSim
import m68k040.VerilatorTest
import m68k040.oracle.ProgramAssembler
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

/** FpImmTableFlushSpec -- directed test for the FP wide-immediate SIDE TABLE
  * (docs/PLAN_routing_congestion_architectural.md item 3; DecodeStage `fpImmTable`,
  * DivEuPlugin `fpS1Imm` capture, FpImmTableService).
  *
  * The 80-bit `F<op>.<fmt> #imm,FPn` immediate no longer rides the uop record; a
  * tag in `imm[FP_IMM_TAG_W-1:0]` names an entry of a FP_IMM_TABLE_DEPTH-deep table that DecodeStage writes
  * and DivEu reads+frees. The two things that can go wrong are (a) an FP op reading
  * a freed/overwritten slot and (b) a leaked entry (the table fills and decode stalls
  * forever). Both hinge on RECLAMATION ACROSS SQUASHES, which with the two-tier
  * reschedule is per flush DOMAIN, not "clear all". This program therefore drives:
  *   - bursts of FP_IMM_TABLE_DEPTH+2 dependent FP-immediate ops (FDIV.L #1 / FADD.L #1
  *     pairs, all exact, the FDIVs on the ~73-cycle iterative lane) -- more than the table
  *     holds, so the decode-side FULL stall is exercised on every iteration;
  *   - a data-dependent `bcs` on a 32-bit constant's bits (never repeats, so the
  *     predictor mispredicts often) with DIFFERENT FP-immediate ops on each path
  *     (FADD.L / FSUB.L / FADD.S / FADD.D) and an FADD.X at the join -- every format
  *     the side channel carries -- so the ROB flush squashes wrong-path FP-immediate
  *     uops sitting in the decode queue, the rename skid and the IQ;
  *   - TRAP #0 (exception-sequencer squash, `excActive`) with FP-immediate ops before
  *     and after;
  * and finally checks FP0/FP1/FP2 against the exact expected integers. The RTL carries
  * sim asserts for the invariants (an entry is valid + backend-owned when DivEu
  * consumes it; a popped uop's tag is allocated; never allocate while full), so a
  * wrong-path-freed-slot bug fails HERE either by assert or by a wrong value.
  *
  * The test additionally REQUIRES that the interesting events happened: the table
  * stalled decode at least once, and ROB flushes fired with FP-immediate entries live
  * on both sides of the decode->rename boundary (the case a naive "clear all on flush"
  * gets wrong in both directions). */
class FpImmTableFlushSpec extends AnyFunSuite {
  lazy val compiled = M68kSim().withVerilator.compile(new FuzzCoreDut)

  private val Pattern = 0x9E3779B9L      // bcs direction = successive LSBs of this
  private val Iters   = 32
  private val Depth   = m68k040.Global.FP_IMM_TABLE_DEPTH
  private val BurstPairs = Depth / 2 + 1  // 2*BurstPairs FP-immediate uops per burst > Depth
  private def takenAt(i: Int): Boolean = ((Pattern >> i) & 1L) == 1L
  // FP0: BurstPairs x (FDIV.L #1 (exact identity) ; FADD.L #1) + FADD.X #1.0 per
  // iteration, then 4x#7, 4x#11, 4x#13 around the traps.
  private val Exp0: Long = Iters * (BurstPairs + 1L) + 4 * 7 + 4 * 11 + 4 * 13
  // FP1: +3 on the fall-through path, -5 on the taken path (FSUB.L #5).
  private val Exp1: Long = (0 until Iters).map(i => if (takenAt(i)) -5L else 3L).sum
  // FP2: +2.0 (FADD.S) on the fall-through path, +4.0 (FADD.D) on the taken path.
  private val Exp2: Long = (0 until Iters).map(i => if (takenAt(i)) 4L else 2L).sum

  private def hex32(v: Long): String = f"0x${v & 0xffffffffL}%08x"

  private val program: String = {
    // BurstPairs x (FDIV.L #1,FP0 ; FADD.L #1,FP0): every FDIV is EXACT (x/1) but runs on
    // the ITERATIVE lane (FpDivSqrtCore.WorstCaseLatency = 73), and everything chains on
    // FP0, so the burst's 2*BurstPairs (> table depth) FP-immediate uops sit in the IQ for
    // hundreds of cycles with their side-table entries live: the table fills and decode
    // stalls. (A burst of plain FADDs -- FixedLatency 13 -- drains as fast as the frontend
    // fills it; measured maxLive 7 of 8, zero stall cycles.)
    val burst = (1 to BurstPairs).map(_ =>
      "    .short  0xF23C, 0x4020, 0x0000, 0x0001    | FDIV.L #1,FP0\n" +
      "    .short  0xF23C, 0x4022, 0x0000, 0x0001    | FADD.L #1,FP0").mkString("\n")
    def adds(imm: Int, n: Int) =
      (1 to n).map(_ => f"    .short  0xF23C, 0x4022, 0x0000, 0x${imm}%04X    | FADD.L #$imm,FP0").mkString("\n")
    s"""
    .text
    .org 0
    .equ PASS_SENT, 0xFFFF0000
_start:
    lea     0x00010000, %a7
    move.l  #_trap0, 0x00000080         | vector 32 = TRAP #0
    move.l  #_fline, 0x0000002C         | vector 11 = F-line (must never fire)
    .short  0xF23C, 0x4000, 0x0000, 0x0000    | FMOVE.L #0,FP0
    .short  0xF23C, 0x4080, 0x0000, 0x0000    | FMOVE.L #0,FP1
    .short  0xF23C, 0x4100, 0x0000, 0x0000    | FMOVE.L #0,FP2
    move.l  #${hex32(Pattern)}, %d7
    moveq   #${Iters - 1}, %d6
_loop:
$burst
    lsr.l   #1, %d7
    bcs.s   _taken
    .short  0xF23C, 0x40A2, 0x0000, 0x0003    | FADD.L #3,FP1
    .short  0xF23C, 0x4522, 0x4000, 0x0000    | FADD.S #2.0,FP2
    bra.s   _join
_taken:
    .short  0xF23C, 0x40A8, 0x0000, 0x0005    | FSUB.L #5,FP1
    .short  0xF23C, 0x5522, 0x4010, 0x0000, 0x0000, 0x0000    | FADD.D #4.0,FP2
_join:
    .short  0xF23C, 0x4822, 0x3FFF, 0x0000, 0x8000, 0x0000, 0x0000, 0x0000    | FADD.X #1.0,FP0
    dbf     %d6, _loop
    | Exception-sequencer squash (excActive) with FP-immediate uops on both sides.
${adds(7, 4)}
    trap    #0
${adds(11, 4)}
    trap    #0
${adds(13, 4)}
    .short  0xF200, 0x6000                    | FMOVE.L FP0,D0
    cmp.l   #${hex32(Exp0)}, %d0
    bne     _fail0
    .short  0xF201, 0x6080                    | FMOVE.L FP1,D1
    cmp.l   #${hex32(Exp1)}, %d1
    bne     _fail1
    .short  0xF202, 0x6100                    | FMOVE.L FP2,D2
    cmp.l   #${hex32(Exp2)}, %d2
    bne     _fail2
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, (%a1)
_halt:
    bra     _halt
_fail0:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0000, (%a1)
    bra     _halt
_fail1:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0001, (%a1)
    bra     _halt
_fail2:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0002, (%a1)
    bra     _halt
_trap0:
    rte
_fline:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F01, (%a1)
    bra     _halt
"""
  }

  for (cached <- Seq(false, true))
  test(s"FP immediates survive branch-mispredict and exception squashes with a full side table (${if (cached) "I/D caches ON, copyback" else "caches OFF"})", VerilatorTest) {
    val image = ProgramAssembler.assemble(program, PortedTestRunner.loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble: ${e.reason}")
    }

    compiled.doSim(s"fp_imm_table_flush_${if (cached) "cached" else "uncached"}", 1) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      FuzzDut.attachProgramWithBusErrors(dut.icache.logic.axi, cd, PortedTestRunner.loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd, injectBusErrors = true)
      for (i <- image.bytes.indices) dmem.mem.write(PortedTestRunner.loadAddr + i, image.bytes(i).toByte)
      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
      dut.intCtrl.logic.iplIn #= 0
      dut.intCtrl.logic.iackAvec #= false; dut.intCtrl.logic.iackVector #= 0
      dut.fa.logic.redirect.valid #= false; dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false
      dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.usp #= 0L
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr #= 15
      dut.wire.logic.seedData #= BigInt(0x00100000L)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()
      if (cached) {
        // PortedTestRunner's ForceCacheableCopyback posture: identity/copyback TTRs over
        // the low 2 GiB on both sides, inhibited data TTR over the high half (sentinel),
        // CACR = DE|IE. With the I-cache on, the frontend runs ~1 instr/cycle instead of
        // the ~10-cycle-per-fetch uncached cadence, so far more wrong-path FP-immediate
        // uops are in the FRONTEND domain when a squash lands.
        dut.ctrl.logic.itt0 #= CachePosture.LowHalfCopybackTtr
        dut.ctrl.logic.itt1 #= 0
        dut.ctrl.logic.dtt0 #= CachePosture.LowHalfCopybackTtr
        dut.ctrl.logic.dtt1 #= CachePosture.HighHalfInhibitedTtr
        dut.rob.logic.exc.ss.cacr #= CachePosture.CacrDataAndInstructionEnable
        dut.ctrl.logic.mmuEnable #= true
        cd.waitSampling()
      }
      dut.fa.logic.redirect.valid #= true
      dut.fa.logic.redirect.payload #= PortedTestRunner.loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= false

      for (i <- 0 until 4) dmem.mem.write(PortedTestRunner.SentinelAddr + i, 0)
      val tab = dut.dec.logic.fpImmTable
      val rl  = dut.rob.logic
      var cyc = 0L
      var word = 0L
      var stallCycles = 0L
      var allocs = 0L
      var flushes = 0L            // ROB doFlush pulses (Tier-2 / exception)
      var earlyFires = 0L         // Tier-1 frontend-only redirects
      var flushBothDomains = 0L   // a squash pulse with entries live on BOTH sides of the boundary
      var maxLive = 0
      while (word == 0 && cyc < 600000) {
        cd.waitSampling(); cyc += 1
        val v = tab.valid.toInt; val b = tab.backend.toInt
        val live = Integer.bitCount(v)
        if (sys.env.contains("FPIMM_TRACE") && cyc < 3000 && (tab.allocFire.toBoolean || dut.dec.fpImmFree.valid.toBoolean || tab.stall.toBoolean))
          println(f"[FpImmTrace] cyc=$cyc%5d valid=0x$v%02x backend=0x$b%02x live=$live alloc=${tab.allocFire.toBoolean} free=${dut.dec.fpImmFree.valid.toBoolean} stall=${tab.stall.toBoolean} doFlush=${rl.doFlushReg.toBoolean} early=${rl.earlyFire.toBoolean}")
        if (live > maxLive) maxLive = live
        if (tab.stall.toBoolean) stallCycles += 1
        if (tab.allocFire.toBoolean) allocs += 1
        val doFlush = rl.doFlushReg.toBoolean
        val early   = rl.earlyFire.toBoolean
        if (doFlush) flushes += 1
        if (early) earlyFires += 1
        if ((doFlush || early) && (v & b) != 0 && (v & ~b & ((1 << Depth) - 1)) != 0) flushBothDomains += 1
        val bs = (0 until 4).map(i => dmem.mem.read(PortedTestRunner.SentinelAddr + i).toLong & 0xffL)
        word = (bs(0) << 24) | (bs(1) << 16) | (bs(2) << 8) | bs(3)
      }
      println(f"[FpImmTable] sentinel=0x$word%08x after $cyc cycles; allocs=$allocs stallCycles=$stallCycles " +
              f"maxLive=$maxLive doFlush=$flushes earlyFire=$earlyFires squashesWithBothDomainsLive=$flushBothDomains " +
              f"(expected FP0=$Exp0 FP1=$Exp1 FP2=$Exp2)")
      assert(word == PortedTestRunner.PassWord,
        f"program did not pass: sentinel=0x$word%08x (0 = hang/timeout at $cyc cycles; 0xDEAD000n = FPn wrong)")
      // Every FP-immediate uop the program retires allocates exactly once; wrong-path
      // ones allocate too (then get reclaimed), so this is a lower bound.
      val retiredFpImm = 3 + Iters * (2 * BurstPairs + 2 + 1) + 12
      assert(allocs >= retiredFpImm, s"allocations $allocs < retired FP-immediate uops $retiredFpImm")
      assert(maxLive == Depth, s"the table never filled (maxLive=$maxLive of $Depth) -- the ${2 * BurstPairs}-deep dependent burst should fill every entry")
      assert(stallCycles > 0, "the FULL stall never engaged -- the directed burst did not exercise it")
      assert(flushes > 0, "no ROB flush fired -- the mispredict/exception squash path was not exercised")
      assert(flushBothDomains > 0,
        "no squash pulse saw FP-immediate entries live on BOTH sides of the decode->rename boundary -- " +
        "the per-domain reclamation was not exercised")
      // Nothing may leak: once the program halts (spinning on `bra _halt`), no entry can
      // remain allocated -- every FP-immediate uop either retired (consumed) or was squashed.
      cd.waitSampling(200)
      assert(tab.valid.toInt == 0, f"leaked side-table entries after halt: valid=0x${tab.valid.toInt}%02x backend=0x${tab.backend.toInt}%02x")
    }
  }
}
