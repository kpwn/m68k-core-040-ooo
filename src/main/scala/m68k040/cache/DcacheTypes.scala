package m68k040.cache

import m68k040.isa.Size
import spinal.core._

/** Load-command token layout.
  *
  * Bit [7] is the NON-LS-SOURCE flag. When it is 0 the token is an ordinary LS-pipe
  * token: [6] is the split half and [5:0] the ROB id, so the LS EU's early-VIPT probe
  * and its later resolved `DLoadCmd` carry the same value and the D-cache can match
  * them (`DcachePlugin`'s `earlyProbeTokens` CAM).
  *
  * When bit [7] is 1 the command did NOT come from the LS pipe and can never own an
  * early probe. Every such value is RESERVED and enumerated here — a don't-care token
  * on a non-LS command could alias a live early-probe entry on token AND vaddr and be
  * silently answered with that probe's captured data (`earlyProbeOwnsCmd`), which is a
  * silent wrong-data bug rather than a hang. The three reserved values are:
  *
  *   0x80  EXC        the commit-side exception sequencer's frame/vector loads
  *   0x81  WALK_ITLB  the ITLB table walker's descriptor reads
  *   0x82  WALK_DTLB  the DTLB table walker's descriptor reads
  */
object DLoadToken {
  val Width = 8
  /** Commit-side exception sequencer (`LsEuPlugin`'s exception override mux). */
  val EXC       = 0x80
  /** ITLB table-walk descriptor read. */
  val WALK_ITLB = 0x81
  /** DTLB table-walk descriptor read. */
  val WALK_DTLB = 0x82
}

/** Early VIPT lookup request. `vaddr` selects the page-invariant set in parallel
  * with the DTLB lookup and `token` associates the result with the later command.
  * `resolved` supports an already-known PA hint. Normally the probe launches with
  * `resolved=False`; the following tokenized DLoadProbeResolve qualifies the same
  * synchronous array read when the registered DTLB response arrives. If that
  * qualification is late, the entry is deliberately unusable and the later command
  * falls back to the ordinary resolved read path. */
case class DLoadProbe() extends Bundle {
  val vaddr     = UInt(32 bits)
  val token     = UInt(DLoadToken.Width bits)
  val resolved  = Bool()
  val paddr     = UInt(32 bits)
  val size      = Size()
  val cacheMode = CacheMode()
  val needsLine = Bool()
}

/** Cancel an early probe which completed by SQ forwarding or faulted translation.
  * `all` is the squash/exception form and invalidates every resident token. */
case class DLoadProbeCancel() extends Bundle {
  val token = UInt(DLoadToken.Width bits)
  val all   = Bool()
}

/** Physical-tag qualification for the synchronous read launched by DLoadProbe.
  * The registered DTLB result arrives alongside that read's BRAM outputs; matching
  * by token lets the cache finish the VIPT tag compare without retaining every
  * way's raw tag/data or performing a second array read. A late resolution may be
  * ignored safely; the later resolved DLoadCmd then uses the ordinary path. */
case class DLoadProbeResolve() extends Bundle {
  val token     = UInt(DLoadToken.Width bits)
  val paddr     = UInt(32 bits)
  val cacheMode = CacheMode()
}

/** Resolved load request: a virtual address + access size + the PRE-TRANSLATED physical
  * address. VIPT: the cache indexes with `vaddr[set]` (page-invariant low bits)
  * and tags with the physical page number carried in `paddr`. The requester (LS
  * EU) supplies `paddr` from a REGISTERED translate stage. Translation must have
  * completed without fault before this command is presented; the D-cache never
  * samples the live, untagged DTLB response. Under identity translation paddr ==
  * vaddr; for the exception serializing path paddr is the identity vaddr.
  * `paddr[11:0]` must equal `vaddr[11:0]` (same page offset) by construction. */
case class DLoadCmd() extends Bundle {
  val vaddr     = UInt(32 bits)
  val paddr     = UInt(32 bits)
  val size      = Size()
  val cacheMode = CacheMode()
  val token     = UInt(DLoadToken.Width bits)
}

