package m68k040.fuzz

import m68k040.VerilatorTest
import m68k040.oracle.ProgramAssembler
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

/** Directed lock-step probe for ONE specific hypothesis:
  *
  *   "when an older in-flight branch mispredicts and a younger `bsr`/`jsr` is
  *    already dispatched behind it, the flush squashes the call and fetch
  *    resumes PAST it, so the entire called subroutine tree is silently
  *    skipped."
  *
  * Motivation: three separately-documented real-hardware boot bugs in the
  * companion SoC repo (docs/BUG_calibration_word_misplaced_0d00.md, Parts 29
  * and 62) all share the signature "a subroutine tree MAME executes is never
  * executed on cpu040". Part 29 proved, by real-hardware ILA capture, that the
  * `bsr.w` at ROM 0x40800284 is correctly resolved by the branch EU
  * (redirect=1/mispredict=1, robId=18) and is then collaterally flushed by an
  * OLDER, unrelated `rts` misprediction (robId=16, PC 0x40804144) that reaches
  * the retire head first. Whether the flushed `bsr` is subsequently RE-executed
  * was never directly tested. This spec tests it.
  *
  * Method. Every program below is shaped so the younger `bsr`/`jsr` is
  * genuinely FETCHED AND DISPATCHED on the mispredicted (wrong) path AND is
  * also on the architecturally correct path -- i.e. the wrong path falls
  * straight through into the correct path at (or just before) the call. For the
  * conditional-branch shapes the mispredicting branch's resolved next-PC
  * (`RobPlugin.flushPcReg := nextPcRd0`) lands EXACTLY on the call opcode, the
  * tightest possible case for a "redirect skips the instruction at flushPc"
  * defect.
  *
  * Every program is checked by FULL MUSASHI LOCK-STEP (`FuzzRunner.run`), which
  * compares the DUT's committed PC/register/CCR stream instruction-by-
  * instruction against the reference model, plus a final memory compare. A
  * skipped call diverges on the very first committed PC after the redirect; a
  * doubly-executed call diverges on the call counter. Nothing here relies on
  * this spec's own expectation of what the RTL should do.
  *
  * `bsr flush probe: mispredict actually happens` guards against a VACUOUS
  * pass: it runs the same programs on the DUT with the ROB's `branchRedirect` /
  * `doFlushReg` / `flushPcReg` taps live and asserts a real commit-time
  * mispredict flush occurred, and reports where fetch was redirected to.
  *
  * Run:
  *   sbt "testOnly m68k040.fuzz.BsrFlushSkipSpec"
  */
object BsrFlushPrograms {

  private val Prologue =
    """	moveq	#0,%d4
      |	moveq	#0,%d5
      |	moveq	#0,%d6
      |	moveq	#0,%d7
      |""".stripMargin

  /** Wrong-path filler ops. Two bytes each, so `k` also sweeps the call site's
    * alignment within the 16-byte fetch line (k=7 straddles it with a 4-byte
    * `bsr.w`). */
  private def fillers(k: Int, reg: String = "%d4"): String =
    (0 until k).map(_ => s"\taddq.l\t#1,$reg").mkString("\n") + (if (k > 0) "\n" else "")

  /** The observed subroutine: bumps a counter and stamps the sandbox, so both a
    * SKIPPED call (counter stays 0) and a DOUBLE call (counter 2) are visible in
    * the lock-step register/memory stream, independently of PC comparison. */
  private val Callee =
    """Lsent:
      |	addq.l	#1,%d5
      |	move.l	%d5,0x4010
      |	rts
      |""".stripMargin

  private val Callee2 =
    """Lsent2:
      |	addq.l	#1,%d7
      |	move.l	%d7,0x4024
      |	rts
      |""".stripMargin

  private val Epilogue =
    """	move.l	%d4,0x4014
      |	move.l	%d5,0x4018
      |	move.l	%d6,0x401c
      |	move.l	%d7,0x4020
      |	bra.w	Ldone
      |""".stripMargin

