package m68k040.frontend

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.services.{BtbUpdateService, BtbUpdate}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Directed BtbPlugin unit test (fetch-time predictor, slice 1).
  * Cold miss -> predict-not-taken; learn -> warm hit predicts taken->target;
  * counter saturation (2 not-taken resolves to flip a learned cond branch);
  * tag mismatch -> no false hit; indirect learns -> mispredicts a changed target
  * -> re-learns. */
class BtbPluginSpec extends AnyFunSuite {

  /** Test-side BtbUpdate provider: a pokeable Flow exposed via BtbUpdateService. */
  class BtbUpdateDriverPlugin extends FiberPlugin with BtbUpdateService {
    val logic = during build new Area {
      // Declare the update Flow as top-level IO so the test pokes it directly (no
      // internal driver -> no double-drive). valid + every payload field are inputs.
      val upd = Flow(BtbUpdate())
      in(upd.valid)
      upd.payload.flatten.foreach(in(_))
    }
    override def btbUpdate: Flow[BtbUpdate] = logic.upd
  }

  /** Wiring plugin: hoists the BtbPlugin's plain-wire ports to top IO INSIDE a Fiber
    * build (the IcachePlugin/FetchAlign convention — a sibling plugin drives the
    * directionless ports; doing it in the raw Component body deadlocks the Fiber). */
  class BtbWirePlugin extends FiberPlugin {
    val logic = during build new Area {
      val btb = host[BtbPlugin]
      val qPc    = in UInt (32 bits)
      val qValid = in Bool ()
      val inval  = in Bool ()
      btb.logic.queryPc       := qPc
      btb.logic.queryValid    := qValid
      btb.logic.invalidateAll := inval
      // Combinational lookup outputs (port 0): predict-taken + target this cycle.
      val oPredValid  = out(Bool());        oPredValid  := btb.logic.predTakenComb
      val oPredTarget = out(UInt(32 bits)); oPredTarget := btb.logic.predTargetComb
      val oPredPc     = out(UInt(32 bits)); oPredPc     := qPc
    }
  }

