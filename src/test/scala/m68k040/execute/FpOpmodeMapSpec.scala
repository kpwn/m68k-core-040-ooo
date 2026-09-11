package m68k040.execute

import m68k040.{M68kSim, VerilatorTest}
import m68k040.execute.fpu._
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** The cpGEN opmode -> {operation, lane, rounding precision} mapping, swept over ALL 128
  * encodings of the 7-bit field, with and without the FMOVECR (`romConst`) veto, and at
  * all four FPCR.PREC settings.
  *
  * Exists because the MC68040's 16 forced-precision encodings (FSADD $62, FDMUL $67, ...)
  * are exactly the kind of table that gets half-copied: before this slice the SAME opmode
  * whitelist was hand-duplicated in FIVE places (MicroOpAssembler, DecodeStage's
  * `ucFpNative`, both Microcode `UFpIssue` arms, and FpSource's own dispatch). They now all
  * read `FpSource`'s single table; this sweep is what proves the table itself is right.
  *
  * Lock-step cannot help here either: Musashi `fatalerror()`s on opmode $62
  * ("fpgen_rm_reg: unimplemented opmode"), so there is no oracle to compare against -- the
  * expectations below come from the M68000PRM's per-instruction "Opmode field" tables. */
class FpOpmodeMapSpec extends AnyFunSuite {

  /** Combinational probe over the four pure functions under test. */
  class Dut extends Component {
    val io = new Bundle {
      val opmode   = in Bits (7 bits)
      val romConst = in Bool ()
      val fpcrPrec = in Bits (2 bits)
      val op       = out(FpOp())
      val prec     = out Bits (2 bits)
      val iter     = out Bool ()
      val native   = out Bool ()
      val dyadic   = out Bool ()
      val noFpDst  = out Bool ()
    }
    io.op      := FpSource.opmodeToFpOp(io.opmode, io.romConst)
    io.prec    := FpSource.opmodeToPrecision(io.opmode, io.romConst, io.fpcrPrec)
    io.iter    := FpSource.isIterativeOpmode(io.opmode, io.romConst)
    io.native  := FpSource.isNativeOpmode(io.opmode)
    io.dyadic  := FpSource.isDyadicOpmode(io.opmode)
    io.noFpDst := FpSource.isNoFpDstOpmode(io.opmode)
  }

  /** The expectation table, written from the M68000PRM Instruction Format sections
    * (every forced-precision row is marked there "Supported by MC68040 only"), NOT from
    * FpSource. opmode -> (FpOp, forced precision or None, iterative lane?). */
  private val expected: Map[Int, (FpOp.E, Option[Int], Boolean)] = Map(
    0x00 -> (FpOp.FMOVE,  None,              false),   // FMOVE
    0x40 -> (FpOp.FMOVE,  Some(FpPrec.Sgl),  false),   // FSMOVE
    0x44 -> (FpOp.FMOVE,  Some(FpPrec.Dbl),  false),   // FDMOVE
    0x01 -> (FpOp.FINT,   None,              false),
    0x03 -> (FpOp.FINTRZ, None,              false),
    0x04 -> (FpOp.FSQRT,  None,              true),    // FSQRT
    0x41 -> (FpOp.FSQRT,  Some(FpPrec.Sgl),  true),    // FSSQRT  (NOT $44 -- see FpSource)
    0x45 -> (FpOp.FSQRT,  Some(FpPrec.Dbl),  true),    // FDSQRT
    0x18 -> (FpOp.FABS,   None,              false),
    0x58 -> (FpOp.FABS,   Some(FpPrec.Sgl),  false),   // FSABS
    0x5C -> (FpOp.FABS,   Some(FpPrec.Dbl),  false),   // FDABS
    0x1A -> (FpOp.FNEG,   None,              false),
    0x5A -> (FpOp.FNEG,   Some(FpPrec.Sgl),  false),   // FSNEG
    0x5E -> (FpOp.FNEG,   Some(FpPrec.Dbl),  false),   // FDNEG
    0x20 -> (FpOp.FDIV,   None,              true),
    0x60 -> (FpOp.FDIV,   Some(FpPrec.Sgl),  true),    // FSDIV
    0x64 -> (FpOp.FDIV,   Some(FpPrec.Dbl),  true),    // FDDIV
    0x22 -> (FpOp.FADD,   None,              false),
    0x62 -> (FpOp.FADD,   Some(FpPrec.Sgl),  false),   // FSADD
    0x66 -> (FpOp.FADD,   Some(FpPrec.Dbl),  false),   // FDADD
    0x23 -> (FpOp.FMUL,   None,              false),
    0x63 -> (FpOp.FMUL,   Some(FpPrec.Sgl),  false),   // FSMUL
    0x67 -> (FpOp.FMUL,   Some(FpPrec.Dbl),  false),   // FDMUL
    0x28 -> (FpOp.FSUB,   None,              false),
    0x68 -> (FpOp.FSUB,   Some(FpPrec.Sgl),  false),   // FSSUB
    0x6C -> (FpOp.FSUB,   Some(FpPrec.Dbl),  false),   // FDSUB
    0x38 -> (FpOp.FCMP,   None,              false),
    0x3A -> (FpOp.FTST,   None,              false))

  private val dyadicExpected =
    Set(0x20, 0x60, 0x64, 0x22, 0x62, 0x66, 0x23, 0x63, 0x67, 0x28, 0x68, 0x6C, 0x38)

  private lazy val dut = M68kSim().withVerilator.compile(new Dut)

  test("the 128-entry cpGEN opmode sweep matches the M68000PRM tables", VerilatorTest) {
    dut.doSim { d =>
      for (om <- 0 until 128; fpcr <- 0 until 4) {
        d.io.opmode #= om; d.io.romConst #= false; d.io.fpcrPrec #= fpcr
        sleep(1)
        val native = d.io.native.toBoolean
        assert(native == expected.contains(om),
          f"opmode $om%02X: native=$native, expected ${expected.contains(om)}")
        assert(d.io.dyadic.toBoolean == dyadicExpected.contains(om),
          f"opmode $om%02X: dyadic mismatch")
        assert(d.io.noFpDst.toBoolean == (om == 0x38 || om == 0x3A),
          f"opmode $om%02X: noFpDst mismatch")
        if (native) {
          val (eOp, ePrec, eIter) = expected(om)
          assert(d.io.op.toEnum == eOp, f"opmode $om%02X: op ${d.io.op.toEnum}, want $eOp")
          assert(d.io.iter.toBoolean == eIter, f"opmode $om%02X: iterative-lane mismatch")
          // "Instructions with an S or D (e.g., FSADD) have the same effect as setting the
          // rounding precision to S or D" (MC68040UM 10.7) -- REGARDLESS of FPCR.PREC, so
          // the forced value must win at all four FPCR settings, and the unforced opmodes
          // must pass FPCR.PREC through untouched at all four.
          assert(d.io.prec.toInt == ePrec.getOrElse(fpcr),
            f"opmode $om%02X fpcrPrec=$fpcr: prec=${d.io.prec.toInt}, " +
            f"want ${ePrec.getOrElse(fpcr)}")
        }
      }
    }
  }

  test("the FMOVECR romConst veto suppresses every opmode interpretation", VerilatorTest) {
    // For FMOVECR the 7-bit field is the constant-ROM OFFSET, not an opmode, and offsets
    // $04/$20 (iterative) and $40..$6C (forced precision) are all perfectly encodable. A
    // missing veto on the precision function would silently make `FMOVECR #$62,FPn` round
    // to single; a missing veto on the lane function wedges the iterative lane outright.
    dut.doSim { d =>
      for (om <- 0 until 128; fpcr <- 0 until 4) {
        d.io.opmode #= om; d.io.romConst #= true; d.io.fpcrPrec #= fpcr
        sleep(1)
        assert(d.io.op.toEnum == FpOp.FMOVECR, f"offset $om%02X must decode as FMOVECR")
        assert(!d.io.iter.toBoolean, f"offset $om%02X must not claim the iterative lane")
        assert(d.io.prec.toInt == fpcr,
          f"offset $om%02X must leave FPCR.PREC alone, got ${d.io.prec.toInt}")
      }
    }
  }

  test("the shared opmode tables are internally consistent") {
    // Pure elaboration-time checks on the Scala tables themselves -- no simulator.
    assert(FpSource.nativeOpmodes.distinct.size == FpSource.nativeOpmodes.size,
      "duplicate entry in nativeOpmodes")
    assert(FpSource.nativeOpmodes.size == 28,
      s"expected 12 base + 16 forced-precision opmodes, got ${FpSource.nativeOpmodes.size}")
    for ((b, s, d) <- FpSource.precisionFamily) {
      assert(FpSource.nativeOpmodes.contains(b) && FpSource.nativeOpmodes.contains(s) &&
             FpSource.nativeOpmodes.contains(d),
        f"precisionFamily row ($b%02X,$s%02X,$d%02X) is not fully in nativeOpmodes")
    }
    assert(FpSource.iterOpmodes.toSet == Set(0x04, 0x41, 0x45, 0x20, 0x60, 0x64),
      "iterOpmodes must cover FSQRT and FDIV in all three precision spellings")
    assert(FpSource.dyadicOpmodes.toSet == dyadicExpected)
    // Every opmode this core admits must have a defined precision family membership or be
    // one of the four that has no forced-precision form.
    val covered = FpSource.precisionFamily.flatMap(t => Seq(t._1, t._2, t._3)).toSet ++
                  FpSource.precisionlessOpmodes.toSet
    assert(covered == FpSource.nativeOpmodes.toSet)
  }
}
