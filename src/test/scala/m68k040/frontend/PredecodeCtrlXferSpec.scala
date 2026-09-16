package m68k040.frontend

import m68k040.VerilatorTest
import m68k040.decode.{OperationDecoder, OpForm}
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** EXHAUSTIVE equivalence proof for `ChunkPredecode.ctrlXfer`.
  *
  * `PredecodeWord.classify` writes `ctrlXfer` as seven direct opword comparators rather
  * than by consuming `OperationDecoder`'s `form` enum, because `form` at each of the 16
  * per-beat predecode instances would un-prune that whole decode cone (the `size` field
  * already pays ~715 LUTs for one enum-free field). That is an AREA choice, and it is
  * only sound if the two agree — so this suite proves the equivalence in HARDWARE, over
  * all 65536 opwords, against the REAL decoder rather than against a second hand-written
  * table:
  *
  *   classify(op).ctrlXfer  ===  decode(op).isBranch || decode(op).form in BRANCH_FORMS
  *
  * That reference set is exactly "an opword whose decode produces a µop reaching
  * `BranchEuPlugin` that the BTB/FTB is allowed to train" (`BranchEuPlugin.isBtbBranch`)
  * plus the returns the RAS and the FTB confirmation path care about:
  *   - `isBranch` (OpSpec): line-6 Bcc/BRA/BSR, and FBcc.W/.L with a REAL condition
  *     (`isFpBccF`, i.e. cc==0 / FBF / FNOP, is carved out there — it decodes as a plain
  *     NOP and never reaches the branch EU, so it must NOT be predictable).
  *   - JMP / JSR      : `MicroOpAssembler`'s `ibrUop` (isBranch && ibranch).
  *   - DBCC / FDBCC   : `MicroOpAssembler`'s `dbccUop` (both, FDBcc via fromFpcc=true).
  *   - RTS / RTR / RTD: `retBranchUop` / `rtdBranch` (isBranch && ibranch && isReturn).
  *   - RTE            : serializing, redirects at retire; included so the bit means
  *                      "control transfer", not "BTB-trainable" minus one odd case.
  *
  * If anyone adds a branch form to `OperationDecoder`, this test fails until the
  * predecode comparators learn it — which is the property that keeps the fetch-side gate
  * from silently losing predictions.
  */
class PredecodeCtrlXferSpec extends AnyFunSuite {

  class Dut extends Component {
    val op        = in Bits (16 bits)
    val ctrlXfer  = out Bool ()
    val refBranch = out Bool ()
    ctrlXfer := PredecodeWord.classify(op).ctrlXfer
    val spec = OperationDecoder.decode(op)
    refBranch := spec.isBranch ||
      (spec.form === OpForm.JMP)  || (spec.form === OpForm.JSR)  ||
      (spec.form === OpForm.DBCC) || (spec.form === OpForm.FDBCC) ||
      (spec.form === OpForm.RTS)  || (spec.form === OpForm.RTR)  ||
      (spec.form === OpForm.RTD)  || (spec.form === OpForm.RTE)
  }

