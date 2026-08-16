package m68k040.decode

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.cache.{IcachePlugin, IcacheSim}
import m68k040.frontend.FetchAlignPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer

/** Task 14b: FMOVE `FPn,<ea>` (cpGEN opclass 011), the STORE direction.
  *
  * Same end-to-end fetch->align->decode harness as `FpMemLoadSpec` (its sibling for the
  * load direction), and for the same reason: `OperationDecoder`'s `ucEntry` for this
  * opword band is a placeholder, so only `DecodeStage.ucBegin`'s real ext-word-aware
  * dispatch -- walked by the actual sequencer -- proves the right crack landed.
  *
  * Covers, per the task brief's Step 4: the accept/reject EA set for every non-Packed
  * format; the DUAL Packed exclusion (both `011` static-k and `111` dynamic-k reach
  * `FP_MEM_TRAP_ENTRY`, unlike the load direction where only `011` needs excluding);
  * PC-relative rejection (Divergence Register D10 -- Musashi permissively WRITES through
  * `EA_PCDI_*`, this core does not); the register-direct emit path for Byte/Word/Long/
  * Single only (D4); and `An`-direct acceptance for the Long format only (D5).
  *
  * Deliberately does NOT assert the stored VALUE -- that is `FpNarrowPackSpec`'s job at
  * the converter level and `FpuLockStepSpec`'s job end-to-end. This file stops at "the
  * crack shape + operand routing + EA gating is correct". */
