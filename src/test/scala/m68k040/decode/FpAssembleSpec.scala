package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.{Cluster, Size}
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class FpAssembleSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt = in(DecodePacket())
    val uop = out(DecodedUop())
    uop := MicroOpAssembler.assemble(pkt).uops(0)
  }
  /** Drive a packet whose length matches what PredecodeWord would really frame. */
  def drive(dut: Dut, op: Int, ext: Int = 0, ext2: Int = 0, len: Int = 2, simple: Boolean = true): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x2000; dut.pkt.simple #= simple; dut.pkt.complex #= !simple
    dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    dut.pkt.words(0) #= op; dut.pkt.words(1) #= ext; dut.pkt.words(2) #= ext2
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }
  def run(check: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim(check)

  // ── Step 2's standalone fix: the two line-F trap PC flavors ────────────────────
  test("a FRAMED line-F encoding traps to vector 11 with the POST-instruction PC", VerilatorTest) {
    run { dut =>
      // FSIN FP0,FP0 (opmode 0x0E) -- a real cpGEN instruction, framed 2 words by Task 5,
      // NOT hardware-native, so it is exactly the FPSP-routed case.
      drive(dut, op = 0xF200, ext = 0x000E, len = 2); sleep(1)
      assert(dut.uop.faulted.toBoolean, "an unimplemented FP op must fault")
      assert(dut.uop.faultVector.toInt == 11, s"architectural vector stays 11, got ${dut.uop.faultVector.toInt}")
      assert(dut.uop.faultUsesNextPc.toBoolean,
        "a framed line-F trap must stack the POST-instruction PC, else FPSP's RTE re-executes the same opword forever")
      assert(dut.uop.nextPc.toLong == 0x2004L,
        f"nextPc must be pc + 2*lenWords = 0x2004, got 0x${dut.uop.nextPc.toLong}%x")
    }
  }

  test("an UNFRAMED line-F encoding keeps the PRE-instruction (faulting) PC", VerilatorTest) {
    run { dut =>
      // FBcc.W (type 010) -- not cpGEN, predecode frames it 1 word, length unknown.
      drive(dut, op = 0xF280, ext = 0x0000, len = 1); sleep(1)
      assert(dut.uop.faulted.toBoolean && dut.uop.faultVector.toInt == 11)
      assert(!dut.uop.faultUsesNextPc.toBoolean,
        "an unknown-length line-F trap MUST stay restartable (faulting PC) -- the handler cannot know the length")
      // And a cpGEN opword that predecode declined to frame (reserved <ea>) behaves the same.
      drive(dut, op = 0xF23D, ext = 0x4022, len = 1); sleep(1)
      assert(dut.uop.faulted.toBoolean && dut.uop.faultVector.toInt == 11)
      assert(!dut.uop.faultUsesNextPc.toBoolean,
        "lenWords===1 on a cpGEN opword means 'not framed' -- it must NOT claim a known length")
    }
  }

  test("line-A and generic illegal traps are untouched by the line-F change", VerilatorTest) {
    run { dut =>
      drive(dut, op = 0xA000, len = 1); sleep(1)
      assert(dut.uop.faultVector.toInt == 10 && !dut.uop.faultUsesNextPc.toBoolean, "line-A vector 10, faulting PC")
      drive(dut, op = 0x4AFC, len = 1); sleep(1)   // ILLEGAL
      assert(dut.uop.faultVector.toInt == 4 && !dut.uop.faultUsesNextPc.toBoolean, "generic illegal vector 4, faulting PC")
    }
  }
}
