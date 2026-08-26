package m68k040.ls

import m68k040.cache.{CacheMode, DStoreCmd}
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import spinal.lib._

case class SqAlloc() extends Bundle {
  val robId = UInt(6 bits)
  // ---- slot A (always present) ----
  val paddr = UInt(32 bits)
  val vaddr = UInt(32 bits)   // logical address of slot A -- the SSW EA field for a
                               // precise-path fault must be the LOGICAL address (the
                               // SQ only stored paddr before this task)
  val data  = Bits(32 bits)
  val size  = Size()
  val nbytesA   = UInt(3 bits)        // covered byte count of slot A (for overlap)
  // drain slot A with explicit strobe (split) vs {data,size}. strb/lineData themselves
  // are NOT carried here (task #252): they are pure functions of (paddr low nibble,
  // size, data) -- exactly DcacheByteLane.storeStrbA/storeDataA/storeStrbB/storeDataB,
  // the same math LsEuPlugin used to run once at alloc and stash 288 redundant bits/entry
  // for. StoreQueue now re-derives them combinationally at the single drain read point.
  val useStrbA  = Bool()
  // ---- slot B (optional second slot of a SPLIT store; one entry = one atomic store) ----
  val validB    = Bool()
  val paddrB    = UInt(32 bits)
  val vaddrB    = UInt(32 bits)   // logical address of slot B (cross-line/page)
  val nbytesB   = UInt(3 bits)
  // ---- cache-mode / precision classification (captured at translate) ----
  val cacheMode  = CacheMode()
  val cacheModeB = CacheMode()
  val supervisor = Bool()
  val precise    = Bool()   // !fast -- withhold ROB completion; drain at head, awaited
}

case class SqFwdQuery() extends Bundle {
  val robId = UInt(6 bits)
  val paddr = UInt(32 bits)
  val size  = Size()
  // Cache-inhibited accesses are serialized device operations.  They order
  // against every older store, even when the addresses do not overlap (device
  // command/data and register ports commonly occupy different addresses).
  val inhibited = Bool()
}

case class SqFwdRsp() extends Bundle {
  val hit   = Bool()          // full-overlap forward
  val data  = Bits(32 bits)
  val stall = Bool()          // partial/ambiguous overlap with an older store
}

/** Speculative store queue: a small ring (depth parametric, default 8).
  *
  *  - alloc  : a store executes -> push at tail (speculative, uncommitted).
  *  - commit : ROB retired this robId -> mark the matching entry committed.
  *  - drain  : committed halves stream in program order into the L1D. COPYBACK
  *             hits may be accepted ahead; an entry pops only on its final ack.
  *  - flush  : mispredict -> roll the tail back to the youngest COMMITTED entry
  *             (speculative entries are squashed and never drain -> memory
  *             untouched). Same pointer discipline as the ROB/freelist.
  *  - fwd    : load forward check. Among entries OLDER than query.robId (ROB
  *             circular order), address-overlapping: full same-size overlap ->
  *             hit+data (youngest such); partial/ambiguous -> stall.
  *
  * All state is RegInit (no uninit Regs); counts are hardware sums (no
  * when-gated Scala-var counters). */
class StoreQueue(depth: Int = 8) extends Component {
  require(isPow2(depth))
  val ptrW = log2Up(depth)

  val io = new Bundle {
    val alloc    = slave(Flow(SqAlloc()))
    val fwd      = new Bundle { val query = in(SqFwdQuery()); val rsp = out(SqFwdRsp()) }
    val commit   = slave(Flow(UInt(6 bits)))
    // Second same-cycle commit port: a store can retire in EITHER slot of the 2-wide
    // retire (slot 1 when it completed early behind a long-latency head, e.g. DIV).
    // Marking a cycle LATE is unsafe — a flush arriving the next cycle would squash
    // the already-retired store — so both retire slots must mark in the retire cycle.
    val commitB  = slave(Flow(UInt(6 bits)))
    val flush    = in(Bool())
    val drain    = master(Stream(DStoreCmd()))
    // Terminal acknowledgement for the oldest accepted half. `drain` is a real
    // Stream: valid/payload remain stable until fire, then the entry remains resident
    // (and forwarding-visible) until the matching in-order ack. COPYBACK-hit halves
    // may be accepted ahead; variable-latency/precise halves remain barriers.
    val drainAck = in(Bool())
    // Error qualifier sampled with drainAck. It is meaningful for the serialized
    // precise/AXI path; COPYBACK hit/allocation acks are clean local terminals.
    val drainErr = in(Bool())
    val empty    = out(Bool())   // no valid entry AND no drain in flight
    // ---- barrier query: age-qualified residency for the LS-EU's launch gate ----
    // Driven from the LS-EU's P4 context, INDEPENDENTLY of the forwarding query
    // (which is muxed between P3 and P4), so the gate is always answered for the
    // load it is actually gating.
    val barrier = new Bundle {
      val robId               = in(UInt(6 bits))
      val olderStore          = out(Bool())   // ANY older resident store
      val olderInhibitedStore = out(Bool())   // an older resident DEVICE store
    }
    // Back-pressure to the LS-EU: high when the ring is FULL (all `depth` entries
    // resident). The LS-EU reads this ONLY in its execute/alloc FSM (a `WAIT_SQ`
    // stall, off the IQ issue-select cone) and holds a store's alloc until an entry
    // drains. The alloc Flow has no `ready`, so the EU is responsible for never
    // allocating while full; the sim assert below enforces that contract.
    val full     = out(Bool())
    // ---- precise-path at-head drain (Task P2) ----
    val robHeadIn           = in(UInt(6 bits))   // = rob.logic.h0
    val robHeadValidIn      = in(Bool())         // = rob.logic.count > 0
    val irqPreemptPendingIn = in(Bool())         // = rob.logic.interruptPending || rob.logic.tracePendingFire
    val sqCompletion        = master(Flow(UInt(6 bits)))
    val sqFaultCompletion   = master(Flow(m68k040.execute.LsFault()))
    val preciseDrainBusy    = out(Bool())
  }

