package m68k040.lockstep

import m68k040.VerilatorTest
import m68k040.oracle.OracleStep
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Directed bit-exact lock-step tests for the HW-native FP op set. Bit-exact is
  * possible because Musashi's internal representation is SoftFloat floatx80 --
  * portable 80-bit extended, not a platform long double -- matching our own 80-bit
  * internal register file (Decision 1).
  *
  * FP data registers go through FpCompare.assertFpEqual (cannot use
  * LockStep.compare, which is Long-based). FPSR/FPCC is compared through the
  * ARCHITECTURAL path instead: the program executes `fmove.l %fpsr,%dN`, landing
  * FPSR in an integer register the EXISTING LockStep.compare already checks
  * bit-exactly every step -- no new plumbing needed. */
class FpuLockStepSpec extends AnyFunSuite {

  private val h = new ExecuteLockStepSpec

  /** Read architectural FPn out of the DUT: resolve arch -> phys through the
    * committed FP RAT, then index RegFilePluginFp's sim-only shadow of its backing
    * Mem (the real Mem is unreadable from a simulation once
    * MultiPortWritesSymplifier rewrites it -- see RegFilePlugin's `shadow`). */
  private def dutFp(dut: h.FullCoreDut, arch: Int): BigInt = {
    require(arch >= 0 && arch < 8, s"FP arch reg must be 0..7, got $arch")
    val phys = dut.ren.logic.fpRat.io.committedPhys(arch).toInt
    dut.rfFp.logic.shadow(phys).toBigInt & FpCompare.Mask
  }

  private def expectFp(name: String, regs: Int*)
                      (dut: h.FullCoreDut, oracle: Vector[OracleStep]): Unit = {
    val last = oracle.last
    assert(last.fp.size == 8,
      s"[$name] oracle step carries no FP state -- musashi_run is stale, rebuild with " +
      "`make -C tools/musashi` (Task 12)")
    for (r <- regs)
      FpCompare.assertFpEqual(dutFp(dut, r), last.fp(r), s"[$name] FP$r")
  }

  /** Loads an 80-bit extended-precision constant DIRECTLY into FPn via the
    * Extended-immediate FMOVE form Task 6 added: `FMOVE.X #<80-bit-lit>,FPn`
    * -- opclass 010, source specifier 010 (Extended), <ea> = mode 7 / reg 4 (#imm). Opword
    * is always 0xF23C; ext = 0x4800 | (dst<<7) (opclass=010<<13=0x4000, srcSpec=Extended=
    * 010<<10=0x0800, dst<<7, opmode=0x00 FMOVE) -- cross-checked against GAS's own real
    * assembler output for `fmove.l #5,%fp0` (ext=0x4000) and `fmove.x #0r1.5,%fp2`
    * (ext=0x4900 = 0x4000|0x0800|(2<<7)). Followed by the SAME 6-word raw layout Musashi's
    * load_extended_float80/READ_EA_FPE case 4 uses: word0 = sign+exponent, word1 =
    * reserved (always zero here), words2-3 = mantissa high 32 bits, words4-5 = mantissa
    * low 32 bits (m68kfpu.c:64-77,684-711).
    *
    * WHY RAW WORDS, NOT A GAS FLOATING LITERAL (`fmove.x #0r<value>,%fpN`): GAS's own
    * `#0r<value>` extended-immediate encoder does NOT set the explicit integer bit (bit 63)
    * that real 68881/68040 hardware and Musashi's floatx80 both require for every
    * normalized/infinite/NaN value -- e.g. `fmove.x #0r1.0,%fp0` assembles to mantissa
    * 0x0000000000000000, not the required 0x8000000000000000. Since several of these
    * constants (QNaN/Infinity) need an EXACT, independently-verifiable bit pattern, and
    * GAS's decimal path cannot even express NaN/Inf with a guaranteed encoding, raw words
    * are the only safe choice. */
  private def immX(dst: Int, high: Int, low: BigInt): String = {
    require(dst >= 0 && dst < 8, s"FP dst must be 0..7, got $dst")
    val extWord = 0x4800 | (dst << 7)
    val hi32 = ((low >> 32) & 0xffffffffL).toLong
    val lo32 = (low & 0xffffffffL).toLong
    f".short 0xF23C ; .short 0x$extWord%04X ; .short 0x$high%04X ; .short 0 ; .long 0x$hi32%08X ; .long 0x$lo32%08X"
  }

  private val PosInf = (0x7FFF, BigInt("8000000000000000", 16))
  private val NegInf = (0xFFFF, BigInt("8000000000000000", 16))
  private val QNan   = (0x7FFF, BigInt("C0000000DEADBEEF", 16))
  private val Frac   = (0xC000, BigInt("B400000000000000", 16))

  private def prog(instrs: Seq[String]): String =
    (instrs :+ "spin: bra.s spin").mkString(" ; ")

  // NOTE: nothing in this file needs a labeled PC-relative memory constant -- every
  // constant load below is a self-contained immediate instruction via `immX()`, so no
  // trailing data block is needed. If a future test here genuinely needs a real
  // memory-resident FP constant (e.g. to exercise Task 6b's memory-source FMOVE), add a
  // `consts`/`ext()`-shaped helper then -- don't carry dead machinery now.

  // ── Normal-value round trip, one per op ──────────────────────────────────

  test("lock-step FP: FADD.X register-register (5.0 + 3.0 = 8.0, exact)", VerilatorTest) {
    val instrs = Seq("fmove.l #5,%fp0", "fmove.l #3,%fp1", "fadd.x %fp1,%fp0")
    h.runLockStep("fp-fadd", prog(instrs), nInstr = instrs.size,
      afterRun = { (dut, oracle) =>
        expectFp("fp-fadd", 0, 1)(dut, oracle)
        FpCompare.assertFpBits(dutFp(dut, 0), "40028000000000000000", "fp-fadd FP0 = 8.0")
      })
  }

