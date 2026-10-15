package m68k040.decode

import m68k040.M68kSim
import m68k040.frontend.DecodePacket
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

/** CPU_DEC_STASH_SHARE premise: the per-instruction fields pc/lenWords/brPredTag/valid are
  * identical across ALL THREE assembled uop positions (live or not), for every input, in
  * both imm forms and both crack-tree forms -- so the slot-1 stash may keep one copy.
  * Exhaustive opwords x random packet/offload (pipeline and free-random offloads). */
class StashShareSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt     = in(DecodePacket())
    val offBits = in(Bits(MicroOpAssembler.Offload().getBitsWidth bits))
    val useFree = in(Bool())
    val offFree = MicroOpAssembler.Offload()
    offFree.assignFromBits(offBits)
    val off     = Mux(useFree, offFree, MicroOpAssembler.computeOffloadFromWords(pkt))
    val retime  = m68k040.top.ShippingCoreConfig.feRetime
    val forms   = Seq(MicroOpAssembler.assembleAtoms(pkt, off, true, retime, uopCone = false)._1,
                      MicroOpAssembler.assembleAtoms(pkt, off, true, retime, uopCone = true)._1,
                      MicroOpAssembler.assemble(pkt, off, fuseLongMoveLoads = true, retime = retime))
    def key(u: DecodedUop): Bits = u.pc.asBits ## u.lenWords.asBits ## u.brPredTag.asBits ## u.valid.asBits
    val ok = out(Bool())
    ok := forms.map(a => key(a.uops(1)) === key(a.uops(0)) && key(a.uops(2)) === key(a.uops(0))).reduce(_ && _)
  }
  test("pc/lenWords/brPredTag/valid identical across the 3 assembled uops") {
    M68kSim().withVerilator.compile(new Dut).doSim(seed = 0x5A5) { d =>
      val rnd = new scala.util.Random(0x5A5)
      val offW = d.offBits.getWidth
      for (round <- 0 until 8; op <- 0 until 65536) {
        d.pkt.flatten.foreach {
          case b: Bool => b #= rnd.nextBoolean()
          case b: Bits => b.randomize()
          case u: UInt => u.randomize()
          case e: SpinalEnumCraft[_] => e.randomize()
          case _ =>
        }
        d.pkt.words(0) #= op
        if (round >= 2) d.pkt.fault #= false
        d.useFree #= (round >= 6)
        d.offBits #= BigInt(offW, rnd)
        sleep(1)
        assert(d.ok.toBoolean, f"per-instruction field differs across positions: round=$round op=$op%04x")
      }
    }
  }
}
