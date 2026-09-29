package m68k040.fuzz

import m68k040.cache.{AxiIds, CacheMode}
import spinal.core.sim._
import scala.collection.mutable

/** D4 oracle: a PASSIVE, posture-independent checker of "a cache-inhibited access is a full
  * memory barrier in both directions", observed where it matters -- on the D-side AXI master
  * and at the D-cache command boundary -- rather than inferred from the RTL's own gates.
  *
  * It reads the DUT and never drives it, so attaching it cannot change a run.
  *
  * ==BUS LEVEL (the P6 property: what the fabric will actually see)==
  * Every AR and AW handshake starts a transaction; the LAST R beat / the B ends it (matched
  * per id, AXI in-order-per-id). A transaction is INHIBITED when it is
  *   - an AR on the refill id while `missCmode === INHIBITED` (an inhibited load transits the
  *     load FSM's REFILL -- `DcachePlugin`'s own comment), or
  *   - an AW on `D_STORE` while `stSubActive` (set at kickoff iff the store is INHIBITED).
  *   busBefore : an inhibited transaction started while ANY other transaction was outstanding
  *   busAfter  : any transaction started while an inhibited transaction was outstanding
  *               (an inhibited store's own next sub-transaction excepted -- it is the same
  *               access, split by `D30` into naturally-aligned pieces)
  *
  * ==COMMAND LEVEL (the LS-EU property: "no younger LS op launches until it completes")==
  * A load command is outstanding from `loadCmdPort` accept to its `loadRspPort` response
  * (matched by token + rid); a store from `storePort` accept to its `storeAck` (acks are in
  * accept order).
  *   cmdBefore : an inhibited command was accepted while another command was outstanding
  *   cmdAfter  : any command was accepted while an inhibited command was outstanding
  *
  * The command level is STRICTER than the bus level: a younger load captured into the
  * D-cache's shadow slot behind an inhibited miss never reaches the bus early (the load FSM
  * is serial) but it HAS launched. D4 forbids both. */
final class InhibitedBarrierMonitor(dut: FuzzCoreDut, val label: String) {
  private final case class Txn(isRead: Boolean, id: Int, addr: Long, inhib: Boolean, cyc: Long) {
    override def toString: String =
      f"${if (isRead) "AR" else "AW"}(id=$id,0x$addr%08x${if (inhib) ",INHIB" else ""},c$cyc)"
  }
  private final case class LdCmd(token: Int, rid: Int, ridV: Boolean, inhib: Boolean, paddr: Long, cyc: Long)

  private val outR  = mutable.ArrayBuffer.empty[Txn]
  private val outW  = mutable.ArrayBuffer.empty[Txn]
  private val ldOut = mutable.ArrayBuffer.empty[LdCmd]
  private val stOut = mutable.Queue.empty[(Boolean, Long)]   // (inhib, paddr) in accept order

  var cycles = 0L
  var inhibReads = 0L; var inhibWrites = 0L; var reads = 0L; var writes = 0L
  var inhibLoadCmds = 0L; var inhibStoreCmds = 0L
  val devReadAddrs  = mutable.ArrayBuffer.empty[Long]
  val devWriteAddrs = mutable.ArrayBuffer.empty[Long]
  val busBefore = mutable.ArrayBuffer.empty[String]
  val busAfter  = mutable.ArrayBuffer.empty[String]
  val cmdBefore = mutable.ArrayBuffer.empty[String]
  val cmdAfter  = mutable.ArrayBuffer.empty[String]
  /** Load responses that matched no outstanding command -- a monitor-model gap, reported so
    * a silent mis-match cannot hide a violation. */
  var unmatchedRsp = 0L

  private val MaxKept = 12
  private def keep(buf: mutable.ArrayBuffer[String], s: => String): Unit =
    if (buf.size < MaxKept) buf += s else buf += ""   // count every one, keep the first few

  def count(buf: mutable.ArrayBuffer[String]): Int = buf.size
  def violations: Int = busBefore.size + busAfter.size + cmdBefore.size + cmdAfter.size

