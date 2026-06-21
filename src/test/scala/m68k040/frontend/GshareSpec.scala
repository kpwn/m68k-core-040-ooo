package m68k040.frontend

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.services.GshareUpdate
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Directed GsharePlugin unit test (direction predictor, slice 3).
  *  - the folded XOR index = fold(pc[31:1]) ^ fold(ghr) (computed in Scala, checked vs HW);
  *  - 2-bit counter saturate ±1 (weakly-taken init 2; trains up to 3 / down to 0);
  *  - GHR shift (shiftValid/shiftDir shifts a directional bit in);
  *  - learns-the-alternation: an alternating outcome at ONE PC across TWO GHR contexts
  *    converges the two PHT entries to OPPOSITE directions (the branchy win). */
class GshareSpec extends AnyFunSuite {

  val ghrBits = 16
  val phtEntries = 2048
  val idxBits = 11

  /** Scala model of the plugin's fold (XOR idxBits-wide chunks of a width-w value). */
  def fold(v: Long, w: Int): Int = {
    var acc = 0
    var lo = 0
    while (lo < w) {
      val hi = scala.math.min(lo + idxBits - 1, w - 1)
      val nb = hi - lo + 1
      val chunk = ((v >> lo) & ((1L << nb) - 1)).toInt
      acc ^= chunk
      lo += idxBits
    }
    acc & ((1 << idxBits) - 1)
  }
  /** index(pc) = fold(pc[31:1]) ^ fold(ghr). */
  def idxOf(pc: Long, ghr: Int): Int = fold((pc >>> 1) & 0x7FFFFFFFL, 31) ^ fold(ghr.toLong, ghrBits)

  /** Wiring plugin: hoists the GsharePlugin's plain-wire ports + the (plugin-owned)
    * update Flow to top IO inside a Fiber build (the BtbPlugin convention). The test
    * drives the GsharePlugin's OWN update Flow directly (it IS the GshareUpdateService
    * provider — in the real core the ROB drives it through the same Flow). */
  class GshareWirePlugin extends FiberPlugin {
    val logic = during build new Area {
      val g = host[GsharePlugin]
      val qPc0   = in UInt (32 bits); val qV0 = in Bool ()
      val sV     = in Bool ();        val sD = in Bool ()
      val inval  = in Bool ()
      val uV     = in Bool ()
      val uIdx   = in UInt (11 bits); val uTaken = in Bool ()
      g.logic.queryPc0    := qPc0
      g.logic.queryValid0 := qV0
      g.logic.shiftValid  := sV
      g.logic.shiftDir    := sD
      g.logic.invalidateAll := inval
      // Drive the plugin's own update Flow (override its idle default).
      g.logic.upd.valid        := uV
      g.logic.upd.payload.index := uIdx
      g.logic.upd.payload.taken := uTaken
      val oTaken = out(Bool());        oTaken := g.logic.phtTaken0
      val oIndex = out(UInt(11 bits)); oIndex := g.logic.phtIndex0
      val oGhr   = out(UInt(16 bits)); oGhr   := g.logic.ghr
    }
  }