/** Load response: size-extracted (byte-lane, big-endian) data + fault. `line` is
  * the raw 128-bit cache line the access hit (byte i = line[i*8 +: 8]); the LS EU
  * uses it for the cross-boundary merge of a misaligned access spanning two lines.
  * The aligned fast path uses `data` exactly as before. */
case class DLoadRsp() extends Bundle {
  val data  = Bits(32 bits)
  val line  = Bits(128 bits)
  val fault = Bool()
}

/** Store command from the SQ drain: a PHYSICAL address (already translated),
  * the store data in the low bytes per size, and the access size. Mode-aware
  * (WRITETHROUGH/COPYBACK/INHIBITED) per `cacheMode` -- see `DcachePlugin`'s own
  * class doc for the per-mode drain policy.
  *
  * `useStrb` selects an explicit line-relative byte strobe + 128-bit line-aligned
  * data (`strb`/`lineData`) instead of deriving the merge from {data,size,paddr-
  * offset} via the byte-lane. The SQ uses the explicit form to drain a SPLIT
  * (cross-line/page) store slot whose byte count need not be a clean 1/2/4 Size
  * (e.g. a 3-byte slot). Aligned stores leave `useStrb=false` (unchanged path). */
case class DStoreCmd() extends Bundle {
  val paddr     = UInt(32 bits)
  val data      = Bits(32 bits)
  val size      = Size()
  val useStrb   = Bool()
  val strb      = Bits(16 bits)
  val lineData  = Bits(128 bits)
  val cacheMode = CacheMode()
  val precise   = Bool()   // this drain is on the SQ's at-head precise path (Task P2) --
                            // gates whether a bus error here goes to the SQ's
                            // sqFaultCompletion (already true today, unaffected by this
                            // task) or the NEW async diagnostic channel (Task P4.5)
}

/** Cache-maintenance (CPUSH / CINV) command — Task P5.1's decode encoding, issued
  * by the ExceptionUnit's commit-time sysOp path and serviced by `DcachePlugin`'s
  * standalone maintenance-walk FSM.
  *
  * `push` (CPUSH) writes dirty matching lines back to memory; `invalidate` (CINV,
  * and CPUSH's own invalidating variant) clears valid+dirty on matches. `scope`
  * selects Line(01) / Page(10) / All(11) — 00 is unused (decode never emits it).
  * `sel` selects which cache(s): DC(01) / IC(10) / BC(11). `addr` is An's value,
  * meaningful for Line/Page scope only. */
case class CacheMaintCmd() extends Bundle {
  val push       = Bool()
  val invalidate = Bool()
  val scope      = UInt(2 bits)
  val sel        = UInt(2 bits)
  val addr       = UInt(32 bits)
}

/** D-cache service contract (spec 4.2). */
trait DcacheService {
  def loadProbe: spinal.lib.Stream[DLoadProbe]       // virtual-set read, before translation
  def loadProbeResolve: spinal.lib.Flow[DLoadProbeResolve] // matching registered PA/tag
  def loadProbeCancel: spinal.lib.Flow[DLoadProbeCancel]
  def loadCmd:  spinal.lib.Stream[DLoadCmd]   // resolved VA+PA; virtual index, physical tag
  def loadRsp:  spinal.lib.Flow[DLoadRsp]     // fixed offset for a hit; valid late on a miss-refill
  def loadBusy: Bool                          // high while a refill is in flight (back-pressures loads)
  def store:    spinal.lib.Stream[DStoreCmd]  // elastic ordered drain; payload stable until fire
  def storeAck: Bool                          // ordered 1-cycle terminal pulse: local hit, allocation, or AXI B
  // 1-cycle pulse, same cycle class as storeAck: the AXI B response for the
  // just-drained store carried a non-OKAY resp (SLVERR/DECERR). storeErr is an
  // additional QUALIFIER a consumer checks alongside storeAck, never a
  // replacement. NOTE (post-P4.4 fix, DcachePlugin.scala's `storeBAck`):
  // storeAck no longer pulses on ANY B handshake -- it is demultiplexed by AXI
  // `id` and only pulses on (a) the store's own write-through completion
  // (id === 1), or the two purely-local ack paths that never touch the AXI B
  // channel at all: (b) `cbHitAckReg` for a COPYBACK hit, (c)
  // `storeAllocAckReg` for a drain-miss write-allocate. It never pulses on an
  // eviction writeback's own B response (id === 2) -- that is diagnostic-only.
  def storeErr: Bool
  // Sticky (once set, stays set until reset): a trusted-cacheable-path AXI
  // transaction issued on the CORE's own behalf (WT-beat/INHIBITED-drain,
  // drain-miss write-allocate refill, or a dirty-victim eviction writeback)
  // came back with a non-OKAY response. First-error-wins; see
  // `DcachePlugin.logic.diagFaultValid`'s doc comment for the per-site kinds.
  def diagFault: Bool

