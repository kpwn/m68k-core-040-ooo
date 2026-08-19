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

/** Task 9b: FMOVEM control-register LIST form — decode-level directed tests.
  *
  * Drives the REAL toolchain-confirmed opword/ext-word pairs (this task's report §1,
  * reproduced from `m68k-linux-gnu-as -m68040 -m68881`) through fetch->align->decode and
  * inspects the emitted µop stream, exactly like `FpMemLoadSpec` does for Task 6b. Only
  * `DecodeStage.ucBegin`'s real ext-word-aware dispatch picks the entry, so nothing
  * shorter than this end-to-end harness proves the right program was walked.
  *
  * The two things worth pinning here beyond "the right rows came out":
  *   - the terminal `SysKind.FMOVE_FPCTRL` sysOp is the LAST µop of the load direction
  *     (anything after it would be silently discarded by excSquash + S_REDIR), and
  *   - the store direction emits NO sysOp at all (design spec Decision 3).
  *
  * Design: docs/superpowers/specs/2026-08-16-fp-control-multiword-transfer-design.md
  */
class FmovemCtrlDecodeSpec extends AnyFunSuite {
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
               useImm: Boolean, imm: Long, sz: String, first: Boolean,
               sysOp: Boolean, isCapKind: Boolean, isApplyKind: Boolean, isNoKind: Boolean,
               sysReadDir: Boolean, readsFpcc: Boolean,
               faulted: Boolean, faultVector: Int, faultUsesNextPc: Boolean) {
    def isLoad  = memOp.startsWith("LOAD")
    def isStore = memOp.startsWith("STORE")
  }

  private def collect(words: Seq[Int], base: Long, n: Int): Seq[U] = {
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
              p.firstOfInstr.toBoolean,
              p.sysOp.toBoolean,
              p.sysKind.toEnum == SysKind.FPCTRL_CAP,
              p.sysKind.toEnum == SysKind.FMOVE_FPCTRL,
              p.sysKind.toEnum == SysKind.NONE,
              p.sysReadDir.toBoolean, p.readsFpcc.toBoolean,
              p.faulted.toBoolean, p.faultVector.toInt, p.faultUsesNextPc.toBoolean)
          }
          acc += rd(0)
          if (dut.sink.logic.u1v.toBoolean) acc += rd(1)
        }
      }
      out = acc.toSeq.take(n)
    }
    out
  }

  private val filler = Seq(0x4e71, 0x4e71, 0x4e71, 0x4e71, 0x4e71, 0x4e71)
  private val base = 0x40800000L
  private val T0 = MicroOpAssembler.T0
  private val T1 = MicroOpAssembler.T1
  private val T2 = MicroOpAssembler.T2
  /** imm[11:0] -> RobPlugin `sysRc`: bit3 = the BATCH marker, bits[2:0] = the mask. */
  private def rc(mask: Int) = 0x8L | mask
  private def sel(pos: Int, mask: Int) = (pos.toLong << 4) | rc(mask)

  private def assertApply(u: U, mask: Int, ctx: String): Unit = {
    assert(u.sysOp, s"$ctx: terminal row is not a sysOp: $u")
    assert(u.isApplyKind, s"$ctx: sysKind is not FMOVE_FPCTRL: $u")
    assert(!u.sysReadDir, s"$ctx: the batch form is write-direction only")
    assert(u.useImm && u.imm == rc(mask), f"$ctx: sysRc imm=0x${u.imm}%x expected 0x${rc(mask)}%x")
    assert(u.memOp.startsWith("NONE"), s"$ctx: the apply row must not be a memory µop")
  }
  private def assertCapture(u: U, temp: Int, ctx: String): Unit = {
    assert(u.isLoad, s"$ctx: expected a load, got $u")
    assert(!u.sysOp, s"$ctx: a capture row must NOT be a sysOp (it would squash the program)")
    assert(u.isCapKind, s"$ctx: sysKind is not FPCTRL_CAP: $u")
    assert(u.dst == temp && u.dstV, s"$ctx: capture slot comes from the dst temp; got ${u.dst}")
  }

  // ── LOAD direction ───────────────────────────────────────────────────────────────

  test("F21F 9C00 = FMOVEM.L (A7)+,FPIAR/FPSR/FPCR: 3 loads + A7+=12 + terminal apply LAST", VerilatorTest) {
    val us = collect(Seq(0xF21F, 0x9C00) ++ filler, base, 5)
    assert(us.length == 5, s"expected 5 uops, got ${us.length}: $us")
    // Three ordinary LONG loads off the UNMODIFIED A7, ascending +0/+4/+8 (D9).
    Seq((0, T0, 0L), (1, T1, 4L), (2, T2, 8L)).foreach { case (i, t, off) =>
      assertCapture(us(i), t, s"load $i")
      assert(us(i).srcA == 15 && us(i).srcAV, s"load $i must address off A7: $us")
      assert(us(i).sz.startsWith("LONG"), s"load $i size ${us(i).sz}")
      assert((if (us(i).useImm) us(i).imm else 0L) == off, s"load $i offset ${us(i).imm} expected $off")
    }
    assert(us(0).first, "the first load must carry firstOfInstr")
    // A7 += 12 BEFORE the sysOp: a write-back after it would never retire.
    assert(us(3).op.startsWith("ADD") && us(3).dst == 15 && us(3).dstV && us(3).imm == 12,
           s"A7 += 4*popcount write-back: ${us(3)}")
    assert(!us(3).sysOp, s"the write-back must not be a sysOp: ${us(3)}")
    assertApply(us(4), 0x7, "terminal")
    assert(us.take(4).forall(!_.sysOp), "no µop before the last may be a sysOp")
  }

  test("F21F/(A7)+ variants: exactly one sysOp per program, and it is the last µop", VerilatorTest) {
    // (A0) non-auto, mask=110 {FPCR,FPSR} -> 2 loads + apply.
    val us = collect(Seq(0xF210, 0x9800) ++ filler, base, 3)
    assert(us.length == 3, s"$us")
    assertCapture(us(0), T0, "load 0"); assertCapture(us(1), T1, "load 1")
    assert(us(0).srcA == 8 && us(1).srcA == 8, s"both loads address off A0: $us")
    assert((if (us(0).useImm) us(0).imm else 0L) == 0L && us(1).imm == 4L, s"ascending +0/+4: $us")
    assertApply(us(2), 0x6, "terminal")
  }

  test("F21F predecrement load -(A0): ONE up-front A0 -= 12, then ASCENDING transfers (D9)", VerilatorTest) {
    // FMOVEM.L -(A0),FPIAR/FPSR/FPCR — opword 0xF220 (mode 100, reg 000), ext 0x9C00.
    val us = collect(Seq(0xF220, 0x9C00) ++ filler, base, 5)
    assert(us.length == 5, s"$us")
    assert(us(0).op.startsWith("ADD") && us(0).dst == 8 && us(0).dstV && us(0).first,
           s"the single up-front decrement must come FIRST: ${us(0)}")
    assert(us(0).imm == 0xFFFFFFF4L, f"A0 -= 12 (two's complement): imm=0x${us(0).imm}%x")
    Seq((1, T0, 0L), (2, T1, 4L), (3, T2, 8L)).foreach { case (i, t, off) =>
      assertCapture(us(i), t, s"transfer ${i - 1}")
      assert((if (us(i).useImm) us(i).imm else 0L) == off,
             s"transfer ${i - 1} must ASCEND from the decremented base, not re-decrement: $us")
    }
    assert(us.count(u => u.op.startsWith("ADD")) == 1,
           "Musashi re-decrements per transfer for -(An); real hardware decrements ONCE (D9)")
    assertApply(us(4), 0x7, "terminal")
  }

  test("F210 9000 = FMOVE.L (A0),FPCR (popcount 1, memory <ea>): 1 load + apply", VerilatorTest) {
    // Previously a clean vector-11 trap (fpu_fmove_mem_ea_fpcr_fpsr_no_fline.s); folded in
    // by this task, since popcount==1 is just the N=1 case of the same mechanism.
    val us = collect(Seq(0xF210, 0x9000) ++ filler, base, 2)
    assert(us.length == 2, s"$us")
    assertCapture(us(0), T0, "load")
    assert(!us(0).faulted, s"must no longer trap: ${us(0)}")
    assertApply(us(1), 0x4, "terminal")
  }

  // ── STORE direction ──────────────────────────────────────────────────────────────

  test("F227 BC00 = FMOVEM.L FPIAR/FPSR/FPCR,-(A7) (the FPSP prologue): no sysOp anywhere", VerilatorTest) {
    val us = collect(Seq(0xF227, 0xBC00) ++ filler, base, 7)
    assert(us.length == 7, s"expected 7 uops, got ${us.length}: $us")
    assert(us.forall(!_.sysOp),
           "the store direction must use NO commit-time sysOp at all (design Decision 3)")
    assert(us.forall(_.isNoKind), s"no sysKind may be set: $us")
    // One up-front A7 -= 12, then read FPCR/FPSR/FPIAR, then store ascending +0/+4/+8.
    assert(us(0).op.startsWith("ADD") && us(0).dst == 15 && us(0).dstV && us(0).first &&
           us(0).imm == 0xFFFFFFF4L, s"A7 -= 12 first: ${us(0)}")
    Seq((1, T0, 0), (2, T1, 1), (3, T2, 2)).foreach { case (i, t, pos) =>
      assert(us(i).op.startsWith("FPCTRLRD") && us(i).cluster.startsWith("CPLX"),
             s"read $pos: ${us(i)}")
      assert(us(i).dst == t && us(i).dstV, s"read $pos dst: ${us(i)}")
      assert(us(i).readsFpcc, s"read $pos must declare a real FPCC dependency: ${us(i)}")
      assert(us(i).useImm && us(i).imm == sel(pos, 0x7),
             f"read $pos imm=0x${us(i).imm}%x expected 0x${sel(pos, 0x7)}%x")
    }
    Seq((4, T0, 0L), (5, T1, 4L), (6, T2, 8L)).foreach { case (i, t, off) =>
      assert(us(i).isStore, s"store: ${us(i)}")
      assert(us(i).srcA == 15 && us(i).srcAV && us(i).srcB == t && us(i).srcBV, s"store: ${us(i)}")
      assert((if (us(i).useImm) us(i).imm else 0L) == off,
             s"stores must ASCEND from the decremented A7 (D9), not reverse: $us")
    }
  }

  test("F227 B800 = FMOVEM.L FPCR/FPSR,-(A7) (mask 110): A7 -= 8, 2 reads + 2 stores", VerilatorTest) {
    val us = collect(Seq(0xF227, 0xB800) ++ filler, base, 5)
    assert(us.length == 5, s"$us")
    assert(us(0).imm == 0xFFFFFFF8L, f"A7 -= 4*2: imm=0x${us(0).imm}%x")
    assert(us(1).imm == sel(0, 0x6) && us(2).imm == sel(1, 0x6), s"read positions: $us")
    assert(us(3).isStore && us(4).isStore, s"$us")
    assert(us.forall(!_.sysOp), s"$us")
  }

  test("F210 B000 = FMOVE.L FPCR,(A0) (popcount 1, memory <ea>): 1 read + 1 store", VerilatorTest) {
    val us = collect(Seq(0xF210, 0xB000) ++ filler, base, 2)
    assert(us.length == 2, s"$us")
    assert(us(0).op.startsWith("FPCTRLRD") && us(0).dst == T0 && us(0).first &&
           us(0).imm == sel(0, 0x4), s"${us(0)}")
    assert(us(1).isStore && us(1).srcB == T0, s"${us(1)}")
    assert(us.forall(!_.sysOp), s"$us")
  }

  test("F210 A400 = FMOVE.L FPIAR,(A0): position 0 of a mask that does not include FPCR", VerilatorTest) {
    val us = collect(Seq(0xF210, 0xA400) ++ filler, base, 2)
    assert(us.length == 2, s"$us")
    // mask = 001 {FPIAR}: FPIAR is the ONLY selected register, so it is at position 0.
    assert(us(0).imm == sel(0, 0x1), f"imm=0x${us(0).imm}%x expected 0x${sel(0, 0x1)}%x")
  }

  // ── Rejected forms keep the existing clean vector-11 F-line trap ──────────────────

  private def assertTrap(us: Seq[U], ctx: String): Unit = {
    assert(us.nonEmpty, s"$ctx: no µop emitted")
    assert(us.head.faulted && us.head.faultVector == 11,
           s"$ctx: expected a vector-11 F-line trap, got ${us.head}")
    assert(us.head.faultUsesNextPc, s"$ctx: the trap must be RTE-able (FPSP-completable)")
    assert(us.forall(!_.sysOp), s"$ctx: a rejected form must emit no sysOp")
  }

  // Task fmovem_ctrl_pcdi_load (2026-08-19): `(d16,PC)` LOAD now decodes -- the Q700 ROM's
  // own `fmovem.l (d16,PC),FPCR/FPSR` at 0x408ED416 needs it (see
  // src/test/resources/m68kooo-ported-tests/asm/fmovem_ctrl_pcdi_load.s). STORE and the
  // indexed `(d8,PC,Xn)` form (both directions) stay rejected -- see the two tests below.
  test("F23A 9C00 000A = FMOVEM.L (10,PC),FPIAR/FPSR/FPCR: PC-relative LOAD decodes " +
       "(3 loads, no base register, terminal apply LAST)", VerilatorTest) {
    val us = collect(Seq(0xF23A, 0x9C00, 0x000A) ++ filler, base, 4)
    assert(us.length == 4, s"expected 4 uops, got ${us.length}: $us")
    // address = (insn + 4) + sext(disp16) = the address of the disp16 word itself, plus
    // the displacement -- mirrors this task's own asm-level report on the +4 offset.
    val addr = base + 4 + 10
    Seq((0, T0, 0L), (1, T1, 4L), (2, T2, 8L)).foreach { case (i, t, off) =>
      assertCapture(us(i), t, s"load $i")
      assert(!us(i).srcAV, s"PC-relative load must carry NO base register: ${us(i)}")
      assert(us(i).useImm && us(i).imm == (addr + off),
             f"load $i addr=0x${us(i).imm}%x expected 0x${addr + off}%x")
    }
    assert(us(0).first, "the first load must carry firstOfInstr")
    assertApply(us(3), 0x7, "terminal")
    assert(us.take(3).forall(!_.sysOp), "no µop before the last may be a sysOp")
  }

  test("F23A BC00 000A = FMOVEM.L FPIAR/FPSR/FPCR,(10,PC): STORE to PC-relative stays " +
       "rejected (not an alterable destination on real hardware)", VerilatorTest) {
    assertTrap(collect(Seq(0xF23A, 0xBC00, 0x000A) ++ filler, base, 1), "(d16,PC) store")
  }

  test("(d8,PC,Xn) indexed PC-relative stays rejected in BOTH directions " +
       "(needs the 3-phase index crack, out of scope)", VerilatorTest) {
    // opword bits[5:0] = 111,011 (mode 7 reg 3) -> 0xF23B; brief-format ext word (bit8=0)
    // so EaDecoder still classifies MEMSIMPLE+pcRel, but with indexValid=True this time.
    assertTrap(collect(Seq(0xF23B, 0x9C00, 0x000A) ++ filler, base, 1), "(d8,PC,Xn) load")
    assertTrap(collect(Seq(0xF23B, 0xBC00, 0x000A) ++ filler, base, 1), "(d8,PC,Xn) store")
  }

  test("mask == 000 stays trapped (Divergence Register D9b is unresolved)", VerilatorTest) {
    assertTrap(collect(Seq(0xF210, 0x8000) ++ filler, base, 1), "load, mask 000")
    assertTrap(collect(Seq(0xF210, 0xA000) ++ filler, base, 1), "store, mask 000")
  }

  test("a non-zero ext[9:0] tail stays trapped (reserved encoding)", VerilatorTest) {
    assertTrap(collect(Seq(0xF210, 0x9C01) ++ filler, base, 1), "load, ext tail 0x001")
    assertTrap(collect(Seq(0xF210, 0xBC80) ++ filler, base, 1), "store, ext tail 0x080")
  }

  test("Task 6b's opclass-010 memory loads are unaffected by this task's new gate", VerilatorTest) {
    // FADD.L (A0),FP0 — the exact case FpMemLoadSpec pins; re-checked here because both
    // gates now live in the same `ucFpRealEntry` mux.
    val us = collect(Seq(0xF210, 0x4022) ++ filler, base, 2)
    assert(us.length == 2, s"$us")
    assert(us(0).isLoad && us(0).dst == T0 && !us(0).sysOp, s"${us(0)}")
    assert(us(1).op.startsWith("FPU") && !us(1).sysOp && !us(1).readsFpcc, s"${us(1)}")
  }
}
