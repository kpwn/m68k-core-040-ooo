package m68k040.oracle

import org.scalatest.funsuite.AnyFunSuite

/** Proves Task 12's oracle-side plumbing end to end. Pure oracle -- no DUT, no
  * Verilator; runs in the fast suite. */
class OracleFpTraceSpec extends AnyFunSuite {

  private val Src = Seq(
    "fmove.l #5,%fp0",
    "fmove.l #3,%fp1",
    "fadd.x %fp1,%fp0",
    "fmove.x cinf(%pc),%fp2",
    "fmove.x cnan(%pc),%fp3",
    "fadd.x %fp3,%fp2",
    "spin: bra.s spin",
    ".align 2",
    "cinf: .short 0x7FFF", ".short 0", ".long 0x80000000", ".long 0x00000000",
    "cnan: .short 0x7FFF", ".short 0", ".long 0xC0000000", ".long 0x00000000"
  ).mkString(" ; ")

  test("Musashi trace carries bit-exact 80-bit FP state") {
    Musashi.assembleAndTrace(Src) match {
      case Left(err) => fail(s"oracle error: ${err.reason}")
      case Right(steps) =>
        assert(steps.size >= 6, s"expected >= 6 steps, got ${steps.size}")
        assert(steps.forall(_.fp.size == 8),
          "every step must carry all 8 FP registers -- if this fails, musashi_run is " +
          "stale: rebuild with `make -C tools/musashi`")

        assert(steps(0).fp(0) == BigInt("4001a000000000000000", 16),
          f"FP0 after fmove.l #5,%%fp0 should be 5.0, got 0x${steps(0).fp(0).toString(16)}")
        assert(steps(1).fp(1) == BigInt("4000c000000000000000", 16),
          f"FP1 after fmove.l #3,%%fp1 should be 3.0, got 0x${steps(1).fp(1).toString(16)}")
        assert(steps(2).fp(0) == BigInt("40028000000000000000", 16),
          f"FP0 after FADD should be exactly 8.0, got 0x${steps(2).fp(0).toString(16)}")

        assert(steps(3).fp(2) == BigInt("7fff8000000000000000", 16), "FP2 should be +inf")
        assert(steps(3).fpsr == 0x02000000L,
          f"FPSR after loading +inf should have FPCC_I set, got 0x${steps(3).fpsr}%08x")
        assert(steps(4).fp(3) == BigInt("7fffc000000000000000", 16), "FP3 should be NaN")
        assert(steps(4).fpsr == 0x01000000L,
          f"FPSR after loading NaN should have FPCC_NAN set, got 0x${steps(4).fpsr}%08x")
        assert(steps(5).fp(2) == BigInt("7fffc000000000000000", 16),
          f"inf+NaN must propagate the NaN unchanged, got 0x${steps(5).fp(2).toString(16)}")

        assert(steps.forall(_.fpcr == 0L),  "FPCR must stay 0 (no FMOVE to FPCR here)")
        assert(steps.forall(_.fpiar == 0L), "FPIAR must stay 0 (Musashi raises no FP exceptions)")
    }
  }

  test("OracleStep parses a full-64-bit fpNl field without overflowing Long") {
    val Div0 = Seq(
      "fmove.l #0,%fp0",
      "fdiv.x %fp0,%fp0",
      "spin: bra.s spin"
    ).mkString(" ; ")
    Musashi.assembleAndTrace(Div0) match {
      case Left(err)   => fail(s"oracle error: ${err.reason}")
      case Right(steps) =>
        assert(steps.size >= 2, s"expected >= 2 steps, got ${steps.size}")
        assert(steps(1).fp(0) == BigInt("ffffffffffffffffffff", 16),
          f"0/0 should yield Musashi's default NaN, got 0x${steps(1).fp(0).toString(16)}")
    }
  }
}