  private def wrap(body: String): String =
    body + "Ldone:\nLend:\n\tbra.s\tLend\n"

  // ── Shape A: cold forward Bcc mispredicts; the call sits EXACTLY at the
  //    redirect PC and was already dispatched on the (fall-through) wrong path.
  def bccBsr(k: Int): String = wrap(
    Prologue +
    "\tmoveq\t#1,%d0\n\tcmp.l\t%d0,%d0\n" +          // Z=1
    "\tbeq.w\tLtgt\n" +                               // cold forward -> predicted NT, actually TAKEN
    fillers(k) +
    "Ltgt:\n\tbsr.w\tLsent\n" +
    "\taddq.l\t#1,%d6\n" +
    Epilogue + Callee)

  // ── Shape B: same, but the call at the redirect PC is a 6-byte absolute JSR.
  def bccJsrAbs(k: Int): String = wrap(
    Prologue +
    "\tmoveq\t#1,%d0\n\tcmp.l\t%d0,%d0\n" +
    "\tbeq.w\tLtgt\n" +
    fillers(k) +
    "Ltgt:\n\tjsr\tLsent\n" +
    "\taddq.l\t#1,%d6\n" +
    Epilogue + Callee)

  // ── Shape C: same, but an INDIRECT JSR (ibranch=1, the class Part 29's own
  //    older mispredicting instruction belonged to).
  def bccJsrInd(k: Int): String = wrap(
    Prologue +
    "\tmove.l\t#Lsent,%a1\n" +
    "\tmoveq\t#1,%d0\n\tcmp.l\t%d0,%d0\n" +
    "\tbeq.w\tLtgt\n" +
    fillers(k) +
    "Ltgt:\n\tjsr\t(%a1)\n" +
    "\taddq.l\t#1,%d6\n" +
    Epilogue + Callee)

  // ── Shape D: TWO back-to-back calls at/after the redirect PC.
  def bccTwoCalls(k: Int): String = wrap(
    Prologue +
    "\tmoveq\t#1,%d0\n\tcmp.l\t%d0,%d0\n" +
    "\tbeq.w\tLtgt\n" +
    fillers(k) +
    "Ltgt:\n\tbsr.w\tLsent\n\tbsr.w\tLsent2\n" +
    "\taddq.l\t#1,%d6\n" +
    Epilogue + Callee + Callee2)

  // ── Shape E: LATE-RESOLVING condition (DIVU-dependent), so the wrong-path
  //    window is deep and the younger call is provably in flight (issued, and
  //    for small k already EU-resolved) when the older branch resolves.
  def lateBccBsr(k: Int): String = wrap(
    Prologue +
    "\tmove.l\t#0x00030000,%d1\n" +
    "\tmoveq\t#7,%d2\n" +
    "\tdivu.w\t%d2,%d1\n" +                            // long latency
    "\ttst.w\t%d1\n" +                                 // quotient 0x6d3a -> Z=0
    "\tbne.w\tLtgt\n" +                                // cold forward -> predicted NT, actually TAKEN
    fillers(k) +
    "Ltgt:\n\tbsr.w\tLsent\n" +
    "\taddq.l\t#1,%d6\n" +
    Epilogue + Callee)

  // ── Shape F: the OLDER mispredicting instruction is an `rts` whose RAS
  //    prediction is wrong (the callee rewrites the return address on the
  //    stack). The wrong path falls straight through into the correct path, so
  //    the younger `bsr` is dispatched behind the `rts` and is ALSO on the
  //    correct path. This is Part 29's literal shape.
  def rtsBsr(k: Int): String = {
    require(k >= 1, "k>=1 so the RAS-predicted return address differs from the real one")
    wrap(
      Prologue +
      "\tbsr.w\tLredir\n" +                            // RAS pushes Lwrong
      "Lwrong:\n" +
      fillers(k) +
      "Lreal:\n\tbsr.w\tLsent\n" +
      "\taddq.l\t#1,%d6\n" +
      Epilogue +
      "Lredir:\n" +
      "\tmove.l\t#Lreal,(%sp)\n" +                     // real return != RAS prediction
      "\trts\n" +
      Callee)
  }

