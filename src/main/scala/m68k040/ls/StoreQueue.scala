package m68k040.ls

import m68k040.cache.{CacheMode, DStoreCmd}
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import spinal.lib._

case class SqAlloc() extends Bundle {
  val robId = UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)
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
  val robId = UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)
  val paddr = UInt(32 bits)
  val size  = Size()
  // ── split/page-crossing second half (cross-page forward-hazard fix) ──────────
  // A misaligned or page-crossing access spills up to 3 bytes into a SECOND,
  // INDEPENDENTLY-translated address (LsEuPlugin's XLATE_B state -> its
  // `paddrB`/`p3Ctx.paddrB`). For a same-PAGE line-crossing access that second
  // half happens to be physically `paddr`'s line + 1 (the page offset survives
  // translation verbatim), but for a page-crossing access it can be ANY
  // physical line at all, with no fixed relationship to `paddr`. `paddrB` here
  // is that real translated address; `splitB` gates whether it is meaningful,
  // mirroring exactly how `SqAlloc.validB` gates `SqAlloc.paddrB` off
  // `front.twoAccess`. A query with `splitB` false must not have its spill
  // lanes (`qMaskNext`, see below) consulted against `paddrB` at all -- and in
  // practice never can, since `qMaskNext` is provably zero whenever `splitB`
  // is false (both are derived from the same `off + size > 16` geometry).
  val splitB = Bool()
  val paddrB = UInt(32 bits)
  // Cache-inhibited accesses are serialized device operations.  They order
  // against every older store, even when the addresses do not overlap (device
  // command/data and register ports commonly occupy different addresses).
  val inhibited = Bool()
}

case class SqFwdRsp() extends Bundle {
  val hit   = Bool()          // full-overlap forward
  val data  = Bits(32 bits)
  val stall = Bool()          // partial/ambiguous overlap with an older store
  // The verdict above was computed while the INHIBITED serialization barrier was
  // present (`serialStall`): an older inhibited store is resident (any query), or the
  // query itself is inhibited and any older store is resident. `hit` is masked in
  // that case, and `stall` is NOT raised for it (see the deliberate note at the
  // driver) -- so a consumer that registers this verdict and later acts on it MUST
  // treat it as provisional: the masked `hit` may hide an exact-match older store
  // that is still undrained when the barrier lifts (hardware corruption at ROM
  // 0x408990E2, 2026-09-08). The LS-EU re-queries while this is set.
  val serial = Bool()
}

/** Speculative store queue: a small ring (depth parametric, default 8).
  *
  *  - alloc  : a store executes -> push at tail (speculative, uncommitted).
  *  - commit : ROB retired this robId -> mark the matching entry committed.
  *  - drain  : committed halves stream in program order into the L1D. COPYBACK
  *             hits may be accepted ahead; an entry pops only on its final ack.
  *  - flush  : mispredict/exception/debug redirect -> roll the tail back to the
  *             youngest entry that must survive: every COMMITTED entry, PLUS the
  *             head entry if it is a precise drain already ACCEPTED by DcachePlugin
  *             (its physical bus write already left the CPU and cannot be
  *             un-issued -- see `headDrainInFlight` below, BUG_calibration_word_
  *             misplaced_0d00.md Part 37). Every other (truly still-speculative,
  *             never-launched) entry is squashed and never drains -> memory
  *             untouched. Same pointer discipline as the ROB/freelist.
  *  - fwd    : load forward check. Among entries OLDER than query.robId (ROB
  *             circular order), address-overlapping: full same-size overlap ->
  *             hit+data (youngest such); partial/ambiguous -> stall. Optional
  *             subword forwarding also covers byte/word loads within aligned LONGs.
  *
  * All state is RegInit (no uninit Regs); counts are hardware sums (no
  * when-gated Scala-var counters). */
