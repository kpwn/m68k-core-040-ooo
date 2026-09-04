package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.{Cluster, MemOp, Size}
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** CMP2 / CHK2 (020+ bounds-check against a memory pair) decode + crack.
  *
  * Opword `0000 0ss0 11 mmm rrr` (ss=op[10:9] size; mode=op[5:3] CONTROL EA) + an
  * extension word `A/D(15) | Rn(14:12) | R/M(11) | 0…`. The EA points to the LOWER
  * bound @[ea]; the UPPER bound is @[ea+size]. R/M=0 CMP2, =1 CHK2.
  *
  * Crack (3 µops): [LOAD.size [EA] -> T0] [LOAD.size [EA+size] -> T1] [CMP2CHK2
  * srcA=T0 srcB=T1 srcC=Rn]. T0=16, T1=17. The compare reads+writes NZVC (the
  * {oldN,Z,oldV,C} RMW) and routes to the CPLX cluster (DivEu). Control-mode-only:
  * Dn/An/(An)+/-(An)/#imm -> illegal (vector 4). */
class Cmp2Chk2DecodeSpec extends AnyFunSuite {
  val T0 = 16; val T1 = 17

  // ── OperationDecoder level ──────────────────────────────────────────────────
  class DecDut extends Component {
    val opword = in Bits (16 bits)
    val o      = out(OpSpec())
    o := OperationDecoder.decode(opword)
  }
  def runDec(op: Int)(check: DecDut => Unit): Unit =
    SimConfig.withVerilator.compile(new DecDut).doSim { dut => dut.opword #= op; sleep(1); check(dut) }

  // ── Assembler level (full 3-µop crack from the ext word) ────────────────────
  class AsmDut extends Component {
    val pkt = in(DecodePacket())
    val a   = MicroOpAssembler.assemble(pkt)
    val count = out(UInt(2 bits)); count := a.count
    val uop0  = out(DecodedUop()); uop0 := a.uops(0)
    val uop1  = out(DecodedUop()); uop1 := a.uops(1)
    val uop2  = out(DecodedUop()); uop2 := a.uops(2)
  }
  // op = opword; ext = the extension word (words(1)); ea ext words ride words(2..).
  def drive(dut: AsmDut, op: Int, ext: Int, w2: Int = 0, len: Int = 2): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x2000; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    dut.pkt.words(0) #= op
    dut.pkt.words(1) #= ext
    dut.pkt.words(2) #= w2
    for (i <- 3 to 4) dut.pkt.words(i) #= 0
  }
  def runAsm(check: AsmDut => Unit): Unit =
    SimConfig.withVerilator.compile(new AsmDut).doSim(check)

  // Build an ext word: A/D, Rn(0-7), R/M(0=CMP2,1=CHK2).
  def ext(ad: Int, rn: Int, rm: Int): Int = (ad << 15) | (rn << 12) | (rm << 11)

  // ── CMP2.W (%a0),%d1 = opword 0x02D0, ext 0x1000 (A/D=0,Rn=1,R/M=0) ──────────
  test("OperationDecoder: CMP2.W (A0),D1 -> CMP2CHK2, CPLX, size WORD, NZVC RMW", VerilatorTest) {
    runDec(0x02D0) { dut =>
      assert(dut.o.op.toEnum == DecOp.CMP2CHK2, "op = CMP2CHK2")
      assert(!dut.o.illegal.toBoolean, "not illegal")
      assert(dut.o.cluster.toEnum == Cluster.CPLX, "routed to CPLX (DivEu)")
      assert(dut.o.size.toEnum == Size.WORD, ".W from ss=op[10:9]=01")
      assert(dut.o.readsNzvc.toBoolean && dut.o.writesNzvc.toBoolean, "reads+writes NZVC (RMW)")
    }
  }

  test("OperationDecoder: CMP2.B/.L size from ss=op[10:9]", VerilatorTest) {
    runDec(0x00D0) { dut => assert(dut.o.op.toEnum == DecOp.CMP2CHK2 && dut.o.size.toEnum == Size.BYTE, ".B") }
    runDec(0x04D0) { dut => assert(dut.o.op.toEnum == DecOp.CMP2CHK2 && dut.o.size.toEnum == Size.LONG, ".L") }
  }

