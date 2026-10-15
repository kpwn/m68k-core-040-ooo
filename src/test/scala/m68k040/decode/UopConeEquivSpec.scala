package m68k040.decode

import m68k040.M68kSim
import m68k040.frontend.DecodePacket
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

/** CPU_UOP_CONE: the one-hot builder-select crack tree must give BIT-IDENTICAL assembler
  * outputs (all 3 uops, every field incl. the imm code, count/count3, FP side channel) to
  * the original whole-bundle priority chain. Both arms run in the shipping CPU_IMM_CONE
  * form (`assembleAtoms`); a third arm checks the plain 32-bit-imm `assemble` too.
  * Same input regimes as ImmConeEquivSpec: exhaustive opwords x pipeline / free-random
  * offloads / cold FMOVEM-data and bit-field ext shapes. */
class UopConeEquivSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt     = in(DecodePacket())
    val offBits = in(Bits(MicroOpAssembler.Offload().getBitsWidth bits))
    val useFree = in(Bool())
    val offFree = MicroOpAssembler.Offload()
    offFree.assignFromBits(offBits)
    val off     = Mux(useFree, offFree, MicroOpAssembler.computeOffloadFromWords(pkt))
    val retime  = m68k040.top.ShippingCoreConfig.feRetime
    val (a, _)  = MicroOpAssembler.assembleAtoms(pkt, off, true, retime, uopCone = false)
    val (b, _)  = MicroOpAssembler.assembleAtoms(pkt, off, true, retime, uopCone = true)
    val c       = MicroOpAssembler.assemble(pkt, off, fuseLongMoveLoads = true, retime = retime, uopCone = false)
    val d       = MicroOpAssembler.assemble(pkt, off, fuseLongMoveLoads = true, retime = retime, uopCone = true)
    val same    = out(Bits(3 bits))
    val same32  = out(Bits(3 bits))
    val rest    = out(Bool())
    for (q <- 0 until 3) { same(q) := a.uops(q).asBits === b.uops(q).asBits; same32(q) := c.uops(q).asBits === d.uops(q).asBits }
    rest := a.count === b.count && a.fpImmAlloc === b.fpImmAlloc && a.fpWideImm === b.fpWideImm &&
            c.count === d.count && (if (retime) a.count3 === b.count3 && c.count3 === d.count3 else True)
    val count = out(UInt(2 bits)); count := a.count
  }

  test("CPU_UOP_CONE builder-select crack tree == original priority chain, exhaustive opwords") {
    M68kSim().withVerilator.compile(new Dut).doSim(seed = 0x0C0) { d =>
      val rnd = new scala.util.Random(0x0C0)
      val offW = d.offBits.getWidth
      var n = 0L
      val counts = Array.fill(4)(0L)
      def randPkt(op: Int): Unit = {
        d.pkt.flatten.foreach {
          case b: Bool => b #= rnd.nextBoolean()
          case b: Bits => b.randomize()
          case u: UInt => u.randomize()
          case e: SpinalEnumCraft[_] => e.randomize()
          case _ =>
        }
        d.pkt.words(0) #= op
      }
      for (round <- 0 until 16; op <- 0 until 65536) {
        randPkt(op)
        if (round >= 12 && round < 14) d.pkt.words(1) #= 0xC800 | (rnd.nextInt(0x10000) & 0x3070)
        if (round >= 4) d.pkt.fault #= false
        if (round >= 14) d.pkt.words(1) #= (rnd.nextInt(8) << 12) | (rnd.nextInt(32) << 6) | (26 + rnd.nextInt(6))
        d.useFree #= (round >= 8 && round < 12)
        d.offBits #= BigInt(offW, rnd)
        sleep(1)
        assert(d.same.toInt == 7, f"uop diverged (atoms form): round=$round op=$op%04x pos-mask=${d.same.toInt}%x")
        assert(d.same32.toInt == 7, f"uop diverged (32-bit form): round=$round op=$op%04x pos-mask=${d.same32.toInt}%x")
        assert(d.rest.toBoolean, f"count/fp side channel diverged: round=$round op=$op%04x")
        counts(d.count.toInt) += 1
        n += 1
      }
      println(s"UOP_CONE equiv: $n vectors, count histogram ${counts.mkString(",")}")
    }
  }
}
