package m68k040.decode

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.cache.{IcachePlugin, IcacheSim}
import m68k040.frontend.FetchAlignPlugin
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer

/** FMOVEM.X data-register-list FSM (task #241/#246, `fmovemxActive` in DecodeStage --
  * the second instantiation of the RegListWalk shared skeleton) emission tests: drives
  * cpGEN opclass-110 (load) static-list packets through the fetch->align->decode pipe and
  * collects the emitted µop stream at the rename-facing sink, then asserts the per-element
  * [LOAD x3 chunks -> T0/T1/T2] + [FP issue row -> FPn] sub-phase shape, the register-list
  * -> address ordering (design doc §2: identity map for list-format bit12=0, `7-n` reverse
  * map for bit12=1), the drop/keep convention (every issue row dropped except the LAST
  * element's, which also carries writesFpcc=False per §2's "not a compute op"), and the
  * empty-list no-op case. Mirrors MovemDecodeSpec's own harness/style verbatim. */
class FmovemxDataListDecodeSpec extends AnyFunSuite {
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

  case class U(op: String, mem: String, dst: Int, dstV: Boolean, base: Int, baseV: Boolean,
               imm: Long, sizeL: Boolean, first: Boolean,
               fpDst: Int, writesFp: Boolean, writesFpcc: Boolean, dropped: Boolean,
               faulted: Boolean, faultVector: Int, useImm: Boolean, fpSrcKind: String)

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
      while (acc.length < n && guard < 800) {
        cd.waitSampling(); guard += 1
        if (dut.sink.logic.uopsOut.valid.toBoolean) {
          def rd(i: Int): U = {
            val p = dut.sink.logic.uopsOut.payload(i)
            U(p.op.toEnum.toString, p.memOp.toEnum.toString, p.dstReg.toInt, p.dstValid.toBoolean,
              p.srcAReg.toInt, p.srcAValid.toBoolean, p.imm.toLong & 0xffffffffL,
              p.size.toEnum == Size.LONG, p.firstOfInstr.toBoolean,
              p.fpDstReg.toInt, p.writesFp.toBoolean, p.writesFpcc.toBoolean, p.divIsRem.toBoolean,
              p.faulted.toBoolean, p.faultVector.toInt, p.useImm.toBoolean, p.fpSrcKind.toEnum.toString)
          }
          acc += rd(0)
          if (dut.sink.logic.u1v.toBoolean) acc += rd(1)
        }
      }
      out = acc.toSeq
    }
    out
  }

  // cpGEN memory-EA opword: 1111 001 000 mmmrrr = 0xF200|<ea>.
  def fpGenOp(mode: Int, reg: Int): Int = 0xF200 | (mode << 3) | reg
  // FMOVEM.X data-list ext1 word: opclass[15:13]=110 (load), bit12=listRev, bit11=0(static),
  // mask=bits[7:0].
  def fxExt1(listRev: Boolean, mask: Int): Int =
    (0x6 << 13) | (if (listRev) 1 << 12 else 0) | mask

  test("FMOVEM.X (An),FP0 single register load -> 3 LOAD chunks + 1 FP issue row (kept)", VerilatorTest) {
    val base = 0xA000L
    // Motorola PRM 5-85..5-88: control mode, bit7 selects FP0.
    val us = collect(Seq(fpGenOp(2, 1), fxExt1(listRev = true, 0x80), 0x4e71, 0x4e71), base, 4)
    assert(us.length == 4, s"expected 3 chunk loads + 1 issue row, got ${us.length}: $us")
    val loads = us.take(3)
    assert(loads.forall(u => u.mem == "LOAD" && u.base == 9 && u.baseV && u.sizeL && u.dropped),
      s"all 3 chunk loads must be LOAD/An-based/.L/dropped: $loads")
    assert(loads(0).dst == MicroOpAssembler.T0 && loads(0).imm == 0 && loads(0).first, s"chunk0=$us")
    assert(loads(1).dst == MicroOpAssembler.T1 && loads(1).imm == 4 && !loads(1).first, s"chunk1=$us")
    assert(loads(2).dst == MicroOpAssembler.T2 && loads(2).imm == 8 && !loads(2).first, s"chunk2=$us")
    val issue = us(3)
    assert(issue.op == "FPU" && issue.mem == "NONE" && !issue.dstV, s"issue=$us")
    assert(issue.fpDst == 0 && issue.writesFp && !issue.writesFpcc && !issue.dropped,
      s"single-element issue row must target FP0, write FP, NOT write FPCC, and be KEPT: $issue")
  }

  test("FMOVEM.X (An),FP0-FP2 -> ascending registers at ascending addresses", VerilatorTest) {
    val base = 0xA100L
    val us = collect(Seq(fpGenOp(2, 1), fxExt1(listRev = true, 0xe0), 0x4e71, 0x4e71), base, 12)
    assert(us.length == 12, s"expected 3 elements x 4 sub-phases, got ${us.length}: $us")
    val issues = (0 to 2).map(k => us(k * 4 + 3))
    assert(issues.map(_.fpDst) == Seq(0, 1, 2), s"control order must be FP0 through FP2: $us")
    // element k's first chunk load address = base(An) + 12*k.
    val firstLoads = (0 to 2).map(k => us(k * 4))
    assert(firstLoads.map(_.imm) == Seq(0L, 12L, 24L), s"element addresses must step by 12 (Extended): $firstLoads")
    // only the LAST element's issue row is kept; the first two are dropped.
    assert(issues(0).dropped && issues(1).dropped && !issues(2).dropped,
      s"only the LAST element's issue row is the kept macro commit: $issues")
    assert(issues.forall(!_.writesFpcc), s"FMOVEM data-list must never write FPCC: $issues")
    // exactly the very first emitted uop of the whole macro carries firstOfInstr.
    assert(us.head.first && us.drop(1).forall(!_.first), s"firstOfInstr markers: $us")
  }

  test("FMOVEM.X (An),FP5-FP7 (3 registers, control/postinc reverse map) -> bit n -> FP(7-n)", VerilatorTest) {
    val base = 0xA200L
    // mask bits 0,1,2 set (same 3 listed slots), reverse map: bit0->FP7, bit1->FP6, bit2->FP5.
    val us = collect(Seq(fpGenOp(2, 1), fxExt1(listRev = true, 0x07), 0x4e71, 0x4e71), base, 12)
    assert(us.length == 12, s"$us")
    val issues = (0 to 2).map(k => us(k * 4 + 3))
    assert(issues.map(_.fpDst) == Seq(5, 6, 7), s"control order must be FP5 through FP7: $us")
  }

  test("FMOVEM.X (An),FP0-FP7 (full 8-register list) -> 8 elements x 4 phases = 32 uops", VerilatorTest) {
    val base = 0xA300L
    val us = collect(Seq(fpGenOp(2, 1), fxExt1(listRev = true, 0xFF), 0x4e71, 0x4e71), base, 32)
    assert(us.length == 32, s"expected 8 elements x 4 sub-phases, got ${us.length}: $us")
    val issues = (0 to 7).map(k => us(k * 4 + 3))
    assert(issues.map(_.fpDst) == (0 to 7), s"$issues")
    assert(issues.dropWhile(_ != issues.last).length == 1 || issues.last.dropped == false,
      s"last element's issue row must be the kept commit")
    assert(issues.init.forall(_.dropped) && !issues.last.dropped, s"$issues")
    val firstLoads = (0 to 7).map(k => us(k * 4))
    assert(firstLoads.map(_.imm) == (0 to 7).map(k => (12L * k)), s"$firstLoads")
  }

  test("FMOVEM.X (d16,An),<list> load -> element addresses fold An + d16 + 12*k", VerilatorTest) {
    val base = 0xA400L
    // (d16,An) mode=101, An=A2 (reg 2), d16=0x40; FP0-FP1.
    val us = collect(Seq(fpGenOp(5, 2), fxExt1(listRev = true, 0xc0), 0x0040, 0x4e71), base, 8)
    assert(us.length == 8, s"$us")
    val firstLoads = Seq(us(0), us(4))
    assert(firstLoads.map(u => u.base) == Seq(10, 10), s"base An must be A2 (arch id 10): $firstLoads")
    assert(firstLoads.map(_.imm) == Seq(0x40L, 0x40L + 12L), s"$firstLoads")
  }

  test("FMOVEM.X empty list -> architectural no-op; the FOLLOWING instruction still decodes", VerilatorTest) {
    val base = 0xA500L
    // (An) mode=010, An=A1, mask=0 (empty), then MOVEQ #5,D0 (0x7005).
    val us = collect(Seq(fpGenOp(2, 1), fxExt1(listRev = true, 0x00), 0x7005, 0x4e71), base, 1)
    assert(us.length == 1, s"$us")
    assert(us(0).op == "MOVE" && us(0).mem == "NONE" && us(0).dst == 0 && us(0).imm == 5,
      s"expected the trailing MOVEQ to decode after the empty-list FMOVEM: $us")
  }

  // ── Decode-matrix collision tests (design doc §4's explicit warning) ─────────────────
  // FMOVECR also lives at cpGEN opclass 111 for the REGISTER-DIRECT case -- confirm the new
  // engine's memory-EA-only gate (`s0IsFpGenMemEa`'s mode ∈ {010,101} restriction) and
  // FMOVECR's own register-direct (mode 000) encoding are mutually exclusive by EA-class
  // alone, not just "should be" by inspection.
  test("FMOVECR #$0F,FP0 (register-direct, mode 000) is NOT claimed by the data-list engine", VerilatorTest) {
    val base = 0xA600L
    // opword 0xF200 (mode=000,reg=000 -- no real EA, FMOVECR reads none); ext1: opclass=010
    // (fpFormIsMovecr's own gate), srcSpec=111 (ROMCONST), dstFp=000 (FP0), opmode=$0F.
    val ext1 = (0x2 << 13) | (0x7 << 10) | (0x0 << 7) | 0x0F
    // request 2: the trailing NOP can ride the SAME 2-wide pop cycle as the 1-uop FMOVECR
    // (fast head, not this task's FSM) -- only us.head is under test here.
    val us = collect(Seq(0xF200, ext1, 0x4e71, 0x4e71), base, 2)
    assert(us.nonEmpty, s"$us")
    val u = us.head
    assert(u.op == "FPU" && u.mem == "NONE", s"$u")
    // FMOVECR's own shape (MicroOpAssembler's assemble() path): ROM offset rides `imm`, NOT
    // the data-list engine's [LOAD x3 chunks] + issue-row shape (which would show mem=="LOAD"
    // targeting T0/T1/T2 first).
    assert(u.useImm && (u.imm == 0x0FL), s"FMOVECR must ride its ROM offset via imm, not the data-list LOAD-chunk shape: $u")
    assert(u.fpSrcKind == "ROMCONST", s"$u")
  }

  // RE-POINTED 2026-09-11. This pinned opclass 111 (the FMOVEM.X STORE direction) as
  // "still traps -- task #242, not yet implemented". That task has since landed:
  // 44f38624 implemented the store direction. `FMOVEM.X <list>,(An)` is a legal
  // MC68040 instruction, so asserting a vector-11 trap was pinning non-68040
  // behaviour. Kept as a DECODE test of the store path rather than deleted, so the
  // opclass-111-vs-FMOVECR boundary this test was written to guard is still covered:
  // with a MEMORY EA it must be claimed by the data-list engine as a STORE, and must
  // NOT be mistaken for FMOVECR (whose gate is register-direct only).
  test("cpGEN memory-EA + ext1 opclass 111 decodes as an FMOVEM.X STORE (not FMOVECR)", VerilatorTest) {
    val base = 0xA700L
    val ext1 = (0x7 << 13) | 0x1080 // store, static control list, FP0
    val us = collect(Seq(fpGenOp(2, 1), ext1, 0x4e71, 0x4e71), base, 4)
    assert(us.nonEmpty, s"$us")
    assert(us.forall(u => !u.faulted),
      s"FMOVEM.X <list>,(An) is a real 68040 instruction and is implemented (44f38624) -- " +
      s"it must NOT trap: $us")
    assert(us.exists(_.mem == "STORE"),
      s"a memory-EA opclass-111 FMOVEM.X must produce STORE chunks (and must not be " +
      s"claimed by FMOVECR, whose gate is register-direct only): $us")
  }

  test("FMOVEM.X (An)+,FP0-FP7 (EA mode 011, load) decodes -- postincrement IS implemented", VerilatorTest) {
    val base = 0xA800L
    val us = collect(Seq(fpGenOp(3, 1), fxExt1(listRev = true, 0xFF), 0x4e71, 0x4e71), base, 4)
    assert(us.nonEmpty, s"$us")
    assert(us.forall(u => !u.faulted),
      s"(An)+ FMOVEM.X is a real 68040 instruction and is implemented (44f38624) -- it must NOT trap: $us")
    assert(us.head.mem == "LOAD" && us.head.baseV,
      s"first uop must be an An-based chunk LOAD: $us")
  }
  // RE-POINTED 2026-09-11, same reason as the `(An)+` case above: `-(An)` is a legal
  // MC68040 FMOVEM.X EA and the auto-update landed in 44f38624.
  test("FMOVEM.X FP0-FP7,-(An) (EA mode 100, store) decodes", VerilatorTest) {
    val base = 0xA900L
    // Predecrement is store-only; the old test incorrectly used a load encoding.
    val us = collect(Seq(fpGenOp(4, 1), 0xe0ff, 0x4e71, 0x4e71), base, 4)
    assert(us.nonEmpty, s"$us")
    assert(us.forall(u => !u.faulted),
      s"-(An) FMOVEM.X is a real 68040 instruction and is implemented (44f38624) -- it must NOT trap: $us")
    assert(us.exists(_.mem == "STORE"), s"predecrement must emit store chunks: $us")
  }

}
