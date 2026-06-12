package m68k040.decode

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.cache.{IcachePlugin, IcacheSim}
import m68k040.frontend.FetchAlignPlugin
import m68k040.isa.{MemOp, Size}
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer

/** µcode SEQUENCER (the DecodeStage ROM-driven engine) emission tests: drive a
  * `microcoded` opword (an ABCD/ADDX -(Ay),-(Ax) memory form) through fetch->align->
  * decode and collect the emitted µop stream at the rename-facing sink (2/cycle pop),
  * then assert the 6-µop sequence (the two predec loads + their An write-backs, the BCD/
  * ADDX op into T2, the trailing store). This is the engine's proof-of-concept (the
  * Microcode ROM generalizes the MOVEM micro-sequencer). */
class MicrocodeSpec extends AnyFunSuite {
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

  case class U(op: String, mem: String, dst: Int, dstV: Boolean, srcA: Int, srcAV: Boolean,
               srcB: Int, srcBV: Boolean, imm: Long, eaAuto: String, eaDelta: Int,
               wNz: Boolean, wX: Boolean, drop: Boolean, first: Boolean, sizeL: Boolean,
               bcdSub: Boolean)

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
              p.srcAReg.toInt, p.srcAValid.toBoolean, p.srcBReg.toInt, p.srcBValid.toBoolean,
              p.imm.toLong & 0xffffffffL, p.eaAuto.toEnum.toString, p.eaDelta.toInt,
              p.writesNzvc.toBoolean, p.writesX.toBoolean, p.divIsRem.toBoolean,
              p.firstOfInstr.toBoolean, p.size.toEnum == Size.LONG, p.bcdSub.toBoolean)
          }
          acc += rd(0)
          if (dut.sink.logic.u1v.toBoolean) acc += rd(1)
        }
      }
      out = acc.toSeq
    }
    out
  }

  // ── ABCD -(A1),-(A0) (0xC109): the 6-µop microcode sequence ──────────────────
  test("ABCD -(A1),-(A0) (0xC109) emits the 6-µop microcode sequence", VerilatorTest) {
    val base = 0x40800000L
    val us = collect(Seq(0xC109, 0x4e71, 0x4e71, 0x4e71), base, 6)
    assert(us.length == 6, s"expected 6 µops, got ${us.length}: $us")
    // µPC0: LOAD (A1=9) -> T0(16), PREDEC delta 1, firstOfInstr
    assert(us(0).mem == "LOAD" && us(0).dst == 16 && us(0).dstV && us(0).srcA == 9 &&
           us(0).eaAuto == "PREDEC" && us(0).eaDelta == 1 && us(0).first,
      s"µPC0 load(A1)->T0 predec: ${us(0)}")
    // µPC1: ADD A1 += -1 (dropped An write-back)
    assert(us(1).op == "ADD" && us(1).dst == 9 && us(1).srcA == 9 && us(1).drop &&
           (us(1).imm & 0xffffffffL) == 0xffffffffL,
      s"µPC1 A1 -= 1 dropped: ${us(1)}")
    // µPC2: LOAD (A0=8) -> T1(17), PREDEC
    assert(us(2).mem == "LOAD" && us(2).dst == 17 && us(2).srcA == 8 && us(2).eaAuto == "PREDEC",
      s"µPC2 load(A0)->T1 predec: ${us(2)}")
    // µPC3: ADD A0 += -1 (dropped)
    assert(us(3).op == "ADD" && us(3).dst == 8 && us(3).srcA == 8 && us(3).drop,
      s"µPC3 A0 -= 1 dropped: ${us(3)}")
    // µPC4: BCD T1,T0 -> T1(17) (result reuses T1), writes NZVC+X, bcdSub False (ABCD)
    assert(us(4).op == "BCD" && us(4).dst == 17 && us(4).srcA == 17 && us(4).srcB == 16 &&
           us(4).wNz && us(4).wX && !us(4).bcdSub,
      s"µPC4 BCD T1,T0->T2: ${us(4)}")
    // µPC5: STORE T1(17) -> (A0=8), no auto, NOT first
    assert(us(5).mem == "STORE" && us(5).srcA == 8 && us(5).srcB == 17 && us(5).srcBV &&
           us(5).eaAuto == "NONE" && !us(5).first,
      s"µPC5 store T2->(A0): ${us(5)}")
  }

  // ── SBCD -(A1),-(A0) (0x8109): bcdSub True on the op µop ─────────────────────
  test("SBCD -(A1),-(A0) (0x8109) op µop carries bcdSub=True", VerilatorTest) {
    val us = collect(Seq(0x8109, 0x4e71, 0x4e71, 0x4e71), 0x40800000L, 6)
    assert(us.length == 6, s"expected 6 µops: $us")
    assert(us(4).op == "BCD" && us(4).bcdSub, s"µPC4 SBCD bcdSub True: ${us(4)}")
  }

  // ── ADDX.L -(A2),-(A3) (0xD78A): op=ADDX, size LONG, predec delta 4 ──────────
  test("ADDX.L -(A2),-(A3) (0xD78A) -> ADDX op, LONG, predec delta 4", VerilatorTest) {
    // 0xD78A: line D, opmode 6 (.L), eaMode 001, Ay=reg=2 (A2), Ax=xxx=3 (A3).
    val us = collect(Seq(0xD78A, 0x4e71, 0x4e71, 0x4e71), 0x40800000L, 6)
    assert(us.length == 6, s"expected 6 µops: $us")
    assert(us(0).mem == "LOAD" && us(0).srcA == 10 && us(0).eaAuto == "PREDEC" && us(0).eaDelta == 4 && us(0).sizeL,
      s"µPC0 load(A2).L predec delta4: ${us(0)}")
    assert(us(2).mem == "LOAD" && us(2).srcA == 11 && us(2).eaDelta == 4, s"µPC2 load(A3): ${us(2)}")
    assert(us(4).op == "ADDX" && us(4).dst == 17 && us(4).srcA == 17 && us(4).srcB == 16 && us(4).sizeL,
      s"µPC4 ADDX.L T1,T0->T2: ${us(4)}")
    assert(us(5).mem == "STORE" && us(5).srcA == 11 && us(5).srcB == 17 && us(5).sizeL,
      s"µPC5 store.L T2->(A3): ${us(5)}")
  }

  // ── The A7-byte even rule: -(A7) .B predecrements by 2 (eaDelta == 2) ────────
  test("ABCD -(A7),-(A0) A7-byte predec delta 2 (even rule)", VerilatorTest) {
    // ABCD with Ay=A7: line C opmode 4 eaMode 001 reg=7 (A7) xxx=0 (A0) -> 0xC10F.
    val us = collect(Seq(0xC10F, 0x4e71, 0x4e71, 0x4e71), 0x40800000L, 6)
    assert(us.length == 6, s"expected 6 µops: $us")
    // µPC0 loads (A7).B -> predec delta 2 (even rule); µPC1 A7 += -2.
    assert(us(0).srcA == 15 && us(0).eaDelta == 2, s"µPC0 -(A7) byte predec delta 2: ${us(0)}")
    assert((us(1).imm & 0xffffffffL) == 0xfffffffeL, s"µPC1 A7 -= 2: ${us(1)}")
  }
}
