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
  val useStrbA  = Bool()              // drain slot A with explicit strobe (split) vs {data,size}
  val strbA     = Bits(16 bits)       // line-relative byte strobe (when useStrbA)
  val lineDataA = Bits(128 bits)      // line-aligned merge data (when useStrbA)
  // ---- slot B (optional second slot of a SPLIT store; one entry = one atomic store) ----
  val validB    = Bool()
  val paddrB    = UInt(32 bits)
  val vaddrB    = UInt(32 bits)   // logical address of slot B (cross-line/page)
  val nbytesB   = UInt(3 bits)
  val strbB     = Bits(16 bits)
  val lineDataB = Bits(128 bits)
  // ---- cache-mode / precision classification (captured at translate) ----
  val cacheMode  = CacheMode()
  val supervisor = Bool()
  val precise    = Bool()   // !fast -- withhold ROB completion; drain at head, awaited
}

case class SqFwdQuery() extends Bundle {
  val robId = UInt(6 bits)
  val paddr = UInt(32 bits)
  val size  = Size()
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
  *  - drain  : the OLDEST entry, once committed, drives the L1D write-through
  *             (one/cycle, in program order) then pops.
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
    val drain    = master(Flow(DStoreCmd()))
    // Memory-write acknowledge for the in-flight drain. The oldest committed entry
    // is PRESENTED on `drain` for exactly one cycle (latched by the D-cache), then
    // HELD resident (still forwarding) until `drainAck` confirms the write-through
    // landed in memory. Popping on drain-issue (not ack) would leave a window where
    // the store left the SQ but its memory write was not yet visible — a younger
    // load that MISSED L1D could refill stale memory. Holding until ack closes it.
    val drainAck = in(Bool())
    // Non-OKAY AXI B response for the store currently occupying the drain port
    // (sampled the SAME cycle as drainAck -- DcachePlugin's storeAck/storeErr are
    // driven off the identical `axi.b.valid && axi.b.ready` handshake, so they are
    // always co-timed).
    val drainErr = in(Bool())
    val empty    = out(Bool())   // no valid entry AND no drain in flight
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
  val strbAs    = Vec.fill(depth)(RegInit(B(0, 16 bits)))
  val lineDataAs= Vec.fill(depth)(RegInit(B(0, 128 bits)))
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
  val strbBs    = Vec.fill(depth)(RegInit(B(0, 16 bits)))
  val lineDataBs= Vec.fill(depth)(RegInit(B(0, 128 bits)))
  // PRE-REGISTERED slot-B upper byte-range bound (= paddrB + nbytesB), same rationale
  // and same 32-bit width (bit-identical to the old `paddrBs(i) + nbytesBs(i)`).
  val paddrHiBs = Vec.fill(depth)(RegInit(U(0, 32 bits)))
  // drain phase of the head entry: false = slot A, true = slot B (split only)
  val drainPhaseB = RegInit(False)

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
  // True iff (b - a) in (0, 32) -- a sits "behind" b within half the 6-bit window.
  def olderThan(a: UInt, b: UInt): Bool = {
    val diff = (b - a)(5 downto 0)
    (diff =/= 0) && (diff < U(32, 6 bits))
  }

  // ---- drain: oldest entry, valid & committed -> write-through, HOLD until ack ----
  // `drainBusy` is set the cycle we present a store half on `io.drain` (the D-cache
  // latches it that cycle) and cleared on `io.drainAck`. While busy we do NOT
  // re-present and we do NOT advance the phase/pop — the entry stays resident so
  // SQ-forwarding covers the entire drain window.
  //
  // A SPLIT store (validB) drains ATOMICALLY: slot A first (drainPhaseB=false),
  // then slot B (drainPhaseB=true). The entry pops only after BOTH halves ACK.
  val drainBusy = RegInit(False)
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
  val headReady = (valids(head) && committed(head) && !io.flush) || headPreciseReady
  val drainIssue = headReady && !drainBusy   // one-cycle present to the D-cache
  io.drain.valid := drainIssue
  // Present slot A (phase false) or slot B (phase true).
  when(!drainPhaseB) {
    io.drain.payload.paddr    := paddrs(head)
    io.drain.payload.data     := datas(head)
    io.drain.payload.size     := sizes(head)
    io.drain.payload.useStrb  := useStrbAs(head)
    io.drain.payload.strb     := strbAs(head)
    io.drain.payload.lineData := lineDataAs(head)
    io.drain.payload.cacheMode := cacheModes(head)
    io.drain.payload.precise  := precises(head)
  } otherwise {
    io.drain.payload.paddr    := paddrBs(head)
    io.drain.payload.data     := B(0, 32 bits)
    io.drain.payload.size     := Size.LONG()
    io.drain.payload.useStrb  := True
    io.drain.payload.strb     := strbBs(head)
    io.drain.payload.lineData := lineDataBs(head)
    io.drain.payload.cacheMode := cacheModes(head)
    io.drain.payload.precise  := precises(head)
  }

  // ---- forwarding (combinational), DUAL-SLOT ----
  // A younger load checks BOTH slot A [paddrA, +nbytesA) and (if validB) slot B
  // [paddrB, +nbytesB) of every older store. A full single-slot forward is taken
  // ONLY for an exact addr+size match against a NON-split store's slot A (the
  // aligned fast path, unchanged). Any overlap with slot B, or a partial overlap,
  // STALLS (the cross load waits for the store to drain to memory then re-reads).
  val q          = io.fwd.query
  val qBytes     = sizeBytes(q.size)
  val perEntry   = for (i <- 0 until depth) yield new Area {
    val ent      = valids(i) && olderThan(robIds(i), q.robId)
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
    // SAME-CACHE-LINE hazard (16-byte line; offBits=4). A younger load that MISSES the
    // L1D refills the WHOLE line from memory. If an OLDER store to the SAME line is still
    // in the SQ (not yet written through to memory), that refill would cache a STALE line
    // (the store's bytes not yet in memory, and the store — write-no-allocate — won't
    // update the now-cached line). A subsequent load to OTHER bytes of that line then HITS
    // the stale cached copy. So a load that line-overlaps an older in-flight store must
    // STALL until the store drains, even with NO byte overlap. (A clean FULL forward is
    // still safe — we already have the data — so it is excluded by the consumer below.)
    val lineA    = paddrs(i)(31 downto 4)
    val lineB    = paddrBs(i)(31 downto 4)
    val qLine    = q.paddr(31 downto 4)
    val sameLine = ent && ((lineA === qLine) || (validBs(i) && (lineB === qLine)))
  }
  // Task P4.4 Step 6 (design doc §5 item 6): re-audit under COPYBACK. This logic
  // predates copyback and was built for a "a store not yet in MEMORY" window
  // (write-through: the only point of truth is memory once acked). Under
  // copyback the CACHE itself becomes the point of truth for a hit. REVIEWED,
  // CONCLUSION: still conservative-correct, no fix needed -- `sameLine`/`stall`
  // (the `perEntry`/`fwd.rsp` block above) is entirely mode-agnostic: this file
  // DOES carry a per-entry `cacheModes` array (used only for `io.drain.payload
  // .cacheMode`, the drain-side handoff to DcachePlugin), but the forward/stall
  // compare logic itself never reads it. LsEuPlugin's own load pipeline
  // (`RESOLVE.whenIsActive`,
  // `execute/LsEuPlugin.scala`) NEVER lets a load reach the cache's own hit-
  // detect (`dcache.loadCmd` only fires from the LAUNCH state, only reachable
  // via RESOLVE's `otherwise` arm) while `fwdStall` is asserted for that load --
  // i.e. while ANY older, same-line, un-drained SQ entry exists. So by the time
  // a load's `dcache.loadCmd` actually launches, no older same-line SQ entry can
  // remain: the cache's own hit-detect (a dirty COPYBACK hit included) is then
  // authoritative and entirely independent of SQ forwarding, exactly as it was
  // for a write-through hit before this task. A load that MISSES a copyback
  // line can only do so because no dirty resident copy exists (same as before);
  // an older, still-undrained SAME-line SQ entry in that situation is still
  // caught by this file's existing `sameLine` stall (Task P4.4's own drain-vs-
  // refill same-set array-write interlock, `DcachePlugin.refillWriteHold`, is
  // exactly what keeps this reasoning sound now that a refill can race a drain
  // at the array level -- see that file). Re-run
  // `StoreQueueSpec -- -z "forward partial-overlap boundary cases"` (the
  // existing same-line-family test) plus the new
  // "same-line stall still holds an older undrained entry (copyback-relevant)"
  // case below -- both green, no RTL change needed here.
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
  // Same-line hazard: any older in-flight store to the load's cache line forces a stall
  // (the refill-stale-line hole above), UNLESS we can cleanly FULL-forward the exact bytes
  // (then we have the data and never touch the cache). A byte-partial overlap already
  // stalls via anyPartial; sameLine extends that to line-overlap-only stores too.
  val anySameLine = perEntry.map(_.sameLine).orR
  io.fwd.rsp.hit   := fullValid
  io.fwd.rsp.data  := best.data
  io.fwd.rsp.stall := (anyPartial || anySameLine) && !fullValid   // a clean full forward resolves the load

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
    strbAs(tail)    := io.alloc.payload.strbA
    lineDataAs(tail):= io.alloc.payload.lineDataA
    validBs(tail)   := io.alloc.payload.validB
    paddrBs(tail)   := io.alloc.payload.paddrB
    nbytesBs(tail)  := io.alloc.payload.nbytesB
    // PRE-REGISTER the slot-B upper byte-range bound (= paddrB + nbytesB), same as A.
    paddrHiBs(tail) := io.alloc.payload.paddrB + io.alloc.payload.nbytesB
    strbBs(tail)    := io.alloc.payload.strbB
    lineDataBs(tail):= io.alloc.payload.lineDataB
    vaddrAs(tail)     := io.alloc.payload.vaddr
    vaddrBs(tail)     := io.alloc.payload.vaddrB
    cacheModes(tail)  := io.alloc.payload.cacheMode
    supervisors(tail) := io.alloc.payload.supervisor
    precises(tail)    := io.alloc.payload.precise
    tail := tail + 1
  }

  // ---- drain handshake: present -> busy; ack -> advance phase / pop ----
  // For a split store: ACK of slot A advances to slot B (no pop); ACK of slot B
  // pops. For a single-slot store: ACK pops directly.
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

  when(drainIssue) { drainBusy := True }
  when(io.drainAck && drainBusy) {
    drainBusy := False
    when(precises(head) && io.drainErr) {
      // Terminal error pop (design doc §4.1): do NOT continue to slot B / leave the
      // entry for excEnteringSq -- pop it right here so drainBusy/phase state stays
      // clean and there is no orphan class. The completion ALSO fires (headReady in
      // the ROB requires completes(h0) even for a faulted entry) alongside the fault.
      io.sqCompletion.valid      := True
      io.sqFaultCompletion.valid := True
      drainPhaseB  := False
      valids(head) := False
      head := head + 1
    } otherwise {
      when(!drainPhaseB && validBs(head)) {
        // slot A acked -> drain slot B next (atomic two-half drain; do NOT pop yet)
        drainPhaseB := True
      } otherwise {
        // single-slot store, or slot B of a split store -> pop the whole entry.
        // sqCompletion fires only on the ack that actually pops the entry, so a
        // split store's slot-A ack does not prematurely signal ROB completion.
        when(precises(head)) { io.sqCompletion.valid := True }
        drainPhaseB  := False
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
  val preciseResolves = io.drainAck && drainBusy && precises(head)
  // Named (not just inlined in the `.elsewhen` below) so the fatal-assert fix
  // (Task: verification-integrity gap, 2026-08-02) can reuse it as the
  // "did a genuine ack just explain this re-issue" discriminator below --
  // see that assert's comment for why.
  val precisePhaseJustAdvanced = RegNext(preciseResolves, init = False)
  when(headPreciseReady && !drainBusy) { preciseDrainBusyReg := True }
    .elsewhen(precisePhaseJustAdvanced) { preciseDrainBusyReg := False }
  io.preciseDrainBusy := preciseDrainBusyReg

  // Priority-rule invariant (design doc §4.1/§5 item 8): once a precise drain has
  // LAUNCHED (preciseDrainBusyReg true), io.irqPreemptPendingIn going true on a
  // later cycle must NOT re-trigger drainIssue (drainIssue's own `!drainBusy` term
  // already excludes it, so a launched-and-still-busy entry can never re-present) and must
  // NOT abort the in-flight drain (nothing in this file reads irqPreemptPendingIn
  // anywhere except headPreciseReady's own term). This assert exists purely to
  // catch a FUTURE edit that accidentally threads irqPreemptPendingIn into the
  // busy path and silently reintroduces the double-issue hazard.
  //
  // FIX (Task: verification-integrity gap, 2026-08-02): making this fatal for the
  // first time (previously `GenerationFlags.simulation` was never actually
  // elaborated in this project's sim configs -- see M68kSim.scala) immediately
  // exposed a FALSE POSITIVE, not a real bug: a SPLIT precise store's legitimate
  // slot-A-ack -> slot-B-drainIssue transition ALSO satisfies the original
  // `preciseDrainBusyReg && drainIssue && precises(head)` condition, because
  // `preciseDrainBusyReg` is (correctly, deliberately) held continuously across
  // BOTH halves of an atomic split drain (see the drain-handshake block above:
  // slot A's ack advances `drainPhaseB` without popping the entry, and
  // `drainBusy` clearing on that same ack is exactly what re-arms `drainIssue`
  // for slot B). That is by design, not the hazard this assert is meant to catch
  // -- confirmed via StoreQueueSpec's own two "split precise store ..." tests,
  // which predate this assert and specifically exercise that exact sequence.
  // The ACTUAL hazard (irqPreemptPendingIn or similar getting threaded into
  // `drainBusy`'s clear path, letting `drainIssue` refire WITHOUT a genuine ack)
  // is distinguished from the legitimate split-drain advance by whether a real
  // `io.drainAck && drainBusy && precises(head)` (`preciseResolves`) fired the
  // PRECEDING cycle -- `precisePhaseJustAdvanced` above already computes exactly
  // that (it is also what legitimately clears `preciseDrainBusyReg` on a final
  // pop). Excluding it here keeps the assert catching the real hazard (a
  // re-issue with NO intervening ack) while no longer false-triggering on an
  // ack-explained phase advance.
  // Explicit `FAILURE` severity -- see M68kSim.scala for why `.includeSimulation`
  // must also be set on the enclosing SpinalConfig for this block to elaborate at
  // all (without it, `GenerationFlags.simulation { ... }` is silently skipped).
  GenerationFlags.simulation {
    assert(!(preciseDrainBusyReg && drainIssue && precises(head) && !precisePhaseJustAdvanced),
      "StoreQueue: a precise drain re-issued while preciseDrainBusyReg was already held, with no intervening ack to explain it",
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
    val popsHead = io.drainAck && drainBusy && !(!drainPhaseB && validBs(head))
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

  // ---- debug-only observability (task #139 finding #1 investigation) ----
  // Zero synth impact (sim tap only, not referenced by any RTL logic).
  head.simPublic(); tail.simPublic()
  drainBusy.simPublic(); drainPhaseB.simPublic()
  valids.foreach(_.simPublic()); committed.foreach(_.simPublic())
  robIds.foreach(_.simPublic())
  // P2.1: per-entry precise-path storage (vaddr/cacheMode/supervisor/precise), tapped
  // so directed alloc-then-inspect tests can verify the ring stored what was allocated.
  vaddrAs.foreach(_.simPublic()); vaddrBs.foreach(_.simPublic())
  cacheModes.foreach(_.simPublic()); supervisors.foreach(_.simPublic()); precises.foreach(_.simPublic())
  io.drain.valid.simPublic(); io.drainAck.simPublic(); io.flush.simPublic()
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