  // ── Shape G: the OLDER mispredicting instruction is itself a cold `bsr`
  //    (predicted not-taken). Its wrong path is the fall-through, which contains
  //    a younger `bsr` that IS architecturally executed -- just later, after the
  //    outer call returns. Tests that a flushed call re-executes even when the
  //    redirect PC is nowhere near it.
  def bsrColdBsr(k: Int): String = wrap(
    Prologue +
    "\tbsr.w\tLouter\n" +
    fillers(k) +                                       // architecturally LIVE
    "\tbsr.w\tLsent\n" +
    "\taddq.l\t#1,%d6\n" +
    Epilogue +
    "Louter:\n\taddq.l\t#1,%d7\n\tmove.l\t%d7,0x4024\n\trts\n" +
    Callee)

  // ── Shape H: a BTB-trained backward loop branch exits (predicted taken,
  //    actually not taken). `flushPcReg` = the branch's fall-through = exactly
  //    the `bsr` opcode.
  def loopExitBsr(trips: Int): String = wrap(
    Prologue +
    s"\tmoveq\t#$trips,%d4\n" +
    "Lloop:\n\tsubq.l\t#1,%d4\n\tbne.w\tLloop\n" +
    "\tbsr.w\tLsent\n" +
    "\taddq.l\t#1,%d6\n" +
    Epilogue + Callee)

  // ── Shape I: nested. The flushed younger `bsr` heads a 3-deep call chain, so
  //    a skip loses a whole subroutine TREE (the real-hardware signature), not
  //    just one frame.
  def bccBsrTree(k: Int): String = wrap(
    Prologue +
    "\tmoveq\t#1,%d0\n\tcmp.l\t%d0,%d0\n" +
    "\tbeq.w\tLtgt\n" +
    fillers(k) +
    "Ltgt:\n\tbsr.w\tLl1\n" +
    "\taddq.l\t#1,%d6\n" +
    Epilogue +
    "Ll1:\n\taddq.l\t#1,%d5\n\tbsr.w\tLl2\n\trts\n" +
    "Ll2:\n\taddq.l\t#1,%d5\n\tbsr.w\tLl3\n\trts\n" +
    "Ll3:\n\taddq.l\t#1,%d5\n\tmove.l\t%d5,0x4010\n\trts\n")

  // ── Shape J: the wrong path is longer than the whole ROB (depth 64), so the
  //    younger call is still sitting in the ibuf / decode / rename skid (not yet
  //    ROB-allocated) when the flush fires -- the other way a call could be
  //    "lost" without ever appearing in the ROB.
  def deepRobBsr(k: Int): String = wrap(
    Prologue +
    "\tmoveq\t#1,%d0\n\tcmp.l\t%d0,%d0\n" +
    "\tbeq.w\tLtgt\n" +
    fillers(k) +
    "Ltgt:\n\tbsr.w\tLsent\n" +
    "\taddq.l\t#1,%d6\n" +
    Epilogue + Callee)

  // ── Shape K: TWO older mispredicting branches stacked ahead of the call, so a
  //    second flush arrives while the first recovery is still in progress.
  def doubleMispredictBsr(k: Int): String = wrap(
    Prologue +
    "\tmoveq\t#1,%d0\n\tcmp.l\t%d0,%d0\n" +
    "\tbeq.w\tLm2\n" +
    fillers(k) +
    "Lm2:\n\tbeq.w\tLtgt\n" +
    fillers(k) +
    "Ltgt:\n\tbsr.w\tLsent\n" +
    "\taddq.l\t#1,%d6\n" +
    Epilogue + Callee)

