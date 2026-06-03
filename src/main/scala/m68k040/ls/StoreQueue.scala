package m68k040.ls

import m68k040.cache.DStoreCmd
import m68k040.isa.Size
import spinal.core._
import spinal.lib._

case class SqAlloc() extends Bundle {
  val robId = UInt(6 bits)
  // ---- slot A (always present) ----
  val paddr = UInt(32 bits)
  val data  = Bits(32 bits)
  val size  = Size()
  val nbytesA   = UInt(3 bits)        // covered byte count of slot A (for overlap)
  val useStrbA  = Bool()              // drain slot A with explicit strobe (split) vs {data,size}
  val strbA     = Bits(16 bits)       // line-relative byte strobe (when useStrbA)
  val lineDataA = Bits(128 bits)      // line-aligned merge data (when useStrbA)
  // ---- slot B (optional second slot of a SPLIT store; one entry = one atomic store) ----
  val validB    = Bool()
  val paddrB    = UInt(32 bits)
  val nbytesB   = UInt(3 bits)
  val strbB     = Bits(16 bits)
  val lineDataB = Bits(128 bits)
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
    val flush    = in(Bool())
    val drain    = master(Flow(DStoreCmd()))
    // Memory-write acknowledge for the in-flight drain. The oldest committed entry
    // is PRESENTED on `drain` for exactly one cycle (latched by the D-cache), then
    // HELD resident (still forwarding) until `drainAck` confirms the write-through
    // landed in memory. Popping on drain-issue (not ack) would leave a window where
    // the store left the SQ but its memory write was not yet visible — a younger
    // load that MISSED L1D could refill stale memory. Holding until ack closes it.
    val drainAck = in(Bool())
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
  // optional slot B (second half of a split store)
  val validBs   = Vec.fill(depth)(RegInit(False))
  val paddrBs   = Vec.fill(depth)(RegInit(U(0, 32 bits)))
  val nbytesBs  = Vec.fill(depth)(RegInit(U(0, 3 bits)))
  val strbBs    = Vec.fill(depth)(RegInit(B(0, 16 bits)))
  val lineDataBs= Vec.fill(depth)(RegInit(B(0, 128 bits)))
  // drain phase of the head entry: false = slot A, true = slot B (split only)
  val drainPhaseB = RegInit(False)

  val head = RegInit(U(0, ptrW bits))   // oldest
  val tail = RegInit(U(0, ptrW bits))   // next free
  val count = RegInit(U(0, log2Up(depth + 1) bits))

  // ---- access-size in bytes ----
  def sizeBytes(s: Size.C): UInt = {
    val n = UInt(3 bits); n := 1
    switch(s) { is(Size.BYTE) { n := 1 }; is(Size.WORD) { n := 2 }; is(Size.LONG) { n := 4 } }
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
  val headReady = valids(head) && committed(head) && !io.flush
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
  } otherwise {
    io.drain.payload.paddr    := paddrBs(head)
    io.drain.payload.data     := B(0, 32 bits)
    io.drain.payload.size     := Size.LONG()
    io.drain.payload.useStrb  := True
    io.drain.payload.strb     := strbBs(head)
    io.drain.payload.lineData := lineDataBs(head)
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
    // slot A range
    val aLo      = paddrs(i)
    val aHi      = paddrs(i) + nbytesAs(i)
    val overlapA = ent && (qLo < aHi) && (aLo < qHi)
    // slot B range (only when this entry is a split store)
    val bLo      = paddrBs(i)
    val bHi      = paddrBs(i) + nbytesBs(i)
    val overlapB = ent && validBs(i) && (qLo < bHi) && (bLo < qHi)
    val overlap  = overlapA || overlapB
    // full overlap = exact addr+size against slot A of a NON-split store.
    val full     = overlapA && !validBs(i) && (aLo === qLo) && (nbytesAs(i) === qBytes)
    val partial  = overlap && !full
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
  io.fwd.rsp.hit   := fullValid
  io.fwd.rsp.data  := best.data
  io.fwd.rsp.stall := anyPartial && !fullValid   // a clean full forward resolves the load

  // ---- commit: mark the matching valid entry committed ----
  when(io.commit.valid) {
    for (i <- 0 until depth)
      when(valids(i) && (robIds(i) === io.commit.payload)) { committed(i) := True }
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
    useStrbAs(tail) := io.alloc.payload.useStrbA
    strbAs(tail)    := io.alloc.payload.strbA
    lineDataAs(tail):= io.alloc.payload.lineDataA
    validBs(tail)   := io.alloc.payload.validB
    paddrBs(tail)   := io.alloc.payload.paddrB
    nbytesBs(tail)  := io.alloc.payload.nbytesB
    strbBs(tail)    := io.alloc.payload.strbB
    lineDataBs(tail):= io.alloc.payload.lineDataB
    tail := tail + 1
  }

  // ---- drain handshake: present -> busy; ack -> advance phase / pop ----
  // For a split store: ACK of slot A advances to slot B (no pop); ACK of slot B
  // pops. For a single-slot store: ACK pops directly.
  when(drainIssue) { drainBusy := True }
  when(io.drainAck && drainBusy) {
    drainBusy := False
    when(!drainPhaseB && validBs(head)) {
      // slot A acked -> drain slot B next (atomic two-half drain; do NOT pop yet)
      drainPhaseB := True
    } otherwise {
      // single-slot store, or slot B of a split store -> pop the whole entry
      drainPhaseB  := False
      valids(head) := False
      head := head + 1
    }
  }

  // ---- flush: squash speculative (uncommitted) entries. Roll tail back to just
  // past the youngest COMMITTED entry. Walk from head over committed entries. ----
  when(io.flush) {
    // count of committed entries still live, scanning head..tail
    val keep = Vec(Bool(), depth)
    for (i <- 0 until depth) keep(i) := valids(i) && committed(i)
    for (i <- 0 until depth) when(!keep(i)) { valids(i) := False }
    // new tail = head + (number of committed-live entries). Committed entries are
    // always the oldest contiguous run (commit is in-order), so this is exact.
    val keepCount = CountOne(keep)
    tail := (head + keepCount).resized
  }

  // ---- count = hardware sum of valids (no Scala-var counters) ----
  count := CountOne(valids).resized
}
