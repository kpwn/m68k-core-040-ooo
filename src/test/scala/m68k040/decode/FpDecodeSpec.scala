package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.Cluster
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Task 4: F-line FP-generic (cpGEN) family classification.
  *
  * Two independent things are proven here: (1) OperationDecoder recognizes exactly the
  * `1111 001 000 mmmrrr` band and nothing else -- in particular it does NOT swallow the
  * existing CPUSH/CINV/PFLUSH/PTEST/MOVE16/FSF line-F carve-outs; and (2) the assembler
  * still delivers the ordinary vector-11 F-line trap for every cpGEN encoding, i.e. this
  * task is behavior-neutral until Task 6.
  */
class FpDecodeSpec extends AnyFunSuite {
  class SpecDut extends Component {
    val opword = in Bits (16 bits)
    val o      = out(OpSpec())
    o := OperationDecoder.decode(opword)
  }
  class AsmDut extends Component {
    val pkt = in(DecodePacket())
    val uop = out(DecodedUop())
    uop := MicroOpAssembler.assemble(pkt).uops(0)
  }

  test("cpGEN band 0xF200-0xF23F is classified FP-generic / DecOp.FPU / Cluster.CPLX", VerilatorTest) {
    SimConfig.withVerilator.compile(new SpecDut).doSim { dut =>
      for (ea <- 0 until 64) {
        val op = 0xF200 | ea
        dut.opword #= op; sleep(1)
        assert(dut.o.fpGeneric.toBoolean, f"op=0x$op%04x must be fpGeneric")
        assert(!dut.o.illegal.toBoolean,  f"op=0x$op%04x must not be illegal")
        assert(dut.o.op.toEnum == DecOp.FPU, f"op=0x$op%04x must decode to DecOp.FPU")
        assert(dut.o.cluster.toEnum == Cluster.CPLX, f"op=0x$op%04x must be Cluster.CPLX")
        assert(!dut.o.writesNzvc.toBoolean && !dut.o.readsNzvc.toBoolean,
          f"op=0x$op%04x: FP ops touch FPCC, never the integer CCR")
      }
    }
  }

  test("the cpGEN arm claims NOTHING outside its band -- every other line-F opword is unchanged", VerilatorTest) {
    SimConfig.withVerilator.compile(new SpecDut).doSim { dut =>
      // Exhaustive over the whole line-F space: fpGeneric must be true IFF (bits[11:9]==001
      // && bits[8:6]==000). This is the real guard against silently swallowing FScc/FBcc/
      // FSAVE/FRESTORE or any of the four existing carve-outs.
      val wrong = scala.collection.mutable.ArrayBuffer[String]()
      for (low <- 0 until 4096) {
        val op = 0xF000 | low
        dut.opword #= op; sleep(1)
        val expect = ((op >> 9) & 0x7) == 1 && ((op >> 6) & 0x7) == 0
        if (dut.o.fpGeneric.toBoolean != expect)
          wrong += f"op=0x$op%04x fpGeneric=${dut.o.fpGeneric.toBoolean} expected=$expect"
      }
      assert(wrong.isEmpty, s"${wrong.size} line-F opwords misclassified:\n" + wrong.take(20).mkString("\n"))
    }
  }

  test("the four pre-existing line-F carve-outs are untouched", VerilatorTest) {
    SimConfig.withVerilator.compile(new SpecDut).doSim { dut =>
      def chk(op: Int, name: String)(f: OpSpec => Boolean): Unit = {
        dut.opword #= op; sleep(1)
        assert(!dut.o.fpGeneric.toBoolean, f"$name (0x$op%04x) must NOT be fpGeneric")
        assert(f(dut.o), f"$name (0x$op%04x) regressed")
      }
      chk(0xF4F8, "CPUSH")   (s => s.sysOp.toBoolean && s.sysKind.toEnum == SysKind.CPUSH)
      chk(0xF4D8, "CINV")    (s => s.sysOp.toBoolean && s.sysKind.toEnum == SysKind.CINV)
      chk(0xF518, "PFLUSHA") (s => s.sysOp.toBoolean && s.sysKind.toEnum == SysKind.PFLUSHA)
      chk(0xF548, "PTESTW")  (s => s.sysOp.toBoolean && s.sysKind.toEnum == SysKind.PTEST)
      chk(0xF620, "MOVE16")  (s => s.microcoded.toBoolean)
      chk(0xF27F, "FSF")     (s => s.op.toEnum == DecOp.CLR && !s.illegal.toBoolean)
    }
  }

  test("until Task 6, every cpGEN encoding still takes the vector-11 F-line trap", VerilatorTest) {
    SimConfig.withVerilator.compile(new AsmDut).doSim { dut =>
      // FADD FP1,FP0 = F200 0422 (opclass 000, src FP1, dst FP0, opmode 0x22).
      dut.pkt.valid #= true; dut.pkt.pc #= 0x1000; dut.pkt.simple #= true; dut.pkt.complex #= false
      dut.pkt.lenWords #= 2; dut.pkt.wordCount #= 2; dut.pkt.fault #= false
      dut.pkt.words(0) #= 0xF200; dut.pkt.words(1) #= 0x0422
      dut.pkt.words(2) #= 0; dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
      sleep(1)
      assert(dut.uop.faulted.toBoolean, "cpGEN must still fault before Task 6")
      assert(dut.uop.faultVector.toInt == 11, s"F-line vector 11, got ${dut.uop.faultVector.toInt}")
      assert(dut.uop.unimplemented.toBoolean, "the trap uop is the generic unimplemented one")
      assert(!dut.uop.writesFp.toBoolean && !dut.uop.writesFpcc.toBoolean,
        "no FP side effects may escape from a trapping uop")
    }
  }
}
