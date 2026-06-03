package m68k040.decode

import m68k040.VerilatorTest
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class DecodeContractsSpec extends AnyFunSuite {
  class Dut extends Component {
    val o = out(OpSpec())
    val e = out(EaSpec())
    o := OpSpec.illegalDefault()
    e := EaSpec.illegalDefault()
  }
  test("contracts elaborate; illegal defaults are sane", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      sleep(1)
      assert(dut.o.illegal.toBoolean)
      assert(dut.o.srcA.kind.toEnum == OperandKind.NONE)
      assert(dut.e.klass.toEnum == EaClass.ILLEGAL)
    }
  }
}
