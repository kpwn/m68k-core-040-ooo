package m68k040.decode

import spinal.core._
import spinal.lib._

/** µop expansion queue (decode-matrix spec §4.1).
  *
  * Buffers the variable-rate µop expansion produced by the cracking
  * MicroOpAssembler (each of the 2 decode slots emits 1-2 µops, so up to 4
  * µops/cycle arrive) and drains a steady 2/cycle to rename, preserving program
  * order. Flushable on a mispredict (pointer reset).
  *
  * Push contract: `io.push` carries a `count` (0..4) of valid µops packed into
  * `uops(0..count-1)`; `io.push.valid` asserts the burst, `io.push.ready` gates
  * it (only accept when ≥4 free slots so a full 4-wide burst never overflows).
  * Pop contract: `io.pop` is a Stream(Vec(DecodedUop,2)); `io.pop1Valid` says
  * whether the 2nd popped slot is a real µop (count==1 at the tail). This mirrors
  * the DecodeUopService.uops + uop1Valid contract rename consumes.
  *
  * Implementation: a ring of `Reg(DecodedUop)` (depth power-of-two). head/tail/
  * count are RegInit (no uninit Regs). Push writes are COMPACTED at the tail (the
  * v-th valid µop lands at tail+v) using HARDWARE sums (no when-gated Scala vars).
  * Pop reads head and head+1; on fire head advances by the popped count. */
class MicroOpQueue(depth: Int = 16) extends Component {
  require(isPow2(depth), "MicroOpQueue depth must be a power of two")
  val ptrW   = log2Up(depth)
  val countW = log2Up(depth + 1)

  val io = new Bundle {
    val push = new Bundle {
      val valid = in Bool ()
      val count = in UInt (3 bits)               // 0..4 valid µops in `uops`
      val uops  = in Vec (DecodedUop(), 4)
      val ready = out Bool ()
    }
    val pop      = master(Stream(Vec(DecodedUop(), 2)))
    val pop1Valid= out Bool ()
    val flush    = in Bool ()
  }

  // Banked flop ring: bank = addr[1:0], row = addr[3:2]. SAME 16x197b flops, partitioned
  // so the 4-wide compacted write is a single tail[1:0] rotate into 4 banks (4 wide muxes)
  // instead of a 16-wide 4:1 crossbar — cuts the ring congestion hotspot. NOT a RAM.
  val banks = 4
  require(depth % banks == 0, "MicroOpQueue depth must be a multiple of banks")
  val rows  = depth / banks
  val rowW  = log2Up(rows)
  def bankOf(addr: UInt): UInt = addr(1 downto 0)
  def rowOf(addr: UInt):  UInt = addr(ptrW - 1 downto 2)
  val bankRegs = Seq.fill(banks)(Vec.fill(rows)(RegInit({
    val u = DecodedUop(); u.assignFromBits(B(0, widthOf(DecodedUop()) bits)); u
  })))

  val head  = RegInit(U(0, ptrW   bits))   // oldest µop
  val tail  = RegInit(U(0, ptrW   bits))   // next free slot
  val count = RegInit(U(0, countW bits))

  // ── Accept only when ≥4 free slots (a full burst never overflows) ────────────
  val freeSlots = (U(depth, countW bits) - count)
  io.push.ready := freeSlots >= U(4, countW bits)
  val pushFire  = io.push.valid && io.push.ready
  // HARDWARE-clamped push count (gate by fire; do NOT use a when-gated Scala var).
  val pushN = Mux(pushFire, io.push.count.resize(countW), U(0, countW bits))

  // ── Pop: read head and head+1 via per-bank row-read + 4:1 bank-select ────────
  io.pop.valid     := count > 0
  val head1     = (head + 1).resized
  val headRow   = rowOf(head)
  val head1Row  = rowOf(head1)
  // Per-bank value at the head row / head+1 row, then select the bank.
  val atHeadRow  = Vec((0 until banks).map(b => bankRegs(b)(headRow)))
  val atHead1Row = Vec((0 until banks).map(b => bankRegs(b)(head1Row)))
  io.pop.payload(0) := atHeadRow(bankOf(head))
  io.pop.payload(1) := atHead1Row(bankOf(head1))
  io.pop1Valid      := count > 1
  val popN = Mux(io.pop.fire, Mux(count > 1, U(2, countW bits), U(1, countW bits)), U(0, countW bits))

  // ── Compacted multi-push via a single tail[1:0] ROTATE into the 4 banks ──────
  // The v-th valid µop targets addr=tail+v; for v=0..3 those hit the 4 distinct banks
  // (tail+v) mod 4 = a rotation by tail[1:0]. So bank b receives uop index
  // vForBank(b) = (b - tail) mod 4, written iff vForBank(b) < count, at row (tail+v)[3:2].
  val tailLo = tail(1 downto 0)
  for (b <- 0 until banks) {
    // vForBank: which push slot lands in bank b this cycle (0..3).
    val vForBank = (U(b, 2 bits) - tailLo)            // mod-4 by 2-bit wrap
    val addrB    = (tail + vForBank.resize(ptrW)).resized   // = tail + v (the ring addr in bank b)
    val writeEnB = pushFire && (io.push.count > vForBank.resize(3))
    when(writeEnB) {
      bankRegs(b)(rowOf(addrB)) := io.push.uops(vForBank)
    }
  }
  when(pushFire) {
    tail := (tail + pushN.resize(ptrW)).resized
  }

  // ── Pop advance ──────────────────────────────────────────────────────────────
  when(io.pop.fire) {
    head := (head + popN.resize(ptrW)).resized
  }

  // ── count update (hardware sum) ──────────────────────────────────────────────
  count := (count + pushN - popN).resized

  // ── Flush: pointer reset (RAM untouched) ──────────────────────────────────────
  when(io.flush) {
    head  := 0
    tail  := 0
    count := 0
  }
}
