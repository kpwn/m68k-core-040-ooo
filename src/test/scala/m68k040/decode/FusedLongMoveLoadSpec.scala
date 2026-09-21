package m68k040.decode

import m68k040.{M68kSim, VerilatorTest}
import m68k040.frontend.DecodePacket
import m68k040.isa.{Cluster, MemOp, Size}
import m68k040.decode.MicroOpAssembler.AssembledUops
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class FusedLongMoveLoadSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt = in(DecodePacket())
    val ordinary = out(AssembledUops())
    val fused = out(AssembledUops())
    ordinary := MicroOpAssembler.assemble(pkt)
    fused := MicroOpAssembler.assemble(pkt, MicroOpAssembler.computeOffloadFromWords(pkt),
      fuseLongMoveLoads = true)
    val same = out Bool()
    same := ordinary.asBits === fused.asBits
  }

  test("direct long MOVE load eligibility, operands, flags and conservative exclusions", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { d =>
      def drive(op: Int, len: Int = 2, ext: Int = 0x1804, fault: Boolean = false): Unit = {
        d.pkt.flatten.foreach {
          case b: Bool => b #= false
          case b: Bits => b #= 0
          case u: UInt => u #= 0
          case _: SpinalEnumCraft[_] => // The packet has one enum, driven explicitly below.
        }
        d.pkt.size #= Size.LONG
        d.pkt.valid #= true; d.pkt.pc #= 0x1000
        d.pkt.simple #= true; d.pkt.lenWords #= len; d.pkt.wordCount #= len
        d.pkt.words(0) #= op; d.pkt.words(1) #= ext; d.pkt.words(2) #= 0x3000
        d.pkt.fault #= fault
        sleep(1)
      }
      for (dstMode <- 0 to 1; dst <- 0 until 8;
           mode <- Seq(2, 5, 6, 7); reg <- 0 until (if(mode == 7) 4 else 8)) {
        val op = 0x2000 | (dst << 9) | (dstMode << 6) | (mode << 3) | reg
        drive(op, if(mode == 2) 1 else if(mode == 7 && reg == 1) 3 else 2)
        assert(d.ordinary.count.toInt == 2 && d.fused.count.toInt == 1, f"opcode $op%04x")
        val u = d.fused.uops(0); val old = d.ordinary.uops(0)
        assert(u.memOp.toEnum == MemOp.LOAD && u.cluster.toEnum == Cluster.LS)
        assert(u.size.toEnum == Size.LONG && u.op.toEnum == DecOp.MOVE)
        assert(u.dstValid.toBoolean && u.dstReg.toInt == dst + 8 * dstMode)
        assert(u.writesNzvc.toBoolean == (dstMode == 0) && !u.writesX.toBoolean)
        assert(u.firstOfInstr.toBoolean && u.lastOfInstr.toBoolean && !u.divIsRem.toBoolean)
        assert(u.pc.toLong == 0x1000 && u.lenWords.toInt == old.lenWords.toInt)
        assert(u.srcAValid.toBoolean == old.srcAValid.toBoolean && u.srcAReg.toInt == old.srcAReg.toInt)
        assert(u.srcCValid.toBoolean == old.srcCValid.toBoolean && u.srcCReg.toInt == old.srcCReg.toInt)
        assert(u.indexLong.toBoolean == old.indexLong.toBoolean && u.indexScale.toInt == old.indexScale.toInt)
        assert(u.imm.toBigInt == old.imm.toBigInt && !u.srcBValid.toBoolean)
        assert(!u.unimplemented.toBoolean && !u.faulted.toBoolean)
        drive(op, fault = true)
        assert(d.same.toBoolean, "fetch fault must take priority over fusion")
      }
      // Byte/word merges, source postincrement/predecrement, memory destination,
      // register/immediate source, ADD, TST, privileged memory-source MOVE-to-SR.
      for(op <- Seq(0x1010, 0x3010, 0x3050, 0x2018, 0x2020, 0x2058, 0x2060,
                    0x2090, 0x2000, 0x2008, 0x203c, 0xd090, 0x4a90, 0x46d0)) {
        drive(op)
        assert(d.same.toBoolean, f"excluded opcode $op%04x changed")
      }
    }
  }
}