  test("ctrlXfer matches OperationDecoder's branch classification for ALL 65536 opwords", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      var mismatches = List.empty[String]
      var nCtrl = 0
      for (op <- 0 until 65536) {
        dut.op #= op
        sleep(1)
        val got = dut.ctrlXfer.toBoolean
        val exp = dut.refBranch.toBoolean
        if (got) nCtrl += 1
        if (got != exp && mismatches.size < 40)
          mismatches = f"op=0x$op%04x ctrlXfer=$got decoderSaysBranch=$exp" :: mismatches
        // The pure-Scala mirror the sim harnesses use to build a self-consistent
        // FetchRsp.pred must not drift from the RTL either.
        val mirror = PredecodeRef.ctrlXfer(op)
        if (got != mirror && mismatches.size < 40)
          mismatches = f"op=0x$op%04x ctrlXfer=$got PredecodeRef.ctrlXfer=$mirror" :: mismatches
      }
      assert(mismatches.isEmpty,
        s"${mismatches.size}+ opwords disagree:\n" + mismatches.reverse.mkString("\n"))
      // Sanity floor: line 6 alone is 4096 opwords, so a classifier that folded to a
      // constant False (the failure mode a pure "no mismatch" assertion cannot see, since
      // the reference is elaborated from the same opword) would be caught here.
      assert(nCtrl > 4096, s"implausibly few control transfers classified: $nCtrl")
      info(s"$nCtrl of 65536 opwords classify as control transfers")
    }
  }

  /** FAIL-CLOSED / never-ambiguous proof.
    *
    * `ambiguousLine` exists because refill-time predecode sometimes cannot see the
    * extension words it needs to frame a length -- they can live past the 64-byte line
    * boundary. `ctrlXfer` is claimed to be immune because a 68k branch opcode is
    * identifiable from its FIRST WORD alone. That claim is load-bearing (the fetch-side
    * gate reads `preds(0).ctrlXfer` DIRECTLY, bypassing Aligner's `p0` ambiguity mux), so
    * it is MEASURED here rather than asserted: for every opword, `ctrlXfer` must be
    * identical with all six extension words present and valid, with them absent and
    * flagged invalid, and with them carrying the complementary bit pattern.
    */
  class AmbDut extends Component {
    val op   = in Bits (16 bits)
    val ext  = in Bits (16 bits)
    val vld  = in Bool ()
    val res  = out Bool ()
    res := PredecodeWord.classify(op, ext, ext, ext, ext, ext, ext,
      vld, vld, vld, vld, vld, vld).ctrlXfer
  }

  test("ctrlXfer is invariant to extension-word content and residency (never ambiguous)", VerilatorTest) {
    SimConfig.withVerilator.compile(new AmbDut).doSim { dut =>
      var bad = List.empty[String]
      for (op <- 0 until 65536) {
        dut.op #= op
        val vals = for ((e, v) <- Seq((0x0000, true), (0xffff, true), (0x0000, false),
                                      (0xffff, false), (0x0170, true), (0x0170, false))) yield {
          dut.ext #= e; dut.vld #= v; sleep(1); dut.res.toBoolean
        }
        if (vals.distinct.size != 1 && bad.size < 20)
          bad = f"op=0x$op%04x ctrlXfer varied with extension words: $vals" :: bad
        if (vals.head != PredecodeRef.ctrlXfer(op) && bad.size < 20)
          bad = f"op=0x$op%04x ext-form disagrees with the reference" :: bad
      }
      assert(bad.isEmpty, bad.reverse.mkString("\n"))
    }
  }

  test("named encodings classify as expected", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      def chk(op: Int, exp: Boolean, name: String): Unit = {
        dut.op #= op; sleep(1)
        assert(dut.ctrlXfer.toBoolean == exp,
          f"$name (0x$op%04x): ctrlXfer=${dut.ctrlXfer.toBoolean} expected=$exp")
      }
      // ---- control transfers ----
      chk(0x6000, true,  "BRA.W")
      chk(0x60FE, true,  "BRA.B")
      chk(0x6100, true,  "BSR.W")
      chk(0x6700, true,  "BEQ.W")
      chk(0x66F0, true,  "BNE.B")
      chk(0x51C8, true,  "DBF D0")
      chk(0x57CF, true,  "DBEQ D7")
      chk(0x4E90, true,  "JSR (A0)")
      chk(0x4EB9, true,  "JSR (xxx).L")
      chk(0x4ED0, true,  "JMP (A0)")
      chk(0x4EFA, true,  "JMP (d16,PC)")
      chk(0x4E73, true,  "RTE")
      chk(0x4E74, true,  "RTD")
      chk(0x4E75, true,  "RTS")
      chk(0x4E77, true,  "RTR")
      chk(0xF281, true,  "FBcc.W (cc=1, FBEQ)")
      chk(0xF2FF, true,  "FBcc.L (cc=0x3f)")
      chk(0xF248, true,  "FDBcc D0")
      // ---- NOT control transfers (the ones worth pinning) ----
      chk(0xF280, false, "FBF.W / FNOP -- decodes as a NOP, never reaches the branch EU")
      chk(0xF2C0, false, "FBF.L -- same carve-out")
      chk(0x4E70, false, "RESET")
      chk(0x4E71, false, "NOP")
      chk(0x4E72, false, "STOP")
      chk(0x4E76, false, "TRAPV")
      chk(0x4E40, false, "TRAP #0")
      chk(0x4E50, false, "LINK.W A0")
      chk(0x4E58, false, "UNLK A0")
      chk(0x50C0, false, "ST D0 (Scc, mode 000)")
      chk(0x57D0, false, "SEQ (A0) (Scc, mode 010)")
      chk(0x50FC, false, "TRAPcc (mode 111 reg 4)")
      chk(0xF24F, true,  "FDBcc D7")
      chk(0xF250, false, "FScc (A0) -- type 001, mode 010")
      chk(0xF27C, false, "FTRAPcc -- type 001, mode 111 reg 4")
      chk(0xF200, false, "FP generic cpGEN")
      chk(0xF3BF, false, "type 100+ (FMOVE ctrl / FMOVEM), bit8=1 -- NOT FBcc")
      chk(0xF4D0, false, "CPUSH")
      chk(0x2000, false, "MOVE.L D0,D0")
      chk(0xD081, false, "ADD.L D1,D0")
      chk(0x3EBA, false, "MOVE.W (d16,PC),(A7)")
      chk(0x0000, false, "ORI.B -- the zero-fill default")
      chk(0x5080, false, "ADDQ.L #8,D0")
      chk(0x5188, false, "SUBQ.L #8,A0")
    }
  }
}