class FpMemStoreSpec extends AnyFunSuite {
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
               useImm: Boolean, imm: Long, sz: String, indexLong: Boolean, indexScale: Int,
               first: Boolean, faulted: Boolean, faultVector: Int, faultUsesNextPc: Boolean,
               fpSrcFmt: Int, usesFpSrcA: Boolean, fpSrcAReg: Int,
               writesFp: Boolean, writesFpcc: Boolean)

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
              p.srcBReg.toInt, p.srcBValid.toBoolean,
              p.useImm.toBoolean, p.imm.toLong & 0xffffffffL, p.size.toEnum.toString,
              p.indexLong.toBoolean, p.indexScale.toInt, p.firstOfInstr.toBoolean,
              p.faulted.toBoolean, p.faultVector.toInt, p.faultUsesNextPc.toBoolean,
              p.fpSrcFmt.toInt, p.usesFpSrcA.toBoolean, p.fpSrcAReg.toInt,
              p.writesFp.toBoolean, p.writesFpcc.toBoolean)
          }
          acc += rd(0)
          if (dut.sink.logic.u1v.toBoolean) acc += rd(1)
        }
      }
      out = acc.toSeq.take(n)
    }
    out
  }

  val filler = Seq(0x4e71, 0x4e71, 0x4e71, 0x4e71, 0x4e71, 0x4e71)

  /** opword: 0xF200 | mode<<3 | reg */
  def fpOp(mode: Int, reg: Int): Int = 0xF200 | (mode << 3) | reg
  /** ext word: opclass<<13 | dstFmt<<10 | srcFpn<<7 | kFactor.  For opclass 011 the
    * ext[12:10] field is the DESTINATION FORMAT and ext[9:7] the SOURCE FPn -- the
    * role-flip of the load direction's identical bit positions. */
  def stExt(fmt: Int, fpn: Int, k: Int = 0): Int = (3 << 13) | (fmt << 10) | (fpn << 7) | k

  val T0 = MicroOpAssembler.T0; val T1 = MicroOpAssembler.T1; val T2 = MicroOpAssembler.T2
  val FmtL = 0; val FmtS = 1; val FmtX = 2; val FmtP = 3
  val FmtW = 4; val FmtD = 5; val FmtB = 6; val FmtPd = 7

  def assertCvt(u: U, temp: Int, chunk: Int, fmt: Int, fpn: Int, first: Boolean): Unit = {
    assert(u.op.startsWith("FPSTORECVT") && u.cluster.startsWith("CPLX") &&
           u.memOp.startsWith("NONE") && u.dst == temp && u.dstV &&
           u.useImm && u.imm == chunk && u.sz.startsWith("LONG") &&
           u.fpSrcFmt == fmt && u.usesFpSrcA && u.fpSrcAReg == fpn &&
           !u.writesFp && !u.writesFpcc && !u.faulted,
      s"expected FPSTORECVT chunk=$chunk fmt=$fmt fpn=$fpn -> T${temp - T0}: $u")
    assert(u.first == first, s"firstOfInstr should be $first: $u")
  }

  // ══ memory destinations: one per EA bucket ═══════════════════════════════════════

  test("FMOVE.L FP0,(A0): FP_STORE_L_ENTRY, 2 uops [CVT->T0, STORE.L]", VerilatorTest) {
    val us = collect(Seq(fpOp(2, 0), stExt(FmtL, 0)) ++ filler, 0x40800000L, 2)
    assert(us.length == 2, s"expected 2 uops: $us")
    assertCvt(us(0), T0, 0, FmtL, 0, first = true)
    assert(us(1).memOp.startsWith("STORE") && us(1).srcA == 8 && us(1).srcAV &&
           us(1).srcB == T0 && us(1).srcBV && us(1).sz.startsWith("LONG") &&
           us(1).useImm && us(1).imm == 0 && !us(1).faulted,
      s"STORE.L T0 -> (A0): ${us(1)}")
  }

  test("FMOVE.S FP3,(A1)+: FP_STORE_S_AUTO_POST_ENTRY, 3 uops, increment LAST (+4)", VerilatorTest) {
    val us = collect(Seq(fpOp(3, 1), stExt(FmtS, 3)) ++ filler, 0x40800000L, 3)
    assert(us.length == 3, s"expected 3 uops: $us")
    assertCvt(us(0), T0, 0, FmtS, 3, first = true)
    assert(us(1).memOp.startsWith("STORE") && us(1).srcA == 9 && us(1).srcAV &&
           us(1).srcB == T0 && us(1).srcBV && us(1).sz.startsWith("LONG") && !us(1).useImm,
      s"STORE.L T0 -> (A1): ${us(1)}")
    assert(us(2).op.startsWith("ADD") && us(2).dst == 9 && us(2).srcA == 9 &&
           us(2).useImm && us(2).imm == 4,
      s"A1 += 4 postinc write-back LAST: ${us(2)}")
  }

  test("FMOVE.X FP1,-(A2): FP_STORE_X_AUTO_PRE_ENTRY, 7 uops, decrement FIRST (-12)", VerilatorTest) {
    val us = collect(Seq(fpOp(4, 2), stExt(FmtX, 1)) ++ filler, 0x40800000L, 7)
    assert(us.length == 7, s"expected 7 uops: $us")
    assert(us(0).op.startsWith("ADD") && us(0).dst == 10 && us(0).srcA == 10 &&
           us(0).useImm && us(0).imm == 0xfffffff4L && us(0).first,
      s"A2 -= 12 predec write-back FIRST: ${us(0)}")
    assertCvt(us(1), T0, 0, FmtX, 1, first = false)
    assertCvt(us(2), T1, 1, FmtX, 1, first = false)
    assertCvt(us(3), T2, 2, FmtX, 1, first = false)
    // The three stores land at A2+0/+4/+8 -- store_extended_float80's exact field order
    // ({sign+exp,0}, mantissa hi32, mantissa lo32), read back by the load direction's own
    // T0/T1/T2 = mem+0/+4/+8 assembly.
    assert(us(4).memOp.startsWith("STORE") && us(4).srcA == 10 && us(4).srcB == T0 && !us(4).useImm,
      s"chunk0 STORE T0 -> (A2+0): ${us(4)}")
    assert(us(5).memOp.startsWith("STORE") && us(5).srcB == T1 && us(5).useImm && us(5).imm == 4,
      s"chunk1 STORE T1 -> (A2+4): ${us(5)}")
    assert(us(6).memOp.startsWith("STORE") && us(6).srcB == T2 && us(6).useImm && us(6).imm == 8,
      s"chunk2 STORE T2 -> (A2+8): ${us(6)}")
  }

  test("FMOVE.W FP2,64(A3): displacement bucket, STORE size WORD", VerilatorTest) {
    val us = collect(Seq(fpOp(5, 3), stExt(FmtW, 2), 64) ++ filler, 0x40800000L, 2)
    assert(us.length == 2, s"expected 2 uops: $us")
    assertCvt(us(0), T0, 0, FmtW, 2, first = true)
    assert(us(1).memOp.startsWith("STORE") && us(1).srcA == 11 && us(1).srcAV &&
           us(1).srcB == T0 && us(1).sz.startsWith("WORD") && us(1).useImm && us(1).imm == 64,
      s"STORE.W T0 -> 64(A3) -- the WORD access is what truncates the int32: ${us(1)}")
  }

  test("FMOVE.D FP4,(8,A4,D1.L): brief-indexed, 2 chunks at +8 and +12, index threaded", VerilatorTest) {
    // brief index ext: D/A=0(Dn), reg=1(D1), W/L=1(.L), scale=00, disp8=8
    val idx = (0 << 15) | (1 << 12) | (1 << 11) | (0 << 9) | 8
    val us = collect(Seq(fpOp(6, 4), stExt(FmtD, 4), idx) ++ filler, 0x40800000L, 4)
    assert(us.length == 4, s"expected 4 uops: $us")
    assertCvt(us(0), T0, 0, FmtD, 4, first = true)
    assertCvt(us(1), T1, 1, FmtD, 4, first = false)
    assert(us(2).memOp.startsWith("STORE") && us(2).srcA == 12 && us(2).srcB == T0 &&
           us(2).useImm && us(2).imm == 8 && us(2).indexLong,
      s"chunk0 STORE T0 -> (8,A4,D1.L): ${us(2)}")
    assert(us(3).memOp.startsWith("STORE") && us(3).srcB == T1 &&
           us(3).useImm && us(3).imm == 12 && us(3).indexLong,
      s"chunk1 STORE T1 -> (12,A4,D1.L): ${us(3)}")
  }

  test("FMOVE.B FP5,(xxx).W: absolute-short destination is accepted (Musashi has no writer)", VerilatorTest) {
    // D6: Musashi's WRITE_EA_8 has no mode-7/reg-0 arm at all, yet the ported corpus test
    // `fpu_fmove_fp_to_ea_matrix.s` exercises `.B` into abs.W and expects it to work. The
    // general EaDecoder/LS substrate this core already uses for ordinary MOVEs is trusted
    // over Musashi's narrower writer set, which is emulator laziness, not an ISA restriction.
    val us = collect(Seq(fpOp(7, 0), stExt(FmtB, 5), 0x3000) ++ filler, 0x40800000L, 2)
    assert(us.length == 2, s"expected 2 uops: $us")
    assertCvt(us(0), T0, 0, FmtB, 5, first = true)
    assert(us(1).memOp.startsWith("STORE") && !us(1).srcAV && us(1).srcB == T0 &&
           us(1).sz.startsWith("BYTE") && us(1).useImm && us(1).imm == 0x3000,
      s"STORE.B T0 -> (0x3000).W: ${us(1)}")
  }

  test("FMOVE.L FP0,(xxx).L: absolute-long destination is accepted", VerilatorTest) {
    val us = collect(Seq(fpOp(7, 1), stExt(FmtL, 0), 0x0002, 0x6000) ++ filler, 0x40800000L, 2)
    assert(us.length == 2, s"expected 2 uops: $us")
    assertCvt(us(0), T0, 0, FmtL, 0, first = true)
    assert(us(1).memOp.startsWith("STORE") && !us(1).srcAV && us(1).useImm && us(1).imm == 0x26000,
      s"STORE.L T0 -> (0x00026000).L: ${us(1)}")
  }

  // ══ rejections ═══════════════════════════════════════════════════════════════════

  test("Packed destinations BOTH trap: static-k (011) AND dynamic-k (111)", VerilatorTest) {
    for ((fmt, tag) <- Seq((FmtP, "static-k 011"), (FmtPd, "dynamic-k 111"))) {
      val us = collect(Seq(fpOp(2, 0), stExt(fmt, 0)) ++ filler, 0x40800000L, 1)
      assert(us.length == 1, s"$tag: expected 1 uop (the trap row): $us")
      assert(us.head.faulted && us.head.faultVector == 11 && us.head.faultUsesNextPc,
        s"$tag Packed destination must trap to vector 11, RTE-able: ${us.head}")
    }
  }

  test("PC-relative destinations trap (D10: this core rejects, Musashi permissively writes)", VerilatorTest) {
    // (d16,PC) = mode 7 / reg 2; (d8,PC,Xn) = mode 7 / reg 3. Neither is an ALTERABLE
    // addressing mode, so neither is a legal FMOVE destination on real hardware.
    val a = collect(Seq(fpOp(7, 2), stExt(FmtL, 0), 0x0010) ++ filler, 0x40800000L, 1)
    assert(a.length == 1 && a.head.faulted && a.head.faultVector == 11,
      s"(d16,PC) destination must trap: $a")
    val b = collect(Seq(fpOp(7, 3), stExt(FmtL, 0), 0x0010) ++ filler, 0x40800000L, 1)
    assert(b.length == 1 && b.head.faulted && b.head.faultVector == 11,
      s"(d8,PC,Xn) destination must trap: $b")
  }

  test("memory-indirect destinations trap (out of the microcode engine's scope)", VerilatorTest) {
    // full-format ext: bit8 = full, I/IS != 000 -> MEMINDIRECT.
    val full = (1 << 8) | (1 << 4) | 1     // BD_SIZE=01(null), IS=0, I/IS=001
    val us = collect(Seq(fpOp(6, 0), stExt(FmtL, 0), full) ++ filler, 0x40800000L, 1)
    assert(us.length == 1 && us.head.faulted && us.head.faultVector == 11,
      s"memory-indirect destination must trap: $us")
  }

  // ══ register-direct destinations (D4/D5) ═════════════════════════════════════════

  test("FMOVE.L FP2,D1 / FMOVE.S FP2,D1: single direct-emit FPSTORECVT, full 32-bit write", VerilatorTest) {
    for ((fmt, tag) <- Seq((FmtL, "Long"), (FmtS, "Single"))) {
      val us = collect(Seq(fpOp(0, 1), stExt(fmt, 2)) ++ filler, 0x40800000L, 1)
      assert(us.length == 1, s"$tag: expected 1 uop (direct-emit, not microcoded): $us")
      val u = us.head
      assert(u.op.startsWith("FPSTORECVT") && u.cluster.startsWith("CPLX") &&
             u.dst == 1 && u.dstV && u.usesFpSrcA && u.fpSrcAReg == 2 && u.fpSrcFmt == fmt &&
             u.useImm && u.imm == 0 && u.sz.startsWith("LONG") && !u.srcAV &&
             !u.writesFp && !u.writesFpcc && !u.faulted,
        s"$tag Dn-direct: no merge source, full-width write: $u")
    }
  }

  test("FMOVE.W FP0,D3 / FMOVE.B FP0,D3: direct-emit with a PARTIAL-register MERGE source", VerilatorTest) {
    for ((fmt, szTag, tag) <- Seq((FmtW, "WORD", "Word"), (FmtB, "BYTE", "Byte"))) {
      val us = collect(Seq(fpOp(0, 3), stExt(fmt, 0)) ++ filler, 0x40800000L, 1)
      assert(us.length == 1, s"$tag: expected 1 uop: $us")
      val u = us.head
      // D11: the low word/byte only is written; srcA reads the OLD Dn back as the merge
      // source. Musashi ZERO-EXTENDS here (`REG_D[reg] = data` from a uint16/uint8) and is
      // a confirmed-wrong oracle for this one field.
      assert(u.op.startsWith("FPSTORECVT") && u.dst == 3 && u.dstV &&
             u.srcA == 3 && u.srcAV && u.sz.startsWith(szTag) &&
             u.usesFpSrcA && u.fpSrcAReg == 0 && u.fpSrcFmt == fmt && !u.faulted,
        s"$tag Dn-direct must read Dn back for the partial merge: $u")
    }
  }

  test("An-direct is accepted for the Long format ONLY (D5)", VerilatorTest) {
    val ok = collect(Seq(fpOp(1, 3), stExt(FmtL, 6)) ++ filler, 0x40800000L, 1)
    assert(ok.length == 1, s"expected 1 uop: $ok")
    assert(ok.head.op.startsWith("FPSTORECVT") && ok.head.dst == 11 && ok.head.dstV &&
           ok.head.usesFpSrcA && ok.head.fpSrcAReg == 6 && !ok.head.faulted,
      s"FMOVE.L FP6,A3 -> arch dst 8+3=11 (WRITE_EA_32 is the only Musashi writer with a mode-1 arm): ${ok.head}")
    for ((fmt, tag) <- Seq((FmtS, "Single"), (FmtW, "Word"), (FmtB, "Byte"), (FmtD, "Double"))) {
      val us = collect(Seq(fpOp(1, 3), stExt(fmt, 0)) ++ filler, 0x40800000L, 1)
      assert(us.length == 1 && us.head.faulted && us.head.faultVector == 11,
        s"$tag An-direct must trap (no Musashi writer has a mode-1 arm for it): $us")
    }
  }

  test("Double / Extended / Packed have NO register-direct arm at all (D4)", VerilatorTest) {
    for ((fmt, tag) <- Seq((FmtD, "Double"), (FmtX, "Extended"), (FmtP, "Packed"),
                           (FmtPd, "Packed-dyn"))) {
      val us = collect(Seq(fpOp(0, 1), stExt(fmt, 0)) ++ filler, 0x40800000L, 1)
      assert(us.length == 1 && us.head.faulted && us.head.faultVector == 11 &&
             us.head.faultUsesNextPc,
        s"$tag Dn-direct must trap (WRITE_EA_64/WRITE_EA_FPE fatalerror on mode 0): $us")
    }
  }

  test("the load direction (opclass 010) is unaffected by this task's new dispatch arm", VerilatorTest) {
    // FADD.L (A0),FP0 -- still 2 uops [LOAD -> T0, UFpIssue], not a store program.
    val ldExt = (2 << 13) | (0 << 10) | (0 << 7) | 0x22
    val us = collect(Seq(fpOp(2, 0), ldExt) ++ filler, 0x40800000L, 2)
    assert(us.length == 2, s"expected 2 uops: $us")
    assert(us(0).memOp.startsWith("LOAD") && us(0).dst == T0, s"load row unchanged: ${us(0)}")
    assert(us(1).op.startsWith("FPU"), s"terminal UFpIssue unchanged: ${us(1)}")
  }
}