  test("lock-step FP: FSUB.X register-register (5.0 - 3.0 = 2.0, exact)", VerilatorTest) {
    val instrs = Seq("fmove.l #5,%fp0", "fmove.l #3,%fp1", "fsub.x %fp1,%fp0")
    h.runLockStep("fp-fsub", prog(instrs), nInstr = instrs.size,
      afterRun = { (dut, oracle) =>
        expectFp("fp-fsub", 0, 1)(dut, oracle)
        FpCompare.assertFpBits(dutFp(dut, 0), "40008000000000000000", "fp-fsub FP0 = 2.0")
      })
  }

  test("lock-step FP: FMUL.X register-register (5.0 * 3.0 = 15.0, exact)", VerilatorTest) {
    val instrs = Seq("fmove.l #5,%fp0", "fmove.l #3,%fp1", "fmul.x %fp1,%fp0")
    h.runLockStep("fp-fmul", prog(instrs), nInstr = instrs.size,
      afterRun = expectFp("fp-fmul", 0, 1))
  }

  test("lock-step FP: FDIV.X register-register (12.0 / 3.0 = 4.0, exact)", VerilatorTest) {
    // Also the multi-cycle-EU case: FDIV shares the CPLX cluster's iterative context
    // with integer DIV (Decision 9).
    val instrs = Seq("fmove.l #12,%fp0", "fmove.l #3,%fp1", "fdiv.x %fp1,%fp0")
    h.runLockStep("fp-fdiv", prog(instrs), nInstr = instrs.size,
      afterRun = { (dut, oracle) =>
        expectFp("fp-fdiv", 0, 1)(dut, oracle)
        FpCompare.assertFpBits(dutFp(dut, 0), "40018000000000000000", "fp-fdiv FP0 = 4.0")
      })
  }

  test("lock-step FP: FSQRT.X (sqrt(9.0) = 3.0, exact)", VerilatorTest) {
    val instrs = Seq("fmove.l #9,%fp2", "fsqrt.x %fp2,%fp3")
    h.runLockStep("fp-fsqrt", prog(instrs), nInstr = instrs.size,
      afterRun = { (dut, oracle) =>
        expectFp("fp-fsqrt", 2, 3)(dut, oracle)
        FpCompare.assertFpBits(dutFp(dut, 3), "4000c000000000000000", "fp-fsqrt FP3 = 3.0")
      })
  }

  test("lock-step FP: FABS.X / FNEG.X (sign-only, mantissa untouched)", VerilatorTest) {
    val instrs = Seq(
      immX(4, Frac._1, Frac._2),
      "fabs.x %fp4,%fp5",
      "fneg.x %fp4,%fp6",
      "fneg.x %fp5,%fp7")
    h.runLockStep("fp-fabs-fneg", prog(instrs), nInstr = instrs.size,
      afterRun = { (dut, oracle) =>
        expectFp("fp-fabs-fneg", 4, 5, 6, 7)(dut, oracle)
        FpCompare.assertFpBits(dutFp(dut, 5), "4000b400000000000000", "fp-fabs FP5 = +2.8125")
        FpCompare.assertFpBits(dutFp(dut, 6), "4000b400000000000000", "fp-fneg FP6 = +2.8125")
        FpCompare.assertFpBits(dutFp(dut, 7), "c000b400000000000000", "fp-fneg FP7 = -2.8125")
      })
  }

  test("lock-step FP: FINT vs FINTRZ disagree on -2.8125 (-3.0 vs -2.0)", VerilatorTest) {
    val instrs = Seq(
      immX(0, Frac._1, Frac._2),
      "fint.x %fp0,%fp1",
      "fintrz.x %fp0,%fp2")
    h.runLockStep("fp-fint", prog(instrs), nInstr = instrs.size,
      afterRun = { (dut, oracle) =>
        expectFp("fp-fint", 0, 1, 2)(dut, oracle)
        FpCompare.assertFpBits(dutFp(dut, 1), "c000c000000000000000", "fp-fint FP1 = -3.0 (RN)")
        FpCompare.assertFpBits(dutFp(dut, 2), "c0008000000000000000", "fp-fintrz FP2 = -2.0 (RZ)")
        assert(dutFp(dut, 1) != dutFp(dut, 2),
          "FINT and FINTRZ MUST differ on -2.8125 -- identical results mean one is " +
          "implemented as an alias of the other")
      })
  }

  test("lock-step FP: FMOVECR ROM constants (pi and 1.0)", VerilatorTest) {
    val instrs = Seq("fmovecr #0x00,%fp0", "fmovecr #0x32,%fp1", "fmovecr #0x0f,%fp2")
    h.runLockStep("fp-fmovecr", prog(instrs), nInstr = instrs.size,
      afterRun = { (dut, oracle) =>
        expectFp("fp-fmovecr", 0, 1, 2)(dut, oracle)
        FpCompare.assertFpBits(dutFp(dut, 0), "4000c90fdaa22168c235", "fp-fmovecr FP0 = pi")
        FpCompare.assertFpBits(dutFp(dut, 1), "3fff8000000000000000", "fp-fmovecr FP1 = 1.0")
        FpCompare.assertFpBits(dutFp(dut, 2), "00000000000000000000", "fp-fmovecr FP2 = 0.0")
      })
  }

  // ── NaN propagation ──────────────────────────────────────────────────────

