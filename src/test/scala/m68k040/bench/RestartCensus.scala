package m68k040.bench

import spinal.core._
import spinal.core.sim._

import scala.collection.mutable.ArrayBuffer

/** RESTART CENSUS (throughput spec P8, section 3.4) -- `IPC_RESTART_CENSUS=1`.
  *
  * SIM-ONLY, TEST-ONLY. Nothing here reaches `src/main`: the only netlist effect is a set of
  * `SimPublic` name preservations (emitted ONLY when the env var is set at elaboration), and
  * every read is pure observation of the DUT, so cycles are bit-identical with it on or off.
  *
  * WHAT IT MEASURES. Every FRONTEND RESTART (a redirect of the fetch PC) opens an EVENT whose
  * TARGET is the redirect PC. The first correct-path instruction -- the one whose PC is the
  * target -- is then followed stage by stage, by PC through the in-order front end and by robId
  * once it has one, until it RETIRES:
  *
  *   t0      the redirect cycle (`FetchAlignPlugin.mispredictRedirect.valid`, `predictFire`,
  *           `ftqMismatch` or `icMaintFlushArm`)
  *   CMD     `ic.cmd.fire` for the target's 8-byte window (FetchAlign -> IcachePlugin)
  *   RSP     first non-stale `ic.rsp.valid` after CMD (IcachePlugin -> IBuf push)
  *   FEED    `fa.feed.fire` with slot-0 pc == target (Aligner -> DecodeStage)
  *   RAW     `dec.raw` fire     (DecodeStage skid 1, FMax Lever C)
  *   FED     `dec.fed` fire     (DecodeStage skid 2 -> MicroOpAssembler)
  *   PUSH    `dec.pushReg` fire (assembled uops -> MicroOpQueue write)
  *   POP     `dec.uopsOut` fire (MicroOpQueue pop -> RenameStage)
  *   DISP    IQ push fire with that pc (RenameStage.uopsStaged -> Dispatch -> ROB alloc + IQ)
  *   ISSUE   any `iq.issue(i)` fire for that robId
  *   CMPL    any ROB completion port for that robId
  *   RET     `retire0 && h0 == robId` or `retire1 && h1 == robId`
  *
  * For a Tier-1 branch redirect (`earlyFire`) the ROB still holds the mispredicting branch and
  * the older path; rename is FROZEN (`earlyPend`) until the branch retires and Tier 2's
  * `doFlushReg` fires. `FLUSH` is that cycle. The back-end stages (DISP..RET) are only accepted
  * AFTER it, so a wrong-path uop that happens to carry the same pc is never mistaken for the
  * correct-path one.
  *
  * The SPLIT it exists for (spec section 1.2): every window cycle is classified
  * robEmpty / retireStall / retire exactly like `StallBudget` (unlagged here), and charged to
  *   SHADOW   inside [t0, FLUSH]       -- ROB still draining the older path, fetch refilling
  *   RESTART  inside (FLUSH, RET]      -- the ROB is waiting on the correct path: RESTART COST
  *   STEADY   everything else
  * plus a "cycles since the last flush-class redirect" histogram of the same three categories.
  */
object RestartCensus {
  val enabled: Boolean = sys.env.get("IPC_RESTART_CENSUS").contains("1")
  /** Per-event cycle-by-cycle trace lines for the first N events of each kernel. */
  val traceEvents: Int = sys.env.get("IPC_RESTART_TRACE").map(_.toInt).getOrElse(0)

  val Stages: Seq[String] =
    Seq("CMD", "RSP", "FEED", "RAW", "FED", "PUSH", "POP", "DISP", "ISSUE", "CMPL", "RET")

  /** Aggregate across every kernel run in this JVM, printed by whoever calls `printAggregate`. */
  final case class Agg(var events: Int = 0)
}

trait RestartCensusHarness { self: CoreBenchHarness =>

