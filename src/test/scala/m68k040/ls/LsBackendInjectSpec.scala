package m68k040.ls

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.rob.{RobPlugin, RenameUopSourcePlugin, RenameCommitSinkPlugin}
import m68k040.rename.RenamedUop
import m68k040.decode.DecOp
import m68k040.isa.{Cluster, MemOp, Size}
import m68k040.cache.DcachePlugin
import m68k040.mmu.DIdentityTranslationPlugin
import m68k040.execute.{AluEuPlugin, BranchEuPlugin, LsEuPlugin}
import m68k040.execute.iq.{IssueQueuePlugin, IssueQueueService}
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** End-to-end LS-cluster injection: full execute backend (rename source ->
  * dispatch -> ROB + IQ -> ALU/branch/LS EUs -> L1D + SQ -> PRFs -> BehavioralMem).
  * Injects a hand-built sequence (store; dependent forwarded load; ALU consumer)
  * and checks the architectural result via the LS EU whitebox + BehavioralMem. */
class LsBackendInjectSpec extends AnyFunSuite {

  class IqFlushTiePlugin extends FiberPlugin {
    val logic = during build new Area { host[IssueQueueService].flushPort := False }
  }

  class BackendWiringPlugin(eu0: AluEuPlugin, eu1: AluEuPlugin, branchEu: BranchEuPlugin, lsEu: LsEuPlugin) extends FiberPlugin {
    val logic = during build new Area {
      val iq  = host[IssueQueueService]
      val rob = host[RobPlugin]
      eu0.issue << iq.issue(0)
      eu1.issue << iq.issue(1)
      iq.aluFastAcceptNext(0) := eu0.fastAcceptNext
      iq.aluFastAcceptNext(1) := eu1.fastAcceptNext
      eu0.flush := iq.flushPort
      eu1.flush := iq.flushPort
      eu0.srSysIn := U(0, 8 bits); eu1.srSysIn := U(0, 8 bits)  // MOVE-from-SR srSys input (unused here)
      // SLOW-ALU (SHIFT/BITFIELD, S3) dynamic wakeup (mirrors top/FullCoreSynth). No shifts in
      // this LS-injection corpus, so inert, but wired for consistency/latent-deadlock safety.
      iq.aluSlowWakeup(0).valid   := eu0.slowWakeup.valid
      iq.aluSlowWakeup(0).payload := eu0.slowWakeup.payload
      iq.aluSlowWakeup(1).valid   := eu1.slowWakeup.valid
      iq.aluSlowWakeup(1).payload := eu1.slowWakeup.payload
      iq.issue(2).ready := False           // no branch uops injected here
      // branchEu is instantiated but no branch uops are injected; drive its issue input
      // INERT so it elaborates (an undriven Stream[RenamedUop] payload = NO DRIVER). This
      // was a latent gap: the branch-EU slice added branchEu to the DUT but only tied the
      // IQ port (issue(2).ready), leaving branchEu's OWN issue.payload undriven.
      branchEu.issue.valid := False
      branchEu.issue.payload.assignDontCare()
      lsEu.issue << iq.issue(3)
      iq.issue(4).ready := False           // no CHK/DIV uops injected here
      rob.logic.completion(0).valid   := eu0.completion.valid
      rob.logic.completion(0).payload := eu0.completion.payload
      rob.logic.completion(1).valid   := eu1.completion.valid
      rob.logic.completion(1).payload := eu1.completion.payload
      rob.logic.completion(2).valid   := lsEu.completion.valid
      rob.logic.completion(2).payload := lsEu.completion.payload
      rob.logic.lsFaultCompletion.valid   := lsEu.faultCompletion.valid
      rob.logic.lsFaultCompletion.payload := lsEu.faultCompletion.payload
      rob.logic.completion(4).valid   := lsEu.sqCompletionPort.valid
      rob.logic.completion(4).payload := lsEu.sqCompletionPort.payload
      rob.logic.sqFaultCompletion.valid   := lsEu.sqFaultCompletionPort.valid
      rob.logic.sqFaultCompletion.payload := lsEu.sqFaultCompletionPort.payload
      rob.logic.preciseDrainBusyIn        := lsEu.preciseDrainBusySig
      rob.logic.inhibitedLoadBusyIn       := lsEu.inhibitedLoadBusySig
      lsEu.robHeadIn           := rob.logic.h0
      lsEu.robHeadValidIn      := rob.logic.count > 0
      lsEu.irqPreemptPendingIn := rob.logic.interruptPending || rob.logic.tracePendingFire
      lsEu.debugHaltImminentIn := rob.logic.haltAfterDue || rob.logic.haltAfterRetireBlock
      iq.lsWakeup.valid   := lsEu.wakeup.valid
      iq.lsWakeup.payload := lsEu.wakeup.payload
      iq.lsNzvcWakeup.valid   := lsEu.wakeupNzvc.valid
      iq.lsNzvcWakeup.payload := lsEu.wakeupNzvc.payload
      lsEu.sqCommit.valid   := rob.logic.retire0
      lsEu.sqCommit.payload := rob.logic.h0
      lsEu.sqCommitB.valid   := rob.logic.retire1
      lsEu.sqCommitB.payload := rob.logic.h1
      lsEu.sqFlush          := host[m68k040.services.RedirectService].doFlush
    }
  }

