package m68k040.fuzz

import m68k040.{M68kSim, VerilatorTest}
import m68k040.lockstep.{LockStep, WhiteboxCapture}
import m68k040.oracle.{Musashi, OracleStep, ProgramAssembler}
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Random-instruction-stream lock-step fuzzing vs Musashi.
  *
  * Per seed: ProgGen emits a constrained-random program; Musashi traces it to
  * the terminal spin (stopPc = the final `bra.s Lend`, computed from the image
  * length); the full-core DUT runs the same image and the retired stream is
  * compared per instruction (PC, CCR, full SR, A7/MSP/ISP, written arch reg)
  * via the SAME LockStep comparator the directed suite uses, plus a final
  * byte-compare of the seeded sandbox window.
  *
  * On divergence, the failing program is GREEDILY MINIMIZED (chunked block
  * removal, re-running after each removal, keeping any divergence) and a
  * ready-to-paste repro report is printed. A found divergence is the PRODUCT:
  * it is reported in full and the suite fails at the END (after all seeds).
  *
  * Config (env):
  *   FUZZ_SEED_START (default 0), FUZZ_SEED_COUNT (default 5),
  *   FUZZ_BLOCKS (body template blocks per program, default 20),
  *   FUZZ_MINIMIZE (default 1), FUZZ_MIN_ATTEMPTS (default 80),
  *   FUZZ_ONLY / FUZZ_SKIP (comma-separated template tags),
  *   FUZZ_ALLOW_CROSSLINE (default 0; the explicit `crossline` template is
  *   always enabled, while this knob broadens generic memory templates).
  *
  * JVM discipline: ONE Verilator compile per JVM (lazy, shared across seeds
  * and minimization reruns). Run batches singly:
  *   JAVA_OPTS=-Xmx10g timeout 1800 ~/sbt/bin/sbt 'testOnly m68k040.fuzz.FuzzLockStepSpec'
  * (see tools/fuzz/sweep.sh). Do NOT share a JVM with the directed
  * ExecuteLockStepSpec — the forked test JVM peaks ~7.6GB per compile.
  */
object FuzzRunner {
  val loadAddr: Long = ProgramAssembler.DefaultLoadAddress

  sealed trait Outcome
  case object Pass extends Outcome
  /** generator/toolchain-side failure (assembly error, oracle runaway) — not a DUT divergence */
  final case class GenFail(reason: String) extends Outcome
  final case class Diverged(kind: String, detail: String, context: String) extends Outcome

  lazy val compiled = M68kSim().withVerilator.compile(new FuzzCoreDut)
  private var runIdx = 0

  /** Assemble + trace the oracle + run the DUT + compare. Pure (no asserts). */
  def run(src: String, simSeed: Int): Outcome = {
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => return GenFail(s"assemble: ${err.reason}")
    }
    val endPc = loadAddr + image.bytes.length - 2   // the final `bra.s Lend`

    val oracleSteps: Vector[OracleStep] =
      Musashi.assembleAndTrace(src, stopPc = Some(endPc)) match {
        case Right(v)  => v
        case Left(err) => return GenFail(s"oracle trace: ${err.reason}")
      }
    if (oracleSteps.isEmpty) return GenFail("oracle produced no steps")
    if ((oracleSteps.last.pc & 0xffffffffL) != (endPc & 0xffffffffL))
      return GenFail(f"oracle runaway: never reached endPc 0x$endPc%08x " +
                     f"(last pc 0x${oracleSteps.last.pc}%08x after ${oracleSteps.size} steps)")
    val n      = oracleSteps.size
    val oracle = oracleSteps

    val oracleMem: Map[Long, Int] = Musashi.assembleAndRun(src, stopPc = Some(endPc)) match {
      case Right(st) => st.memoryWrites
      case Left(err) => return GenFail(s"oracle run: ${err.reason}")
    }

    runIdx += 1
    // Determinism: spinal's SparseMemory() seeds its page-fill from the GLOBAL
    // scala.util.Random, so unwritten-page content (= wrong-path fetch bytes,
    // hence frontend timing) would otherwise depend on in-JVM run HISTORY —
    // making minimized repros unreproducible. Pin the global RNG per run so a
    // run is a pure function of (source, simSeed).
    scala.util.Random.setSeed(simSeed)
    var outcome: Outcome = Pass
    compiled.doSim(s"fuzz_$runIdx", simSeed) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val handle = new WhiteboxCapture.Handle