  /** Called from `BackendWiringPlugin`'s build area: keep the probed nets through Verilator.
    * A no-op unless `IPC_RESTART_CENSUS=1` at elaboration time. */
  def restartCensusSimPublic(host: spinal.lib.misc.plugin.PluginHost): Unit = if (RestartCensus.enabled) {
    val fa  = host[m68k040.frontend.FetchAlignPlugin].logic
    val dec = host[m68k040.decode.DecodeStage].logic
    val ren = host[m68k040.rename.RenameStage].logic
    val iq  = host[m68k040.execute.iq.IssueQueueService]
    val rob = host[m68k040.rob.RobPlugin].logic
    def pub(d: Data*): Unit = d.foreach(x => x.flatten.foreach(s => SimPublic(s)))
    pub(fa.ic.cmd.valid, fa.ic.cmd.ready, fa.ic.cmd.payload.pc, fa.ic.rsp.valid, fa.rspStaleHead,
      fa.feed.valid, fa.feed.ready, fa.feed.payload(0).pc, fa.feed.payload(0).valid,
      fa.decodePc, fa.fetchPc, fa.ibuf.io.cnt,
      dec.raw.valid, dec.raw.ready, dec.raw.payload.packets(0).pc,
      dec.fed.valid, dec.fed.ready, dec.fed.payload.packets(0).pc,
      dec.pushReg.valid, dec.pushReg.ready, dec.pushReg.payload.uops(0).pc, dec.pushReg.payload.count,
      dec.uopsOut.valid, dec.uopsOut.ready, dec.uopsOut.payload(0).pc, dec.uopsOut.payload(1).pc,
      dec.uop1Sig, dec.pushReg.payload.uops(1).pc, dec.pushReg.payload.uops(2).pc,
      dec.pushReg.payload.uops(3).pc,
      ren.uopsStaged.valid, ren.uopsStaged.ready,
      iq.push.valid, iq.push.ready, iq.push.payload(0).robId, iq.push.payload(0).uop.pc,
      iq.push.payload(1).robId, iq.push.payload(1).uop.pc, iq.pushSlot1Valid,
      rob.exc.redirectValid, rob.branchRedirect, rob.excActive, rob.earlyPend,
      rob.flushPcReg, rob.retire0, rob.retire1, rob.h1)
    for (i <- 0 until 5) pub(iq.issue(i).valid, iq.issue(i).ready, iq.issue(i).payload.robId)
    for (i <- 0 until 6) pub(rob.completion(i).valid, rob.completion(i).payload)
    for (i <- 0 until 4) pub(rob.ccrCompletion(i).valid, rob.ccrCompletion(i).payload.robId)
  }

  /** One restart event. Stage stamps are absolute cycles (-1 = not observed). */
  final class RcEvent(val src: String, val t0: Long, val target: Long, val resolve: Long) {
    val st = Array.fill(RestartCensus.Stages.size)(-1L)
    var flush: Long = -1L          // Tier-2 doFlushReg for a Tier-1 event (== t0 otherwise)
    var robId: Int = -1
    var dispSlot: Int = -1
    var closed = false
    var superseded = false
    var traced = false
    val trace = ArrayBuffer.empty[String]
    def stamp(i: Int, c: Long): Unit = if (st(i) < 0) st(i) = c
    def has(i: Int): Boolean = st(i) >= 0
    def ret: Long = st(RestartCensus.Stages.indexOf("RET"))
  }

  final class RestartCensus(kernel: String) {
    import RestartCensus.Stages
    private def S(n: String) = Stages.indexOf(n)
    val events = ArrayBuffer.empty[RcEvent]
    private var cur: Option[RcEvent] = None           // latest FLUSH-CLASS event (owns the phase)
    private val open = ArrayBuffer.empty[RcEvent]      // every event still being followed
    private def flushClass(src: String) =
      src != "fe-predict" && src != "ftq-mismatch" && src != "complex-resume"
    // per-cycle, index-aligned with `histo` in runKernel (one append per sampling)
    val cls = ArrayBuffer.empty[Byte]      // 0 robEmpty, 1 retireStall, 2 retire
    val sinceFe = ArrayBuffer.empty[Int]   // cycles since the last flush-class frontend redirect
    val phase = ArrayBuffer.empty[Byte]    // 0 steady, 1 shadow, 2 restart
    val srcOf = ArrayBuffer.empty[String]  // source of the event owning a shadow/restart cycle
    private var lastFe = -100000L
    private var prevBranchRedirect = false
    private var prevExcRedirect = false
    private var lastMispredResolve = -1L
    private var prevExcActive = false
    private var lastExcRise = -1L

