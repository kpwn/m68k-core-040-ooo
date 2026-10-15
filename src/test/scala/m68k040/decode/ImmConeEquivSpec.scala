package m68k040.decode

import m68k040.M68kSim
import m68k040.frontend.DecodePacket
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

/** CPU_IMM_CONE: the one-hot atom form of the assembler (`assembleAtoms` + ImmAtoms.expand)
  * must give BIT-IDENTICAL immediates to the original `assemble` at EVERY crack position
  * (0..2, live or not -- all three are written into the GROUP bank), and every non-imm uop
  * field, count, and FP side-channel output must be untouched.
  *
  * Exhaustive over the 16-bit opword space, with random extension words / pc / packet
  * fields, in two input regimes:
  *   - the pipeline's own offload (`computeOffloadFromWords`, == `computeOffload` on an
  *     Aligner packet, see FedSpecsPacketPairingSpec), and
  *   - a FREE random Offload bit pattern (illegal enum encodings included), so the check
  *     covers the cone as a function of all of its inputs, not just the reachable ones. */
class ImmConeEquivSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt     = in(DecodePacket())
    val offBits = in(Bits(MicroOpAssembler.Offload().getBitsWidth bits))
    val useFree = in(Bool())
    val offFree = MicroOpAssembler.Offload()
    offFree.assignFromBits(offBits)
    val off     = Mux(useFree, offFree, MicroOpAssembler.computeOffloadFromWords(pkt))
    val retime  = m68k040.top.ShippingCoreConfig.feRetime
    val a       = MicroOpAssembler.assemble(pkt, off, fuseLongMoveLoads = true, retime = retime)
    val (b, ia) = MicroOpAssembler.assembleAtoms(pkt, off, fuseLongMoveLoads = true, retime = retime)
    val names   = ia.names
    val used    = ia.used
    val immSame  = out(Bits(3 bits))
    val restSame = out(Bool())
    for (q <- 0 until 3) immSame(q) := ia.expand(b.uops(q).imm) === a.uops(q).imm
    def noImm(u: DecodedUop): Bits = DecodedUop.withImm(u, B(0, 1 bits)).asBits
    restSame := (0 until 3).map(q => noImm(a.uops(q)) === noImm(b.uops(q))).reduce(_ && _) &&
                a.count === b.count && a.fpImmAlloc === b.fpImmAlloc && a.fpWideImm === b.fpWideImm &&
                (if (retime) a.count3 === b.count3 else True)
    // coverage: which atoms were actually selected at some position
    val selPos = out(Vec(Bits(ImmAtoms.WIDTH bits), 3))
    for (q <- 0 until 3) selPos(q) := b.uops(q).imm
  }

  test("CPU_IMM_CONE atom cone == original imm cone, exhaustive opwords") {
    M68kSim().withVerilator.compile(new Dut).doSim(seed = 0x1CE) { d =>
      val rnd = new scala.util.Random(0x1CE)
      val offW = d.offBits.getWidth
      var n = 0L
      val hit = Array.fill(3)(BigInt(0))
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
      // rounds 0-7: pipeline offload; 8-11: free random offload; 12-13: words(1) forced to the
      // dynamic-FMOVEM ext shape (11xx1 ... with the 0x078F bits clear) so the cold
      // FMOVEM-data crack (`isDynamicFmovem`) is reached at all; 14-15: words(1) forced to a
      // static bit-field ext (Do=Dw=0, width 26..31, any offset) so the 3-uop span with the
      // `hi` load (needHi) is common, PC-relative included. 12-15 also clear `fault`.
      for (round <- 0 until 16; op <- 0 until 65536) {
        randPkt(op)
        if (round >= 12 && round < 14) d.pkt.words(1) #= 0xC800 | (rnd.nextInt(0x10000) & 0x3070)
        if (round >= 12) d.pkt.fault #= false
        if (round >= 14) d.pkt.words(1) #= (rnd.nextInt(8) << 12) | (rnd.nextInt(32) << 6) | (26 + rnd.nextInt(6))
        val free = round >= 8 && round < 12
        d.useFree #= free
        d.offBits #= BigInt(offW, rnd)
        sleep(1)
        val imm = d.immSame.toInt
        assert(imm == 7, f"imm diverged: round=$round op=$op%04x pos-mask=$imm%x")
        assert(d.restSame.toBoolean, f"non-imm field diverged: round=$round op=$op%04x")
        for (q <- 0 until 3) hit(q) |= d.selPos(q).toBigInt
        n += 1
      }
      val any = hit.reduce(_ | _)
      def nm(m: BigInt) = d.names.zipWithIndex.collect { case (s, k) if m.testBit(k) => s }.mkString(",")
      println(s"IMM_CONE equiv: $n vectors, code bits used=${d.used.length}, ever selected=${any.bitCount}")
      for (q <- 0 until 3) println(s"  pos$q (${hit(q).bitCount}): ${nm(hit(q))}")
      val never = d.used.filterNot(i => any.testBit(i))
      println(s"  never selected: ${never.map(d.names(_)).mkString(",")}")
      assert(never.isEmpty, s"atoms never selected: ${never.map(d.names(_)).mkString(",")}")
    }
  }
}