  // ---- ring storage (all RegInit) ----
  val valids    = Vec.fill(depth)(RegInit(False))
  val committed = Vec.fill(depth)(RegInit(False))
  val robIds    = Vec.fill(depth)(RegInit(U(0, 6 bits)))
  val paddrs    = Vec.fill(depth)(RegInit(U(0, 32 bits)))
  val datas     = Vec.fill(depth)(RegInit(B(0, 32 bits)))
  val sizes     = Vec.fill(depth)(RegInit(Size.BYTE()))
  // slot-A explicit-strobe drain (split stores) + covered byte count (overlap)
  val nbytesAs  = Vec.fill(depth)(RegInit(U(1, 3 bits)))
  val useStrbAs = Vec.fill(depth)(RegInit(False))
  // ── SQ drain-payload elimination (task #252, 2026-08-19) ──
  // The prior LUT-reduction pass (2026-08-15) folded strbA/lineDataA/strbB/lineDataB
  // (288 bits/entry) into a single 1W/1R async-read `Mem` (`SqDrainRow`/`drainRowMem`),
  // reasoning they were drain-only fields. That Mem itself turned out to be entirely
  // redundant, not merely foldable: every one of those bits is re-derivable
  // combinationally at drain time from data the SQ already stores for unrelated
  // reasons -- `paddrs(i)`, `sizes(i)`, `datas(i)` are exactly the (off, size, data)
  // inputs to `DcacheByteLane.storeStrbA/storeDataA/storeStrbB/storeDataB`
  // (cache/DcacheTypes.scala), the SAME pure functions LsEuPlugin used to call once at
  // alloc time to PRODUCE strbA/lineDataA/strbB/lineDataB in the first place. A store
  // is at most 4 bytes (BYTE/WORD/LONG) and a split only occurs at line-offset 13-15
  // (`s1CrossLine`, LsEuPlugin.scala), so slot A/B each carry at most 3 real bytes --
  // never near the 128-bit width the old Mem row stored. The Mem/case-class and the
  // alloc-time compute+store in LsEuPlugin are gone; the drain-side read below now
  // calls DcacheByteLane directly, fed by `paddrs(sendPtr)(3 downto 0)` (the SAME
  // line-offset LsEuPlugin's `stOff` used -- slot B's own paddr is always line-aligned
  // at offset 0 for a split, so slot B's bytes are derived from slot A's offset too,
  // exactly as before), `sizes(sendPtr)`, `datas(sendPtr)`. Same stability: sendPtr and
  // the source registers are exactly as stable as the old Mem's own read address was.
  // FMax (P1): PRE-REGISTERED slot-A upper byte-range bound, computed at ALLOC as
  // paddr + nbytesA. The forward overlap test then compares the load range against
  // this stored bound (a COMPARE) instead of recomputing paddr+nbytes in the
  // forward cone (which placed a per-entry CARRY8 adder in the binding
  // s2Paddr->fwdData post-route path). Stored at 32 bits — EXACTLY the width of the
  // old combinational `paddrs(i) + nbytesAs(i)` (SpinalHDL `+` yields a 32-bit
  // result, carry-out dropped), so the (qLo < aHi) compare is bit-identical to
  // before. paddrLo is just paddrs(i) (no adder needed).
  val paddrHiAs = Vec.fill(depth)(RegInit(U(0, 32 bits)))
  // optional slot B (second half of a split store)
  val validBs   = Vec.fill(depth)(RegInit(False))
  val paddrBs   = Vec.fill(depth)(RegInit(U(0, 32 bits)))
  val nbytesBs  = Vec.fill(depth)(RegInit(U(0, 3 bits)))
  // (strbB/lineDataB are DERIVED at drain time -- see the drain-present mux below;
  // no per-entry storage needed at all now, task #252.)
  // PRE-REGISTERED slot-B upper byte-range bound (= paddrB + nbytesB), same rationale
  // and same 32-bit width (bit-identical to the old `paddrBs(i) + nbytesBs(i)`).
  val paddrHiBs = Vec.fill(depth)(RegInit(U(0, 32 bits)))
  // Presentation and acknowledgement are independent ordered cursors. A split store
  // contributes two accepted halves but exactly one architectural entry/pop.
  val sendPtr    = RegInit(U(0, ptrW bits))
  val sendPhaseB = RegInit(False)
  val ackPhaseB  = RegInit(False)
  // Compatibility/debug alias retained for existing hang traces.
  val drainPhaseB = ackPhaseB
  val acceptedW = log2Up(depth * 2 + 1)
  val acceptedHalves = RegInit(U(0, acceptedW bits))

  // ---- precise-path fields (P2): logical addresses (SSW EA needs LOGICAL, not
  // physical), per-entry cache mode (drives the real write-through/copyback/
  // inhibited drain policy), the supervisor bit (fault frame's FC/SSW), and the
  // `precise` classification (withholds ROB completion until a real bus response
  // for this entry, at head, is observed). All inert until later P2 tasks wire
  // consumers -- populated at alloc below, same indexing convention as the
  // existing per-entry Vecs. ----
  val vaddrAs    = Vec.fill(depth)(RegInit(U(0, 32 bits)))
  val vaddrBs    = Vec.fill(depth)(RegInit(U(0, 32 bits)))
  val cacheModes = Vec.fill(depth)(RegInit(CacheMode.WRITETHROUGH))
  val cacheModesB= Vec.fill(depth)(RegInit(CacheMode.WRITETHROUGH))
  val supervisors= Vec.fill(depth)(RegInit(False))
  val precises   = Vec.fill(depth)(RegInit(False))

  val head = RegInit(U(0, ptrW bits))   // oldest
  val tail = RegInit(U(0, ptrW bits))   // next free
  val count = RegInit(U(0, log2Up(depth + 1) bits))

  // ---- access-size in bytes ----
  def sizeBytes(s: Size.C): UInt = {
    val n = UInt(3 bits); n := 1
    switch(s) { is(Size.BYTE) { n := 1 }; is(Size.WORD) { n := 2 }; is(Size.LONG) { n := 4 } }
    n
  }