  // ── The crack: 2 loads + compare ────────────────────────────────────────────
  test("assembler: CMP2.W (A0),D1 -> [load.W (A0)->T0][load.W 2(A0)->T1][cmp srcA=T0,srcB=T1,srcC=D1]", VerilatorTest) {
    runAsm { dut => drive(dut, 0x02D0, ext(ad = 0, rn = 1, rm = 0)); sleep(1)
      assert(dut.count.toInt == 3, "3-µop crack")
      // µop0: load lower @ (A0) -> T0
      assert(dut.uop0.op.toEnum == DecOp.MOVE && dut.uop0.cluster.toEnum == Cluster.LS, "u0 = LS load")
      assert(dut.uop0.memOp.toEnum == MemOp.LOAD, "u0 LOAD")
      assert(dut.uop0.size.toEnum == Size.WORD, "u0 size .W")
      assert(dut.uop0.srcAReg.toInt == 8 && dut.uop0.srcAValid.toBoolean, "u0 base = A0 (arch 8)")
      assert(dut.uop0.dstReg.toInt == T0 && dut.uop0.dstValid.toBoolean, "u0 -> T0")
      assert(dut.uop0.imm.toLong == 0, "u0 disp = 0 (lower @ [EA])")
      assert(dut.uop0.firstOfInstr.toBoolean, "u0 is first")
      // µop1: load upper @ (A0)+2 -> T1
      assert(dut.uop1.memOp.toEnum == MemOp.LOAD, "u1 LOAD")
      assert(dut.uop1.dstReg.toInt == T1 && dut.uop1.dstValid.toBoolean, "u1 -> T1")
      assert(dut.uop1.srcAReg.toInt == 8, "u1 base = A0")
      assert((dut.uop1.imm.toLong & 0xffffffffL) == 2, "u1 disp = +size(2) (upper @ [EA+2])")
      assert(!dut.uop1.firstOfInstr.toBoolean, "u1 not first")
      // µop2: compare T0,T1,Rn=D1
      assert(dut.uop2.op.toEnum == DecOp.CMP2CHK2 && dut.uop2.cluster.toEnum == Cluster.CPLX, "u2 = CMP2CHK2 CPLX")
      assert(dut.uop2.srcAReg.toInt == T0 && dut.uop2.srcAValid.toBoolean, "u2 srcA = T0 (lower)")
      assert(dut.uop2.srcBReg.toInt == T1 && dut.uop2.srcBValid.toBoolean, "u2 srcB = T1 (upper)")
      assert(dut.uop2.srcCReg.toInt == 1 && dut.uop2.srcCValid.toBoolean, "u2 srcC = D1 (Rn)")
      assert(!dut.uop2.dstValid.toBoolean, "u2 writes no int reg")
      assert(dut.uop2.readsNzvc.toBoolean && dut.uop2.writesNzvc.toBoolean, "u2 NZVC RMW")
      assert(!dut.uop2.isChk2.toBoolean, "u2 isChk2 = False (CMP2)")
      assert(!dut.uop2.divSigned.toBoolean, "u2 adReg = False (D1 is a data reg)")
      assert(dut.uop2.faultUsesNextPc.toBoolean, "u2 stacks nextPc (group-2 vec6)")
      assert(!dut.uop2.unimplemented.toBoolean, "not illegal")
    }
  }

  // ── CHK2.L (%a0),%a2 = opword 0x04D0, ext 0xA800 (A/D=1,Rn=2,R/M=1) ──────────
  test("assembler: CHK2.L (A0),A2 -> isChk2, adReg, size +4 disp, srcC=A2", VerilatorTest) {
    runAsm { dut => drive(dut, 0x04D0, ext(ad = 1, rn = 2, rm = 1)); sleep(1)
      assert(dut.count.toInt == 3, "3-µop crack")
      assert(dut.uop0.size.toEnum == Size.LONG, "u0 size .L")
      assert((dut.uop1.imm.toLong & 0xffffffffL) == 4, "u1 disp = +size(4)")
      assert(dut.uop2.srcCReg.toInt == 10 && dut.uop2.srcCValid.toBoolean, "u2 srcC = A2 (arch 8+2=10)")
      assert(dut.uop2.isChk2.toBoolean, "u2 isChk2 = True (CHK2, R/M=1)")
      assert(dut.uop2.divSigned.toBoolean, "u2 adReg = True (A2 is an address reg)")
      assert(dut.uop2.size.toEnum == Size.LONG, "u2 size .L")
    }
  }

