package m68k040.fuzz

import m68k040.{M68kSim, VerilatorTest}
import m68k040.oracle.ProgramAssembler
import m68k040.lockstep.WhiteboxCapture
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** One-shot diagnostic: run the saved memory-indirect-ALU-src HANG repro
  * (task #139) through a raw FuzzCoreDut, using the REAL WhiteboxCapture
  * machinery (robId-correlated wbObs + commitObs, exactly mirroring
  * FuzzLockStepSpec's captureWb/onCommit wiring) to reconstruct the
  * macro-instruction stream the lock-step harness actually sees, and
  * compare it against the raw per-cycle commitObs PC stream to find
  * exactly which commit gets dropped-when-it-shouldn't (or vice versa).
  * NOT part of the regular suite — a targeted debug tool, deleted/inert
  * once the bug is fixed. See fuzz-campaign-divergence-2026-07-16 memory. */
class MiHangTraceSpec extends AnyFunSuite {
  test("trace: MI_ALU_SRC_ENTRY hang (task #139, seed=10027 minimized repro)", VerilatorTest) {
    // Finding #3 (task #139): testing whether the IssueQueuePlugin same-cycle
    // issue/flush race fix (verified to fix finding #1's minimal repro) ALSO
    // resolves finding #3's separately-discovered ADDQ memory-indirect PC-garbage
    // bug (MI_RMW_ENTRY), which was never root-caused.
    // Finding #1 (task #139), mechanism #2 investigation: the IssueQueuePlugin
    // same-cycle issue/flush race fix does NOT resolve this original repro's hang
    // (confirmed: no same-cycle race near its actual freeze point, rawCommits=44-47).
    // Using the existing sqSlots(rid,pcStore,completes) cross-reference to identify
    // the REAL identities of robId 47 (stuck load) and robId 45 (blocking committed
    // store) in this larger, more complex program.
    val path = "/home/qwertyoruiop/.claude/projects/-home-qwertyoruiop-m68k-core-040-ooo/memory/repros/fuzz-seed10027-mi-alu-src-hang.s"
    val src  = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)))
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble failed: ${e.reason}")
    }

    // CRITICAL: FuzzRunner.run compiles FIRST (a lazy val, compiled exactly once,
    // outside of any per-run seeding), THEN seeds scala.util.Random, THEN calls doSim
    // — nothing consumes Random between the seed and the SparseMemory fill. Compiling
    // BEFORE seeding (as an earlier attempt here did) lets compile() itself consume
    // Random state, shifting the downstream fill content even with the "same" seed
    // value — confirmed to matter: that ordering reached Lend cleanly and settled at
    // exactly 47 kept steps by cycle 164, NOT reproducing the real failure.
    val simSeed = 115042236
    val compiled = M68kSim().withVerilator.compile(new FuzzCoreDut)
    scala.util.Random.setSeed(simSeed)
    compiled.doSim("mihang", simSeed) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val handle = new WhiteboxCapture.Handle

      def captureWb(w: m68k040.execute.WbObs): Unit = {
        if (w.valid.toBoolean) {
          handle.onWb(
            w.robId.toInt,
            WhiteboxCapture.Wb(
              dstArch   = w.dstArch.toInt,
              result    = w.result.toLong & 0xffffffffL,
              intWrite  = w.intWrite.toBoolean,
              nzvc      = w.nzvc.toInt,
              nzvcWrite = w.nzvcWrite.toBoolean,
              x         = if (w.x.toBoolean) 1 else 0,
              xWrite    = w.xWrite.toBoolean, divRem = w.divRem.toBoolean,
              keepCommit = w.keepCommit.toBoolean))
        }
      }

      var rawCommits = 0
      val rawPcs = scala.collection.mutable.ArrayBuffer[(Int, Long)]()
      var lastUcA = false
      var lastUcP = -1
      var postFreezeCycles = 0
      var cycleCount = 0

      cd.onSamplings {
        cycleCount += 1
        // Task #139 mechanism #2: log EVERY SQ alloc from cycle 0, unconditionally, so
        // a later-observed orphaned/stuck SQ head can be traced back to its TRUE
        // allocating instruction (PC read from rob.logic.pcStore at the alloc cycle
        // itself) rather than misattributed to whatever instruction currently owns
        // that same robId number after the ROB has cycled back around and reused it.
        val sqA = dut.lsEu.logic.sq
        if (sqA.io.alloc.valid.toBoolean) {
          val aRid = sqA.io.alloc.payload.robId.toInt
          val aPaddr = sqA.io.alloc.payload.paddr.toLong & 0xffffffffL
          val aPc = dut.rob.logic.pcStore(aRid).toLong & 0xffffffffL
          println(f"[mihang] SQ-ALLOC robId=$aRid paddr=0x$aPaddr%08x ownerPc=0x$aPc%08x cyc=$cycleCount rawCommits=$rawCommits")
        }
        // Task #139 finding #1 -- bisection pinned the trigger to whether the FIRST
        // mispredicted branch (bra.s Lskip_3) is robId 0 (no hang) vs robId 1 (hangs).
        // Trace ROB + SQ head/tail/flush from cycle 0 unconditionally for the first 60
        // cycles to see exactly what differs about ring-position 0 vs 1 at the moment
        // of the first flush.
        if (cycleCount >= 80 && cycleCount <= 220) {
          val robH = dut.rob.logic.head.toInt
          val robT = dut.rob.logic.tail.toInt
          val robDoFlush = dut.rob.logic.doFlushReg.toBoolean
          val sq0 = dut.lsEu.logic.sq
          val sqH = sq0.head.toInt
          val sqT = sq0.tail.toInt
          val sqFlushNow = sq0.io.flush.toBoolean
          val iqFlush = dut.iq.flushSignal.toBoolean
          val iqSlots = dut.iq.logic.slots.zipWithIndex.filter(_._1.sel.toBoolean).map { case (s, i) =>
            f"s$i(rid=${s.hot.robId.toInt})"
          }.mkString(" ")
          val lsBusy = dut.lsEu.logic.busy.toBoolean
          val lsS1V  = dut.lsEu.logic.s1Valid.toBoolean
          val lsRid  = dut.lsEu.logic.s1Ctx.robId.toInt
          val lsFsm  = if (dut.lsEu.logic.dbgIsIdle.toBoolean) "IDLE"
                    else if (dut.lsEu.logic.dbgIsXlate.toBoolean) "XLATE"
                    else if (dut.lsEu.logic.dbgIsResolve.toBoolean) "RESOLVE"
                    else if (dut.lsEu.logic.dbgIsLaunch.toBoolean) "LAUNCH"
                    else if (dut.lsEu.logic.dbgIsWait.toBoolean) "WAIT"
                    else if (dut.lsEu.logic.dbgIsWaitA.toBoolean) "WAIT_A"
                    else if (dut.lsEu.logic.dbgIsWaitB.toBoolean) "WAIT_B"
                    else if (dut.lsEu.logic.dbgIsWaitSQ.toBoolean) "WAIT_SQ"
                    else "???"
          val lsIssueV = dut.lsEu.issuePort.valid.toBoolean
          val lsIssueR = dut.lsEu.issuePort.ready.toBoolean
          val lsIssueRid = dut.lsEu.issuePort.payload.robId.toInt
          println(f"[mihang] cyc=$cycleCount%3d robHead=$robH robTail=$robT robDoFlush=$robDoFlush sqHead=$sqH sqTail=$sqT sqFlush=$sqFlushNow iqFlush=$iqFlush iqSlots=[$iqSlots] lsBusy=$lsBusy lsS1V=$lsS1V lsRid=$lsRid lsFsm=$lsFsm lsIssueV=$lsIssueV lsIssueR=$lsIssueR lsIssueRid=$lsIssueRid")
        }
        captureWb(dut.eu0.logic.wbObs)
        captureWb(dut.eu1.logic.wbObs)
        captureWb(dut.lsEu.logic.wbObs); captureWb(dut.divEu.logic.wbObs);
        {
          val bw = dut.branchEu.logic.wbObs
          if (bw.valid.toBoolean) {
            val anWr = bw.anWrite.toBoolean
            handle.onWb(
              bw.robId.toInt,
              WhiteboxCapture.Wb(
                dstArch   = if (anWr) bw.anArch.toInt else 0,
                result    = if (anWr) bw.anData.toLong & 0xffffffffL else 0L,
                intWrite  = anWr,
                nzvc      = 0,
                nzvcWrite = false,
                x         = 0,
                xWrite    = false))
          }
        }
        var firedThisCycle = 0
        for (k <- 0 until 2) {
          val c = dut.rob.logic.commitObs(k)
          if (c.fire.toBoolean) {
            firedThisCycle += 1
            val pc = c.pc.toLong & 0xffffffffL
            rawCommits += 1
            if (rawPcs.size < 80) rawPcs += ((rawCommits, pc))
            println(f"[mihang] commit #$rawCommits port=$k robId=${c.robId.toInt} pc=0x$pc%08x")
            handle.onCommit(c.robId.toInt, pc,
              sysByte = c.sysByte.toInt & 0xff, a7 = c.a7.toLong & 0xffffffffL,
              msp = dut.rob.logic.exc.ss.msp.toLong & 0xffffffffL,
              isp = dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL)
          }
        }
        if (firedThisCycle == 2) println(f"[mihang] *** DUAL-RETIRE this cycle (both ports fired) ***")

        // Task #139: watch every completion(k) + branchCompletion event unconditionally
        // (cheap for this tiny minimized repro).
        for (k <- 0 until 4) {
          val c = dut.rob.logic.completion(k)
          if (c.valid.toBoolean) {
            val rid = c.payload.toInt
            println(f"[mihang] completion(port=$k) valid robId=$rid rawCommits=$rawCommits")
          }
        }
        {
          val bc = dut.rob.logic.branchCompletion
          if (bc.valid.toBoolean) {
            val rid = bc.payload.robId.toInt
            println(f"[mihang] branchCompletion valid robId=$rid mispredict=${bc.payload.mispredict.toBoolean} nextPc=0x${bc.payload.nextPc.toLong & 0xffffffffL}%08x rawCommits=$rawCommits")
          }
        }
        // Task #139 finding #1 follow-up: pin down the exact off-by-one in LsEuPlugin's
        // RESOLVE-stage completion capture (compRobId := s1Ctx.robId). Watch the LS EU's
        // own issue/s1/completion signals every cycle once we're near the freeze window.
        if (rawCommits >= 1) {
          val lsBusy   = dut.lsEu.logic.busy.toBoolean
          val lsS1V    = dut.lsEu.logic.s1Valid.toBoolean
          val lsS1Rid  = dut.lsEu.logic.s1Ctx.robId.toInt
          val lsIssueV = dut.lsEu.issuePort.valid.toBoolean
          val lsIssueR = dut.lsEu.issuePort.ready.toBoolean
          val lsIssueRid = dut.lsEu.issuePort.payload.robId.toInt
          val lsCompV  = dut.lsEu.logic.compValid.toBoolean
          val lsCompRid = dut.lsEu.logic.compRobId.toInt
          val fsmState = if (dut.lsEu.logic.dbgIsIdle.toBoolean) "IDLE"
                    else if (dut.lsEu.logic.dbgIsXlate.toBoolean) "XLATE"
                    else if (dut.lsEu.logic.dbgIsResolve.toBoolean) "RESOLVE"
                    else if (dut.lsEu.logic.dbgIsLaunch.toBoolean) "LAUNCH"
                    else if (dut.lsEu.logic.dbgIsWait.toBoolean) "WAIT"
                    else if (dut.lsEu.logic.dbgIsWaitA.toBoolean) "WAIT_A"
                    else if (dut.lsEu.logic.dbgIsWaitB.toBoolean) "WAIT_B"
                    else if (dut.lsEu.logic.dbgIsWaitSQ.toBoolean) "WAIT_SQ"
                    else "???"
          val sqFull = dut.lsEu.logic.sq.io.full.toBoolean
          println(f"[mihang] lsEu busy=$lsBusy s1V=$lsS1V s1Rid=$lsS1Rid issueV=$lsIssueV issueR=$lsIssueR issueRid=$lsIssueRid compV=$lsCompV compRid=$lsCompRid fsm=$fsmState sqFull=$sqFull rawCommits=$rawCommits")
          val sq = dut.lsEu.logic.sq
          val sqHead = sq.head.toInt
          val sqTail = sq.tail.toInt
          val sqDrainBusy = sq.drainBusy.toBoolean
          val sqDrainValid = sq.io.drain.valid.toBoolean
          val sqDrainAck = sq.io.drainAck.toBoolean
          val sqFlush = sq.io.flush.toBoolean
          val sqHeadValid = sq.valids(sqHead).toBoolean
          val sqHeadCommitted = sq.committed(sqHead).toBoolean
          val sqHeadRobId = sq.robIds(sqHead).toInt
          val robHeadNow = dut.rob.logic.head.toInt
          val robTailNow = dut.rob.logic.tail.toInt
          val ownerPc = dut.rob.logic.pcStore(sqHeadRobId).toLong & 0xffffffffL
          val ownerComplete = dut.rob.logic.completes(sqHeadRobId).toBoolean
          val ownerMispredict = dut.rob.logic.mispredictStore(sqHeadRobId).toBoolean
          println(f"[mihang]   sq head=$sqHead tail=$sqTail drainBusy=$sqDrainBusy drainValid=$sqDrainValid drainAck=$sqDrainAck flush=$sqFlush headValid=$sqHeadValid headCommitted=$sqHeadCommitted headRobId=$sqHeadRobId robHead=$robHeadNow robTail=$robTailNow ownerPc=0x$ownerPc%08x ownerComplete=$ownerComplete ownerMispredict=$ownerMispredict rawCommits=$rawCommits")
          // Dump ALL resident SQ slots (not just head), each cross-referenced against
          // its own ROB owner's pcStore/completes/mispredictStore, to find which entry
          // fwdStall really keys on and why THAT entry never retires.
          val slots = (0 until 8).filter(i => sq.valids(i).toBoolean).map { i =>
            val rid = sq.robIds(i).toInt
            val opc = dut.rob.logic.pcStore(rid).toLong & 0xffffffffL
            val ocomp = dut.rob.logic.completes(rid).toBoolean
            f"slot$i(rid=$rid,committed=${sq.committed(i).toBoolean},ownerPc=0x$opc%08x,ownerComplete=$ocomp)"
          }.mkString(" ")
          val eaAuto = dut.lsEu.logic.u1.eaAuto.toEnum
          val curIsLoad = dut.lsEu.logic.isLoad.toBoolean
          val curIsStore = dut.lsEu.logic.isStore.toBoolean
          val fwdQRid = dut.lsEu.logic.sq.io.fwd.query.robId.toInt
          val fwdHit = dut.lsEu.logic.sq.io.fwd.rsp.hit.toBoolean
          val fwdStallSig = dut.lsEu.logic.sq.io.fwd.rsp.stall.toBoolean
          println(f"[mihang]   sqSlots: $slots | curEaAuto=$eaAuto curIsLoad=$curIsLoad curIsStore=$curIsStore fwdQueryRid=$fwdQRid fwdHit=$fwdHit fwdStall=$fwdStallSig rawCommits=$rawCommits")
          // Task #139 IssueQueuePlugin fix verification: watch for a same-cycle
          // issue/flush race (the exact mechanism the fix targets) anywhere in this
          // run, not just a fixed early cycle window -- needed for the larger
          // 46-instruction repro where the freeze happens much later.
          val iqFlushNow = dut.iq.flushSignal.toBoolean
          val lsIssueVNow = dut.lsEu.issuePort.valid.toBoolean
          val lsIssueRNow = dut.lsEu.issuePort.ready.toBoolean
          val lsIssueRidNow = dut.lsEu.issuePort.payload.robId.toInt
          if (iqFlushNow || (lsIssueVNow && lsIssueRNow)) {
            // Task #139 mechanism #2: disambiguate "robId 47 fires twice because
            // ROB tail wrapped around between the two fires" (two different
            // macro-ops coincidentally sharing the slot number) vs "genuine
            // same-robId double-issue" (tail unchanged / not wrapped between fires).
            val robTailNow = dut.rob.logic.tail.toInt
            println(f"[mihang]   iqFlush=$iqFlushNow lsIssueFire=${lsIssueVNow && lsIssueRNow} lsIssueRid=$lsIssueRidNow robTail=$robTailNow rawCommits=$rawCommits")
          }
        }
        val ucA = dut.dec.logic.ucActive.toBoolean
        val ucP = dut.dec.logic.ucPc.toInt
        if (ucA != lastUcA || (ucA && ucP != lastUcP)) {
          println(f"[mihang] uc cycle~ ucActive=$ucA ucPc=$ucP rawCommits=$rawCommits")
        }
        lastUcA = ucA; lastUcP = ucP

        // Task #139 mechanism #2: live-trace which MI ROM entry the decoder actually
        // selects, on any cycle a mem-indirect op is flagged. Gate on ucIsMemInd alone
        // (not fed.valid&&fed.ready) since ucIsMemInd is combinational off `fed` and we
        // want to see it regardless of whether this exact cycle's group gets accepted.
        val ucIsMi = dut.dec.logic.ucIsMemInd.toBoolean
        if (ucIsMi) {
          val miEntry   = dut.dec.logic.ucMiEntry.toInt
          val miLine    = dut.dec.logic.ucLine.toInt
          val miOpmode  = dut.dec.logic.ucOpmode.toInt
          val miAluSrc  = dut.dec.logic.ucAluSrcMi.toBoolean
          val miMoveSrc = dut.dec.logic.ucMoveSrcMi.toBoolean
          val miMoveDst = dut.dec.logic.ucMoveDstMi.toBoolean
          val miSrcEaEa = dut.dec.logic.ucMoveSrcMiEaEa.toBoolean
          val miDstEaEa = dut.dec.logic.ucMoveDstMiEaEa.toBoolean
          val miBothMi  = dut.dec.logic.ucMoveBothMi.toBoolean
          val miFlagsOnly = dut.dec.logic.ucMiFlagsOnly.toBoolean
          val fedV = dut.dec.logic.fed.valid.toBoolean
          val fedR = dut.dec.logic.fed.ready.toBoolean
          val fedPc = dut.dec.logic.fed.payload.packets(0).pc.toLong & 0xffffffffL
          println(f"[mihang] MI-DECODE entry=$miEntry line=$miLine opmode=$miOpmode aluSrc=$miAluSrc " +
            f"moveSrc=$miMoveSrc moveDst=$miMoveDst srcEaEa=$miSrcEaEa dstEaEa=$miDstEaEa bothMi=$miBothMi " +
            f"flagsOnly=$miFlagsOnly fedV=$fedV fedR=$fedR fedPc=0x$fedPc%08x rawCommits=$rawCommits")
        }

        // Once we're at/past the freeze point, watch fetch/decode-push/ROB-occupancy
        // every cycle for a bounded window to see which resource stops moving.
        if (rawCommits >= 6 && postFreezeCycles < 40) {
          postFreezeCycles += 1
          val fedV = dut.dec.logic.fed.valid.toBoolean
          val fedR = dut.dec.logic.fed.ready.toBoolean
          val qR   = dut.dec.logic.queue.io.push.ready.toBoolean
          val robHead = dut.rob.logic.head.toInt
          val robTail = dut.rob.logic.tail.toInt
          val robCnt  = dut.rob.logic.count.toInt
          val doFlush = dut.rob.logic.doFlushReg.toBoolean
          val flushPc = dut.rob.logic.flushPcReg.toLong & 0xffffffffL
          println(f"[mihang] post+$postFreezeCycles%2d fedV=$fedV fedR=$fedR qR=$qR robHead=$robHead robTail=$robTail robCnt=$robCnt " +
            f"doFlush=$doFlush flushPc=0x$flushPc%08x ucA=$ucA ucP=$ucP")
        }
      }

      FuzzDut.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.urp   #= 0
      dut.ctrl.logic.srp   #= 0
      dut.intCtrl.logic.iplIn #= 0
      dut.intCtrl.logic.iackAvec #= false
      dut.intCtrl.logic.iackVector #= 0
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)

      // Boot: supervisor (SR 0x2700), SSP/ISP 0x00100000, USP 0 — EXACTLY matching
      // FuzzLockStepSpec.scala:162-175. This was missing in earlier attempts here
      // (A7/arch-15 was left unseeded, garbage/X-valued) — the actual root cause,
      // confirmed by this fix alone reproducing the real HANG (see memory writeup).
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.usp #= 0L
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr  #= 15
      dut.wire.logic.seedData  #= BigInt(0x00100000L)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false

      // Match the REAL harness's cap exactly (FuzzLockStepSpec.scala:178:
      // cap = 3000 + 80*n, n=47 for this program) to test whether handle.result.size
      // genuinely reaches n=47 within budget (a timing/cap-sizing issue, not a real
      // hang) or never does (a genuine stall).
      val n = 47
      val cap = 3000 + 80 * n
      var guard = 0
      var reachedAt = -1
      while (guard < cap) {
        cd.waitSampling()
        guard += 1
        if (reachedAt < 0 && handle.result.size >= n) reachedAt = guard
      }
      println(f"[mihang] handle.result.size reached >= $n at cycle=$reachedAt (cap=$cap)")

      val kept = handle.result
      println(f"[mihang] rawCommits=$rawCommits keptSteps=${kept.size}")
      println(s"[mihang] raw PC stream (first ${rawPcs.size}): " + rawPcs.map { case (i, pc) => f"#$i=0x$pc%08x" }.mkString(" "))
      println(s"[mihang] WhiteboxCapture kept PC stream (${kept.size}): " +
        kept.zipWithIndex.map { case (co, i) => f"#${i+1}=0x${co.pc}%08x" }.mkString(" "))
    }
  }
}