  // ---- SSW-encoded access size (00=byte,01=word,10=long), mirrors LsEuPlugin's
  // captureFault -- feeds sqFaultCompletion.payload.sizeBits below. ----
  def sizeBitsOf(s: Size.C): UInt = {
    val n = UInt(2 bits); n := 2   // default LONG-encoding, mirrors LsEuPlugin's captureFault
    switch(s) {
      is(Size.BYTE) { n := 0 }
      is(Size.WORD) { n := 1 }
      is(Size.LONG) { n := 2 }
    }
    n
  }

  // ---- ROB circular age compare: is robId `a` strictly OLDER than `b`? ----
  // Bug (movem_rom_mem_forms ported-test triage, task movem-rom-mem-forms):
  // the ORIGINAL formula tested `(b - a) mod 64` against a fixed `< 32` threshold
  // -- i.e. it assumed no two SIMULTANEOUSLY-relevant robIds are ever more than
  // half the ROB's 64-entry space apart. That assumption is false: the ROB
  // (RobPlugin.scala, `depth = 64`) can hold up to 63 live entries at once, and a
  // PRECISE store (MMU off/inhibited -- exactly the ported-test bare-metal
  // posture) withholds its own ROB completion until it personally drains AT
  // HEAD (see `headPreciseReady` below), which stalls the whole ROB head behind
  // it. So a still-undrained precise store near the ROB head and a much younger
  // load near the tail can legitimately be MORE than 31 robId slots apart
  // whenever 30+ cheap non-memory instructions dispatch behind a store that is
  // still waiting its turn to drain. Past that threshold the old compare
  // silently flipped to "not older", so `ent` went False for a store that WAS
  // still resident and WAS still older -- the load then fell through to the
  // cache/memory (which the store had NOT yet reached) and returned
  // stale/uninitialized data (0xFFFFFFFF in the repro) instead of forwarding.
  // Reproduced directly: movem_rom_mem_forms's MOVEM.L D0-D5,24(A2) followed by
  // enough later instructions (any mix, not MOVEM-specific -- plain `nop`s
  // reproduce it identically) pushes the readback load's robId >=32 slots past
  // the still-undrained store's robId.
  //
  // Fix, two cases:
  //  1. `committed(a)` (the ROB has ALREADY retired this store) -> UNCONDITIONALLY
  //     older than any live query. Retirement is strictly in-order, so anything
  //     already retired precedes anything not yet retired -- no robId arithmetic
  //     needed, and this is exactly the case a "fast" (non-precise, cacheable
  //     MMU-on) store hits: it retires (ROB head passes it) before it drains,
  //     which is also the ONE case where `io.robHeadIn` can run ahead of a still-
  //     resident SQ entry (ruling out anchoring to it unconditionally).
  //  2. `!committed(a)`: `a` has NOT yet retired, so by definition it still sits
  //     at-or-after the current ROB head, and `b` (a live query's robId) is
  //     ALSO always at-or-after head (an in-flight instruction can't query
  //     before it's dispatched into a still-live ROB slot). Both operands are
  //     therefore within the SAME unwrapped [head, head+64) window, so a
  //     head-anchored distance compare is exact -- no half-window ceiling.
  // (`committed(a)` is checked at the call site below, not inside this helper,
  // so `olderThan` here covers ONLY case 2's head-anchored math.)
  def olderThan(a: UInt, b: UInt): Bool = {
    val ageA = (a - io.robHeadIn)(5 downto 0)
    val ageB = (b - io.robHeadIn)(5 downto 0)
    ageA < ageB
  }

  // ---- elastic ordered drain producer ---------------------------------------
  // `drainBusy` remains as the historical debug name, but is now derived from the
  // accepted-half count rather than being a one-transaction lock.
  val drainBusy = acceptedHalves =/= 0
  // Precise-path at-head drain: an UNCOMMITTED precise entry drains once its robId
  // reaches the (non-speculative) ROB head -- i.e. every program-older instruction
  // has already retired -- gated off a preempt-pending input (an interrupt/trace
  // about to fire must not race a drain launch; see preciseDrainBusy below for the
  // in-flight-drain side of that same gate). Deadlock-freedom (design doc §4.1): the
  // SQ ring order equals program order (IssueQueuePlugin.scala:316-337 enforces
  // oldest-occupied-LS-only issue), so a precise store's robId reaches the ROB head
  // only once every program-older store has already allocated and drained -- i.e.
  // `robIds(head) === io.robHeadIn` can only become true once this entry genuinely
  // IS the SQ ring head too. Nothing here needs an explicit reordering check.
  val headPreciseReady = valids(head) && !committed(head) && precises(head) &&
                         (robIds(head) === io.robHeadIn) && io.robHeadValidIn &&
                         !io.flush && !io.irqPreemptPendingIn