  class BtbDut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val drv  = new BtbUpdateDriverPlugin
    val btb  = new BtbPlugin
    val wire = new BtbWirePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), drv, btb, wire)) }
  }

  /** Issue a lookup for `pc` and return the REGISTERED prediction one cycle later. */
  def lookup(dut: BtbDut, cd: ClockDomain, pc: Long): (Boolean, Long, Long) = {
    // Combinational lookup: drive qPc/qValid, let the async-read + tag-decode settle,
    // sample the prediction THIS cycle (no register delay).
    dut.wire.logic.qPc #= pc; dut.wire.logic.qValid #= true
    sleep(1); cd.waitSampling()        // settle the combinational read
    val pv = dut.wire.logic.oPredValid.toBoolean
    val pt = dut.wire.logic.oPredTarget.toLong
    val pp = dut.wire.logic.oPredPc.toLong
    dut.wire.logic.qValid #= false
    (pv, pt, pp)
  }

  /** Retire-update the BTB for a branch at `pc` (taken/target/brType). */
  def update(dut: BtbDut, cd: ClockDomain, pc: Long, taken: Boolean, target: Long, brType: Int): Unit = {
    dut.drv.logic.upd.valid #= true
    dut.drv.logic.upd.payload.pc #= pc
    dut.drv.logic.upd.payload.taken #= taken
    dut.drv.logic.upd.payload.target #= target
    dut.drv.logic.upd.payload.brType #= brType
    cd.waitSampling()
    dut.drv.logic.upd.valid #= false
    cd.waitSampling()                  // let the registered valids/mem write settle
  }

  def init(dut: BtbDut, cd: ClockDomain): Unit = {
    dut.wire.logic.qValid #= false; dut.wire.logic.qPc #= 0; dut.wire.logic.inval #= false
    dut.drv.logic.upd.valid #= false
    cd.waitSampling()
  }

  test("cold miss -> predict not-taken", VerilatorTest) {
    SimConfig.withVerilator.compile(new BtbDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      val (pv, _, _) = lookup(dut, cd, 0x1000)
      assert(!pv, "cold (never-learned) PC must predict not-taken")
    }
  }

  test("learn -> warm hit predicts taken->target (cond, weak then strong)", VerilatorTest) {
    SimConfig.withVerilator.compile(new BtbDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      // A conditional branch resolves TAKEN once: allocate counter:=2 (weakly taken).
      update(dut, cd, 0x2000, taken = true, target = 0x2040, brType = 0)
      val (pv1, pt1, pp1) = lookup(dut, cd, 0x2000)
      assert(pv1, "after one taken resolve a cond branch must predict taken (ctr=2)")
      assert(pt1 == 0x2040, s"predicted target 0x${pt1.toHexString} != 0x2040")
      assert(pp1 == 0x2000, "predBranchPc must echo the queried PC")
      // A second taken bumps to 3 (still taken).
      update(dut, cd, 0x2000, taken = true, target = 0x2040, brType = 0)
      val (pv2, _, _) = lookup(dut, cd, 0x2000)
      assert(pv2, "ctr saturated at 3 still predicts taken")
    }
  }

  test("counter saturation: 2 not-taken resolves flip a learned cond branch", VerilatorTest) {
    SimConfig.withVerilator.compile(new BtbDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      // Learn strongly-taken (taken x2 -> ctr=3).
      update(dut, cd, 0x3000, taken = true, target = 0x3080, brType = 0)
      update(dut, cd, 0x3000, taken = true, target = 0x3080, brType = 0)
      assert(lookup(dut, cd, 0x3000)._1, "ctr=3 predicts taken")
      // One not-taken: 3 -> 2, still taken (bimodal hysteresis).
      update(dut, cd, 0x3000, taken = false, target = 0x3080, brType = 0)
      assert(lookup(dut, cd, 0x3000)._1, "ctr=2 still predicts taken (one not-taken)")
      // Second not-taken: 2 -> 1, now NOT taken.
      update(dut, cd, 0x3000, taken = false, target = 0x3080, brType = 0)
      assert(!lookup(dut, cd, 0x3000)._1, "ctr=1 predicts NOT taken (two not-taken flips)")
    }
  }

  test("unconditional pegs taken on first install", VerilatorTest) {
    SimConfig.withVerilator.compile(new BtbDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      // brType=1 (uncond): allocation pegs ctr:=3 and predicts taken immediately.
      update(dut, cd, 0x4000, taken = true, target = 0x40C0, brType = 1)
      val (pv, pt, _) = lookup(dut, cd, 0x4000)
      assert(pv && pt == 0x40C0, "uncond predicts taken to target on first install")
    }
  }

  test("tag mismatch -> no false hit", VerilatorTest) {
    SimConfig.withVerilator.compile(new BtbDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      // Learn at 0x5000.
      update(dut, cd, 0x5000, taken = true, target = 0x5040, brType = 0)
      assert(lookup(dut, cd, 0x5000)._1, "0x5000 hits")
      // A different PC that maps to the SAME index but a DIFFERENT tag must miss.
      // index uses pc[1+idxBits downto 2]; add 2^(2+idxBits) to flip a tag bit while
      // keeping the index. idxBits = log2(128) = 7 -> tag flip at bit (2+7)=... use a
      // large stride that preserves index bits [8:2] but changes tag bits [31:9].
      val aliasPc = 0x5000 + (1 << 16)
      assert((aliasPc & 0x1FE) == (0x5000 & 0x1FE), "alias must share the index field")
      assert(!lookup(dut, cd, aliasPc)._1, "tag-mismatched alias must NOT false-hit")
    }
  }

  test("indirect learns -> mispredict-changed-target re-learns", VerilatorTest) {
    SimConfig.withVerilator.compile(new BtbDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      // JMP (An): uncond indirect, learns last-seen target.
      update(dut, cd, 0x6000, taken = true, target = 0x7000, brType = 1)
      val (pv1, pt1, _) = lookup(dut, cd, 0x6000)
      assert(pv1 && pt1 == 0x7000, "indirect predicts last-seen target 0x7000")
      // Next time the same indirect resolves to a DIFFERENT target -> re-learn it.
      update(dut, cd, 0x6000, taken = true, target = 0x8000, brType = 1)
      val (pv2, pt2, _) = lookup(dut, cd, 0x6000)
      assert(pv2 && pt2 == 0x8000, "indirect re-learns the new target 0x8000")
    }
  }

  test("invalidateAll clears learned entries", VerilatorTest) {
    SimConfig.withVerilator.compile(new BtbDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      update(dut, cd, 0x9000, taken = true, target = 0x9040, brType = 1)
      assert(lookup(dut, cd, 0x9000)._1, "learned before invalidate")
      dut.wire.logic.inval #= true; cd.waitSampling(); dut.wire.logic.inval #= false; cd.waitSampling()
      assert(!lookup(dut, cd, 0x9000)._1, "invalidateAll -> cold miss again")
    }
  }
}
