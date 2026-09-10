package m68k040.decode

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.cache.{IcachePlugin, IcacheSim}
import m68k040.frontend.FetchAlignPlugin
import m68k040.isa.{MemOp, Size}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer

/** MOVEM micro-sequencer (the DecodeStage FSM) emission tests: drives MOVEM packets
  * through the fetch->align->decode pipe and collects the emitted µop stream at the
  * rename-facing sink (2/cycle pop), then asserts the per-element LOAD/STORE µops (reg/
  * addr/size/dir), the 2/cycle pairing + odd tail, the reversed order for -(An), and the
  * single final An-update µop for (An)+/-(An). */
class MovemDecodeSpec extends AnyFunSuite {
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

  /** One collected µop (the fields under test). */
  case class U(op: String, mem: String, dst: Int, dstV: Boolean, srcB: Int, srcBV: Boolean,
               base: Int, baseV: Boolean, imm: Long, sizeL: Boolean, isMovea: Boolean, first: Boolean,
               last: Boolean,
               // Brief-indexed EA (mode 6 / mode 7 reg 3): the index register Xn rides
               // srcC, constant across the whole macro, with its own long/scale attributes.
               idx: Int, idxV: Boolean, idxLong: Boolean, idxScale: Int)

  /** Drive `words` at `base`, redirect, and collect up to `n` emitted µops. */
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
            U(p.op.toEnum.toString, p.memOp.toEnum.toString, p.dstReg.toInt, p.dstValid.toBoolean,
              p.srcBReg.toInt, p.srcBValid.toBoolean, p.srcAReg.toInt, p.srcAValid.toBoolean,
              p.imm.toLong & 0xffffffffL, p.size.toEnum == Size.LONG, p.isMovea.toBoolean, p.firstOfInstr.toBoolean,
              p.lastOfInstr.toBoolean,
              p.srcCReg.toInt, p.srcCValid.toBoolean, p.indexLong.toBoolean, p.indexScale.toInt)
          }
          acc += rd(0)
          if (dut.sink.logic.u1v.toBoolean) acc += rd(1)
        }
      }
      out = acc.toSeq
    }
    out
  }

  // MOVEM opword: 0100 1 d 001 s mmmrrr. d=bit10 (0 store / 1 load), s=bit6 (0 .W / 1 .L).
  def movem(dir: Int, sizeL: Int, mode: Int, reg: Int): Int =
    0x4880 | (dir << 10) | (sizeL << 6) | (mode << 3) | reg

  test("MOVEM.L D0/D1/A0,(A1) store -> 2 then 1 STORE uops + final An+=0 (control (An))", VerilatorTest) {
    val base = 0x9000L
    // store (.L), mode 2 (An), An=A1 (reg 1); mask D0(bit0),D1(bit1),A0(bit8) = 0x103.
    // The macro maps to ONE oracle step: an An-base mode emits a final KEPT An-update
    // (here An+=0, a no-op An write — Musashi leaves the control-mode An unchanged) so the
    // macro commits + advances PC; the 3 stores are dropped (verified via checkMem).
    val us = collect(Seq(movem(0, 1, 2, 1), 0x0103, 0x4e71, 0x4e71), base, 5)
    assert(us.length == 5, s"expected base snapshot + 3 stores + 1 An update, got ${us.length}: $us")
    val snap = us.head
    assert(snap.op == "ADD" && snap.mem == "NONE" && snap.dst == MicroOpAssembler.T0 &&
           snap.dstV && snap.base == 9 && snap.baseV && snap.first,
      s"control-mode base must be snapshotted before any transfer: $snap")
    val st = us.slice(1, 4)
    // ascending order: D0->A1+0, D1->A1+4, A0->A1+8. base = A1 = arch 9.
    assert(st.forall(s => s.mem == "STORE" && !s.dstV), s"$st")
    assert(st(0).srcB == 0 && st(0).base == MicroOpAssembler.T0 && st(0).baseV &&
           st(0).imm == 0 && st(0).sizeL && !st(0).first, s"u0=$us")
    assert(st(1).srcB == 1 && st(1).imm == 4 && !st(1).first, s"u1=$us")
    assert(st(2).srcB == 8 && st(2).imm == 8 && !st(2).first, s"u2=$us")
    // final An update: A1 += 0 (control mode -> An unchanged), no mem, no flags.
    assert(us(4).op == "ADD" && us(4).mem == "NONE" && us(4).dst == 9 && us(4).dstV &&
           us(4).imm == 0 && !us(4).first, s"anUpd=$us")
  }

  test("MOVEM.L (A1)+,D0/D1/A0 load -> 3 LOAD uops + final An update (A1 += 12)", VerilatorTest) {
    val base = 0x9100L
    // load (.L), mode 3 ((An)+), An=A1; mask D0,D1,A0 = 0x103.
    val us = collect(Seq(movem(1, 1, 3, 1), 0x0103, 0x4e71, 0x4e71), base, 4)
    assert(us.length == 4, s"expected 3 loads + 1 An update, got ${us.length}: $us")
    assert(us(0).mem == "LOAD" && us(0).dst == 0 && us(0).dstV && us(0).base == 9 && us(0).imm == 0 && us(0).first, s"u0=$us")
    assert(us(1).mem == "LOAD" && us(1).dst == 1 && us(1).imm == 4, s"u1=$us")
    assert(us(2).mem == "LOAD" && us(2).dst == 8 && us(2).imm == 8, s"u2=$us")
    // final An update: ADD A1 += count(3)*4 = 12, no mem, no flags.
    assert(us(3).op == "ADD" && us(3).mem == "NONE" && us(3).dst == 9 && us(3).dstV &&
           us(3).base == 9 && us(3).imm == 12 && !us(3).first, s"anUpd=$us")
  }

  test("MOVEM.W (A1)+,D0/D1 load -> .W loads carry the sign-extend marker (isMovea)", VerilatorTest) {
    val base = 0x9200L
    // load (.W), mode 3, An=A1; mask D0,D1 = 0x3.
    val us = collect(Seq(movem(1, 0, 3, 1), 0x0003, 0x4e71, 0x4e71), base, 3)
    assert(us.length == 3, s"$us")
    assert(us(0).mem == "LOAD" && !us(0).sizeL && us(0).isMovea && us(0).imm == 0, s"u0=$us")
    assert(us(1).mem == "LOAD" && !us(1).sizeL && us(1).isMovea && us(1).imm == 2, s"u1=$us")
    // final An update: A1 += count(2)*2 = 4.
    assert(us(2).op == "ADD" && us(2).dst == 9 && us(2).imm == 4, s"anUpd=$us")
  }

  test("MOVEM.W D0/D1,(A1) store -> .W stores do NOT carry isMovea (store side)", VerilatorTest) {
    val base = 0x9280L
    val us = collect(Seq(movem(0, 0, 2, 1), 0x0003, 0x4e71, 0x4e71), base, 4)
    assert(us.length == 4, s"$us")
    assert(us.head.dst == MicroOpAssembler.T0 && us.head.first, s"missing base snapshot: $us")
    assert(us.slice(1, 3).forall(u => u.mem == "STORE" && !u.sizeL && !u.isMovea), s"$us")
    assert(us(3).op == "ADD" && us(3).imm == 0, s"control (An) final An+=0: $us")
  }

  test("MOVEM.L D0-D7/A0-A6,-(A7) store predec -> reversed order, 8 cycles, final A7 -= 60", VerilatorTest) {
    val base = 0x9300L
    // store (.L), mode 4 (-(An)), An=A7 (reg 7). gas reverses the predec mask: for
    // D0-D7/A0-A6 (A7 excluded) the mask word is 0xFFFE (bit0=A7 clear, bits1-15 set).
    val us = collect(Seq(movem(0, 1, 4, 7), 0xfffe, 0x4e71, 0x4e71), base, 16)
    assert(us.length == 16, s"expected 15 stores + 1 An update, got ${us.length}: $us")
    val stores = us.take(15)
    assert(stores.forall(_.mem == "STORE"), s"$stores")
    // predec reversed: extract lowest-set-bit-first, map bit i -> reg 15-i, addresses
    // DESCEND base-4, base-8, ... Mask 0xFFFE: bit1->reg14(A6)@-4, bit2->reg13(A5)@-8,
    // ..., bit15->reg0(D0)@-60. So extracted regs = A6,A5,A4,A3,A2,A1,A0,D7,..,D0.
    val expRegs = (1 to 15).map(i => 15 - i)             // bit i (i=1..15) -> reg 15-i = 14,13,..,0
    for (k <- 0 until 15) {
      val expImm = -(4L * (k + 1)) & 0xffffffffL
      assert(stores(k).srcB == expRegs(k) && stores(k).base == 15 && stores(k).imm == expImm,
        s"store[$k] reg=${stores(k).srcB} (exp ${expRegs(k)}) imm=${stores(k).imm.toInt} (exp ${-(4*(k+1))}): $us")
    }
    // exactly the first store is firstOfInstr.
    assert(stores(0).first && stores.drop(1).forall(!_.first), s"first markers: $us")
    // final An update: A7 -= count(15)*4 = 60 -> imm = -60.
    val an = us(15)
    assert(an.op == "ADD" && an.dst == 15 && an.imm == ((-60L) & 0xffffffffL) && !an.first, s"anUpd=$us")
  }

  test("MOVEM.L all 16 registers preserves +64/-64 running and final deltas", VerilatorTest) {
    val postBase = 0x9340L
    val post = collect(Seq(movem(1, 1, 3, 1), 0xffff, 0x4e71, 0x4e71), postBase, 17)
    assert(post.length == 17, s"expected 16 loads + final An update, got ${post.length}: $post")
    assert(post.take(16).zipWithIndex.forall { case (u, i) =>
      u.mem == "LOAD" && u.imm == 4L * i
    }, s"postincrement offsets must be 0..60 by four: $post")
    assert(post.last.op == "ADD" && post.last.dst == 9 && post.last.imm == 64,
      s"all-16 postincrement final delta must be +64: ${post.last}")

    val preBase = 0x9360L
    val pre = collect(Seq(movem(0, 1, 4, 7), 0xffff, 0x4e71, 0x4e71), preBase, 17)
    assert(pre.length == 17, s"expected 16 stores + final An update, got ${pre.length}: $pre")
    assert(pre.take(16).zipWithIndex.forall { case (u, i) =>
      u.mem == "STORE" && u.imm == ((-4L * (i + 1)) & 0xffffffffL)
    }, s"predecrement offsets must be -4..-64 by four: $pre")
    assert(pre.last.op == "ADD" && pre.last.dst == 15 &&
           pre.last.imm == ((-64L) & 0xffffffffL),
      s"all-16 predecrement final delta must be -64: ${pre.last}")
  }

  test("MOVEM.L (d16,PC),D0/D1 load -> addresses fold EA_PCDI = pc+4+d16 (base invalid)", VerilatorTest) {
    val base = 0x9380L
    // load (.L), mode 7 reg 2 ((d16,PC)), d16 = 0x20; mask D0,D1 = 0x3.
    val us = collect(Seq(movem(1, 1, 7, 2), 0x0003, 0x0020, 0x4e71), base, 2)
    assert(us.length == 2, s"$us")
    // base read invalid (abs/PC folded into imm); EA_PCDI = pc + 4 + 0x20 = base+0x24.
    // Control mode -> NO final An update (only the 2 loads).
    val pcdi = base + 4 + 0x20
    assert(us(0).mem == "LOAD" && !us(0).baseV && (us(0).imm == (pcdi & 0xffffffffL)), s"u0=$us pcdi=${pcdi.toHexString}")
    assert(us(1).mem == "LOAD" && !us(1).baseV && (us(1).imm == ((pcdi + 4) & 0xffffffffL)), s"u1=$us")
  }

  test("MOVEM.L D3,(A1) single register -> one STORE + final An+=0 (control)", VerilatorTest) {
    val base = 0x9400L
    val us = collect(Seq(movem(0, 1, 2, 1), 0x0008, 0x4e71, 0x4e71), base, 3)
    assert(us.length == 3, s"$us")
    assert(us(0).dst == MicroOpAssembler.T0 && us(0).first, s"missing base snapshot: $us")
    assert(us(1).mem == "STORE" && us(1).srcB == 3 && us(1).imm == 0 && !us(1).first, s"u0=$us")
    assert(us(2).op == "ADD" && us(2).dst == 9 && us(2).imm == 0, s"control single final An+=0: $us")
  }

  test("MOVEM.L (A1)+,D3 single register load -> one LOAD + final An update (A1 += 4)", VerilatorTest) {
    val base = 0x9480L
    val us = collect(Seq(movem(1, 1, 3, 1), 0x0008, 0x4e71, 0x4e71), base, 2)
    assert(us.length == 2, s"$us")
    assert(us(0).mem == "LOAD" && us(0).dst == 3 && us(0).imm == 0 && us(0).first, s"u0=$us")
    assert(us(1).op == "ADD" && us(1).dst == 9 && us(1).imm == 4, s"anUpd=$us")
  }

  test("MOVEM empty mask (control) -> no moves; the FOLLOWING instruction still decodes", VerilatorTest) {
    val base = 0x9500L
    // MOVEM.L D-none,(A1) with mask 0, then MOVEQ #5,D0 (0x7005) must decode (FSM resumes).
    val us = collect(Seq(movem(0, 1, 2, 1), 0x0000, 0x7005, 0x4e71), base, 1)
    assert(us.length == 1, s"$us")
    assert(us(0).op == "MOVE" && us(0).mem == "NONE" && us(0).dst == 0 && us(0).imm == 5,
      s"expected the trailing MOVEQ to decode after the empty MOVEM: $us")
  }

  // ── Stage 2 task 4: `lastOfInstr` (spec section 6.2's `macroLast`) off the REAL FSM ──
  // The interesting property, and the one a "last = !first" shortcut gets wrong: for every
  // An-base MOVEM form the macro's last µop is the TRAILING An-update µop, not the last
  // data transfer -- so a MOVEM has middle µops that are neither first nor last.
  test("MOVEM lastOfInstr marks the trailing An-update uop, not the last transfer", VerilatorTest) {
    val base = 0x9700L
    // MOVEM.L (A1)+,D0/D1/A0 -> 3 LOADs (2 in one cycle + an odd tail) + the An update.
    val us = collect(Seq(movem(1, 1, 3, 1), 0x0103, 0x4e71, 0x4e71), base, 4)
    assert(us.length == 4, s"expected 3 loads + 1 An update, got ${us.length}: $us")
    assert(us.take(3).forall(u => u.mem == "LOAD" && !u.last),
      s"no transfer uop may claim lastOfInstr while a trailing An update follows: $us")
    assert(us(3).op == "ADD" && us(3).mem == "NONE" && us(3).last,
      s"the trailing An-update uop IS the macro's last uop: $us")
    // ...and the macro's MIDDLE uops are neither first nor last (the exact shape a
    // mechanical `last = !first` would have gotten wrong).
    assert(us(1).first == false && us(1).last == false, s"uop 1 must be neither first nor last: $us")
    assert(us(2).first == false && us(2).last == false, s"uop 2 must be neither first nor last: $us")
  }

  test("MOVEM lastOfInstr: PC-relative form (no An update) marks the final transfer", VerilatorTest) {
    // (d16,PC) has NO An base -> movemHasFinal is False -> no trailing An-update uop, so
    // the LAST TRANSFER carries lastOfInstr. Two sub-cases, because the FSM emits up to
    // TWO transfers per cycle and the marker must land on the right one of the pair.
    // (a) 2 registers -> ONE emission cycle of 2 moves: the SECOND of the pair is last.
    val pair = collect(Seq(movem(1, 1, 7, 2), 0x0003, 0x0020, 0x4e71), 0x9780L, 2)
    assert(pair.length == 2, s"$pair")
    assert(pair(0).first && !pair(0).last, s"pair uop0 is first, not last: $pair")
    assert(!pair(1).first && pair(1).last, s"pair uop1 (2nd of the emitted pair) is last: $pair")
    // (b) 3 registers -> a pair then an ODD TAIL cycle of 1 move: the tail is last, and
    //     the 2nd of the leading pair must NOT be (its cycle does not drain the mask).
    val odd = collect(Seq(movem(1, 1, 7, 2), 0x0103, 0x0020, 0x4e71), 0x97c0L, 3)
    assert(odd.length == 3, s"$odd")
    assert(!odd(0).last && !odd(1).last, s"neither of the leading pair may be last: $odd")
    assert(odd(2).last, s"the odd-tail transfer is the macro's last uop: $odd")
  }

  test("MOVEM lastOfInstr: the base-snapshot uop is never the macro's last uop", VerilatorTest) {
    // Control-mode (An) with the base An itself addressable -> a leading snapshot uop
    // (firstOfInstr) precedes every transfer, and the trailing An+=0 is still last.
    val us = collect(Seq(movem(0, 1, 2, 1), 0x0103, 0x4e71, 0x4e71), 0x9800L, 5)
    assert(us.length == 5, s"expected snapshot + 3 stores + An update, got ${us.length}: $us")
    assert(us(0).first && !us(0).last, s"the snapshot uop is first, never last: $us")
    assert(us.slice(1, 4).forall(!_.last), s"no store may claim lastOfInstr: $us")
    assert(us(4).op == "ADD" && us(4).last, s"the trailing An update is last: $us")
  }

  // ══════════════════════════════════════════════════════════════════════════
  // `(d8,An,Xn)` brief-indexed MOVEM (EA mode 6) — BOTH directions.
  //
  // The LOAD direction was admitted by task movem-agu-index-hazard-2026-08-19; the
  // STORE direction by task movem-idx-an-store-2026-09-11. `(d8,An,Xn)` is a CONTROL
  // ALTERABLE mode, so `MOVEM <list>,(d8,An,Xn)` is a real MC68040 instruction
  // (Musashi's `movem_re_*` EA mask `A+-DXWL` includes mode 6) and trapping it was a
  // divergence from silicon. Mode 6 is also the ONLY MOVEM EA with a real base register
  // AND a real index register live at once, so it is the only shape that emits BOTH
  // snapshot µops (base/T0 first, then index/T1).
  //
  // Brief-format extension word, at words(2) (the register-mask word occupies words(1)):
  //   bit15 = D/A, bits14:12 = Xn, bit11 = W/L, bits10:9 = scale, bit8 = 0 (brief),
  //   bits7:0 = d8.
  // ══════════════════════════════════════════════════════════════════════════
  def briefExt(da: Int, xn: Int, long: Int, scale: Int, d8: Int): Int =
    (da << 15) | (xn << 12) | (long << 11) | (scale << 9) | (d8 & 0xff)

  test("MOVEM.L D1/D4,(0x10,A2,D3.l*4) STORE (EA mode 6) -> base+index snapshots, indexed stores, An+=0", VerilatorTest) {
    val base = 0x9880L
    // store (.L), mode 6, An = A2 (arch reg 10); mask D1(bit1)/D4(bit4) = 0x12;
    // ext = D3 as a LONG index, scale 4, d8 = 0x10.
    val us = collect(Seq(movem(0, 1, 6, 2), 0x0012, briefExt(0, 3, 1, 2, 0x10), 0x4e71), base, 5)
    assert(us.length == 5, s"expected base snap + index snap + 2 stores + An update, got ${us.length}: $us")
    // BOTH snapshots run, base/T0 first then index/T1 (DecodeStage's movemSnapIdxPending
    // sequencing). NOTE: movemSnapUop sets firstOfInstr unconditionally, so in this
    // two-snapshot shape BOTH carry it -- benign (both share the macro's own pc, so an
    // interrupt taken at either returns to the MOVEM and re-runs the whole macro).
    assert(us(0).op == "ADD" && us(0).mem == "NONE" && us(0).dst == MicroOpAssembler.T0 &&
           us(0).dstV && us(0).base == 10 && us(0).baseV && us(0).first,
      s"uop0 must be the base snapshot T0 := A2: $us")
    assert(us(1).op == "ADD" && us(1).mem == "NONE" && us(1).dst == MicroOpAssembler.T1 &&
           us(1).dstV && us(1).base == 3 && us(1).baseV,
      s"uop1 must be the index snapshot T1 := D3: $us")
    val st = us.slice(2, 4)
    assert(st.forall(s => s.mem == "STORE" && !s.dstV && s.sizeL && !s.isMovea),
      s"both transfers must be plain .L STOREs that write no register: $st")
    // The index rides srcC on EVERY element, redirected to the immutable T1 snapshot,
    // carrying the brief format's long/scale attributes; only the folded disp walks.
    assert(st.forall(s => s.idxV && s.idx == MicroOpAssembler.T1 && s.idxLong && s.idxScale == 2),
      s"the index must ride srcC (T1 snapshot, long, scale 4) on both stores: $st")
    assert(st(0).srcB == 1 && st(0).srcBV && st(0).base == MicroOpAssembler.T0 && st(0).baseV &&
           st(0).imm == 0x10, s"store0 = D1 at base+d8: $us")
    assert(st(1).srcB == 4 && st(1).base == MicroOpAssembler.T0 && st(1).imm == 0x14,
      s"store1 = D4 at base+d8+4: $us")
    // Control mode -> the trailing kept `An += 0` commit, exactly like (An)/(d16,An).
    assert(us(4).op == "ADD" && us(4).mem == "NONE" && us(4).dst == 10 && us(4).dstV &&
           us(4).imm == 0 && us(4).last, s"anUpd=$us")
  }

  // The LOAD direction of the SAME EA, as a side-by-side control: the ONLY differences
  // must be the memOp/dst routing and the far-page probe carrier -- the snapshots, the
  // srcC index threading, the folded displacements and the trailing An+=0 are identical.
  test("MOVEM.L (0x10,A2,D3.l*4),D1/D4 LOAD (EA mode 6) -> same EA machinery, LOAD routing", VerilatorTest) {
    val base = 0x98c0L
    val us = collect(Seq(movem(1, 1, 6, 2), 0x0012, briefExt(0, 3, 1, 2, 0x10), 0x4e71), base, 5)
    assert(us.length == 5, s"expected base snap + index snap + 2 loads + An update, got ${us.length}: $us")
    assert(us(0).dst == MicroOpAssembler.T0 && us(1).dst == MicroOpAssembler.T1,
      s"both snapshots must still be emitted for the LOAD direction: $us")
    val ld = us.slice(2, 4)
    assert(ld.forall(u => u.mem == "LOAD" && u.dstV && !u.srcBV),
      s"both transfers must be LOADs that write a register and read no store data: $ld")
    assert(ld.forall(u => u.idxV && u.idx == MicroOpAssembler.T1 && u.idxLong && u.idxScale == 2),
      s"the index rides srcC identically in the LOAD direction: $ld")
    assert(ld(0).dst == 1 && ld(0).imm == 0x10 && ld(1).dst == 4 && ld(1).imm == 0x14, s"$us")
    assert(us(4).op == "ADD" && us(4).dst == 10 && us(4).imm == 0 && us(4).last, s"anUpd=$us")
  }

  test("MOVEM then a following MOVEQ -> front-end resumes (the held fed releases cleanly)", VerilatorTest) {
    val base = 0x9600L
    // MOVEM.L D0/D1,(A1) (2 stores + final An+=0) then MOVEQ #7,D2 (0x7407).
    val us = collect(Seq(movem(0, 1, 2, 1), 0x0003, 0x7407, 0x4e71), base, 5)
    assert(us.length == 5, s"$us")
    assert(us(0).dst == MicroOpAssembler.T0 && us(0).first, s"missing base snapshot: $us")
    assert(us(1).mem == "STORE" && us(2).mem == "STORE", s"$us")
    assert(us(3).op == "ADD" && us(3).imm == 0, s"control (An) final An+=0: $us")
    assert(us(4).op == "MOVE" && us(4).mem == "NONE" && us(4).dst == 2 && us(4).imm == 7,
      s"expected the trailing MOVEQ #7,D2 after the MOVEM: $us")
  }
}