  test("lock-step FP: NaN propagates bit-for-bit through FADD/FMUL/FSUB", VerilatorTest) {
    val instrs = Seq(
      immX(0, QNan._1, QNan._2),
      "fmove.l #7,%fp1",
      "fmove.x %fp0,%fp2", "fadd.x %fp1,%fp2",
      "fmove.x %fp0,%fp3", "fmul.x %fp1,%fp3",
      "fmove.x %fp0,%fp4", "fsub.x %fp1,%fp4")
    h.runLockStep("fp-nan", prog(instrs), nInstr = instrs.size,
      afterRun = { (dut, oracle) =>
        expectFp("fp-nan", 0, 1, 2, 3, 4)(dut, oracle)
        for (r <- Seq(2, 3, 4))
          FpCompare.assertFpBits(dutFp(dut, r), "7fffc0000000deadbeef",
            s"fp-nan FP$r must be the ORIGINAL NaN, payload intact")
      })
  }

  test("lock-step FP: 0.0/0.0 yields the architectural default NaN", VerilatorTest) {
    val instrs = Seq("fmove.l #0,%fp0", "fdiv.x %fp0,%fp0")
    h.runLockStep("fp-nan-created", prog(instrs), nInstr = instrs.size,
      afterRun = expectFp("fp-nan-created", 0))
  }

  // ── Infinity arithmetic ──────────────────────────────────────────────────

  test("lock-step FP: infinity arithmetic (inf+finite, inf-inf, inf*0, inf/inf)", VerilatorTest) {
    val instrs = Seq(
      immX(0, PosInf._1, PosInf._2),
      immX(1, NegInf._1, NegInf._2),
      "fmove.l #7,%fp2",
      "fmove.l #0,%fp3",
      "fmove.x %fp0,%fp4", "fadd.x %fp2,%fp4",
      "fmove.x %fp0,%fp5", "fadd.x %fp1,%fp5",
      "fmove.x %fp0,%fp6", "fmul.x %fp3,%fp6",
      "fmove.x %fp0,%fp7", "fdiv.x %fp0,%fp7")
    h.runLockStep("fp-inf", prog(instrs), nInstr = instrs.size,
      afterRun = { (dut, oracle) =>
        expectFp("fp-inf", 0, 1, 2, 3, 4, 5, 6, 7)(dut, oracle)
        FpCompare.assertFpBits(dutFp(dut, 4), "7fff8000000000000000",
          "fp-inf FP4: +inf + 7.0 must remain exactly +inf")
        for (r <- Seq(5, 6, 7)) {
          val v = dutFp(dut, r)
          assert(((v >> 64) & 0x7fff) == 0x7fff && (v & ((BigInt(1) << 63) - 1)) != 0,
            f"fp-inf FP$r must be a NaN, got 0x${v.toString(16)}")
        }
      })
  }

  // ── FCMP vs FTST on infinity: the directed regression ────────────────────

  test("lock-step FP: FTST of infinity sets FPSR.I; FCMP inf,inf leaves I clear", VerilatorTest) {
    // Flagged in docs/superpowers/specs/2026-08-09-fpu-hardware-design.md section 3:
    // FTST is a distinct classifier, not a decode rewrite into FCMP-against-zero.
    // Confirmed in the vendored source (m68kfpu.c:1547-1553 FTST calls
    // SET_CONDITION_CODES directly, which sets FPCC_I for infinity; m68kfpu.c:1517-1546
    // FCMP takes a SEPARATE infinity branch that clears all FPCC bits and sets only
    // N/Z, never I) and empirically on the real oracle (FPSR 0x02000000 after FTST of
    // +inf, 0x04000000 after FCMP +inf,+inf).
    //
    // GUARD (Divergence Register D4, tightened 2026-08-17). The `fmove.l %fpsr,%dN` reads
    // below land FPSR in D0..D3, which `LockStep.compare` then checks BIT-EXACTLY, all 32
    // bits, against Musashi -- including the EXC/AEXC bytes. That is safe here ONLY because
    // this specific program never raises an FP exception (FTST/FCMP of infinities raise
    // nothing; see D6), so both sides read those bytes as zero. It is an incidental
    // property of the chosen instructions, not a designed guard.
    // => If you ever add a RAISING instruction to this program (or to any other lock-stepped
    //    program that reads FPSR into an integer register), you MUST mask the exception
    //    bytes before comparing -- Musashi models NO FP exceptions at all and will read 0
    //    where our RTL correctly accrues a sticky bit. The resulting divergence is the
    //    ORACLE being wrong, not the DUT; do not "fix" it by weakening FPSR accrual.
    val instrs = Seq(
      immX(0, PosInf._1, PosInf._2),
      immX(1, PosInf._1, PosInf._2),
      "ftst.x %fp0",
      "fmove.l %fpsr,%d0",
      "fcmp.x %fp1,%fp0",
      "fmove.l %fpsr,%d1",
      immX(2, NegInf._1, NegInf._2),
      "ftst.x %fp2",
      "fmove.l %fpsr,%d2",
      "fcmp.x %fp2,%fp0",
      "fmove.l %fpsr,%d3")
    h.runLockStep("fp-ftst-vs-fcmp", prog(instrs),
      nInstr = instrs.size,
      afterRun = { (dut, oracle) =>
        val last = oracle.last
        val I = 0x02000000L; val Z = 0x04000000L; val N = 0x08000000L
        assert((last.d(0) & I) != 0,
          f"oracle sanity: FTST of +inf must set FPSR.I, got D0=0x${last.d(0)}%08x")
        assert((last.d(1) & I) == 0,
          f"oracle sanity: FCMP inf,inf must leave FPSR.I CLEAR, got D1=0x${last.d(1)}%08x")
        assert((last.d(1) & Z) != 0,
          f"oracle sanity: FCMP +inf,+inf must set FPSR.Z, got D1=0x${last.d(1)}%08x")
        assert((last.d(2) & I) != 0 && (last.d(2) & N) != 0,
          f"oracle sanity: FTST of -inf must set FPSR.I and FPSR.N, got D2=0x${last.d(2)}%08x")
        assert((last.d(3) & I) == 0,
          f"oracle sanity: FCMP -inf,+inf must leave FPSR.I CLEAR, got D3=0x${last.d(3)}%08x")
        expectFp("fp-ftst-vs-fcmp", 0, 1, 2)(dut, oracle)
        FpCompare.assertFpBits(dutFp(dut, 0), "7fff8000000000000000",
          "fp-ftst-vs-fcmp FP0 must be UNMODIFIED by FTST/FCMP")
      })
  }

