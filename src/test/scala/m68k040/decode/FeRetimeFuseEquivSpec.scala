package m68k040.decode

import m68k040.M68kSim
import m68k040.frontend.DecodePacket
import m68k040.isa.Size
import m68k040.decode.MicroOpAssembler.AssembledUops
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

/** CPU_FE_RETIME: the assembler with `retime = true` (pre-fuse `count3`, shallow fused
  * MOVE.L verdict/operands) must produce bit-identical uops/count to the original for
  * EVERY opword, across packet sizes and extension-word shapes. Exhaustive over the
  * 16-bit opword space, so the "opUop.dstValid is always True inside the fused arm"
  * argument is checked, not assumed. */
class FeRetimeFuseEquivSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt  = in(DecodePacket())
    val off  = MicroOpAssembler.computeOffloadFromWords(pkt)
    val a    = MicroOpAssembler.assemble(pkt, off, fuseLongMoveLoads = true)
    val b    = MicroOpAssembler.assemble(pkt, off, fuseLongMoveLoads = true, retime = true)
    val same = out Bool()
    val fusedNow = out Bool()
    same := a.count === b.count && a.uops.asBits === b.uops.asBits &&
            a.fpImmAlloc === b.fpImmAlloc && a.fpWideImm === b.fpWideImm &&
            b.count3 === (b.count === U(3, 2 bits))
    val op = pkt.words(0)
    // a fused long MOVE: line-2 opword with a MEMORY source that still assembles to 1 uop
    fusedNow := (a.count === U(1, 2 bits)) && (op(15 downto 12) === B"4'h2") &&
                (op(5 downto 3).asUInt >= 2) && !(op(5 downto 3) === B"3'b111" && op(2 downto 0).asUInt >= 4)
  }

  test("CPU_FE_RETIME assembler == original over all 65536 opwords") {
    M68kSim().withVerilator.compile(new Dut).doSim { d =>
      d.pkt.flatten.foreach {
        case b: Bool => b #= false
        case b: Bits => b #= 0
        case u: UInt => u #= 0
        case _: SpinalEnumCraft[_] =>
      }
      d.pkt.valid #= true; d.pkt.pc #= 0x1000; d.pkt.simple #= true
      d.pkt.lenWords #= 3; d.pkt.wordCount #= 6
      val exts = Seq(0x0000, 0x1804, 0x0170, 0xFFFF)
      var fused = 0L; var n = 0L
      for (sz <- Seq(Size.BYTE, Size.WORD, Size.LONG); ext <- exts; op <- 0 until 65536) {
        d.pkt.size #= sz
        d.pkt.words(0) #= op
        for (w <- 1 until 6) d.pkt.words(w) #= (ext ^ (w * 0x0101)) & 0xFFFF
        sleep(1)
        assert(d.same.toBoolean, f"retimed assembler diverged: op=$op%04x ext=$ext%04x size=$sz")
        if (d.fusedNow.toBoolean) fused += 1
        n += 1
      }
      println(s"FE_RETIME fuse equiv: $n vectors, fused MOVE.L shapes=$fused")
      assert(fused > 1000, s"fused coverage too thin: $fused")
    }
  }
}