  // ── Shape L: the re-executed call's target is far away (a cold I-cache line
  //    and a fresh fetch window), so re-execution costs a refill.
  def farCalleeBsr(k: Int): String = wrap(
    Prologue +
    "\tmoveq\t#1,%d0\n\tcmp.l\t%d0,%d0\n" +
    "\tbeq.w\tLtgt\n" +
    fillers(k) +
    "Ltgt:\n\tbsr.w\tLsent\n" +
    "\taddq.l\t#1,%d6\n" +
    Epilogue +
    "\t.space\t1024\n" + Callee)

  // ── Shape M: the older branch's condition depends on a COLD data load, so it
  //    resolves very late and the younger call is deep in the ROB by then.
  def loadDepBccBsr(k: Int): String = wrap(
    Prologue +
    "\tmove.l\t0x4030,%d1\n" +                       // cold D-side read (0xff fill both sides)
    "\tcmp.l\t%d1,%d1\n" +                           // Z=1, but only once the load lands
    "\tbeq.w\tLtgt\n" +
    fillers(k) +
    "Ltgt:\n\tbsr.w\tLsent\n" +
    "\taddq.l\t#1,%d6\n" +
    Epilogue + Callee)

  // ── Shape N: the re-executed call's callee immediately mispredicts again, so
  //    a fresh flush lands inside the just-recovered subroutine.
  def bsrTargetMispredict(k: Int): String = wrap(
    Prologue +
    "\tmoveq\t#1,%d0\n\tcmp.l\t%d0,%d0\n" +
    "\tbeq.w\tLtgt\n" +
    fillers(k) +
    "Ltgt:\n\tbsr.w\tLsent\n" +
    "\taddq.l\t#1,%d6\n" +
    Epilogue +
    "Lsent:\n" +
    "\tmoveq\t#1,%d0\n\tcmp.l\t%d0,%d0\n\tbeq.w\tLin\n" +
    "\taddq.l\t#1,%d4\n\taddq.l\t#1,%d4\n" +
    "Lin:\n\taddq.l\t#1,%d5\n\tmove.l\t%d5,0x4010\n\trts\n")

  // ── Shape O: VERY late resolution (a 3-deep DIVU dependency chain) plus a
  //    long wrong path, so the ROB genuinely FILLS with wrong-path work before
  //    the older branch resolves -- the deepest squash window constructible
  //    without interrupts.
  def veryLateBccBsr(k: Int): String = wrap(
    Prologue +
    "\tmove.l\t#0x00030000,%d1\n" +
    "\tmoveq\t#7,%d2\n" +
    "\tdivu.w\t%d2,%d1\n" +
    "\tdivu.w\t%d2,%d1\n" +
    "\tdivu.w\t%d2,%d1\n" +                          // quotient 0xe7bd, no overflow
    "\ttst.w\t%d1\n" +
    "\tbne.w\tLtgt\n" +
    fillers(k) +
    "Ltgt:\n\tbsr.w\tLsent\n" +
    "\taddq.l\t#1,%d6\n" +
    Epilogue + Callee)

  // ── Shape P: the wrong path is made of STORES, so the store queue is loaded
  //    with speculative entries that must be squashed by the same flush that
  //    squashes the call. A leaked wrong-path store is caught by re-reading the
  //    pre-seeded location after recovery.
  def storeWrongPathBsr(k: Int): String = wrap(
    Prologue +
    "\tmove.l\t#0xaaaaaaaa,%d2\n" +
    "\tmove.l\t%d2,0x4028\n" +                       // architectural seed
    "\tmove.l\t#0x0badbad0,%d3\n" +
    "\tmoveq\t#1,%d0\n\tcmp.l\t%d0,%d0\n" +
    "\tbeq.w\tLtgt\n" +
    (0 until k).map(_ => "\tmove.l\t%d3,0x4028").mkString("\n") + (if (k > 0) "\n" else "") +
    "Ltgt:\n\tbsr.w\tLsent\n" +
    "\tmove.l\t0x4028,%d6\n" +                       // must still read 0xaaaaaaaa
    Epilogue + Callee)

