package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.{Size, MemOp}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

/** MOVE-to/from-memory CCR cracking (the fix): MOVE sets NZVC for ALL dest forms,
  * including MOVE *to memory*. Exposes BOTH cracked µops + the µop count so the
  * store µop (MOVE Dn,(mem)) and the op µop (MOVE (mem),Dn) can be inspected. */
class MoveCcrSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt   = in(DecodePacket())
    val uop0  = out(DecodedUop())
    val uop1  = out(DecodedUop())
    val count = out(UInt(2 bits))
    val a = MicroOpAssembler.assemble(pkt)
    uop0  := a.uops(0)
    uop1  := a.uops(1)
    count := a.count
  }
  def drive(dut: Dut, op: Int, w1: Int = 0, w2: Int = 0, len: Int = 1): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x1000; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    dut.pkt.words(0) #= op; dut.pkt.words(1) #= w1; dut.pkt.words(2) #= w2
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }
  def run(check: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim(check)

  // MOVE.L D0,0x2000 (abs.W dst) : 0x21C0 ## 0x2000.  dst EA = (mode 111, reg 000) = abs.W
  // The crackStore path -> count 1, uop0 = STORE, writesNzvc TRUE (the fix), no int dst.
  test("MOVE.L D0,(abs) -> single STORE µop sets writesNzvc, no int dst", VerilatorTest) {
    run { dut => drive(dut, 0x21C0, 0x2000, len = 2); sleep(1)
      assert(dut.count.toInt == 1, s"expected 1 µop, got ${dut.count.toInt}")
      assert(dut.uop0.memOp.toEnum == MemOp.STORE, "uop0 must be the STORE")
      assert(dut.uop0.op.toEnum == DecOp.MOVE && dut.uop0.size.toEnum == Size.LONG)
      assert(!dut.uop0.unimplemented.toBoolean, "store path must not be unimplemented")
      assert(dut.uop0.srcBValid.toBoolean && dut.uop0.srcBReg.toInt == 0, "store data = D0")
      assert(!dut.uop0.dstValid.toBoolean, "store writes no int reg")
      assert(dut.uop0.writesNzvc.toBoolean, "MOVE to memory MUST set NZVC (the fix)")
      assert(!dut.uop0.writesX.toBoolean, "MOVE never writes X")
    }
  }

  // MOVE.W D1,0x2000 : 0x33C1 ## 0x2000.  Word size variant.
  test("MOVE.W D1,(abs) -> STORE µop writesNzvc, size WORD, data D1", VerilatorTest) {
    run { dut => drive(dut, 0x33C1, 0x2000, len = 2); sleep(1)
      assert(dut.count.toInt == 1 && dut.uop0.memOp.toEnum == MemOp.STORE)
      assert(dut.uop0.size.toEnum == Size.WORD)
      assert(dut.uop0.srcBReg.toInt == 1 && dut.uop0.srcBValid.toBoolean)
      assert(dut.uop0.writesNzvc.toBoolean && !dut.uop0.dstValid.toBoolean)
    }
  }

  // MOVE.B D2,(A0) : 0x1082.  dst EA = (mode 010=(An), reg 000) -> memSimple (A0 indirect).
  test("MOVE.B D2,(A0) -> STORE µop writesNzvc, size BYTE, base A0", VerilatorTest) {
    run { dut => drive(dut, 0x1082); sleep(1)
      assert(dut.count.toInt == 1 && dut.uop0.memOp.toEnum == MemOp.STORE)
      assert(dut.uop0.size.toEnum == Size.BYTE)
      assert(dut.uop0.srcBReg.toInt == 2 && dut.uop0.srcBValid.toBoolean)  // data = D2
      assert(dut.uop0.srcAValid.toBoolean && dut.uop0.srcAReg.toInt == 8)  // base = A0 (arch 8)
      assert(dut.uop0.writesNzvc.toBoolean && !dut.uop0.dstValid.toBoolean)
    }
  }

  // MOVE.L 0x2000,D3 (abs.W src -> Dn) : 0x2639 ## 0x0000 ## 0x2000 (abs.L? use abs.W).
  // abs.W src EA = (mode 111, reg 000). 0x2638 ## 0x2000. dst Dn=3 (bits 11-9=011, mode 000).
  // crackLoad path -> count 2: uop0 = LOAD->T0, uop1 = MOVE T0->D3 with writesNzvc TRUE.
  test("MOVE.L (abs),D3 -> LOAD + op µop; op µop sets writesNzvc (mem->Dn)", VerilatorTest) {
    run { dut => drive(dut, 0x2638, 0x2000, len = 2); sleep(1)
      assert(dut.count.toInt == 2, s"expected 2 µops (load+op), got ${dut.count.toInt}")
      assert(dut.uop0.memOp.toEnum == MemOp.LOAD, "uop0 must be the LOAD")
      assert(dut.uop1.op.toEnum == DecOp.MOVE && dut.uop1.memOp.toEnum == MemOp.NONE)
      assert(dut.uop1.dstValid.toBoolean && dut.uop1.dstReg.toInt == 3, "op µop -> D3")
      assert(dut.uop1.writesNzvc.toBoolean, "MOVE mem->Dn MUST set NZVC")
      assert(!dut.uop1.writesX.toBoolean)
    }
  }
}