    def onCycle(dut: CoreBenchHarness#FullCoreDut, cyc: Long): Unit = {
      val fa = dut.fa.logic; val dec = dut.dec.logic; val rob = dut.rob.logic
      val ren = dut.ren.logic
      val iq = dut.iq
      def u(x: UInt): Long = x.toLong & 0xffffffffL

      if (rob.branchCompletion.valid.toBoolean && rob.branchCompletion.payload.mispredict.toBoolean)
        lastMispredResolve = cyc
      val excAct = rob.excActive.toBoolean
      if (excAct && !prevExcActive) lastExcRise = cyc
      prevExcActive = excAct

      // ── event openers ─────────────────────────────────────────────────────────
      val doFlush = rob.doFlushReg.toBoolean
      val suppress = rob.earlySuppressFe.toBoolean
      val early = rob.earlyFire.toBoolean
      val faRed = fa.mispredictRedirect.valid.toBoolean
      var opened: Option[RcEvent] = None
      if (faRed) {
        val src =
          if (doFlush && !suppress) {
            if (prevBranchRedirect) "T2-branch" else if (prevExcRedirect) "exc/RTE/sysop" else "T2-other"
          } else if (early) "T1-branch"
          else "complex-resume"
        opened = Some(new RcEvent(src, cyc, u(fa.mispredictRedirect.payload),
          if (early) lastMispredResolve else if (src == "exc/RTE/sysop") lastExcRise else cyc))
        if (src != "complex-resume") lastFe = cyc
        if (doFlush && !suppress) opened.get.flush = cyc
        if (src == "complex-resume") opened.get.flush = cyc
      } else if (fa.icMaintFlushArm.toBoolean) {
        opened = Some(new RcEvent("icMaintFlush", cyc, u(fa.decodePc), cyc)); opened.get.flush = cyc
      } else if (fa.ftqMismatch.toBoolean) {
        opened = Some(new RcEvent("ftq-mismatch", cyc, u(fa.ftqMismatchPc), cyc)); opened.get.flush = cyc
      } else if (fa.predictFire.toBoolean) {
        opened = Some(new RcEvent("fe-predict", cyc, u(fa.predictTargetReg), cyc)); opened.get.flush = cyc
      }
      // Tier 2 flush of the SAME branch Tier 1 redirected for: not a new frontend event.
      if (doFlush && suppress) cur.foreach(e => if (e.flush < 0) e.flush = cyc)
      // A Tier-2 doFlush that is NOT suppressed while a T1 event waits = an older event won.
      // A flush-class redirect kills every older in-flight event (their targets are
      // squashed). A frontend-only redirect (predictFire/ftqMismatch/resume) squashes only
      // the FRONT END, so an older event whose target has already reached the back end
      // (DISP) survives it; one still in the front end is superseded.
      opened.foreach { e =>
        open.foreach { c =>
          if (!c.closed && (flushClass(e.src) || !c.has(S("FEED")))) { c.superseded = true; c.closed = true }
        }
        open.filterInPlace(!_.closed)
        e.traced = events.count(_.src == e.src) < RestartCensus.traceEvents
        events += e; open += e
        if (flushClass(e.src)) cur = Some(e)
      }

      // ── stage tracking for the live event ─────────────────────────────────────
      open.filter(!_.closed).foreach { e =>
        val T = e.target
        val afterStart = cyc > e.t0 || (e.src == "fe-predict" && cyc == e.t0)
        if (afterStart) {
          if (!e.has(S("CMD")) && fa.ic.cmd.valid.toBoolean && fa.ic.cmd.ready.toBoolean &&
              u(fa.ic.cmd.payload.pc) == (T & ~7L)) e.stamp(S("CMD"), cyc)
          else if (e.has(S("CMD")) && !e.has(S("RSP")) && cyc > e.st(S("CMD")) &&
              fa.ic.rsp.valid.toBoolean && !fa.rspStaleHead.toBoolean) e.stamp(S("RSP"), cyc)
          if (!e.has(S("FEED")) && fa.feed.valid.toBoolean && fa.feed.ready.toBoolean &&
              u(fa.feed.payload(0).pc) == T) e.stamp(S("FEED"), cyc)
          if (e.has(S("FEED")) && !e.has(S("RAW")) && dec.raw.valid.toBoolean && dec.raw.ready.toBoolean &&
              u(dec.raw.payload.packets(0).pc) == T) e.stamp(S("RAW"), cyc)
          if (e.has(S("RAW")) && !e.has(S("FED")) && dec.fed.valid.toBoolean && dec.fed.ready.toBoolean &&
              u(dec.fed.payload.packets(0).pc) == T) e.stamp(S("FED"), cyc)
          if (e.has(S("FED")) && !e.has(S("PUSH")) && dec.pushReg.valid.toBoolean && dec.pushReg.ready.toBoolean &&
              (0 until dec.pushReg.payload.count.toInt.min(4)).exists(k => u(dec.pushReg.payload.uops(k).pc) == T))
            e.stamp(S("PUSH"), cyc)
          if (e.has(S("PUSH")) && !e.has(S("POP")) && dec.uopsOut.valid.toBoolean && dec.uopsOut.ready.toBoolean &&
              (u(dec.uopsOut.payload(0).pc) == T || (dec.uop1Sig.toBoolean && u(dec.uopsOut.payload(1).pc) == T)))
            e.stamp(S("POP"), cyc)
        }
        // Back-end stages only after FLUSH and after the target has left the MicroOpQueue:
        // an older loop iteration's copy of the same pc can otherwise be taken for it.
        val backendOpen = e.flush >= 0 && cyc > e.flush && e.has(S("POP")) && cyc > e.st(S("POP"))
        if (backendOpen && !e.has(S("DISP")) && iq.push.valid.toBoolean && iq.push.ready.toBoolean) {
          if (u(iq.push.payload(0).uop.pc) == T) {
            e.stamp(S("DISP"), cyc); e.robId = iq.push.payload(0).robId.toInt; e.dispSlot = 0
          } else if (iq.pushSlot1Valid.toBoolean && u(iq.push.payload(1).uop.pc) == T) {
            e.stamp(S("DISP"), cyc); e.robId = iq.push.payload(1).robId.toInt; e.dispSlot = 1
          }
        }
        if (e.robId >= 0) {
          if (!e.has(S("ISSUE")) && cyc > e.st(S("DISP")) &&
              (0 until 5).exists(i => iq.issue(i).valid.toBoolean && iq.issue(i).ready.toBoolean &&
                iq.issue(i).payload.robId.toInt == e.robId)) e.stamp(S("ISSUE"), cyc)
          if (!e.has(S("CMPL")) && cyc >= e.st(S("DISP")) && (
              (0 until 6).exists(i => rob.completion(i).valid.toBoolean && rob.completion(i).payload.toInt == e.robId) ||
              (rob.branchCompletion.valid.toBoolean && rob.branchCompletion.payload.robId.toInt == e.robId)))
            e.stamp(S("CMPL"), cyc)
          val r0 = rob.retireLanes(0).toBoolean && rob.h0.toInt == e.robId
          val r1 = rob.retireLanes(1).toBoolean && rob.h1.toInt == e.robId
          if (cyc > e.st(S("DISP")) && (r0 || r1)) { e.stamp(S("RET"), cyc); e.closed = true }
        }
        if (e.trace.size < 60 && e.traced) {
          def b(x: Boolean) = if (x) "#" else "."
          e.trace += f"  c+${cyc - e.t0}%3d fl=${b(doFlush)} ePend=${b(rob.earlyPend.toBoolean)} " +
            f"cmd=${b(fa.ic.cmd.valid.toBoolean && fa.ic.cmd.ready.toBoolean)}@${u(fa.ic.cmd.payload.pc)}%08x " +
            f"rsp=${b(fa.ic.rsp.valid.toBoolean)}${if (fa.rspStaleHead.toBoolean) "s" else " "} " +
            f"ibuf=${fa.ibuf.io.cnt.toInt}%2d dPc=${u(fa.decodePc)}%08x " +
            f"feed=${b(fa.feed.valid.toBoolean)}${b(fa.feed.ready.toBoolean)}@${u(fa.feed.payload(0).pc)}%08x " +
            f"raw=${b(dec.raw.valid.toBoolean)}${b(dec.raw.ready.toBoolean)} fed=${b(dec.fed.valid.toBoolean)}${b(dec.fed.ready.toBoolean)} " +
            f"push=${b(dec.pushReg.valid.toBoolean)} pop=${b(dec.uopsOut.valid.toBoolean)}${b(dec.uopsOut.ready.toBoolean)}@${u(dec.uopsOut.payload(0).pc)}%08x " +
            f"ren=${b(ren.uopsStaged.valid.toBoolean)}${b(ren.uopsStaged.ready.toBoolean)} " +
            f"iqPush=${b(iq.push.valid.toBoolean && iq.push.ready.toBoolean)} " +
            f"iss=${(0 until 5).map(i => b(iq.issue(i).valid.toBoolean && iq.issue(i).ready.toBoolean)).mkString} " +
            f"rob=${rob.count.toInt}%2d ret=${b(rob.retireLanes(0).toBoolean)}${b(rob.retireLanes(1).toBoolean)} " +
            f"stages=${Stages.zipWithIndex.filter(z => e.st(z._2) == cyc).map(_._1).mkString(",")}"
        }
      }

      // ── per-cycle classification (unlagged) ───────────────────────────────────
      val c: Byte = if (rob.count.toInt == 0) 0 else if (rob.retireLanes(0).toBoolean) 2 else 1
      cls += c
      sinceFe += scala.math.min(cyc - lastFe, 100000L).toInt
      val ph: (Byte, String) = cur match {
        case Some(e) if !e.superseded && (!e.closed || e.ret == cyc) =>
          if (e.flush < 0 || cyc <= e.flush) (1: Byte, e.src) else (2: Byte, e.src)
        case _ => (0: Byte, "")
      }
      val ph2 = if (ph._1 != 0) ph else
        open.find(e => !flushClass(e.src) && !e.superseded && (!e.has(S("DISP")) || e.st(S("DISP")) == cyc)) match {
          case Some(e) => (3: Byte, e.src)
          case None => ph
        }
      phase += ph2._1; srcOf += ph2._2

      open.filterInPlace(!_.closed)
      prevBranchRedirect = rob.branchRedirect.toBoolean
      prevExcRedirect = rob.exc.redirectValid.toBoolean
    }

