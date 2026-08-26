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
    // (An) mode=010, An=A1 (reg 1); mask=0x01 (FP0), forward/identity map.
    val us = collect(Seq(fpGenOp(2, 1), fxExt1(listRev = false, 0x01), 0x4e71, 0x4e71), base, 4)
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

  test("FMOVEM.X (An),FP0-FP2 (3 registers, forward/identity map) -> ascending addr, bit n -> FPn", VerilatorTest) {
    val base = 0xA100L
    val us = collect(Seq(fpGenOp(2, 1), fxExt1(listRev = false, 0x07), 0x4e71, 0x4e71), base, 12)
    assert(us.length == 12, s"expected 3 elements x 4 sub-phases, got ${us.length}: $us")
    val issues = (0 to 2).map(k => us(k * 4 + 3))
    assert(issues.map(_.fpDst) == Seq(0, 1, 2), s"forward map: bit n -> FPn, got ${issues.map(_.fpDst)}: $us")
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
    assert(issues.map(_.fpDst) == Seq(7, 6, 5), s"reverse map: bit n -> FP(7-n), got ${issues.map(_.fpDst)}: $us")
  }

  test("FMOVEM.X (An),FP0-FP7 (full 8-register list) -> 8 elements x 4 phases = 32 uops", VerilatorTest) {
    val base = 0xA300L
    val us = collect(Seq(fpGenOp(2, 1), fxExt1(listRev = false, 0xFF), 0x4e71, 0x4e71), base, 32)
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
    // (d16,An) mode=101, An=A2 (reg 2), d16=0x40; mask=0x03 (2 elements).
    val us = collect(Seq(fpGenOp(5, 2), fxExt1(listRev = false, 0x03), 0x0040, 0x4e71), base, 8)
    assert(us.length == 8, s"$us")
    val firstLoads = Seq(us(0), us(4))
    assert(firstLoads.map(u => u.base) == Seq(10, 10), s"base An must be A2 (arch id 10): $firstLoads")
    assert(firstLoads.map(_.imm) == Seq(0x40L, 0x40L + 12L), s"$firstLoads")
  }

  test("FMOVEM.X empty list -> architectural no-op; the FOLLOWING instruction still decodes", VerilatorTest) {
    val base = 0xA500L
    // (An) mode=010, An=A1, mask=0 (empty), then MOVEQ #5,D0 (0x7005).
    val us = collect(Seq(fpGenOp(2, 1), fxExt1(listRev = false, 0x00), 0x7005, 0x4e71), base, 1)
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

  test("cpGEN memory-EA + ext1 opclass 111 (store direction, NOT this task's scope) still traps -- unaffected by this task", VerilatorTest) {
    val base = 0xA700L
    // (An) mode=010, An=A1; ext1 opclass=111 (store direction -- task #242, not yet
    // implemented) with a nonzero static-list mask. This is the exact boundary the design
    // doc flags: opclass 111 is ALSO the raw bits FMOVECR aliases for the register-direct
    // case -- but with a MEMORY EA (mode 010) instead of register-direct (mode 000), so
    // this must NOT be claimed by either FMOVECR's own register-direct-only gate OR (yet)
    // this task's opclass-110-only data-list engine -- it must fall through to the
    // PRE-EXISTING µcode-engine trap path exactly as it did before this task (a regression
    // check, not a new-feature check).
    val ext1 = (0x7 << 13) | 0x01   // opclass=111, static (bit11=0), mask=0x01
    val us = collect(Seq(fpGenOp(2, 1), ext1, 0x4e71, 0x4e71), base, 2)
    assert(us.nonEmpty, s"$us")
    val u = us.head
    assert(u.faulted && u.faultVector == 11,
      s"opclass-111 memory-EA FMOVEM must still trap (vector 11, FP_MEM_TRAP_ENTRY) -- this task's gate is opclass-110-only: $u")
    // NOT the data-list engine's LOAD-chunk shape.
    assert(!(u.mem == "LOAD" && u.dst == MicroOpAssembler.T0),
      s"must NOT have been claimed by the data-list engine: $u")
  }

  // ── SCOPE-BOUNDARY REGRESSION (campaign/fmovem-postinc-ring): `(An)+`/`-(An)` are NOT
  // admitted yet, contrary to what the overall design doc's §1 table implies for the
  // FEATURE'S EVENTUAL full scope -- `s0IsFpGenMemEa`'s EA-mode restriction (this file's
  // header doc, DecodeStage.scala's `slot0IsFmovemx` comment) admits ONLY mode 010 `(An)`
  // and mode 101 `(d16,An)`; postincrement/predecrement/indexed/PC-relative are still
  // task #242-245 (design doc §7 breakdown items 2-4), UNLANDED as of this task -- no
  // `fmovemxAnUop`-equivalent An-auto-update µop exists anywhere in DecodeStage.scala.
  // This pins that boundary precisely: `FMOVEM.X (An)+,<list>` (opclass 110, EA mode
  // 011) must still fall through to the pre-existing µcode-engine trap
  // (FP_MEM_TRAP_ENTRY, vector 11) exactly like the opclass-111 store case above, NOT
  // silently reach the data-list engine with a wrong (non-auto-updating) address.
  // Update/delete this test the moment `(An)+`/`-(An)` actually lands.
  test("FMOVEM.X (An)+,FP0-FP7 (EA mode 011, load direction -- postinc NOT YET implemented) still traps", VerilatorTest) {
    val base = 0xA800L
    // (An)+ mode=011, An=A1; ext1 opclass=110 (load direction, IN this task's scope),
    // static list, full mask -- everything about this word IS admitted except the EA mode.
    val us = collect(Seq(fpGenOp(3, 1), fxExt1(listRev = true, 0xFF), 0x4e71, 0x4e71), base, 2)
    assert(us.nonEmpty, s"$us")
    val u = us.head
    assert(u.faulted && u.faultVector == 11,
      s"opclass-110 (An)+ FMOVEM must still trap (vector 11, FP_MEM_TRAP_ENTRY) -- " +
      s"postincrement is unowned until task #242-245 land: $u")
    assert(!(u.mem == "LOAD" && u.dst == MicroOpAssembler.T0),
      s"must NOT have been claimed by the data-list engine (which has no An-update path): $u")
  }

  test("FMOVEM.X -(An),FP0-FP7 (EA mode 100, predecrement NOT YET implemented) still traps", VerilatorTest) {
    val base = 0xA900L
    val us = collect(Seq(fpGenOp(4, 1), fxExt1(listRev = false, 0xFF), 0x4e71, 0x4e71), base, 2)
    assert(us.nonEmpty, s"$us")
    val u = us.head
    assert(u.faulted && u.faultVector == 11,
      s"opclass-110 -(An) FMOVEM must still trap (vector 11, FP_MEM_TRAP_ENTRY): $u")
  }
}
