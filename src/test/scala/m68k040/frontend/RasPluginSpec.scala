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
      // branchRepair ports (see RasPlugin's class comment). Driven unconditionally: with
      // the option OFF they are inert wires, so every pre-existing test is unaffected.
      val iRepairValid = in Bool ()
      val iRepairKind  = in UInt (RasRepair.W bits)
      val iRepairData  = in UInt (32 bits)
      ras.logic.repairValid := iRepairValid
      ras.logic.repairKind  := iRepairKind
      ras.logic.repairData  := iRepairData
      val oPredValid  = out(Bool());        oPredValid  := ras.logic.predValid
      val oPredTarget = out(UInt(32 bits)); oPredTarget := ras.logic.predTarget
      val oRasSp = out(UInt(ras.logic.spBits bits)); oRasSp := ras.logic.rasSp
      val oCount = out(UInt(ras.logic.cntBits bits)); oCount := ras.logic.count
    }
  }

  class RasDut(branchRepair: Boolean = false) extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ras  = new RasPlugin(branchRepair = branchRepair)
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
    dut.wire.logic.iRepairValid #= false
    dut.wire.logic.iRepairKind  #= RasRepair.NONE
    dut.wire.logic.iRepairData  #= 0
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

  /** The REDIRECT a mispredicting branch causes, exactly as the wiring layer presents it:
    * `checkpointRestore` and the three `branchRepair` ports are asserted on the SAME cycle
    * (the RAS itself delays the repair by one flop so it reads the restored stack -- see
    * RasPlugin's class comment), then two cycles pass for restore + repair to land. */
  def redirect(dut: RasDut, cd: ClockDomain, kind: Int, data: Long): Unit = {
    dut.wire.logic.iCkRestore   #= true
    dut.wire.logic.iRepairValid #= true
    dut.wire.logic.iRepairKind  #= kind
    dut.wire.logic.iRepairData  #= data
    cd.waitSampling()
    dut.wire.logic.iCkRestore   #= false
    dut.wire.logic.iRepairValid #= false
    cd.waitSampling(2)
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

  // ══════════════════════════════════════════════════════════════════════════════
  //  branchRepair: THE MISPREDICTED CALL'S OWN PUSH (2026-09-27)
  //
  //  The defect these pin is measured, not argued: the flush a mispredicting branch
  //  causes rolls the RAS back to a checkpoint that PREDATES that branch, so when the
  //  branch IS the call its own -- already architectural -- push is reverted and the
  //  callee's `rts` gets no prediction at all. On the `br-ind` probe that is 1024 of
  //  2050 retired mispredicts: EVERY mispredicted call costs TWO.
  //
  //  Each test below states the property at the predictor's own output (`predTarget` IS
  //  what the callee's `rts` will be predicted with), and each FAILS with branchRepair
  //  off -- the first two assert the value the un-repaired RAS cannot produce, and the
  //  idempotence test asserts the stack depth a blind re-push would get wrong.
  // ══════════════════════════════════════════════════════════════════════════════

  test("branchRepair: a mispredicted CALL keeps its own push, so the callee's rts predicts", VerilatorTest) {
    SimConfig.withVerilator.compile(new RasDut(branchRepair = true)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      // An older, correct-path call is already on the stack.
      push(dut, cd, 0xA000)
      // The ROB drains -> the checkpoint is refreshed HERE, i.e. BEFORE the call below.
      // This is the wiring layer's `rob.countIsZero` proxy and it is the whole defect:
      // on this front-end-starved machine the ROB is empty most cycles, so the
      // checkpoint is fresh -- but it is never fresh enough to include the branch the
      // flush is FOR.
      save(dut, cd)
      cd.waitSampling(2)
      // THE CALL: `jsr (%a0)` at 0x1230, so its fall-through (the return address it also
      // pushed on the real stack) is 0x1234. The frontend pushes it at fetch.
      push(dut, cd, 0x1234)
      // Wrong path: the BTB's stale target sent fetch somewhere else, and it fetched
      // another call before the mispredict was discovered.
      push(dut, cd, 0x9999)
      sleep(1)
      assert(predict(dut) == (true, 0x9999L), "the wrong-path push is visible before the redirect")
      // The branch EU resolves the call: wrong target -> redirect. Restore + repair.
      redirect(dut, cd, RasRepair.CALL, 0x1234)
      assert(predict(dut) == (true, 0x1234L),
        s"after a mispredicted CALL the top of stack must be the call's own return address " +
        s"0x1234 (got 0x${predict(dut)._2.toHexString}) -- this IS the callee's rts prediction")
      // And the stack below it is intact, so the CALLER's return still predicts too.
      pop(dut, cd); sleep(1)
      assert(predict(dut) == (true, 0xA000L), "the caller's own return address is still below it")
    }
  }

  test("branchRepair is IDEMPOTENT: a checkpoint that already holds the push is not double-pushed", VerilatorTest) {
    SimConfig.withVerilator.compile(new RasDut(branchRepair = true)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      push(dut, cd, 0xA000)
      push(dut, cd, 0x1234)          // the call's fetch-time push
      save(dut, cd)                  // the ROB drained AFTER it -> the checkpoint HAS it
      cd.waitSampling(2)
      push(dut, cd, 0x9999)          // wrong path
      redirect(dut, cd, RasRepair.CALL, 0x1234)
      val (sp, count) = spCount(dut)
      assert(predict(dut) == (true, 0x1234L), "top of stack is the call's return address")
      assert(count == 2, s"the push must NOT be applied twice (count=$count, want 2) -- a blind " +
        "re-push would leave the stack one deep and move the second mispredict to the CALLER's rts")
      assert(sp == 2, s"sp must match the two live entries (got $sp)")
      pop(dut, cd); sleep(1)
      assert(predict(dut) == (true, 0xA000L), "and the entry below is the caller's, not a duplicate")
    }
  }

  test("branchRepair: a mispredicted RETURN keeps its own pop, so the caller's rts predicts", VerilatorTest) {
    SimConfig.withVerilator.compile(new RasDut(branchRepair = true)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      push(dut, cd, 0xA000)          // the caller's return address
      push(dut, cd, 0x1234)          // the callee's return address
      save(dut, cd)                  // the ROB drains -> checkpoint = both entries
      cd.waitSampling(2)
      // The callee's `rts` is RAS-predicted to 0x1234 and pops. It mispredicts (the real
      // return address on the stack differs -- a stale entry, or a non-LIFO return), so a
      // redirect follows. WITHOUT the repair the restore puts 0x1234 BACK, and the
      // CALLER's later rts then pops the callee's address and mispredicts in its turn.
      pop(dut, cd)
      sleep(1)
      assert(predict(dut) == (true, 0xA000L), "the pop is visible before the redirect")
      redirect(dut, cd, RasRepair.RET, 0x1234)
      val (_, count) = spCount(dut)
      assert(count == 1, s"the return's own pop must survive the restore (count=$count, want 1)")
      assert(predict(dut) == (true, 0xA000L),
        s"the caller's return address must be on top, not the callee's " +
        s"(got 0x${predict(dut)._2.toHexString})")
    }
  }

  test("branchRepair: a redirect for a branch that is NEITHER a call nor a return only restores", VerilatorTest) {
    SimConfig.withVerilator.compile(new RasDut(branchRepair = true)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10); init(dut, cd)
      push(dut, cd, 0xA000)
      save(dut, cd)
      cd.waitSampling(2)
      push(dut, cd, 0x9999)          // wrong-path call, fetched past a mispredicted Bcc
      redirect(dut, cd, RasRepair.NONE, 0)
      val (sp, count) = spCount(dut)
      assert((sp, count) == (1L, 1L), s"kind=NONE must leave the plain restore alone (sp=$sp count=$count)")
      assert(predict(dut) == (true, 0xA000L), "the restored correct-path top is unchanged")
    }
  }
}
