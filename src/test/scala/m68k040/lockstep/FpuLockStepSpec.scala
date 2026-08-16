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
}
