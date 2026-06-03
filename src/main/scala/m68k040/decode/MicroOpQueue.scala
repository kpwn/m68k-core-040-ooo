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

  // ── Ring storage (all RegInit so no per-seed randomization) ──────────────────
  val ring = Vec.fill(depth)(RegInit({
    val u = DecodedUop(); u.assignFromBits(B(0, widthOf(DecodedUop()) bits)); u
  }))

  val head  = RegInit(U(0, ptrW   bits))   // oldest µop
  val tail  = RegInit(U(0, ptrW   bits))   // next free slot
  val count = RegInit(U(0, countW bits))

  // ── Accept only when ≥4 free slots (a full burst never overflows) ────────────
  val freeSlots = (U(depth, countW bits) - count)
  io.push.ready := freeSlots >= U(4, countW bits)
  val pushFire  = io.push.valid && io.push.ready
  // HARDWARE-clamped push count (gate by fire; do NOT use a when-gated Scala var).
  val pushN = Mux(pushFire, io.push.count.resize(countW), U(0, countW bits))

  // ── Pop: present head and head+1 ─────────────────────────────────────────────
  io.pop.valid     := count > 0
  io.pop.payload(0) := ring(head)
  io.pop.payload(1) := ring((head + 1).resized)
  io.pop1Valid      := count > 1
  val popN = Mux(io.pop.fire, Mux(count > 1, U(2, countW bits), U(1, countW bits)), U(0, countW bits))

  // ── Compacted multi-push: v-th valid µop lands at tail + v ───────────────────
  when(pushFire) {
    for (v <- 0 until 4) {
      when(io.push.count > U(v, 3 bits)) {
        ring((tail + U(v, ptrW bits)).resized) := io.push.uops(v)
      }
    }
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