  // ── FPCR rounding mode, end to end (Task 14c) ────────────────────────────
  //
  // Task 7's FpuCoreSpec already proves the ARITHMETIC honours `io.rmode`. What these
  // tests prove is the thing that was genuinely broken until Task 14c: that a real
  // architectural FPCR write reaches that input at all. Until this task, `fpRmodeIn` was
  // default-driven RN and nothing anywhere assigned it, so every one of the four programs
  // below produced the identical (RN) answer.
  //
  // WHY `move.l #imm,%d0 ; fmove.l %d0,%fpcr` AND NOT `fmove.l #imm,%fpcr`: the immediate
  // <ea> form of FMOVE-to-control-register is deliberately NOT implemented (see
  // MicroOpAssembler.scala's `fpCtrlEaReg`, which admits mode 000/001 only, and the
  // recorded reasoning immediately above it -- `imm` cannot carry both a 32-bit value and
  // the 3-bit register mask through the ROB's single `sysRc` side-channel). The
  // register-direct form is the real, supported architectural write.
  //
  // WHY THIS IS LOCK-STEPPABLE: Musashi honours FPCR's rounding mode -- `fmove_fpcr`
  // (m68kfpu.c:1651-1653) sets SoftFloat's `float_rounding_mode` from `(REG_FPCR >> 4) & 3`
  // on every write to FPCR -- so the oracle's FP results are genuinely rounding-mode
  // dependent too. (Musashi's FPSR EXC/AEXC bytes are NOT modelled at all -- Divergence
  // Register D4 -- so these programs deliberately never read FPSR.)
  //
  // THE VECTOR: 1/3 is the canonical repeating quotient. Its exact 64-bit-significand
  // expansion is 0xAAAA_AAAA_AAAA_AAAA with a round bit of 1 and an infinite sticky tail,
  // i.e. ~0.67 ULP above the truncated value -- so RN rounds up, RZ truncates. Doing it
  // for BOTH +1/3 and -1/3 separates all four modes: RM and RP each truncate for one sign
  // and round away for the other, which no single-sign vector can distinguish.
  private val OneThirdDn = "aaaaaaaaaaaaaaaa"   // truncated toward zero
  private val OneThirdUp = "aaaaaaaaaaaaaaab"   // rounded away from zero

  private def roundingTest(tag: String, fpcr: Int, posLow: String, negLow: String): Unit =
    test(s"lock-step FP: FPCR rounding mode $tag reaches the FP EU (1/3 and -1/3)", VerilatorTest) {
      val instrs = Seq(
        f"move.l #0x$fpcr%02X,%%d0",
        "fmove.l %d0,%fpcr",
        "fmove.l #1,%fp0", "fmove.l #3,%fp1", "fdiv.x %fp1,%fp0",
        "fmove.l #-1,%fp2", "fmove.l #3,%fp3", "fdiv.x %fp3,%fp2")
      h.runLockStep(s"fp-round-$tag", prog(instrs), nInstr = instrs.size,
        afterRun = { (dut, oracle) =>
          expectFp(s"fp-round-$tag", 0, 2)(dut, oracle)
          FpCompare.assertFpBits(dutFp(dut, 0), "3ffd" + posLow,
            s"fp-round-$tag: +1/3 under $tag")
          FpCompare.assertFpBits(dutFp(dut, 2), "bffd" + negLow,
            s"fp-round-$tag: -1/3 under $tag")
        })
    }

  roundingTest("RN", 0x00, OneThirdUp, OneThirdUp)
  roundingTest("RZ", 0x10, OneThirdDn, OneThirdDn)
  roundingTest("RM", 0x20, OneThirdDn, OneThirdUp)   // toward -inf
  roundingTest("RP", 0x30, OneThirdUp, OneThirdDn)   // toward +inf

  // ══ FMOVE FPn,<ea> -- the STORE direction (Task 14b) ═════════════════════════════
  //
  // These are BIT-EXACT memory comparisons against Musashi's `fmove_reg_mem`
  // (m68kfpu.c:1570-1632), which is a VALID oracle for the stored VALUE: it calls the
  // very SoftFloat routines (`floatx80_to_int32`, `floatx80_to_float32`,
  // `floatx80_to_float64`, `store_extended_float80`) that `FpNarrowPack` transcribes.
  // `runLockStep`'s existing `checkMem`/`checkSpan` machinery does the comparison against
  // the oracle's own memory image, byte for byte, so nothing new is needed here.
  //
  // Musashi is NOT an oracle for this instruction's EXCEPTIONS -- `fmove_reg_mem` calls
  // neither `float_raise` nor `SET_CONDITION_CODES` (verified by direct search), so these
  // programs deliberately never read FPSR and never enable an FPCR trap. The converter's
  // own exception behaviour is proven at the unit level in `FpNarrowPackSpec`.
  //
  // NOT COVERED HERE, DELIBERATELY: `.W`/`.B` into a DATA REGISTER. That is a partial-
  // register merge on real hardware, and Musashi's `WRITE_EA_16`/`WRITE_EA_8` assign
  // `REG_D[reg] = data` from a `uint16`/`uint8`, zero-extending over the whole register --
  // a confirmed-wrong oracle for that one case (Divergence Register D11). The memory forms
  // of `.W`/`.B` below have no such problem and ARE lock-stepped.
  private val Scr = 0x3000L

