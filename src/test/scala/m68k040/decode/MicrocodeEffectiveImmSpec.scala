package m68k040.decode

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import scala.util.Random

/** Exercise the actual runtime selector from a synchronous descriptor ROM,
  * including descriptors whose ignored raw immediate is deliberately nonzero. */
class MicrocodeEffectiveImmSpec extends AnyFunSuite {
  import Microcode._

  private val corners = for {
    op <- Seq[UOp](UMiHostOp, UMiHostMove, UCasOp(CasForm.CASS), UMove)
    enable <- Seq(false, true)
    source <- Seq[Sel](SNone, SEaDispLo, SNegOne, SMiImm)
  } yield Desc(op, useImm = enable, imm = source, isFirst = true, isLast = true)
  private val rows = rom ++ corners

  class Dut extends Component {
    val ctx = in(Ctx())
    val valid = in Bool()
    val address = in UInt(log2Up(rows.size) bits)
    val memory = Mem(DescBits(), rows.size) init rows.map(descToBits)
    val bits = memory.readSync(address)
    val previousAddress = RegNext(address) init 0
    val actual = out(DecodedUop())
    val expected = out(DecodedUop())
    actual := resolveFromBits(bits, ctx, valid)
    expected := Vec(rows.map(d => resolve(d, ctx, valid)))(previousAddress)
    val mismatch = out Bool()
    mismatch := actual.asBits =/= expected.asBits
    val rowWidth = bits.getBitsWidth
    val actualImmediate = out Bits(32 bits)
    actualImmediate := actual.imm
  }

  test("effective immediate ROM matches descriptor oracle, including ignored-selector and host-op corners", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim(seed = 0x1ee7) { dut =>
      val cd = dut.clockDomain
      val rng = new Random(0x1ee7)
      cd.forkStimulus(10)
      dut.address #= 0
      dut.valid #= false
      def randomContext(): Unit = dut.ctx.flatten.foreach {
        case b: Bool => b #= rng.nextBoolean()
        case e: SpinalEnumCraft[_] =>
          val enum = e.asInstanceOf[SpinalEnumCraft[SpinalEnum]]
          enum #= enum.spinalEnum.elements(rng.nextInt(enum.spinalEnum.elements.size))
        case bits: BitVector => bits #= BigInt(bits.getWidth, rng)
        case other => fail(s"unhandled context field $other")
      }
      randomContext()
      cd.waitSampling(5)
      var checked = 0
      var host = 0
      var ignored = 0
      // Every ROM row and synthetic corner, alternating valid and exercising
      // zero/ones/sign boundary/one-hot values as well as changing every context.
      val values = Seq(BigInt(0), BigInt("ffffffff", 16), BigInt("80000000", 16),
        BigInt("7fffffff", 16)) ++ (0 until 32).map(i => BigInt(1) << i)
      for ((value, sweep) <- values.zipWithIndex; index <- rows.indices) {
        dut.address #= index
        randomContext()
        dut.ctx.miHostImm #= value
        dut.ctx.eaDispLo #= (value ^ BigInt("a5a55a5a", 16))
        dut.ctx.miOtherIsImm #= ((sweep & 1) != 0)
        dut.valid #= ((index + sweep) % 3 != 0)
        cd.waitSampling()
        sleep(1)
        assert(!dut.mismatch.toBoolean,
          s"row=$index descriptor=${rows(index)} sweep=$sweep actualImm=${dut.actual.imm.toBigInt.toString(16)} expectedImm=${dut.expected.imm.toBigInt.toString(16)}")
        if (rows(index).uop == UMiHostOp) {
          host += 1
          assert(dut.actualImmediate.toBigInt == value,
            "host immediate value must survive even when useImm is false")
        } else if (!rows(index).useImm) {
          ignored += 1
          assert(dut.actualImmediate.toBigInt == 0,
            "a non-host row must ignore its raw selector when useImm is false")
        }
        checked += 1
      }
      assert(host > 0 && ignored > 0)
      assert(dut.rowWidth == 79, "effective selection must not widen the microcode ROM")
      info(s"MICROCODE_ENCODING romDepth=$romSize rowWidth=${dut.rowWidth} testRows=${rows.size}")
      info(s"MICROCODE_RUNTIME_COMPARE checked=$checked host=$host ignored=$ignored")
    }
  }
}