class StoreQueue(depth: Int = 8, subwordForwarding: Boolean = false,
                 reserveLateStore: Boolean = false) extends Component {
  require(isPow2(depth))
  val ptrW = log2Up(depth)

  val io = new Bundle {
    val alloc    = slave(Flow(SqAlloc()))
    // Local synchronous reservation owner: LSU P3. Flush cancels that owner;
    // this slot index must never be used for an outstanding asynchronous reply.
    val reserveOnly = if(reserveLateStore) in Bool() else null
    val allocSlot = if(reserveLateStore) out(UInt(ptrW bits)) else null
    val publish = if(reserveLateStore) slave(Flow(new Bundle {
      val slot = UInt(ptrW bits)
      val robId = UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)
      val data = Bits(32 bits)
    })) else null
    val fwd      = new Bundle { val query = in(SqFwdQuery()); val rsp = out(SqFwdRsp()) }
    val commit   = slave(Flow(UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)))
    // Second same-cycle commit port: a store can retire in EITHER slot of the 2-wide
    // retire (slot 1 when it completed early behind a long-latency head, e.g. DIV).
    // Marking a cycle LATE is unsafe — a flush arriving the next cycle would squash
    // the already-retired store — so both retire slots must mark in the retire cycle.
    val commitB  = slave(Flow(UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)))
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
      val robId               = in(UInt(m68k040.Global.ROB_ID_W_DEFAULT bits))
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
    val robHeadIn           = in(UInt(m68k040.Global.ROB_ID_W_DEFAULT bits))   // = rob.logic.h0
    val robHeadValidIn      = in(Bool())         // = rob.logic.count > 0
    val irqPreemptPendingIn = in(Bool())         // = rob.logic.interruptPending || rob.logic.tracePendingFire
    val sqCompletion        = master(Flow(UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)))
    val sqFaultCompletion   = master(Flow(m68k040.execute.LsFault()))
    val preciseDrainBusy    = out(Bool())
    // ---- Part 127: flush/orphan handshake with LsEuPlugin's `pendMem` ring --------
    // `pendMem` and this ring are a lock-step PAIR (see `LsEuPlugin.deferCompletion`).
    // These two outputs are the only things that let the LS EU mirror the flush rule
    // this component actually applies, instead of assuming it.
    //   `flushKeptPrecise` : THIS flush cycle kept an uncommitted precise head (the
    //                        `headDrainInFlight` arm). Its pendMem entry must be kept
    //                        too, or the two rings desynchronize by one FOREVER.
    //   `sqCompletionOrphan`: qualifies `sqCompletion` -- the popping entry has no live
    //                        ROB entry, so its replay must be DISCARDED, not applied.
    val flushKeptPrecise    = out(Bool())
    val sqCompletionOrphan  = out(Bool())
  }

  // ---- ring storage (all RegInit) ----
  val valids    = Vec.fill(depth)(RegInit(False))
  val dataReady = if(reserveLateStore) Vec.fill(depth)(RegInit(False)) else null
  def hasData(slot: UInt): Bool = if(reserveLateStore) dataReady(slot) else True
  val committed = Vec.fill(depth)(RegInit(False))
  val robIds    = Vec.fill(depth)(RegInit(U(0, m68k040.Global.ROB_ID_W_DEFAULT bits)))
  val paddrs    = Vec.fill(depth)(RegInit(U(0, 32 bits)))
  val datas     = Vec.fill(depth)(RegInit(B(0, 32 bits)))
  val sizes     = Vec.fill(depth)(RegInit(Size.BYTE()))
  // slot-A explicit-strobe drain (split stores). The covered byte count that used to
  // live alongside it is gone -- the forward overlap test is byte-lane masks now, and
  // `nbytesA/B` had no other consumer (see the geometry block below).
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
  // ── Forward-overlap geometry: PRE-REGISTERED byte-lane masks (this task) ──────
  // The forward cone used to answer "does this entry overlap the query?" with a pair
  // of 32-bit MAGNITUDE comparisons per slot against a pre-registered upper bound
  // (`paddrHiA/B = paddr + nbytes`, the previous FMax pass). That still left the
  // QUERY-side `qHi = q.paddr + qBytes` 32-bit adder (3 CARRY8 levels, fanout 32)
  // directly on the design's worst post-route path
  // (`p4Ctx_fwdHit -> p4Ctx_fwdData[7]`, -0.420ns, 13 levels -- see `b8346d1b`).
  //
  // Both SQ slots are provably confined to ONE 16-byte cache line (proof + the
  // permanent alloc-time sim assert below), so the whole magnitude comparison is more
  // than the question needs: overlap == "same line AND the byte-lane masks intersect".
  // The masks are computed HERE, at alloc, once per cycle, off the forward cone; the
  // per-query cost is then a 28-bit line EQUALITY (which `sameLine` below already
  // computed anyway, so it is shared) plus a bitwise mask AND -- zero carry chains,
  // zero arithmetic, in the whole cone.
  //
  //   maskAs(i)     16-bit byte-lane mask of slot A within line `paddrs(i)(31:4)`
  //   maskBs(i)     ditto for slot B within line `paddrBs(i)(31:4)` (0 when !validB)
  //
  // The QUERY, unlike an entry, is NOT line-confined: a misaligned load at line
  // offset 13..15 spans its own line AND a second, independently-translated one
  // (LsEuPlugin's `s1CrossLine`/`s1CrossPage`). The query therefore carries a
  // 19-bit span -- 16 lanes of its own line plus up to 3 lanes of the SECOND
  // half -- plus that second half's REAL translated address, `SqFwdQuery.paddrB`
  // (cross-page forward-hazard fix; see that Bundle's doc comment). Originally
  // (the `9e0af36f` fold) this file instead stored `line - 1` per entry
  // (`linePrevAs`/`linePrevBs`, now DELETED) and tested `linePrev === qLine`, i.e.
  // assumed the query's second half is always exactly `qLine + 1` -- true only
  // for a same-page line-crossing access (the page offset survives translation
  // verbatim, so slot B really does land at `qLine + 1` there), but WRONG for a
  // page-crossing access, whose second half is independently DTLB-translated and
  // can be any physical line. Comparing directly against the query's own
  // `paddrB(31:4)` is correct in BOTH cases (for a same-page split it simply
  // equals `qLine + 1` again) and removes the `linePrev` registers entirely --
  // the second-line target now arrives pre-translated in the query itself, no
  // per-entry storage needed for it at all.
  //
  // These two REPLACE `paddrHiAs`/`paddrHiBs`/`nbytesAs`/`nbytesBs` outright
  // (the `9e0af36f` fold), and `linePrevAs`/`linePrevBs` (this task) on top:
  // `nbytesA/B` are no longer needed at ALL, because `full` (exact addr+size
  // match) is now `maskA === qSpan`, which is equivalent: both masks are a
  // contiguous run starting at the access offset, so mask equality IS
  // offset-plus-length equality.
  //
  // A sim-only shadow of the deleted magnitude-comparator registers, plus a
  // per-entry equivalence assert re-evaluating an interval-overlap reference
  // test (now covering the paddrB-based spill case too) against them every
  // cycle, is declared immediately below and checked after `perEntry`. That is
  // the permanent, executable proof of everything claimed in this comment.
  val maskAs     = Vec.fill(depth)(RegInit(B(1, 16 bits)))
  // optional slot B (second half of a split store)
  val validBs   = Vec.fill(depth)(RegInit(False))
  val paddrBs   = Vec.fill(depth)(RegInit(U(0, 32 bits)))
  // (strbB/lineDataB are DERIVED at drain time -- see the drain-present mux below;
  // no per-entry storage needed at all now, task #252.)
  val maskBs     = Vec.fill(depth)(RegInit(B(0, 16 bits)))

  // ── Forward-overlap equivalence tripwire: SIM-ONLY shadow of the OLD structure ──
  // These four reproduce, bit for bit, the `nbytesAs`/`nbytesBs`/`paddrHiAs`/
  // `paddrHiBs` Reg-Vecs the mask scheme above replaced, written from the identical
  // alloc payload with the identical `+` semantics (32-bit, carry-out dropped). The
  // per-entry assertions further down then re-evaluate the ORIGINAL magnitude-
  // comparator overlap test against them and pin the new derivation to it on EVERY
  // cycle, for EVERY resident entry -- which is the executable form of the equivalence
  // proof for a piece of logic whose failure mode is silent data corruption, not a
  // crash. It stays permanently, so the two can never drift apart.
  //
  // Elaborated only under `includeSimulation` (see M68kSim.scala) => zero synthesis
  // cost: like RobPlugin's `fs*` Slice-C shadows, every one of these vals is null in a
  // synth/GenVerilog build and must never be referenced outside a
  // `GenerationFlags.simulation` block.
  val fsNbytesA  = GenerationFlags.simulation { Vec.fill(depth)(RegInit(U(1, 3 bits))) }
  val fsNbytesB  = GenerationFlags.simulation { Vec.fill(depth)(RegInit(U(0, 3 bits))) }
  val fsPaddrHiA = GenerationFlags.simulation { Vec.fill(depth)(RegInit(U(0, 32 bits))) }
  val fsPaddrHiB = GenerationFlags.simulation { Vec.fill(depth)(RegInit(U(0, 32 bits))) }
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
  // ---- ORPHANED entry (Part 127, defect SS8(b) of Part 126) ----------------------
  // Set on the ONE entry a flush deliberately KEEPS via `headDrainInFlight` (see the
  // flush block's doc comment): a precise, still-UNCOMMITTED head whose drain has
  // already been accepted by DcachePlugin, so its physical write is irrevocably in
  // flight and the ring bookkeeping must be allowed to unwind. What that comment did
  // NOT account for is that the flush ALSO destroys the entry's ROB slot -- the ROB
  // flush is pointer-only (`tail := head`), so its robId is immediately re-allocatable.
  // Two consequences, both real:
  //   1. LAUNCH. For a SPLIT entry, slot B has not been presented yet, and its only
  //      launch gate is `robIds(head) === io.robHeadIn` -- which the dead ROB entry can
  //      never satisfy. The entry can never finish, and (`empty` staying false) nothing
  //      behind it can ever drain: the same permanent wedge Part 37 fixed one instance
  //      of. `headPreciseReady` below therefore gives an orphan its own launch arm.
  //   2. COMPLETION. `io.sqCompletion.payload := robIds(head)` would mark whatever
  //      brand-new instruction inherited that robId as COMPLETE -- it would retire
  //      without ever executing -- and the LS EU would replay this dead store's An/NZVC
  //      write-back onto that instruction's rename. `io.sqCompletionOrphan` flags the
  //      pop so LsEuPlugin's deferred-replay stage consumes the pendMem slot and drives
  //      NOTHING to the ROB or the PRF.
  val orphans    = Vec.fill(depth)(RegInit(False))

  val head = RegInit(U(0, ptrW bits))   // oldest
  val tail = RegInit(U(0, ptrW bits))   // next free
  val count = RegInit(U(0, log2Up(depth + 1) bits))

  // ---- access-size in bytes ----
  def sizeBytes(s: Size.C): UInt = {
    val n = UInt(3 bits); n := 1
    switch(s) { is(Size.BYTE) { n := 1 }; is(Size.WORD) { n := 2 }; is(Size.LONG) { n := 4 } }
    n
  }

  // ---- byte-lane span mask: the forward-overlap primitive -------------------
  // A 19-bit span over the byte lanes of ONE 16-byte cache line PLUS a 3-lane spill
  // into the following line:
  //     bit j      (j <  16)  = "this access covers byte j of line (addr >> 4)"
  //     bit 16 + k (k <   3)  = "this access covers byte k of line (addr >> 4) + 1"
  // `off` is the line offset (addr(3 downto 0)); the covered byte count is given as
  // four thermometer predicates (n>=1, n>=2, n>=3, n>=4) rather than as a number, so a
  // caller holding a `Size` enum can supply them directly without first decoding a
  // byte count. Only counts 1..4 are representable, which is all this design ever
  // produces (BYTE/WORD/LONG, and a split slot is at most 3 bytes -- see the alloc
  // assert). 3 spill lanes suffice for the same reason: a 4-byte access at the worst
  // offset (13) spills exactly 1..3 bytes.
  //
  // Written as an explicit per-lane expression, NOT as `(mask << off)`: a barrel
  // shifter is log2(16) = 4 mux levels deep, whereas this is a 4->16 one-hot decode
  // (one LUT4 per lane) followed by one 6-input term per lane
  // {oh(j), oh(j-1), oh(j-2), oh(j-3), ge2, ge4} -- i.e. exactly two LUT levels,
  // which matters because the query-side instance sits on the critical path.
  def spanMask(off: UInt, nz: Bool, ge2: Bool, ge3: Bool, ge4: Bool): Bits = {
    val oh = UIntToOh(off, 16)
    val m  = Bits(19 bits)
    def lane(k: Int): Bool = if (k >= 0 && k < 16) oh(k) else False
    for (j <- 0 until 19)
      m(j) := (lane(j) && nz) || (lane(j - 1) && ge2) ||
              (lane(j - 2) && ge3) || (lane(j - 3) && ge4)
    m
  }

  /** spanMask for an entry, whose covered byte count is a real 1..4 number. */
  def spanMaskOfCount(off: UInt, n: UInt): Bits =
    spanMask(off, n =/= 0, n >= 2, n >= 3, n >= 4)

  /** spanMask for the query, whose count comes straight from the size enum
    * (1 / 2 / 4 -- never 3, so `ge3` collapses onto `ge4`). */
  def spanMaskOfSize(off: UInt, s: Size.C): Bits = {
    val isLong = s === Size.LONG
    spanMask(off, True, s =/= Size.BYTE, isLong, isLong)
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
    val ageA = (a - io.robHeadIn)(m68k040.Global.ROB_ID_W_DEFAULT - 1 downto 0)
    val ageB = (b - io.robHeadIn)(m68k040.Global.ROB_ID_W_DEFAULT - 1 downto 0)
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
  // ORPHAN arm (Part 127): an entry the flush KEPT has no ROB entry left, so
  // `robIds(head) === io.robHeadIn` can never be satisfied -- and, for the same
  // reason, there is no ROB entry for an interrupt/trace to preempt in favour of, so
  // `irqPreemptPendingIn` must not gate it either (the commit-side exception FSM
  // WAITS for the SQ to go empty, so holding an orphan back for a pending preempt is
  // a deadlock, not a precision measure). Its slot A has already hit memory; the only
  // correct thing left to do is let it finish.
  val headOrphan = valids(head) && orphans(head) && precises(head)
  val headPreciseReady = valids(head) && hasData(head) && !committed(head) && precises(head) &&
                         Mux(headOrphan, True,
                             (robIds(head) === io.robHeadIn) && io.robHeadValidIn &&
                             !io.irqPreemptPendingIn) &&
                         !io.flush

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
  val sendCommitted = valids(sendPtr) && hasData(sendPtr) && committed(sendPtr) && !io.flush
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
  // aligned fast path, unchanged), or the optional contained subword case below.
  // Any overlap with slot B, or an unsupported partial overlap,
  // STALLS (the cross load waits for the store to drain to memory then re-reads).
  val q          = io.fwd.query
  // ── Query-side geometry: line + 19-bit byte-lane span. NO ARITHMETIC. ─────────
  // `qLine` is a pure wire slice; `qSpan` is the two-LUT-level decode documented at
  // `spanMask`. This is what replaces the old `qHi = q.paddr + qBytes` 32-bit adder
  // that `b8346d1b` measured at 3 CARRY8 levels / 0.487ns of route at fanout 32 on
  // the design's worst post-route path.
  val qLine      = q.paddr(31 downto 4)
  val qSpan      = spanMaskOfSize(q.paddr(3 downto 0), q.size)
  val qMaskThis  = qSpan(15 downto 0)    // lanes the query covers in its OWN line
  val qMaskNext  = qSpan(18 downto 16)   // lanes it spills into the SECOND half
  // Line of the query's SECOND half (cross-page forward-hazard fix). Only
  // meaningful when `q.splitB` -- see `SqFwdQuery`'s doc comment. Provably equal
  // to `qLine + 1` whenever the split is same-page, and independent of `qLine`
  // entirely when it is page-crossing.
  val qLineB     = q.paddrB(31 downto 4)
  val perEntry   = for (i <- 0 until depth) yield new Area {
    // committed(i): the ROB already retired this store -- unconditionally older
    // than any live query (see olderThan's comment above, case 1). Otherwise
    // fall back to the head-anchored compare (case 2).
    val ent      = valids(i) && (committed(i) || olderThan(robIds(i), q.robId))
    // Slot lines. `lineA`/`lineB` are ALSO the operands of the `sameLine` term at the
    // bottom of this Area, so the two 28-bit equality comparators below are shared
    // with it rather than being new logic.
    val lineA    = paddrs(i)(31 downto 4)
    val lineB    = paddrBs(i)(31 downto 4)
    val sameLineA = lineA === qLine
    val sameLineB = lineB === qLine
    // ── Overlap by line-equality + byte-lane mask intersection ────────────────
    // An entry slot is confined to ONE line (invariant asserted at alloc), so it can
    // meet the query in exactly two ways: inside the query's own line, or inside the
    // query's SECOND half (when the query spills across a line -- possibly a page --
    // boundary). The second-half test compares against the query's OWN
    // independently-translated `qLineB` (cross-page forward-hazard fix), not an
    // assumed `qLine + 1`: for a same-page split `qLineB` already equals `qLine + 1`,
    // and for a page-crossing split it is wherever the real translation landed.
    // `q.splitB` gates the term off entirely for a non-split query (redundant with
    // `qMaskNext` already being provably zero there, kept for clarity/robustness).
    // `maskA(2 downto 0)` is the only part that can meet a spill: the spill lands on
    // lanes 0..2 of the second half by construction.
    val geomA    = (sameLineA && (maskAs(i) & qMaskThis).orR) ||
                   (q.splitB && (lineA === qLineB) && (maskAs(i)(2 downto 0) & qMaskNext).orR)
    val overlapA = ent && geomA
    val geomB    = (sameLineB && (maskBs(i) & qMaskThis).orR) ||
                   (q.splitB && (lineB === qLineB) && (maskBs(i)(2 downto 0) & qMaskNext).orR)
    val overlapB = ent && validBs(i) && geomB
    val overlap  = overlapA || overlapB
    // full overlap = exact addr+size against slot A of a NON-split store. Both masks
    // are a CONTIGUOUS run starting at the access offset, so mask equality is exactly
    // "same offset AND same length"; combined with line equality that is `aLo === qLo
    // && nbytesA === qBytes`. Comparing the FULL 19-bit span (the entry mask
    // zero-extended, since an entry never spills) additionally forces `qMaskNext === 0`
    // -- i.e. a line-crossing query can never be a full forward, which is exactly what
    // the old form gave too (an entry at the same address with the same length would
    // itself have been split, hence `validB`, hence excluded).
    val geomFull = !validBs(i) && sameLineA && (maskAs(i).resize(19) === qSpan)
    // Deliberately limited to aligned LONG producers: byte extraction is then
    // shared after winner selection, with no per-entry barrel shifter or adder.
    val geomSubword = if(subwordForwarding) {
      !validBs(i) && !q.splitB && sizes(i) === Size.LONG &&
        paddrs(i)(1 downto 0) === 0 && sameLineA &&
        paddrs(i)(3 downto 2) === q.paddr(3 downto 2) &&
        (q.size === Size.BYTE || (q.size === Size.WORD && q.paddr(1 downto 0) =/= 3))
    } else False
    val full     = ent && (geomFull || geomSubword) && hasData(U(i, ptrW bits))
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
    // (HISTORICAL from here up: the `qHi` adder that paragraph discusses no longer
    // exists at all -- the byte-lane-mask rework above deleted it, which was exactly
    // the follow-up `b8346d1b` left standing. The hoisting experiment's conclusion
    // still holds for the remaining duplicated query-side terms.)
    //
    // (`lineA`/`lineB`/`sameLineA`/`sameLineB`/`qLine` are declared at the top of this
    // Area now -- the overlap test shares the very same equality comparators.)
    //
    // Cross-page forward-hazard fix (this task) extends this term too: a split
    // (twoAccess) LOAD performs TWO separate L1D refills, one per half, each capable
    // of triggering the exact same refill-stale-line hole this stall exists to close.
    // `sameLineA`/`sameLineB` above only ever compared against the query's OWN line
    // (`qLine`) -- silently missing the case where it is the query's SECOND half
    // (`qLineB`, independently translated, no fixed relationship to `qLine`) that
    // shares a line with a still-resident WRITETHROUGH entry. Same root cause as the
    // `geomA`/`geomB` byte-overlap gap above, same fix shape.
    val sameLineA2 = q.splitB && (lineA === qLineB)
    val sameLineB2 = q.splitB && (lineB === qLineB)
    val sameLine = ent && (
      (sameLineA && (cacheModes(i) =/= CacheMode.COPYBACK)) ||
      (validBs(i) && sameLineB && (cacheModesB(i) =/= CacheMode.COPYBACK)) ||
      (sameLineA2 && (cacheModes(i) =/= CacheMode.COPYBACK)) ||
      (validBs(i) && sameLineB2 && (cacheModesB(i) =/= CacheMode.COPYBACK))
    )
  }
  // ── Forward-overlap equivalence tripwire: pin the mask scheme to an interval model ──
  // Re-evaluates an independent magnitude/interval-overlap reference model against
  // the sim-only shadow registers, and asserts the line+mask derivation agrees, for
  // every RESIDENT entry, every cycle -- not only when a query is actually live, since
  // `io.fwd.query` is driven unconditionally from the LS-EU and the extra coverage is
  // free. The `ent` age qualifier is deliberately NOT applied: it is untouched by
  // either the `9e0af36f` fold or this task, and excluding it makes the check
  // strictly sharper.
  //
  //     ovAOwnRef   = (qLo   < aHi) && (aLo < qHi)     aHi = paddr  + nbytesA
  //     ovBOwnRef   = (qLo   < bHi) && (bLo < qHi)     bHi = paddrB + nbytesB
  //     full        = ovAOwnRef && !validB && (aLo === qLo) && (nbytesA === qBytes)
  // is EXACTLY the `9e0af36f` reference model (own-line only). THIS TASK extends it
  // with a second, independent interval test anchored at the query's OWN
  // `q.paddrB` (never at an assumed `q.paddr + qBytes` continuation, which is the
  // precise thing that was wrong for a page-crossing split):
  //     ovASpillRef = splitB && (qLoB < aHi) && (aLo < qHiB)   qHiB = paddrB + qSpillBytes
  //     ovBSpillRef = splitB && (qLoB < bHi) && (bLo < qHiB)
  //     ovARef = ovAOwnRef || ovASpillRef       ovBRef = ovBOwnRef || ovBSpillRef
  // `qSpillBytes` is the popcount of `qMaskNext` (0..3): the real byte count the
  // query's spill lanes cover, independent of how those lanes are represented.
  //
  // ONE DELIBERATE, DOCUMENTED DEVIATION, hence the `*Wrap` guards. The reference
  // model computes `aHi`/`qHi`/`qHiB` with SpinalHDL's 32-bit `+` (carry-out
  // dropped), so an access touching the last bytes of the 32-bit address space
  // wrapped its upper bound to a SMALL value and the `<` test then reported NO
  // overlap -- a missed forward/hazard for e.g. a byte store at 0xFFFFFFFF. The mask
  // form is modular in the line number and has no such hole: it reports the overlap
  // correctly. That is strictly safer (an extra detected hazard is a stall or an
  // exact full forward, never wrong data), but it IS a difference, so the assert
  // excludes the wrapping case(s) rather than pretending they do not exist. Nothing
  // in this design places memory there; if a future test does, this comment is the
  // reason the tripwire stays quiet.
  GenerationFlags.simulation {
    val qBytesRef    = sizeBytes(q.size)
    val qHiRef       = q.paddr + qBytesRef
    val qWrap        = (q.paddr +^ qBytesRef).msb
    val qSpillBytes  = CountOne(qMaskNext).resize(3 bits)
    val qHiBRef      = q.paddrB + qSpillBytes
    val qWrapB       = (q.paddrB +^ qSpillBytes).msb
    for (i <- 0 until depth) {
      val e         = perEntry(i)
      val aLoRef    = paddrs(i)
      val bLoRef    = paddrBs(i)
      val aWrap     = (aLoRef +^ fsNbytesA(i)).msb
      val bWrap     = (bLoRef +^ fsNbytesB(i)).msb
      val ovAOwnRef = (q.paddr < fsPaddrHiA(i)) && (aLoRef < qHiRef)
      val ovBOwnRef = (q.paddr < fsPaddrHiB(i)) && (bLoRef < qHiRef)
      val ovASpillRef = q.splitB && (q.paddrB < fsPaddrHiA(i)) && (aLoRef < qHiBRef)
      val ovBSpillRef = q.splitB && (q.paddrB < fsPaddrHiB(i)) && (bLoRef < qHiBRef)
      val ovARef  = ovAOwnRef || ovASpillRef
      val ovBRef  = ovBOwnRef || ovBSpillRef
      val fullRef = ovAOwnRef && !validBs(i) && (aLoRef === q.paddr) &&
                    (fsNbytesA(i) === qBytesRef)
      val bSpillWrapOk = !q.splitB || !qWrapB
      when(valids(i) && !qWrap && bSpillWrapOk) {
        when(!aWrap) {
          assert(e.geomA === ovARef,
            "StoreQueue: slot-A line+mask overlap disagrees with the interval-overlap " +
            "shadow -- store-to-load forwarding geometry has drifted",
            FAILURE)
          assert(e.geomFull === fullRef,
            "StoreQueue: slot-A full-forward line+mask test disagrees with the " +
            "interval-overlap shadow -- a forward would return the wrong bytes",
            FAILURE)
        }
        when(validBs(i) && !bWrap) {
          assert(e.geomB === ovBRef,
            "StoreQueue: slot-B line+mask overlap disagrees with the interval-overlap " +
            "shadow -- split-store forwarding geometry has drifted",
            FAILURE)
        }
      }
    }
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
  val ageDist    = Vec((0 until depth).map(i => (q.robId - robIds(i))(m68k040.Global.ROB_ID_W_DEFAULT - 1 downto 0)))
  case class Cand() extends Bundle {
    val valid = Bool(); val full = Bool(); val dist = UInt(m68k040.Global.ROB_ID_W_DEFAULT bits); val data = Bits(32 bits)
    val subword = Bool()
  }
  val cands = (0 until depth).map { i =>
    val c = Cand()
    c.valid := perEntry(i).overlap
    c.full  := perEntry(i).full
    c.dist  := ageDist(i)
    c.data  := datas(i)
    c.subword := perEntry(i).geomSubword
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
  if(subwordForwarding) {
    val byteData = Vec(best.data(31 downto 24), best.data(23 downto 16),
      best.data(15 downto 8), best.data(7 downto 0))(q.paddr(1 downto 0))
    val wordData = Vec(best.data(31 downto 16), best.data(23 downto 8),
      best.data(15 downto 0), B(0, 16 bits))(q.paddr(1 downto 0))
    when(best.subword) {
      io.fwd.rsp.data := Mux(q.size === Size.BYTE, byteData.resize(32), wordData.resize(32))
    }
  }
  io.fwd.rsp.serial := serialStall
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
  if(reserveLateStore) {
    io.allocSlot := tail
    when(io.publish.valid && !io.flush) {
      val slot = io.publish.slot
      datas(slot) := io.publish.data
      dataReady(slot) := True
      assert(valids(slot) && !dataReady(slot) && robIds(slot) === io.publish.robId,
        "StoreQueue: late publication lost its reserved owner", FAILURE)
    }
    GenerationFlags.simulation {
      when(io.alloc.valid && io.reserveOnly && !io.flush) {
        assert(!io.alloc.precise, "StoreQueue: precise store cannot reserve without data", FAILURE)
      }
      for(i <- 0 until depth) {
        when(valids(i) && !dataReady(i)) {
          assert(!committed(i), "StoreQueue: unfilled reservation committed", FAILURE)
          assert(!(io.commit.valid && io.commit.payload === robIds(i)) &&
                 !(io.commitB.valid && io.commitB.payload === robIds(i)),
            "StoreQueue: commit notice preceded reserved data publication", FAILURE)
        }
      }
    }
  }
  when(io.alloc.valid && !io.flush) {
    if(reserveLateStore) dataReady(tail) := !io.reserveOnly
    valids(tail)    := True
    committed(tail) := False
    robIds(tail)    := io.alloc.payload.robId
    paddrs(tail)    := io.alloc.payload.paddr
    datas(tail)     := io.alloc.payload.data
    sizes(tail)     := io.alloc.payload.size
    useStrbAs(tail) := io.alloc.payload.useStrbA
    validBs(tail)   := io.alloc.payload.validB
    paddrBs(tail)   := io.alloc.payload.paddrB
    // PRE-REGISTER the forward-overlap geometry (the `9e0af36f` fold): the byte-lane
    // mask of each slot within its resident line. All of it is a pure function of the
    // alloc payload, evaluated once per cycle here instead of 8x per query in the
    // binding p4Ctx_fwdHit -> p4Ctx_fwdData path. (The entry's own `line - 1`
    // registers this task deleted -- see the class-level comment above -- are gone;
    // the query now carries its own second-half address instead.)
    // Slot A: `nbytesA` is 1..4 and never crosses the line (asserted below).
    maskAs(tail)     := spanMaskOfCount(io.alloc.payload.paddr(3 downto 0),
                                        io.alloc.payload.nbytesA)(15 downto 0)
    // Slot B: line-aligned by construction (LsEuPlugin's `addrB = (va & ~15) + 16`,
    // whose page offset survives translation verbatim), 1..3 bytes. Forced to an
    // empty mask when this is not a split store, so the mask alone can never claim
    // an overlap for a slot that does not exist.
    maskBs(tail)     := Mux(io.alloc.payload.validB,
                            spanMaskOfCount(io.alloc.payload.paddrB(3 downto 0),
                                            io.alloc.payload.nbytesB)(15 downto 0),
                            B(0, 16 bits))
    // (strbA/lineDataA/strbB/lineDataB no longer stored -- derived at drain, task #252.)
    vaddrAs(tail)     := io.alloc.payload.vaddr
    vaddrBs(tail)     := io.alloc.payload.vaddrB
    cacheModes(tail)  := io.alloc.payload.cacheMode
    cacheModesB(tail) := io.alloc.payload.cacheModeB
    supervisors(tail) := io.alloc.payload.supervisor
    precises(tail)    := io.alloc.payload.precise
    orphans(tail)     := False   // fresh entry: never inherit a prior occupant's orphan mark
    // Drive the sim-only shadow of the deleted magnitude-comparator geometry, in the
    // deleted code's exact form (`paddr + nbytes`, 32-bit, carry-out dropped).
    GenerationFlags.simulation {
      fsNbytesA(tail)  := io.alloc.payload.nbytesA
      fsNbytesB(tail)  := io.alloc.payload.nbytesB
      fsPaddrHiA(tail) := io.alloc.payload.paddr  + io.alloc.payload.nbytesA
      fsPaddrHiB(tail) := io.alloc.payload.paddrB + io.alloc.payload.nbytesB
    }
    tail := tail + 1
  }

  // ── The "each slot lives inside ONE 16-byte cache line" invariant, MACHINE-CHECKED ──
  // The whole line-equality + mask-intersection scheme rests on this, so it is asserted
  // at the boundary it enters the design rather than argued in a comment. It holds by
  // construction in LsEuPlugin: `nbytesA = twoAccess ? (16 - stOff) : sizeBytes`, and
  // `twoAccess` itself requires `stOff + sizeBytes > 16` with sizeBytes <= 4, i.e.
  // stOff >= 13, so `16 - stOff` is 1..3 and never exceeds 3 bits; slot B is the
  // line-aligned `addrB = (va & ~15) + 16` whose page offset survives translation
  // verbatim, carrying the 1..3 spilled bytes. A future EA/size change that broke
  // either property would silently under-detect forwarding hazards, so this fires
  // loudly instead.
  GenerationFlags.simulation {
    when(io.alloc.valid && !io.flush) {
      assert(io.alloc.payload.nbytesA =/= 0 && io.alloc.payload.nbytesA <= 4,
        "StoreQueue: slot-A byte count outside 1..4 -- byte-lane mask cannot represent it",
        FAILURE)
      assert((io.alloc.payload.paddr(3 downto 0) +^ io.alloc.payload.nbytesA) <= 16,
        "StoreQueue: slot A crosses a 16-byte cache line -- the forward byte-lane mask " +
        "assumes each SQ slot is line-confined",
        FAILURE)
      when(io.alloc.payload.validB) {
        assert(io.alloc.payload.nbytesB =/= 0 && io.alloc.payload.nbytesB <= 4,
          "StoreQueue: slot-B byte count outside 1..4 -- byte-lane mask cannot represent it",
          FAILURE)
        assert((io.alloc.payload.paddrB(3 downto 0) +^ io.alloc.payload.nbytesB) <= 16,
          "StoreQueue: slot B crosses a 16-byte cache line -- the forward byte-lane mask " +
          "assumes each SQ slot is line-confined",
          FAILURE)
      }
    }
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
  // Qualifier for the pop that `sqCompletion` announces (Part 127). Combinational off
  // `orphans(head)`, valid on exactly the cycles `sqCompletion.valid` is.
  io.sqCompletionOrphan   := orphans(head)
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
  //
  // Real-hardware hang (BUG_calibration_word_misplaced_0d00.md Part 37, task
  // following Part 36's S_DRAIN/sqDrained investigation): a SECOND, DIFFERENT
  // trigger for the exact same "phantom entry never drains -> empty stays false
  // forever" failure mode the `popsHead` fix below already closed one instance of.
  //
  // `committed(i)` alone is NOT a complete "must not squash" test. This file's own
  // §4.1 doc comments elsewhere argue a flush can never race an in-flight PRECISE
  // drain because a branch-mispredict/exception flush can only originate from a
  // RETIRING head, and an uncommitted precise entry blocks retire of everything at
  // or behind it — true for `branchRedirect`/`exc.redirectValid`, but
  // `RobPlugin.doFlushReg` (the source `sqFlush`/`io.flush` is wired from,
  // FullCoreSynth.scala) ALSO fires from `debugRecoverEnter`/`debugPcApply` — a
  // JTAG-driven halt/step/PC-apply — neither of which is retire-gated the same way.
  // Those CAN land on any cycle, including mid-drain of a precise (split) store
  // whose slot-A half has ALREADY been ACCEPTED by DcachePlugin (`io.drain.fire`
  // already fired) — an irrevocable, already-in-flight physical bus write. Squashing
  // `valids(head)` at that moment does not (cannot) cancel that write; it only
  // orphans the SQ's own bookkeeping: the eventual real drainAck for that already-
  // accepted half does not pop the entry (validBs(head)/ackPhaseB drive it into the
  // "advance to slot B" arm instead, since neither was told the entry died), so
  // `head`/`sendPtr`/`ackPhaseB` never resolve — the ring is permanently wedged on
  // that now-dead index, and NOTHING (not even a brand-new, ordinary committed
  // store) can ever drain again, matching the real-hardware `sqDrained`-stuck-false
  // symptom exactly. Directed repro: `StoreQueueSpec`'s "BUG_calibration_word Part
  // 37" test.
  //
  // Fix: extend "must keep" with "the head entry has an outstanding drain already
  // ACCEPTED by DcachePlugin" — `drainBusy` (an accepted half awaiting its ack) OR
  // `ackPhaseB` (slot A already acked, slot B not yet presented/acked) — which by
  // construction can only ever be true of the CURRENT head (a precise drain's
  // `noAccepted`/`sendAtHead` launch gates make it the sole occupant of the drain
  // pipe; a non-precise/pipelined send can only start once ALREADY committed, so it
  // is always covered by the plain `committed(i)` term and never needs this). This
  // lets an in-flight precise drain that a non-retire-gated flush caught mid-way
  // run to its natural completion — its physical effect already left the CPU, so
  // there is nothing left to squash; the SQ's own ring bookkeeping (`head`/
  // `sendPtr`/`ackPhaseB`) simply needs to be allowed to unwind normally instead of
  // being abandoned mid-sequence.
  val headDrainInFlight = valids(head) && !committed(head) && (drainBusy || ackPhaseB)
  // An uncommitted entry can only ever have an accepted drain half if it is PRECISE:
  // both non-precise launch arms (`sendPipelined`, `sendSerialReady`) require
  // `sendCommitted`. Stated explicitly so the two Part-127 outputs below do not rest
  // on that being re-derived at every read site.
  val headKeptByFlush = io.flush && headDrainInFlight && precises(head) && !terminalAck
  io.flushKeptPrecise := headKeptByFlush
  when(io.flush) {
    // A drainAck this same cycle pops the head entry (single-slot, or slot B of a
    // split). That popped entry must NOT be counted as kept — otherwise the flush's
    // keepCount over-counts by one and tail lands one past the real youngest entry,
    // leaving a PHANTOM entry that never drains (empty stays false forever -> the
    // commit-side exception FSM hangs at E_DRAIN). Exclude the popped head here.
    val popsHead = terminalAck
    val keep = Vec(Bool(), depth)
    for (i <- 0 until depth) {
      val isHead = U(i, log2Up(depth) bits) === head
      keep(i) := valids(i) && (committed(i) || (isHead && headDrainInFlight)) && !(popsHead && isHead)
    }
    for (i <- 0 until depth) when(!keep(i)) { valids(i) := False }
    // new tail = head' + (number of committed-live entries), where head' accounts for
    // the coincident pop (head advances by 1 if popsHead). Committed entries are the
    // oldest contiguous run (commit is in-order), so this is exact.
    val keepCount = CountOne(keep)
    val headAfter = Mux(popsHead, head + 1, head)
    tail := (headAfter + keepCount).resized
    // Part 127: the entry kept by the `headDrainInFlight` arm (and ONLY that one --
    // every other kept entry is `committed`, i.e. its instruction has already retired
    // and its ROB slot legitimately no longer matters) loses its ROB entry to this
    // same flush. Mark it so `headPreciseReady` can still launch its slot B and so its
    // eventual completion is discarded instead of applied to whatever instruction
    // inherits its robId.
    when(headKeptByFlush) { orphans(head) := True }
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
  orphans.foreach(_.simPublic())   // Part 127
  // Part 127: these two are read by directed tests through a NESTED instance (the
  // LS-EU DUT), where a bare IO port is not reachable -- same reason `io.flush` above
  // carries one.
  io.flushKeptPrecise.simPublic(); io.sqCompletionOrphan.simPublic()
  io.drain.valid.simPublic(); io.drain.ready.simPublic(); io.drainAck.simPublic(); io.flush.simPublic()
  // Task #139 mechanism #2: catch the ORIGINATING alloc of any SQ entry, so a
  // later-observed stuck head can be traced back to the actual allocating PC
  // even after the ROB has reused that robId number for a newer instruction.
  io.alloc.valid.simPublic(); io.alloc.payload.robId.simPublic(); io.alloc.payload.paddr.simPublic()
  io.alloc.payload.vaddr.simPublic()   // store-address tripwire tap (test/lockstep/StoreAddrTripwire.scala)
  // Task P2.5 post-review fix: expose the RAW at-head drain-confirm pulse (before
  // LsEuPlugin's own apply/collision-retry arbitration) so a directed test can
  // distinguish "the SQ confirmed the drain this cycle" from "the replay actually
  // applied it" -- the whole point of the `liveCompletionFires` collision test.
  io.sqCompletion.valid.simPublic(); io.sqCompletion.payload.simPublic()
}