    private def med(xs: scala.collection.Seq[Long]): Double =
      if (xs.isEmpty) Double.NaN else { val s = xs.sorted; s(s.size / 2).toDouble }
    private def mean(xs: scala.collection.Seq[Long]): Double = if (xs.isEmpty) Double.NaN else xs.sum.toDouble / xs.size

    /** Print the per-kernel census. `lo..hi` is the IPC window (histo indices). */
    def report(lo: Int, hi: Int, windowRetired: Int): Unit = {
      val inWin = events.filter(e => e.t0 >= lo && e.t0 <= hi)
      println(f"\n[restart:$kernel] events in window=${inWin.size} (all=${events.size}) " +
        f"window=${hi - lo + 1} cycles, $windowRetired macros")
      val bySrc = inWin.groupBy(_.src).toSeq.sortBy(-_._2.size)
      for ((src, es) <- bySrc) {
        val done = es.filter(e => !e.superseded && e.ret >= 0)
        println(f"[restart:$kernel] src=$src%-15s n=${es.size}%4d completed=${done.size}%4d " +
          f"superseded=${es.count(_.superseded)}")
        if (done.nonEmpty) {
          // stage stamps relative to t0 (median / mean), and per-stage DELTAS
          val rel = Stages.indices.map(i => done.filter(_.st(i) >= 0).map(e => e.st(i) - e.t0))
          println(f"[restart:$kernel]   stage   " + Stages.map(s => f"$s%6s").mkString)
          println(f"[restart:$kernel]   med t0+ " + rel.map(r => if (r.isEmpty) "     -" else f"${med(r)}%6.0f").mkString)
          println(f"[restart:$kernel]   mean t0+" + rel.map(r => if (r.isEmpty) "     -" else f"${mean(r)}%6.1f").mkString)
          println(f"[restart:$kernel]   seen    " + rel.map(r => f"${r.size}%6d").mkString)
          val res2t0 = done.filter(_.resolve >= 0).map(e => e.t0 - e.resolve)
          val t0ToFlush = done.filter(_.flush >= 0).map(e => e.flush - e.t0)
          val flushToRet = done.filter(_.flush >= 0).map(e => e.ret - e.flush)
          val t0ToRet = done.map(e => e.ret - e.t0)
          def h(xs: scala.collection.Seq[Long]) = xs.groupBy(identity).toSeq.sortBy(_._1).map { case (k, v) => s"$k:${v.size}" }.mkString("{", " ", "}")
          println(f"[restart:$kernel]   resolve(T1: branch EU; exc: excActive rise)->t0 mean=${mean(res2t0)}%.2f  t0->FLUSH mean=${mean(t0ToFlush)}%.2f " +
            f"med=${med(t0ToFlush)}%.0f  FLUSH->RET mean=${mean(flushToRet)}%.2f med=${med(flushToRet)}%.0f  " +
            f"t0->RET mean=${mean(t0ToRet)}%.2f")
          println(f"[restart:$kernel]   FLUSH->RET hist ${h(flushToRet)}")
          println(f"[restart:$kernel]   t0->FLUSH  hist ${h(t0ToFlush)}")
          // Where is the target at FLUSH? (the last stage it has reached at or before FLUSH)
          val atFlush = done.filter(_.flush >= 0).map { e =>
            val reached = Stages.indices.filter(i => e.st(i) >= 0 && e.st(i) <= e.flush)
            if (reached.isEmpty) "none" else Stages(reached.max)
          }
          println(f"[restart:$kernel]   stage reached by FLUSH: " +
            atFlush.groupBy(identity).toSeq.sortBy(-_._2.size).map { case (k, v) => s"$k:${v.size}" }.mkString(" "))
          // After FLUSH: per-stage residency on the critical path, from max(FLUSH, prev stage)
          val post = Seq("POP", "DISP", "ISSUE", "CMPL", "RET").map(S)
          val postDeltas = post.map { i =>
            val xs = done.filter(e => e.flush >= 0 && e.st(i) >= 0).map { e =>
              val prevStages = (0 until i).filter(j => e.st(j) >= 0)
              val prev = if (prevStages.isEmpty) e.flush else scala.math.max(e.flush, e.st(prevStages.max))
              e.st(i) - prev
            }
            f"${Stages(i)}=${mean(xs)}%.2f"
          }
          println(f"[restart:$kernel]   post-FLUSH critical path (mean, from max(FLUSH,prev stage)): ${postDeltas.mkString(" ")}")
        }
        for (e <- es.take(RestartCensus.traceEvents) if e.trace.nonEmpty) {
          println(f"[restart-trace:$kernel] src=${e.src} t0=${e.t0} target=${e.target}%08x resolve=${e.resolve} " +
            f"flush=${e.flush} robId=${e.robId} stamps=" +
            Stages.zipWithIndex.map { case (s, i) => s"$s:${if (e.st(i) < 0) "-" else (e.st(i) - e.t0).toString}" }.mkString(" "))
          e.trace.foreach(l => println(s"[restart-trace:$kernel] $l"))
        }
      }

      // ── the split ─────────────────────────────────────────────────────────────
      val n = hi - lo + 1
      val w = (lo to hi).filter(_ < cls.size)
      def cnt(pred: Int => Boolean, c: Int) = w.count(i => pred(i) && cls(i) == c)
      val names = Seq("robEmpty", "retireStall", "retire")
      println(f"[restart:$kernel] SPLIT (unlagged, window $n cyc)   " + names.map(s => f"$s%12s").mkString)
      for ((pn, p) <- Seq("steady" -> 0, "shadow" -> 1, "restart" -> 2, "feBubble" -> 3)) {
        val row = (0 to 2).map(c => cnt(i => phase(i) == p, c))
        println(f"[restart:$kernel]   $pn%-8s ${row.sum}%7d cyc (${100.0 * row.sum / n}%5.1f%%)  " +
          row.map(x => f"$x%12d").mkString)
      }
      val totals = (0 to 2).map(c => cnt(_ => true, c))
      println(f"[restart:$kernel]   total    ${n}%7d cyc           " + totals.map(x => f"$x%12d").mkString)
      val rs = totals(1)
      if (rs > 0) {
        val rsRestart = cnt(i => phase(i) == 2, 1); val rsShadow = cnt(i => phase(i) == 1, 1)
        val rsFe = cnt(i => phase(i) == 3, 1)
        println(f"[restart:$kernel]   retireStall: restart ${100.0 * rsRestart / rs}%.1f%%  shadow ${100.0 * rsShadow / rs}%.1f%%  " +
          f"feBubble ${100.0 * rsFe / rs}%.1f%%  steady ${100.0 * (rs - rsRestart - rsShadow - rsFe) / rs}%.1f%%")
      }
      // since-flush histogram (flush-class redirects only: T1/T2/exc)
      val edges = Seq(0, 5, 10, 15, 20, 25, 30, 40, 50, 75, 100, Int.MaxValue)
      println(f"[restart:$kernel] SINCE-FLUSH histogram (cycles since last flush-class redirect)")
      println(f"[restart:$kernel]   bucket      " + names.map(s => f"$s%12s").mkString + f"${"cum rs%"}%9s")
      var cumRs = 0
      for (b <- edges.indices.dropRight(1)) {
        val (a, z) = (edges(b), edges(b + 1))
        val row = (0 to 2).map(c => w.count(i => sinceFe(i) >= a && sinceFe(i) < z && cls(i) == c))
        cumRs += row(1)
        val lab = if (z == Int.MaxValue) s"$a+" else s"$a-${z - 1}"
        println(f"[restart:$kernel]   $lab%-10s  " + row.map(x => f"$x%12d").mkString +
          f"${if (rs > 0) 100.0 * cumRs / rs else 0.0}%8.1f%%")
      }
    }
  }
}