  class GshareDut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val gsh  = new GsharePlugin
    val wire = new GshareWirePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), gsh, wire)) }
  }

  def init(dut: GshareDut, cd: ClockDomain): Unit = {
    dut.wire.logic.qPc0 #= 0; dut.wire.logic.qV0 #= false
    dut.wire.logic.sV #= false; dut.wire.logic.sD #= false; dut.wire.logic.inval #= false
    dut.wire.logic.uV #= false; dut.wire.logic.uIdx #= 0; dut.wire.logic.uTaken #= false
    cd.waitSampling()
  }

  /** Combinational read: drive the query PC, settle, sample {taken, index}. */
  def read(dut: GshareDut, cd: ClockDomain, pc: Long): (Boolean, Int) = {
    dut.wire.logic.qPc0 #= pc; dut.wire.logic.qV0 #= true
    sleep(1)
    val t = dut.wire.logic.oTaken.toBoolean
    val i = dut.wire.logic.oIndex.toInt
    (t, i)
  }

  /** Retire-train pht[index] toward `taken`. */
  def train(dut: GshareDut, cd: ClockDomain, index: Int, taken: Boolean): Unit = {
    dut.wire.logic.uV #= true
    dut.wire.logic.uIdx #= index
    dut.wire.logic.uTaken #= taken
    cd.waitSampling()
    dut.wire.logic.uV #= false
    cd.waitSampling()
  }

  /** Shift one directional bit into the GHR. */
  def shift(dut: GshareDut, cd: ClockDomain, dir: Boolean): Unit = {
    dut.wire.logic.sV #= true; dut.wire.logic.sD #= dir
    cd.waitSampling()
    dut.wire.logic.sV #= false
    cd.waitSampling()
  }

  test("index = fold(pc) XOR fold(ghr); GHR starts 0", VerilatorTest) {
    SimConfig.withVerilator.compile(new GshareDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      assert(dut.wire.logic.oGhr.toInt == 0, "GHR init 0")
      for (pc <- Seq(0x1000L, 0x2046L, 0x12348L, 0xABCDEL)) {
        val (_, idx) = read(dut, cd, pc)
        assert(idx == idxOf(pc, 0), s"pc=0x${pc.toHexString} HW idx $idx != model ${idxOf(pc, 0)}")
      }
    }
  }

  test("GHR shift folds into the index", VerilatorTest) {
    SimConfig.withVerilator.compile(new GshareDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      // Shift in 1,0,1 -> GHR = 0b101 = 5.
      shift(dut, cd, true); shift(dut, cd, false); shift(dut, cd, true)
      assert(dut.wire.logic.oGhr.toInt == 5, s"GHR ${dut.wire.logic.oGhr.toInt} != 5")
      val pc = 0x3000L
      val (_, idx) = read(dut, cd, pc)
      assert(idx == idxOf(pc, 5), s"HW idx $idx != model ${idxOf(pc, 5)} (ghr=5)")
    }
  }

  test("counter init weakly-taken (2); 2->1 flips not-taken; saturates at 0 and 3", VerilatorTest) {
    SimConfig.withVerilator.compile(new GshareDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      val pc = 0x5000L
      val idx = idxOf(pc, 0)
      assert(read(dut, cd, pc)._1, "init 2 -> taken")
      train(dut, cd, idx, taken = false)              // 2 -> 1
      assert(!read(dut, cd, pc)._1, "1 -> not taken")
      train(dut, cd, idx, taken = false)              // 1 -> 0
      train(dut, cd, idx, taken = false)              // 0 -> 0 (saturate)
      assert(!read(dut, cd, pc)._1, "0 saturates -> not taken")
      train(dut, cd, idx, taken = true)               // 0 -> 1
      assert(!read(dut, cd, pc)._1, "1 -> not taken")
      train(dut, cd, idx, taken = true)               // 1 -> 2
      assert(read(dut, cd, pc)._1, "2 -> taken")
      train(dut, cd, idx, taken = true)               // 2 -> 3
      train(dut, cd, idx, taken = true)               // 3 -> 3 (saturate)
      assert(read(dut, cd, pc)._1, "3 saturates -> taken")
    }
  }

  test("learns the alternation: two GHR contexts at one PC converge to opposite dirs", VerilatorTest) {
    SimConfig.withVerilator.compile(new GshareDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      // A single branch PC whose outcome alternates: after a TAKEN iteration (GHR low
      // bit 1) the next is NOT-taken; after a NOT-taken iteration (GHR low bit 0) the
      // next is TAKEN. Two GHR contexts (ghrA vs ghrB) index two PHT entries; train each
      // toward its (opposite) outcome and confirm the directions diverge.
      val pc = 0x6000L
      val ghrA = 0x0001  // "after taken" context
      val ghrB = 0x0000  // "after not-taken" context
      val idxA = idxOf(pc, ghrA)
      val idxB = idxOf(pc, ghrB)
      assert(idxA != idxB, "the two GHR contexts must map to distinct PHT entries")
      // Train context A strongly NOT-taken, context B strongly TAKEN.
      for (_ <- 0 until 3) { train(dut, cd, idxA, taken = false) }  // -> 0
      for (_ <- 0 until 3) { train(dut, cd, idxB, taken = true) }   // -> 3
      // Read with the GHR set to each context (shift the bits in), confirm opposite dirs.
      // ghrA = 1: shift one '1' from GHR=0.
      shift(dut, cd, true)
      assert(dut.wire.logic.oGhr.toInt == ghrA, "GHR == ghrA")
      assert(!read(dut, cd, pc)._1, "context A (after-taken) predicts NOT-taken")
      // Reset GHR to 0 = ghrB via invalidate.
      dut.wire.logic.inval #= true; cd.waitSampling(); dut.wire.logic.inval #= false; cd.waitSampling()
      assert(dut.wire.logic.oGhr.toInt == ghrB, "GHR == ghrB (0 after invalidate)")
      assert(read(dut, cd, pc)._1, "context B (after-not-taken) predicts TAKEN")
    }
  }
}
