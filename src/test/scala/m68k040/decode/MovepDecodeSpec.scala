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

/** MOVEP micro-sequencer (the DecodeStage FSM) emission tests: drives MOVEP packets
  * through the fetch->align->decode pipe and collects the emitted µop stream at the
  * rename-facing sink, then asserts the per-byte LOAD/STORE + SHIFT/AND/OR assembly
  * µops for all 4 variants (reg<->mem, .W/.L), the temps used (T0=16 scratch, T1=17
  * accumulator), the addresses (base=Ay, disp=disp16+k), and the single kept final
  * commit (mem->reg: MOVE T1->Dx full-32; reg->mem: the last byte store keepCommit). */
class MovepDecodeSpec extends AnyFunSuite {
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
  case class U(op: String, mem: String, dst: Int, dstV: Boolean,
               srcA: Int, srcAV: Boolean, srcB: Int, srcBV: Boolean,
               useImm: Boolean, imm: Long, sizeL: Boolean,
               shiftOp: Int, shiftDir: Boolean, first: Boolean, keep: Boolean)

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
            // SpinalEnum.toString can append a `_<n>` disambiguator (e.g. "AND_1"); strip it.
            U(p.op.toEnum.toString.replaceAll("_\\d+$", ""), p.memOp.toEnum.toString, p.dstReg.toInt, p.dstValid.toBoolean,
              p.srcAReg.toInt, p.srcAValid.toBoolean, p.srcBReg.toInt, p.srcBValid.toBoolean,
              p.useImm.toBoolean, p.imm.toLong & 0xffffffffL, p.size.toEnum == Size.LONG,
              p.shiftOp.toInt, p.shiftDir.toBoolean, p.firstOfInstr.toBoolean, p.keepCommit.toBoolean)
          }
          acc += rd(0)
          if (dut.sink.logic.u1v.toBoolean) acc += rd(1)
        }
      }
      out = acc.toSeq
    }
    out
  }

  val T0 = 16; val T1 = 17

  // MOVEP opword: 0000 rrr 1 oo 001 aaa. oo (bits 7:6): 00=.W m->r, 01=.L m->r,
  // 10=.W r->m, 11=.L r->m.
  def movep(dx: Int, oo: Int, ay: Int): Int =
    0x0108 | (dx << 9) | (oo << 6) | ay

  // ── reg -> mem .W: store Dx[15:8] @ ea, Dx[7:0] @ ea+2 (big-endian alternating) ──
  test("MOVEP.W D2,(disp,A3) reg->mem -> LSR Dx>>8 ; STORE.B ; STORE.B Dx (keep)", VerilatorTest) {
    val base = 0xA000L
    val disp = 0x10
    // oo=10 (.W r->m), Dx=2, Ay=3, disp16=0x10.
    val us = collect(Seq(movep(2, 2, 3), disp, 0x4e71, 0x4e71), base, 3)
    assert(us.length == 3, s"$us")
    // u0: LSR (shiftOp=1=LSL/LSR family, dir=right) Dx(2)>>8 -> T1, LONG, drop (temp).
    assert(us(0).op == "SHIFT" && us(0).mem == "NONE" && us(0).srcA == 2 && us(0).srcAV &&
           us(0).dst == T1 && us(0).dstV && us(0).useImm && us(0).imm == 8 &&
           us(0).shiftOp == 1 && !us(0).shiftDir && us(0).sizeL && us(0).first, s"u0=$us")
    // u1: STORE.B T1 -> [A3 + disp+0]; base=A3=arch 11; no int dst.
    assert(us(1).op == "MOVE" && us(1).mem == "STORE" && us(1).srcB == T1 && us(1).srcBV &&
           us(1).srcA == 11 && us(1).srcAV && !us(1).dstV && us(1).imm == disp && !us(1).first, s"u1=$us")
    // u2: STORE.B Dx(2) -> [A3 + disp+2]; the last byte stores Dx directly; keepCommit.
    assert(us(2).op == "MOVE" && us(2).mem == "STORE" && us(2).srcB == 2 && us(2).srcBV &&
           us(2).srcA == 11 && us(2).imm == (disp + 2) && us(2).keep && !us(2).dstV, s"u2=$us")
  }

  // ── reg -> mem .L: store Dx[31:24] @ ea, [23:16] @ ea+2, [15:8] @ ea+4, [7:0] @ ea+6 ──
  test("MOVEP.L D4,(disp,A5) reg->mem -> 3 shifts+stores + final STORE.B Dx (keep)", VerilatorTest) {
    val base = 0xA100L
    val disp = 0x10
    // oo=11 (.L r->m), Dx=4, Ay=5.
    val us = collect(Seq(movep(4, 3, 5), disp, 0x4e71, 0x4e71), base, 7)
    assert(us.length == 7, s"$us")
    // LSR Dx>>24 -> T1 ; STORE.B T1 -> [A5+disp+0]
    assert(us(0).op == "SHIFT" && us(0).imm == 24 && us(0).dst == T1 && us(0).srcA == 4 && us(0).first, s"u0=$us")
    assert(us(1).mem == "STORE" && us(1).srcB == T1 && us(1).srcA == 13 && us(1).imm == disp, s"u1=$us")
    // LSR Dx>>16 -> T1 ; STORE.B T1 -> [A5+disp+2]
    assert(us(2).op == "SHIFT" && us(2).imm == 16 && us(2).dst == T1, s"u2=$us")
    assert(us(3).mem == "STORE" && us(3).srcB == T1 && us(3).imm == (disp + 2), s"u3=$us")
    // LSR Dx>>8 -> T1 ; STORE.B T1 -> [A5+disp+4]
    assert(us(4).op == "SHIFT" && us(4).imm == 8 && us(4).dst == T1, s"u4=$us")
    assert(us(5).mem == "STORE" && us(5).srcB == T1 && us(5).imm == (disp + 4), s"u5=$us")
    // STORE.B Dx -> [A5+disp+6] (keep)
    assert(us(6).mem == "STORE" && us(6).srcB == 4 && us(6).imm == (disp + 6) && us(6).keep, s"u6=$us")
  }

  // ── mem -> reg .W: Dx[15:0] := ([ea]<<8)|[ea+2]; Dx[31:16] PRESERVED via AND mask ──
  test("MOVEP.W (disp,A0),D0 mem->reg -> AND mask + 2 loads + assemble + MOVE->Dx (keep)", VerilatorTest) {
    val base = 0xA200L
    val disp = 0x10
    // oo=00 (.W m->r), Dx=0, Ay=0.
    val us = collect(Seq(movep(0, 0, 0), disp, 0x4e71, 0x4e71), base, 7)
    assert(us.length == 7, s"$us")
    // u0: AND Dx & 0xFFFF0000 -> T1 (preserve Dx[31:16]); LONG; no flags.
    assert(us(0).op == "AND" && us(0).srcA == 0 && us(0).srcAV && us(0).dst == T1 && us(0).dstV &&
           us(0).useImm && (us(0).imm == 0xFFFF0000L) && us(0).sizeL && us(0).first, s"u0=$us")
    // u1: LOAD.B [A0+disp+0] -> T0
    assert(us(1).mem == "LOAD" && us(1).dst == T0 && us(1).srcA == 8 && us(1).srcAV && us(1).imm == disp, s"u1=$us")
    // u2: LSL T0<<8 -> T0
    assert(us(2).op == "SHIFT" && us(2).shiftDir && us(2).imm == 8 && us(2).srcA == T0 && us(2).dst == T0, s"u2=$us")
    // u3: OR T1|T0 -> T1
    assert(us(3).op == "OR" && us(3).srcA == T1 && us(3).srcB == T0 && us(3).dst == T1, s"u3=$us")
    // u4: LOAD.B [A0+disp+2] -> T0
    assert(us(4).mem == "LOAD" && us(4).dst == T0 && us(4).imm == (disp + 2), s"u4=$us")
    // u5: OR T1|T0 -> T1
    assert(us(5).op == "OR" && us(5).srcA == T1 && us(5).srcB == T0 && us(5).dst == T1, s"u5=$us")
    // u6: MOVE T1 -> Dx (full .L write). srcB=T1. The final move writes a REAL reg (Dx,
    // not a temp; not divIsRem) so it is the naturally KEPT macro commit (no keepCommit needed).
    assert(us(6).op == "MOVE" && us(6).mem == "NONE" && us(6).srcB == T1 && us(6).dst == 0 && us(6).dstV &&
           us(6).sizeL, s"u6=$us")
  }

  // ── mem -> reg .L: Dx := ([ea]<<24)|([ea+2]<<16)|([ea+4]<<8)|[ea+6] ──
  test("MOVEP.L (disp,A1),D1 mem->reg -> 4 loads + shifts/ors + MOVE->Dx (keep)", VerilatorTest) {
    val base = 0xA300L
    val disp = 0x10
    // oo=01 (.L m->r), Dx=1, Ay=1.
    val us = collect(Seq(movep(1, 1, 1), disp, 0x4e71, 0x4e71), base, 11)
    assert(us.length == 11, s"$us")
    // u0: LOAD.B [A1+disp+0] -> T0 (first)
    assert(us(0).mem == "LOAD" && us(0).dst == T0 && us(0).srcA == 9 && us(0).imm == disp && us(0).first, s"u0=$us")
    // u1: LSL T0<<24 -> T1 (the accumulator seed)
    assert(us(1).op == "SHIFT" && us(1).shiftDir && us(1).imm == 24 && us(1).srcA == T0 && us(1).dst == T1, s"u1=$us")
    // u2: LOAD.B [A1+disp+2] -> T0 ; u3: LSL<<16 -> T0 ; u4: OR -> T1
    assert(us(2).mem == "LOAD" && us(2).dst == T0 && us(2).imm == (disp + 2), s"u2=$us")
    assert(us(3).op == "SHIFT" && us(3).imm == 16 && us(3).srcA == T0 && us(3).dst == T0, s"u3=$us")
    assert(us(4).op == "OR" && us(4).srcA == T1 && us(4).srcB == T0 && us(4).dst == T1, s"u4=$us")
    // u5: LOAD.B [A1+disp+4] -> T0 ; u6: LSL<<8 -> T0 ; u7: OR -> T1
    assert(us(5).mem == "LOAD" && us(5).dst == T0 && us(5).imm == (disp + 4), s"u5=$us")
    assert(us(6).op == "SHIFT" && us(6).imm == 8 && us(6).srcA == T0 && us(6).dst == T0, s"u6=$us")
    assert(us(7).op == "OR" && us(7).srcA == T1 && us(7).srcB == T0 && us(7).dst == T1, s"u7=$us")
    // u8: LOAD.B [A1+disp+6] -> T0 ; u9: OR -> T1
    assert(us(8).mem == "LOAD" && us(8).dst == T0 && us(8).imm == (disp + 6), s"u8=$us")
    assert(us(9).op == "OR" && us(9).srcA == T1 && us(9).srcB == T0 && us(9).dst == T1, s"u9=$us")
    // u10: MOVE T1 -> Dx (naturally kept — writes a real reg, not a temp).
    assert(us(10).op == "MOVE" && us(10).mem == "NONE" && us(10).srcB == T1 && us(10).dst == 1 &&
           us(10).dstV && us(10).sizeL, s"u10=$us")
  }

  // A following instruction decodes after the MOVEP FSM finishes (front-end resumes).
  test("MOVEP then a following MOVEQ -> front-end resumes cleanly", VerilatorTest) {
    val base = 0xA400L
    // MOVEP.W D2,(0x10,A3) (3 µops) then MOVEQ #7,D5 (0x7a07).
    val us = collect(Seq(movep(2, 2, 3), 0x10, 0x7a07, 0x4e71), base, 4)
    assert(us.length == 4, s"$us")
    assert(us(3).op == "MOVE" && us(3).mem == "NONE" && us(3).dst == 5 && us(3).imm == 7,
      s"expected trailing MOVEQ #7,D5 after MOVEP: $us")
  }
}