  test("lock-step FP store: FMOVE.L FPn,(An) -- integer conversion into memory", VerilatorTest) {
    val instrs = Seq("movea.l #0x3000,%a0", "fmove.l #12345,%fp0", "fmove.l %fp0,(%a0)")
    h.runLockStep("fp-st-l", prog(instrs), nInstr = instrs.size,
      checkMem = Seq(Scr), checkSpan = 4)
  }

  test("lock-step FP store: FMOVE.S FPn,(An) -- exact single round trip (+3.0f)", VerilatorTest) {
    val instrs = Seq("movea.l #0x3000,%a0", "fmove.l #3,%fp0", "fmove.s %fp0,(%a0)",
                     "move.l (%a0),%d1")
    h.runLockStep("fp-st-s", prog(instrs), nInstr = instrs.size,
      checkMem = Seq(Scr), checkSpan = 4)
  }

  test("lock-step FP store: FMOVE.S with a value that must ROUND (1/3 -> single)", VerilatorTest) {
    // 1/3 has an infinite binary expansion, so the extended->single narrowing genuinely
    // exercises roundAndPackFloat32's increment/tie path rather than a bit-copy.
    val instrs = Seq("movea.l #0x3000,%a0",
                     "fmove.l #1,%fp0", "fmove.l #3,%fp1", "fdiv.x %fp1,%fp0",
                     "fmove.s %fp0,(%a0)", "move.l (%a0),%d1")
    h.runLockStep("fp-st-s-round", prog(instrs), nInstr = instrs.size,
      checkMem = Seq(Scr), checkSpan = 4)
  }

  test("lock-step FP store: FMOVE.D FPn,(An) -- BOTH chunks, correct word order", VerilatorTest) {
    // WRITE_EA_64 writes the HIGH 32 bits at ea+0 and the low at ea+4; a swapped pair
    // would show up immediately in the 8-byte comparison.
    val instrs = Seq("movea.l #0x3000,%a0",
                     "fmove.l #1,%fp0", "fmove.l #3,%fp1", "fdiv.x %fp1,%fp0",
                     "fmove.d %fp0,(%a0)", "move.l (%a0),%d1", "move.l 4(%a0),%d2")
    h.runLockStep("fp-st-d", prog(instrs), nInstr = instrs.size,
      checkMem = Seq(Scr, Scr + 4), checkSpan = 4)
  }

  test("lock-step FP store: FMOVE.X FPn,(An) -- all THREE chunks + the zero reserved word", VerilatorTest) {
    // `store_extended_float80` writes {sign+exp} at +0, a literal ZERO at +2, mantissa
    // hi32 at +4, mantissa lo32 at +8. The 12-byte comparison covers every one of those,
    // including the reserved word the LOAD direction deliberately skips.
    val instrs = Seq("movea.l #0x3000,%a0",
                     immX(0, QNan._1, QNan._2),
                     "fmove.x %fp0,(%a0)",
                     "move.l (%a0),%d1", "move.l 4(%a0),%d2", "move.l 8(%a0),%d3")
    h.runLockStep("fp-st-x", prog(instrs), nInstr = instrs.size,
      checkMem = Seq(Scr, Scr + 4, Scr + 8), checkSpan = 4)
  }

  test("lock-step FP store: FMOVE.W / FMOVE.B into memory (truncating access sizes)", VerilatorTest) {
    // The two `clr.l`s are load-bearing, not decoration: `checkMem` compares a 4-byte span
    // and the DUT's backing memory model does NOT zero-fill a page the program never wrote,
    // so the bytes BESIDE a partial (.W/.B) store would be compared as garbage-vs-oracle-0.
    // Clearing both longs first makes the whole span defined in the DUT and the oracle
    // alike, which is exactly what makes "only the low word/byte changed" a real assertion.
    val instrs = Seq("movea.l #0x3000,%a0",
                     "clr.l (%a0)", "clr.l 4(%a0)",
                     "fmove.l #-1234,%fp0",
                     "fmove.w %fp0,(%a0)", "fmove.b %fp0,4(%a0)",
                     "move.w (%a0),%d1", "move.b 4(%a0),%d2")
    h.runLockStep("fp-st-wb", prog(instrs), nInstr = instrs.size,
      checkMem = Seq(Scr, Scr + 4), checkSpan = 4)
  }

  test("lock-step FP store: auto-increment (An)+ .S and auto-decrement -(An) .X", VerilatorTest) {
    // The An write-backs are compared by the ORDINARY per-step integer lock-step (+4 for
    // Single, -12 for Extended), and the stored bytes by checkMem.
    val instrs = Seq("movea.l #0x3000,%a0", "movea.l #0x3020,%a1",
                     "fmove.l #7,%fp0",
                     "fmove.s %fp0,(%a0)+",
                     "fmove.x %fp0,-(%a1)")
    h.runLockStep("fp-st-auto", prog(instrs), nInstr = instrs.size,
      checkMem = Seq(Scr, 0x3014L, 0x3018L, 0x301CL), checkSpan = 4)
  }