  /** The full directed matrix: (name, source). */
  def matrix: Vector[(String, String)] = {
    val b = Vector.newBuilder[(String, String)]
    for (k <- 0 to 16) b += ((f"bcc_bsr_k$k%02d", bccBsr(k)))
    for (k <- 0 to 8)  b += ((f"bcc_jsr_abs_k$k%02d", bccJsrAbs(k)))
    for (k <- 0 to 8)  b += ((f"bcc_jsr_ind_k$k%02d", bccJsrInd(k)))
    for (k <- 0 to 8)  b += ((f"bcc_two_calls_k$k%02d", bccTwoCalls(k)))
    for (k <- Seq(0, 1, 2, 3, 4, 6, 8, 12, 16, 24)) b += ((f"late_bcc_bsr_k$k%02d", lateBccBsr(k)))
    for (k <- 1 to 9)  b += ((f"rts_bsr_k$k%02d", rtsBsr(k)))
    for (k <- 0 to 8)  b += ((f"bsr_cold_bsr_k$k%02d", bsrColdBsr(k)))
    for (t <- 2 to 6)  b += ((f"loop_exit_bsr_t$t%02d", loopExitBsr(t)))
    for (k <- 0 to 8)  b += ((f"bcc_bsr_tree_k$k%02d", bccBsrTree(k)))
    for (k <- Seq(32, 40, 48, 56, 64, 72, 96)) b += ((f"deep_rob_bsr_k$k%02d", deepRobBsr(k)))
    for (k <- 0 to 8)  b += ((f"double_mispredict_k$k%02d", doubleMispredictBsr(k)))
    for (k <- Seq(0, 3, 7, 11)) b += ((f"far_callee_bsr_k$k%02d", farCalleeBsr(k)))
    for (k <- 0 to 8)  b += ((f"load_dep_bcc_bsr_k$k%02d", loadDepBccBsr(k)))
    for (k <- 0 to 8)  b += ((f"bsr_target_mispredict_k$k%02d", bsrTargetMispredict(k)))
    for (k <- Seq(8, 16, 32, 48, 64, 96)) b += ((f"very_late_bcc_bsr_k$k%02d", veryLateBccBsr(k)))
    for (k <- 0 to 8)  b += ((f"store_wrong_path_bsr_k$k%02d", storeWrongPathBsr(k)))
    b.result()
  }
}

/** DUT-side observation of the commit-time mispredict flush, so a lock-step
  * PASS cannot be vacuous (i.e. "no mispredict ever happened, nothing tested").
  */
object BsrFlushProbe {
  final case class Result(redirects: Int, flushes: Int, flushPcs: Vector[Long], callPc: Long)

  private var runIdx = 0

  def probe(src: String, cycles: Int = 6000, simSeed: Int = 1): Either[String, Result] = {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => return Left(s"assemble: ${err.reason}")
    }
    runIdx += 1
    var redirects = 0
    var flushes = 0
    val flushPcs = scala.collection.mutable.ArrayBuffer[Long]()
    scala.util.Random.setSeed(simSeed)
    FuzzRunner.compiled.doSim(s"bsrflushprobe_$runIdx", simSeed) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      var pendingCapture = false
      cd.onSamplings {
        if (pendingCapture) {
          flushPcs += (dut.rob.logic.flushPcReg.toLong & 0xffffffffL)
          pendingCapture = false
        }
        if (dut.rob.logic.branchRedirect.toBoolean) { redirects += 1; pendingCapture = true }
        if (dut.rob.logic.doFlushReg.toBoolean) flushes += 1
      }

      FuzzDut.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      for (a <- ProgGen.SandboxBase until (ProgGen.SandboxBase + ProgGen.SandboxSize))
        dmem.mem.write(a, 0xff.toByte)

      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.urp #= 0
      dut.ctrl.logic.srp #= 0
      dut.intCtrl.logic.iplIn #= 0
      dut.intCtrl.logic.iackAvec #= false
      dut.intCtrl.logic.iackVector #= 0
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false
      dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)

      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.usp #= 0L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr #= 15
      dut.wire.logic.seedData #= BigInt(0x00100000L)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= false
      cd.waitSampling(cycles)
    }
    Right(Result(redirects, flushes, flushPcs.toVector, 0L))
  }
}

