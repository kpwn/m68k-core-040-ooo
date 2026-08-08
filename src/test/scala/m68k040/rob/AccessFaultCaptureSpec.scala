package m68k040.rob

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.DcachePlugin
import m68k040.decode.DecOp
import m68k040.execute.LsEuPlugin
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.isa.{Cluster, MemOp, Size}
import m68k040.ls.{DFaultingTranslationPlugin, LsEuSourcePlugin, BehavioralMemAgent}
import m68k040.rename.RenamedUop
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Task 1: capture an MMU access fault (vector 2 + faultAddr + SSW attrs).
  *
  * Two halves:
  *  - LS EU: an LS access that takes a DTLB rsp.fault emits faultCompletion
  *    {robId, faultAddr=VA, write, sizeBits, supervisor} (and still completes so
  *    the entry can retire) and writes NO register.
  *  - ROB: a driven lsFaultCompletion flags the entry faulted vector 2 + records
  *    faultAddr/SSW attrs; at retire it raises exceptionPending vector 2 with the
  *    faulting PC + faultAddr.
  */
class AccessFaultCaptureSpec extends AnyFunSuite {

  // ── LS-EU half ──────────────────────────────────────────────────────────────
  class LsDut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val rfX    = new RegFilePluginX     // LS EU now writes X for RTR CCR-restore
    val xlate  = new DFaultingTranslationPlugin
    val dcache = new DcachePlugin
    val eu     = new LsEuPlugin
    val src    = new LsEuSourcePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, rfInt, rfNzvc, rfX, xlate, dcache, eu, src)) }
  }

  test("LS EU: a faulting store emits faultCompletion (VA, write, size, super); no reg write", VerilatorTest) {
    M68kSim().withVerilator.compile(new LsDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val s = dut.src.logic
      s.iValid #= false; s.iSqCommitValid #= false; s.iSqFlush #= false
      s.seedValid #= false; s.obsIntAddr #= 0; s.iPsrcAValid #= false; s.iPsrcBValid #= false
      // iStkPush is an LS-source input added after this spec was written: an UNDRIVEN
      // stkPush randomizes per sim seed, and stkPush=True turns the store into a
      // PREDECREMENT (s1Va = base - szBytes), so VA 0x2000 became 0x1ffc -> VPN 0x1
      // (not the faulting VPN 0x2) on ~half the seeds. Pin it false (plain aligned store).
      s.iStkPush #= false
      s.iLeaAddr #= false   // MUST default: undriven -> randomized per seed -> LEA path
      cd.waitSampling(80)
      // Fault VA 0x2000 -> VPN 0x2.
      dut.xlate.logic.faultEn  #= true
      dut.xlate.logic.faultVpn #= 0x2
      // seed base preg 10 = 0x2000, data preg 11.
      s.seedValid #= true; s.seedAddr #= 10; s.seedData #= BigInt(0x2000L); cd.waitSampling()
      s.seedAddr #= 11; s.seedData #= BigInt(0x12345678L); cd.waitSampling()
      s.seedValid #= false; cd.waitSampling(2)
      // issue a LONG store to VA 0x2000 (base=10, data=11, robId 7) -> FAULTS.
      s.iValid #= true; s.iMemOp #= MemOp.STORE; s.iSize #= Size.LONG
      s.iPsrcA #= 10; s.iPsrcAValid #= true; s.iPsrcB #= 11; s.iPsrcBValid #= true
      s.iImm #= 0; s.iPdstValid #= false; s.iPdst #= 0; s.iRobId #= 7
      cd.waitSamplingWhere(s.iReady.toBoolean)
      s.iValid #= false

      var seen = false; var n = 0
      while (!seen && n < 60) {
        if (dut.src.logic.fValid.toBoolean) {
          seen = true
          assert(dut.src.logic.fRob.toInt == 7, "fault robId")
          assert(dut.src.logic.fAddr.toLong == 0x2000L, f"faultAddr=0x${dut.src.logic.fAddr.toLong}%x")
          assert(dut.src.logic.fWrite.toBoolean, "store -> write")
          assert(dut.src.logic.fSize.toInt == 2, "LONG -> sizeBits 2")
          // the faulting access must still COMPLETE (so the ROB can retire it).
          assert(dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == 7,
            "faulting access still completes")
        }
        n += 1; cd.waitSampling()
      }
      assert(seen, "faultCompletion never fired for a faulting store")
    }
  }

  test("LS EU: a non-faulting access does NOT assert faultCompletion", VerilatorTest) {
    M68kSim().withVerilator.compile(new LsDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val s = dut.src.logic
      s.iValid #= false; s.iSqCommitValid #= false; s.iSqFlush #= false
      s.seedValid #= false; s.obsIntAddr #= 0; s.iPsrcAValid #= false; s.iPsrcBValid #= false
      s.iStkPush #= false   // pin false (undriven -> per-seed predecrement); plain store
      s.iLeaAddr #= false   // MUST default: undriven -> randomized per seed -> LEA path
      cd.waitSampling(80)
      dut.xlate.logic.faultEn #= false   // no faults
      s.seedValid #= true; s.seedAddr #= 10; s.seedData #= BigInt(0x3000L); cd.waitSampling()
      s.seedAddr #= 11; s.seedData #= BigInt(0xAAAAL); cd.waitSampling()
      s.seedValid #= false; cd.waitSampling(2)
      s.iValid #= true; s.iMemOp #= MemOp.STORE; s.iSize #= Size.LONG
      s.iPsrcA #= 10; s.iPsrcAValid #= true; s.iPsrcB #= 11; s.iPsrcBValid #= true
      s.iImm #= 0; s.iPdstValid #= false; s.iPdst #= 0; s.iRobId #= 4
      cd.waitSamplingWhere(s.iReady.toBoolean)
      s.iValid #= false
      var n = 0; var faulted = false; var completed = false
      while (!completed && n < 60) {
        if (dut.src.logic.fValid.toBoolean) faulted = true
        if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == 4) completed = true
        n += 1; cd.waitSampling()
      }
      assert(completed, "non-faulting store completes")
      assert(!faulted, "non-faulting access must NOT assert faultCompletion")
    }
  }

  // ── ROB half ────────────────────────────────────────────────────────────────
  class RobDut extends Component {
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

  def pokeRu(u: RenamedUop, pc: Long, robDst: Int = 0): Unit = {
    u.valid #= true; u.pc #= pc; u.nextPc #= pc + 2; u.faultUsesNextPc #= false
    u.op #= DecOp.MOVE; u.cluster #= Cluster.LS; u.size #= Size.LONG
    u.useImm #= false; u.imm #= 0
    u.isBranch #= false; u.cond #= 0; u.branchDisp #= 0; u.unimplemented #= false
    u.dstArch #= robDst
    u.psrcA #= 0; u.psrcAValid #= false; u.psrcB #= 0; u.psrcBValid #= false
    u.pdst #= 0; u.pdstValid #= false; u.pdstOld #= 0
    u.pNzvcSrc #= 0; u.readsNzvc #= false; u.pNzvcDst #= 0; u.writesNzvc #= false; u.pNzvcOld #= 0
    u.pXSrc #= 0; u.readsX #= false; u.pXDst #= 0; u.writesX #= false; u.pXOld #= 0
    u.faulted #= false; u.faultVector #= 0; u.isRte #= false
    u.sysOp #= false; u.sysKind #= m68k040.decode.SysKind.NONE; u.sysReadDir #= false
  }

  test("ROB: lsFaultCompletion flags entry vector 2 + faultAddr; exceptionPending at retire") {
    M68kSim().compile(new RobDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.rsrc.logic.src.valid #= false; dut.rsrc.logic.u1v #= false
      dut.rob.logic.flush.valid #= false
      for (c <- dut.rob.logic.completion) { c.valid #= false; c.payload #= 0 }
      dut.rob.logic.branchCompletion.valid #= false
      dut.rob.logic.lsFaultCompletion.valid #= false
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x500)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0x502)
      cd.waitSampling(3)

      // alloc a single LS uop at pc 0x500 (robId 0).
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x500)
      dut.rsrc.logic.src.valid #= true; dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()

      // complete robId 0 via the normal port AND drive the lsFaultCompletion (the LS
      // EU asserts both for a faulting access).
      dut.rob.logic.completion(2).valid #= true; dut.rob.logic.completion(2).payload #= 0
      dut.rob.logic.lsFaultCompletion.valid #= true
      dut.rob.logic.lsFaultCompletion.payload.robId #= 0
      dut.rob.logic.lsFaultCompletion.payload.faultAddr #= BigInt(0x2000L)
      dut.rob.logic.lsFaultCompletion.payload.write #= true
      dut.rob.logic.lsFaultCompletion.payload.sizeBits #= 2
      dut.rob.logic.lsFaultCompletion.payload.supervisor #= true
      cd.waitSampling()
      dut.rob.logic.completion(2).valid #= false
      dut.rob.logic.lsFaultCompletion.valid #= false

      var seen = false; var n = 0
      while (!seen && n < 50) {
        if (dut.rob.logic.exceptionPending.toBoolean) {
          seen = true
          assert(dut.rob.logic.exceptionVector.toInt == 2, s"vector=${dut.rob.logic.exceptionVector.toInt}")
          assert(dut.rob.logic.exceptionPc.toLong == 0x500L, f"excPc=0x${dut.rob.logic.exceptionPc.toLong}%x")
          assert(dut.rob.logic.exceptionFaultAddr.toLong == 0x2000L,
            f"faultAddr=0x${dut.rob.logic.exceptionFaultAddr.toLong}%x")
          assert(dut.rob.logic.exceptionFaultWr.toBoolean, "write attr")
          assert(dut.rob.logic.exceptionFaultSize.toInt == 2, "size attr (LONG)")
          assert(dut.rob.logic.exceptionFaultSup.toBoolean, "supervisor attr")
          assert(!dut.csink.logic.commitValidOut(0).toBoolean, "faulted entry must NOT commit normally")
        }
        n += 1; cd.waitSampling()
      }
      assert(seen, "exceptionPending never pulsed for the MMU-faulted entry")
    }
  }
}
