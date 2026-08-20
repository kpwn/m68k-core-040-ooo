package m68k040.rob

import m68k040.{M68kParams, M68kSim}
import m68k040.core.ParamPlugin
import m68k040.decode.DecOp
import m68k040.rename.RenamedUop
import m68k040.isa.{Cluster, Size}
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Task 2: ROB per-entry fault capture.
  *
  * Each ROB entry carries {faulted, faultVector} (set at alloc from the renamed
  * uop). At retire of a faulted HEAD entry the ROB signals an exception-pending
  * pulse with the vector + the faulting instruction's PC — mirroring the branch
  * retireAlone + mispredict-redirect pattern. The faulted entry is `retireAlone`
  * (a fault is precise: it must be the head, alone) and does NOT drive a normal
  * commit (no result writeback) — the exception replaces its retire.
  */
class RobFaultSpec extends AnyFunSuite {

  class SimpleDut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val rsrc = new RenameUopSourcePlugin
    val drv  = new RobAllocDriverPlugin
    val rob  = new RobPlugin
    val csink = new RenameCommitSinkPlugin
    val tsink = new CommitTraceSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), rsrc, drv, rob, csink, tsink)) }
  }

  def pokeRu(
      u: RenamedUop,
      valid: Boolean = true,
      pc: Long = 0,
      dstArch: Int = 0,
      pdst: Int = 0, pdstValid: Boolean = false, pdstOld: Int = 0,
      isBranch: Boolean = false,
      faulted: Boolean = false, faultVector: Int = 0
  ): Unit = {
    u.valid #= valid
    u.pc #= pc
    u.nextPc #= pc + 2
    u.faultUsesNextPc #= false
    u.op #= DecOp.MOVE
    u.cluster #= Cluster.INT
    u.size #= Size.LONG
    u.useImm #= false; u.imm #= 0
    u.isBranch #= isBranch; u.cond #= 0; u.branchDisp #= 0
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
  }

  def initSimple(dut: SimpleDut, cd: ClockDomain): Unit = {
    dut.rsrc.logic.src.valid #= false
    dut.rsrc.logic.u1v #= false
    dut.rob.logic.flush.valid #= false
    for (c <- dut.rob.logic.completion) { c.valid #= false; c.payload #= 0 }
    dut.rob.logic.branchCompletion.valid #= false
    pokeRu(dut.rsrc.logic.src.payload(0))
    pokeRu(dut.rsrc.logic.src.payload(1))
    cd.waitSampling(3)
  }

  def markComplete(dut: SimpleDut, robId: Int): Unit = {
    dut.rob.logic.completion(0).valid #= true
    dut.rob.logic.completion(0).payload #= robId
  }
  def clearComplete(dut: SimpleDut): Unit = dut.rob.logic.completion(0).valid #= false

  test("faulted entry: at retire signals exceptionPending with vector + faultPc, no normal commit") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      // alloc a single faulted uop (illegal -> vector 4) at pc 0x300.
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x300, dstArch = 2, pdst = 20,
             pdstValid = true, pdstOld = 2, faulted = true, faultVector = 4)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()

      // complete robId 0
      markComplete(dut, 0)
      cd.waitSampling()
      clearComplete(dut)

      // exceptionPending must pulse with vector=4, pc=0x300 (the FAULTING pc, not nextPc).
      var seen = false
      var n = 0
      while (!seen && n < 50) {
        if (dut.rob.logic.exceptionPending.toBoolean) {
          seen = true
          assert(dut.rob.logic.exceptionVector.toInt == 4, s"vector=${dut.rob.logic.exceptionVector.toInt}")
          assert(dut.rob.logic.exceptionPc.toLong == 0x300L, f"excPc=0x${dut.rob.logic.exceptionPc.toLong}%x")
          // a faulted entry does NOT drive a normal commit (no result writeback)
          assert(!dut.csink.logic.commitValidOut(0).toBoolean, "faulted entry must NOT commit normally")
        }
        n += 1; cd.waitSampling()
      }
      assert(seen, "exceptionPending never pulsed at faulted retire")
    }
  }

  test("non-faulted entry retires normally, no exceptionPending") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x100, dstArch = 3, pdst = 20,
             pdstValid = true, pdstOld = 3)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()
      markComplete(dut, 0)
      cd.waitSampling()
      clearComplete(dut)

      var n = 0; var committed = false
      while (!committed && n < 50) {
        if (dut.tsink.logic.fireOut(0).toBoolean) {
          committed = true
          assert(!dut.rob.logic.exceptionPending.toBoolean, "no exception for a normal entry")
          assert(dut.csink.logic.commitValidOut(0).toBoolean, "normal entry commits")
        }
        n += 1; cd.waitSampling()
      }
      assert(committed, "normal entry never retired")
    }
  }

  test("faulted entry behind an older normal entry: older commits first, then exception") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      // slot0 normal (robId 0), slot1 faulted (robId 1, vector 8 / privilege)
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x100, dstArch = 1, pdst = 20, pdstValid = true, pdstOld = 1)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0x102, dstArch = 0, pdstValid = false,
             faulted = true, faultVector = 8)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      cd.waitSampling()

      // complete both (1 first, then 0)
      markComplete(dut, 1); cd.waitSampling()
      dut.rob.logic.completion(0).payload #= 0; cd.waitSampling()
      clearComplete(dut)

      // First: robId 0 commits normally (NOT alongside the faulted entry — fault is retireAlone).
      cd.waitSamplingWhere(dut.tsink.logic.fireOut(0).toBoolean)
      assert(dut.csink.logic.commitValidOut(0).toBoolean, "older normal entry commits")
      assert(!dut.rob.logic.exceptionPending.toBoolean, "no exception while the NORMAL head retires")

      // Then: the faulted entry reaches the head -> exceptionPending vector 8 pc 0x102.
      var seen = false; var n = 0
      while (!seen && n < 50) {
        if (dut.rob.logic.exceptionPending.toBoolean) {
          seen = true
          assert(dut.rob.logic.exceptionVector.toInt == 8, s"vector=${dut.rob.logic.exceptionVector.toInt}")
          assert(dut.rob.logic.exceptionPc.toLong == 0x102L, f"excPc=0x${dut.rob.logic.exceptionPc.toLong}%x")
        }
        n += 1; cd.waitSampling()
      }
      assert(seen, "exceptionPending never pulsed for the younger faulted entry")
    }
  }
}