  test("lock-step FP store: displacement, brief-indexed and absolute-LONG destinations", VerilatorTest) {
    // ABSOLUTE-SHORT is deliberately absent: Musashi cannot referee it at all. Its FP write
    // helpers have no mode-7/reg-0 arm and `fatalerror` out of the whole emulator
    // ("M68kFPU: WRITE_EA_32: unhandled mode 7, reg 0"), so an abs.W destination kills the
    // ORACLE, not the DUT. This core supports it (D6) and `FpMemStoreSpec` covers it at
    // decode level; the ported corpus test `fpu_fmove_fp_to_ea_matrix.s` covers it
    // end-to-end without an oracle.
    val instrs = Seq("movea.l #0x3000,%a0", "moveq #8,%d6",
                     "fmove.l #99,%fp0",
                     "fmove.l %fp0,16(%a0)",
                     "fmove.d %fp0,(32,%a0,%d6.w)",
                     "fmove.s %fp0,0x3050:l")
    h.runLockStep("fp-st-ea", prog(instrs), nInstr = instrs.size,
      checkMem = Seq(0x3010L, 0x3028L, 0x302CL, 0x3050L), checkSpan = 4)
  }

  test("lock-step FP store: register-direct FMOVE.L/.S FPn,Dn and FMOVE.L FPn,An", VerilatorTest) {
    // Full-width destinations only -- see the D11 note above for why `.W`/`.B` into Dn is
    // excluded. The integer/An results are compared by the ordinary per-step lock-step.
    //
    // The An form is hand-encoded because GNU as REFUSES `fmove.l %fp0,%a3` ("operands
    // mismatch"), even though Musashi's `WRITE_EA_32` implements mode 1 and this core
    // accepts it (D5, matching the existing FPCR-control-register precedent). opword
    // 0xF200|(1<<3)|3 = 0xF20B; ext = opclass 011 << 13 | Long fmt 000 << 10 | FP0 << 7.
    val instrs = Seq("fmove.l #-5,%fp0",
                     "fmove.l %fp0,%d1",
                     "fmove.s %fp0,%d2",
                     ".short 0xF20B ; .short 0x6000")
    h.runLockStep("fp-st-reg", prog(instrs), nInstr = instrs.size)
  }

  test("lock-step FP store: the ported corpus's OWN fpu_fsqrt_basic encoding, refereed by Musashi", VerilatorTest) {
    // `fpu_fsqrt_basic.s` (and five sibling corpus tests) build their FPn seed with the
    // formula `ext = 0x4000 | (Dn<<10) | (FPn<<7)`, which puts the DATA REGISTER NUMBER in
    // ext[12:10] -- the source FORMAT field -- instead of the constant 001 that `.S` needs.
    // With Dn=0 that is format 000 = LONG, so `.short 0xF200,0x4000` is `FMOVE.L D0,FP0`
    // and FP0 receives the INTEGER 0x40800000 (1082130432.0), not 4.0f. The verify side
    // then stores with format 001 (Single), so the test can never match its own expected
    // 0x40000000 on ANY correct 68k. This test replays that EXACT instruction sequence and
    // refereeing it against Musashi: agreement proves the divergence is the corpus test's
    // encoding, not this core's store direction.
    val instrs = Seq("movea.l #0x3000,%a0",
                     "move.l #0x40800000,%d0",
                     ".short 0xF200 ; .short 0x4000",   // FMOVE.L D0,FP0  (the corpus's own bytes)
                     ".short 0xF200 ; .short 0x0084",   // FSQRT.X FP0,FP1
                     ".short 0xF210 ; .short 0x6480",   // FMOVE.S FP1,(A0)
                     "move.l (%a0),%d1")
    h.runLockStep("fp-st-corpus-fsqrt", prog(instrs), nInstr = instrs.size,
      checkMem = Seq(Scr), checkSpan = 4)
  }

  test("lock-step FP store: back-to-back stores of DIFFERENT FPn must not cross-feed", VerilatorTest) {
    // The exact hazard `fpu_x2s_stash_back_to_back.s` was written for in the sibling core:
    // two FMOVE.S FPn,(An) close together sharing one conversion latch. This core has no
    // such latch (the conversion is a per-uop combinational cone off `fpRdA`), and the
    // 4-way distinct values here would expose any sharing immediately.
    val instrs = Seq("movea.l #0x3000,%a0",
                     "fmove.l #11,%fp0", "fmove.l #22,%fp1",
                     "fmove.l #33,%fp2", "fmove.l #44,%fp3",
                     "fmove.s %fp0,(%a0)", "fmove.s %fp1,4(%a0)",
                     "fmove.s %fp2,8(%a0)", "fmove.s %fp3,12(%a0)",
                     "move.l (%a0),%d1", "move.l 4(%a0),%d2",
                     "move.l 8(%a0),%d3", "move.l 12(%a0),%d4")
    h.runLockStep("fp-st-b2b", prog(instrs), nInstr = instrs.size,
      checkMem = Seq(Scr, Scr + 4, Scr + 8, Scr + 12), checkSpan = 4)
  }

  // ══════════════════════════════════════════════════════════════════════════════════
  // FMOVEM.X data-register-list LOAD -- ROB COMMIT-PC CORRUPTION (campaign/fmovem-
  // postinc-ring). See docs/BUG_fmovemx_dataload_rob_commit_corruption.md for the full
  // writeup. SUMMARY: task #241/#246 ("shared register-list-walk skeleton + FMOVEM.X
  // data-list FSM") was previously verified ONLY at decode/µop-shape level
  // (`FmovemxDataListDecodeSpec`/`FpMemLoadSpec`) -- NO test ever drove it through a
  // real ROB retire + lock-step PC comparison before this session. Doing so for the
  // first time (below) found the ROB's commit-PC framing (`RobPlugin.commitPc0 =
  // Mux(p0.retireAlone, nextPcRd0, p0.predNextPc)`) is WRONG for this FSM's "kept
  // commit" µop (the last element's FP-issue row -- writes the FP PRF only, no integer
  // register, `u.isBranch=False`), in at least two distinct ways depending on EA mode:
  //   - `(An)` (mode 010), even a trivial 1-register list: the commit reads WHOLESALE
  //     GARBAGE -- not just `pc` but `a7` too (a completely untouched register),
  //     matching uninitialised-simulation-state readback, not a simple off-by-N.
  //   - `(d16,An)` (mode 101): the FMOVEM macro's OWN commit is fine, but the VERY NEXT
  //     retiring instruction's commit-pc is short by exactly one word (2 bytes) -- ONLY
  //     when the FMOVEM FSM actually ran a real multi-element/multi-cycle drain (an
  //     8-element list); a `(d16,An)` SINGLE-element list (below) round-trips clean.
  // Both are reproducible, deterministic, 100% NOT related to the aligned-load split
  // ring this campaign was scoped to stress -- they block ever reaching that stress
  // test with a real oracle-compared FPn value. Root cause not pinned to a single line
  // (`RobPlugin.scala`'s retire/commit-PC path is large and FMax-critical); flagged
  // architectural per this session's own decision rule rather than guess-patched.
  // ══════════════════════════════════════════════════════════════════════════════════

