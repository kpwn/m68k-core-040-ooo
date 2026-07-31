package m68k040.sim

import org.scalatest.funsuite.AnyFunSuite
import spinal.lib.bus.amba4.axi.Axi4Config

class AxiProtocolCheckerSpec extends AnyFunSuite {
  private val busCfg = AxiMemModel.axiConfig(128, 4)

  test("duplicate live AR id is reported") {
    val c = new AxiProtocolChecker(AxiMemModelConfig(checkProtocol = false), busCfg)
    c.onAr(3, BigInt(0), 4, 0, 1, idAlreadyLive = true, cycle = 10)
    assert(c.violations.exists(_.contains("ALREADY outstanding")))
  }

  test("non-INCR burst is reported") {
    val c = new AxiProtocolChecker(AxiMemModelConfig(checkProtocol = false), busCfg)
    c.onAr(0, BigInt(0), 4, 0, 0 /* FIXED */, idAlreadyLive = false, cycle = 10)
    assert(c.violations.exists(_.contains("not INCR")))
  }

  test("r.last on the wrong beat is reported") {
    val c = new AxiProtocolChecker(AxiMemModelConfig(checkProtocol = false), busCfg)
    c.onAr(1, BigInt(0), 4, 3, 1, idAlreadyLive = false, cycle = 1)
    c.onRBeat(1, isLast = true, idOutstanding = true, cycle = 2)
    assert(c.violations.exists(_.contains("last on beat 1 but len+1=4")))
  }

  test("wstrb wider than the beat is reported") {
    val c = new AxiProtocolChecker(AxiMemModelConfig(checkProtocol = false), busCfg)
    c.onAw(8, BigInt(0), 4, 0, 1, idAlreadyLive = false, cycle = 1)
    c.onWBeat(8, wlast = true, engineSaysLast = true, strb = BigInt(1) << 16,
              bytesPerBeat = 16, cycle = 2)
    assert(c.violations.exists(_.contains("bits beyond 16 bytes")))
  }
}
