package m68k040.fuzz

import spinal.core.sim._
import scala.collection.mutable

/** LIVENESS MONITOR for the LS pipeline -- turns a hang into a DIAGNOSIS instead of a timeout.
  *
  * Three independent bounds, any one of which trips it. They are deliberately GLOBAL
  * properties rather than a per-resource checklist (probe credit / ring slot / SQ port /
  * DTLB claim ...): a checklist can be under-enumerated, which is how every earlier LS-OoO
  * deadlock slipped through (see `LsOooInhibitedOrderSpec`'s `OldestLsProgress` note).
  *
  *   (R) RETIRE PROGRESS -- the ROB holds work (`count > 0`) and nothing has committed on any
  *       commit port (normal pair or exception) for `retireBound` cycles.
  *   (S) STAGE AGE -- the SAME robId has occupied the SAME LS stage (S1, T, TX, P3, P4) or
  *       the SAME park slot for `stageBound` consecutive cycles.
  *   (O) OLDEST-LS PROGRESS -- the program-oldest issued-but-uncompleted LS op (ROB-head
  *       anchored age) has stayed the oldest and uncompleted for `oldestBound` cycles.
  *
  * On a trip `failure` is set ONCE with a one-shot state dump (ROB head/count, the last
  * committed PC, every LS stage, the park, the aligned ring, SQ barrier verdicts and the
  * IQ's resident LS slots). A deadlock's state is static, so one dump is the whole picture.
  *
  * The bounds are generous -- the corpus's slowest legitimate waits (a DRAM-latency L2 model
  * behind a table walk, a divide chain) are two orders of magnitude shorter -- and all three
  * reset on a flush, which legitimately removes in-flight ops without completing them.
  *
  * PASSIVE: reads simPublic signals only; never drives the DUT. */