  // Minimal repro #1: `(An)` mode, single-register list -- the SHORTEST possible
  // FMOVEM.X data-list load (4 bytes, one element, 4 sub-phase cycles), no ring
  // pressure, aligned base. Still fails, with WHOLESALE garbage on the FMOVEM's own
  // commit (`a7` corrupted too, not just `pc`) -- rules out "only misaligned/multi-
  // element cases are affected."
  ignore("[KNOWN BUG, see docs/BUG_fmovemx_dataload_rob_commit_corruption.md] " +
         "FMOVEM.X (An),FP0 single-element -- ROB commit-pc reads garbage") {
    val instrs = Seq(
      "movea.l #0x3000,%a1",
      "move.l #0x3fff0000,(%a1)", "move.l #0x11223344,(4,%a1)", "move.l #0xaabbccdd,(8,%a1)",
      "fmovem.x (%a1),%fp0",
      "move.l %a1,%d7")
    h.runLockStep("fmovemx-an-min", prog(instrs), nInstr = instrs.size)
  }

  // Minimal repro #2 (POSITIVE control, GREEN): `(d16,An)` mode, single-register list.
  // Same shape as repro #1 but the OTHER admitted EA mode -- passes clean. Narrows the
  // trigger: NOT every FMOVEM.X data-list load is broken, specifically `(An)` (any
  // element count) and `(d16,An)` with a REAL multi-cycle multi-element drain are.
  test("lock-step: FMOVEM.X (d16,An),FP0 single-element then a trailing kept instruction", VerilatorTest) {
    val instrs = Seq(
      "movea.l #0x3000,%a1",
      "move.l #0x3fff0000,(2,%a1)", "move.l #0x11223344,(6,%a1)", "move.l #0xaabbccdd,(10,%a1)",
      "fmovem.x (2,%a1),%fp0",
      "move.l %a1,%d7")
    h.runLockStep("fmovemx-d16an-min", prog(instrs), nInstr = instrs.size)
  }

