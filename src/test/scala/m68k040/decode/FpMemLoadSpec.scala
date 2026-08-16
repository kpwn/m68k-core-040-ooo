package m68k040.decode

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.cache.{IcachePlugin, IcacheSim}
import m68k040.frontend.FetchAlignPlugin
import m68k040.isa.{Cluster, MemOp, Size}
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer

/** Task 6b: F-line FP-generic genuine memory-source loads (`F<op> <mem>,FPn`).
  *
  * Drives real F-line memory-source opwords through fetch->align->decode (the SAME
  * end-to-end harness `MicrocodeSpec`/`MovesDecodeSpec`/`CasDecodeSpec` already use for
  * every other microcoded family) and collects the emitted µop stream at the
  * rename-facing sink, since `OperationDecoder`'s placeholder `ucEntry` for this family is
  * NEVER the real one -- only `DecodeStage.ucBegin`'s real ext-word-aware dispatch (walked
  * by the actual sequencer) proves the right crack landed. This is a STRONGER, more
  * end-to-end check than a `MicroOpAssembler`-only dispatch-boundary stub would be for a
  * microcoded family (`MicroOpAssembler` never runs the ROM walk at all).
  *
  * Covers: at least one addressing mode per class (register-indirect, predecrement,
  * postincrement, displacement, brief-indexed, absolute, PC-relative) across a
  * representative spread of formats; the Extended-format 3-chunk assembly specifically
  * (srcA/srcB/srcC land on T0/T1/T2 in mem+0/mem+4/mem+8 order, not garbled); Packed and
  * every other opclass sharing this opword band (FMOVE store / FPCR-FPSR-FPIAR / FMOVEM)
  * still traps to vector 11 with faultUsesNextPc=True; a non-hardware-native opmode
  * (transcendental) memory source also traps; Task 6's register-direct/immediate paths are
  * unaffected by this task's new dispatch arm.
  *
  * Deliberately does NOT assert end-to-end ARITHMETIC correctness (FPn's final value) --
  * that needs `FpuCore` (Task 7) + `DivEuPlugin`'s FP lane (Task 8) both landed, neither of
  * which exists yet. Task 13 ("Directed bit-exact lock-step tests for the HW-native FP op
  * set") is the right place for `FADD.L (A0),FP0`-style end-to-end lock-step vectors once
  * those land -- this task's own coverage stops at "the crack shape + operand routing is
  * correct" (mirrors Task 8's own explicit "knowingly incomplete" framing). */
