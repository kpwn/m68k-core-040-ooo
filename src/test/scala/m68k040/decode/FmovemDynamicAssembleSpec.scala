package m68k040.decode

import m68k040.{M68kSim, VerilatorTest}
import m68k040.frontend.DecodePacket
import m68k040.isa.Cluster
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class FmovemDynamicAssembleSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt = in(DecodePacket())
    val result = out(MicroOpAssembler.AssembledUops())
    val admitted = out Bool()
    result := MicroOpAssembler.assemble(pkt)
    admitted := MicroOpAssembler.isDynamicFmovem(pkt)
  }
  test("dynamic masks produce only fixed operand capture and backend invocation", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      def drive(op: Int, ext: Int, ea: Int=0, len: Int=3): Unit = {
        dut.pkt.valid #= true; dut.pkt.pc #= 0x4088e264L
        dut.pkt.simple #= false; dut.pkt.complex #= true
        dut.pkt.lenWords #= len; dut.pkt.wordCount #= len
        dut.pkt.size #= m68k040.isa.Size.LONG
        dut.pkt.fault #= false; dut.pkt.faultAtc #= false; dut.pkt.brPredTag #= 0
        for(i <- dut.pkt.words.indices) dut.pkt.words(i) #= 0
        dut.pkt.words(0) #= op; dut.pkt.words(1) #= ext; dut.pkt.words(2) #= ea
        sleep(1)
      }
      for(dn <- 0 until 8; store <- Seq(false,true); mode <- Seq(2,3,4,5,6,7);
          an <- 0 until 8 if (mode!=3 || !store) && (mode!=4 || store) &&
            (mode!=7 || an<2 || (an==2 && !store))) {
        val ext=(if(store) 0xe000 else 0xc000)|0x800|(if(mode==4) 0 else 0x1000)|(dn<<4)
        drive(0xf200|(mode<<3)|an,ext,if(mode==5) 0xff28 else 0)
        assert(dut.admitted.toBoolean)
        assert(dut.result.count.toInt==3 && !dut.result.fpImmAlloc.toBoolean)
        val cap=dut.result.uops(0); val ea=dut.result.uops(1); val invoke=dut.result.uops(2)
        assert(cap.sysKind.toEnum==SysKind.FPCTRL_CAP && !cap.sysOp.toBoolean)
        assert(cap.srcBValid.toBoolean && cap.srcBReg.toInt==dn && cap.dstReg.toInt==16)
        assert(cap.firstOfInstr.toBoolean && !cap.lastOfInstr.toBoolean)
        assert(ea.cluster.toEnum==Cluster.LS && ea.leaAddr.toBoolean && ea.dstReg.toInt==17)
        assert(!ea.firstOfInstr.toBoolean && !ea.lastOfInstr.toBoolean)
        assert(invoke.sysOp.toBoolean && invoke.sysKind.toEnum==SysKind.FMOVEM_DATA)
        assert(invoke.srcBValid.toBoolean && invoke.srcBReg.toInt==17 && !invoke.useImm.toBoolean)
        assert(!invoke.dstValid.toBoolean && !invoke.writesFp.toBoolean)
        assert(invoke.imm.toLong==((if(store) 0x40 else 0)|(mode<<3)|an))
        assert(!invoke.firstOfInstr.toBoolean && invoke.lastOfInstr.toBoolean)
      }
      // Exact failing ROM instruction: command extension is NOT the displacement.
      drive(0xf22e,0xf800,0xff28)
      assert(dut.result.uops(1).srcAReg.toInt==14)
      assert(dut.result.uops(1).imm.toLong==0xffffff28L)
      dut.pkt.valid #= false; sleep(1)
      assert(dut.admitted.toBoolean && dut.result.count.toInt==3,
        "classification must not use the non-authoritative packet valid field")
      // Negative forms retain the existing trap route, never a truncated EA.
      for((op,ext,ea) <- Seq((0xf21f,0xf800,0), (0xf227,0xc800,0),
          (0xf23a,0xf800,0), (0xf230,0xf800,0x0110),
          (0xf228,0xf801,0), (0xf228,0xf880,0), (0xf228,0xe800,0))) {
        drive(op,ext,ea)
        assert(!dut.admitted.toBoolean,f"invalid cold encoding admitted: $op%04x $ext%04x $ea%04x")
      }
      drive(0xf22e,0xf800,0xff28)
      dut.pkt.fault #= true; sleep(1)
      assert(!dut.admitted.toBoolean,"instruction fetch faults cannot start a transfer")
    }
  }
}