  // ── (d16,An) bounds pointer: disp folds into both loads (+size on the 2nd) ───
  test("assembler: CMP2.W (8,A0),D3 -> loads at disp 8 and 8+2", VerilatorTest) {
    // 0x02E8 = 0000 0010 1110 1000 : ss=01(.W), bits7:6=11, mode=5 (d16,An), reg=0(A0).
    runAsm { dut => drive(dut, 0x02E8, ext(ad = 0, rn = 3, rm = 0), w2 = 8, len = 3); sleep(1)
      assert(dut.count.toInt == 3)
      assert((dut.uop0.imm.toLong & 0xffffffffL) == 8, "u0 disp = d16 = 8")
      assert((dut.uop1.imm.toLong & 0xffffffffL) == 10, "u1 disp = 8 + size(2) = 10")
      assert(dut.uop2.srcCReg.toInt == 3, "u2 srcC = D3")
    }
  }

  // ── Control-mode-only: Dn / An / (An)+ / -(An) / #imm -> illegal (vector 4) ──
  test("assembler: CMP2 with Dn EA (mode 0) -> illegal", VerilatorTest) {
    // 0x02C0 = mode 0 (Dn) — NOT a control mode. (Predecode wouldn't frame it; the
    // assembler also rejects it: opUop forced illegal.)
    runAsm { dut => drive(dut, 0x02C0, ext(0, 1, 0)); sleep(1)
      assert(dut.uop0.unimplemented.toBoolean, "Dn EA -> illegal")
      assert(dut.uop0.faulted.toBoolean && dut.uop0.faultVector.toInt == 4, "vector 4")
    }
  }
  test("assembler: CMP2 with An EA (mode 1) -> illegal", VerilatorTest) {
    runAsm { dut => drive(dut, 0x02C8, ext(0, 1, 0)); sleep(1)
      assert(dut.uop0.unimplemented.toBoolean && dut.uop0.faultVector.toInt == 4, "An EA -> illegal vec4")
    }
  }
  test("assembler: CMP2 with (An)+ EA (mode 3) -> illegal (not control)", VerilatorTest) {
    runAsm { dut => drive(dut, 0x02D8, ext(0, 1, 0)); sleep(1)
      assert(dut.uop0.unimplemented.toBoolean && dut.uop0.faultVector.toInt == 4, "(An)+ -> illegal vec4")
    }
  }
  test("assembler: CMP2 with -(An) EA (mode 4) -> illegal (not control)", VerilatorTest) {
    runAsm { dut => drive(dut, 0x02E0, ext(0, 1, 0)); sleep(1)
      assert(dut.uop0.unimplemented.toBoolean && dut.uop0.faultVector.toInt == 4, "-(An) -> illegal vec4")
    }
  }
  test("assembler: CMP2 with #imm EA (mode 7 reg 4) -> illegal", VerilatorTest) {
    // 0x02FC = mode 7, reg 4 (#imm) — not a control mode.
    runAsm { dut => drive(dut, 0x02FC, ext(0, 1, 0)); sleep(1)
      assert(dut.uop0.unimplemented.toBoolean && dut.uop0.faultVector.toInt == 4, "#imm -> illegal vec4")
    }
  }

