package m68k040.frontend

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Directed unit test of RasPlugin `branchSnapshot` (`CPU_RAS_BRANCH_SNAPSHOT`): the
  * per-branch pointer + top-of-stack restore that replaces the ROB-drain checkpoint on a
  * Tier-1 redirect. The full-core effect is measured by `RasBranchSnapshotIpcSpec`. */
class RasBranchSnapshotSpec extends AnyFunSuite {

  class SnapWirePlugin extends FiberPlugin {
    val logic = during build new Area {
      val ras = host[RasPlugin]
      val iPushValid = in Bool ()
      val iPushRetPc = in UInt (32 bits)
      val iPopValid  = in Bool ()
      val iCkSave    = in Bool ()
      val iCkRestore = in Bool ()
      val iSnapApply = in Bool ()
      val iSnapSp    = in UInt (4 bits)
      val iSnapCount = in UInt (5 bits)
      val iSnapTos   = in UInt (32 bits)
      ras.logic.pushValid         := iPushValid
      ras.logic.pushRetPc         := iPushRetPc
      ras.logic.popValid          := iPopValid
      ras.logic.checkpointSave    := iCkSave
      ras.logic.checkpointRestore := iCkRestore
      ras.logic.snapApply := iSnapApply
      ras.logic.snapSp    := iSnapSp
      ras.logic.snapCount := iSnapCount
      ras.logic.snapTos   := iSnapTos
      val oPredValid  = out(Bool());        oPredValid  := ras.logic.predValid
      val oPredTarget = out(UInt(32 bits)); oPredTarget := ras.logic.predTarget
      val oLiveValid  = out(Bool());        oLiveValid  := ras.liveSnapshot.valid
      val oLiveSp     = out(UInt(4 bits));  oLiveSp     := ras.liveSnapshot.sp
      val oLiveCount  = out(UInt(5 bits));  oLiveCount  := ras.liveSnapshot.count
      val oLiveTos    = out(UInt(32 bits)); oLiveTos    := ras.liveSnapshot.tos
    }
  }

  class SnapDut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ras  = new RasPlugin(branchRepair = true, branchSnapshot = true)
    val wire = new SnapWirePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ras, wire)) }
  }

  private def w(d: SnapDut) = d.wire.logic

  def init(d: SnapDut, cd: ClockDomain): Unit = {
    w(d).iPushValid #= false; w(d).iPushRetPc #= 0; w(d).iPopValid #= false
    w(d).iCkSave #= false; w(d).iCkRestore #= false
    w(d).iSnapApply #= false; w(d).iSnapSp #= 0; w(d).iSnapCount #= 0; w(d).iSnapTos #= 0
    cd.waitSampling()
  }
  def push(d: SnapDut, cd: ClockDomain, pc: Long): Unit = {
    w(d).iPushValid #= true; w(d).iPushRetPc #= pc; cd.waitSampling(); w(d).iPushValid #= false
  }
  def pop(d: SnapDut, cd: ClockDomain): Unit = {
    w(d).iPopValid #= true; cd.waitSampling(); w(d).iPopValid #= false
  }
  def top(d: SnapDut): (Boolean, Long) = (w(d).oPredValid.toBoolean, w(d).oPredTarget.toLong)
  case class Snap(sp: Int, count: Int, tos: Long)
  def live(d: SnapDut): Snap = {
    assert(w(d).oLiveValid.toBoolean)
    Snap(w(d).oLiveSp.toInt, w(d).oLiveCount.toInt, w(d).oLiveTos.toLong)
  }
  def driveSnap(d: SnapDut, s: Snap): Unit = {
    w(d).iSnapSp #= s.sp; w(d).iSnapCount #= s.count; w(d).iSnapTos #= s.tos
  }

  test("snapshot restores pointer+TOS and keeps the live entries below it", VerilatorTest) {
    SimConfig.withVerilator.compile(new SnapDut).doSim { d =>
      val cd = d.clockDomain; cd.forkStimulus(10); init(d, cd)
      // A checkpoint taken on an EMPTY stack: the ROB-drain proxy, maximally stale.
      w(d).iCkSave #= true; cd.waitSampling(); w(d).iCkSave #= false; cd.waitSampling()
      push(d, cd, 0x1000); push(d, cd, 0x2000); push(d, cd, 0x3000)
      sleep(1)
      val s = live(d)   // the state a branch fetched here would carry
      assert(s == Snap(3, 3, 0x3000), s"live snapshot $s")
      // Wrong path: a return then a call clobbers the TOS slot, plus one more push.
      pop(d, cd); push(d, cd, 0xBAD0); push(d, cd, 0xBAD1)
      sleep(1); assert(top(d) == (true, 0xBAD1L))
      // Tier-1 redirect with a snapshot: snapApply only (the wiring withholds the restore).
      driveSnap(d, s); w(d).iSnapApply #= true; cd.waitSampling(); w(d).iSnapApply #= false
      sleep(1)
      assert(top(d) == (true, 0x3000L), f"TOS after snapshot = ${top(d)._2}%x")
      pop(d, cd); sleep(1); assert(top(d) == (true, 0x2000L), "entry below TOS must survive")
      pop(d, cd); sleep(1); assert(top(d) == (true, 0x1000L))
      pop(d, cd); sleep(1); assert(!top(d)._1, "stack must now be empty")
    }
  }

  test("a wrong-path push on the apply cycle cannot land on the restored TOS", VerilatorTest) {
    SimConfig.withVerilator.compile(new SnapDut).doSim { d =>
      val cd = d.clockDomain; cd.forkStimulus(10); init(d, cd)
      push(d, cd, 0x1000); push(d, cd, 0x2000)
      sleep(1); val s = live(d)
      pop(d, cd)                       // rasSp now points at the TOS slot of `s`
      driveSnap(d, s)
      w(d).iSnapApply #= true; w(d).iPushValid #= true; w(d).iPushRetPc #= 0xBAD0
      cd.waitSampling()
      w(d).iSnapApply #= false; w(d).iPushValid #= false
      cd.waitSampling(2); sleep(1)
      assert(top(d) == (true, 0x2000L), f"parked wrong-path push clobbered TOS: ${top(d)._2}%x")
      pop(d, cd); sleep(1); assert(top(d) == (true, 0x1000L))
    }
  }

  test("a same-cycle checkpoint restore wins over the snapshot", VerilatorTest) {
    SimConfig.withVerilator.compile(new SnapDut).doSim { d =>
      val cd = d.clockDomain; cd.forkStimulus(10); init(d, cd)
      push(d, cd, 0x1000)
      w(d).iCkSave #= true; cd.waitSampling(); w(d).iCkSave #= false; cd.waitSampling()
      push(d, cd, 0x2000); sleep(1); val s = live(d)
      driveSnap(d, s); w(d).iSnapApply #= true; w(d).iCkRestore #= true
      cd.waitSampling()
      w(d).iSnapApply #= false; w(d).iCkRestore #= false
      sleep(1)
      assert(top(d) == (true, 0x1000L), "an older Tier-2 flush's checkpoint restore must win")
      pop(d, cd); sleep(1); assert(!top(d)._1)
    }
  }
}