  // Flush-source review (design doc §4.1, recorded here per the plan's Slice P3
  // hardening pass): a branch-mispredict flush (RobPlugin's doFlushReg) can only
  // originate from a RETIRING head, and a store is never itself a mispredicting
  // branch -- so a flush never races an in-flight precise drain's OWN instruction.
  // excSquash (the commit-side exception sequencer) requires excIdle to have
  // already gone false, which the exception-entry trigger itself gates on the
  // head being a plain (non-precise-drain-holding) instruction -- a precise store
  // occupying the head blocks faultRetire/rteRetire/sysRetire from firing (headReady
  // there still requires completes(h0), which a not-yet-resolved precise entry has
  // not set) exactly the same way it blocks retire0. No additional gating needed.
  val sendMode = CacheMode()
  sendMode := Mux(sendPhaseB, cacheModesB(sendPtr), cacheModes(sendPtr))
  val sendPrecise = precises(sendPtr)
  val sendCommitted = valids(sendPtr) && committed(sendPtr) && !io.flush
  val sendAtHead = sendPtr === head
  val noAccepted = acceptedHalves === 0
  // Task (WT-pipelining): WRITETHROUGH joins COPYBACK here. Both share the exact
  // same precondition -- `!sendPrecise`, i.e. this is a `fastStore` (LsEuPlugin's
  // `!fastStore` classification) that already completed its ROB bookkeeping
  // decoupled from the physical write, so presenting it as soon as it is
  // COMMITTED (not gated on `sendAtHead`/`noAccepted`, i.e. not required to be the
  // sole occupant of DcachePlugin's drain pipe) carries no NEW precision cost --
  // see DcachePlugin.scala's `inputStoreSerial`/`wtOutstanding` for the admission
  // side of this change and its own doc comment for the full argument (a
  // non-precise WT store's bus error was ALREADY diagnostic-only/async before this
  // change, exactly like COPYBACK's kind=1/2/3 sites -- pipelining does not touch
  // that). `sendMode` still gates OUT `INHIBITED`, which can never reach here
  // anyway: `fastStore` (LsEuPlugin.scala) requires `cmode =/= INHIBITED`, so an
  // INHIBITED access is unconditionally `precise` and never observes
  // `sendPipelined` regardless of this term.
  val sendPipelined = !sendPrecise &&
    (sendMode === CacheMode.COPYBACK || sendMode === CacheMode.WRITETHROUGH)
  val sendPreciseReady = sendAtHead && headPreciseReady && noAccepted
  val sendSerialReady = sendCommitted && sendAtHead && noAccepted
  io.drain.valid := Mux(sendPipelined, sendCommitted,
    Mux(sendPrecise, sendPreciseReady, sendSerialReady))

  // Present slot A (phase false) or slot B (phase true) from the send cursor.
  // Drain-payload derivation (task #252): strb/lineData are pure functions of
  // (line-offset, size, data) — DcacheByteLane's own store-split math — computed here
  // directly instead of reading a per-entry Mem row. `sendStOff` is slot A's own
  // paddr low nibble; slot B's bytes are derived from that SAME offset (slot B's own
  // paddr is always line-aligned at offset 0 for a split, so it carries no useful
  // offset of its own — see LsEuPlugin's `stOff`, the original alloc-time source of
  // this exact math).
  val sendStOff = paddrs(sendPtr)(3 downto 0)
  val sendStrbA = m68k040.cache.DcacheByteLane.storeStrbA(sendStOff, sizes(sendPtr))
  val sendDataA = m68k040.cache.DcacheByteLane.storeDataA(sendStOff, sizes(sendPtr), datas(sendPtr))
  val sendStrbB = m68k040.cache.DcacheByteLane.storeStrbB(sendStOff, sizes(sendPtr))
  val sendDataB = m68k040.cache.DcacheByteLane.storeDataB(sendStOff, sizes(sendPtr), datas(sendPtr))
  when(!sendPhaseB) {
    io.drain.payload.paddr    := paddrs(sendPtr)
    io.drain.payload.data     := datas(sendPtr)
    io.drain.payload.size     := sizes(sendPtr)
    io.drain.payload.useStrb  := useStrbAs(sendPtr)
    io.drain.payload.strb     := sendStrbA
    io.drain.payload.lineData := sendDataA
    io.drain.payload.cacheMode := cacheModes(sendPtr)
    io.drain.payload.precise  := precises(sendPtr)
  } otherwise {
    io.drain.payload.paddr    := paddrBs(sendPtr)
    io.drain.payload.data     := B(0, 32 bits)
    io.drain.payload.size     := Size.LONG()
    io.drain.payload.useStrb  := True
    io.drain.payload.strb     := sendStrbB
    io.drain.payload.lineData := sendDataB
    io.drain.payload.cacheMode := cacheModesB(sendPtr)
    io.drain.payload.precise  := precises(sendPtr)
  }

  val drainIssue = io.drain.fire

