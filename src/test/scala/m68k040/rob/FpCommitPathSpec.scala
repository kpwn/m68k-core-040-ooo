package m68k040.rob

import m68k040.{M68kParams, M68kSim}
import m68k040.core.ParamPlugin
import m68k040.decode.{DecodedUop, DecOp, FpSrcKind}
import m68k040.rename.{RenameStage, DecodeUopSourcePlugin}
import m68k040.isa.{Cluster, Size}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Regression tests for the FP/FPCC COMMIT PATH (fix landed 2026-08-16, discovered
  * during the Task 8 review of the FPU implementation plan).
  *
  * Two independent bugs, both of which made the FPU non-functional for any sustained
  * FP-using program (not an edge case):
  *
  *  Bug A -- `RobPlugin.RobPayload` had no FP/FPCC fields at all, so the ROB drove
  *    `CommitSlot.fp*`/`fpcc*` to inert False/0 defaults forever. An FP writer's new
  *    physical tag therefore NEVER entered the committed FP RAT and its old tag NEVER
  *    returned to `fpFree`. Compounding consequence: `fpRat.io.rollback := flush`
  *    restores the COMMITTED FP RAT on every flush, and since the committed FP RAT
  *    never advanced past its reset identity seed, every branch mispredict / exception
  *    silently reverted all architectural FP state to FP_n -> phys n.
  *
  *  Bug B -- `RenameStage`'s `freeReady` gate omitted `fpFree.io.popReady` /
  *    `fpccFree.io.popReady`, so rename did not stall on an exhausted FP/FPCC pool.
  *    `Freelist.io.pop(k).id` is a bare `ram.readAsync(head + ...)` with no underflow
  *    guard, so an un-gated pop hands out a STILL-LIVE physical tag (and underflows
  *    `count`) -- two in-flight uops renamed onto one physical register.
  *
  * Harness: the SAME real-rename -> real-ROB closed loop `RobPluginSpec.E2EDut`
  * already establishes for the int freelist's sustained-free-loop test, so these
  * tests exercise the genuine RTL commit loop (rename allocates -> ROB threads the
  * identity through `RobPayload` -> retire drives `CommitSlot` -> rename commits the
  * FP RAT and pushes the old tag back into `fpFree`), not a hand-built mock of it.
  */
class FpCommitPathSpec extends AnyFunSuite {

