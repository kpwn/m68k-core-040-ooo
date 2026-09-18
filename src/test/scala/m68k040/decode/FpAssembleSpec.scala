package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.{Cluster, Size}
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class FpAssembleSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt = in(DecodePacket())
    val uop = out(DecodedUop())
    // The 80-bit FP immediate left the uop record (plan item 3): it is now an
    // `AssembledUops` SIDE CHANNEL that DecodeStage writes into its FP wide-immediate
    // side table. Expose it so the assembler-level checks below still observe the value.
    val fpImmAlloc = out Bool()
    val fpWideImm  = out Bits(80 bits)
    private val asm = MicroOpAssembler.assemble(pkt)
    uop        := asm.uops(0)
    fpImmAlloc := asm.fpImmAlloc
    fpWideImm  := asm.fpWideImm
  }
  /** Drive a packet whose length matches what PredecodeWord would really frame. */
  def drive(dut: Dut, op: Int, ext: Int = 0, ext2: Int = 0, len: Int = 2, simple: Boolean = true): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x2000; dut.pkt.simple #= simple; dut.pkt.complex #= !simple
    dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    dut.pkt.words(0) #= op; dut.pkt.words(1) #= ext; dut.pkt.words(2) #= ext2
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }
  def run(check: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim(check)

  test("ROM FSCALE immediate before FDIV requires an FPU emulation frame", VerilatorTest) {
    run { dut =>
      // Board ROM: 408eeeCE F23C 5926 0001 = FSCALE.B #1,FP2;
      //            408eeeD4 F200 0520      = FDIV FP1,FP2.
      // MC68040UM 9.6.1: recognized unsupported FP instructions use vector11,
      // a format2 frame and the following instruction's PC, not generic F-line.
      drive(dut, op = 0xF200, ext = 0x0520, len = 2)
      dut.pkt.pc #= 0x408eeed4L
      sleep(1)
      assert(!dut.uop.faulted.toBoolean && dut.uop.op.toEnum == DecOp.FPU,
        "the displayed FDIV must execute natively")
      assert(dut.uop.fpuOp.toInt == 0x20)

      drive(dut, op = 0xF23C, ext = 0x5926, ext2 = 1, len = 3)
      dut.pkt.pc #= 0x408eeeceL
      sleep(1)
      assert(dut.uop.faulted.toBoolean && dut.uop.faultVector.toInt == 11)
      assert(dut.uop.faultUsesNextPc.toBoolean && dut.uop.lenWords.toInt == 3)
      assert(dut.uop.fpuSoftwareComplete.toBoolean,
        "FSCALE.B immediate needs format2/FPSP state, not format0 with the following FDIV PC")
      assert(dut.uop.fpuCmdWord.toInt == 0x5926)
    }
  }

  // ── Step 2's standalone fix: the two line-F trap PC flavors ────────────────────
  test("a FRAMED line-F encoding traps to vector 11 with the POST-instruction PC", VerilatorTest) {
    run { dut =>
      // FSIN FP0,FP0 (opmode 0x0E) -- a real cpGEN instruction, framed 2 words by Task 5,
      // NOT hardware-native, so it is exactly the FPSP-routed case.
      drive(dut, op = 0xF200, ext = 0x000E, len = 2); sleep(1)
      assert(dut.uop.faulted.toBoolean, "an unimplemented FP op must fault")
      assert(dut.uop.faultVector.toInt == 11, s"architectural vector stays 11, got ${dut.uop.faultVector.toInt}")
      assert(dut.uop.faultUsesNextPc.toBoolean,
        "a framed line-F trap must stack the POST-instruction PC, else FPSP's RTE re-executes the same opword forever")
      assert(dut.uop.lenWords.toInt == 2,
        f"lenWords must be 2 (nextPc = pc + 2*lenWords = 0x2004), got ${dut.uop.lenWords.toInt}")
    }
  }

  test("an UNFRAMED line-F encoding keeps the PRE-instruction (faulting) PC", VerilatorTest) {
    run { dut =>
      // FBcc.W (type 010) -- not cpGEN, predecode frames it 1 word, length unknown.
      drive(dut, op = 0xF280, ext = 0x0000, len = 1); sleep(1)
      assert(dut.uop.faulted.toBoolean && dut.uop.faultVector.toInt == 11)
      assert(!dut.uop.faultUsesNextPc.toBoolean,
        "an unknown-length line-F trap MUST stay restartable (faulting PC) -- the handler cannot know the length")
    }
  }

  // Task 6b UPDATE: a cpGEN opword with a RESERVED <ea> (mode 7/reg 5..7, e.g. 0xF23D) is
  // NOT Dn(0)/An(1)/#imm(7,4) -- it now falls into Task 6b's memory-mode-<ea> µcode gate
  // (OperationDecoder cannot distinguish "reserved" from "genuine memory" without the ext
  // word either, exactly like every other opclass sharing this opword band -- Finding 1).
  // The trap decision for THIS shape has moved from THIS assembler-only layer to
  // `DecodeStage.ucBegin`'s `EaClass =/= MEMSIMPLE` rejection (verified end-to-end by
  // `FpMemLoadSpec`, which exercises the real µcode-ROM walk this `Dut` cannot). At
  // THIS layer, the only observable is that the opword got routed to the µcode engine
  // at all (not silently left as an ordinary "bad"/illegal instruction).
  test("a cpGEN opword with a RESERVED <ea> routes to the µcode engine (Task 6b) instead of trapping here", VerilatorTest) {
    class DecDut extends Component {
      val opword = in Bits (16 bits)
      val o      = out(OpSpec())
      o := OperationDecoder.decode(opword)
    }
    SimConfig.withVerilator.compile(new DecDut).doSim { dut =>
      dut.opword #= 0xF23D; sleep(1)   // mode 7 / reg 5 -- reserved, not Dn/An/#imm
      assert(!dut.o.illegal.toBoolean, "cpGEN family stays non-illegal (the µcode engine owns emission)")
      assert(dut.o.microcoded.toBoolean, "routed to the µcode engine -- ucBegin resolves accept/reject for real")
      assert(dut.o.op.toEnum == DecOp.FPU)
    }
  }

  test("line-A and generic illegal traps are untouched by the line-F change", VerilatorTest) {
    run { dut =>
      drive(dut, op = 0xA000, len = 1); sleep(1)
      assert(dut.uop.faultVector.toInt == 10 && !dut.uop.faultUsesNextPc.toBoolean, "line-A vector 10, faulting PC")
      drive(dut, op = 0x4AFC, len = 1); sleep(1)   // ILLEGAL
      assert(dut.uop.faultVector.toInt == 4 && !dut.uop.faultUsesNextPc.toBoolean, "generic illegal vector 4, faulting PC")
    }
  }

  // ── Step 6: FP-emission directed tests ──────────────────────────────────────

  test("FADD FP1,FP0 emits one CPLX FP uop: dyadic reads FPn, source FPm, writes FPn+FPCC", VerilatorTest) {
    run { dut =>
      drive(dut, op = 0xF200, ext = 0x0422, len = 2); sleep(1)   // opclass 000, src FP1, dst FP0, opmode 0x22
      assert(!dut.uop.faulted.toBoolean, "FADD is hardware-native -- it must not trap")
      assert(dut.uop.op.toEnum == DecOp.FPU && dut.uop.cluster.toEnum == Cluster.CPLX)
      assert(dut.uop.fpuOp.toInt == 0x22, s"fpuOp must be the raw opmode 0x22, got 0x${dut.uop.fpuOp.toInt.toHexString}")
      assert(dut.uop.fpSrcKind.toEnum == FpSrcKind.FPREG)
      assert(dut.uop.usesFpSrcA.toBoolean && dut.uop.fpSrcAReg.toInt == 0,
        "a DYADIC op reads its destination FP0 as an operand")
      assert(dut.uop.usesFpSrcB.toBoolean && dut.uop.fpSrcBReg.toInt == 1, "source is FP1")
      assert(dut.uop.writesFp.toBoolean && dut.uop.fpDstReg.toInt == 0, "destination is FP0")
      assert(dut.uop.writesFpcc.toBoolean, "every FP op writes FPCC")
      assert(!dut.uop.readsFpcc.toBoolean, "nothing reads FPCC yet (FBcc/FScc deferred)")
      assert(!dut.uop.dstValid.toBoolean && !dut.uop.srcAValid.toBoolean && !dut.uop.srcBValid.toBoolean,
        "an FP register-form uop touches NO integer registers")
      assert(!dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean, "FP ops never touch the integer CCR")
      assert(dut.uop.firstOfInstr.toBoolean, "single uop -- it is the macro boundary")
    }
  }

  test("monadic FP ops do NOT read their destination (no false RAW dependency)", VerilatorTest) {
    run { dut =>
      // FABS FP2,FP3 : opclass 000, src FP2, dst FP3, opmode 0x18 -> ext = 0x09 98
      drive(dut, op = 0xF200, ext = 0x0998, len = 2); sleep(1)
      assert(!dut.uop.faulted.toBoolean && dut.uop.fpuOp.toInt == 0x18)
      assert(!dut.uop.usesFpSrcA.toBoolean, "FABS is monadic -- it must not claim its destination as a source")
      assert(dut.uop.usesFpSrcB.toBoolean && dut.uop.fpSrcBReg.toInt == 2)
      assert(dut.uop.writesFp.toBoolean && dut.uop.fpDstReg.toInt == 3)
    }
  }

  test("FCMP and FTST write ONLY the condition codes", VerilatorTest) {
    run { dut =>
      drive(dut, op = 0xF200, ext = 0x04B8, len = 2); sleep(1)   // FCMP FP1,FP1: opmode 0x38, src FP1, dst FP1
      assert(!dut.uop.faulted.toBoolean && dut.uop.fpuOp.toInt == 0x38)
      assert(!dut.uop.writesFp.toBoolean, "FCMP writes no FP register")
      assert(dut.uop.writesFpcc.toBoolean, "FCMP writes FPCC")
      assert(dut.uop.usesFpSrcA.toBoolean, "FCMP is dyadic -- it reads the destination operand")
      drive(dut, op = 0xF200, ext = 0x003A, len = 2); sleep(1)   // FTST FP0
      assert(!dut.uop.faulted.toBoolean && dut.uop.fpuOp.toInt == 0x3A)
      assert(!dut.uop.writesFp.toBoolean && dut.uop.writesFpcc.toBoolean)
      assert(!dut.uop.usesFpSrcA.toBoolean, "FTST is monadic (a classifier, not a compare against zero)")
    }
  }

  test("FMOVE.L D3,FP0 sources an INTEGER register on the ordinary int rename path", VerilatorTest) {
    run { dut =>
      // opclass 010, source specifier 000 (long int), <ea> = mode 0 reg 3, opmode 0x00
      drive(dut, op = 0xF203, ext = 0x4000, len = 2); sleep(1)
      assert(!dut.uop.faulted.toBoolean)
      assert(dut.uop.fpSrcKind.toEnum == FpSrcKind.INTREG)
      assert(dut.uop.srcAValid.toBoolean && dut.uop.srcAReg.toInt == 3, "int source is D3 via srcA")
      assert(!dut.uop.usesFpSrcB.toBoolean, "no FP register source in the int-source form")
      assert(!dut.uop.usesFpSrcA.toBoolean, "FMOVE is monadic")
      assert(dut.uop.writesFp.toBoolean && dut.uop.fpDstReg.toInt == 0)
      assert(dut.uop.size.toEnum == Size.LONG)
      assert(dut.uop.fpSrcFmt.toInt == 0, "fpSrcFmt carries ext[12:10] verbatim (000 = Long)")
    }
  }

  test("FMOVE.S D3,FP0 sets fpSrcFmt=Single (distinguishes int-register Long vs Single)", VerilatorTest) {
    run { dut =>
      // opclass 010, source specifier 001 (single), <ea> = mode 0 reg 3, opmode 0x00
      drive(dut, op = 0xF203, ext = 0x4400, len = 2); sleep(1)
      assert(!dut.uop.faulted.toBoolean)
      assert(dut.uop.fpSrcKind.toEnum == FpSrcKind.INTREG, "still an int-register READ -- the bit pattern rides Dn")
      assert(dut.uop.fpSrcFmt.toInt == 1, "fpSrcFmt=001 (Single) -- this is what tells Task 8 NOT to convert as an integer")
      assert(dut.uop.size.toEnum == Size.LONG, "size alone cannot distinguish Long vs Single -- both read a full 32-bit Dn")
    }
  }

  test("FMOVECR #$0F,FP0 sources the constant ROM via imm, no register source", VerilatorTest) {
    run { dut =>
      drive(dut, op = 0xF200, ext = 0x5C0F, len = 2); sleep(1)   // Musashi's own documented literal
      assert(!dut.uop.faulted.toBoolean)
      assert(dut.uop.fpSrcKind.toEnum == FpSrcKind.ROMCONST)
      assert(dut.uop.useImm.toBoolean && dut.uop.imm.toInt == 0x0F, "the ROM offset rides imm")
      assert(!dut.uop.usesFpSrcA.toBoolean && !dut.uop.usesFpSrcB.toBoolean && !dut.uop.srcAValid.toBoolean)
      assert(dut.uop.writesFp.toBoolean && dut.uop.fpDstReg.toInt == 0 && dut.uop.writesFpcc.toBoolean)
    }
  }

  // ── ROM-offset/opmode aliasing regression ─────────────────────────────────────
  // $38 and $3A are REAL, defined FMOVECR ROM offsets (10^32 and 10^128 -- cromWords
  // indices 14/16, see FpCheapPipe.scala's cromIndexOf/cromWords) that numerically alias
  // fpNoFpDst's FCMP ($38) and FTST ($3A) opmodes. `writesFp := !fpNoFpDst` (the plain
  // FCMP/FTST default, driven unconditionally before the fpFormIsMovecr branch) must be
  // overridden back to True inside the FMOVECR branch -- FMOVECR always writes FPn,
  // unlike genuine FCMP/FTST. Without the override this silently decodes with
  // writesFp=False, so rename allocates no FP destination and the EU's result is
  // discarded (wrong answer, no fault, no hang).
  test("FMOVECR #$38,FP0 (10^32) still writes FPn despite aliasing FCMP's opmode", VerilatorTest) {
    run { dut =>
      drive(dut, op = 0xF200, ext = 0x5C38, len = 2); sleep(1)
      assert(!dut.uop.faulted.toBoolean)
      assert(dut.uop.fpSrcKind.toEnum == FpSrcKind.ROMCONST)
      assert(dut.uop.useImm.toBoolean && dut.uop.imm.toInt == 0x38, "the ROM offset rides imm")
      assert(dut.uop.writesFp.toBoolean, "FMOVECR #$38 must write FP0 -- $38 is a ROM offset here, not FCMP's opmode")
      assert(dut.uop.fpDstReg.toInt == 0 && dut.uop.writesFpcc.toBoolean)
    }
  }

  test("FMOVECR #$3A,FP0 (10^128) still writes FPn despite aliasing FTST's opmode", VerilatorTest) {
    run { dut =>
      drive(dut, op = 0xF200, ext = 0x5C3A, len = 2); sleep(1)
      assert(!dut.uop.faulted.toBoolean)
      assert(dut.uop.fpSrcKind.toEnum == FpSrcKind.ROMCONST)
      assert(dut.uop.useImm.toBoolean && dut.uop.imm.toInt == 0x3A, "the ROM offset rides imm")
      assert(dut.uop.writesFp.toBoolean, "FMOVECR #$3A must write FP0 -- $3A is a ROM offset here, not FTST's opmode")
      assert(dut.uop.fpDstReg.toInt == 0 && dut.uop.writesFpcc.toBoolean)
    }
  }

  // ── Immediate-source forms (this deliverable's extended scope) ───────────────
  // <ea> = mode 7 / reg 4 (#imm) for every case below: op = 0xF200 | (7<<3) | 4 = 0xF23C.
  // opmode 0x22 = FADD (dyadic, hardware-native) in every case, so any observed fault
  // would prove a real bug, not an expected native-opmode exclusion.
  val immOp = 0xF23C

  test("FADD.L #$12345678,FP0 : 32-bit int immediate, INTIMM, no sign-extension needed", VerilatorTest) {
    run { dut =>
      // ext: opclass 010, src spec 000 (Long), dst FP0, opmode 0x22 -> 0x4022
      drive(dut, op = immOp, ext = 0x4022, ext2 = 0x1234, len = 4)
      dut.pkt.words(3) #= 0x5678
      sleep(1)
      assert(!dut.uop.faulted.toBoolean, "FADD.L #imm is hardware-native -- must not trap")
      assert(dut.uop.fpSrcKind.toEnum == FpSrcKind.INTIMM)
      assert(dut.uop.fpSrcFmt.toInt == 0, "fpSrcFmt=000 (Long)")
      assert(!dut.uop.useImm.toBoolean, "imm/useImm stay reserved for FMOVECR -- these ride fpWideImm")
      assert(dut.fpWideImm.toBigInt == BigInt("0000000012345678", 16),
        f"fpWideImm must be the 32-bit value zero-extended to 80 bits, got 0x${dut.fpWideImm.toBigInt.toString(16)}")
      assert(dut.uop.usesFpSrcB.toBoolean == false && dut.uop.srcAValid.toBoolean == false,
        "no register source of any kind for an immediate form")
      assert(dut.uop.usesFpSrcA.toBoolean, "FADD is dyadic -- it still reads its destination FP0")
    }
  }

  test("FADD.W #$8000,FP0 : 16-bit int immediate SIGN-EXTENDS a negative value to 32 bits", VerilatorTest) {
    run { dut =>
      // ext: opclass 010, src spec 100 (Word), dst FP0, opmode 0x22 -> 0x5022
      drive(dut, op = immOp, ext = 0x5022, ext2 = 0x8000, len = 3); sleep(1)
      assert(!dut.uop.faulted.toBoolean)
      assert(dut.uop.fpSrcKind.toEnum == FpSrcKind.INTIMM)
      assert(dut.uop.fpSrcFmt.toInt == 4, "fpSrcFmt=100 (Word)")
      val lo32 = dut.fpWideImm.toBigInt & BigInt("FFFFFFFF", 16)
      assert(lo32 == BigInt("FFFF8000", 16),
        f"word 0x8000 (-32768) must sign-extend to 0xFFFF8000, got 0x${lo32.toString(16)}")
      assert((dut.fpWideImm.toBigInt >> 32) == 0, "upper 48 bits must be zero-padded")
    }
  }

  test("FADD.B #$80,FP0 : 8-bit int immediate SIGN-EXTENDS a negative value to 32 bits", VerilatorTest) {
    run { dut =>
      // ext: opclass 010, src spec 110 (Byte), dst FP0, opmode 0x22 -> 0x5822
      drive(dut, op = immOp, ext = 0x5822, ext2 = 0x0080, len = 3); sleep(1)
      assert(!dut.uop.faulted.toBoolean)
      assert(dut.uop.fpSrcKind.toEnum == FpSrcKind.INTIMM)
      assert(dut.uop.fpSrcFmt.toInt == 6, "fpSrcFmt=110 (Byte)")
      val lo32 = dut.fpWideImm.toBigInt & BigInt("FFFFFFFF", 16)
      assert(lo32 == BigInt("FFFFFF80", 16),
        f"byte 0x80 (-128) must sign-extend to 0xFFFFFF80, got 0x${lo32.toString(16)}")
      assert((dut.fpWideImm.toBigInt >> 32) == 0, "upper 48 bits must be zero-padded")
    }
  }

  test("FADD.S #$3F800000,FP0 vs FADD.L #$3F800000,FP0 : the SAME bit pattern must be tagged differently " +
       "(Single bit-pattern vs Long integer) -- fpSrcKind/fpSrcFmt are the discriminator, not size", VerilatorTest) {
    run { dut =>
      // Single: ext opclass 010, src spec 001, dst FP0, opmode 0x22 -> 0x4422
      drive(dut, op = immOp, ext = 0x4422, ext2 = 0x3F80, len = 4); sleep(1)
      assert(!dut.uop.faulted.toBoolean)
      assert(dut.uop.fpSrcKind.toEnum == FpSrcKind.SINGLEIMM,
        "0x3F800000 with a Single source specifier must be tagged SINGLEIMM, never converted as an integer " +
        "(misrouting it through INTREG/INTIMM would silently mean 1065353216 instead of 1.0f)")
      assert(dut.uop.fpSrcFmt.toInt == 1, "fpSrcFmt=001 (Single)")
      assert((dut.fpWideImm.toBigInt & BigInt("FFFFFFFF", 16)) == BigInt("3F800000", 16),
        "the bit pattern rides through VERBATIM, unconverted")

      // Long, same raw bits: ext opclass 010, src spec 000, dst FP0, opmode 0x22 -> 0x4022
      drive(dut, op = immOp, ext = 0x4022, ext2 = 0x3F80, len = 4); sleep(1)
      assert(!dut.uop.faulted.toBoolean)
      assert(dut.uop.fpSrcKind.toEnum == FpSrcKind.INTIMM,
        "the IDENTICAL bit pattern 0x3F800000 with a Long source specifier must be tagged INTIMM -- " +
        "Task 8 converts this as the INTEGER 1065353216, not the float 1.0")
      assert(dut.uop.fpSrcFmt.toInt == 0, "fpSrcFmt=000 (Long)")
      assert((dut.fpWideImm.toBigInt & BigInt("FFFFFFFF", 16)) == BigInt("3F800000", 16),
        "the raw bits are identical -- only fpSrcKind/fpSrcFmt tell Long and Single apart")
    }
  }

  test("FADD.D #imm,FP0 : 64-bit double bit-pattern immediate rides fpWideImm verbatim", VerilatorTest) {
    run { dut =>
      // pi in IEEE-754 double: 0x400921FB54442D18
      // ext: opclass 010, src spec 101 (Double), dst FP0, opmode 0x22 -> 0x5422
      // drive() only pokes words(0..4); word(5) is poked directly below.
      drive(dut, op = immOp, ext = 0x5422, ext2 = 0x4009, len = 6)
      dut.pkt.words(3) #= 0x21FB; dut.pkt.words(4) #= 0x5444; dut.pkt.words(5) #= 0x2D18
      sleep(1)
      assert(!dut.uop.faulted.toBoolean)
      assert(dut.uop.fpSrcKind.toEnum == FpSrcKind.DOUBLEIMM)
      assert(dut.uop.fpSrcFmt.toInt == 5, "fpSrcFmt=101 (Double)")
      val got = dut.fpWideImm.toBigInt
      val want = BigInt("400921FB54442D18", 16)
      assert(got == want,
        f"fpWideImm must be the 64-bit bit pattern zero-extended to 80 bits, want 0x${want.toString(16)}, got 0x${got.toString(16)}")
    }
  }

  test("FADD.X #imm,FP0 : 80-bit extended immediate skips the RESERVED word (Musashi READ_EA_FPE case 4)", VerilatorTest) {
    run { dut =>
      // ext: opclass 010, src spec 010 (Extended), dst FP0, opmode 0x22 -> 0x4822
      // Layout: word2=sign+exp (0x3FFF, i.e. 1.0x's biased exponent), word3=RESERVED
      // (poisoned with 0xDEAD to prove it is skipped, not folded in), words4-7=mantissa.
      dut.pkt.valid #= true; dut.pkt.pc #= 0x2000; dut.pkt.simple #= true; dut.pkt.complex #= false
      dut.pkt.lenWords #= 8; dut.pkt.wordCount #= 8; dut.pkt.fault #= false
      dut.pkt.words(0) #= immOp; dut.pkt.words(1) #= 0x4822
      dut.pkt.words(2) #= 0x3FFF; dut.pkt.words(3) #= 0xDEAD
      dut.pkt.words(4) #= 0x8000; dut.pkt.words(5) #= 0x0000
      dut.pkt.words(6) #= 0x0000; dut.pkt.words(7) #= 0x0000
      sleep(1)
      assert(!dut.uop.faulted.toBoolean)
      assert(dut.uop.fpSrcKind.toEnum == FpSrcKind.EXTIMM)
      assert(dut.uop.fpSrcFmt.toInt == 2, "fpSrcFmt=010 (Extended)")
      val got = dut.fpWideImm.toBigInt
      // word2(16)##word4(16)##word5(16)##word6(16)##word7(16) = 3FFF|8000|0000|0000|0000
      val want = BigInt("3FFF8000000000000000", 16)
      assert(got == want,
        f"fpWideImm must be word2##word4##word5##word6##word7 = 0x${want.toString(16)}, " +
        f"got 0x${got.toString(16)} -- if the RESERVED word (0xDEAD) leaked in, this proves it was NOT skipped")
    }
  }

  test("FADD.P #imm,FP0 : packed decimal immediate is EXCLUDED regardless of opmode (Decision 2)", VerilatorTest) {
    run { dut =>
      // ext: opclass 010, src spec 011 (Packed), dst FP0, opmode 0x22 (FADD, hardware-native)
      // -> 0x4C22. Packed immediates consume 6 words (Task 5's fpImmWords table) -> len=8.
      drive(dut, op = immOp, ext = 0x4C22, len = 8); sleep(1)
      assert(dut.uop.faulted.toBoolean && dut.uop.faultVector.toInt == 11,
        "packed decimal must trap to vector 11 even though FADD is a native opmode")
      assert(dut.uop.faultUsesNextPc.toBoolean, "framed (len=8), so FPSP must be able to RTE past it")
      assert(!dut.uop.writesFp.toBoolean && !dut.uop.writesFpcc.toBoolean,
        "no FP side effect on the trapping uop")
      assert(dut.uop.lenWords.toInt == 8, "lenWords = 8 -> nextPc = pc + 2*8 = 0x2010")
    }
  }

  test("out-of-scope cpGEN forms (register/immediate <ea>) still trap to vector 11 -- with the post-instruction PC", VerilatorTest) {
    run { dut =>
      def trapsWithNextPc(op: Int, ext: Int, len: Int, name: String): Unit = {
        drive(dut, op = op, ext = ext, len = len); sleep(1)
        assert(dut.uop.faulted.toBoolean && dut.uop.faultVector.toInt == 11, s"$name must trap to vector 11")
        assert(dut.uop.faultUsesNextPc.toBoolean, s"$name is framed, so FPSP must be able to RTE past it")
        assert(!dut.uop.writesFp.toBoolean && !dut.uop.writesFpcc.toBoolean,
          s"$name must leave no FP side effect on the trapping uop")
      }
      // <ea> = mode 0 (Dn direct) -- Task 6's own scope, unaffected by Task 6b's new
      // memory-mode-<ea> µcode gate (mode 0 is explicitly excluded from it).
      trapsWithNextPc(0xF200, 0x000E, 2, "FSIN (transcendental -> FPSP)")
      // $21 FMOD, $27 FSGLMUL: still genuinely unimplemented in hardware. These replace
      // the FSADD case this test used to carry -- see the next test for why.
      trapsWithNextPc(0xF200, 0x0421, 2, "FMOD (-> FPSP)")
      trapsWithNextPc(0xF200, 0x0427, 2, "FSGLMUL (-> FPSP)")
    }
  }

  test("the MC68040 forced-rounding-precision opmodes are NATIVE, not FPSP traps", VerilatorTest) {
    // This test replaces an earlier assertion that `FSADD` (opmode $62) trapped to vector
    // 11. That assertion encoded a real GAP, not a design decision: FSADD/FDADD/FSMUL/...
    // are absent from MC68040UM Table 9-10's unimplemented-instruction list and appear in
    // every M68000PRM "Opmode field" table marked "Supported by MC68040 only", i.e. they
    // are hardware instructions. They execute the SAME operation as their base opmode and
    // differ only in rounding precision, which rides to the EU in the same 7-bit `fpuOp`
    // field -- so the whole of decode's job here is to stop rejecting them and to get
    // `usesFpSrcA` (dyadic-ness) right for the new encodings.
    run { dut =>
      // (ext word, expected raw opmode, dyadic?, writes an FP destination?)
      val cases = Seq(
        (0x0440, 0x40, false),   // FSMOVE.S Dn,FPn   (monadic)
        (0x0444, 0x44, false),   // FDMOVE
        (0x0441, 0x41, false),   // FSSQRT
        (0x0445, 0x45, false),   // FDSQRT
        (0x0458, 0x58, false),   // FSABS
        (0x045C, 0x5C, false),   // FDABS
        (0x045A, 0x5A, false),   // FSNEG
        (0x045E, 0x5E, false),   // FDNEG
        (0x0460, 0x60, true),    // FSDIV  (dyadic -- reads FPn)
        (0x0464, 0x64, true),    // FDDIV
        (0x0462, 0x62, true),    // FSADD
        (0x0466, 0x66, true),    // FDADD
        (0x0463, 0x63, true),    // FSMUL
        (0x0467, 0x67, true),    // FDMUL
        (0x0468, 0x68, true),    // FSSUB
        (0x046C, 0x6C, true))    // FDSUB
      for ((ext, opmode, dyadic) <- cases) {
        drive(dut, op = 0xF200, ext = ext, len = 2); sleep(1)
        val n = f"opmode $$$opmode%02X"
        assert(!dut.uop.faulted.toBoolean, s"$n must NOT trap to FPSP any more")
        assert(dut.uop.fpuOp.toInt == opmode,
          s"$n: fpuOp must carry the RAW opmode (the precision travels in it)")
        assert(dut.uop.writesFp.toBoolean, s"$n writes an FP destination")
        assert(dut.uop.usesFpSrcA.toBoolean == dyadic,
          s"$n: usesFpSrcA=${dut.uop.usesFpSrcA.toBoolean}, expected $dyadic")
      }
    }
  }

  // Task 6b UPDATE: every case below has a GENUINE memory <ea> (mode 2, (A0)) -- these are
  // now CORRECTLY routed to Task 6b's µcode engine (`spec.microcoded := True`) instead of
  // trapping directly at THIS assembler-only layer; the accept/reject decision moved to
  // `DecodeStage.ucBegin`, which this `Dut` (MicroOpAssembler-only) cannot exercise. The
  // real end-to-end trap for every one of these forms is verified by `FpMemLoadSpec`
  // (Packed memory source, opclass 011 store, opclass 110 FMOVEM all directly covered
  // there; the FPCR/FPSR/FPIAR memory-EA form (opclass 100) is the SAME "wrong opclass"
  // rejection path, not independently re-tested here to avoid duplicating that coverage).
  // At THIS layer, the only observable is that each opword got routed to the µcode
  // engine at all (not silently misclassified as an ordinary "bad"/illegal instruction).
  test("out-of-scope cpGEN forms (genuine memory <ea>) route to the µcode engine (Task 6b), not trap here", VerilatorTest) {
    class DecDut extends Component {
      val opword = in Bits (16 bits)
      val o      = out(OpSpec())
      o := OperationDecoder.decode(opword)
    }
    def routesToUcode(op: Int, name: String): Unit = {
      SimConfig.withVerilator.compile(new DecDut).doSim { dut =>
        dut.opword #= op; sleep(1)
        assert(!dut.o.illegal.toBoolean, s"$name: cpGEN family stays non-illegal")
        assert(dut.o.microcoded.toBoolean, s"$name: routed to the µcode engine -- ucBegin decides accept/reject")
        assert(dut.o.op.toEnum == DecOp.FPU, s"$name: still classified DecOp.FPU")
      }
    }
    // ext word is irrelevant to OperationDecoder (opword-only) -- only the opword's <ea>
    // mode (mode 2 = (A0) here) matters for THIS classification decision.
    routesToUcode(0xF210, "FADD.L (A0),FP0 (memory source -> Task 6b)")
    routesToUcode(0xF210, "FADD.P (A0),FP0 (packed decimal -> FPSP, via ucBegin)")
    routesToUcode(0xF210, "FMOVE.X FP0,(A0) (opclass 011, store direction -> unowned)")
    routesToUcode(0xF210, "FMOVEM.X (A0),FP0-FP7 (opclass 110 -> unowned)")
    routesToUcode(0xF210, "FMOVE.L (A0),FPCR (opclass 100, memory-EA -> unowned)")
  }

  test("an FP uop never escapes with an inconsistent framing view", VerilatorTest) {
    run { dut =>
      // A cpGEN opword that predecode did NOT frame (len 1) must never emit an FP uop:
      // emitting one would compute nextPc = pc+2 for a >=2-word instruction (wild PC).
      drive(dut, op = 0xF200, ext = 0x0422, len = 1); sleep(1)
      assert(dut.uop.faulted.toBoolean, "an unframed cpGEN packet must trap, not emit")
      assert(!dut.uop.writesFp.toBoolean && dut.uop.op.toEnum != DecOp.FPU)
      // Likewise for a COMPLEX packet.
      drive(dut, op = 0xF200, ext = 0x0422, len = 2, simple = false); sleep(1)
      assert(dut.uop.faulted.toBoolean, "a complex packet must trap, not emit")
    }
  }

  test("Task 10: a recognized-but-non-native register-form FP op captures fpuSoftwareComplete/fpuCmdWord", VerilatorTest) {
    run { dut =>
      // FSIN FP1,FP0 (opmode 0x0E, non-native, register form) -- ext = 0x0000|... let's
      // use the SAME literal Task 6's own directed test already uses: ext=0x000E, dst=FP0.
      drive(dut, op = 0xF200, ext = 0x000E, len = 2); sleep(1)
      assert(dut.uop.faulted.toBoolean && dut.uop.faultVector.toInt == 11)
      assert(dut.uop.faultUsesNextPc.toBoolean, "set by Task 6's fpLenKnown, not this task")
      assert(dut.uop.fpuSoftwareComplete.toBoolean,
        "the register-to-register form must ALSO set this task's own trigger bit")
      assert(dut.uop.fpuCmdWord.toInt == 0x000E, "fpuCmdWord must carry the raw ext word verbatim")
    }
  }

  test("Task 10: an immediate-source or memory-source trap does NOT set fpuSoftwareComplete", VerilatorTest) {
    run { dut =>
      // FADD.L #imm,FP0, non-native opmode -- wait, use a genuinely non-native immediate
      // form: reuse Task 6's FADD.P (packed) trap, which is opclass 010, NOT
      // fpFormIsReg, so fpuGenRegUnimpl must NOT fire even though faultUsesNextPc does.
      drive(dut, op = 0xF23C, ext = 0x4C22, ext2 = 0x0000, len = 8)
      sleep(1)
      assert(dut.uop.faulted.toBoolean && dut.uop.faultUsesNextPc.toBoolean,
        "Task 6's broader gate still fires (this is exactly WHY fpuSoftwareComplete must stay narrower)")
      assert(!dut.uop.fpuSoftwareComplete.toBoolean,
        "an opclass-010 trap must NOT set fpuSoftwareComplete -- Task 11 would misread ext[12:10] as an FP register number")
      assert(dut.uop.fpuCmdWord.toInt == 0, "fpuCmdWord stays zero for anything outside the narrow trigger population")
    }
  }
}