  private val ax = dut.dcache.logic.axi
  private val lc = dut.dcache.logic.loadCmdPort
  private val lr = dut.dcache.logic.loadRspPort
  private val sp = dut.dcache.logic.storePort

  /** RTL mechanism counters, SNAPSHOTTED every cycle (a signal cannot be read after the
    * sim ends): loadLaunches, loadCompletes, storeLaunches, quietWait, youngerHeld,
    * walkerFenced. All zero, and `rtlPresent` false, when the barrier is not built. */
  private val d4c = dut.lsEu.logic.d4Sim
  val rtlPresent: Boolean = d4c != null
  val rtl = Array.fill(6)(0L)
  def rtlLoadLaunches = rtl(0); def rtlLoadCompletes = rtl(1); def rtlStoreLaunches = rtl(2)
  def rtlQuietWait = rtl(3); def rtlYoungerHeld = rtl(4); def rtlWalkerFenced = rtl(5)

  def sample(): Unit = {
    cycles += 1
    if (rtlPresent) {
      rtl(0) = d4c.loadLaunches.toLong; rtl(1) = d4c.loadCompletes.toLong
      rtl(2) = dut.lsEu.logic.sq.d4StoreLaunches.toLong
      rtl(3) = d4c.quietWaitCycles.toLong; rtl(4) = d4c.youngerHeldCycles.toLong
      rtl(5) = d4c.walkerFencedCycles.toLong
    }
    // ---------------- bus level ----------------
    // Completions first: a transaction that ends this cycle is not outstanding for a start
    // in the same cycle (the fabric sees the end before or with the next start).
    if (ax.r.valid.toBoolean && ax.r.ready.toBoolean && ax.r.payload.last.toBoolean) {
      val id = ax.r.payload.id.toInt
      val i = outR.indexWhere(_.id == id); if (i >= 0) outR.remove(i)
    }
    if (ax.b.valid.toBoolean && ax.b.ready.toBoolean) {
      val id = ax.b.payload.id.toInt
      val i = outW.indexWhere(_.id == id); if (i >= 0) outW.remove(i)
    }
    def start(t: Txn): Unit = {
      val others = (outR ++ outW)
      val inhibOut = others.filter(_.inhib)
      if (t.inhib && others.nonEmpty) {
        // An inhibited store's next D30 sub-transaction following its own previous one is
        // the SAME access; only report it when something ELSE is outstanding.
        val foreign = if (!t.isRead) others.filterNot(o => o.inhib && !o.isRead) else others
        if (foreign.nonEmpty)
          keep(busBefore, s"c$cycles $t started with outstanding ${foreign.mkString(",")}")
      }
      if (inhibOut.nonEmpty) {
        val sameAccess = t.inhib && !t.isRead && inhibOut.forall(o => !o.isRead)
        if (!sameAccess)
          keep(busAfter, s"c$cycles $t started while inhibited ${inhibOut.mkString(",")} outstanding")
      }
      if (t.isRead) outR += t else outW += t
    }
    if (ax.ar.valid.toBoolean && ax.ar.ready.toBoolean) {
      val id = ax.ar.payload.id.toInt
      val inhib = id == AxiIds.dRefill(0) &&
        dut.dcache.logic.missCmode.toEnum == CacheMode.INHIBITED
      val a = ax.ar.payload.addr.toLong & 0xffffffffL
      reads += 1; if (inhib) { inhibReads += 1; devReadAddrs += a }
      start(Txn(isRead = true, id, a, inhib, cycles))
    }
    if (ax.aw.valid.toBoolean && ax.aw.ready.toBoolean) {
      val id = ax.aw.payload.id.toInt
      val inhib = id == AxiIds.D_STORE && dut.dcache.logic.stSubActive.toBoolean
      val a = ax.aw.payload.addr.toLong & 0xffffffffL
      writes += 1; if (inhib) { inhibWrites += 1; devWriteAddrs += a }
      start(Txn(isRead = false, id, a, inhib, cycles))
    }

    // ---------------- command level ----------------
    if (lr.valid.toBoolean) {
      val tok = lr.payload.token.toInt; val rid = lr.payload.rid.toInt
      val ridV = lr.payload.ridValid.toBoolean
      val i = ldOut.indexWhere(c => c.token == tok && c.ridV == ridV && (!ridV || c.rid == rid))
      if (i >= 0) ldOut.remove(i) else unmatchedRsp += 1
    }
    if (dut.dcache.logic.storeAckReg.toBoolean && stOut.nonEmpty) stOut.dequeue()
    def cmdStart(inhib: Boolean, what: String): Unit = {
      val othersLd = ldOut.size; val othersSt = stOut.size
      val inhibOut = ldOut.exists(_.inhib) || stOut.exists(_._1)
      if (inhib && (othersLd + othersSt) > 0)
        keep(cmdBefore, s"c$cycles inhibited $what accepted with $othersLd load(s) / $othersSt store(s) " +
          s"outstanding at the D-cache (${ldOut.map(c => f"ld 0x${c.paddr}%x@c${c.cyc}").mkString(",")})")
      if (inhibOut && !inhib)
        keep(cmdAfter, s"c$cycles $what accepted while an inhibited command is outstanding " +
          s"(${ldOut.filter(_.inhib).map(c => f"ld 0x${c.paddr}%x@c${c.cyc}").mkString(",")}" +
          s"${if (stOut.exists(_._1)) " + inhibited store" else ""})")
      else if (inhibOut && inhib)
        keep(cmdAfter, s"c$cycles inhibited $what accepted while ANOTHER inhibited command is outstanding")
    }
    if (lc.valid.toBoolean && lc.ready.toBoolean) {
      val inhib = lc.payload.cacheMode.toEnum == CacheMode.INHIBITED
      val pa = lc.payload.paddr.toLong & 0xffffffffL
      if (inhib) inhibLoadCmds += 1
      cmdStart(inhib, f"load 0x$pa%08x")
      ldOut += LdCmd(lc.payload.token.toInt, lc.payload.rid.toInt, lc.payload.ridValid.toBoolean,
                     inhib, pa, cycles)
    }
    if (sp.valid.toBoolean && sp.ready.toBoolean) {
      val inhib = sp.payload.cacheMode.toEnum == CacheMode.INHIBITED
      val pa = sp.payload.paddr.toLong & 0xffffffffL
      if (inhib) inhibStoreCmds += 1
      // A split inhibited store's second half following its first is the same access.
      val sameAccess = inhib && stOut.nonEmpty && stOut.forall(_._1) && ldOut.isEmpty
      if (!sameAccess) cmdStart(inhib, f"store 0x$pa%08x")
      stOut.enqueue((inhib, pa))
    }
  }

