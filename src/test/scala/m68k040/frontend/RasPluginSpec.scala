package m68k040.frontend

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Directed RasPlugin unit test (return-address stack, slice 2).
  * push/pop/predict, wrap on overflow (circular), no-predict on underflow (empty),
  * invalidate clears count. The RAS is single-ported (one push XOR one pop per cycle).
  */
class RasPluginSpec extends AnyFunSuite {

  /** Wiring plugin: hoists the RasPlugin's plain-wire ports to top IO inside a Fiber
    * build (the BtbPlugin convention — a sibling drives the directionless ports). */
  class RasWirePlugin extends FiberPlugin {
    val logic = during build new Area {
      val ras = host[RasPlugin]
      val iPushValid = in Bool ()
      val iPushRetPc = in UInt (32 bits)
      val iPopValid  = in Bool ()
      val iInval     = in Bool ()
      ras.logic.pushValid     := iPushValid
      ras.logic.pushRetPc     := iPushRetPc
      ras.logic.popValid      := iPopValid
      ras.logic.invalidateAll := iInval
      val oPredValid  = out(Bool());        oPredValid  := ras.logic.predValid
      val oPredTarget = out(UInt(32 bits)); oPredTarget := ras.logic.predTarget
    }
  }

  class RasDut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ras  = new RasPlugin
    val wire = new RasWirePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ras, wire)) }
  }

  def init(dut: RasDut, cd: ClockDomain): Unit = {
    dut.wire.logic.iPushValid #= false
    dut.wire.logic.iPushRetPc #= 0
    dut.wire.logic.iPopValid  #= false
    dut.wire.logic.iInval     #= false
    cd.waitSampling()
  }

  /** Read the combinational predict (valid + target) THIS cycle. */
  def predict(dut: RasDut): (Boolean, Long) =
    (dut.wire.logic.oPredValid.toBoolean, dut.wire.logic.oPredTarget.toLong)

  def push(dut: RasDut, cd: ClockDomain, retPc: Long): Unit = {
    dut.wire.logic.iPushValid #= true
    dut.wire.logic.iPushRetPc #= retPc
    cd.waitSampling()
    dut.wire.logic.iPushValid #= false
  }

  def pop(dut: RasDut, cd: ClockDomain): Unit = {
    dut.wire.logic.iPopValid #= true
    cd.waitSampling()
    dut.wire.logic.iPopValid #= false
  }

  test("empty RAS predicts nothing (no-predict on underflow)", VerilatorTest) {
    SimConfig.withVerilator.compile(new RasDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      sleep(1)
      val (pv, _) = predict(dut)
      assert(!pv, "an empty RAS must predict nothing (count==0)")
      // A pop on the empty RAS does not produce a prediction and stays empty.
      pop(dut, cd); sleep(1)
      assert(!predict(dut)._1, "pop on empty stays empty / no predict")
    }
  }

  test("push then predict top-of-stack; pop unwinds LIFO", VerilatorTest) {
    SimConfig.withVerilator.compile(new RasDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      push(dut, cd, 0x1000)
      sleep(1)
      var (pv, pt) = predict(dut)
      assert(pv && pt == 0x1000, s"after one push predict=0x1000 (got pv=$pv pt=0x${pt.toHexString})")
      push(dut, cd, 0x2000)
      push(dut, cd, 0x3000)
      sleep(1)
      assert(predict(dut) == (true, 0x3000L), "top of stack = last pushed 0x3000")
      // Pop unwinds in LIFO order: 0x3000 -> 0x2000 -> 0x1000 -> empty.
      pop(dut, cd); sleep(1)
      assert(predict(dut) == (true, 0x2000L), "after one pop top=0x2000")
      pop(dut, cd); sleep(1)
      assert(predict(dut) == (true, 0x1000L), "after two pops top=0x1000")
      pop(dut, cd); sleep(1)
      assert(!predict(dut)._1, "after three pops the RAS is empty")
    }
  }

  test("overflow is circular: push beyond depth wraps, count saturates", VerilatorTest) {
    SimConfig.withVerilator.compile(new RasDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      val depth = 16
      // Push depth+3 entries: 0x100, 0x200, ... The oldest 3 are overwritten (circular).
      for (i <- 0 until depth + 3) push(dut, cd, 0x100 * (i + 1))
      sleep(1)
      // Top is the LAST pushed.
      assert(predict(dut)._2 == 0x100 * (depth + 3), "top = last pushed after overflow")
      // count saturated at depth: exactly `depth` pops drain to empty (the overflow did
      // not grow count beyond depth — it overwrote the oldest in place).
      for (_ <- 0 until depth) { pop(dut, cd) }
      sleep(1)
      assert(!predict(dut)._1, "after `depth` pops the saturated RAS is empty")
    }
  }

  test("invalidateAll clears count -> predicts nothing", VerilatorTest) {
    SimConfig.withVerilator.compile(new RasDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      push(dut, cd, 0x4000)
      push(dut, cd, 0x5000)
      sleep(1)
      assert(predict(dut)._1, "non-empty before invalidate")
      dut.wire.logic.iInval #= true; cd.waitSampling(); dut.wire.logic.iInval #= false
      sleep(1)
      assert(!predict(dut)._1, "invalidateAll clears count -> no predict")
    }
  }
}
