package m68k040.lockstep

import m68k040.cache.DcachePlugin
import m68k040.execute.LsEuPlugin
import m68k040.execute.regfile.RegFilePluginInt
import m68k040.isa.{MemOp, Size}
import spinal.core.sim._
import scala.collection.mutable

/** Committed-STORE address tripwire (2026-09-08, the p163 "10 bytes at RAM 0x0..0x9" defect).
  *
  * Hardware evidence: a store wrote bytes 0..9 = [ff ff][00 00 00 00][00 00 00 00] into a
  * dirty L1D line (DRAM read back correct until `dcache-op push`), i.e. an errant STORE whose
  * effective address resolved to 0. This helper watches the single ordered committed-store
  * point (`DcachePlugin.logic.storePort`, the same tap `MemWriteCapture` uses: SQ committed
  * drain + exception frame pushes, upstream of the copyback cache) and records every store
  * whose lowest touched PHYSICAL byte is below `limit`, together with everything needed to
  * say WHY its address was what it was:
  *
  *   - the instruction (macro PC, robId) and its S0 operand read: `psrcA` (the physical
  *     base register), `psrcAValid` (false => the AGU substitutes 0), `base0` (the value
  *     actually latched into `s1Base`), the PRF shadow's content of `psrcA` in that cycle,
  *     whether a bypass port served the read, and the LAST physical write into `psrcA`
  *     (port index per RegFilePlugin's documented slot order 0=AluEu0 1=AluEu1 2=BranchEu
  *     3=LsEu 4=DivEu 5=exc/seed, cycle, data);
  *   - the S1 effective address (`s1Base`, `s1Va`) and the SQ allocation (vaddr/paddr);
  *   - the drained payload (paddr/data/size/strb) and whether it came out of the SQ
  *     (`sendPtr` entry -> robId) or the exception unit's frame path.
  *
  * `allow` whitelists legitimate low writes (a test that installs vectors below `limit`).
  * Call `onCycle()` once per sampled cycle; read `hits` / `report` after the run. */
