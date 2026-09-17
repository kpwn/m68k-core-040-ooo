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
  /** Bits the token reserves for the robId. FIXED AT 6 and deliberately INDEPENDENT of
    * Global.ROB_ID_W: the token is a cross-module encoding (bit7 = special, bit6 =
    * bDone, bits5:0 = robId, with 0x80/0x81/0x82 reserved), shared with DcachePlugin's
    * early-probe CAM. A smaller ROB pads into this field rather than reshaping the
    * token -- the layout, and every reserved value, stay bit-identical. */
  val RobIdBits = 6
  /** Commit-side exception sequencer (`LsEuPlugin`'s exception override mux). */
  val EXC       = 0x80
  /** ITLB table-walk descriptor read. */
  val WALK_ITLB = 0x81
  /** DTLB table-walk descriptor read. */
  val WALK_DTLB = 0x82
}

/** Early VIPT lookup request. `vaddr` selects the page-invariant set in parallel
  * with the DTLB lookup and `token` associates the result with the later command.
  * Normally the probe launches with `resolved=False`; the following tokenized
  * DLoadProbeResolve qualifies the same synchronous array read when the registered
  * DTLB response arrives. If that qualification is late, the entry is deliberately
  * unusable and the later command falls back to the ordinary resolved read path.
  *
  * ── `resolved` / `paddrHint`: A LOADED GUN. READ THIS BEFORE SETTING EITHER. ────
  *
  * Indexing the array early from `vaddr` is CORRECT: the set index lives below the
  * page offset, so it is identical in the virtual and the physical address. The TAG
  * IS NOT. `paddrHint` is compared against physical tags (`DcachePlugin`'s
  * `probeReadTag`), so it MUST be a genuinely TRANSLATED address -- and at the
  * moment a probe launches, no translation exists yet. That is the entire reason
  * the early probe exists.
  *
  * Until 2026-09-09 this field was named `paddr` and its one IN-CORE producer
  * assigned it `tCtx.vaddr` -- a VIRTUAL address in a field the cache tags with.
  * Inert, because that producer also hard-wires `resolved` False and it gates every
  * consumer (and note the compile-time `earlyViptEnabled` gate ANDs with `resolved`,
  * so flipping THAT alone is inert too -- both halves have to change). But a trap:
  * setting `resolved := True` would have silently turned virtual addresses into
  * physical tag comparisons. Harmless under an identity map; a silent FALSE-HIT
  * generator under any real one, which is the exact failure class this cache spent
  * months chasing. The field is now named for what it is, and the in-core producer
  * supplies NO hint (0) rather than a plausible-looking wrong one, so a future
  * `resolved := True` there fails loudly instead of quietly.
  *
  * `resolved=1` IS a supported, exercised path -- `DcacheSpec`'s VIPT slice-B tests
  * drive it directly, with an identity paddr -- so it is not dead code to be deleted.
  * It is guarded instead: `DcachePlugin` asserts in simulation that an early tag,
  * once a resolve for the same token arrives, equals the genuinely translated one.
  *
  * THE SAFE EARLY-HIT PATH ALREADY EXISTS and is the one that actually runs:
  * `DcachePlugin`'s `probeResolveTagIn` / `probeUsableIfResolved` /
  * `probeWayMatchResolved`, which tag against the DLoadProbeResolve payload -- a
  * genuinely translated physical address. Anyone wanting hit determination one cycle
  * earlier must extend THAT path, not revive this one, and must supply a real
  * translation here. `DcachePlugin`'s allocation-site assertion says so too.
  */