  // ---- forwarding (combinational), DUAL-SLOT ----
  // A younger load checks BOTH slot A [paddrA, +nbytesA) and (if validB) slot B
  // [paddrB, +nbytesB) of every older store. A full single-slot forward is taken
  // ONLY for an exact addr+size match against a NON-split store's slot A (the
  // aligned fast path, unchanged). Any overlap with slot B, or a partial overlap,
  // STALLS (the cross load waits for the store to drain to memory then re-reads).
  val q          = io.fwd.query
  val qBytes     = sizeBytes(q.size)
  val perEntry   = for (i <- 0 until depth) yield new Area {
    // committed(i): the ROB already retired this store -- unconditionally older
    // than any live query (see olderThan's comment above, case 1). Otherwise
    // fall back to the head-anchored compare (case 2).
    val ent      = valids(i) && (committed(i) || olderThan(robIds(i), q.robId))
    val qLo      = q.paddr
    val qHi      = q.paddr + qBytes
    // slot A range. paddrHi is PRE-REGISTERED at alloc (= paddr + nbytesA), so the
    // overlap test is a pure COMPARE here — no per-entry adder in the forward cone.
    val aLo      = paddrs(i)
    val aHi      = paddrHiAs(i)
    val overlapA = ent && (qLo < aHi) && (aLo < qHi)
    // slot B range (only when this entry is a split store); bHi pre-registered too.
    val bLo      = paddrBs(i)
    val bHi      = paddrHiBs(i)
    val overlapB = ent && validBs(i) && (qLo < bHi) && (bLo < qHi)
    val overlap  = overlapA || overlapB
    // full overlap = exact addr+size against slot A of a NON-split store.
    val full     = overlapA && !validBs(i) && (aLo === qLo) && (nbytesAs(i) === qBytes)
    val partial  = overlap && !full
    val inhibitedStore = ent &&
      ((cacheModes(i) === CacheMode.INHIBITED) ||
       (validBs(i) && (cacheModesB(i) === CacheMode.INHIBITED)))
    // SAME-CACHE-LINE hazard (16-byte line; offBits=4). A younger load that MISSES the
    // L1D refills the WHOLE line from memory. If an OLDER WRITETHROUGH store to the SAME
    // line is still in the SQ (not yet written through to memory), that refill would cache
    // a STALE line (the store's bytes not yet in memory, and the store — write-no-allocate
    // -- won't update the now-cached line). A subsequent load to OTHER bytes of that line
    // then HITS the stale cached copy. So a load that line-overlaps an older in-flight
    // WRITETHROUGH store must STALL until the store drains, even with NO byte overlap.
    // (A clean FULL forward is still safe -- we already have the data -- so it is excluded
    // by the consumer below.)
    //
    // LS-cluster review finding P5 (this task): this hazard is WRITETHROUGH-specific, not
    // mode-agnostic. It exists only because WT-miss is write-no-allocate -- the store's
    // bytes never touch the cache array, so a stale line cached by a racing refill is
    // PERMANENTLY wrong (nothing ever corrects it). Under COPYBACK there is no equivalent
    // hole: whenever this store finally reaches drain admission (DcachePlugin store-S1/S2),
    // it performs a FRESH tag/hit check against the array's CURRENT state -- `stS2HitAny`,
    // computed live, not cached from alloc time (DcachePlugin.scala ~2710). If the line is
    // resident (e.g. a same-line load's refill populated it in the interim, clean, without
    // this store's bytes) the store takes the COPYBACK-hit arm: an on-chip RMW that merges
    // its bytes into the array and sets the dirty bit (DcachePlugin.scala ~2744, Task P4.1).
    // If the line is NOT resident (e.g. it was evicted again before the store drained) the
    // store takes the write-allocate arm (DcachePlugin.scala ~2716, Task P4.2), fetching a
    // fresh copy and merging in the same way. Either arm leaves the array holding the
    // store's bytes once the store drains -- the array self-heals, there is no window where
    // staleness becomes permanent. Three further races were checked and are independently
    // closed by existing, address-agnostic pipeline interlocks (not by this SQ-level stall):
    //   1. Store tries to drain WHILE a same-line load's refill is still in flight: SQ
    //      drain admission (S0) is held off by `storePipeHeld` (`loadMissDiscovered` /
    //      `loadMissStoreBarrier`, DcachePlugin.scala ~2527) for the ENTIRE duration of any
    //      in-flight load miss, regardless of address -- the store cannot even begin its own
    //      tag check until the refill completes, and then sees the line as resident (hit).
    //   2. A same-line load's miss is discovered WHILE this store is already admitted
    //      (S1/S2/S3): `loadMissStoreBarrier` snapshots/parks exactly this race (again
    //      address-agnostic -- protects the eviction-snapshot mechanism generally).
    //   3. A same-line load's refill lands DURING this store's own S1->S2 hit-write window
    //      (registered tag-read stale for 2 cycles): `DcachePlugin.refillWriteHold` holds the
    //      refill's array write off whenever `stS1Set`/`stS2Set` match the refill's set --
    //      a RAW SET-INDEX compare (`paddr(offBits+setBits-1 downto offBits)`), a strict
    //      SUPERSET of same-LINE, so this hold already covers the exact-same-line case
    //      unconditionally, independent of this file's `sameLine` term (verified directly
    //      against that RTL, not assumed -- see the note at `refillWriteHold`'s declaration).
    // Genuine byte-level overlap (a real RAW hazard) is NOT this term's job either way --
    // that is `overlap`/`full`/`partial` above, entirely untouched by cache mode.
    // CONCLUSION: gate this stall's line terms on `cacheModes(i)`/`cacheModesB(i)` (each
    // half of a split store may carry an independently-translated cache mode -- see
    // `SqAlloc.cacheModeB`) so a COPYBACK entry no longer forces this stall for a
    // same-line-but-non-overlapping query; WRITETHROUGH (and INHIBITED, already covered
    // separately by `serialStall` below) keep it exactly as conservative as before.
    //
    // AREA-COST FOLLOW-UP (the "+2,287 LUT" concern `9130a0b2` flagged against itself and
    // asked to be root-caused): that number is a MISATTRIBUTION. Measured, not argued:
    //   * It reproduces exactly -- OOC synth of the parent vs this commit, same machine,
    //     same tool: CLB LUTs 98,018 -> 100,305 (+2,287), raw LUT cells 97,124 -> 98,971
    //     (+1,847), FF +18, WNS -1.156ns -> -0.867ns (timing IMPROVED).
    //   * But normalising SpinalHDL's line-number-derived signal names and diffing the two
    //     20MB netlists shows the ONLY logic difference in the WHOLE design is the eight
    //     `perEntry_i_sameLine` assigns below -- 16 two-bit enum compares folded into
    //     already-present 28-bit comparators. Everything else in that diff is signal
    //     RENAMING caused by the DcachePlugin.scala comment edits shifting line numbers.
    //     Re-synthesising the post-commit netlist with ONLY those eight assigns reverted
    //     (renaming kept) reproduces the parent's numbers BIT-IDENTICALLY on every metric,
    //     so the renaming contributes exactly zero and the delta is causally this change.
    //   * It is NOT timing-driven: at a relaxed 20ns clock (13-14ns of slack, no timing
    //     pressure at all) the raw LUT delta is the SAME +1,847, with identical F7 (-50),
    //     F8 (-91) and CARRY8 (-6) deltas.
    //   * Localising the delta by driven-signal name settles it: `LsEuPlugin*` -- which is
    //     where this StoreQueue and every changed gate LIVES -- moves by +12 LUTs, and this
    //     forward cone itself (`perEntry`/`sameLine`/`fwd`/`cands`/`ageDist`) gets 290 LUTs
    //     SMALLER. +1,960 of the delta lands in `DecodeStage_logic_pushReg_payload_uops_*`.
    //   * DecodeStage cannot possibly be a consequence of this term: `io.fwd.rsp.stall` has
    //     exactly TWO loads in the entire emitted netlist, both the same `p4Ctx_fwdStall`
    //     flip-flop D-input (LsEuPlugin.scala) -- this whole cone's combinational fanout
    //     terminates at ONE register, so there is no path from here to decode at all.
    // CONCLUSION: this fix's real area cost is ~12 LUTs; the rest is Vivado's global
    // optimiser landing in a different local optimum in an unrelated cone. Nothing here is
    // restructurable to recover it (hoisting the cone's duplicated query-side `qHi` adder /
    // `robId` age subtract was tried and measured BIT-IDENTICAL -- Vivado already CSEs
    // them, and the routed netlist shows the shared `perEntry_0_qHi` net at fanout 32).
    // Corollary for future A/Bs on this design: attribute area by cell-name localisation,
    // not by the design total -- a ~12-LUT change measured as +2.3% of the whole device.
    val lineA    = paddrs(i)(31 downto 4)
    val lineB    = paddrBs(i)(31 downto 4)
    val qLine    = q.paddr(31 downto 4)
    val sameLine = ent && (
      ((lineA === qLine) && (cacheModes(i) =/= CacheMode.COPYBACK)) ||
      (validBs(i) && (lineB === qLine) && (cacheModesB(i) =/= CacheMode.COPYBACK))
    )
  }
  // youngest older overlapping entry: among ALL overlapping matches (full OR
  // partial), the one closest (in ROB age) to the query. ONE reduce tree carrying
  // whether that youngest-overlapping entry is a FULL overlap. A clean forward is
  // possible iff the YOUNGEST overlapping store fully covers the query: a younger
  // partial store (e.g. a split store's slot B overwriting some query bytes) would
  // itself be the youngest-overlapping entry and is NOT full -> stall. This is a
  // single tree (same depth as the prior youngest-full select), avoiding a serial
  // dependency on a separately-reduced `best.dist` (which regressed FMax).
  val anyPartial = perEntry.map(_.partial).orR
  val ageDist    = Vec((0 until depth).map(i => (q.robId - robIds(i))(5 downto 0)))
  case class Cand() extends Bundle {
    val valid = Bool(); val full = Bool(); val dist = UInt(6 bits); val data = Bits(32 bits)
  }
  val cands = (0 until depth).map { i =>
    val c = Cand()
    c.valid := perEntry(i).overlap
    c.full  := perEntry(i).full
    c.dist  := ageDist(i)
    c.data  := datas(i)
    c
  }
  val best = cands.reduceBalancedTree { (a, b) =>
    val o = Cand()
    // prefer the valid one; if both valid, the smaller dist (younger store)
    when(a.valid && (!b.valid || (a.dist <= b.dist))) { o := a } otherwise { o := b }
    o
  }
  val fullValid = best.valid && best.full
  // Same-line hazard: any older in-flight WRITETHROUGH store to the load's cache line
  // forces a stall (the refill-stale-line hole above), UNLESS we can cleanly FULL-forward
  // the exact bytes (then we have the data and never touch the cache). A byte-partial
  // overlap already stalls via anyPartial; sameLine extends that to line-overlap-only WT
  // stores too (COPYBACK entries are excluded from this term -- see the `sameLine` decl
  // comment in `perEntry` above for the hazard-coverage argument).
  val anySameLine = perEntry.map(_.sameLine).orR
  // INHIBITED is a total-order boundary (architecture design §7.1; MSHR design
  // §4.2/§5.5), not merely a cache-bypass hint.  Therefore:
  //   * an inhibited load waits for every older resident store; and
  //   * every load waits for an older inhibited store.
  // This deliberately covers different addresses: many MMIO devices expose a
  // write port and readback/status port at distinct addresses.  Suppress a
  // nominal full-overlap forward while the serial barrier is present so the LS
  // EU cannot take its fullForward arm ahead of rsp.stall.
  val anyOlder = perEntry.map(_.ent).orR
  val anyOlderInhibitedStore = perEntry.map(_.inhibitedStore).orR
  val serialStall = (q.inhibited && anyOlder) || anyOlderInhibitedStore
  // Suppress the forward across a serialization boundary: a device read must reach
  // the DEVICE, never echo an older store's data out of this ring.
  io.fwd.rsp.hit   := fullValid && !serialStall
  io.fwd.rsp.data  := best.data
  // DELIBERATELY NOT `|| serialStall`.  Driving the boundary as a forwarding STALL
  // made the LS-EU re-query from p4 every cycle until the older store drained --
  // a wait whose release condition is not self-resolving, because the spinning load
  // holds LS-EU/D-cache resources that the very drain it waits for can need.  That
  // is a real hardware hang: 2026-08-22 the CPU wedged permanently on `TST.B` of a
  // VIA register with a healthy bus, zero exceptions, and a debug halt (which stops
  // the retry) released it.  The ordering is instead enforced ONCE, at the LS-EU's
  // load-launch gate, as "do not launch until this load is the ROB head and the ring
  // has drained" -- a condition the in-order ROB is guaranteed to reach.  Only the
  // ordinary address hazards remain a stall here.
  io.fwd.rsp.stall := (anyPartial || anySameLine) && !fullValid