  def attach(): this.type = { dut.clockDomain.onSamplings(sample()); this }

  def rtlCounters: Option[String] =
    if (!rtlPresent) None else Some(
      s"rtl: loadLaunches=$rtlLoadLaunches loadCompletes=$rtlLoadCompletes " +
      s"storeLaunches=$rtlStoreLaunches quietWait=$rtlQuietWait youngerHeld=$rtlYoungerHeld " +
      s"walkerFenced=$rtlWalkerFenced")

  def summary: String =
    s"[d4-monitor] $label cycles=$cycles reads=$reads writes=$writes inhibR=$inhibReads " +
    s"inhibW=$inhibWrites inhibLdCmd=$inhibLoadCmds inhibStCmd=$inhibStoreCmds " +
    s"busBefore=${busBefore.size} busAfter=${busAfter.size} cmdBefore=${cmdBefore.size} " +
    s"cmdAfter=${cmdAfter.size} unmatchedRsp=$unmatchedRsp" + rtlCounters.map(" " + _).getOrElse("")

  def details(n: Int = 4): String = {
    def f(tag: String, b: mutable.ArrayBuffer[String]) =
      if (b.isEmpty) "" else s"\n  $tag (${b.size}): " + b.filter(_.nonEmpty).take(n).mkString("\n    ")
    f("busBefore", busBefore) + f("busAfter", busAfter) + f("cmdBefore", cmdBefore) + f("cmdAfter", cmdAfter)
  }
}