      // `secondDst`: this EU's `divRem` records are genuine SECOND architectural
      // destinations (DIVREM -> Dr, MULHI -> Dh) and must still be compared -- see
      // WhiteboxCapture.Wb.secondDst for why only the CPLX lane may set it.
      def captureWb(w: m68k040.execute.WbObs, secondDst: Boolean = false): Unit = {
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
              keepCommit = w.keepCommit.toBoolean, secondDst = secondDst))
        }
      }

      cd.onSamplings {
        captureWb(dut.eu0.logic.wbObs)
        captureWb(dut.eu1.logic.wbObs)
        captureWb(dut.lsEu.logic.wbObs); captureWb(dut.divEu.logic.wbObs, secondDst = true);
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
                xWrite    = false,
                // Scc <ea> memory-dest: the branch EU's op µop writes only T1 but IS the
                // instruction's single kept oracle step. Without this the whole Scc-mem
                // instruction vanished from the retire stream (fuzz cluster A, 40/57).
                keepCommit = bw.keepCommit.toBoolean))
          }
        }
        for (k <- 0 until 2) {
          val c = dut.rob.logic.commitObs(k)
          if (c.fire.toBoolean) {
            handle.onCommit(c.robId.toInt, c.pc.toLong & 0xffffffffL,
              sysByte = c.sysByte.toInt & 0xff, a7 = c.a7.toLong & 0xffffffffL,
              msp = dut.rob.logic.exc.ss.msp.toLong & 0xffffffffL,
              isp = dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL, macroLast = c.macroLast.toBoolean)
            // Task #144: directly map robId -> the lock-step comparator's own "idx"
            // (handle.result.size right after this commit is processed), sidestepping
            // ALL manual PC-arithmetic/instruction-length correlation -- the previous
            // 3 manual attempts this session were unreliable; this is authoritative.
            if (sys.env.contains("FUZZ_TRACE_CPLX")) {
              println(f"[cplxtrace] IDX-MAP port=$k robId=${c.robId.toInt} pc=0x${c.pc.toLong & 0xffffffffL}%08x -> idx=${handle.result.size - 1}")
            }
          }
        }
        {
          val c = dut.rob.logic.commitObs(2)
          if (c.fire.toBoolean) {
            handle.onExcCommit(c.pc.toLong & 0xffffffffL, c.sysByte.toInt & 0xff, c.a7.toLong & 0xffffffffL,
              if (c.ccrFoldValid.toBoolean) c.ccrFold.toInt & 0xf else -1,
              if (c.setCcr5Valid.toBoolean) c.setCcr5.toInt & 0x1f else -1,
              msp = dut.rob.logic.exc.ss.msp.toLong & 0xffffffffL,
              isp = dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL)
          }
        }
      }

      // TEMP DEBUG (task #139 CMP2/CHK2 hang, env-gated so it's a complete no-op
      // unless explicitly enabled -- guarantees EXACT reproduction since it rides
      // the REAL FuzzRunner.run harness rather than a hand-rolled boot sequence
      // that can silently diverge in wrong-path/SparseMemory fill content via
      // scala.util.Random state differences).
      if (sys.env.contains("FUZZ_TRACE_CPLX")) {
        var trCyc = 0
        var trLastLine = ""
        cd.onSamplings {
          trCyc += 1
          // Task #141: log EVERY X-flag scoreboard push/clear from cycle 0, to name
          // the exact instruction (PC + robId) that leaks a permanently-stuck sbX bit.
          if (dut.iq.pushPort.valid.toBoolean) {
            if (dut.iq.logic.pushUop0.writesX.toBoolean) {
              val pc = dut.iq.logic.pushUop0.pc.toLong & 0xffffffffL
              val rid = dut.iq.pushPort.payload(0).robId.toInt
              val pxd = dut.iq.logic.pushUop0.pXDst.toInt
              println(f"[cplxtrace] SBX-PUSH0 cyc=$trCyc%5d rid=$rid pc=0x$pc%08x pXDst=$pxd")
            }
            if (dut.iq.pushSlot1Port.toBoolean && dut.iq.logic.pushUop1.writesX.toBoolean) {
              val pc = dut.iq.logic.pushUop1.pc.toLong & 0xffffffffL
              val rid = dut.iq.pushPort.payload(1).robId.toInt
              val pxd = dut.iq.logic.pushUop1.pXDst.toInt
              println(f"[cplxtrace] SBX-PUSH1 cyc=$trCyc%5d rid=$rid pc=0x$pc%08x pXDst=$pxd")
            }
            // Task #141: log every STATIC sbInt claim (the `.otherwise` branch of
            // IssueQueuePlugin.scala:723-727 -- pdstValid, not LS/CPLX/aluSlow) to
            // name whichever instruction claims int-physreg 42 (the confirmed-stuck
            // bit) and check whether it's later flushed before its completion clear.
            if (dut.iq.logic.pushUop0.pdstValid.toBoolean) {
              val pc = dut.iq.logic.pushUop0.pc.toLong & 0xffffffffL
              val rid = dut.iq.pushPort.payload(0).robId.toInt
              val pdst = dut.iq.logic.pushUop0.pdst.toInt
              println(f"[cplxtrace] SBINT-PUSH0 cyc=$trCyc%5d rid=$rid pc=0x$pc%08x pdst=$pdst")
            }
            if (dut.iq.pushSlot1Port.toBoolean && dut.iq.logic.pushUop1.pdstValid.toBoolean) {
              val pc = dut.iq.logic.pushUop1.pc.toLong & 0xffffffffL
              val rid = dut.iq.pushPort.payload(1).robId.toInt
              val pdst = dut.iq.logic.pushUop1.pdst.toInt
              println(f"[cplxtrace] SBINT-PUSH1 cyc=$trCyc%5d rid=$rid pc=0x$pc%08x pdst=$pdst")
            }
            // Task #141: log every writesNzvc push to name whoever claims pNzvcDst=7/8.
            if (dut.iq.logic.pushUop0.writesNzvc.toBoolean) {
              val pc = dut.iq.logic.pushUop0.pc.toLong & 0xffffffffL
              val rid = dut.iq.pushPort.payload(0).robId.toInt
              val pnd = dut.iq.logic.pushUop0.pNzvcDst.toInt
              val isLsN = dut.iq.logic.push0IsLsNzvc.toBoolean
              println(f"[cplxtrace] SBNZVC-PUSH0 cyc=$trCyc%5d rid=$rid pc=0x$pc%08x pNzvcDst=$pnd isLsNzvc=$isLsN")
            }
            if (dut.iq.pushSlot1Port.toBoolean && dut.iq.logic.pushUop1.writesNzvc.toBoolean) {
              val pc = dut.iq.logic.pushUop1.pc.toLong & 0xffffffffL
              val rid = dut.iq.pushPort.payload(1).robId.toInt
              val pnd = dut.iq.logic.pushUop1.pNzvcDst.toInt
              val isLsN = dut.iq.logic.push1IsLsNzvc.toBoolean
              println(f"[cplxtrace] SBNZVC-PUSH1 cyc=$trCyc%5d rid=$rid pc=0x$pc%08x pNzvcDst=$pnd isLsNzvc=$isLsN")
            }
            // Task #144: op + psrcB (physical) at IQ-push time, keyed by robId/pc together
            // -- unambiguously identifies which mu-op (of a multi-mu-op crack) a given
            // psrcB value belongs to, unlike a rename-time trace which lacks robId.
            {
              val pc = dut.iq.logic.pushUop0.pc.toLong & 0xffffffffL
              val rid = dut.iq.pushPort.payload(0).robId.toInt
              val op = dut.iq.logic.pushUop0.op.toEnum
              val psrcB = dut.iq.logic.pushUop0.psrcB.toInt
              val psrcBValid = dut.iq.logic.pushUop0.psrcBValid.toBoolean
              val ucPc = dut.dec.logic.ucPc.toInt
              val ucAct = dut.dec.logic.ucActive.toBoolean
              println(f"[cplxtrace] PUSH0-OP cyc=$trCyc%5d rid=$rid pc=0x$pc%08x op=$op psrcB=$psrcB psrcBValid=$psrcBValid ucPc=$ucPc ucActive=$ucAct")
            }
            if (dut.iq.pushSlot1Port.toBoolean) {
              val pc = dut.iq.logic.pushUop1.pc.toLong & 0xffffffffL
              val rid = dut.iq.pushPort.payload(1).robId.toInt
              val op = dut.iq.logic.pushUop1.op.toEnum
              val psrcB = dut.iq.logic.pushUop1.psrcB.toInt
              val psrcBValid = dut.iq.logic.pushUop1.psrcBValid.toBoolean
              val ucPc = dut.dec.logic.ucPc.toInt
              val ucAct = dut.dec.logic.ucActive.toBoolean
              println(f"[cplxtrace] PUSH1-OP cyc=$trCyc%5d rid=$rid pc=0x$pc%08x op=$op psrcB=$psrcB psrcBValid=$psrcBValid ucPc=$ucPc ucActive=$ucAct")
            }
          }
          val deV = dut.divEu.issuePort.valid.toBoolean
          val deR = dut.divEu.issuePort.ready.toBoolean
          val deBusy = dut.divEu.logic.busy.toBoolean
          val iqSlots = dut.iq.logic.slots.zipWithIndex.filter(_._1.sel.toBoolean).map { case (s, i) =>
            f"s$i(rid=${s.hot.robId.toInt},op=${s.hot.op.toEnum},lsWait=${s.lsWait.toBoolean},cplxWait=${s.cplxWait.toBoolean},trig=0x${s.triggers.toBigInt.toString(16)},pA=${s.hot.psrcA.toInt}(v=${s.hot.psrcAValid.toBoolean}),pB=${s.hot.psrcB.toInt}(v=${s.hot.psrcBValid.toBoolean}),pC=${s.hot.psrcC.toInt}(v=${s.hot.psrcCValid.toBoolean}),readsNzvc=${s.hot.readsNzvc.toBoolean},pNzvcSrc=${s.hot.pNzvcSrc.toInt})"
          }.mkString(" ")
          val lsBusyHex = dut.iq.logic.lsBusy.toBigInt.toString(16)
          val sbXBusyHex = dut.iq.logic.sbX.busy.toBigInt.toString(16)
          val sbIntBusyHex = dut.iq.logic.sbInt.busy.toBigInt.toString(16)
          val sbNzvcBusyHex = dut.iq.logic.sbNzvc.busy.toBigInt.toString(16)
          val lsNzvcBusyHex = dut.iq.logic.lsNzvcBusy.toBigInt.toString(16)
          val lsWakeV = dut.iq.lsWakeupPort.valid.toBoolean
          val lsWakeP = dut.iq.lsWakeupPort.payload.toInt
          val lsNzvcWakeV = dut.iq.lsNzvcWakeup.valid.toBoolean
          val lsNzvcWakeP = dut.iq.lsNzvcWakeup.payload.toInt
          // Task #144: value-level taps -- what does the LS EU actually load, and what
          // does the ALU EU actually compute (result + flags), keyed by robId, so a
          // wrong VALUE (not a control-flow/scoreboard issue -- those are cleared, see
          // fuzz-campaign-divergence-2026-07-16 memory) can be caught directly.
          if (dut.lsEu.logic.compValid.toBoolean) {
            val rid = dut.lsEu.logic.compRobId.toInt
            val dat = dut.lsEu.logic.compData.toLong & 0xffffffffL
            val nzvc = dut.lsEu.logic.compNzvc.toInt & 0xf
            val nzvcW = dut.lsEu.logic.compNzvcWrite.toBoolean
            println(f"[cplxtrace] LSU-COMP cyc=$trCyc%5d rid=$rid data=0x$dat%08x nzvc=0x$nzvc%x nzvcWrite=$nzvcW")
          }
          if (dut.eu0.logic.fastFire.toBoolean) {
            val rid = dut.eu0.logic.s1Ctx.robId.toInt
            val res = dut.eu0.logic.mergedResult.toLong & 0xffffffffL
            val nzvc = dut.eu0.logic.finalNzvc.toInt & 0xf
            println(f"[cplxtrace] EU0-FIRE cyc=$trCyc%5d rid=$rid result=0x$res%08x nzvc=0x$nzvc%x")
          }
          if (dut.eu1.logic.fastFire.toBoolean) {
            val rid = dut.eu1.logic.s1Ctx.robId.toInt
            val res = dut.eu1.logic.mergedResult.toLong & 0xffffffffL
            val nzvc = dut.eu1.logic.finalNzvc.toInt & 0xf
            println(f"[cplxtrace] EU1-FIRE cyc=$trCyc%5d rid=$rid result=0x$res%08x nzvc=0x$nzvc%x")
          }
          // Task #144: does the store mu-op's architectural srcBReg (pre-rename) resolve
          // to a physical register (psrcB) that matches the CURRENT RAT mapping? Print
          // for both dispatch slots whenever the group fires, to correlate against DIV's
          // own pdst=5 (SBINT-PUSH trace) and see if the store ever reads srcBReg==2 (D2).
          {
            val fireV = dut.ren.logic.fire.toBoolean
            val uop1  = dut.ren.logic.uop1Sig.toBoolean
            if (fireV) {
              val pc0 = dut.ren.logic.dec0.pc.toLong & 0xffffffffL
              val srcBReg0 = dut.ren.logic.dec0.srcBReg.toInt
              val srcBValid0 = dut.ren.logic.dec0.srcBValid.toBoolean
              val psrcB0 = dut.ren.logic.raw(0).psrcB.toInt
              val psrcBValid0 = dut.ren.logic.raw(0).psrcBValid.toBoolean
              println(f"[cplxtrace] REN-SLOT0 cyc=$trCyc%5d pc=0x$pc0%08x srcBReg=$srcBReg0 srcBValid=$srcBValid0 psrcB=$psrcB0 psrcBValid=$psrcBValid0")
            }
            if (fireV && uop1) {
              val pc1 = dut.ren.logic.dec1.pc.toLong & 0xffffffffL
              val srcBReg1 = dut.ren.logic.dec1.srcBReg.toInt
              val srcBValid1 = dut.ren.logic.dec1.srcBValid.toBoolean
              val psrcB1 = dut.ren.logic.raw(1).psrcB.toInt
              val psrcBValid1 = dut.ren.logic.raw(1).psrcBValid.toBoolean
              println(f"[cplxtrace] REN-SLOT1 cyc=$trCyc%5d pc=0x$pc1%08x srcBReg=$srcBReg1 srcBValid=$srcBValid1 psrcB=$psrcB1 psrcBValid=$psrcBValid1")
            }
          }
          if (dut.divEu.logic.wbObs.valid.toBoolean) {
            val w = dut.divEu.logic.wbObs
            val rid = w.robId.toInt
            val res = w.result.toLong & 0xffffffffL
            val dst = w.dstArch.toInt
            val nzvc = w.nzvc.toInt & 0xf
            println(f"[cplxtrace] DIV-WB cyc=$trCyc%5d rid=$rid dstArch=$dst result=0x$res%08x nzvc=0x$nzvc%x")
          }
          // Task #144: watch ucCtx.miOther/miOtherValid directly across the mu-code
          // engine's sequencing, whenever active -- sidesteps guessing which cycle
          // corresponds to which row (the push-time ucPc tap turned out stale/offset
          // by an unknown pipeline delay).
          if (dut.dec.logic.ucActive.toBoolean) {
            val ucPc = dut.dec.logic.ucPc.toInt
            val miOther = dut.dec.logic.ucCtx.miOther.toInt
            val miOtherValid = dut.dec.logic.ucCtx.miOtherValid.toBoolean
            println(f"[cplxtrace] UC-ACTIVE cyc=$trCyc%5d ucPc=$ucPc miOther=$miOther miOtherValid=$miOtherValid")
          }
          // Task #144 final check: is memory-indirect EA detection itself firing on
          // the SECOND (post-flush, real) decode attempt at all? Print every cycle
          // ucIsMemInd is true (decode-time, independent of whether ucBegin actually
          // latches it) to see every candidate entry point, not just successful ones.
          if (dut.dec.logic.ucIsMemInd.toBoolean) {
            val moveDst = dut.dec.logic.ucMoveDstMi.toBoolean
            println(f"[cplxtrace] UC-ISMEMIND cyc=$trCyc%5d ucMoveDstMi=$moveDst")
          }
          // Task #144: fire exactly on ucBegin -- the actual entry-point decision --
          // to see precisely what packet/entry was chosen, bypassing all downstream
          // pipeline-stage-offset guessing.
          if (dut.dec.logic.ucBegin.toBoolean) {
            val entryPc = dut.dec.logic.ucEntryPkt.pc.toLong & 0xffffffffL
            val entryW0 = dut.dec.logic.ucEntryPkt.words(0).toLong & 0xffffL
            val realEntry = dut.dec.logic.ucRealEntry.toInt
            val isMemInd = dut.dec.logic.ucIsMemInd.toBoolean
            val pendV = dut.dec.logic.ucPendValid.toBoolean
            val fedV = dut.dec.logic.fed.valid.toBoolean
            val moveDstMi = dut.dec.logic.ucMoveDstMi.toBoolean
            println(f"[cplxtrace] UC-BEGIN cyc=$trCyc%5d entryPc=0x$entryPc%08x entryW0=0x$entryW0%04x realEntry=$realEntry isMemInd=$isMemInd ucPendValid=$pendV fedValid=$fedV moveDstMi=$moveDstMi")
          }
          // Task #144: does `fed` ever carry the target instruction's PC at all on the
          // real (post-flush) attempt, regardless of whether ucIsMemInd fires for it?
          if (dut.dec.logic.fed.valid.toBoolean) {
            val pc0 = dut.dec.logic.fed.payload.packets(0).pc.toLong & 0xffffffffL
            val pc1 = dut.dec.logic.fed.payload.packets(1).pc.toLong & 0xffffffffL
            if ((pc0 >= 0x40800108L && pc0 <= 0x40800125L) || (pc1 >= 0x40800108L && pc1 <= 0x40800125L)) {
              val s1v = dut.dec.logic.fed.payload.slot1Valid.toBoolean
              val ucode = dut.dec.logic.slot1IsUcodeEarly.toBoolean
              val memInd = dut.dec.logic.slot1IsMemIndEarly.toBoolean
              val pendV = dut.dec.logic.ucPendValid.toBoolean
              val enter0 = dut.dec.logic.ucEnterSlot0.toBoolean
              val w0 = dut.dec.logic.fed.payload.packets(1).words(0).toLong & 0xffffL
              val w1 = dut.dec.logic.fed.payload.packets(1).words(1).toLong & 0xffffL
              val w2 = dut.dec.logic.fed.payload.packets(1).words(2).toLong & 0xffffL
              val w3 = dut.dec.logic.fed.payload.packets(1).words(3).toLong & 0xffffL
              val w4 = dut.dec.logic.fed.payload.packets(1).words(4).toLong & 0xffffL
              val wc = dut.dec.logic.fed.payload.packets(1).wordCount.toInt
              val simp = dut.dec.logic.fed.payload.packets(1).simple.toBoolean
              val cplx = dut.dec.logic.fed.payload.packets(1).complex.toBoolean
              println(f"[cplxtrace] FED-VALID cyc=$trCyc%5d pc0=0x$pc0%08x pc1=0x$pc1%08x slot1Valid=$s1v slot1IsUcodeEarly=$ucode slot1IsMemIndEarly=$memInd ucPendValid=$pendV ucEnterSlot0=$enter0 w0=0x$w0%04x w1=0x$w1%04x w2=0x$w2%04x w3=0x$w3%04x w4=0x$w4%04x wc=$wc simple=$simp complex=$cplx")
            }
          }
          // D1 removed the single sticky front-FSM poison bit. Report the actual
          // poison owners: launched aligned descriptors and the cold split replay.
          val alignedPoisoned = dut.lsEu.logic.alignedPoisoned.exists(_.toBoolean)
          val splitPoisoned   = dut.lsEu.logic.bkPoisoned.toBoolean
          val sqFlush  = dut.lsEu.sqFlushSig.toBoolean
          val doFlush  = dut.rob.logic.doFlushReg.toBoolean
          val lsuBusy  = dut.lsEu.logic.busy.toBoolean
          val lsuS1V   = dut.lsEu.logic.s1Valid.toBoolean
          val lsuCompV = dut.lsEu.logic.compValid.toBoolean
          val line = f"deIssueV=$deV deIssueR=$deR deBusy=$deBusy robHead=${dut.rob.logic.head.toInt} lsBusy=0x$lsBusyHex sbXBusy=0x$sbXBusyHex sbIntBusy=0x$sbIntBusyHex sbNzvcBusy=0x$sbNzvcBusyHex lsNzvcBusy=0x$lsNzvcBusyHex alignedPoisoned=$alignedPoisoned splitPoisoned=$splitPoisoned sqFlush=$sqFlush doFlush=$doFlush lsuBusy=$lsuBusy lsuS1V=$lsuS1V lsuCompV=$lsuCompV iqSlots=[$iqSlots]"
          if (line != trLastLine || lsWakeV || lsNzvcWakeV || sqFlush || doFlush) {
            println(f"[cplxtrace] cyc=$trCyc%5d lsWakeV=$lsWakeV lsWakeP=$lsWakeP lsNzvcWakeV=$lsNzvcWakeV lsNzvcWakeP=$lsNzvcWakeP $line")
            trLastLine = line
          }
        }
      }

      FuzzDut.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      // Task #143: Musashi's oracle (m68k_ref.cpp cb_read8) returns a HARDCODED 0xFF
      // for any address it was never told about, deterministic and seed-independent.
      // The DUT's SparseMemory-backed dmem defaults unwritten bytes to a genuinely
      // RANDOM, seed-derived pattern instead -- structurally incompatible with
      // Musashi's fixed default. A program that reads a sandbox byte no earlier
      // instruction wrote (including one that FuzzRunner.minimize's greedy chunk
      // removal can manufacture by deleting the write while keeping the read) would
      // near-certainly diverge on the resulting CCR/register value even though
      // nothing in the RTL is wrong -- a false positive. Pre-fill the whole sandbox
      // window to 0xFF on the DUT side, matching Musashi's default exactly, so an
      // unwritten read agrees on both sides by construction. See
      // fuzz-campaign-divergence-2026-07-16 memory, "DEFINITIVE ROOT CAUSE CONFIRMED".
      for (a <- ProgGen.SandboxBase until (ProgGen.SandboxBase + ProgGen.SandboxSize)) {
        dmem.mem.write(a, 0xff.toByte)
      }
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
      dut.wire.logic.seedValid    #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)

      // Boot: supervisor (SR 0x2700), SSP/ISP 0x00100000, USP 0 (Musashi's boot
      // USP default — the MOVE-USP template always writes before reading).
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.usp #= 0L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L   // DE|IE -- "firmware already
                                                  // enabled the caches" (design doc
                                                  // section 5.2); IE is inert (this
                                                  // core's I-cache has no CACR
                                                  // consumer) but poked for
                                                  // documentation-of-intent parity
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr  #= 15
      dut.wire.logic.seedData  #= BigInt(0x00100000L)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= false

      var guard = 0
      val cap   = 3000 + 80 * n
      while (handle.result.size < n && guard < cap) { cd.waitSampling(); guard += 1 }

      if (handle.result.size < n) {
        val got = handle.result
        val tail = got.takeRight(3).map(c => f"pc=0x${c.pc}%08x").mkString(" ")
        outcome = Diverged("HANG",
          s"only ${got.size}/$n instructions committed within $cap cycles",
          s"last commits: $tail")
      } else {
        val res = LockStep.compare(handle.result.take(n), oracle)
        if (!res.ok) {
          val div = res.firstDivergence.get
          val i   = div.index.toInt
          val ctx = ((i - 3) max 0 until ((i + 2) min n)).map { j =>
            val c = handle.result(j); val s = oracle(j)
            f"  idx$j%3d dut{pc=0x${c.pc}%08x sr=0x${c.sr}%04x a7=0x${c.a7 & 0xffffffffL}%08x " +
              f"reg${c.archRegId}=0x${c.archRegWrite & 0xffffffffL}%08x(v=${c.archRegValid})} " +
              f"orc{pc=0x${s.pc}%08x sr=0x${s.sr}%04x a7=0x${s.a(7) & 0xffffffffL}%08x}"
          }.mkString("\n")
          outcome = Diverged("STEP", s"idx=$i ${div.detail}", ctx)
        } else {
          // Final sandbox memory compare (every sandbox byte is prologue-seeded,
          // so the oracle write map covers the full window).
          cd.waitSampling(300)
          // Compare only ORACLE-WRITTEN sandbox bytes (the directed checkMem
          // rule: the DUT's behavioral mem defaults unwritten bytes to a
          // non-zero pattern). The prologue writes EVERY sandbox byte, so for
          // generated programs this is the full window.
          val diffs = scala.collection.mutable.ArrayBuffer[String]()
          for (a <- ProgGen.SandboxBase until (ProgGen.SandboxBase + ProgGen.SandboxSize)
               if diffs.size < 16; expected <- oracleMem.get(a)) {
            val got = dmem.peekByte(a)
            if (got != (expected & 0xff))
              diffs += f"mem[0x$a%08x]: dut=0x$got%02x oracle=0x${expected & 0xff}%02x"
          }
          if (diffs.nonEmpty)
            outcome = Diverged("MEM", diffs.head, diffs.mkString("\n  "))
        }
      }
    }
    outcome
  }

  /** Greedy chunked minimization: repeatedly try removing chunks of removable
    * body blocks (sizes 8,4,2,1), keeping any removal that still diverges.
    * `pinKind` keeps a removal only if the divergence KIND matches the
    * original (a HANG stays a HANG) — useful when triaging a specific class;
    * None accepts any divergence (smaller repros). */
  def minimize(blocks: Vector[ProgGen.Block], simSeed: Int, maxAttempts: Int,
               pinKind: Option[String] = None): (Vector[ProgGen.Block], Outcome) = {
    var cur = blocks
    var lastDiv: Outcome = Diverged("?", "?", "")
    var attempts = 0
    def keeps(o: Outcome): Option[Diverged] = o match {
      case d: Diverged if pinKind.forall(_ == d.kind) => Some(d)
      case _                                          => None
    }
    for (chunk <- Seq(8, 4, 2, 1)) {
      var progress = true
      while (progress && attempts < maxAttempts) {
        progress = false
        var i = cur.length - chunk
        while (i >= 0 && attempts < maxAttempts) {
          val slice = cur.slice(i, i + chunk)
          if (slice.nonEmpty && slice.forall(_.removable)) {
            val cand = cur.patch(i, Nil, chunk)
            attempts += 1
            keeps(run(ProgGen.render(cand), simSeed)) match {
              case Some(d) => cur = cand; lastDiv = d; progress = true
              case None    => ()
            }
          }
          i -= chunk
        }
      }
    }
    (cur, lastDiv)
  }
}