  class Dut extends Component {
    val db    = new Database
    val host  = db on (new PluginHost)
    val rsrc  = new RenameUopSourcePlugin
    val rob   = new RobPlugin
    val disp  = new m68k040.dispatch.DispatchPlugin
    val iq     = new IssueQueuePlugin
    val eu0    = new AluEuPlugin
    val eu1    = new AluEuPlugin
    val branchEu = new BranchEuPlugin
    val xlateD = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin()
    val lsEu   = new LsEuPlugin
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val rfX    = new RegFilePluginX
    val wire   = new BackendWiringPlugin(eu0, eu1, branchEu, lsEu)
    val ftie   = new IqFlushTiePlugin
    val csink  = new RenameCommitSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()),
      rsrc, rob, disp, iq, eu0, eu1, branchEu, xlateD, dcache, lsEu,
      rfInt, rfNzvc, rfX, wire, ftie, csink)) }
  }

  def pokeUop(
      u: RenamedUop, valid: Boolean = true, pc: Long = 0,
      op: SpinalEnumElement[DecOp.type] = DecOp.MOVE,
      cluster: SpinalEnumElement[Cluster.type] = Cluster.INT,
      memOp: SpinalEnumElement[MemOp.type] = MemOp.NONE,
      size: SpinalEnumElement[Size.type] = Size.LONG,
      useImm: Boolean = false, imm: Long = 0, dstArch: Int = 0,
      psrcA: Int = 0, psrcAValid: Boolean = false,
      psrcB: Int = 0, psrcBValid: Boolean = false,
      pdst: Int = 0, pdstValid: Boolean = true, pdstOld: Int = 0,
      writesNzvc: Boolean = false, pNzvcDst: Int = 0): Unit = {
    u.valid #= valid; u.pc #= pc; u.nextPc #= pc + 2; u.faultUsesNextPc #= false; u.op #= op; u.cluster #= cluster; u.memOp #= memOp
    u.size #= size; u.useImm #= useImm; u.imm #= BigInt(imm & 0xffffffffL)
    u.isBranch #= false; u.cond #= 0; u.branchDisp #= 0; u.unimplemented #= false
    u.faulted #= false; u.faultVector #= 0; u.isRte #= false
    u.dstArch #= dstArch
    u.psrcA #= psrcA; u.psrcAValid #= psrcAValid
    u.psrcB #= psrcB; u.psrcBValid #= psrcBValid
    u.pdst #= pdst; u.pdstValid #= pdstValid; u.pdstOld #= pdstOld
    u.pNzvcSrc #= 0; u.readsNzvc #= false
    u.pNzvcDst #= pNzvcDst; u.writesNzvc #= writesNzvc; u.pNzvcOld #= 0
    u.pXSrc #= 0; u.readsX #= false; u.pXDst #= 0; u.writesX #= false; u.pXOld #= 0
    // Drive EVERY remaining RenamedUop field — the EU/IQ read several (isMovea muxes the
    // writeback to src2; toCcr muxes flags; isShift routes the slow path), so leaving any
    // undriven gives a nondeterministic per-netlist value and a flaky sim. This corpus is
    // store/load/ALU only: all the op-flavour flags are false. Fields added by later ISA
    // slices (MOVEA, toCcr, shifts, line-4/5) must default here.
    u.psrcC #= 0; u.psrcCValid #= false
    u.ibranch #= false; u.anInc #= 0; u.stkPush #= false; u.ccrRestore #= false
    u.toCcr #= false; u.isCondTrap #= false
    u.faultAddr #= 0; u.sswInstr #= false
    u.divSigned #= false; u.divIsRem #= false
    u.isChk2 #= false
    u.shiftOp #= 0; u.shiftDir #= false; u.extByte #= false
    u.isMovea #= false; u.isScc #= false; u.isDbcc #= false
    u.div64 #= false; u.bcdSub #= false; u.bitOp #= 0
    u.bfOp #= 0; u.bfDynamic #= false; u.bfMem #= false; u.bfStoreForm #= 0
    u.indexLong #= false; u.indexScale #= 0
    u.leaAddr #= false; u.movesAliasStore #= false
    u.fromCcr #= false; u.fromSr #= false
    u.needsSupervisor #= false; u.keepCommit #= false
    u.faultAtc #= false
    u.sysOp #= false; u.sysKind #= m68k040.decode.SysKind.NONE; u.sysReadDir #= false
    u.predTaken #= false; u.predTarget #= 0
    u.phtValid #= false; u.phtIndex #= 0
    u.casForm #= 0
    u.firstOfInstr #= true
  }

  def memByte(addr: Long): Int = ((addr * 5 + 0x23) & 0xff).toInt

  test("store -> forwarded load -> ALU consumer (end-to-end)", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val base = 0x6000L
      for (i <- 0 until 16) mem.pokeByte(base + i, memByte(base + i))

      // capture LS EU writeback (the forwarded load result) by robId
      val lsWb = scala.collection.mutable.Map[Int, Long]()
      val lsNzvc = scala.collection.mutable.Map[Int, Int]()
      cd.onSamplings {
        val w = dut.lsEu.logic.wbObs
        if (w.valid.toBoolean && w.intWrite.toBoolean) lsWb(w.robId.toInt) = w.result.toLong & 0xffffffffL
        if (w.valid.toBoolean && w.nzvcWrite.toBoolean) lsNzvc(w.robId.toInt) = w.nzvc.toInt
      }
      // capture ALU EU writebacks too
      val aluWb = scala.collection.mutable.Map[Int, Long]()
      cd.onSamplings {
        for (eu <- Seq(dut.eu0, dut.eu1)) {
          val w = eu.logic.wbObs
          if (w.valid.toBoolean && w.intWrite.toBoolean) aluWb(w.robId.toInt) = w.result.toLong & 0xffffffffL
        }
      }

      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      dut.rob.logic.flush.valid #= false
      cd.waitSampling(80) // PRF init sweep

      def push1(configure: RenamedUop => Unit): Unit = {
        configure(dut.rsrc.logic.src.payload(0))
        dut.rsrc.logic.src.valid #= true
        dut.rsrc.logic.u1v #= false
        cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
        dut.rsrc.logic.src.valid #= false
      }

      // Seed PRFs via MOVEQ ALU ops:
      //  p10 = base address (store/load base)
      //  p11 = store data 0xCAFE0001
      //  p13 = ALU addend 0x10
      push1(u => pokeUop(u, pc = 0x100, op = DecOp.MOVE, useImm = true, imm = base,
        dstArch = 0, pdst = 10, pdstValid = true, pdstOld = 0)); cd.waitSampling(3)
      push1(u => pokeUop(u, pc = 0x102, op = DecOp.MOVE, useImm = true, imm = 0xCAFE0001L,
        dstArch = 1, pdst = 11, pdstValid = true, pdstOld = 1)); cd.waitSampling(3)
      push1(u => pokeUop(u, pc = 0x104, op = DecOp.MOVE, useImm = true, imm = 0x10,
        dstArch = 2, pdst = 13, pdstValid = true, pdstOld = 2)); cd.waitSampling(6)

      // I3: STORE p11 -> mem[p10+0] (LONG). robId assigned by ROB; no int dst.
      push1(u => pokeUop(u, pc = 0x106, cluster = Cluster.LS, memOp = MemOp.STORE,
        useImm = true, imm = 0, psrcA = 10, psrcAValid = true, psrcB = 11, psrcBValid = true,
        pdst = 0, pdstValid = false, dstArch = 3))
      cd.waitSampling(6)

      // I4: LOAD mem[p10+0] -> p20 (D4). Forwards from the uncommitted store.
      push1(u => pokeUop(u, pc = 0x108, cluster = Cluster.LS, memOp = MemOp.LOAD,
        useImm = true, imm = 0, psrcA = 10, psrcAValid = true,
        pdst = 20, pdstValid = true, pdstOld = 4, dstArch = 4,
        writesNzvc = true, pNzvcDst = 4))
      cd.waitSampling(10)

      // I5: ADD p20, p13 -> p21 (D5) = loadResult + 0x10. Consumes the load via
      //     dynamic-completion wakeup.
      push1(u => pokeUop(u, pc = 0x10A, op = DecOp.ADD, psrcA = 20, psrcAValid = true,
        psrcB = 13, psrcBValid = true, pdst = 21, pdstValid = true, pdstOld = 5,
        dstArch = 5, writesNzvc = true, pNzvcDst = 5))
      cd.waitSampling(30)

      // The load (robId 4) forwarded the store's data 0xCAFE0001.
      val loadRobId = 4
      assert(lsWb.contains(loadRobId), s"LS load must write back; saw ${lsWb.keys.toSeq.sorted}")
      assert(lsWb(loadRobId) == 0xCAFE0001L, s"forwarded load = 0x${lsWb(loadRobId).toHexString} expected CAFE0001")
      assert(lsNzvc.get(loadRobId).contains(0x8),
        s"forwarded MOVE.L flags must be N=1,ZVC=0; saw ${lsNzvc.get(loadRobId)}")
      // The ALU consumer (robId 5) = 0xCAFE0001 + 0x10 = 0xCAFE0011.
      val addRobId = 5
      assert(aluWb.contains(addRobId), s"ALU consumer must write back; saw ${aluWb.keys.toSeq.sorted}")
      assert(aluWb(addRobId) == 0xCAFE0011L, s"ALU consumer = 0x${aluWb(addRobId).toHexString} expected CAFE0011")

      // Let the store drain/pop, then load the same negative value through the
      // ordinary cache descriptor path. This distinguishes the aligned-ring flag
      // calculation from the forwarded completion above.
      var drainWait = 0
      while (!dut.lsEu.logic.sq.io.empty.toBoolean && drainWait < 80) {
        cd.waitSampling(); drainWait += 1
      }
      assert(dut.lsEu.logic.sq.io.empty.toBoolean,
        "store must drain before descriptor-load check")
      push1(u => pokeUop(u, pc = 0x10C, cluster = Cluster.LS, memOp = MemOp.LOAD,
        useImm = true, imm = 0, psrcA = 10, psrcAValid = true,
        pdst = 22, pdstValid = true, pdstOld = 6, dstArch = 6,
        writesNzvc = true, pNzvcDst = 6))
      cd.waitSampling(30)
      val ringLoadRobId = 6
      assert(lsWb.get(ringLoadRobId).contains(0xCAFE0001L),
        s"aligned descriptor load must return CAFE0001; saw ${lsWb.get(ringLoadRobId).map(_.toHexString)}")
      assert(lsNzvc.get(ringLoadRobId).contains(0x8),
        s"aligned descriptor MOVE.L flags must be N=1,ZVC=0; saw ${lsNzvc.get(ringLoadRobId)}")
      cd.waitSampling(4)
    }
  }
}
