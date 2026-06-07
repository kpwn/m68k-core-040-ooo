package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Scc decode: `0101 cccc 11 000 rrr` -> a branch-EU µop that sets Dn[7:0] to
  * 0xFF/0x00 on condition cccc (partial byte write, upper-24 preserved, NO flags). */
class SccDecodeSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt = in(DecodePacket())
    val uop = out(DecodedUop())
    uop := MicroOpAssembler.assemble(pkt).uops(0)
  }
  def drive(dut: Dut, op: Int): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x1000; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= 1; dut.pkt.wordCount #= 1; dut.pkt.fault #= false
    dut.pkt.words(0) #= op; dut.pkt.words(1) #= 0; dut.pkt.words(2) #= 0
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }
  def run(check: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim(check)

  // SEQ D2 = 0x57C2 (0101 0111 11 000 010): cccc=0111 (EQ), mode 000 (Dn), D2.
  test("SEQ D2 -> isScc branch uop, cond=7, reads NZVC, reads+writes D2, no flags", VerilatorTest) {
    run { dut => drive(dut, 0x57C2); sleep(1)
      assert(dut.uop.isScc.toBoolean, "Scc must set isScc")
      assert(dut.uop.isBranch.toBoolean, "Scc routes to the branch EU")
      assert(dut.uop.cond.toInt == 0x7)
      assert(dut.uop.readsNzvc.toBoolean, "Scc reads NZVC for the condition")
      assert(dut.uop.srcAReg.toInt == 2 && dut.uop.srcAValid.toBoolean, "reads old D2 (merge source)")
      assert(dut.uop.dstReg.toInt == 2 && dut.uop.dstValid.toBoolean, "writes D2[7:0]")
      assert(!dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean, "Scc sets NO flags")
      assert(!dut.uop.unimplemented.toBoolean && !dut.uop.faulted.toBoolean)
      assert(!dut.uop.isDbcc.toBoolean)
    }
  }
  // ST D0 = 0x50C0 (cccc=0000, T=true): always 0xFF.
  test("ST D0 -> cond=0 (true)", VerilatorTest) {
    run { dut => drive(dut, 0x50C0); sleep(1)
      assert(dut.uop.isScc.toBoolean && dut.uop.cond.toInt == 0x0 && dut.uop.dstReg.toInt == 0)
    }
  }
  // SF D1 = 0x51C1 (cccc=0001, F=false): always 0x00.
  test("SF D1 -> cond=1 (false)", VerilatorTest) {
    run { dut => drive(dut, 0x51C1); sleep(1)
      assert(dut.uop.isScc.toBoolean && dut.uop.cond.toInt == 0x1 && dut.uop.dstReg.toInt == 1)
    }
  }
  // SMI D7 = 0x5BC7 (cccc=1011, MI): negative.
  test("SMI D7 -> cond=0xB (MI)", VerilatorTest) {
    run { dut => drive(dut, 0x5BC7); sleep(1)
      assert(dut.uop.isScc.toBoolean && dut.uop.cond.toInt == 0xB && dut.uop.dstReg.toInt == 7)
    }
  }
  // Scc to memory (mode != 0, != 001) is the deferred RMW form -> illegal.
  // SEQ (A0) = 0x57D0 (mode 010): deferred -> unimplemented.
  test("SEQ (A0) (memory dest) -> unimplemented (deferred)", VerilatorTest) {
    run { dut => drive(dut, 0x57D0); sleep(1)
      assert(dut.uop.unimplemented.toBoolean && !dut.uop.isScc.toBoolean)
    }
  }
}