class BsrFlushSkipSpec extends AnyFunSuite {

  private def runGroup(label: String, cases: Vector[(String, String)]): Unit = {
    val failures = scala.collection.mutable.ArrayBuffer[String]()
    for ((name, src) <- cases) {
      val t0 = System.nanoTime()
      val outcome = FuzzRunner.run(src, simSeed = 1)
      val dt = (System.nanoTime() - t0) / 1e9
      outcome match {
        case FuzzRunner.Pass =>
          println(f"[bsrflush] $name%-24s PASS ($dt%.1fs)")
        case FuzzRunner.GenFail(r) =>
          println(f"[bsrflush] $name%-24s GENFAIL ($dt%.1fs): $r")
          failures += s"$name GENFAIL: $r"
        case FuzzRunner.Diverged(k, d, c) =>
          println(f"[bsrflush] $name%-24s DIVERGED[$k] ($dt%.1fs): $d\n$c\n--- program ---\n$src")
          failures += s"$name DIVERGED[$k]: $d"
      }
    }
    assert(failures.isEmpty,
      s"$label: ${failures.size}/${cases.size} directed flush/re-execute cases failed:\n  " +
        failures.mkString("\n  "))
  }

  private val all = BsrFlushPrograms.matrix
  private def group(prefix: String) = all.filter(_._1.startsWith(prefix))

  test("flushed bsr at the redirect PC is re-executed (cold Bcc mispredict)", VerilatorTest) {
    runGroup("bcc_bsr", group("bcc_bsr_k"))
  }

  test("flushed jsr (absolute + indirect) at the redirect PC is re-executed", VerilatorTest) {
    runGroup("bcc_jsr", group("bcc_jsr_"))
  }

  test("two flushed back-to-back calls are both re-executed", VerilatorTest) {
    runGroup("bcc_two_calls", group("bcc_two_calls"))
  }

  test("flushed bsr re-executes with a deep late-resolving speculative window", VerilatorTest) {
    runGroup("late_bcc_bsr", group("late_bcc_bsr"))
  }

  test("flushed bsr behind an older mispredicting RTS is re-executed", VerilatorTest) {
    runGroup("rts_bsr", group("rts_bsr"))
  }

  test("flushed bsr behind an older mispredicting cold BSR is re-executed", VerilatorTest) {
    runGroup("bsr_cold_bsr", group("bsr_cold_bsr"))
  }

  test("flushed bsr at a trained loop-exit fall-through is re-executed", VerilatorTest) {
    runGroup("loop_exit_bsr", group("loop_exit_bsr"))
  }

  test("a flushed 3-deep call TREE is fully re-executed", VerilatorTest) {
    runGroup("bcc_bsr_tree", group("bcc_bsr_tree"))
  }

  test("a call still in the frontend skid (wrong path longer than the ROB) is re-executed", VerilatorTest) {
    runGroup("deep_rob_bsr", group("deep_rob_bsr"))
  }

  test("a call behind TWO stacked mispredicts is re-executed", VerilatorTest) {
    runGroup("double_mispredict", group("double_mispredict"))
  }

  test("a flushed call with a cold/far callee is re-executed", VerilatorTest) {
    runGroup("far_callee_bsr", group("far_callee_bsr"))
  }

  test("a flushed call behind a cold-load-dependent branch is re-executed", VerilatorTest) {
    runGroup("load_dep_bcc_bsr", group("load_dep_bcc_bsr"))
  }

  test("a re-executed call whose callee mispredicts again still runs once", VerilatorTest) {
    runGroup("bsr_target_mispredict", group("bsr_target_mispredict"))
  }

  test("a call flushed from a ROB-FILLING very-late-resolve window is re-executed", VerilatorTest) {
    runGroup("very_late_bcc_bsr", group("very_late_bcc_bsr"))
  }

