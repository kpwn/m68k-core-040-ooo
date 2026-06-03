package m68k040.ls

import m68k040.cache.DStoreCmd
import m68k040.isa.Size
import spinal.core._
import spinal.lib._

case class SqAlloc() extends Bundle {
  val robId = UInt(6 bits)
  val paddr = UInt(32 bits)
  val data  = Bits(32 bits)
  val size  = Size()
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
    val alloc  = slave(Flow(SqAlloc()))
    val fwd    = new Bundle { val query = in(SqFwdQuery()); val rsp = out(SqFwdRsp()) }
    val commit = slave(Flow(UInt(6 bits)))
    val flush  = in(Bool())
    val drain  = master(Flow(DStoreCmd()))
  }

  // ---- ring storage (all RegInit) ----
  val valids    = Vec.fill(depth)(RegInit(False))
  val committed = Vec.fill(depth)(RegInit(False))
  val robIds    = Vec.fill(depth)(RegInit(U(0, 6 bits)))
  val paddrs    = Vec.fill(depth)(RegInit(U(0, 32 bits)))
  val datas     = Vec.fill(depth)(RegInit(B(0, 32 bits)))
  val sizes     = Vec.fill(depth)(RegInit(Size.BYTE()))

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

  // ---- drain: oldest entry, valid & committed -> write-through, then pop ----
  val drainFire = valids(head) && committed(head) && !io.flush
  io.drain.valid          := drainFire
  io.drain.payload.paddr  := paddrs(head)
  io.drain.payload.data   := datas(head)
  io.drain.payload.size   := sizes(head)

  // ---- forwarding (combinational) ----
  val q          = io.fwd.query
  val qBytes     = sizeBytes(q.size)
  val perEntry   = for (i <- 0 until depth) yield new Area {
    val ent      = valids(i) && olderThan(robIds(i), q.robId)
    val eBytes   = sizeBytes(sizes(i))
    // byte ranges [paddr, paddr+bytes)
    val qLo      = q.paddr
    val qHi      = q.paddr + qBytes
    val eLo      = paddrs(i)
    val eHi      = paddrs(i) + eBytes
    val overlap  = ent && (qLo < eHi) && (eLo < qHi)
    // full overlap = exact same address AND same size (full forward of the value)
    val full     = overlap && (eLo === qLo) && (eBytes === qBytes)
    val partial  = overlap && !full
  }
  // youngest older overlapping entry: among matches, the one closest (in ROB age)
  // to the query. Build a priority by age distance (q.robId - robId), smallest wins.
  val anyOverlap = perEntry.map(_.overlap).orR
  val anyPartial = perEntry.map(_.partial).orR
  // pick the youngest full-overlap entry (smallest (q.robId - robId))
  val fullVec    = Vec(perEntry.map(_.full))
  val ageDist    = Vec((0 until depth).map(i => (q.robId - robIds(i))(5 downto 0)))
  // Select the youngest full-overlap entry (smallest ROB age distance). Build a
  // pure Mux-tree reduction over the Scala collection (no self-referential reg).
  case class Cand() extends Bundle { val valid = Bool(); val dist = UInt(6 bits); val data = Bits(32 bits) }
  val cands = (0 until depth).map { i =>
    val c = Cand(); c.valid := fullVec(i); c.dist := ageDist(i); c.data := datas(i); c
  }
  val best = cands.reduceBalancedTree { (a, b) =>
    val o = Cand()
    // prefer the valid one; if both valid, the smaller dist (younger store)
    when(a.valid && (!b.valid || (a.dist <= b.dist))) { o := a } otherwise { o := b }
    o
  }
  io.fwd.rsp.hit   := best.valid
  io.fwd.rsp.data  := best.data
  io.fwd.rsp.stall := anyPartial && !best.valid   // a full forward resolves the load

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
    tail := tail + 1
  }

  // ---- drain pop: clear head entry, advance head ----
  when(drainFire) {
    valids(head) := False
    head := head + 1
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