final class StoreAddrTripwire(lsEu: LsEuPlugin, dc: DcachePlugin, rfInt: RegFilePluginInt,
                              val limit: Long = 0x10L, allow: Long => Boolean = _ => false) {
  final case class PrfWrite(port: Int, cycle: Long, data: Long)
  final case class StoreRec(robId: Int, pc: Long, issueCycle: Long, psrcA: Int, psrcAValid: Boolean,
                            base0: Long, prfShadow: Long, lastWriter: Option[PrfWrite],
                            bypass: Option[(Int, Long)], eaAuto: Int, pdst: Int, pdstValid: Boolean,
                            stkPush: Boolean) {
    var s1Base: Long = -1L; var s1Va: Long = -1L; var s1AnWb: Long = -1L
    var vaddr: Long = -1L; var paddr: Long = -1L; var allocCycle: Long = -1L
    def baseSource: String =
      if (!psrcAValid) "NO-BASE (psrcAValid=0 => AGU base forced to 0)"
      else bypass match {
        case Some((port, d)) => f"BYPASS port=$port data=0x$d%08x"
        case None =>
          val w = lastWriter.map(w => f"last PRF write port=${w.port} cycle=${w.cycle} data=0x${w.data}%08x").getOrElse("never written")
          (if (base0 == prfShadow) "PRF (matches shadow)" else f"PRF MISMATCH shadow=0x$prfShadow%08x") + s"; $w"
      }
    override def toString: String =
      f"rob=$robId pc=0x$pc%08x issue@$issueCycle psrcA=p$psrcA valid=$psrcAValid base0=0x$base0%08x " +
      f"eaAuto=$eaAuto pdst=p$pdst/$pdstValid stkPush=$stkPush s1Base=0x$s1Base%08x s1Va=0x$s1Va%08x " +
      f"anWb=0x$s1AnWb%08x alloc@$allocCycle vaddr=0x$vaddr%08x paddr=0x$paddr%08x <- $baseSource"
  }
  final case class Hit(cycle: Long, paddr: Long, lowest: Long, data: Long, size: Int, useStrb: Boolean,
                       strb: Int, lineData: BigInt, precise: Boolean, src: String, rec: Option[StoreRec]) {
    override def toString: String =
      f"[tripwire] cycle=$cycle STORE paddr=0x$paddr%08x lowest=0x$lowest%08x " +
      (if (useStrb) f"strb=0x$strb%04x line=0x$lineData%032x" else f"data=0x$data%08x size=$size") +
      f" precise=$precise via=$src :: ${rec.map(_.toString).getOrElse("(no issue record)")}"
  }

  val hits = mutable.ArrayBuffer[Hit]()
  /** Every STORE issue seen (by robId, latest wins) -- kept for post-mortem dumps. */
  val recs = mutable.LinkedHashMap[Int, StoreRec]()
  private val lastWriter = mutable.HashMap[Int, PrfWrite]()
  private var cycle = 0L
  private var s1Seen = -1
  var storesDrained = 0L

  def onCycle(): Unit = {
    cycle += 1
    val ls = lsEu.logic
    // 1. physical write stream (merged buses; slot order documented in RegFilePlugin)
    val w = rfInt.logic.dbgW
    var i = 0
    while (i < w.size) {
      if (w(i).valid.toBoolean)
        lastWriter(w(i).address.toInt) = PrfWrite(i, cycle, w(i).data.toLong & 0xffffffffL)
      i += 1
    }
    // 2. S0: an LS STORE issue -- capture the base operand as read
    val ip = lsEu.issuePort
    if (ip.valid.toBoolean && ip.ready.toBoolean) {
      val u = ip.payload.uop
      if (u.memOp.toEnum == MemOp.STORE) {
        val pa   = u.psrcA.toInt
        val byp  = rfInt.logic.dbgByp
        val bh   = (0 until byp.size).find(k => byp(k).valid.toBoolean && byp(k).address.toInt == pa)
                     .map(k => (k, byp(k).data.toLong & 0xffffffffL))
        val rob  = ip.payload.robId.toInt
        recs(rob) = StoreRec(rob, u.pc.toLong & 0xffffffffL, cycle, pa, u.psrcAValid.toBoolean,
                             ls.base0.toLong & 0xffffffffL,
                             rfInt.logic.shadow(pa).toLong & 0xffffffffL,
                             lastWriter.get(pa), bh, u.eaAuto.toEnum.position, u.pdst.toInt,
                             u.pdstValid.toBoolean, u.stkPush.toBoolean)
      }
    }
    // 3. S1: first cycle a store context sits in S1 (held stable while busy)
    if (ls.s1Valid.toBoolean) {
      val r = ls.s1Ctx.robId.toInt
      if (r != s1Seen) {
        s1Seen = r
        recs.get(r).foreach { x =>
          x.s1Base = ls.s1Base.toLong & 0xffffffffL
          x.s1Va   = ls.s1Va.toLong & 0xffffffffL
          x.s1AnWb = ls.s1AnWb.toLong & 0xffffffffL
        }
      }
    } else s1Seen = -1
    // 4. SQ allocation (translated address)
    val al = ls.sq.io.alloc
    if (al.valid.toBoolean) {
      recs.get(al.payload.robId.toInt).foreach { x =>
        x.vaddr = al.payload.vaddr.toLong & 0xffffffffL
        x.paddr = al.payload.paddr.toLong & 0xffffffffL
        x.allocCycle = cycle
      }
    }
    // 5. the committed drain
    val sp = dc.logic.storePort
    if (sp.valid.toBoolean && sp.ready.toBoolean) {
      storesDrained += 1
      val p     = sp.payload
      val paddr = p.paddr.toLong & 0xffffffffL
      val useStrb = p.useStrb.toBoolean
      val strb  = if (useStrb) p.strb.toInt & 0xffff else 0
      val lowest =
        if (useStrb) (paddr & ~0xfL) + (0 until 16).find(k => ((strb >> k) & 1) == 1).getOrElse(0)
        else paddr
      if (lowest < limit && !allow(lowest)) {
        val sqDrain = ls.sq.io.drain.valid.toBoolean && ls.sq.io.drain.ready.toBoolean
        val (src, rec) =
          if (sqDrain) {
            val e = ls.sq.sendPtr.toInt
            (s"SQ entry $e", recs.get(ls.sq.robIds(e).toInt))
          } else ("EXC-frame/other (no SQ drain handshake this cycle)", None)
        val size = p.size.toEnum match {
          case Size.BYTE => 1
          case Size.WORD => 2
          case _         => 4
        }
        hits += Hit(cycle, paddr, lowest, p.data.toLong & 0xffffffffL, size, useStrb, strb,
                    if (useStrb) p.lineData.toBigInt else BigInt(0), p.precise.toBoolean, src, rec)
      }
    }
  }

  def report: String =
    if (hits.isEmpty) f"[tripwire] no committed store below 0x$limit%x ($storesDrained stores drained)"
    else hits.mkString("\n")

  /** The last `n` STORE issue records, for a post-mortem dump. */
  def recentStores(n: Int): String = recs.values.toSeq.takeRight(n).mkString("\n")
}