  // ---- commit: mark the matching valid entry committed (either retire slot) ----
  when(io.commit.valid) {
    for (i <- 0 until depth)
      when(valids(i) && (robIds(i) === io.commit.payload)) { committed(i) := True }
  }
  when(io.commitB.valid) {
    for (i <- 0 until depth)
      when(valids(i) && (robIds(i) === io.commitB.payload)) { committed(i) := True }
  }

  // ---- alloc: push at tail (speculative) ----
  when(io.alloc.valid && !io.flush) {
    valids(tail)    := True
    committed(tail) := False
    robIds(tail)    := io.alloc.payload.robId
    paddrs(tail)    := io.alloc.payload.paddr
    datas(tail)     := io.alloc.payload.data
    sizes(tail)     := io.alloc.payload.size
    nbytesAs(tail)  := io.alloc.payload.nbytesA
    // PRE-REGISTER the slot-A upper byte-range bound (= paddr + nbytesA). This `+`
    // yields a 32-bit result (carry-out dropped), bit-identical to the old forward-
    // path `paddrs(i) + nbytesAs(i)` — the adder now lives at alloc (once/cycle, off
    // the forward cone) instead of per-entry in the binding s2Paddr->fwdData path.
    paddrHiAs(tail) := io.alloc.payload.paddr + io.alloc.payload.nbytesA
    useStrbAs(tail) := io.alloc.payload.useStrbA
    validBs(tail)   := io.alloc.payload.validB
    paddrBs(tail)   := io.alloc.payload.paddrB
    nbytesBs(tail)  := io.alloc.payload.nbytesB
    // PRE-REGISTER the slot-B upper byte-range bound (= paddrB + nbytesB), same as A.
    paddrHiBs(tail) := io.alloc.payload.paddrB + io.alloc.payload.nbytesB
    // (strbA/lineDataA/strbB/lineDataB no longer stored -- derived at drain, task #252.)
    vaddrAs(tail)     := io.alloc.payload.vaddr
    vaddrBs(tail)     := io.alloc.payload.vaddrB
    cacheModes(tail)  := io.alloc.payload.cacheMode
    cacheModesB(tail) := io.alloc.payload.cacheModeB
    supervisors(tail) := io.alloc.payload.supervisor
    precises(tail)    := io.alloc.payload.precise
    tail := tail + 1
  }

