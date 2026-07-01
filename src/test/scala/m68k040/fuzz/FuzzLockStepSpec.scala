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
  *   FUZZ_ALLOW_CROSSLINE (default 0 — see ProgGen exclusion list).
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

      cd.onSamplings {
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
        for (k <- 0 until 2) {
          val c = dut.rob.logic.commitObs(k)
          if (c.fire.toBoolean) {
            handle.onCommit(c.robId.toInt, c.pc.toLong & 0xffffffffL,
              sysByte = c.sysByte.toInt & 0xff, a7 = c.a7.toLong & 0xffffffffL,
              msp = dut.rob.logic.exc.ss.msp.toLong & 0xffffffffL,
              isp = dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL)
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

      FuzzDut.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.rootPtr   #= 0
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