class FuzzLockStepSpec extends AnyFunSuite {
  private def envInt(k: String, dflt: Int): Int = sys.env.get(k).map(_.trim.toInt).getOrElse(dflt)

  private val seedStart   = envInt("FUZZ_SEED_START", 0)
  private val seedCount   = envInt("FUZZ_SEED_COUNT", 5)
  private val nBlocks     = envInt("FUZZ_BLOCKS", 20)
  private val doMinimize  = envInt("FUZZ_MINIMIZE", 1) == 1
  private val minAttempts = envInt("FUZZ_MIN_ATTEMPTS", 80)
  // Minimize at most this many divergences per batch (minimization is ~1-3 min
  // each; later ones in a hot-bug batch are usually duplicates of one class).
  private val minimizeMax = envInt("FUZZ_MINIMIZE_MAX", 3)

  test(s"fuzz lock-step sweep: seeds [$seedStart, ${seedStart + seedCount})", VerilatorTest) {
    val divergedSeeds = scala.collection.mutable.ArrayBuffer[(Int, String)]()
    val genFails      = scala.collection.mutable.ArrayBuffer[(Int, String)]()

    for (seed <- seedStart until (seedStart + seedCount)) {
      val t0   = System.nanoTime()
      val prog = ProgGen.generate(seed, nBlocks)
      val simSeed = (seed * 0x9e3779b1 + 1) & 0x7fffffff
      val outcome = FuzzRunner.run(prog.source, simSeed)
      val dtGen = (System.nanoTime() - t0) / 1e9

      outcome match {
        case FuzzRunner.Pass =>
          println(f"[fuzz] seed=$seed PASS ($dtGen%.1fs)")
        case FuzzRunner.GenFail(reason) =>
          println(f"[fuzz] seed=$seed GENFAIL ($dtGen%.1fs): $reason")
          genFails += ((seed, reason))
        case d @ FuzzRunner.Diverged(kind, detail, _) =>
          val budgetLeft = divergedSeeds.size < minimizeMax
          println(f"[fuzz] seed=$seed DIVERGED[$kind] ($dtGen%.1fs): $detail" +
                  (if (doMinimize && budgetLeft) " — minimizing..." else " — reporting unminimized (budget)"))
          val pinKind = if (envInt("FUZZ_MIN_SAMEKIND", 0) == 1) Some(kind) else None
          val (minBlocks, minDiv) =
            if (doMinimize && budgetLeft) FuzzRunner.minimize(prog.blocks, simSeed, minAttempts, pinKind)
            else (prog.blocks, d: FuzzRunner.Outcome)
          val (mk, md, mc) = minDiv match {
            case FuzzRunner.Diverged(k2, d2, c2) => (k2, d2, c2)
            case _                               => (kind, detail, d.context)
          }
          val src = ProgGen.render(minBlocks)
          val report =
            s"""
               |===== FUZZ DIVERGENCE seed=$seed =====
               |kind: $mk
               |detail: $md
               |${if (mc.nonEmpty) s"context:\n$mc\n" else ""}
               |original: [$kind] $detail
               |minimized program (${minBlocks.size} blocks; prologue seeds retained):
               |$src
               |repro (regen):  FUZZ_SEED_START=$seed FUZZ_SEED_COUNT=1 FUZZ_BLOCKS=$nBlocks testOnly m68k040.fuzz.FuzzLockStepSpec
               |repro (direct): save the program above to prog.s, then
               |                FUZZ_PROG=prog.s FUZZ_PROBE_SEED=$simSeed testOnly m68k040.fuzz.FuzzProbeSpec
               |======================================
               |""".stripMargin
          println(report)
          divergedSeeds += ((seed, s"[$mk] $md"))
      }
    }

    println(s"[fuzz] sweep done: ${seedCount} seeds, ${divergedSeeds.size} divergences, ${genFails.size} generator failures")
    divergedSeeds.foreach { case (s, d) => println(s"[fuzz]   seed=$s $d") }
    genFails.foreach      { case (s, d) => println(s"[fuzz]   genfail seed=$s $d") }
    assert(genFails.isEmpty, s"generator/toolchain failures on seeds ${genFails.map(_._1).mkString(",")}")
    assert(divergedSeeds.isEmpty,
      s"lock-step divergences on seeds ${divergedSeeds.map(_._1).mkString(",")} — see the FUZZ DIVERGENCE reports above")
  }
}
