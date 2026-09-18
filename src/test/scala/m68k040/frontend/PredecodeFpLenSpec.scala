package m68k040.frontend

import m68k040.VerilatorTest
import m68k040.cache.ChunkPredecode
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Task 5: F-line FP-generic (cpGEN) length framing, with REAL extension words.
  *
  * PredecodeWordSpec's exhaustive 65536-opword sweep drives the 1-arg classify() overload
  * (extW = 0), so it only ever exercises the opclass-000 register form. Everything whose
  * length depends on the extension word lives here.
  *
  * NOTE on that sweep: it aborts at the FIRST mismatch, so "PredecodeWordSpec passes" is
  * not evidence that the rest of the space was checked. This spec deliberately ACCUMULATES
  * failures and reports them together.
  */
class PredecodeFpLenSpec extends AnyFunSuite {
  class Dut extends Component {
    val op    = in Bits (16 bits)
    val extW  = in Bits (16 bits)   // op+1 : the FP extension word
    val extW2 = in Bits (16 bits)   // op+2 : the <ea>'s own first extension word
    val res   = out(ChunkPredecode())
    res := PredecodeWord.classify(op, extW, extW2)
  }

  private def run(body: (Dut, (Int, Int, Int, Int, String) => Unit) => Unit): Unit =
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val fails = scala.collection.mutable.ArrayBuffer[String]()
      def chk(op: Int, ext: Int, ext2: Int, len: Int, name: String): Unit = {
        dut.op #= op; dut.extW #= ext; dut.extW2 #= ext2; sleep(1)
        if (!dut.res.simple.toBoolean)
          fails += f"$name (op=0x$op%04x ext=0x$ext%04x): expected simple"
        else if (dut.res.lenWords.toInt != len)
          fails += f"$name (op=0x$op%04x ext=0x$ext%04x): len=${dut.res.lenWords.toInt} expected=$len"
      }
      body(dut, chk)
      assert(fails.isEmpty, s"${fails.size} framing mismatches:\n" + fails.mkString("\n"))
    }

  test("cpGEN register-to-register and FMOVECR forms frame as 2 words", VerilatorTest) {
    run { (_, chk) =>
      // FADD FP1,FP0   : opclass 000, src FP1, dst FP0, opmode 0x22
      chk(0xF200, 0x0422, 0x0000, 2, "FADD FP1,FP0")
      // FMUL FP2,FP3   : opclass 000, src FP2, dst FP3, opmode 0x23
      chk(0xF200, 0x09A3, 0x0000, 2, "FMUL FP2,FP3")
      // FTST FP0       : opclass 000, opmode 0x3A
      chk(0xF200, 0x003A, 0x0000, 2, "FTST FP0")
      // FMOVECR #$0F,FP0: opclass 010, source specifier 111, ROM offset 0x0F.
      // This literal is Musashi's own documented example (m68kfpu.c: "fmovecr #$f, fp0 f200 5c0f").
      chk(0xF200, 0x5C0F, 0x0000, 2, "FMOVECR #$0F,FP0")
      // A NON-native opmode must be framed IDENTICALLY -- length is opmode-independent, and
      // this is exactly the FPSP-routed case that needs a known length to RTE past.
      chk(0xF200, 0x000E, 0x0000, 2, "FSIN FP0,FP0 (FPSP-routed, still framed)")
    }
  }

  // ── Task 9b: FMOVEM control-register LIST form ──────────────────────────────────
  // Length is `2 + the <ea>'s own extension words` and is INDEPENDENT of the register
  // mask's popcount — the mask only affects the execute-time transfer count and the
  // microcode program length, never the fetched instruction's own length. Every literal
  // below is real `m68k-linux-gnu-as -m68040 -m68881` output (this task's report §1).
  test("FMOVEM control-register list: length is 2 + EA ext, independent of popcount", VerilatorTest) {
    run { (_, chk) =>
      // Store direction (ddd = 101). -(A7) / (A0) / (A0)+ : no EA extension word.
      chk(0xF227, 0xBC00, 0x0000, 2, "FMOVEM.L FPIAR/FPSR/FPCR,-(A7)  [the FPSP prologue]")
      chk(0xF227, 0xB800, 0x0000, 2, "FMOVEM.L FPCR/FPSR,-(A7)        [popcount 2]")
      chk(0xF210, 0xB000, 0x0000, 2, "FMOVE.L  FPCR,(A0)              [popcount 1]")
      chk(0xF210, 0xAC00, 0x0000, 2, "FMOVEM.L FPIAR/FPSR,(A0)")
      chk(0xF218, 0xBC00, 0x0000, 2, "FMOVEM.L FPIAR/FPSR/FPCR,(A0)+")
      // Load direction (ddd = 100) frames identically.
      chk(0xF21F, 0x9C00, 0x0000, 2, "FMOVEM.L (A7)+,FPIAR/FPSR/FPCR")
      chk(0xF210, 0x9800, 0x0000, 2, "FMOVEM.L (A0),FPCR/FPSR")
      chk(0xF210, 0x9000, 0x0000, 2, "FMOVE.L  (A0),FPCR              [popcount 1]")
      // (d16,An) = 3 words; abs.W = 3; abs.L = 4; brief-indexed = 3. Same for BOTH
      // directions and every popcount — the mask never moves the length.
      chk(0xF228, 0xBC00, 0x0008, 3, "FMOVEM.L FPIAR/FPSR/FPCR,(8,A0)")
      chk(0xF228, 0x9C00, 0x0008, 3, "FMOVEM.L (8,A0),FPIAR/FPSR/FPCR")
      chk(0xF228, 0x9000, 0x0008, 3, "FMOVE.L  (8,A0),FPCR            [popcount 1, same length]")
      chk(0xF238, 0xBC00, 0x1234, 3, "FMOVEM.L FPIAR/FPSR/FPCR,(0x1234).W")
      chk(0xF239, 0xBC00, 0x1234, 4, "FMOVEM.L FPIAR/FPSR/FPCR,(0x12345678).L")
      chk(0xF230, 0xBC00, 0x1004, 3, "FMOVEM.L FPIAR/FPSR/FPCR,(4,A0,D1.W)")
      // PC-relative: framed here (predecode is direction- and legality-agnostic); the
      // ACCEPT gate that rejects it lives in DecodeStage, not in framing.
      chk(0xF23A, 0x9C00, 0x000A, 3, "FMOVEM.L (10,PC),FPIAR/FPSR/FPCR")
      chk(0xF23B, 0x9C00, 0x0804, 3, "FMOVEM.L (4,PC,D0.L),FPIAR/FPSR/FPCR")
      // A mask of 000, and a reserved non-zero ext[9:0] tail, are rejected at DECODE (a
      // vector-11 trap) but must still be FRAMED here -- an unframed length would make
      // that trap un-RTE-able and so un-completable by a real FPSP kernel.
      chk(0xF210, 0x8000, 0x0000, 2, "mask 000 (trapped at decode, still framed)")
      chk(0xF210, 0x9C01, 0x0000, 2, "reserved ext tail (trapped at decode, still framed)")
    }
  }

  test("cpGEN <ea>-source forms add the EA's own extension words", VerilatorTest) {
    run { (_, chk) =>
      // FADD.L D1,FP0     : opclass 010, src spec 000 (long int), EA mode 0 reg 1 -> 0 ext
      chk(0xF201, 0x4022, 0x0000, 2, "FADD.L D1,FP0")
      // FADD.L (A0),FP0   : EA mode 2 -> 0 ext
      chk(0xF210, 0x4022, 0x0000, 2, "FADD.L (A0),FP0")
      // FADD.L (d16,A0),FP0: EA mode 5 -> 1 ext
      chk(0xF228, 0x4022, 0x0004, 3, "FADD.L (d16,A0),FP0")
      // FADD.L (xxx).L,FP0: EA mode 7 reg 1 -> 2 ext
      chk(0xF239, 0x4022, 0x0000, 4, "FADD.L (xxx).L,FP0")
      // FADD.L (d8,A0,Xn),FP0: EA mode 6, BRIEF format (extW2 bit8 = 0) -> 1 ext
      chk(0xF230, 0x4022, 0x1000, 3, "FADD.L (d8,A0,Xn),FP0 brief")
      // FMOVE.X FP0,(A0)  : opclass 011 (FMOVE FPn -> <ea>), EA mode 2 -> 0 ext.
      // Emission is deferred (this store direction remains unowned by any task in this
      // plan); the LENGTH is framed now so the vector-11 trap carries a known length and
      // FPSP can RTE past it.
      chk(0xF210, 0x6800, 0x0000, 2, "FMOVE.X FP0,(A0) [framed, emission deferred]")
      // FMOVEM.X (A0),FP0-FP7 : opclass 110, EA mode 2 -> 0 ext (same reasoning)
      chk(0xF210, 0xD0FF, 0x0000, 2, "FMOVEM.X (A0),FP0-FP7 [framed, emission deferred]")
    }
  }

  test("cpGEN #imm source length follows the FP source SPECIFIER, not the op size", VerilatorTest) {
    run { (_, chk) =>
      val immEa = 0xF23C   // cpGEN with <ea> = mode 7 reg 4 (#imm)
      chk(immEa, 0x4022, 0x0000, 4, "FADD.L #imm,FP0   (long   -> 2 imm words)")
      chk(immEa, 0x4422, 0x0000, 4, "FADD.S #imm,FP0   (single -> 2 imm words)")
      chk(immEa, 0x4822, 0x0000, 8, "FADD.X #imm,FP0   (ext    -> 6 imm words)")
      chk(immEa, 0x5022, 0x0000, 3, "FADD.W #imm,FP0   (word   -> 1 imm word)")
      chk(immEa, 0x5422, 0x0000, 6, "FADD.D #imm,FP0   (double -> 4 imm words)")
      chk(immEa, 0x5822, 0x0000, 3, "FADD.B #imm,FP0   (byte   -> 1 imm word)")
    }
  }

  test("unframeable cpGEN EAs and the non-cpGEN line-F space keep the 1-word trap framing", VerilatorTest) {
    run { (_, chk) =>
      // Reserved <ea> encodings (mode 7 regs 5/6/7) -> eaExt rejects -> 1-word F-line trap.
      chk(0xF23D, 0x4022, 0x0000, 1, "cpGEN with reserved <ea> mode7/reg5")
      chk(0xF23F, 0x4022, 0x0000, 1, "cpGEN with reserved <ea> mode7/reg7")
      // FScc / FBcc / FSAVE / FRESTORE (opword bits[8:6] != 000) are NOT cpGEN -- but they
      // are no longer UNFRAMED either. The three expectations below used to read `1` from
      // when this file was written, i.e. before PredecodeWord grew its `fpScc` / `fpBcc`
      // arms; a 1-word frame on any of them fetches the CONDITION WORD or the DISPLACEMENT
      // as the next opword (see those arms' own comments). Corrected 2026-09-18 against the
      // ASSEMBLER, not against the RTL -- `m68k-linux-gnu-as -m68040 -m68881` emits:
      //     fseq  %d0   ->  f240 0001            = 2 words  (opword + condition word)
      //     fbeq  lbl   ->  f281 fffe            = 2 words  (opword + disp16)
      //     fbeq.l lbl  ->  f2c1 ffff fff8       = 3 words  (opword + disp32)
      // (M68000PRM: every cpID-001 type-001 encoding carries a mandatory coprocessor
      // CONDITION word after the opword; FBcc type 010/011 carry disp16/disp32 instead.)
      chk(0xF240, 0x0000, 0x0000, 2, "FScc Dn (type 001): opword + condition word")
      chk(0xF280, 0x0000, 0x0000, 2, "FBcc.W (type 010): opword + disp16")
      chk(0xF2C0, 0x0000, 0x0000, 3, "FBcc.L (type 011): opword + disp32")
      // FSAVE Dn is not a legal FSAVE <ea> (control-alterable only); the 1-word framing is
      // the F-line trap framing, and the trap stacks the FAULTING pc, so it is sound.
      chk(0xF300, 0x0000, 0x0000, 1, "FSAVE-band opword (bit 8 set -- Task 9/11)")
      // The pre-existing carve-outs are unchanged.
      chk(0xF27F, 0x0000, 0x0000, 4, "FSF (xxx).L")
      chk(0xF620, 0x0000, 0x0000, 2, "MOVE16 (Ax)+,(Ay)+")
      chk(0xF600, 0x0000, 0x0000, 1, "MOVE16 absolute form (out of scope, 1 word)")
    }
  }

  test("an unresident FP extension word frames 2 words AND flags ambiguousLine", VerilatorTest) {
    // extWValid=false is the I-cache-line-boundary case: the Aligner must re-resolve or
    // stall, so the guessed length must never be trusted downstream.
    class AmbDut extends Component {
      val op  = in Bits (16 bits)
      val res = out(ChunkPredecode())
      res := PredecodeWord.classify(op, B(0, 16 bits), B(0, 16 bits),
                                    extWValid = false, extW2Valid = false)
    }
    SimConfig.withVerilator.compile(new AmbDut).doSim { dut =>
      dut.op #= 0xF200; sleep(1)
      assert(dut.res.simple.toBoolean, "cpGEN with an unknown ext word must stay simple (guess + flag)")
      assert(dut.res.lenWords.toInt == 2, s"guess must be the 2-word register form, got ${dut.res.lenWords.toInt}")
      assert(dut.res.ambiguousLine.toBoolean,
        "the guess MUST set ambiguousLine so Aligner re-resolves or stalls (Aligner.scala:95,106,267)")
      dut.op #= 0xF27F; sleep(1)
      assert(!dut.res.ambiguousLine.toBoolean, "the FSF carve-out is opword-only -- never ambiguous")
    }
  }
}