  // ── Fail-safe: architecturally LEGAL control EAs the crack does not implement ──
  // `b9d0781` forces mode 6 ((d8,An,Xn), brief AND full-format) and mode 7/reg 3
  // ((d8,PC,Xn)) illegal via `MicroOpAssembler.c2IndexedShape`. Both are §4.39-legal
  // CMP2/CHK2 control EAs; `EaDecoder` classifies them MEMSIMPLE (they are supported
  // for the GENERIC ALU/MOVE crackLoad AGU), so without this gate the crack would
  // admit them and compute a WRONG address for the full-format shapes. A clean vec-4
  // is the fail-safe. These two cases pin that contract at decode level — the ported
  // `chk2_cmp2_illegal_ea_traps.s` (`_c6`, `_c8`) pins the same thing end-to-end, and
  // the contradicting `ExecuteLockStepSpec` "CMP2.W (d8,An,Xn) indexed bounds pointer"
  // case (written against the PRE-b9d0781 contract, failing ever since) was removed
  // in favour of these. DELETE these two when task #257 implements the EA compute.
  test("assembler: CMP2 with (d8,An,Xn) EA (mode 6) -> illegal (fail-safe, not implemented)", VerilatorTest) {
    // 0x02F0 = ss=01 (.W), mode 6, reg 0 -> (d8,A0,Xn). w2 = brief-format ext word.
    runAsm { dut => drive(dut, 0x02F0, ext(0, 1, 0), w2 = 0x2000, len = 3); sleep(1)
      assert(dut.uop0.unimplemented.toBoolean && dut.uop0.faulted.toBoolean &&
             dut.uop0.faultVector.toInt == 4, "(d8,An,Xn) -> fail-safe illegal vec4")
    }
  }
  test("assembler: CMP2 with (d8,PC,Xn) EA (mode 7 reg 3) -> illegal (fail-safe, not implemented)", VerilatorTest) {
    // 0x02FB = ss=01 (.W), mode 7, reg 3 -> (d8,PC,Xn). w2 = brief-format ext word.
    runAsm { dut => drive(dut, 0x02FB, ext(0, 1, 0), w2 = 0x2000, len = 3); sleep(1)
      assert(dut.uop0.unimplemented.toBoolean && dut.uop0.faulted.toBoolean &&
             dut.uop0.faultVector.toInt == 4, "(d8,PC,Xn) -> fail-safe illegal vec4")
    }
  }
  // The NON-indexed control modes next to them must stay LEGAL — otherwise the gate
  // above would be silently over-broad and these tests would pass vacuously.
  test("assembler: CMP2 with (d16,An) EA (mode 5) stays legal (gate is not over-broad)", VerilatorTest) {
    runAsm { dut => drive(dut, 0x02E8, ext(0, 1, 0), w2 = 8, len = 3); sleep(1)
      assert(!dut.uop0.unimplemented.toBoolean && !dut.uop0.faulted.toBoolean,
             "(d16,An) must remain a legal CMP2 bounds EA")
    }
  }

  // ── PC-relative EA: base is pc+4, NOT pc+2 ─────────────────────────────────
  // CMP2/CHK2 is a 2-ext-word instruction: [opword@pc][cmp2_ext@pc+2][ea_ext@pc+4].
  // For a (d16,PC) EA (mode 7 reg 2), the m68k spec says PC-base = address of the EA
  // extension word = pc+4.  The crack must therefore use pc+4+disp (not pc+2+disp).
  //
  // Encoding: opword 0x02FA = 0000 0010 1111 1010 (ss=01 .W, mode 7 = bits[5:3]=111,
  // reg 2 = bits[2:0]=010 → (d16,PC)).  ext word = cmp2 ext (A/D=0,Rn=1,R/M=0).
  // ea_ext (words(2)) = d16 = 4.  pkt.pc = 0x2000.
  // Expected lower-bound address = 0x2000 + 4 + 4 = 0x2008.
  // Expected upper-bound address = 0x2008 + 2 (size=.W) = 0x200A.
  test("assembler: CMP2.W (d16,PC) lower=pc+4+disp, upper=pc+4+disp+size (not pc+2)", VerilatorTest) {
    // 0x02FA = mode 7, reg 2 = (d16,PC)
    runAsm { dut => drive(dut, 0x02FA, ext(ad = 0, rn = 1, rm = 0), w2 = 4, len = 3); sleep(1)
      assert(dut.count.toInt == 3, "3-µop crack")
      // µop0: lower bound load — imm must be the folded absolute address pc+4+disp.
      // srcAValid = false because PC-rel folds the base into the immediate.
      assert(!dut.uop0.srcAValid.toBoolean, "PC-rel: no base register (folded into imm)")
      // pkt.pc = 0x2000, ea_ext at pc+4, d16 = 4 -> target = 0x2008
      val lowerAddr = dut.uop0.imm.toLong & 0xffffffffL
      assert(lowerAddr == 0x2008L,
        s"lower-bound: expected pc+4+disp=0x2008, got 0x${lowerAddr.toHexString} " +
        s"(bug would give 0x${(0x2002L + 4L).toHexString} for pc+2+disp)")
      // µop1: upper bound load = lower + size(2) = 0x200A
      val upperAddr = dut.uop1.imm.toLong & 0xffffffffL
      assert(upperAddr == 0x200AL,
        s"upper-bound: expected pc+4+disp+size=0x200A, got 0x${upperAddr.toHexString}")
      // sanity: the rest of the crack is well-formed
      assert(dut.uop0.dstReg.toInt == T0 && dut.uop1.dstReg.toInt == T1, "T0 / T1")
      assert(dut.uop2.op.toEnum == DecOp.CMP2CHK2, "u2 = compare")
      assert(dut.uop2.srcCReg.toInt == 1, "Rn = D1")
    }
  }
}
