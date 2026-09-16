package m68k040.execute

import m68k040.VerilatorTest
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Differential gate for the AluEuPlugin S1 writeback mux RE-ASSOCIATION
  * (Lever N-A2 / N-A2', see AluEuPlugin.scala).
  *
  * The re-association hoists the ONE late input on each cone (`rsp.result` for the
  * 32-bit integer writeback, `aluNzvc`/`rsp.xOut` for the flags) from the INNERMOST
  * position of an 8-deep / 3-deep nest to a single outermost 2:1 mux with an early
  * select. It is supposed to be UNCONDITIONALLY value-identical -- not "identical for
  * reachable op encodings", identical for every selector combination whatsoever.
  *
  * This DUT carries both trees side by side: `oldOut` is a VERBATIM transcription of
  * AluEuPlugin @ bde6e366 (the shape that produced the vivado200_fmax2 checkpoint),
  * `newOut` is a verbatim transcription of the re-associated shape. The selector
  * legs are driven as free inputs, so the sweep covers combinations the decoder can
  * never actually produce as well as the ones it can -- exactly the claim being made.
  *
  * Mutation-checked: swapping `keepOldMid`'s `Size.BYTE` for `Size.WORD`, dropping the
  * `anWide` term from either `keepOld*`, or exchanging the `restMid`/`restHi` slices
  * all make this fail within the first few dozen points.
  */
class AluMergeReassocSpec extends AnyFunSuite {

  class MergeDut extends Component {
    val io = new Bundle {
      // ── selectors (free inputs: every combination is swept, reachable or not) ──
      val isBfResolve, isPack, isUnpk, isBcd  = in Bool()
      val isLogicSr, casIsOp, isMovea         = in Bool()
      val isFromCcrSr, opIsMove, toCcr        = in Bool()
      val size                                = in(Size())
      // ── data legs ──
      val rspResult, bfResolveRes, packRes8, unpkRes32, bcd32 = in Bits(32 bits)
      val srLogicResult, casResult, moveaResult               = in Bits(32 bits)
      val sizeMergedMove, s1Src1                              = in Bits(32 bits)
      val aluNzvc, casNzvc, ccrNzvc, bcdNzvc                  = in Bits(4 bits)
      val rspXOut, ccrX, bcdCarry                             = in Bool()
      // ── outputs ──
      val oldOut,  newOut  = out Bits(32 bits)
      val oldNzvc, newNzvc = out Bits(4 bits)
      val oldX,    newX    = out Bool()
    }
    // `anWide = u1.isMovea && (u1.op =/= DecOp.MOVE)` -- reproduced structurally so the
    // sweep cannot violate the anWide => isMovea implication the re-association uses.
    val anWide = io.isMovea && !io.opIsMove

    // ══════════ OLD: verbatim transcription of AluEuPlugin @ bde6e366 ══════════
    val opResult = Mux(io.isBfResolve, io.bfResolveRes,
                   Mux(io.isPack, io.packRes8,
                   Mux(io.isUnpk, io.unpkRes32,
                   Mux(io.isBcd,  io.bcd32, io.rspResult))))
    val sizeMerged = io.size.mux(
      Size.BYTE -> (io.s1Src1(31 downto 8)  ## opResult(7 downto 0)),
      Size.WORD -> (io.s1Src1(31 downto 16) ## opResult(15 downto 0)),
      Size.LONG -> opResult)
    io.oldOut  := Mux(io.isLogicSr, io.srLogicResult,
                  Mux(io.casIsOp, io.casResult,
                  Mux(anWide, opResult,
                  Mux(io.isMovea, io.moveaResult,
                  Mux(io.isFromCcrSr, io.sizeMergedMove, sizeMerged)))))
    io.oldNzvc := Mux(io.casIsOp, io.casNzvc,
                  Mux(io.toCcr, io.ccrNzvc, Mux(io.isBcd, io.bcdNzvc, io.aluNzvc)))
    io.oldX    := Mux(io.toCcr, io.ccrX, Mux(io.isBcd, io.bcdCarry, io.rspXOut))

    // ══════════ NEW: verbatim transcription of the re-associated shape ══════════
    val earlyOpResult = Mux(io.isBfResolve, io.bfResolveRes,
                        Mux(io.isPack, io.packRes8,
                        Mux(io.isUnpk, io.unpkRes32, io.bcd32)))
    val opIsAlu   = !io.isBfResolve && !io.isPack && !io.isUnpk && !io.isBcd
    val takeOpRes = !io.isLogicSr && !io.casIsOp && (anWide || (!io.isMovea && !io.isFromCcrSr))
    val earlyOther = Mux(io.isLogicSr, io.srLogicResult,
                     Mux(io.casIsOp, io.casResult,
                     Mux(io.isMovea, io.moveaResult, io.sizeMergedMove)))
    val keepOldHi  = !anWide && (io.size =/= Size.LONG)
    val keepOldMid = !anWide && (io.size === Size.BYTE)
    val takeAluLo  = takeOpRes && opIsAlu
    val takeAluMid = takeAluLo && !keepOldMid
    val takeAluHi  = takeAluLo && !keepOldHi
    val restLo  = Mux(takeOpRes, earlyOpResult(7 downto 0), earlyOther(7 downto 0))
    val restMid = Mux(takeOpRes, Mux(keepOldMid, io.s1Src1(15 downto 8),  earlyOpResult(15 downto 8)),
                                 earlyOther(15 downto 8))
    val restHi  = Mux(takeOpRes, Mux(keepOldHi,  io.s1Src1(31 downto 16), earlyOpResult(31 downto 16)),
                                 earlyOther(31 downto 16))
    io.newOut  := Mux(takeAluHi,  io.rspResult(31 downto 16), restHi) ##
                  Mux(takeAluMid, io.rspResult(15 downto 8),  restMid) ##
                  Mux(takeAluLo,  io.rspResult(7 downto 0),   restLo)
    val useAluNzvc = !io.casIsOp && !io.toCcr && !io.isBcd
    val nonAluNzvc = Mux(io.casIsOp, io.casNzvc, Mux(io.toCcr, io.ccrNzvc, io.bcdNzvc))
    io.newNzvc := Mux(useAluNzvc, io.aluNzvc, nonAluNzvc)
    val useAluX = !io.toCcr && !io.isBcd
    io.newX    := Mux(useAluX, io.rspXOut, Mux(io.toCcr, io.ccrX, io.bcdCarry))
  }

  test("re-association is value-identical over EVERY selector combination", VerilatorTest) {
    SimConfig.withVerilator.compile(new MergeDut).doSim(seed = 42) { dut =>
      val rnd   = new scala.util.Random(0xA1FEED)
      val sizes = Seq(Size.BYTE, Size.WORD, Size.LONG)
      // Distinct per-leg fills so ANY mis-selection or mis-slicing shows up as a
      // mismatch rather than aliasing onto another leg's value.
      def fill(tag: Int, i: Int): Long = ((tag.toLong << 24) | (rnd.nextInt() & 0x00FFFFFFL)) & 0xFFFFFFFFL
      var points = 0
      for (combo <- 0 until 1024; sz <- sizes.indices; rep <- 0 until 6) {
        dut.io.isBfResolve #= ((combo >> 0) & 1) == 1
        dut.io.isPack      #= ((combo >> 1) & 1) == 1
        dut.io.isUnpk      #= ((combo >> 2) & 1) == 1
        dut.io.isBcd       #= ((combo >> 3) & 1) == 1
        dut.io.isLogicSr   #= ((combo >> 4) & 1) == 1
        dut.io.casIsOp     #= ((combo >> 5) & 1) == 1
        dut.io.isMovea     #= ((combo >> 6) & 1) == 1
        dut.io.isFromCcrSr #= ((combo >> 7) & 1) == 1
        dut.io.opIsMove    #= ((combo >> 8) & 1) == 1
        dut.io.toCcr       #= ((combo >> 9) & 1) == 1
        dut.io.size        #= sizes(sz)
        dut.io.rspResult     #= fill(0x01, rep); dut.io.bfResolveRes #= fill(0x02, rep)
        dut.io.packRes8      #= fill(0x03, rep); dut.io.unpkRes32    #= fill(0x04, rep)
        dut.io.bcd32         #= fill(0x05, rep); dut.io.srLogicResult#= fill(0x06, rep)
        dut.io.casResult     #= fill(0x07, rep); dut.io.moveaResult  #= fill(0x08, rep)
        dut.io.sizeMergedMove#= fill(0x09, rep); dut.io.s1Src1       #= fill(0x0A, rep)
        dut.io.aluNzvc  #= rnd.nextInt(16); dut.io.casNzvc #= rnd.nextInt(16)
        dut.io.ccrNzvc  #= rnd.nextInt(16); dut.io.bcdNzvc #= rnd.nextInt(16)
        dut.io.rspXOut  #= rnd.nextBoolean(); dut.io.ccrX  #= rnd.nextBoolean()
        dut.io.bcdCarry #= rnd.nextBoolean()
        sleep(1)
        val o = dut.io.oldOut.toLong; val n = dut.io.newOut.toLong
        assert(o == n, f"result mismatch combo=$combo%d size=$sz%d rep=$rep%d old=$o%08x new=$n%08x")
        assert(dut.io.oldNzvc.toInt == dut.io.newNzvc.toInt, s"nzvc mismatch combo=$combo size=$sz")
        assert(dut.io.oldX.toBoolean == dut.io.newX.toBoolean, s"X mismatch combo=$combo size=$sz")
        points += 1
      }
      println(s"[AluMergeReassocSpec] $points differential points, 0 mismatches")
      assert(points == 1024 * 3 * 6)
    }
  }
}
