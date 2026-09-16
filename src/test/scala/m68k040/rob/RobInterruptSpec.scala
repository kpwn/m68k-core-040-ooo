package m68k040.rob

import m68k040.{M68kParams, M68kSim}
import m68k040.core.ParamPlugin
import m68k040.decode.DecOp
import m68k040.exception.InterruptControlPlugin
import m68k040.rename.RenamedUop
import m68k040.isa.{Cluster, Size}
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Task 3: interrupt recognition at a macro-instruction boundary.
  *
  * interruptPending = (iplIn > srSys[2:0] || iplIn==7) && firstStore(head) &&
  *                    !faulted(head) && !rte(head) && excIdle && head present.
  * The head must NOT commit when interruptPending; the level + vector + head
  * instruction PC are captured for the exception entry.
  */
class RobInterruptSpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val intCtrl = new InterruptControlPlugin
    val rsrc = new RenameUopSourcePlugin
    val drv  = new RobAllocDriverPlugin
    val rob  = new RobPlugin
    val csink = new RenameCommitSinkPlugin
    val tsink = new CommitTraceSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), intCtrl, rsrc, drv, rob, csink, tsink)) }
  }

  def pokeRu(
      u: RenamedUop,
      valid: Boolean = true,
      pc: Long = 0,
      dstArch: Int = 0,
      pdst: Int = 0, pdstValid: Boolean = false, pdstOld: Int = 0,
      isBranch: Boolean = false,
      faulted: Boolean = false, faultVector: Int = 0,
      firstOfInstr: Boolean = true
  ): Unit = {
    u.valid #= valid
    u.pc #= pc
    u.lenWords #= 1
    u.faultUsesNextPc #= false
    u.op #= DecOp.MOVE
    u.cluster #= Cluster.INT
    u.size #= Size.LONG
    u.useImm #= false; u.imm #= 0
    u.isBranch #= isBranch; u.cond #= 0
    u.unimplemented #= false
    u.dstArch #= dstArch
    u.psrcA #= 0; u.psrcAValid #= false
    u.psrcB #= 0; u.psrcBValid #= false
    u.pdst #= pdst; u.pdstValid #= pdstValid; u.pdstOld #= pdstOld
    u.pNzvcSrc #= 0; u.readsNzvc #= false
    u.pNzvcDst #= 0; u.writesNzvc #= false; u.pNzvcOld #= 0
    u.pXSrc #= 0; u.readsX #= false
    u.pXDst #= 0; u.writesX #= false; u.pXOld #= 0
    u.faulted #= faulted
    u.faultVector #= faultVector
    u.isRte #= false
    u.debugBreakValid #= false; u.debugBreakSlot #= 0
    u.sysOp #= false; u.sysKind #= m68k040.decode.SysKind.NONE; u.sysReadDir #= false
    u.isCondTrap #= false
    u.sswInstr #= false
    u.firstOfInstr #= firstOfInstr
  }

  def init(dut: Dut, cd: ClockDomain): Unit = {
    dut.rsrc.logic.src.valid #= false
    dut.rsrc.logic.u1v #= false
    dut.rob.logic.flush.valid #= false
    for (c <- dut.rob.logic.completion) { c.valid #= false; c.payload #= 0 }
    dut.rob.logic.branchCompletion.valid #= false
    dut.intCtrl.logic.iplIn #= 0
    dut.intCtrl.logic.iackAvec #= false
    dut.intCtrl.logic.iackVector #= 0
    pokeRu(dut.rsrc.logic.src.payload(0))
    pokeRu(dut.rsrc.logic.src.payload(1))
    cd.waitSampling(3)
  }

  /** Alloc a single uop and return when it is allocated (NOT marked complete). */
  def allocOne(dut: Dut, cd: ClockDomain, pc: Long, firstOfInstr: Boolean = true,
               faulted: Boolean = false, faultVector: Int = 0): Unit = {
    pokeRu(dut.rsrc.logic.src.payload(0), pc = pc, firstOfInstr = firstOfInstr,
           faulted = faulted, faultVector = faultVector)
    dut.rsrc.logic.src.valid #= true
    dut.rsrc.logic.u1v #= false
    cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
    dut.rsrc.logic.src.valid #= false
    cd.waitSampling()
  }

  // SR boot system byte: srSys reset = 0x27 (S=1, I=7, T=0). For these tests we set
  // the I-mask explicitly via the SystemState srSys reg.
  def setMask(dut: Dut, cd: ClockDomain, mask: Int): Unit = {
    // srSys[2:0] = mask, keep S=1 (bit5), T=0.
    dut.rob.logic.exc.ss.srSys #= (0x20 | (mask & 0x7))
    cd.waitSampling()
  }

  /** Poll up to `n` cycles for interruptPending to pulse; return its sampled
    * {level, vec, pc} on the firing cycle. interruptPending is a 1-cycle pulse (the
    * exc-FSM goes active next cycle and re-suppresses it), so a fixed-cycle check is
    * backend/seed-timing-flaky -- poll instead. Also asserts the head did not commit
    * on the firing cycle. */
  def awaitPending(dut: Dut, cd: ClockDomain, n: Int = 8): Option[(Int, Int, Long)] = {
    var k = 0
    while (k < n) {
      if (dut.rob.logic.interruptPending.toBoolean) {
        assert(!dut.csink.logic.commitValidOut(0).toBoolean, "head must NOT commit on interrupt")
        return Some((dut.rob.logic.interruptLevel.toInt, dut.rob.logic.interruptVec.toInt,
                     dut.rob.logic.interruptPc.toLong & 0xffffffffL))
      }
      cd.waitSampling(); k += 1
    }
    None
  }

  /** Assert interruptPending NEVER pulses across `n` cycles (masked / not-first / faulted). */
  def assertNeverPending(dut: Dut, cd: ClockDomain, n: Int = 8): Unit = {
    var k = 0
    while (k < n) {
      assert(!dut.rob.logic.interruptPending.toBoolean, "interruptPending must NOT fire")
      cd.waitSampling(); k += 1
    }
  }

  test("ipl>mask at a first-uop head -> interruptPending, head not committed, captures") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)
      setMask(dut, cd, 2)
      allocOne(dut, cd, pc = 0x400)
      // Raise IPL=3 > mask=2, autovector.
      dut.intCtrl.logic.iplIn #= 3
      dut.intCtrl.logic.iackAvec #= true
      val p = awaitPending(dut, cd)
      assert(p.isDefined, "interruptPending must fire (ipl>mask)")
      val (level, vec, pc) = p.get
      assert(level == 3, s"level=$level")
      assert(vec == 24 + 3, s"avec vec=$vec")
      assert(pc == 0x400L, f"intPc=0x$pc%x")
    }
  }

  test("ipl==mask -> NOT pending") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)
      setMask(dut, cd, 2)
      allocOne(dut, cd, pc = 0x400)
      dut.intCtrl.logic.iplIn #= 2  // equal -> masked
      dut.intCtrl.logic.iackAvec #= true
      assertNeverPending(dut, cd)
    }
  }

  test("NMI (ipl==7) through mask=7 -> pending") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)
      setMask(dut, cd, 7)
      allocOne(dut, cd, pc = 0x500)
      dut.intCtrl.logic.iplIn #= 7  // NMI: 7>7 is false, but NMI always recognized
      dut.intCtrl.logic.iackAvec #= true
      val p = awaitPending(dut, cd)
      assert(p.isDefined, "NMI must be recognized through mask=7")
      val (level, vec, _) = p.get
      assert(level == 7, "NMI level 7")
      assert(vec == 24 + 7, "NMI autovector 31")
    }
  }

  test("vectored: iackAvec=0 -> curVec = iackVector") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)
      setMask(dut, cd, 1)
      allocOne(dut, cd, pc = 0x600)
      dut.intCtrl.logic.iplIn #= 4
      dut.intCtrl.logic.iackAvec #= false
      dut.intCtrl.logic.iackVector #= 0x45
      val p = awaitPending(dut, cd)
      assert(p.isDefined, "ipl>mask must be pending")
      assert(p.get._2 == 0x45, s"vectored vec=${p.get._2}")
    }
  }

  test("mid-cracked-instruction head (firstOfInstr=false) -> NOT pending") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)
      setMask(dut, cd, 2)
      allocOne(dut, cd, pc = 0x700, firstOfInstr = false)
      dut.intCtrl.logic.iplIn #= 5  // > mask
      dut.intCtrl.logic.iackAvec #= true
      assertNeverPending(dut, cd)
    }
  }

  test("faulted head -> fault has priority, NOT interruptPending") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)
      setMask(dut, cd, 2)
      allocOne(dut, cd, pc = 0x800, faulted = true, faultVector = 4)
      dut.intCtrl.logic.iplIn #= 5
      dut.intCtrl.logic.iackAvec #= true
      assertNeverPending(dut, cd)
    }
  }
}