  // ---- drain handshake: accepted send cursor + in-order ack cursor -----------
  // A split store contributes two accepted halves. The send cursor may run ahead
  // across committed COPYBACK halves; the ack cursor alone controls forwarding
  // visibility and architectural pop.
  //
  // Precise-path completion/fault (design doc §4.1): a precise entry withholds ROB
  // completion until its real bus response is observed here. sqCompletion fires on
  // every successful (or terminally-popped, even faulted) ack of a precise head --
  // the ROB's completes-required-even-for-a-fault contract needs both to fire
  // together on error. sqFaultCompletion additionally fires (and carries the
  // FAILING SLOT's own logical address, not always slot A's) only on a genuine
  // AXI B error.
  io.sqCompletion.valid   := False
  io.sqCompletion.payload := robIds(head)
  io.sqFaultCompletion.valid           := False
  io.sqFaultCompletion.payload.robId   := robIds(head)
  io.sqFaultCompletion.payload.faultAddr  := Mux(drainPhaseB, vaddrBs(head), vaddrAs(head))
  io.sqFaultCompletion.payload.write      := True
  io.sqFaultCompletion.payload.sizeBits   := sizeBitsOf(sizes(head))
  io.sqFaultCompletion.payload.supervisor := supervisors(head)
  io.sqFaultCompletion.payload.atc        := False   // a physical bus error, never MMU/ATC

  val drainAckFire = io.drainAck && drainBusy
  val terminalAck = drainAckFire &&
    ((precises(head) && io.drainErr) || ackPhaseB || !validBs(head))

  // Stream-side cursor. A and B of a split entry are separate handshakes.
  when(drainIssue) {
    when(!sendPhaseB && validBs(sendPtr)) {
      sendPhaseB := True
    } otherwise {
      sendPhaseB := False
      sendPtr := sendPtr + 1
    }
  }

  // Exact accepted-half occupancy, including simultaneous local-ack/accept
  // turnover on a dense COPYBACK-hit stream.
  when(drainIssue && !drainAckFire) {
    acceptedHalves := acceptedHalves + 1
  } elsewhen(!drainIssue && drainAckFire) {
    acceptedHalves := acceptedHalves - 1
  }

  when(drainAckFire) {
    when(precises(head) && io.drainErr) {
      // Terminal error pop (design doc §4.1): do NOT continue to slot B / leave the
      // entry for excEnteringSq -- pop it right here so phase state stays clean and
      // there is no orphan class. The completion ALSO fires (headReady in
      // the ROB requires completes(h0) even for a faulted entry) alongside the fault.
      io.sqCompletion.valid      := True
      io.sqFaultCompletion.valid := True
      ackPhaseB    := False
      valids(head) := False
      head := head + 1
      // A precise split is serialized, so an error on A occurs before B has fired.
      // Skip that unsent B and realign presentation with the popped entry.
      when(!ackPhaseB && validBs(head)) {
        sendPhaseB := False
        sendPtr := head + 1
      }
    } otherwise {
      when(!ackPhaseB && validBs(head)) {
        // slot A acked -> drain slot B next (atomic two-half drain; do NOT pop yet)
        ackPhaseB := True
      } otherwise {
        // single-slot store, or slot B of a split store -> pop the whole entry.
        // sqCompletion fires only on the ack that actually pops the entry, so a
        // split store's slot-A ack does not prematurely signal ROB completion.
        when(precises(head)) { io.sqCompletion.valid := True }
        ackPhaseB    := False
        valids(head) := False
        head := head + 1
      }
    }
  }

  // ---- preciseDrainBusy: registered launch-through-resolution-plus-one-cycle busy
  // (design doc §4.1: "the SQ launch decision should be registered and the ROB
  // samples the registered busy"), asserted the cycle a precise drain LAUNCHES and
  // held one extra cycle past its resolution (the ack-OK-to-retire handshake seam)
  // so the ROB's normalIrqGate/traceNormalGate never race a same-cycle preempt
  // against a drain that just resolved. ----
  val preciseDrainBusyReg = RegInit(False)
  val preciseLaunch = drainIssue && sendPrecise
  val preciseFinalAck = terminalAck && precises(head)
  val preciseFinalAckD = RegNext(preciseFinalAck, init = False)
  when(preciseLaunch) { preciseDrainBusyReg := True }
    .elsewhen(preciseFinalAckD) { preciseDrainBusyReg := False }
  io.preciseDrainBusy := preciseDrainBusyReg

  // Protocol and precise-path priority invariants. A serial/precise half may only
  // launch with no older accepted half; an ack can never exist without occupancy.
  GenerationFlags.simulation {
    assert(!(io.drainAck && !drainBusy),
      "StoreQueue: drainAck arrived with no accepted drain half",
      FAILURE)
    assert(!(io.drainErr && !io.drainAck),
      "StoreQueue: drainErr must qualify a same-cycle drainAck",
      FAILURE)
    assert(!(drainIssue && !sendPipelined && !noAccepted),
      "StoreQueue: a serial/precise drain half launched while older halves were accepted",
      FAILURE)
  }

