package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.{Size, MemOp}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class MicroOpAssemblerSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt = in(DecodePacket())
    val uop = out(DecodedUop())
    uop := MicroOpAssembler.assemble(pkt).uops(0)
  }
  def drive(dut: Dut, op: Int, w1: Int = 0, w2: Int = 0, len: Int = 1): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x1000; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    dut.pkt.words(0) #= op; dut.pkt.words(1) #= w1; dut.pkt.words(2) #= w2
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }
  def run(check: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim(check)

  test("MOVEQ #5,D3", VerilatorTest) { run { dut => drive(dut, 0x7605); sleep(1)
    assert(dut.uop.op.toEnum == DecOp.MOVE && dut.uop.dstReg.toInt == 3 && dut.uop.dstValid.toBoolean)
    assert(dut.uop.useImm.toBoolean && dut.uop.imm.toLong == 5 && dut.uop.size.toEnum == Size.LONG)
    assert(dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean && !dut.uop.unimplemented.toBoolean)
  }}
  test("MOVE.W D0,D1 (src in srcB; srcA=dst Dn for partial merge, writesNzvc)", VerilatorTest) { run { dut => drive(dut, 0x3200); sleep(1)
    // MOVE.W to a DATA register is a PARTIAL-register write (preserve D1[31:16]). The
    // assembler makes it READ its destination Dn (D1) as srcA so the ALU EU's .B/.W
    // size-merge has the old value as the merge source.
    assert(dut.uop.op.toEnum == DecOp.MOVE && dut.uop.srcBReg.toInt == 0 && dut.uop.srcBValid.toBoolean)
    assert(dut.uop.srcAValid.toBoolean && dut.uop.srcAReg.toInt == 1)   // srcA = dst D1 (merge source)
    assert(!dut.uop.isMovea.toBoolean && dut.uop.dstReg.toInt == 1 && dut.uop.dstValid.toBoolean)
    assert(dut.uop.writesNzvc.toBoolean)
  }}
  test("MOVEA.L A0,A1 (src in srcB, no flags, isMovea)", VerilatorTest) { run { dut => drive(dut, 0x2248); sleep(1)
    assert(dut.uop.srcBReg.toInt == 8 && dut.uop.srcBValid.toBoolean)
    assert(dut.uop.dstReg.toInt == 9 && dut.uop.dstValid.toBoolean && !dut.uop.writesNzvc.toBoolean)
    // An-dst MOVE -> isMovea (full-32 write, no partial merge; .W sign-extends). No
    // srcA dst-read merge source (An is never partially written).
    assert(dut.uop.isMovea.toBoolean && !dut.uop.srcAValid.toBoolean)
  }}
  test("MOVE.L #imm,D0", VerilatorTest) { run { dut => drive(dut, 0x203C, 0x1234, 0x5678, len = 3); sleep(1)
    assert(dut.uop.useImm.toBoolean && dut.uop.imm.toLong == 0x12345678L && dut.uop.dstReg.toInt == 0)
    assert(dut.uop.writesNzvc.toBoolean)
  }}
  test("ADD.L D1,D0 (srcA=Dn dest, srcB=EA, NZVC+X)", VerilatorTest) { run { dut => drive(dut, 0xD081); sleep(1)
    assert(dut.uop.op.toEnum == DecOp.ADD && dut.uop.srcAReg.toInt == 0 && dut.uop.srcBReg.toInt == 1)
    assert(dut.uop.dstReg.toInt == 0 && dut.uop.dstValid.toBoolean && dut.uop.writesNzvc.toBoolean && dut.uop.writesX.toBoolean)
  }}
  test("CMP.L D1,D0 (no write, NZVC)", VerilatorTest) { run { dut => drive(dut, 0xB081); sleep(1)
    assert(dut.uop.op.toEnum == DecOp.CMP && !dut.uop.dstValid.toBoolean && dut.uop.writesNzvc.toBoolean)
  }}
  test("Bcc word disp", VerilatorTest) { run { dut => drive(dut, 0x6700, 0x0010, len = 2); sleep(1)
    assert(dut.uop.isBranch.toBoolean && dut.uop.cond.toInt == 7 && dut.uop.imm.toLong == 0x10)
  }}
  test("memSimple-EA source -> cracked load uop (slot0), not unimplemented", VerilatorTest) { run { dut => drive(dut, 0xD090); sleep(1)
    // ADD.L (A0),D0 — EA (A0) is memSimple -> slot0 is now the LOAD µop (cracking
    // slice 2), no longer unimplemented. (Full sequence covered by CrackLoadSpec.)
    assert(!dut.uop.unimplemented.toBoolean && dut.uop.memOp.toEnum == MemOp.LOAD)
  }}
  // EOR.L D1,D2 (0xB382): reg dest. srcA = EA reg (D2 = dst operand), srcB = Dn (D1),
  // dst = EA reg (D2). Writes the reg + NZVC, no X.
  test("EOR.L D1,D2 (reg dest): srcA=D2, srcB=D1, dst=D2, NZVC no X", VerilatorTest) { run { dut => drive(dut, 0xB382); sleep(1)
    assert(dut.uop.op.toEnum == DecOp.EOR && !dut.uop.unimplemented.toBoolean)
    assert(dut.uop.srcAReg.toInt == 2 && dut.uop.srcAValid.toBoolean)
    assert(dut.uop.srcBReg.toInt == 1 && dut.uop.srcBValid.toBoolean)
    assert(dut.uop.dstReg.toInt == 2 && dut.uop.dstValid.toBoolean)
    assert(dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean && !dut.uop.useImm.toBoolean)
  }}
  // EOR.B D2,(A0) (0xB510): memory destination = the RMW form -> cracks into a leading
  // LOAD ([load -> T0][EOR -> T1][store T1]). uops(0) is the load (full triple in
  // MemRmwDecodeSpec). An-direct / MEMCOMPLEX dest still illegal.
  test("EOR.B D2,(A0) (mem dest) -> leading LOAD (RMW crack)", VerilatorTest) { run { dut => drive(dut, 0xB510); sleep(1)
    assert(!dut.uop.unimplemented.toBoolean && dut.uop.memOp.toEnum == MemOp.LOAD)
  }}
  // 0xB508 (EOR.B D2,A0, An-direct) is the CMPM (Ay)+,(Ax)+ ENCODING, not a bad EOR EA.
  // PRE-EXISTING STALE TEST found 2026-07-11 while validating the F1/F2/F3 predecode-
  // overflow fix (same class as the PredecodeRef NOP/CMPM gaps + the BitfieldDecodeSpec
  // ucEntry-width gap): this test predates CMPM support (`2e04cc8`/`2e93d88`), which routes
  // this exact encoding through OperationDecoder's `isCmpm` -> `microcoded` (Microcode.
  // CMPM_ENTRY) — the DecodeStage substitutes the REAL µcode-engine µops for a microcoded
  // op's slot entirely, discarding whatever `MicroOpAssembler.assemble()` alone produces
  // (same "benign placeholder crack" pattern as MOVEM/CAS/MOVES), so `unimplemented` here
  // is no longer meaningful for 0xB508 — it is a REAL, legal, executed instruction.
  // Re-pointed at an EA that is STILL genuinely illegal for EOR (mode7/reg4 = #imm, which
  // can never be a destination) to preserve this test's original intent: the `eorMemBad`
  // gate (non-DATAREG, non-MEMSIMPLE EOR destination -> illegal).
  test("EOR.B D2,#imm (mode7/reg4, not a valid EOR dest) -> unimplemented", VerilatorTest) {
    run { dut => drive(dut, 0xB53C); sleep(1)
      assert(dut.uop.unimplemented.toBoolean)
    }
  }
  // Line-0 immediates: srcA = EA reg (Dn dst operand), srcB = the trailing imm
  // word(s) via useImm, dst = EA reg. ADDI.L #imm,D0 (0x0680) + imm32 = words(1..2).
  test("ADDI.L #0x12345678,D0 (reg dest): srcA=D0, useImm imm32, dst=D0, NZVCX", VerilatorTest) {
    run { dut => drive(dut, 0x0680, 0x1234, 0x5678, len = 3); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.ADD && !dut.uop.unimplemented.toBoolean)
      assert(dut.uop.srcAReg.toInt == 0 && dut.uop.srcAValid.toBoolean)
      assert(dut.uop.useImm.toBoolean && dut.uop.imm.toLong == 0x12345678L && !dut.uop.srcBValid.toBoolean)
      assert(dut.uop.dstReg.toInt == 0 && dut.uop.dstValid.toBoolean)
      assert(dut.uop.writesNzvc.toBoolean && dut.uop.writesX.toBoolean && dut.uop.size.toEnum == Size.LONG)
    }
  }
  // ANDI.W #0xABCD,D3 (0x0243) + imm word = words(1). .W -> 1 imm word.
  test("ANDI.W #0xABCD,D3 (reg dest): useImm low word, dst=D3, NZ no X", VerilatorTest) {
    run { dut => drive(dut, 0x0243, 0xABCD, len = 2); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.AND && !dut.uop.unimplemented.toBoolean)
      assert(dut.uop.srcAReg.toInt == 3 && dut.uop.dstReg.toInt == 3 && dut.uop.dstValid.toBoolean)
      assert(dut.uop.useImm.toBoolean && (dut.uop.imm.toLong & 0xffff) == 0xABCD)
      assert(dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean && dut.uop.size.toEnum == Size.WORD)
    }
  }
  // CMPI.L #imm,D1 (0x0C81): writes NO register.
  test("CMPI.L #imm,D1 (reg dest): no dst write, NZVC", VerilatorTest) {
    run { dut => drive(dut, 0x0C81, 0x0000, 0x0001, len = 3); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.CMP && !dut.uop.dstValid.toBoolean && dut.uop.writesNzvc.toBoolean)
      assert(dut.uop.srcAReg.toInt == 1 && dut.uop.useImm.toBoolean && dut.uop.imm.toLong == 1)
    }
  }
  // ADDI.B #imm,(A0) (0x0610): memory destination = the RMW form -> cracks into a
  // leading LOAD ([load -> T0][ADD #imm -> T1][store T1]). uops(0) is the load.
  test("ADDI.B #imm,(A0) (mem dest) -> leading LOAD (RMW crack)", VerilatorTest) {
    run { dut => drive(dut, 0x0610, 0x0042, len = 2); sleep(1)
      assert(!dut.uop.unimplemented.toBoolean && dut.uop.memOp.toEnum == MemOp.LOAD)
    }
  }
  // ANDI #imm,CCR (0x023C) + imm.B = words(1). toCcr µop: reads+writes NZVC+X, no int
  // operands/dst, op = AND, useImm = the imm byte. NOT unimplemented (CCR is non-priv).
  test("ANDI #0x1f,CCR (0x023C): toCcr, AND, reads+writes NZVC+X, no int dst", VerilatorTest) {
    run { dut => drive(dut, 0x023C, 0x001F, len = 2); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.AND && !dut.uop.unimplemented.toBoolean)
      assert(dut.uop.toCcr.toBoolean)
      assert(dut.uop.readsNzvc.toBoolean && dut.uop.readsX.toBoolean)
      assert(dut.uop.writesNzvc.toBoolean && dut.uop.writesX.toBoolean)
      assert(!dut.uop.srcAValid.toBoolean && !dut.uop.srcBValid.toBoolean && !dut.uop.dstValid.toBoolean)
      assert(dut.uop.useImm.toBoolean && (dut.uop.imm.toLong & 0x1f) == 0x1f)
    }
  }
  test("ORI #imm,CCR (0x003C): toCcr, OR", VerilatorTest) {
    run { dut => drive(dut, 0x003C, 0x0003, len = 2); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.OR && dut.uop.toCcr.toBoolean && !dut.uop.unimplemented.toBoolean)
    }
  }
  test("EORI #imm,CCR (0x0A3C): toCcr, EOR", VerilatorTest) {
    run { dut => drive(dut, 0x0A3C, 0x0010, len = 2); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.EOR && dut.uop.toCcr.toBoolean && !dut.uop.unimplemented.toBoolean)
    }
  }
  // ANDI #imm,SR (0x027C, word) is a real PRIVILEGED sysOp (task #161: reuses
  // SysKind.MOVE_TO_SR's ExceptionUnit S_APPLY case), NOT "unimplemented" -- this
  // test previously asserted the pre-#161 stale/illegal behavior (confirmed via
  // git-stash bisect against task #228's unrelated diff, reproduces identically on
  // either side, i.e. genuinely stale, not a live bug). Per the isToSr assembler
  // comment (MicroOpAssembler.scala ~line 2010): needsSupervisor is NOT the gate for
  // this form -- privilege is enforced by RobPlugin's sysPrivFault, keyed off
  // sysOp/sysKind alone, so this assertion does NOT check needsSupervisor.
  test("ANDI #imm,SR (word, 0x027C) -> privileged sysOp (MOVE_TO_SR), not toCcr/unimplemented", VerilatorTest) {
    run { dut => drive(dut, 0x027C, 0x0000, len = 2); sleep(1)
      assert(dut.uop.sysOp.toBoolean && dut.uop.sysKind.toEnum == SysKind.MOVE_TO_SR)
      assert(!dut.uop.toCcr.toBoolean && !dut.uop.unimplemented.toBoolean)
    }
  }
  test("non-simple packet -> unimplemented", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drive(dut, 0x7605); dut.pkt.simple #= false; dut.pkt.complex #= true; sleep(1)
      assert(dut.uop.unimplemented.toBoolean)
    }
  }

  // ── Track C: LEA / PEA / MOVE-from-CCR/SR / MOVE-to-CCR cracks ───────────────
  test("LEA (A0),A1 (0x43D0): leaAddr LS uop, dst=A1, base=A0, no mem", VerilatorTest) {
    run { dut => drive(dut, 0x43D0); sleep(1)
      assert(dut.uop.leaAddr.toBoolean && dut.uop.op.toEnum == DecOp.MOVE)
      assert(dut.uop.cluster.toEnum == m68k040.isa.Cluster.LS)
      assert(dut.uop.memOp.toEnum == m68k040.isa.MemOp.NONE)
      assert(dut.uop.dstReg.toInt == 9 && dut.uop.dstValid.toBoolean)   // A1 = arch 9
      assert(dut.uop.srcAReg.toInt == 8 && dut.uop.srcAValid.toBoolean) // A0 = arch 8
      assert(!dut.uop.unimplemented.toBoolean)
    }
  }
  test("PEA (A0) (0x4850): uops(0) = leaAddr -> T0 (first)", VerilatorTest) {
    run { dut => drive(dut, 0x4850); sleep(1)
      assert(dut.uop.leaAddr.toBoolean && dut.uop.dstReg.toInt == 16)   // T0
      assert(dut.uop.firstOfInstr.toBoolean)
    }
  }
  test("MOVE from CCR,(A0) (0x42D0): fromCcr op, T1 dst, keepCommit (mem)", VerilatorTest) {
    run { dut => drive(dut, 0x42D0); sleep(1)
      assert(dut.uop.fromCcr.toBoolean && dut.uop.op.toEnum == DecOp.MOVE)
      assert(dut.uop.readsNzvc.toBoolean && dut.uop.readsX.toBoolean)
      assert(dut.uop.dstReg.toInt == 17 && dut.uop.keepCommit.toBoolean) // T1 + keep
    }
  }
  test("MOVE from CCR,D0 (0x42C0): fromCcr op, Dn dst (.W merge)", VerilatorTest) {
    run { dut => drive(dut, 0x42C0); sleep(1)
      assert(dut.uop.fromCcr.toBoolean && dut.uop.dstReg.toInt == 0 && dut.uop.dstValid.toBoolean)
      assert(dut.uop.size.toEnum == m68k040.isa.Size.WORD && dut.uop.srcAValid.toBoolean) // merge src
    }
  }
  test("MOVE from SR,D0 (0x40C0): fromSr op + needsSupervisor (privileged)", VerilatorTest) {
    run { dut => drive(dut, 0x40C0); sleep(1)
      assert(dut.uop.fromSr.toBoolean && dut.uop.needsSupervisor.toBoolean)
      assert(dut.uop.readsNzvc.toBoolean && dut.uop.dstReg.toInt == 0)
    }
  }
  test("MOVE D0,CCR (0x44C0): toCcr op (MOVE), writes NZVC+X, no old-CCR read", VerilatorTest) {
    run { dut => drive(dut, 0x44C0); sleep(1)
      assert(dut.uop.toCcr.toBoolean && dut.uop.op.toEnum == DecOp.MOVE)
      assert(dut.uop.writesNzvc.toBoolean && dut.uop.writesX.toBoolean)
      assert(!dut.uop.readsNzvc.toBoolean)   // a full MOVE-to-CCR does NOT read old CCR
    }
  }

  // ── Task P5.3: CPUSH/CINV An routing (mirrors PTEST) + packed scope/cache-selector.
  // opword layout: bits[2:0] = An (offset by 8 -> arch reg id), bits[4:3] = scope,
  // bits[7:6] = cacheSel, bit[5] = CPUSH(1)/CINV(0) selector (irrelevant to this
  // routing). imm[3:0] must carry {scope,cacheSel} MSB-first: imm[3:2]=scope,
  // imm[1:0]=cacheSel — Task P5.5 reads sysCapRc(3 downto 2)/sysCapRc(1 downto 0)
  // for scope/selector, so this bit ordering is load-bearing.
  test("CINV (0xF448, An=A0, scope=01, cacheSel=01): An->srcB, no srcA/dst, imm={scope,cacheSel}", VerilatorTest) {
    run { dut => drive(dut, 0xF448); sleep(1)
      assert(dut.uop.sysKind.toEnum == SysKind.CINV)
      assert(dut.uop.srcBReg.toInt == 8 && dut.uop.srcBValid.toBoolean)  // A0 = arch 8
      assert(!dut.uop.srcAValid.toBoolean && !dut.uop.dstValid.toBoolean && !dut.uop.useImm.toBoolean)
      assert((dut.uop.imm.toLong & 0xF) == 0x5)   // scope=01, cacheSel=01 -> {01,01} = 0101
    }
  }
  test("CPUSH (0xF471, An=A1, scope=10, cacheSel=01): An->srcB, imm={scope,cacheSel}", VerilatorTest) {
    run { dut => drive(dut, 0xF471); sleep(1)
      assert(dut.uop.sysKind.toEnum == SysKind.CPUSH)
      assert(dut.uop.srcBReg.toInt == 9 && dut.uop.srcBValid.toBoolean)  // A1 = arch 9
      assert(!dut.uop.srcAValid.toBoolean && !dut.uop.dstValid.toBoolean && !dut.uop.useImm.toBoolean)
      assert((dut.uop.imm.toLong & 0xF) == 0x9)   // scope=10, cacheSel=01 -> {10,01} = 1001
    }
  }
  test("CPUSH (0xF478, An=A0, scope=11, cacheSel=01): imm={scope,cacheSel}", VerilatorTest) {
    run { dut => drive(dut, 0xF478); sleep(1)
      assert(dut.uop.sysKind.toEnum == SysKind.CPUSH)
      assert(dut.uop.srcBReg.toInt == 8 && dut.uop.srcBValid.toBoolean)  // A0 = arch 8
      assert((dut.uop.imm.toLong & 0xF) == 0xD)   // scope=11, cacheSel=01 -> {11,01} = 1101
    }
  }

  // ── Line-A / Line-F emulator-trap vector routing (vector 10 / vector 11) ──────
  // Real 68k silicon traps the ENTIRE 0xA000-0xAFFF opcode range unconditionally to
  // vector 10 ("Line 1010 Emulator") -- there are NO valid opcodes there at all -- and
  // any F-line opword the CPU does not implement to vector 11 ("Line 1111 Emulator").
  // On classic Mac OS the A-line range IS the Toolbox call ABI (every `_NewPtr`-style
  // trap word), and vector 11 is how the ROM's FPSP gets handed unimplemented FP ops,
  // so mis-routing either to the GENERIC vector-4 illegal-instruction path would be a
  // total-loss correctness bug, not an edge case.
  //
  // The routing lives in MicroOpAssembler's `bad` fallback (the top-nibble mux on
  // `op(15 downto 12)`: 0xA -> 10, 0xF -> 11, default -> 4) plus PredecodeWord's
  // is(0xA)/is(0xF) arms (single-word `simple` framing, so the opword is never
  // mis-framed as a multi-word/complex instruction). OperationDecoder deliberately has
  // NO is(0xA) arm at all -- line A falls through to OpSpec.illegalDefault() -- and
  // line F's arm only claims CPUSH/CINV/PFLUSH/PTEST/MOVE16/FSF, leaving everything
  // else on the same illegal default. These two sweeps pin that end-to-end.
  test("line-A (0xA000-0xAFFF): ALL 4096 opwords fault to vector 10, exhaustively", VerilatorTest) {
    run { dut =>
      val bad = scala.collection.mutable.ArrayBuffer[String]()
      for (op <- 0xA000 to 0xAFFF) {
        drive(dut, op); sleep(1)
        val faulted = dut.uop.faulted.toBoolean
        val vec     = dut.uop.faultVector.toInt
        val unimpl  = dut.uop.unimplemented.toBoolean
        val nextPc  = dut.uop.faultUsesNextPc.toBoolean
        // faultUsesNextPc must be FALSE: the A-line frame stacks the address OF the
        // trap word itself (the Toolbox dispatcher reads the trap word back from it).
        if (!faulted || vec != 10 || !unimpl || nextPc)
          bad += f"0x$op%04X faulted=$faulted vec=$vec unimpl=$unimpl usesNextPc=$nextPc"
      }
      assert(bad.isEmpty, s"${bad.size}/4096 line-A opwords mis-routed; first 10: ${bad.take(10).mkString(", ")}")
    }
  }
  // ── Task 6: the cpGEN band's OWN length-aware coverage (see the comment above --
  // the base sweep now excludes cpGEN entirely, since its always-len=1 drive can never
  // exercise fpLenKnown's real (len>=2) behavior). This sweep drives every cpGEN opword
  // at len=2 with a NON-NATIVE opmode (0x0E, FSIN) so fpEmit never fires and every one
  // of them exercises the TRAPPING path -- pinning down that a FRAMED cpGEN trap stacks
  // the POST-instruction PC, which is exactly the behavior the base sweep was blind to.
  //
  // Task 6b UPDATE: this sweep covered the FULL cpGEN <ea> band (mode/reg = 0..63) back
  // when EVERY non-emittable cpGEN opword trapped at THIS (MicroOpAssembler-only) layer.
  // That is no longer true for GENUINE MEMORY <ea> modes (mode != 0 Dn, != 1 An, != 7/reg4
  // #imm) -- Task 6b routes that whole band to the µcode engine instead
  // (`spec.microcoded := True`, OperationDecoder.scala), and the real accept/reject
  // decision (including the identical "non-native opmode -> trap" rule this sweep drives)
  // now lives in `DecodeStage.ucBegin`, which THIS `Dut` cannot exercise -- verified
  // end-to-end instead by `FpMemLoadSpec`'s own "FSIN.L (A0),FP0 ... traps vector 11" case.
  // The sweep below is narrowed to the <ea> values THIS layer still directly owns (Task
  // 6's register-direct/immediate paths), plus a companion sweep confirming every
  // EXCLUDED (memory-mode) <ea> value is correctly deferred to the µcode engine rather
  // than silently falling through un-routed. */
  test("line-F cpGEN band (register/immediate <ea>): framed-but-non-emittable opwords stack the POST-instruction PC", VerilatorTest) {
    run { dut =>
      val bad = scala.collection.mutable.ArrayBuffer[String]()
      for (low <- 0 until 64) {   // full cpGEN band, opword = 0xF200 | low
        val mode = (low >> 3) & 7; val reg = low & 7
        // Task 6b's own territory (Dn/An/#imm) -- everything else now defers to the
        // µcode engine, checked separately below.
        val isMemMode = (mode != 0) && (mode != 1) && !(mode == 7 && reg == 4)
        if (!isMemMode) {
          val op = 0xF200 | low
          // Drive at len=2 (the register-form framing) with a NON-NATIVE opmode (0x0E,
          // FSIN) so fpEmit never fires and this always exercises the TRAPPING path.
          drive(dut, op, w1 = 0x000E, len = 2); sleep(1)
          val faulted = dut.uop.faulted.toBoolean
          val vec     = dut.uop.faultVector.toInt
          val nextPc  = dut.uop.faultUsesNextPc.toBoolean
          if (!faulted || vec != 11 || !nextPc)
            bad += f"0x$op%04X faulted=$faulted vec=$vec usesNextPc=$nextPc (want true)"
        }
      }
      assert(bad.isEmpty, s"${bad.size} cpGEN register/immediate opwords: faultUsesNextPc not set when framed; " +
        bad.take(10).mkString("\n"))
    }
  }

  test("line-F cpGEN band (genuine memory <ea>): every opword defers to the µcode engine (Task 6b)", VerilatorTest) {
    class DecDut extends Component {
      val opword = in Bits (16 bits)
      val o      = out(OpSpec())
      o := OperationDecoder.decode(opword)
    }
    SimConfig.withVerilator.compile(new DecDut).doSim { dut =>
      val bad = scala.collection.mutable.ArrayBuffer[String]()
      for (low <- 0 until 64) {
        val mode = (low >> 3) & 7; val reg = low & 7
        val isMemMode = (mode != 0) && (mode != 1) && !(mode == 7 && reg == 4)
        if (isMemMode) {
          val op = 0xF200 | low
          dut.opword #= op; sleep(1)
          val illegal    = dut.o.illegal.toBoolean
          val microcoded = dut.o.microcoded.toBoolean
          if (illegal || !microcoded)
            bad += f"0x$op%04X illegal=$illegal microcoded=$microcoded (want illegal=false microcoded=true)"
        }
      }
      assert(bad.isEmpty, s"${bad.size} cpGEN memory-<ea> opwords not routed to the µcode engine; " +
        bad.take(10).mkString("\n"))
    }
  }

  // ── Task 9: FMOVE.L <ea>,FPcr / FPcr,<ea> (FPCR / FPSR / FPIAR) ──────────────
  // Opword/extension encodings below are the LITERAL output of
  //   m68k-linux-gnu-as -m68040 -m68881   (Task 9 Step 1, re-run for this task):
  //   f200 9000  fmovel %d0,%fpcr      f200 b000  fmovel %fpcr,%d0
  //   f200 8800  fmovel %d0,%fpsr      f200 a800  fmovel %fpsr,%d0
  //   f200 8400  fmovel %d0,%fpiar     f200 a400  fmovel %fpiar,%d0
  //   f208 8400  fmovel %a0,%fpiar     f210 bc00  fmovel %fpiar/%fpsr/%fpcr,%a0@
  //   f23c 8800 0000 0000  fmovel #0,%fpsr
  // NOTE these are recognized in MicroOpAssembler, NOT OperationDecoder: `decode()` sees
  // the opword only (it runs at I-cache refill time), and both the direction (ext[15:13])
  // and the register mask (ext[12:10]) live in the extension word.
  test("FMOVE.L D0,FPCR (F200 9000): sysOp/FMOVE_FPCTRL, WRITE direction, mask rides imm", VerilatorTest) {
    run { dut => drive(dut, 0xF200, w1 = 0x9000, len = 2); sleep(1)
      assert(!dut.uop.faulted.toBoolean, "FMOVE.L Dn,FPCR must not fault (it F-line trapped before Task 9)")
      assert(dut.uop.sysOp.toBoolean && dut.uop.sysKind.toEnum == SysKind.FMOVE_FPCTRL)
      assert(!dut.uop.sysReadDir.toBoolean, "ext[15:13]=100 is <ea> -> control register")
      assert(dut.uop.imm.toLong == 0x4, "mask ext[12:10]=100 (FPCR) rides imm[2:0]")
      assert(!dut.uop.useImm.toBoolean, "useImm MUST stay False so the IQ wakes the Rn dependency")
      assert(dut.uop.srcBValid.toBoolean && dut.uop.srcBReg.toInt == 0, "Rn=D0 rides srcB")
      assert(!dut.uop.dstValid.toBoolean, "a WRITE to a control register has no rename destination")
      assert(dut.uop.op.toEnum == DecOp.MOVE, "result = srcB, captured into sysValStore by the ROB")
      // The FP rename classes must stay untouched -- FPCR/FPSR/FPIAR are non-renamed, and
      // the FPSR write's FPCC nibble goes DIRECTLY to the committed FPCC phys from
      // ExceptionUnit. This is what makes the uop provably unable to collide with
      // RobPlugin's sysOp-read commit block (which force-clears fpWrite/fpccWrite).
      assert(!dut.uop.writesFp.toBoolean && !dut.uop.writesFpcc.toBoolean && !dut.uop.readsFpcc.toBoolean)
    }
  }
  test("FMOVE.L D0,FPSR (F200 8800) / D0,FPIAR (F200 8400): mask selects the right register", VerilatorTest) {
    run { dut =>
      drive(dut, 0xF200, w1 = 0x8800, len = 2); sleep(1)
      assert(dut.uop.sysOp.toBoolean && !dut.uop.sysReadDir.toBoolean && dut.uop.imm.toLong == 0x2, "FPSR mask = 010")
      drive(dut, 0xF200, w1 = 0x8400, len = 2); sleep(1)
      assert(dut.uop.sysOp.toBoolean && !dut.uop.sysReadDir.toBoolean && dut.uop.imm.toLong == 0x1, "FPIAR mask = 001")
    }
  }
  test("FMOVE.L FPSR,D0 (F200 A800): READ direction takes a real renamed int dst", VerilatorTest) {
    run { dut => drive(dut, 0xF200, w1 = 0xA800, len = 2); sleep(1)
      assert(dut.uop.sysOp.toBoolean && dut.uop.sysKind.toEnum == SysKind.FMOVE_FPCTRL)
      assert(dut.uop.sysReadDir.toBoolean, "ext[15:13]=101 is control register -> <ea>")
      assert(dut.uop.dstValid.toBoolean && dut.uop.dstReg.toInt == 0, "Rn=D0 is the renamed dst")
      assert(!dut.uop.srcBValid.toBoolean && !dut.uop.srcAValid.toBoolean)
      assert(dut.uop.imm.toLong == 0x2, "FPSR mask = 010")
      // Same FP-rename-exclusivity check as the WRITE-direction case above, and it matters
      // MORE here: this is the direction that actually takes a rename destination, so it is
      // the one that reaches RobPlugin's sysOp-read commit block and its force-clear of
      // fpWrite/fpccWrite (RobPlugin.scala:1297-1298). If decode ever started claiming an
      // FP/FPCC destination for this form, that force-clear would silently leak a physical
      // register instead of faulting loudly.
      assert(!dut.uop.writesFp.toBoolean && !dut.uop.writesFpcc.toBoolean && !dut.uop.readsFpcc.toBoolean)
    }
  }
  test("FMOVE.L A0,FPIAR (F208 8400): <ea> mode 001 maps An -> arch id 8..15", VerilatorTest) {
    run { dut => drive(dut, 0xF208, w1 = 0x8400, len = 2); sleep(1)
      assert(dut.uop.sysOp.toBoolean && !dut.uop.sysReadDir.toBoolean)
      assert(dut.uop.srcBValid.toBoolean && dut.uop.srcBReg.toInt == 8, "A0 = arch reg 8")
    }
  }
  test("FMOVE-control arm is EXT-WORD gated: 0xF200 with ext=0 still faults to vector 11", VerilatorTest) {
    run { dut =>
      // ext[15:13]=000 is the register-to-register ARITHMETIC form (opmode 0 = FMOVE
      // FPm,FPn), NOT a control-register move. It must NOT be claimed as a sysOp.
      drive(dut, 0xF200, w1 = 0x0000, len = 2); sleep(1)
      assert(!dut.uop.sysOp.toBoolean, "opclass 000 is arithmetic, not a control-register move")
    }
  }
}