case class DLoadProbe() extends Bundle {
  val vaddr     = UInt(32 bits)
  val token     = UInt(DLoadToken.Width bits)
  /** Set ONLY if `paddrHint` carries a genuinely TRANSLATED physical address. */
  val resolved  = Bool()
  /** Meaningless unless `resolved`. MUST be physical -- see the class comment. */
  val paddrHint = UInt(32 bits)
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
  /** THE INVARIANT: this requester consumes NO byte lane at or beyond the end of the
    * 16-byte line. It is the exemption from the `DcacheByteLane.extract` line-wrap
    * tripwire, and it is the requester's promise that a wrapped lane, if one is
    * produced, is discarded rather than used.
    *
    * Two producers legitimately satisfy that promise, and BOTH must, because the bit
    * disables a guard against silent wrong data:
    *
    *  1. The LS EU's cross-line split pair. Slot A is deliberately presented at the
    *     ORIGINAL, line-CROSSING offset/size because it needs that LINE, not that
    *     value; it consumes `DLoadRsp.line` and ignores `DLoadRsp.data` entirely, and
    *     the pair is merged later by `DcacheByteLane.extractCross`.
    *  2. A DIRECTED TEST that drives `loadCmd` straight, bypassing the AGU splitter,
    *     to prove the cache's own containment -- `MmioLoadSizingSpec`'s D25/D30. Those
    *     assert which AXI sub-transactions are emitted for a straddling INHIBITED
    *     access; D25 reads no data at all, and D30 CLAMPS its comparison to the bytes
    *     inside the line (`got >> 8*(n - clampedN)` shifts every wrapped lane out). So
    *     neither consumes a wrapped lane. They set it ONLY for the shapes that
    *     actually straddle (`off + n > 16`); the non-straddling shapes in the same
    *     loop leave it False so the tripwire still guards them.
    *
    * Everything else leaves it False. Setting it where a wrapped lane IS consumed
    * silently reintroduces exactly the bug the tripwire exists to catch.
    *
    * NOT a licence to drive straddling commands from the core. The LS EU's
    * `s1CrossLine` predicate is address/size only and is NOT conditioned on cache
    * mode, so every line-crossing data access -- INHIBITED device reads included --
    * is split into two commands before `DcachePlugin` sees either half.
    *
    * It exists to make the `DcacheByteLane.extract` line-wrap tripwire precise: that
    * assertion (see `extract` below) fires on a multi-byte extract whose bytes run
    * past the end of the 16-byte line, which is exactly the silent-wrong-data shape
    * that bit the RTE frame pops and the FRESTORE header read. Slot A of a split pair
    * is the ONE legitimate producer of that shape, and this bit is how the cache
    * knows to exempt it -- rather than the alternative of weakening the assertion so
    * it no longer catches a real unguarded consumer. Purely a verification contract:
    * no synthesised logic reads it. */
  val lineOnly  = Bool()
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
  def extract(line: Bits, off: UInt, size: Size.C, dataUsed: Bool = null): Bits = {
    // Per-byte view of the line (byte i = line[i*8 +: 8]).
    val bytes = line.subdivideIn(8 bits)   // bytes(0) = line[7:0] = mem byte at offset 0
    // ── LINE-WRAP TRIPWIRE (2026-09-09) ─────────────────────────────────────────
    // `off` is 4 bits, so `off + 1/2/3` WRAP inside the line: a WORD at offset 15
    // returns {byte[15], byte[0]} of the SAME line, and a LONG at offset 13/14/15
    // returns 1/2/3 bytes of the line's own head, instead of crossing into the next
    // line. That is SILENT WRONG DATA, and it has already cost two real defects --
    // the RTE exception-frame word pops and the FRESTORE header word, both of which
    // reach `dcache.loadCmd` directly and therefore bypass the LS EU's AGU cross-line
    // splitter (LsEuPlugin's `crossesLine`/`twoAccess`). The synthesised behaviour is
    // deliberately UNCHANGED (the wrap is still what the hardware computes); this is a
    // simulation-only assertion so that any future consumer which presents a crossing
    // multi-byte access AND consumes `DLoadRsp.data` fails loudly instead of quietly.
    //
    // `dataUsed` is the caller's "this extraction's value is actually consumed"
    // predicate. It must EXCLUDE the one legitimate wrapping producer: slot A of an
    // LS EU cross-line split pair, which is presented at the original crossing
    // offset/size purely to obtain `DLoadRsp.line` (see `DLoadCmd.lineOnly`). A `null`
    // default means "always consumed" -- correct for the callers whose enclosing
    // `when` already carries the validity, and for the constant-offset callers.
    GenerationFlags.simulation {
      val used   = if (dataUsed == null) True else dataUsed
      val nbytes = UInt(3 bits); nbytes := 1
      switch(size) {
        is(Size.BYTE) { nbytes := 1 }
        is(Size.WORD) { nbytes := 2 }
        is(Size.LONG) { nbytes := 4 }
      }
      // Widening add: off=15 + 4 must not itself wrap the comparison.
      assert(!used || ((off +^ nbytes) <= U(16, 5 bits)),
        "DcacheByteLane.extract: multi-byte access WRAPS past the end of the 16-byte " +
        "line (off + size > 16). The consumer must split the access -- a direct " +
        "dcache.loadCmd requester does NOT get the LS EU's AGU cross-line split. " +
        "See DLoadCmd.lineOnly.",
        FAILURE)
    }
    // ── THE WRAP IS NOW CLOSED IN HARDWARE TOO (2026-09-17) ────────────────────
    // The tripwire above is simulation-only, so on silicon a crossing access still
    // returned the line's OWN HEAD BYTES -- data that is plausible, aliased to real
    // memory, and therefore silently wrong.  That is exactly the shape of the
    // wrong-PC-on-RTE defect: a longword assembled half from the frame and half
    // from the top of the same line still looks like an address.
    //
    // The lanes below replace those head bytes with a CONSTANT ZERO, and they do it
    // for FREE -- no extra logic level, in fact slightly LESS logic than before.
    //
    // WHY IT IS FREE.  `bytes(off + k)` is a 16:1 mux whose select is `off + k` mod
    // 16.  Which mux INPUTS can a wrap reach?  For lane k, index j is produced by
    // off = j - k mod 16, and that is a wrap exactly when j < k:
    //
    //     lane 1 (off+1): index 0 <- off=15                    -> wrap-only
    //     lane 2 (off+2): index 0 <- off=14, index 1 <- off=15 -> wrap-only
    //     lane 3 (off+3): indices 0,1,2 <- off=13,14,15        -> wrap-only
    //
    // So "did this lane wrap" is a property of the SELECTED INDEX, not a separate
    // predicate that would have to be ANDed onto the mux output.  Tying those
    // inputs to a literal zero is therefore exact -- every legal (off, size) pair
    // reads an index >= k and is bit-identical to before -- and it costs a mux
    // input becoming a constant, which the synthesiser folds away.
    //
    // The assertion is KEPT: zero is deterministic, not correct.  A requester that
    // needs the bytes past the line end must still split the access; this only
    // guarantees that if one does not, it cannot be handed a convincing lie.
    def lane(k: Int): Bits =
      Vec((0 until 16).map(j => if (j < k) B(0, 8 bits) else bytes(j)))(off + k)
    val b0 = bytes(off)
    val b1 = lane(1)
    val b2 = lane(2)
    val b3 = lane(3)
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