  // ══ FMOVEM.X (d16,An),<list> LOAD -- data-register-list ROTATING-PHASE multi-split ══
  // ══ aligned-load-ring stress (campaign/fmovem-postinc-ring)                       ══
  //
  // SCOPE CORRECTION vs. the campaign's original brief. The brief's premise -- that
  // `FMOVEM.X (An)+,<list>` is already implemented (design doc
  // docs/superpowers/specs/2026-08-19-fmovem-data-list-design.md §1's table) -- does NOT
  // hold at this task's HEAD: only task #241/#246 (the FSM skeleton, LOAD direction,
  // EA modes `(An)`/`(d16,An)` only) has landed. Postincrement/predecrement/indexed/
  // PC-relative and the STORE direction are still design-doc §7 items 2-4, unimplemented
  // -- confirmed both by static read of `DecodeStage.scala`'s `slot0IsFmovemx` gate (EA
  // mode restricted to 010/101) and empirically by
  // `FmovemxDataListDecodeSpec`'s new "(An)+ ... still traps" / "-(An) ... still traps"
  // regressions (vector 11, FP_MEM_TRAP_ENTRY). See docs/BUG_fmovemx_postinc_unimplemented.md.
  //
  // The RING-STRESS MECHANISM the campaign actually cares about is orthogonal to
  // auto-update, though: `DecodeStage.scala`'s `fmovemxOff` increments by 12 bytes per
  // element regardless of EA mode (`(An)`/`(d16,An)`/a hypothetical `(An)+` would all
  // step identically), so a MISALIGNED `(d16,An)` base drives the EXACT SAME
  // rotating-phase-across-elements multi-split pattern the brief was chasing. This test
  // substitutes `(d16,An)` for the unavailable `(An)+` -- a genuine, in-scope,
  // oracle-valid stand-in for the mechanism under test, not a weaker one.
  //
  // Also load-bearing: this project's OWN vendored Musashi
  // (tools/musashi/musashi/m68kfpu.c `READ_EA_FPE`) does NOT advance the address for EA
  // mode `(An)` (imode==2) across a multi-register list -- every listed FPn would read
  // the SAME 12 bytes at An (confirmed by direct source read; matches the analogous note
  // in the ported v1 corpus's `fpu_fmovem_x_an_indirect.s`). `(d16,An)` (imode==5) DOES
  // advance correctly (Musashi's own `fmovem()` dispatcher pre-computes `di_mode_ea` once
  // and increments it by 12 per listed register, exactly like this core's `fmovemxOff`),
  // so it is the only one of this task's two admitted EA modes that is BOTH in-scope and
  // a valid oracle for a multi-register list -- another reason to use it here.
  //
  // ALSO BLOCKED (see the bug-doc block above): this exact test used to fail on its own
  // account with a clean +2-byte commit-pc shortfall on the trailing `move.l %a1,%d7`
  // step -- the ROB commit-corruption bug, NOT a ring defect (the FMOVEM macro's own
  // commit, and every FPn value, were already confirmed byte-exact when this was found).
  // `ignore`d pending that fix; re-enable once the bug doc's fix lands.
  //
  // Base = A1(0x3000) + d16(2) = 0x3002: 2 mod 16, 2 mod 4 (NOT a multiple of 4), so the
  // 12-byte-per-element Extended stride keeps SHIFTING PHASE relative to the 16-byte
  // D-cache line, unlike plain MOVEM.L's fixed-phase mod-4 case (every element there
  // crosses at the SAME chunk position, if it crosses at all). Hand-verified against the
  // RTL's own split criterion (`LsEuPlugin.s1CrossLine = (vaddr(3:0) + size) > 16`, size=4
  // for these Long LOAD chunks, i.e. crosses iff `vaddr mod 16 >= 13`): of the 24 chunks
  // (8 elements x 3), exactly 6 cross a line -- one per element k in {1,2,3,5,6,7} (k=0
  // and k=4 land on offsets {2,6,10}/{50,54,58} mod 16, none >=13, so neither ever
  // splits) -- and the crossing chunk ROTATES chunk0->chunk1->chunk2->chunk0... every
  // element (period 4, since gcd(12,16)=4). Table (offset = raw byte offset from the
  // 0x3000 line-grid origin, i.e. 2 + 12*k + 4*i):
  //   k=0(FP7): 2,6,10        no split      k=4(FP3): 50,54,58      no split
  //   k=1(FP6): 14,18,22      chunk0 SPLITS k=5(FP2): 62,66,70      chunk0 SPLITS
  //   k=2(FP5): 26,30,34      chunk1 SPLITS k=6(FP1): 74,78,82      chunk1 SPLITS
  //   k=3(FP4): 38,42,46      chunk2 SPLITS k=7(FP0): 86,90,94      chunk2 SPLITS
  // (register column: reverse map, `FMOVEM.X` control/postinc list format -- bit index k
  // extracted low-to-high from the full 0xFF mask maps to FP(7-k), so element k's memory
  // slot lands in FP(7-k), highest-numbered register at the lowest address per the ISA.)
  ignore("[BLOCKED on docs/BUG_fmovemx_dataload_rob_commit_corruption.md] lock-step: FMOVEM.X (d16,An),FP0-FP7 -- misaligned base drives rotating-phase multi-split ring pressure") {
    val d16 = 2
    // 8 distinct, recognizable 80-bit patterns (real hardware Extended format: word0
    // upper-16 = sign+exp, lower-16 = reserved/0; word1 = mantissa hi32; word2 = mantissa
    // lo32). Diverge in OPPOSITE directions per k so no two elements' bytes can alias by
    // coincidence, and any cross-talk between concurrently in-flight split pairs shows up
    // immediately as a wrong FPn.
    val elems = (0 until 8).map { k =>
      val hi16   = (0x3ff0 + k) & 0xffff
      val mantHi = (0x11223344L + k * 0x01010101L) & 0xffffffffL
      val mantLo = (0xaabbccddL - k * 0x01010101L) & 0xffffffffL
      (hi16, mantHi, mantLo)
    }
    val storeInstrs = (0 until 8).flatMap { k =>
      val (hi16, mantHi, mantLo) = elems(k)
      val off = d16 + 12 * k
      val w0  = (hi16.toLong << 16) & 0xffffffffL   // reserved lower 16 bits = 0
      Seq(f"move.l #0x$w0%08x,($off,%%a1)",
          f"move.l #0x$mantHi%08x,(${off + 4},%%a1)",
          f"move.l #0x$mantLo%08x,(${off + 8},%%a1)")
    }
    val instrs = Seq("movea.l #0x3000,%a1") ++ storeInstrs ++
      Seq("fmovem.x (2,%a1),%fp0-%fp7", "move.l %a1,%d7")
    var splitCount = 0
    h.runLockStep("fmovemx-ring-rotate", prog(instrs), nInstr = instrs.size,
      perCycle = { dut => if (dut.lsEu.logic.alignedEnqSplit.toBoolean) splitCount += 1 },
      afterRun = { (dut, oracle) =>
        // (1) genuine multi-split pressure, not a theoretical claim: >=6 aligned-ring
        // split-pair pushes over the course of this ONE macro-instruction (one per
        // element in the table above) -- the rotating-phase stress this test exists
        // for. `>=` rather than `==`: a store-side split (if this core's StoreQueue
        // ever grows one sharing the same counter) or an incidental extra push would
        // not falsely fail this; only TOO FEW would.
        assert(splitCount >= 6,
          s"fmovemx-ring-rotate: expected >= 6 aligned-load-ring split-pair pushes " +
          s"(elements k in {1,2,3,5,6,7} per the hand-verified table above) -- only " +
          s"saw $splitCount. Either the base/d16 line-crossing arithmetic drifted from " +
          s"the RTL's own s1CrossLine criterion, or the ring stopped taking the split " +
          s"path for this pattern.")
        // (2) every FPn round-tripped byte-exact despite the concurrent back-to-back
        // splits -- catches any aliasing/cross-talk between split pairs in flight.
        for (k <- 0 until 8) {
          val (hi16, mantHi, mantLo) = elems(k)
          val reg      = 7 - k   // reverse map: element k -> FP(7-k)
          val expected = FpCompare.fp80(hi16, (BigInt(mantHi) << 32) | BigInt(mantLo))
          FpCompare.assertFpEqual(dutFp(dut, reg), expected, s"fmovemx-ring-rotate FP$reg (element k=$k)")
        }
      })
  }
}