final class LsLivenessMonitor(dut: FuzzCoreDut, name: String,
                              retireBound: Int = 4000, stageBound: Int = 4000,
                              oldestBound: Int = 4000) {
  private val RobW   = m68k040.Global.ROB_ID_W_DEFAULT
  private val RobMod = BigInt(1) << RobW
  private val ls     = dut.lsEu.logic
  private val hasPark = ls.parkValid != null

  var failure: Option[String] = None
  /** NON-VACUITY: cycles on which P4 held the SAME op as last cycle (it did not leave)
    * while a program-OLDER LS op sat upstream of it in S1/T/TX/P3 -- the precondition of
    * every LS-OoO pipe deadlock. A reproducer whose run shows 0 here never built the shape
    * it claims to test. */
  var olderBehindStuckP4 = 0L
  /** LS-OoO liveness replays requested (LsEuPlugin `replayRequest`) and actually taken at
    * the ROB head (`lsReplayRedirect`), when the build has them. */
  var replays = 0L
  var replayRedirects = 0L
  var cycle = 0L
  var commits = 0L
  var lastCommitPc = -1L
  private var sinceCommit = 0L

  private val stageOcc = mutable.Map.empty[String, (BigInt, Long)]  // stage -> (robId, since)
  private val inFlight = mutable.Map.empty[BigInt, Long]            // robId -> issue cycle
  private var oldestRob: BigInt = -1
  private var oldestFor = 0L

  private def age(r: BigInt, head: BigInt) = ((r - head) % RobMod + RobMod) % RobMod

  private def trip(kind: String, msg: String): Unit = if (failure.isEmpty) {
    failure = Some(s"[liveness/$name] $kind at cycle $cycle: $msg\n${dump()}")
  }

  def summary: String =
    s"[liveness/$name] cycles=$cycle commits=$commits olderBehindStuckP4=$olderBehindStuckP4 " +
      s"replays=$replays replayRedirects=$replayRedirects" +
      failure.map(_ => " TRIPPED").getOrElse("")

  def attach(): this.type = { dut.clockDomain.onSamplings(sample()); this }

  def sample(): Unit = {
    cycle += 1
    val flush = dut.rob.logic.doFlushReg.toBoolean
    // ── (R) retire progress ──────────────────────────────────────────────────────────
    var committed = false
    for (k <- 0 until 3) {
      val c = dut.rob.logic.commitObs(k)
      if (c.fire.toBoolean) { committed = true; commits += 1; lastCommitPc = c.pc.toLong & 0xffffffffL }
    }
    val robCount = dut.rob.logic.count.toBigInt
    if (committed || robCount == 0 || flush) sinceCommit = 0 else sinceCommit += 1
    if (sinceCommit > retireBound)
      trip("RETIRE-STALL", s"ROB count=$robCount and no commit for $sinceCommit cycles")

    // ── (S) stage age ────────────────────────────────────────────────────────────────
    val occ = mutable.ArrayBuffer.empty[(String, Boolean, BigInt)]
    occ += (("S1", ls.s1Valid.toBoolean, ls.s1Ctx.robId.toBigInt))
    occ += (("T",  ls.tValid.toBoolean,  ls.tCtx.robId.toBigInt))
    occ += (("TX", ls.txValid.toBoolean, ls.txCtx.robId.toBigInt))
    occ += (("P3", ls.p3Valid.toBoolean, ls.p3Ctx.front.robId.toBigInt))
    occ += (("P4", ls.p4Valid.toBoolean, ls.p4Ctx.xlate.front.robId.toBigInt))
    if (hasPark) for (i <- ls.parkValid.indices)
      occ += ((s"PARK$i", ls.parkValid(i).toBoolean, ls.parkMem(i).bk.robId.toBigInt))
    {
      val head = dut.lsEu.robHeadIn.toBigInt
      val p4 = occ.find(_._1 == "P4").get
      val p4Stuck = p4._2 && !flush && stageOcc.get("P4").exists(_._1 == p4._3)
      val olderUp = occ.exists { case (st, v, r) =>
        v && Set("S1", "T", "TX", "P3")(st) && age(r, head) < age(p4._3, head) }
      if (p4Stuck && olderUp) olderBehindStuckP4 += 1
    }
    for ((st, v, r) <- occ) {
      if (!v || flush) stageOcc.remove(st)
      else stageOcc.get(st) match {
        case Some((pr, since)) if pr == r =>
          if (cycle - since > stageBound)
            trip("STAGE-AGE", s"robId=$r has occupied $st for ${cycle - since} cycles")
        case _ => stageOcc(st) = (r, cycle)
      }
    }

    // ── (O) oldest-LS progress ───────────────────────────────────────────────────────
    val ip = dut.lsEu.issuePort
    if (ip.valid.toBoolean && ip.ready.toBoolean) inFlight.getOrElseUpdate(ip.payload.robId.toBigInt, cycle)
    if (ls.compValid.toBoolean) inFlight.remove(ls.compRobId.toBigInt)
    if (flush) { inFlight.clear(); oldestRob = -1; oldestFor = 0 }
    if (inFlight.isEmpty) { oldestRob = -1; oldestFor = 0 }
    else {
      val head = dut.lsEu.robHeadIn.toBigInt
      val oldest = inFlight.keys.minBy(age(_, head))
      if (oldest == oldestRob) {
        oldestFor += 1
        if (oldestFor > oldestBound)
          trip("OLDEST-LS-STARVED", s"robId=$oldest (issued cycle ${inFlight(oldest)}) is the oldest " +
            s"in-flight LS op and uncompleted for $oldestFor cycles; in-flight=" +
            inFlight.keys.toSeq.sortBy(age(_, head)).mkString(","))
      } else { oldestRob = oldest; oldestFor = 0 }
    }
  }

  def dump(): String = {
    val b = new StringBuilder
    // By-name and guarded: a signal that is not simPublic in this build must cost one dump
    // LINE, never the diagnosis itself.
    def ln(s: => String): Unit = {
      val t = try s catch { case e: Throwable => s"<unavailable: ${e.getClass.getSimpleName}>" }
      b ++= s"  [ls-dump] $t\n"
    }
    val head = dut.lsEu.robHeadIn.toBigInt
    ln(f"rob head=${dut.rob.logic.head.toBigInt} count=${dut.rob.logic.count.toBigInt} " +
      f"lsHead=$head headValid=${dut.lsEu.robHeadValidIn.toBoolean} commits=$commits " +
      f"lastCommitPc=0x$lastCommitPc%08x")
    ln(f"issue(v=${dut.lsEu.issuePort.valid.toBoolean},r=${dut.lsEu.issuePort.ready.toBoolean}," +
      f"rob=${dut.lsEu.issuePort.payload.robId.toBigInt})")
    ln(f"s1(v=${ls.s1Valid.toBoolean},rob=${ls.s1Ctx.robId.toBigInt}) " +
      f"t(v=${ls.tValid.toBoolean},rob=${ls.tCtx.robId.toBigInt}) " +
      f"tx(v=${ls.txValid.toBoolean},rob=${ls.txCtx.robId.toBigInt},second=${ls.txSecond.toBoolean}) " +
      f"p3(v=${ls.p3Valid.toBoolean},rob=${ls.p3Ctx.front.robId.toBigInt}) " +
      f"p4(v=${ls.p4Valid.toBoolean},rob=${ls.p4Ctx.xlate.front.robId.toBigInt})")
    ln(f"p4Inhibited=${ls.p4Inhibited.toBoolean} p4AtRobHead=${ls.p4AtRobHead.toBoolean} " +
      f"p4LaunchOk=${ls.p4LaunchOk.toBoolean} p4RetryQuery=${ls.p4RetryQuery.toBoolean} " +
      f"fwdHit=${ls.p4Ctx.fwdHit.toBoolean} fwdStall=${ls.p4Ctx.fwdStall.toBoolean} " +
      f"fwdSerial=${ls.p4Ctx.fwdSerial.toBoolean} " +
      f"paddr=0x${ls.p4Ctx.xlate.paddr.toBigInt}%x")
    if (hasPark) {
      ln(f"park ownsBarrier=${ls.parkOwnsBarrier.toBoolean} hasFree=${ls.parkHasFree.toBoolean} " +
        f"drain=${ls.parkDrain.toBoolean} admit=${ls.parkAdmit.toBoolean}")
      for (i <- ls.parkValid.indices) if (ls.parkValid(i).toBoolean)
        ln(f"  park$i rob=${ls.parkMem(i).bk.robId.toBigInt} saw=${ls.parkSaw(i).toBoolean} " +
          f"age=${age(ls.parkMem(i).bk.robId.toBigInt, head)}")
    }
    ln(f"ring count=${ls.alignedCount.toBigInt} full=${ls.alignedFull.toBoolean} " +
      f"sendPtr=${ls.alignedSendPtr.toBigInt} rspPtr=${ls.alignedRspPtr.toBigInt} " +
      f"pushPtr=${ls.alignedPushPtr.toBigInt} sendValid=${ls.alignedSendValid.toBoolean} " +
      f"sendHeld=${ls.alignedSendHeld.toBoolean} bkBusy=${ls.bkBusy.toBoolean}")
    for (i <- ls.alignedValid.indices) if (ls.alignedValid(i).toBoolean)
      ln(f"  slot$i sent=${ls.alignedSent(i).toBoolean} done=${ls.alignedDone(i).toBoolean} " +
        f"poison=${ls.alignedPoisoned(i).toBoolean} rob=${ls.alignedMem(i).bk.robId.toBigInt} " +
        f"two=${ls.alignedMem(i).twoAccess.toBoolean}")
    ln(f"sq empty=${ls.sq.io.empty.toBoolean} full=${ls.sq.io.full.toBoolean} " +
      f"olderStore=${ls.sq.io.barrier.olderStore.toBoolean} " +
      f"olderInhibStore=${ls.sq.io.barrier.olderInhibitedStore.toBoolean} " +
      f"loadBusy=${ls.loadBusyReg.toBoolean} respSeen=${ls.inhibitedRespSeen.toBoolean} " +
      f"ldOwner=${ls.ldOwner.toBigInt} orderViol=${dut.lsEu.orderViolationPort.valid.toBoolean}")
    val lc = dut.dcache.logic.loadCmdPort
    ln(f"dcache loadCmd(v=${lc.valid.toBoolean},r=${lc.ready.toBoolean}) " +
      f"excLoadOut=${ls.excLoadOutstanding.toBoolean} walkStOut=${ls.walkStOutstanding.toBoolean} " +
      f"coreStOut=${ls.coreStOutstanding.toBigInt}")
    ln {
      val iq = dut.iq.logic
      val lsSlots = iq.slots.zipWithIndex.filter { case (s, _) =>
        s.sel.toBoolean && s.hot.cluster.toEnum.toString.contains("LS") }
      "iq LS slots: " + lsSlots.map { case (s, i) =>
        s"s$i(rob=${s.hot.robId.toBigInt},mem=${s.hot.memOp.toEnum},ready=${s.ready.toBoolean})"
      }.mkString(" ")
    }
    b.toString
  }
}

object LsLivenessMonitor {
  /** Attach to every `PortedTestRunner` run? `FUZZ_LIVENESS=1` forces it on, `=0` off;
    * unset follows `FUZZ_LS_OOO` (the configuration whose liveness is in question). */
  val enabledByEnv: Boolean = sys.env.get("FUZZ_LIVENESS") match {
    case Some(v) => v == "1"
    case None    => sys.env.get("FUZZ_LS_OOO").contains("1")
  }
}