  // ---- flush: squash speculative (uncommitted) entries. Roll tail back to just
  // past the youngest COMMITTED entry. Walk from head over committed entries. ----
  when(io.flush) {
    // A drainAck this same cycle pops the head entry (single-slot, or slot B of a
    // split). That popped entry must NOT be counted as kept — otherwise the flush's
    // keepCount over-counts by one and tail lands one past the real youngest entry,
    // leaving a PHANTOM entry that never drains (empty stays false forever -> the
    // commit-side exception FSM hangs at E_DRAIN). Exclude the popped head here.
    val popsHead = terminalAck
    val keep = Vec(Bool(), depth)
    for (i <- 0 until depth)
      keep(i) := valids(i) && committed(i) && !(popsHead && (U(i, log2Up(depth) bits) === head))
    for (i <- 0 until depth) when(!keep(i)) { valids(i) := False }
    // new tail = head' + (number of committed-live entries), where head' accounts for
    // the coincident pop (head advances by 1 if popsHead). Committed entries are the
    // oldest contiguous run (commit is in-order), so this is exact.
    val keepCount = CountOne(keep)
    val headAfter = Mux(popsHead, head + 1, head)
    tail := (headAfter + keepCount).resized
  }

  // ---- count = hardware sum of valids (no Scala-var counters) ----
  count := CountOne(valids).resized

  // ---- full: all `depth` entries resident. Driven from the LIVE valids popcount
  // (not the registered `count`, which trails valids by a cycle) so back-pressure is
  // precise: there is no one-cycle window where a stale count would let a (depth+1)th
  // alloc slip in and overrun the ring. This compare feeds ONLY the LS-EU alloc FSM
  // (a `WAIT_SQ` stall, off the IQ select cone) — same push-only discipline as the
  // `lsBusy` read — so it is not on the sensitive issue->operand critical path. ----
  val liveCount = CountOne(valids)
  io.full := liveCount === U(depth, liveCount.getWidth bits)

  // Sim-only contract guard: the LS-EU must never present an alloc while full (the
  // alloc Flow has no ready, so a missing WAIT_SQ back-pressure would silently
  // overrun the ring and corrupt `count`/`head`/`tail`). A flush this cycle squashes
  // speculative entries, so allow alloc+flush coincidence.
  // Explicit `FAILURE` severity -- see M68kSim.scala for why `.includeSimulation`
  // must also be set on the enclosing SpinalConfig for this block to elaborate at
  // all (without it, `GenerationFlags.simulation { ... }` is silently skipped).
  GenerationFlags.simulation {
    assert(!(io.alloc.valid && io.full && !io.flush),
      "StoreQueue: alloc fired while full — missing LS-EU back-pressure (WAIT_SQ)",
      FAILURE)
  }

  // ---- empty: no resident entry AND no drain in flight ----
  io.empty := !valids.reduce(_ || _) && !drainBusy

  // ---- barrier residency, AGE-QUALIFIED ----
  // The age qualifier is load-bearing, not a refinement.  A YOUNGER store can allocate
  // while an older load is parked at the LS-EU's launch gate: the IQ issues the oldest
  // LS uop (`ohLoldest`) but that uop has already LEFT its slot by the time it parks,
  // so the next LS uop is free to issue behind it.  Gating on whole-ring occupancy
  // would therefore let a younger store hold an older load forever -- the store cannot
  // commit until the load retires, and the load would not launch until the ring drains.
  // Only OLDER entries may gate.  `ent`'s `committed(i) ||` arm is what makes this
  // exact across a robId wrap (see `olderThan`'s header).
  val barrierEnt = (0 until depth).map(i =>
    valids(i) && (committed(i) || olderThan(robIds(i), io.barrier.robId)))
  io.barrier.olderStore := barrierEnt.reduce(_ || _)
  io.barrier.olderInhibitedStore := (0 until depth).map(i =>
    barrierEnt(i) && ((cacheModes(i) === CacheMode.INHIBITED) ||
                      (validBs(i) && (cacheModesB(i) === CacheMode.INHIBITED)))).reduce(_ || _)
  io.barrier.olderStore.simPublic(); io.barrier.olderInhibitedStore.simPublic()

  // ---- debug-only observability (task #139 finding #1 investigation) ----
  // Zero synth impact (sim tap only, not referenced by any RTL logic).
  head.simPublic(); tail.simPublic(); sendPtr.simPublic()
  sendPhaseB.simPublic(); ackPhaseB.simPublic(); acceptedHalves.simPublic()
  drainBusy.simPublic(); drainPhaseB.simPublic()
  valids.foreach(_.simPublic()); committed.foreach(_.simPublic())
  robIds.foreach(_.simPublic())
  // P2.1: per-entry precise-path storage (vaddr/cacheMode/supervisor/precise), tapped
  // so directed alloc-then-inspect tests can verify the ring stored what was allocated.
  vaddrAs.foreach(_.simPublic()); vaddrBs.foreach(_.simPublic())
  cacheModes.foreach(_.simPublic()); cacheModesB.foreach(_.simPublic())
  supervisors.foreach(_.simPublic()); precises.foreach(_.simPublic())
  io.drain.valid.simPublic(); io.drain.ready.simPublic(); io.drainAck.simPublic(); io.flush.simPublic()
  // Task #139 mechanism #2: catch the ORIGINATING alloc of any SQ entry, so a
  // later-observed stuck head can be traced back to the actual allocating PC
  // even after the ROB has reused that robId number for a newer instruction.
  io.alloc.valid.simPublic(); io.alloc.payload.robId.simPublic(); io.alloc.payload.paddr.simPublic()
  // Task P2.5 post-review fix: expose the RAW at-head drain-confirm pulse (before
  // LsEuPlugin's own apply/collision-retry arbitration) so a directed test can
  // distinguish "the SQ confirmed the drain this cycle" from "the replay actually
  // applied it" -- the whole point of the `liveCompletionFires` collision test.
  io.sqCompletion.valid.simPublic(); io.sqCompletion.payload.simPublic()
}