class FpMemLoadSpec extends AnyFunSuite {
  class Dut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val ic = new IcachePlugin
    val fa = new FetchAlignPlugin
    val dec = new DecodeStage
    val sink = new UopSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), new IdentityTranslationPlugin, ic, fa, dec, sink)) }
  }

  case class U(op: String, memOp: String, cluster: String,
               dst: Int, dstV: Boolean, srcA: Int, srcAV: Boolean, srcB: Int, srcBV: Boolean,
               srcC: Int, srcCV: Boolean, useImm: Boolean, imm: Long, sz: String,
               eaAuto: String, eaDelta: Int, indexLong: Boolean, indexScale: Int, first: Boolean, last: Boolean,
               faulted: Boolean, faultVector: Int, faultUsesNextPc: Boolean,
               fpuOp: Int, fpDstReg: Int, writesFp: Boolean, fpSrcFmt: Int,
               usesFpSrcA: Boolean, fpSrcAReg: Int, usesFpSrcB: Boolean,
               writesFpcc: Boolean, fpSrcKind: String)

  // Collects exactly `n` emitted µops (2/cycle pop, mirrors MicrocodeSpec's own harness).
  def collect(words: Seq[Int], base: Long, n: Int): Seq[U] = {
    var out = Seq.empty[U]
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, words)
      dut.sink.logic.uopsOut.ready #= false
      dut.fa.logic.resume.valid #= false
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= base
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      dut.sink.logic.uopsOut.ready #= true
      val acc = ArrayBuffer[U]()
      var guard = 0
      while (acc.length < n && guard < 400) {
        cd.waitSampling(); guard += 1
        if (dut.sink.logic.uopsOut.valid.toBoolean) {
          def rd(i: Int): U = {
            val p = dut.sink.logic.uopsOut.payload(i)
            U(p.op.toEnum.toString, p.memOp.toEnum.toString, p.cluster.toEnum.toString,
              p.dstReg.toInt, p.dstValid.toBoolean, p.srcAReg.toInt, p.srcAValid.toBoolean,
              p.srcBReg.toInt, p.srcBValid.toBoolean, p.srcCReg.toInt, p.srcCValid.toBoolean,
              p.useImm.toBoolean, p.imm.toLong & 0xffffffffL,
              p.size.toEnum.toString, p.eaAuto.toEnum.toString, p.eaDelta.toInt,
              p.indexLong.toBoolean, p.indexScale.toInt, p.firstOfInstr.toBoolean, false,
              p.faulted.toBoolean, p.faultVector.toInt, p.faultUsesNextPc.toBoolean,
              p.fpuOp.toInt, p.fpDstReg.toInt, p.writesFp.toBoolean, p.fpSrcFmt.toInt,
              p.usesFpSrcA.toBoolean, p.fpSrcAReg.toInt, p.usesFpSrcB.toBoolean,
              p.writesFpcc.toBoolean, p.fpSrcKind.toEnum.toString)
          }
          acc += rd(0)
          if (dut.sink.logic.u1v.toBoolean) acc += rd(1)
        }
      }
      // A single-uop direct-emit crack (Task 6's register-direct path, unmicrocoded) can
      // legally 2-wide-pack with a SUBSEQUENT unrelated instruction in slot1 (the same
      // real frontend behavior `DecodeStageSpec`'s own "2-wide MOVEQ stream" test
      // exercises) -- trim to exactly the `n` uops this instruction's own crack produces.
      out = acc.toSeq.take(n)
    }
    out
  }

  // Filler NOPs after the real instruction so the aligner always has enough live bytes.
  val filler = Seq(0x4e71, 0x4e71, 0x4e71, 0x4e71, 0x4e71, 0x4e71)

  // opword builder: 0xF200 | mode<<3 | reg
  def fpOp(mode: Int, reg: Int): Int = 0xF200 | (mode << 3) | reg
  // ext-word builder: opclass<<13 | srcSpec<<10 | dstFp<<7 | opmode
  def fpExt(opclass: Int, srcSpec: Int, dstFp: Int, opmode: Int): Int =
    (opclass << 13) | (srcSpec << 10) | (dstFp << 7) | opmode

  val T0 = MicroOpAssembler.T0; val T1 = MicroOpAssembler.T1; val T2 = MicroOpAssembler.T2

  // ── Register-indirect (An), Long, FADD ──────────────────────────────────────────
  test("FADD.L (A0),FP0 (register-indirect): FP_MEM_L_ENTRY, 2 uops [LOAD.L(A0)->T0, UFpIssue]", VerilatorTest) {
    val op  = fpOp(2, 0)                          // (A0)
    val ext = fpExt(2, 0, 0, 0x22)                 // opclass 010, Long, FP0, FADD
    val us = collect(Seq(op, ext) ++ filler, 0x40800000L, 2)
    assert(us.length == 2, s"expected 2 uops: $us")
    assert(us(0).memOp.startsWith("LOAD") && us(0).dst == T0 && us(0).dstV && us(0).srcA == 8 && us(0).srcAV &&
           us(0).sz.startsWith("LONG") && us(0).useImm && us(0).imm == 0 && us(0).first && !us(0).faulted,
      s"LOAD.L (A0)+0 -> T0: ${us(0)}")
    assert(us(1).op.startsWith("FPU") && us(1).cluster.startsWith("CPLX") && us(1).srcA == T0 && us(1).srcAV &&
           !us(1).srcBV && !us(1).srcCV && us(1).fpuOp == 0x22 && us(1).fpDstReg == 0 &&
           us(1).writesFp && us(1).fpSrcFmt == 0 && us(1).usesFpSrcA && us(1).fpSrcAReg == 0 &&
           us(1).writesFpcc && us(1).fpSrcKind.startsWith("INTREG") && !us(1).faulted,
      s"UFpIssue srcA=T0 INTREG FADD->FP0: ${us(1)}")
  }

  // ── Predecrement -(A1), Word, FMOVE (monadic, writesFp) ─────────────────────────
  test("FMOVE.W -(A1),FP1 (predecrement): FP_MEM_W_AUTO_PRE_ENTRY, 3 uops, decrement FIRST", VerilatorTest) {
    val op  = fpOp(4, 1)                           // -(A1)
    val ext = fpExt(2, 4, 1, 0x00)                  // Word, FP1, FMOVE
    val us = collect(Seq(op, ext) ++ filler, 0x40800000L, 3)
    assert(us.length == 3, s"expected 3 uops: $us")
    // µ0: A1 -= 2 (Word delta), dropped, isFirst
    assert(us(0).op.startsWith("ADD") && us(0).dst == 9 && us(0).srcA == 9 && us(0).useImm &&
           us(0).imm == 0xfffffffeL && us(0).first,
      s"A1 -= 2 predec write-back FIRST: ${us(0)}")
    // µ1: LOAD.W (A1) -> T0 (already-decremented A1, plain register read, no imm)
    assert(us(1).memOp.startsWith("LOAD") && us(1).dst == T0 && us(1).srcA == 9 && us(1).srcAV &&
           us(1).sz.startsWith("WORD") && !us(1).first,
      s"LOAD.W (A1) -> T0: ${us(1)}")
    // µ2: UFpIssue, monadic FMOVE writes FP1, isLast
    assert(us(2).op.startsWith("FPU") && us(2).srcA == T0 && us(2).fpuOp == 0x00 && us(2).fpDstReg == 1 &&
           us(2).writesFp && !us(2).usesFpSrcA && us(2).fpSrcFmt == 4 && us(2).fpSrcKind.startsWith("INTREG"),
      s"UFpIssue monadic FMOVE->FP1: ${us(2)}")
  }

  // ── Postincrement (A2)+, Double, FSUB -> MEMPAIR (2-chunk) ──────────────────────
  test("FSUB.D (A2)+,FP2 (postincrement): FP_MEM_D_AUTO_POST_ENTRY, 4 uops, increment LAST", VerilatorTest) {
    val op  = fpOp(3, 2)                           // (A2)+
    val ext = fpExt(2, 5, 2, 0x28)                  // Double, FP2, FSUB
    val us = collect(Seq(op, ext) ++ filler, 0x40800000L, 4)
    assert(us.length == 4, s"expected 4 uops: $us")
    assert(us(0).memOp.startsWith("LOAD") && us(0).dst == T0 && us(0).srcA == 10 && !us(0).useImm && us(0).first,
      s"LOAD.L (A2)+0 -> T0 (hi): ${us(0)}")
    assert(us(1).memOp.startsWith("LOAD") && us(1).dst == T1 && us(1).srcA == 10 && us(1).useImm && us(1).imm == 4,
      s"LOAD.L (A2)+4 -> T1 (lo): ${us(1)}")
    assert(us(2).op.startsWith("FPU") && us(2).srcA == T0 && us(2).srcAV && us(2).srcB == T1 && us(2).srcBV &&
           !us(2).srcCV && us(2).fpSrcKind.startsWith("MEMPAIR") && us(2).fpuOp == 0x28 && !us(2).last,
      s"UFpIssue MEMPAIR srcA=T0,srcB=T1: ${us(2)}")
    // µ3: A2 += 8 (Double total size), dropped, isLast
    assert(us(3).op.startsWith("ADD") && us(3).dst == 10 && us(3).useImm && us(3).imm == 8,
      s"A2 += 8 postinc write-back LAST: ${us(3)}")
  }

  // ── Displacement (d16,An), Extended -> MEMEXT (3-chunk): THE bit-exact-assembly test ──
  test("FADD.X (16,A3),FP3 (displacement, Extended): FP_MEM_X_ENTRY, 4 uops, T0/T1/T2 = mem+0/+4/+8", VerilatorTest) {
    val op  = fpOp(5, 3)                           // (d16,A3)
    val ext = fpExt(2, 2, 3, 0x22)                  // Extended, FP3, FADD
    val disp = 0x0010                               // +16
    val us = collect(Seq(op, ext, disp) ++ filler, 0x40800000L, 4)
    assert(us.length == 4, s"expected 4 uops: $us")
    // Chunk 0 (mem+16+0 = sign+exp): -> T0
    assert(us(0).memOp.startsWith("LOAD") && us(0).dst == T0 && us(0).srcA == 11 && us(0).srcAV &&
           us(0).useImm && us(0).imm == 16 && us(0).first,
      s"chunk0 LOAD (A3+16) -> T0: ${us(0)}")
    // Chunk 1 (mem+16+4 = mantissa hi): -> T1
    assert(us(1).memOp.startsWith("LOAD") && us(1).dst == T1 && us(1).srcA == 11 && us(1).useImm && us(1).imm == 20,
      s"chunk1 LOAD (A3+20) -> T1: ${us(1)}")
    // Chunk 2 (mem+16+8 = mantissa lo): -> T2
    assert(us(2).memOp.startsWith("LOAD") && us(2).dst == T2 && us(2).srcA == 11 && us(2).useImm && us(2).imm == 24,
      s"chunk2 LOAD (A3+24) -> T2: ${us(2)}")
    // Terminal UFpIssue: srcA=T0, srcB=T1, srcC=T2, ALL valid, fpSrcKind=MEMEXT, isLast.
    assert(us(3).op.startsWith("FPU") && us(3).srcA == T0 && us(3).srcAV &&
           us(3).srcB == T1 && us(3).srcBV && us(3).srcC == T2 && us(3).srcCV &&
           us(3).fpSrcKind.startsWith("MEMEXT") && us(3).fpuOp == 0x22 && us(3).fpDstReg == 3,
      s"UFpIssue MEMEXT srcA=T0,srcB=T1,srcC=T2 -- confirms NOT garbled/misordered: ${us(3)}")
  }

  // ── Brief-indexed (d8,An,Xn), Single -> reuses the same 1-chunk INTREG shape as Long ──
  test("FMUL.S (4,A4,D1.L),FP4 (brief-indexed): FP_MEM_S_ENTRY, 2 uops, index threaded through", VerilatorTest) {
    val op  = fpOp(6, 4)                           // (d8,A4,Xn)
    val ext = fpExt(2, 1, 4, 0x23)                  // Single, FP4, FMUL
    // brief index ext word: D/A=0(Dn), reg=1(D1), W/L=1(.L), scale=00, disp8=4
    val idxExt = (0 << 15) | (1 << 12) | (1 << 11) | (0 << 9) | 4
    val us = collect(Seq(op, ext, idxExt) ++ filler, 0x40800000L, 2)
    assert(us.length == 2, s"expected 2 uops: $us")
    assert(us(0).memOp.startsWith("LOAD") && us(0).dst == T0 && us(0).srcA == 12 && us(0).useImm && us(0).imm == 4 &&
           us(0).indexLong && us(0).indexScale == 0,
      s"LOAD.L (A4+4,D1.L) index threaded through: ${us(0)}")
    assert(us(1).op.startsWith("FPU") && us(1).srcA == T0 && us(1).fpSrcFmt == 1 && us(1).fpSrcKind.startsWith("INTREG"),
      s"UFpIssue Single (fpSrcFmt=1, distinguishes from Long): ${us(1)}")
  }

  // ── Absolute (xxx).W, Byte, FTST (monadic, no dst, no dyadic src) ───────────────
  test("FTST.B (0x2000).W: FP_MEM_B_ENTRY, 2 uops, writesFp=False usesFpSrcA=False (FTST)", VerilatorTest) {
    val op   = fpOp(7, 0)                          // (xxx).W
    val ext  = fpExt(2, 6, 5, 0x3A)                 // Byte, FP5, FTST
    val absW = 0x2000
    val us = collect(Seq(op, ext, absW) ++ filler, 0x40800000L, 2)
    assert(us.length == 2, s"expected 2 uops: $us")
    assert(us(0).memOp.startsWith("LOAD") && us(0).dst == T0 && !us(0).srcAV && us(0).useImm && us(0).imm == 0x2000 &&
           us(0).sz.startsWith("BYTE"),
      s"LOAD.B (0x2000).W -> T0, no base (absolute): ${us(0)}")
    assert(us(1).op.startsWith("FPU") && !us(1).dstV /* no int dst */ && !us(1).writesFp && !us(1).usesFpSrcA &&
           us(1).fpuOp == 0x3A && us(1).writesFpcc,
      s"UFpIssue FTST: writesFp=False, usesFpSrcA=False (not dyadic), still writesFpcc: ${us(1)}")
  }

  // ── PC-relative (d16,PC), Long -- confirms the pc+4 fold (mirrors bit-field's bfPcRelConst) ──
  test("FADD.L (8,PC),FP6 (PC-relative): FP_MEM_L_ENTRY, address = pc+4+disp (no base)", VerilatorTest) {
    val base = 0x40800000L
    val op  = fpOp(7, 2)                            // (d16,PC)
    val ext = fpExt(2, 0, 6, 0x22)                   // Long, FP6, FADD
    val disp = 8
    val us = collect(Seq(op, ext, disp) ++ filler, base, 2)
    assert(us.length == 2, s"expected 2 uops: $us")
    // address = (pc-of-EA-ext-word = base+4) + disp(8) = base+12, folded entirely into imm
    // (no live base register -- srcAValid=False, mirrors the absolute-EA LOAD shape).
    val expectAddr = (base + 4 + 8) & 0xffffffffL
    assert(us(0).memOp.startsWith("LOAD") && !us(0).srcAV && us(0).useImm && us(0).imm == expectAddr,
      s"LOAD.L pc+4+disp = 0x${expectAddr.toHexString}, no base: ${us(0)}")
    assert(us(1).op.startsWith("FPU") && us(1).srcA == T0 && us(1).fpDstReg == 6,
      s"UFpIssue after PC-rel load: ${us(1)}")
  }

  // ── Packed source (fpSrcSpec 011) always traps, regardless of EA mode -- Decision 2 ──
  test("F<op>.P (A0),FPn (Packed): traps vector 11, faultUsesNextPc=True (length is known)", VerilatorTest) {
    val op  = fpOp(2, 0)                            // (A0)
    val ext = fpExt(2, 3, 0, 0x22)                   // Packed source spec (011), FADD opmode
    val us = collect(Seq(op, ext) ++ filler, 0x40800000L, 1)
    assert(us.length == 1, s"expected 1 uop (the trap row): $us")
    assert(us(0).faulted && us(0).faultVector == 11 && us(0).faultUsesNextPc,
      s"Packed source must always trap (Decision 2), RTE-able: ${us(0)}")
  }

  // ── FMOVE FPn,(mem) (opclass 011, store direction) shares this opword band. Task 14b
  //    LANDED it, so it is no longer a trap -- what this test now guards is that the two
  //    directions stay DISTINCT (the store program must not be mishandled as a load, and
  //    vice versa). Full store-direction coverage lives in `FpMemStoreSpec`. ────────────
  test("FMOVE.L FP0,(A0) (opclass 011, store): now a real store program, NOT a load crack", VerilatorTest) {
    val op  = fpOp(2, 0)
    val ext = fpExt(3, 0, 0, 0x00)                   // opclass 011 (store) -- Task 14b
    val us = collect(Seq(op, ext) ++ filler, 0x40800000L, 2)
    assert(us.length == 2, s"expected 2 uops (Task 14b's [CVT, STORE] crack): $us")
    assert(us(0).op.startsWith("FPSTORECVT") && !us(0).faulted && us(0).usesFpSrcA,
      s"opclass 011 must reach the store direction's conversion uop: ${us(0)}")
    assert(us(1).memOp.startsWith("STORE") && !us(1).faulted,
      s"opclass 011 must emit a STORE, never a LOAD: ${us(1)}")
  }

  // ── FMOVEM <ea>,list (opclass 110) shares this opword band but is NOT this task's job ──
  test("FMOVEM.X (A0),FP0-FP7 (opclass 110): shares the opword band, still traps -- unowned gap", VerilatorTest) {
    val op  = fpOp(2, 0)
    val ext = fpExt(6, 0, 0, 0xFF)                   // opclass 110 (FMOVEM), NOT this task's job
    val us = collect(Seq(op, ext) ++ filler, 0x40800000L, 1)
    assert(us.length == 1, s"expected 1 uop (the trap row): $us")
    assert(us(0).faulted && us(0).faultVector == 11, s"opclass 110 (FMOVEM) must still trap: ${us(0)}")
  }

  // ── A non-hardware-native opmode (e.g. FSIN, opmode 0x0E) memory source must ALSO trap --
  //    FPSP-routed regardless of source form, mirroring Task 6's register/imm-form fpNative gate.
  test("FSIN.L (A0),FP0 (non-native opmode, memory source): traps vector 11", VerilatorTest) {
    val op  = fpOp(2, 0)
    val ext = fpExt(2, 0, 0, 0x0E)                   // opclass 010 (in scope), Long, FSIN (non-native)
    val us = collect(Seq(op, ext) ++ filler, 0x40800000L, 1)
    assert(us.length == 1, s"expected 1 uop (the trap row): $us")
    assert(us(0).faulted && us(0).faultVector == 11 && us(0).faultUsesNextPc,
      s"non-native opmode memory source must trap to FPSP, not silently 'execute': ${us(0)}")
  }

  // ── Task 6's register-direct path (mode 0, Dn) is UNCHANGED by this task's new gate ──
  test("FADD.L D1,FP0 (register-direct, Task 6's job): 1 uop, INTREG, unaffected by this task", VerilatorTest) {
    val op  = fpOp(0, 1)                            // D1 direct
    val ext = fpExt(2, 0, 0, 0x22)                   // Long, FP0, FADD
    val us = collect(Seq(op, ext) ++ filler, 0x40800000L, 1)
    assert(us.length == 1, s"expected 1 uop (Task 6's direct-emission path, not microcoded): $us")
    assert(us(0).op.startsWith("FPU") && us(0).srcA == 1 && us(0).srcAV && us(0).fpSrcKind.startsWith("INTREG") &&
           !us(0).faulted,
      s"FADD.L D1,FP0 stays Task 6's single-uop direct-emit path: ${us(0)}")
  }
}