  // ── Cache maintenance (CPUSH / CINV), Task P5.4 ────────────────────────────
  /** Start a maintenance walk. A 1-cycle Flow pulse; the walk latches the payload.
    * The ExceptionUnit's commit-time sysOp path is the SOLE driver, and it may
    * ONLY pulse this once `maintQuiesced` is true (see that method's contract). */
  def maintCmd: spinal.lib.Flow[CacheMaintCmd]
  /** 1-cycle pulse: the walk started by `maintCmd` has fully completed. */
  def maintDone: Bool
  /** 1-cycle pulse, coincident with or before `maintDone`: at least one dirty-line
    * writeback in the current walk completed with a non-OKAY AXI response. */
  def maintError: Bool
  /** REQUIRED PRECONDITION for `maintCmd`, and the reason it exists: the whole
    * D-cache datapath (load FSM, refill/eviction engine, the store S0..S3 pipe and
    * BOTH sets of AXI write completion flags) is genuinely idle RIGHT NOW, so the
    * maintenance walk can take the shared array read port and the AXI write channels
    * without racing an in-flight transaction.
    *
    * This is NOT implied by `excActive`. `excActive` only stops the LS EU from
    * issuing anything NEW; an older COMMITTED store still draining out of the
    * StoreQueue, or a load-refill/dirty-victim eviction accepted before the flush
    * landed, can still be mid-transaction for many cycles afterwards. The consumer
    * must therefore wait on this (together with the SQ-drained signal) before
    * pulsing `maintCmd` — see ExceptionUnit's `S_DRAIN` state. */
  def maintQuiesced: Bool
}

/** Big-endian byte-lane helpers shared by load extraction and store merge.
  *
  * m68k is big-endian: the byte at address A is the MOST significant byte of a
  * naturally-aligned multi-byte access. Within a 16-byte (128-bit) line, the
  * byte at line offset `o` (o in 0..15) lives in line bits [ (15-o)*8 +: 8 ] is
  * NOT how AXI memory is laid out — AXI/memory is byte-addressed little-endian in
  * the beat (byte o = bits [o*8 +: 8]). The cache stores the line exactly as the
  * AXI beat delivered it (byte o = line[o*8 +: 8]); big-endian semantics live only
  * in how a multi-byte LOAD assembles those bytes into the 32-bit result. */
