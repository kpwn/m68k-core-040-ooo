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
      val iCkSave    = in Bool ()
      val iCkRestore = in Bool ()
      ras.logic.pushValid        := iPushValid
      ras.logic.pushRetPc        := iPushRetPc
      ras.logic.popValid         := iPopValid
      ras.logic.invalidateAll    := iInval
      ras.logic.checkpointSave   := iCkSave
      ras.logic.checkpointRestore := iCkRestore
      val oPredValid  = out(Bool());        oPredValid  := ras.logic.predValid
      val oPredTarget = out(UInt(32 bits)); oPredTarget := ras.logic.predTarget
      val oRasSp = out(UInt(ras.logic.spBits bits)); oRasSp := ras.logic.rasSp
      val oCount = out(UInt(ras.logic.cntBits bits)); oCount := ras.logic.count
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
    dut.wire.logic.iCkSave    #= false
    dut.wire.logic.iCkRestore #= false
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

  def save(dut: RasDut, cd: ClockDomain): Unit = {
    dut.wire.logic.iCkSave #= true
    cd.waitSampling()
    dut.wire.logic.iCkSave #= false
  }

  def restore(dut: RasDut, cd: ClockDomain): Unit = {
    dut.wire.logic.iCkRestore #= true
    cd.waitSampling()
    dut.wire.logic.iCkRestore #= false
  }

  def spCount(dut: RasDut): (Long, Long) =
    (dut.wire.logic.oRasSp.toLong, dut.wire.logic.oCount.toLong)

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

  // ── Checkpoint/restore (rollback-on-flush fix, 2026-08-28) ─────────────────────
  // The RAS's original design deliberately never checkpointed/restored rasSp/count
  // on a flush ("ACCEPT CORRUPTION" — see Ras.scala's old doc comment). These tests
  // exercise the fix directly at the RAS-unit level: checkpointSave snapshots
  // rasSp/count/contents; checkpointRestore undoes anything speculative since.

  test("checkpointRestore undoes wrong-path pushes since the last checkpointSave", VerilatorTest) {
    SimConfig.withVerilator.compile(new RasDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      // Correct-path: one real call.
      push(dut, cd, 0x1000)
      sleep(1)
      assert(predict(dut) == (true, 0x1000L), "correct-path push visible before checkpoint")
      // Checkpoint here (mirrors the wiring layer's "ROB fully drained" refresh).
      save(dut, cd)
      val (spBefore, countBefore) = { sleep(1); spCount(dut) }
      // Wrong-path speculative excursion: two more pushes that never should have
      // architecturally happened (e.g. wrong-path BSR instructions fetched down a
      // mispredicted branch).
      push(dut, cd, 0x2000)
      push(dut, cd, 0x3000)
      sleep(1)
      assert(predict(dut) == (true, 0x3000L), "wrong-path pushes ARE visible to prediction before restore")
      // The misprediction is discovered (a flush fires): restore.
      restore(dut, cd)
      sleep(1)
      val (spAfter, countAfter) = spCount(dut)
      assert((spAfter, countAfter) == (spBefore, countBefore),
        s"restore must reproduce the exact pre-excursion sp/count (got sp=$spAfter count=$countAfter, " +
        s"want sp=$spBefore count=$countBefore)")
      assert(predict(dut) == (true, 0x1000L),
        "post-restore prediction must be the correct-path top (0x1000), not a wrong-path entry")
      // And the correct path can keep predicting accurately from here — this is the
      // "measurably improves post-flush prediction quality" bar, not just
      // "eventually corrected by the EU": a real return now predicts right away.
      pop(dut, cd)
      sleep(1)
      assert(!predict(dut)._1, "after popping the one real entry the RAS is correctly empty again")
    }
  }

  test("checkpointRestore undoes wrong-path pops since the last checkpointSave", VerilatorTest) {
    SimConfig.withVerilator.compile(new RasDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      push(dut, cd, 0x1000)
      push(dut, cd, 0x2000)
      push(dut, cd, 0x3000)
      sleep(1)
      save(dut, cd)
      // Wrong-path speculative returns pop entries that a correct-path successor still needs.
      pop(dut, cd)
      pop(dut, cd)
      sleep(1)
      assert(predict(dut) == (true, 0x1000L), "two wrong-path pops expose the wrong (older) entry")
      restore(dut, cd)
      sleep(1)
      assert(predict(dut) == (true, 0x3000L), "restore undoes the wrong-path pops -> top is 0x3000 again")
    }
  }

  test("checkpoint captures full CONTENT, not just the pointer -- survives a wrong-path wraparound", VerilatorTest) {
    // A naive pointer/count-only checkpoint is unsound if wrong-path speculation
    // pushes more times than the RAS has entries: the wraparound overwrites a slot
    // the restored pointer still needs. This test drives exactly that shape: a
    // checkpoint is taken with one real entry at the bottom of the stack, then a
    // wrong-path excursion pushes `depth` MORE entries (a full wraparound, clobbering
    // every slot including the one the checkpoint's rasSp-1 points at) before restore.
    SimConfig.withVerilator.compile(new RasDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      val depth = 16
      push(dut, cd, 0x1000)
      sleep(1)
      save(dut, cd)
      // Wrong-path: push `depth` more (a full circular wraparound over every slot,
      // including the one holding the checkpointed 0x1000).
      for (i <- 0 until depth) push(dut, cd, 0x9000 + i)
      sleep(1)
      assert(predict(dut)._2 == (0x9000 + depth - 1),
        "sanity: the wraparound's last push is visible before restore")
      restore(dut, cd)
      sleep(1)
      // A pointer-only restore would reproduce the correct sp/count but read back
      // whatever the wraparound clobbered that slot with (0x9000+depth-1's neighbour),
      // NOT the real 0x1000 -- silently mispredicting forever after. The full-content
      // checkpoint must reproduce the exact original value.
      assert(predict(dut) == (true, 0x1000L),
        "content checkpoint must survive a full wrong-path wraparound: top must be the real 0x1000, " +
        s"not a wraparound-clobbered value (got ${predict(dut)})")
    }
  }

  test("checkpointSave with nothing outstanding is a safe no-op (no drift on repeated saves)", VerilatorTest) {
    SimConfig.withVerilator.compile(new RasDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      push(dut, cd, 0x1000)
      push(dut, cd, 0x2000)
      sleep(1)
      save(dut, cd); save(dut, cd); save(dut, cd)
      sleep(1)
      assert(predict(dut) == (true, 0x2000L), "repeated saves with no intervening push/pop do not corrupt state")
      restore(dut, cd)
      sleep(1)
      assert(predict(dut) == (true, 0x2000L), "restoring the same steady-state checkpoint is a no-op")
    }
  }
}
