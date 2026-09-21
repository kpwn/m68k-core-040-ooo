package m68k040.cache

import org.scalatest.funsuite.AnyFunSuite

class IcachePredecodeConfigSpec extends AnyFunSuite {
  test("refill classifier width accepts only explicitly supported configurations") {
    assert(IcachePredecodeConfig.parse("8") == 8)
    assert(IcachePredecodeConfig.parse("16") == 16)
    for (invalid <- Seq("", "0", "4", "32", "eight", " 8", "8 "))
      intercept[IllegalArgumentException](IcachePredecodeConfig.parse(invalid))
  }
}