object DcacheByteLane {
  /** Extract a size-typed value from a 128-bit line at byte offset `off` (0..15),
    * assembling bytes big-endian: result MSB = byte at `off`. Result is right-
    * justified in 32 bits (zero-extended above the access size). */
  def extract(line: Bits, off: UInt, size: Size.C): Bits = {
    // Per-byte view of the line (byte i = line[i*8 +: 8]).
    val bytes = line.subdivideIn(8 bits)   // bytes(0) = line[7:0] = mem byte at offset 0
    val b0 = bytes(off)
    val b1 = bytes(off + 1)
    val b2 = bytes(off + 2)
    val b3 = bytes(off + 3)
    val result = Bits(32 bits)
    result := B(0, 32 bits)
    switch(size) {
      is(Size.BYTE) { result := B(0, 24 bits) ## b0 }
      is(Size.WORD) { result := B(0, 16 bits) ## b0 ## b1 }
      is(Size.LONG) { result := b0 ## b1 ## b2 ## b3 }
    }
    result
  }

  /** Compute the 128-bit merge data + 16-bit byte strobe for a store of `size`
    * bytes at byte offset `off` (0..15). Store data is right-justified in 32 bits
    * (low bytes carry the value, big-endian: data[7:0] is the LEAST significant
    * byte = the highest address). */
  def storeData(off: UInt, size: Size.C, data: Bits): Bits = {
    val out   = Vec(Bits(8 bits), 16)
    for (i <- 0 until 16) out(i) := B(0, 8 bits)
    // big-endian: byte at offset off = MSB of the value
    switch(size) {
      is(Size.BYTE) { out(off) := data(7 downto 0) }
      is(Size.WORD) { out(off) := data(15 downto 8); out(off + 1) := data(7 downto 0) }
      is(Size.LONG) {
        out(off) := data(31 downto 24); out(off + 1) := data(23 downto 16)
        out(off + 2) := data(15 downto 8); out(off + 3) := data(7 downto 0)
      }
    }
    out.asBits
  }

  /** 16-bit byte strobe for a store of `size` bytes at byte offset `off`. */
  def storeStrb(off: UInt, size: Size.C): Bits = {
    val strb = Bits(16 bits)
    val nbytes = UInt(3 bits)
    nbytes := 1
    switch(size) {
      is(Size.BYTE) { nbytes := 1 }
      is(Size.WORD) { nbytes := 2 }
      is(Size.LONG) { nbytes := 4 }
    }
    val bits = Vec(Bool(), 16)
    // `off +^ nbytes` (WIDENING add): a WORD/LONG at a high offset (e.g. off=14,
    // nbytes=2 -> 16) would overflow a 4-bit `off + nbytes` to 0, zeroing the strobe
    // (NO bytes stored). A non-crossing access ending exactly at the line boundary
    // (off+nbytes==16) is valid -> compare with the widened sum.
    val end  = off +^ nbytes        // 5-bit (no overflow at off=14, WORD -> 16)
    val offW = off.resize(5 bits)
    for (i <- 0 until 16) bits(i) := (U(i, 5 bits) >= offW) && (U(i, 5 bits) < end)
    strb := bits.asBits
    strb
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Cross-boundary (split) helpers (misaligned access spanning two 16-byte lines).
  //
  // Big-endian byte k of the access (k=0 is the MSB, living at byte offset `off`)
  // occupies ABSOLUTE byte position `off + k`. If `off + k <= 15` it lives in line
  // A at index `off+k`; otherwise in line B at index `off+k-16`. A single m68k
  // access is at most 4 bytes, so it spans at most two adjacent lines.
  // ───────────────────────────────────────────────────────────────────────────

  private def nBytesOf(size: Size.C): UInt = {
    val n = UInt(3 bits); n := 1
    switch(size) {
      is(Size.BYTE) { n := 1 }
      is(Size.WORD) { n := 2 }
      is(Size.LONG) { n := 4 }
    }
    n
  }

  /** Load merge: gather `size` big-endian bytes spanning the A/B line boundary at
    * byte offset `off`. Byte k of the value (k=0 = MSB at `off`) is taken from
    * line A at `off+k` (if `off+k<16`) else line B at `off+k-16`. Right-justified
    * in 32 bits. Aligned (no cross) reduces to `extract(lineA, ...)`. */
  def extractCross(lineA: Bits, lineB: Bits, off: UInt, size: Size.C): Bits = {
    val aBytes = lineA.subdivideIn(8 bits)   // aBytes(i) = lineA[i*8 +: 8]
    val bBytes = lineB.subdivideIn(8 bits)
    // selByte(k) = the absolute-position byte at (off + k).
    def selByte(k: Int): Bits = {
      val pos = off +^ U(k, 5 bits)          // 0..18 (off<=15, k<=3)
      Mux(pos < U(16), aBytes(pos.resize(4 bits)), bBytes((pos - U(16)).resize(4 bits)))
    }
    val b0 = selByte(0); val b1 = selByte(1); val b2 = selByte(2); val b3 = selByte(3)
    val result = Bits(32 bits)
    result := B(0, 32 bits)
    switch(size) {
      is(Size.BYTE) { result := B(0, 24 bits) ## b0 }
      is(Size.WORD) { result := B(0, 16 bits) ## b0 ## b1 }
      is(Size.LONG) { result := b0 ## b1 ## b2 ## b3 }
    }
    result
  }

  // Per-byte value of the access at big-endian byte index k (k=0 = MSB).
  private def valueByte(size: Size.C, data: Bits, k: Int): Bits = {
    val out = Bits(8 bits); out := B(0, 8 bits)
    switch(size) {
      is(Size.BYTE) { if (k == 0) out := data(7 downto 0) }
      is(Size.WORD) {
        if (k == 0) out := data(15 downto 8)
        if (k == 1) out := data(7 downto 0)
      }
      is(Size.LONG) {
        if (k == 0) out := data(31 downto 24)
        if (k == 1) out := data(23 downto 16)
        if (k == 2) out := data(15 downto 8)
        if (k == 3) out := data(7 downto 0)
      }
    }
    out
  }

  // True iff big-endian access byte k is in-range for this size (k < nBytes).
  private def kActive(size: Size.C, k: Int): Bool = (U(k) < nBytesOf(size))

  /** Store split — slot A (low line): the 128-bit merge data for bytes whose
    * absolute position `off+k` stays within line A (< 16). */
  def storeDataA(off: UInt, size: Size.C, data: Bits): Bits = {
    val out = Vec(Bits(8 bits), 16)
    for (i <- 0 until 16) out(i) := B(0, 8 bits)
    for (k <- 0 until 4) {
      val pos = off +^ U(k, 5 bits)
      when(kActive(size, k) && (pos < U(16))) { out(pos.resize(4 bits)) := valueByte(size, data, k) }
    }
    out.asBits
  }

  /** Store split — slot A byte strobe. */
  def storeStrbA(off: UInt, size: Size.C): Bits = {
    val bits = Vec(Bool(), 16)
    for (i <- 0 until 16) bits(i) := False
    for (k <- 0 until 4) {
      val pos = off +^ U(k, 5 bits)
      when(kActive(size, k) && (pos < U(16))) { bits(pos.resize(4 bits)) := True }
    }
    bits.asBits
  }

  /** Store split — slot B (high line): bytes whose absolute position `off+k`
    * spilled past line A (>= 16), placed at `off+k-16` in line B. */
  def storeDataB(off: UInt, size: Size.C, data: Bits): Bits = {
    val out = Vec(Bits(8 bits), 16)
    for (i <- 0 until 16) out(i) := B(0, 8 bits)
    for (k <- 0 until 4) {
      val pos = off +^ U(k, 5 bits)
      when(kActive(size, k) && (pos >= U(16))) { out((pos - U(16)).resize(4 bits)) := valueByte(size, data, k) }
    }
    out.asBits
  }

  /** Store split — slot B byte strobe. Zero when the access does not cross. */
  def storeStrbB(off: UInt, size: Size.C): Bits = {
    val bits = Vec(Bool(), 16)
    for (i <- 0 until 16) bits(i) := False
    for (k <- 0 until 4) {
      val pos = off +^ U(k, 5 bits)
      when(kActive(size, k) && (pos >= U(16))) { bits((pos - U(16)).resize(4 bits)) := True }
    }
    bits.asBits
  }
}