  /** Real DecodeStage-shaped source -> real RenameStage -> real RobPlugin, with the
    * ROB's commit ports wired straight back into rename (RenameCommitService). */
  class E2EDut extends Component {
    val db    = new Database
    val host  = db on (new PluginHost)
    val dsrc  = new DecodeUopSourcePlugin
    val ren   = new RenameStage
    val drv   = new RobAllocDriverPlugin
    val rob   = new RobPlugin
    val tsink = new CommitTraceSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), dsrc, ren, drv, rob, tsink)) }
  }

  /** An FP-generic macro-op shaped like FADD FPm,FPn: reads FPm + FPn, writes FPn and
    * the FPCC. No int dst / no NZVC / no X, so it exercises ONLY the FP + FPCC pools. */
  def pokeFpUop(
      u: DecodedUop,
      fpDstReg: Int,
      writesFp: Boolean = true,
      writesFpcc: Boolean = true,
      fpSrcAReg: Int = 0, usesFpSrcA: Boolean = false,
      fpSrcBReg: Int = 0, usesFpSrcB: Boolean = false,
      pc: Long = 0
  ): Unit = {
    u.valid #= true
    u.pc #= pc
    u.nextPc #= pc + 4
    u.faultUsesNextPc #= false
    u.op #= DecOp.MOVE
    u.cluster #= Cluster.CPLX
    u.size #= Size.LONG
    u.srcAReg #= 0; u.srcAValid #= false
    u.srcBReg #= 0; u.srcBValid #= false
    u.srcCReg #= 0; u.srcCValid #= false
    u.dstReg #= 0;  u.dstValid  #= false
    u.useImm #= false; u.imm #= 0
    u.readsNzvc #= false; u.readsX #= false
    u.writesNzvc #= false; u.writesX #= false
    u.isBranch #= false; u.cond #= 0; u.branchDisp #= 0
    u.unimplemented #= false
    u.faulted #= false; u.faultVector #= 0; u.isRte #= false
    u.sysOp #= false; u.sysKind #= m68k040.decode.SysKind.NONE; u.sysReadDir #= false
    u.needsSupervisor #= false
    u.firstOfInstr #= true
    u.debugBreakValid #= false; u.debugBreakSlot #= 0
    // FP fields (the whole point of this poke).
    u.fpSrcAReg #= fpSrcAReg; u.usesFpSrcA #= usesFpSrcA
    u.fpSrcBReg #= fpSrcBReg; u.usesFpSrcB #= usesFpSrcB
    u.fpDstReg  #= fpDstReg;  u.writesFp   #= writesFp
    u.readsFpcc #= false;     u.writesFpcc #= writesFpcc
    u.fpuOp #= 0; u.fpSrcKind #= FpSrcKind.FPREG; u.fpSrcFmt #= 0; u.fpWideImm #= BigInt(0)
  }

  def clearSlot(u: DecodedUop): Unit = { u.valid #= false; u.writesFp #= false; u.writesFpcc #= false }

  def initDut(dut: E2EDut, cd: ClockDomain): Unit = {
    dut.dsrc.logic.src.valid #= false
    dut.dsrc.logic.s1v #= false
    for (i <- 0 until 6) dut.rob.logic.completion(i).valid #= false
    dut.rob.logic.flush.valid #= false
    // Both decode slots must hold concrete inert values before init (an un-poked
    // input Bundle field would otherwise let a stray writesFp pop the FP freelist).
    pokeFpUop(dut.dsrc.logic.src.payload(0), fpDstReg = 0, writesFp = false, writesFpcc = false)
    pokeFpUop(dut.dsrc.logic.src.payload(1), fpDstReg = 0, writesFp = false, writesFpcc = false)
    clearSlot(dut.dsrc.logic.src.payload(0))
    clearSlot(dut.dsrc.logic.src.payload(1))
    cd.waitSampling()
    var n = 0
    while (!dut.dsrc.logic.src.ready.toBoolean && n < 300) { cd.waitSampling(); n += 1 }
    assert(dut.dsrc.logic.src.ready.toBoolean, "rename init never completed")
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // PROPERTY 1 (Bug B regression): an exhausted FP pool BACKPRESSURES rename; it
  // never hands out a live tag twice and `fpFree.count` never underflows.
  // ═══════════════════════════════════════════════════════════════════════════════
  test("Bug B: exhausted fpFree stalls rename (no double-allocation, no count underflow)") {
    M68kSim().compile(new E2EDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initDut(dut, cd)

      // FP-writing macro-ops, single-wide, NEVER completed -> nothing can retire, so
      // the FP freelist can only drain. fpFree: physCount=16, archCount=8 -> 8 free.
      // popPorts=2 -> popReady = (count >= 2), so exactly 7 pops are possible before
      // the pool is declared not-ready (8,7,6,5,4,3,2 -> stall at 1).
      val tags = scala.collection.mutable.ArrayBuffer[Int]()
      var minCount = 99
      var maxCount = 0
      var fires = 0
      pokeFpUop(dut.dsrc.logic.src.payload(0), fpDstReg = 1, writesFp = true, writesFpcc = false)
      clearSlot(dut.dsrc.logic.src.payload(1))
      dut.dsrc.logic.src.valid #= true
      dut.dsrc.logic.s1v #= false

      for (_ <- 0 until 60) {
        sleep(1)
        val ready = dut.dsrc.logic.src.ready.toBoolean
        if (ready) { tags += dut.ren.logic.uopsPort.payload(0).pFpDst.toInt; fires += 1 }
        val c = dut.ren.logic.fpFree.count.toInt
        if (c < minCount) minCount = c
        if (c > maxCount) maxCount = c
        cd.waitSampling()
      }
      dut.dsrc.logic.src.valid #= false

      assert(fires == 7,
        s"expected exactly 7 FP allocations before the 8-entry fpFree pool backpressures, got $fires " +
        s"(pre-fix this ran unbounded, handing out live tags)")
      assert(tags.distinct.size == tags.size,
        s"fpFree handed out a DUPLICATE physical tag while both were in flight: $tags")
      assert(!dut.dsrc.logic.src.ready.toBoolean, "rename must STALL (not corrupt) once fpFree is exhausted")
      assert(minCount >= 1 && maxCount <= 8,
        s"fpFree.count left its legal 1..8 window (min=$minCount max=$maxCount) -- underflow/double-pop")
    }
  }

  test("Bug B: exhausted fpccFree stalls rename (no double-allocation, no count underflow)") {
    M68kSim().compile(new E2EDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initDut(dut, cd)

      // FPCC-only writers (FCMP/FTST shape: no FP data dst). fpccFree: physCount=16,
      // archCount=1 -> 15 free, popPorts=2 -> 14 pops then stall at count==1.
      val tags = scala.collection.mutable.ArrayBuffer[Int]()
      var minCount = 99
      var fires = 0
      pokeFpUop(dut.dsrc.logic.src.payload(0), fpDstReg = 0, writesFp = false, writesFpcc = true)
      clearSlot(dut.dsrc.logic.src.payload(1))
      dut.dsrc.logic.src.valid #= true
      dut.dsrc.logic.s1v #= false

      for (_ <- 0 until 60) {
        sleep(1)
        if (dut.dsrc.logic.src.ready.toBoolean) {
          tags += dut.ren.logic.uopsPort.payload(0).pFpccDst.toInt; fires += 1
        }
        val c = dut.ren.logic.fpccFree.count.toInt
        if (c < minCount) minCount = c
        cd.waitSampling()
      }
      dut.dsrc.logic.src.valid #= false

      assert(fires == 14,
        s"expected exactly 14 FPCC allocations before the 15-entry fpccFree pool backpressures, got $fires")
      assert(tags.distinct.size == tags.size,
        s"fpccFree handed out a DUPLICATE physical tag while both were in flight: $tags")
      assert(!dut.dsrc.logic.src.ready.toBoolean, "rename must STALL once fpccFree is exhausted")
      assert(minCount >= 1, s"fpccFree.count underflowed (min=$minCount)")
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // PROPERTY 2 (Bug A regression): with the ROB commit path wired, FP writers that
  // RETIRE genuinely replenish fpFree/fpccFree, so a sustained FP stream flows far
  // past the 8-deep pool instead of deadlocking at 7.
  // ═══════════════════════════════════════════════════════════════════════════════
  test("Bug A: retiring FP writers replenish fpFree/fpccFree -- sustained stream past the pool depth") {
    M68kSim().compile(new E2EDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initDut(dut, cd)

      val mask = 63
      var nextTail = 0
      var allocs = 0
      var cyc = 0
      val target = 48                        // 6x the FP pool depth
      val pending = scala.collection.mutable.Queue[(Int, Int)]()
      val completeLag = 4
      var retires = 0
      val retireFork = fork {
        while (true) {
          cd.waitSampling()
          if (dut.tsink.logic.fireOut(0).toBoolean) retires += 1
          if (dut.tsink.logic.fireOut(1).toBoolean) retires += 1
        }
      }

      while (allocs < target && cyc < 4000) {
        dut.rob.logic.completion(0).valid #= false
        if (pending.nonEmpty && pending.front._2 <= cyc) {
          val (rid, _) = pending.dequeue()
          dut.rob.logic.completion(0).valid #= true
          dut.rob.logic.completion(0).payload #= rid
        }
        // FADD-shaped: writes FPn and the FPCC, rotating through FP0-FP7.
        pokeFpUop(dut.dsrc.logic.src.payload(0), fpDstReg = allocs % 8,
                  writesFp = true, writesFpcc = true,
                  fpSrcAReg = allocs % 8, usesFpSrcA = true, pc = allocs * 4)
        clearSlot(dut.dsrc.logic.src.payload(1))
        dut.dsrc.logic.src.valid #= true
        dut.dsrc.logic.s1v #= false
        sleep(1)
        val fired = dut.dsrc.logic.src.ready.toBoolean
        cd.waitSampling()
        if (fired) {
          pending.enqueue((nextTail, cyc + completeLag))
          nextTail = (nextTail + 1) & mask
          allocs += 1
        }
        cyc += 1
      }
      dut.dsrc.logic.src.valid #= false
      var drain = 0
      while (pending.nonEmpty && drain < 2000) {
        dut.rob.logic.completion(0).valid #= false
        if (pending.front._2 <= cyc) {
          val (rid, _) = pending.dequeue()
          dut.rob.logic.completion(0).valid #= true
          dut.rob.logic.completion(0).payload #= rid
        }
        cd.waitSampling(); cyc += 1; drain += 1
      }
      dut.rob.logic.completion(0).valid #= false
      for (_ <- 0 until 20) cd.waitSampling()

      assert(allocs >= target,
        s"FP stream stalled at $allocs of $target allocations -- fpFree never replenished " +
        s"(pre-fix the ROB drove fpWrite=False forever, so no old tag was ever pushed back)")
      assert(retires >= target,
        s"only $retires retires observed for $allocs allocations")
      // The pools must be back at (or near) full depth once everything has retired.
      assert(dut.ren.logic.fpFree.count.toInt >= 7,
        s"fpFree did not replenish after full drain: count=${dut.ren.logic.fpFree.count.toInt}")
      assert(dut.ren.logic.fpccFree.count.toInt >= 14,
        s"fpccFree did not replenish after full drain: count=${dut.ren.logic.fpccFree.count.toInt}")
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // PROPERTY 3 (Bug A's compounding flush consequence): after real FP commits, a
  // flush must restore the COMMITTED FP/FPCC mapping -- not the reset identity.
  // ═══════════════════════════════════════════════════════════════════════════════
  test("Bug A/flush: architectural FP + FPCC state survives a flush (committed RAT, not reset identity)") {
    M68kSim().compile(new E2EDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initDut(dut, cd)

      // (a) Rename ONE FP writer targeting FP3 (+ FPCC), capture the allocated tags.
      pokeFpUop(dut.dsrc.logic.src.payload(0), fpDstReg = 3, writesFp = true, writesFpcc = true, pc = 0x400)
      clearSlot(dut.dsrc.logic.src.payload(1))
      dut.dsrc.logic.src.valid #= true
      dut.dsrc.logic.s1v #= false
      sleep(1)
      assert(dut.dsrc.logic.src.ready.toBoolean, "rename should accept the FP uop")
      val fpTag   = dut.ren.logic.uopsPort.payload(0).pFpDst.toInt
      val fpccTag = dut.ren.logic.uopsPort.payload(0).pFpccDst.toInt
      cd.waitSampling()
      dut.dsrc.logic.src.valid #= false
      assert(fpTag != 3, s"a fresh FP allocation must not be the identity tag 3 (got $fpTag)")
      assert(fpccTag != 0, s"a fresh FPCC allocation must not be the identity tag 0 (got $fpccTag)")

      // (b) Complete + retire it through the REAL ROB. Wait for the ALLOC first --
      // rename->ROB has a pipeline stage, and an alloc clears completes(tail), so a
      // completion pulse driven before the alloc lands would be swallowed.
      var w = 0
      while (dut.rob.logic.count.toInt == 0 && w < 50) { cd.waitSampling(); w += 1 }
      assert(dut.rob.logic.count.toInt == 1, s"the FP uop never reached the ROB (count=${dut.rob.logic.count.toInt})")
      dut.rob.logic.completion(0).valid #= true
      dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= false
      var n = 0
      while (dut.rob.logic.count.toInt != 0 && n < 100) { cd.waitSampling(); n += 1 }
      assert(dut.rob.logic.count.toInt == 0, "the FP uop never retired")
      cd.waitSampling(2)

      // (c) The COMMITTED FP/FPCC RATs must now name the retired tags. Pre-fix these
      // stayed at the reset identity seed forever (the ROB drove fpWrite=False).
      assert(dut.ren.logic.fpRat.committedPhys(3).toInt == fpTag,
        s"committed FP3 should be $fpTag after retire, got ${dut.ren.logic.fpRat.committedPhys(3).toInt}")
      assert(dut.ren.logic.fpccRat.committedPhys(0).toInt == fpccTag,
        s"committed FPCC should be $fpccTag after retire, got ${dut.ren.logic.fpccRat.committedPhys(0).toInt}")

      // (d) Flush (the ROB's own flush port drives RenameCommitService.flushPort ->
      // fpRat.io.rollback / fpccRat.io.rollback / fpFree.io.flush).
      dut.rob.logic.flush.valid #= true
      cd.waitSampling()
      dut.rob.logic.flush.valid #= false
      cd.waitSampling(3)

      // (e) A post-flush reader of FP3 must still see the COMMITTED tag. Pre-fix the
      // rollback reverted FP3 to phys 3 (reset identity) -- silently wrong FP data.
      assert(dut.ren.logic.fpRat.committedPhys(3).toInt == fpTag,
        "committed FP3 mapping must survive the flush")
      pokeFpUop(dut.dsrc.logic.src.payload(0), fpDstReg = 6, writesFp = true, writesFpcc = false,
                fpSrcAReg = 3, usesFpSrcA = true, pc = 0x500)
      clearSlot(dut.dsrc.logic.src.payload(1))
      dut.dsrc.logic.src.valid #= true
      dut.dsrc.logic.s1v #= false
      var m = 0
      while (!dut.dsrc.logic.src.ready.toBoolean && m < 100) { cd.waitSampling(); m += 1 }
      sleep(1)
      val readTag = dut.ren.logic.uopsPort.payload(0).pFpSrcA.toInt
      dut.dsrc.logic.src.valid #= false
      assert(readTag == fpTag,
        s"after the flush, a read of FP3 resolved to phys $readTag but the COMMITTED mapping is $fpTag " +
        s"(the 'every flush reverts FP state to the reset identity' bug)")
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // Direct field-level proof that RobPayload threads the RENAME identity (and only
  // it) to CommitSlot -- the ROB never invents a second source of truth.
  // ═══════════════════════════════════════════════════════════════════════════════
  class SimpleDut extends Component {
    val db    = new Database
    val host  = db on (new PluginHost)
    val rsrc  = new RenameUopSourcePlugin
    val drv   = new RobAllocDriverPlugin
    val rob   = new RobPlugin
    val csink = new RenameCommitSinkPlugin
    val tsink = new CommitTraceSinkPlugin
    val cacheCtrl = new CacheControlSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), rsrc, drv, rob, csink, tsink, cacheCtrl)) }
  }

  test("RobPayload threads the FP/FPCC rename identity verbatim to the commit ports") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      for (i <- 0 until 6) dut.rob.logic.completion(i).valid #= false
      dut.rob.logic.flush.valid #= false
      cd.waitSampling()

      def pokeRu(u: m68k040.rename.RenamedUop, pc: Long,
                 fpDstArch: Int, pFpDst: Int, pFpDstValid: Boolean, pFpOld: Int,
                 pFpccDst: Int, writesFpcc: Boolean, pFpccOld: Int): Unit = {
        u.valid #= true; u.pc #= pc; u.nextPc #= pc + 4; u.faultUsesNextPc #= false
        u.op #= DecOp.MOVE; u.cluster #= Cluster.CPLX; u.size #= Size.LONG
        u.useImm #= false; u.imm #= 0
        u.isBranch #= false; u.cond #= 0; u.branchDisp #= 0; u.unimplemented #= false
        u.dstArch #= 0; u.pdst #= 0; u.pdstValid #= false; u.pdstOld #= 0
        u.psrcA #= 0; u.psrcAValid #= false; u.psrcB #= 0; u.psrcBValid #= false
        u.pNzvcSrc #= 0; u.readsNzvc #= false
        u.pNzvcDst #= 0; u.writesNzvc #= false; u.pNzvcOld #= 0
        u.pXSrc #= 0; u.readsX #= false; u.pXDst #= 0; u.writesX #= false; u.pXOld #= 0
        u.faulted #= false; u.faultVector #= 0; u.isRte #= false
        u.debugBreakValid #= false; u.debugBreakSlot #= 0
        u.sysOp #= false; u.sysKind #= m68k040.decode.SysKind.NONE; u.sysReadDir #= false
        u.needsSupervisor #= false
        u.fpDstArch #= fpDstArch
        u.pFpDst #= pFpDst; u.pFpDstValid #= pFpDstValid; u.pFpOld #= pFpOld
        u.pFpccDst #= pFpccDst; u.writesFpcc #= writesFpcc; u.pFpccOld #= pFpccOld
      }

      // Slot 0: a real FP+FPCC writer.  Slot 1: a non-FP uop -- its commit port must
      // report fpWrite/fpccWrite FALSE (no spurious fpFree push).
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x100,
             fpDstArch = 5, pFpDst = 9, pFpDstValid = true, pFpOld = 2,
             pFpccDst = 11, writesFpcc = true, pFpccOld = 4)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0x104,
             fpDstArch = 0, pFpDst = 0, pFpDstValid = false, pFpOld = 0,
             pFpccDst = 0, writesFpcc = false, pFpccOld = 0)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      cd.waitSampling()

      dut.rob.logic.completion(0).valid #= true
      dut.rob.logic.completion(0).payload #= 1
      cd.waitSampling()
      dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= false

      cd.waitSamplingWhere(dut.csink.logic.commitValidOut(0).toBoolean)
      assert(dut.csink.logic.commitFpWrOut(0).toBoolean, "slot0 commit must report fpWrite")
      assert(dut.csink.logic.commitFpArchOut(0).toInt == 5, "slot0 fpArchDst")
      assert(dut.csink.logic.commitFpNewOut(0).toInt == 9, "slot0 fpNew (rename's pFpDst)")
      assert(dut.csink.logic.commitFpOldOut(0).toInt == 2, "slot0 fpOld (rename's pFpOld -> freed)")
      assert(dut.csink.logic.commitFpccWrOut(0).toBoolean, "slot0 commit must report fpccWrite")
      assert(dut.csink.logic.commitFpccNewOut(0).toInt == 11, "slot0 fpccNew")
      assert(dut.csink.logic.commitFpccOldOut(0).toInt == 4, "slot0 fpccOld")
      if (dut.csink.logic.commitValidOut(1).toBoolean) {
        assert(!dut.csink.logic.commitFpWrOut(1).toBoolean, "a non-FP uop must not assert fpWrite")
        assert(!dut.csink.logic.commitFpccWrOut(1).toBoolean, "a non-FP uop must not assert fpccWrite")
      }
    }
  }
}
