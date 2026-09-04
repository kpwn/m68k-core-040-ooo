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
                                    reordered: Int, dutWrites: Int, oracleWrites: Int,
                                    silentSample: Seq[ByteWrite] = Nil)

  /** How far ahead a reference write may be matched out of order.
    *
    * NOT a fudge factor -- it is the size of one known, source-confirmed ORACLE modelling
    * artifact. Musashi's `MOVE.L <ea>,-(An)` handlers hardcode two 16-bit writes, LOW half
    * first (`m68k_in.c:6349-6355`, `m68ki_write_16(ea+2, ...)` then
    * `m68ki_write_16(ea, ...)`), unconditionally for every CPU type -- the 68000/68010
    * predecrement-long bus quirk. Note this is NOT the `M68K_SIMULATE_PD_WRITES` path,
    * which is OFF (`m68kconf.h:102`); the split is in the opcode handler itself. A 68040
    * with a 32-bit port issues ONE long write, which is what this core does, so the DUT is
    * the more accurate side and the reference stream is the one out of order.
    *
    * Measured: 13 of the 200 fuzz seeds hit this and nothing else. 8 covers the 4-byte
    * reorder with slack. A reorder is only allowed across writes to DIFFERENT addresses,
    * so two writes to the SAME byte still have to arrive in the right order. */
  val ReorderWindow = 8

  /** ORDERED comparison of the DUT's byte-write stream against the reference's.
    *
    * `inScope` filters both sides identically. The intended caller passes the program's
    * data sandbox, which excludes the supervisor stack: exception FRAME layouts legally
    * differ between this core (68040 formats $0/$2/$7) and Musashi's model, so stack
    * bytes are not a like-for-like comparison and asserting on them would be a
    * manufactured false positive.
    *
    * ── SILENT REWRITES, and why they are counted rather than failed ─────────────────
    * This core has TWO deliberate, documented always-write simplifications. Both write a
    * byte back unchanged where a real 68040 would not write at all, so both are invisible
    * to any architectural oracle and visible only to a bus observer. This comparator is
    * that bus observer, and it re-derived BOTH from minimized fuzz repros -- the first
    * from a 7-line program on its very first run.
    *
    *  1. CAS / CAS2 on a FAILED compare. `2026-06-24-cas-cas2-design.md` §6: "the engine
    *     has no conditional store ... CAS/CAS2 always issue their store(s)", cost stated
    *     as *"a redundant bus write on mismatch (invisible to the architectural oracle;
    *     matters only to an external bus observer we don't model)"*. Minimal repro:
    *     `cas2.w %d2:%d3,%d4:%d5,(%a0):(%a1)` with a mismatching compare -> 4 silent bytes.
    *  2. Memory bit-field RMW spill byte. `2026-06-30-bitfield-mem-dynamic-3c.md`:
    *     "Always-5-byte span (no data-dependent branch in the straight-line engine);
    *     funnel leaves the spill byte unchanged when unused -> RMW write-back is a no-op."
    *     Minimal repro: `bfchg 4(%a2){#10:#17}` -> 1 silent byte one past the field.
    *
    * Measured over 200 fuzz seeds: 187 silent bytes across 53 seeds, all attributable to
    * these two. With `FUZZ_SKIP=cas,cas2` the count falls to the bit-field class alone.
    * LOCKSTEP_MEM_STRICT=1 promotes them to hard divergences so the fuzz minimizer can
    * shrink any FUTURE one down to its instruction -- which is how class 2 was found.
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
    val oMatched = Array.fill(o.size)(false)
    var i = 0; var j = 0; var silent = 0; var reordered = 0
    val silentSample = new scala.collection.mutable.ArrayBuffer[ByteWrite]()
    while (i < d.size) {
      while (j < o.size && oMatched(j)) j += 1
      val a = d(i)
      // Search the window for an unmatched reference write of the same (addr, value).
      // Stop early at an unmatched reference write to the SAME address: consuming past it
      // would be reordering two writes to one byte, which is a real ordering error.
      var k = j; var hit = -1
      val lim = math.min(j + ReorderWindow, o.size)
      var stop = false
      while (k < lim && hit < 0 && !stop) {
        if (!oMatched(k)) {
          val b = o(k)
          if ((b.address & 0xffffffffL) == a.addr) {
            if ((b.value & 0xff) == a.value) hit = k else stop = true
          }
        }
        k += 1
      }
      if (hit >= 0) {
        oMatched(hit) = true
        if (hit != j) reordered += 1
        shadow(a.addr) = a.value
        i += 1
      } else if (a.value == cur(a.addr)) {
        silent += 1; if (silentSample.size < 8) silentSample += a; i += 1  // value-preserving extra DUT write
      } else {
        val b = if (j < o.size) o(j) else MemoryWriteEvent(0, 0)
        return MemCompareResult(Some(
          f"store dut#$i/orc#$j: dut mem[0x${a.addr}%08x]=0x${a.value}%02x (was 0x${cur(a.addr)}%02x) " +
          f"has no matching reference write within $ReorderWindow " +
          f"(reference head: mem[0x${b.address & 0xffffffffL}%08x]=0x${b.value & 0xff}%02x, " +
          f"dut cycle ${a.cycle})" + window(i, j)), silent, reordered, d.size, o.size, silentSample.toSeq)
      }
    }
    val missing = (0 until o.size).find(!oMatched(_))
    missing match {
      case Some(k) =>
        val b = o(k)
        MemCompareResult(Some(
          f"MEMORY WRITE MISSING: reference wrote mem[0x${b.address & 0xffffffffL}%08x]=0x${b.value & 0xff}%02x " +
          f"(oracle store #$k) and the DUT never did" + window(d.size, k)),
          silent, reordered, d.size, o.size, silentSample.toSeq)
      case None => MemCompareResult(None, silent, reordered, d.size, o.size, silentSample.toSeq)
    }
  }
}