  test("a call flushed alongside squashed speculative STORES is re-executed", VerilatorTest) {
    runGroup("store_wrong_path_bsr", group("store_wrong_path_bsr"))
  }

  test("flush/re-execute is stable across sim seeds (frontend timing jitter)", VerilatorTest) {
    // SparseMemory's page fill is seed-derived, so the bytes the wrong path
    // fetches beyond the program -- and hence frontend/decode timing and the
    // exact ROB occupancy at flush -- change with the sim seed. Part 62's
    // real-hardware finding is that the collision culprit varies boot to boot;
    // this sweeps the sim-side analogue.
    val shapes = Vector(
      ("bcc_bsr_k07", BsrFlushPrograms.bccBsr(7)),
      ("rts_bsr_k04", BsrFlushPrograms.rtsBsr(4)),
      ("bcc_bsr_tree_k03", BsrFlushPrograms.bccBsrTree(3)),
      ("double_mispredict_k05", BsrFlushPrograms.doubleMispredictBsr(5)),
      ("deep_rob_bsr_k64", BsrFlushPrograms.deepRobBsr(64)),
      ("very_late_bcc_bsr_k64", BsrFlushPrograms.veryLateBccBsr(64)),
      ("store_wrong_path_bsr_k06", BsrFlushPrograms.storeWrongPathBsr(6)))
    val failures = scala.collection.mutable.ArrayBuffer[String]()
    for ((name, src) <- shapes; seed <- 1 to 8) {
      FuzzRunner.run(src, simSeed = seed) match {
        case FuzzRunner.Pass => println(s"[bsrflush] $name seed=$seed PASS")
        case FuzzRunner.GenFail(r) => failures += s"$name seed=$seed GENFAIL: $r"
        case FuzzRunner.Diverged(k, d, c) =>
          println(s"[bsrflush] $name seed=$seed DIVERGED[$k]: $d\n$c\n--- program ---\n$src")
          failures += s"$name seed=$seed DIVERGED[$k]: $d"
      }
    }
    assert(failures.isEmpty, "seed sweep failures:\n  " + failures.mkString("\n  "))
  }

  test("bsr flush probe: a real commit-time mispredict flush actually occurs", VerilatorTest) {
    // Non-vacuity guard. Every shape must produce at least one commit-time
    // branchRedirect (RobPlugin.branchRedirect = retire0 && retireAlone &&
    // mispredictStore(h0)); otherwise the lock-step passes above prove nothing
    // about flush recovery.
    val probes = Vector(
      "bcc_bsr_k00", "bcc_bsr_k04", "bcc_bsr_k07", "bcc_bsr_k12",
      "bcc_jsr_abs_k04", "bcc_jsr_ind_k04", "bcc_two_calls_k04",
      "late_bcc_bsr_k08", "late_bcc_bsr_k24",
      "rts_bsr_k04", "bsr_cold_bsr_k04", "loop_exit_bsr_t04", "bcc_bsr_tree_k04",
      "deep_rob_bsr_k64", "double_mispredict_k05", "far_callee_bsr_k07",
      "load_dep_bcc_bsr_k04", "bsr_target_mispredict_k04",
      "very_late_bcc_bsr_k64", "store_wrong_path_bsr_k06")
    val bad = scala.collection.mutable.ArrayBuffer[String]()
    for (name <- probes) {
      val src = all.toMap.apply(name)
      BsrFlushProbe.probe(src) match {
        case Left(err) => bad += s"$name probe error: $err"
        case Right(r) =>
          val pcs = r.flushPcs.map(p => f"0x$p%08x").mkString(",")
          println(f"[bsrflush-probe] $name%-20s branchRedirect=${r.redirects}%2d doFlushReg=${r.flushes}%2d flushPc=[$pcs]")
          if (r.redirects == 0) bad += s"$name: NO commit-time branchRedirect -- lock-step case is vacuous"
      }
    }
    assert(bad.isEmpty, "non-vacuity guard failed:\n  " + bad.mkString("\n  "))
  }
}
