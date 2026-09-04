package m68k040.lockstep

import m68k040.cache.DcachePlugin
import m68k040.oracle.MemoryWriteEvent
import spinal.core.sim._
import scala.collection.mutable

/** Ordered byte-granular capture of the DUT's ARCHITECTURAL store stream.
  *
  * `LockStep.scala:20-21` says memory writes are *"captured but NOT yet compared"*; in
  * fact the `CommitObservation.memAddr/memData/memWrite` fields were never even
  * populated -- `WhiteboxCapture` hardcodes them to 0/false. So there was nothing to
  * un-discard, and this is a fresh capture from a better tap.
  *
  * The tap is `DcachePlugin.logic.storePort` (already fully `simPublic`). It is the
  * single ordered point through which BOTH the store queue's committed drain and the
  * exception unit's frame pushes reach memory (`LsEuPlugin.scala:293-295` and the
  * `excStoreReady` arbitration at `:2867`), and it sits UPSTREAM of the copyback cache,
  * so it carries program store order rather than writeback order. Speculative stores
  * never reach it: the SQ drains committed entries only.
  *
  * Musashi's ordered per-byte log is `OracleState.memoryWriteEvents` (`mem_event[...]`
  * lines, `musashi_run.cpp:326-329`), emitted MSB-first per access by
  * `m68k_ref.cpp:484-496`. Expanding a DUT store into ascending byte addresses therefore
  * lines up with it exactly. */
object MemWriteCapture {

  final case class ByteWrite(addr: Long, value: Int, cycle: Long)

  final class Handle(dc: DcachePlugin) {
    private val sp = dc.logic.storePort
    val events = mutable.ArrayBuffer[ByteWrite]()
    private var cycle = 0L

    /** Call once per sampled cycle. */
    def onCycle(): Unit = {
      cycle += 1
      if (!(sp.valid.toBoolean && sp.ready.toBoolean)) return
      val p     = sp.payload
      val paddr = p.paddr.toLong & 0xffffffffL
      if (p.useStrb.toBoolean) {
        // Line-relative byte strobe: lane i covers byte (paddr & ~0xF) + i, and
        // `lineData` is `Vec(Bits(8),16).asBits`, so lane i sits at bits [8i+7 : 8i].
        val strb = sp.payload.strb.toBigInt
        val line = sp.payload.lineData.toBigInt
        val base = paddr & ~0xfL
        for (i <- 0 until 16 if strb.testBit(i))
          events += ByteWrite(base + i, ((line >> (8 * i)) & 0xff).toInt, cycle)
      } else {
        val n = p.size.toEnum match {
          case m68k040.isa.Size.BYTE => 1
          case m68k040.isa.Size.WORD => 2
          case _                     => 4
        }
        val data = p.data.toLong & 0xffffffffL
        for (k <- 0 until n)
          events += ByteWrite(paddr + k, ((data >> (8 * (n - 1 - k))) & 0xff).toInt, cycle)
      }
    }
  }

  final case class MemCompareResult(error: Option[String], silentRewrites: Int,
                                    dutWrites: Int, oracleWrites: Int)

  /** ORDERED comparison of the DUT's byte-write stream against the reference's.
    *
    * `inScope` filters both sides identically. The intended caller passes the program's
    * data sandbox, which excludes the supervisor stack: exception FRAME layouts legally
    * differ between this core (68040 formats $0/$2/$7) and Musashi's model, so stack
    * bytes are not a like-for-like comparison and asserting on them would be a
    * manufactured false positive.
    *
    * ── SILENT REWRITES, and why they are counted rather than failed ─────────────────
    * `docs/superpowers/specs/2026-06-24-cas-cas2-design.md` §6 records a deliberate,
    * user-approved simplification: CAS/CAS2 have no conditional store, so on a FAILED
    * compare they store the just-loaded value back. The doc states the cost outright --
    * *"a redundant bus write on mismatch (invisible to the architectural oracle; matters
    * only to an external bus observer we don't model)"*. This comparator IS that bus
    * observer, and it re-derived the deviation from a 7-line program on its first run.
    *
    * A DUT write is therefore classified SILENT when it writes the byte the shadow
    * memory already holds and the reference performs no matching write. Those are
    * counted and reported, not failed. Everything else -- a reference write the DUT
    * never performed, a value disagreement, an extra DUT write that CHANGES a byte --
    * is a hard failure. The count is surfaced so a spike (a genuinely duplicated store)
    * is visible rather than absorbed.
    *
    * This is strictly stronger than the pre-existing final-image check in
    * `FuzzLockStepSpec`, which iterates only addresses the ORACLE wrote and therefore
    * cannot see a DUT store to an address the oracle never touched, nor any ordering. */
  def compare(dut: Seq[ByteWrite], oracle: Seq[MemoryWriteEvent],
              inScope: Long => Boolean, initialByte: Long => Int): MemCompareResult = {
    val d = dut.filter(w => inScope(w.addr)).toVector
    val o = oracle.filter(w => inScope(w.address & 0xffffffffL)).toVector
    val shadow = new scala.collection.mutable.HashMap[Long, Int]()
    def cur(a: Long): Int = shadow.getOrElse(a, initialByte(a) & 0xff)
    def window(i: Int, j: Int): String = {
      def slice[A](v: Vector[A], k: Int)(f: A => String) =
        (math.max(0, k - 4) until math.min(k + 5, v.size)).map(x => f(v(x))).mkString(" ")
      s"\n    dut[${math.max(0, i - 4)}..]: ${slice(d, i)(w => f"0x${w.addr}%x=${w.value}%02x")}" +
      s"\n    orc[${math.max(0, j - 4)}..]: ${slice(o, j)(w => f"0x${w.address & 0xffffffffL}%x=${w.value & 0xff}%02x")}"
    }
    var i = 0; var j = 0; var silent = 0
    while (i < d.size && j < o.size) {
      val a = d(i); val b = o(j)
      if (a.addr == (b.address & 0xffffffffL) && a.value == (b.value & 0xff)) {
        shadow(a.addr) = a.value; i += 1; j += 1
      } else if (a.value == cur(a.addr)) {
        silent += 1; i += 1   // value-preserving extra DUT write (CAS/CAS2 §6 class)
      } else {
        return MemCompareResult(Some(
          f"store dut#$i/orc#$j: dut mem[0x${a.addr}%08x]=0x${a.value}%02x (was 0x${cur(a.addr)}%02x) " +
          f"vs oracle mem[0x${b.address & 0xffffffffL}%08x]=0x${b.value & 0xff}%02x " +
          f"(dut cycle ${a.cycle})" + window(i, j)), silent, d.size, o.size)
      }
    }
    while (i < d.size) {
      val a = d(i)
      if (a.value == cur(a.addr)) { silent += 1; i += 1 }
      else return MemCompareResult(Some(
        f"extra DUT store #$i mem[0x${a.addr}%08x]=0x${a.value}%02x (was 0x${cur(a.addr)}%02x) " +
        f"with no reference write left (dut cycle ${a.cycle})" + window(i, j)), silent, d.size, o.size)
    }
    if (j < o.size) {
      val b = o(j)
      return MemCompareResult(Some(
        f"MEMORY WRITE MISSING: reference wrote mem[0x${b.address & 0xffffffffL}%08x]=0x${b.value & 0xff}%02x " +
        f"(oracle store #$j) and the DUT never did" + window(i, j)), silent, d.size, o.size)
    }
    MemCompareResult(None, silent, d.size, o.size)
  }
}
