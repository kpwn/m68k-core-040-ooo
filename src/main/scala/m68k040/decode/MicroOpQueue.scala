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
  * Implementation: a ring (depth power-of-two), banked 4-way into per-bank
  * `Mem(DecodedUop)` (`ram_style`=distributed, single write port + 2 async read
  * ports per bank -- see the area-fold comment at `banks`/`bankMems` below).
  * head/tail/count are RegInit (no uninit Regs). Push writes are COMPACTED at the
  * tail (the v-th valid µop lands at tail+v) using HARDWARE sums (no when-gated
  * Scala vars). Pop reads head and head+1 combinationally (async); on fire head
  * advances by the popped count. */
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

  // Banked storage: bank = addr[1:0], row = addr[3:2]. Partitioned so the 4-wide
  // compacted write is a single tail[1:0] rotate into 4 banks (4 wide muxes) instead
  // of a 16-wide 4:1 crossbar — cuts the ring congestion hotspot.
  //
  // ── Area fold (LUT-count-reduction-broad-review-2026-08-15, "MicroOpQueue flop-ring
  // -> per-bank LUTRAM"): WAS `Vec.fill(rows)(RegInit(...))` per bank -- 16 rows x
  // widthOf(DecodedUop()) bits of flops total (measured 7,197 FF bits via netlist
  // census), the largest area item in this file's plugin, sitting in the decode
  // pblock (83.6% CLB LUT-utilization, the tightest-packed region in the design).
  //
  // Writer-exclusivity (checked, not assumed): each bank has EXACTLY ONE write
  // attempt per cycle. The push loop below computes ONE `vForBank`/`addrB`/`writeEnB`
  // per bank per cycle (a rotation of the 4 push slots into the 4 banks by
  // `tail[1:0]`), so the old `bankRegs(b)(rowOf(addrB)) := ...` was ALREADY a single
  // dynamically-indexed write per bank per cycle (SpinalHDL lowers a Vec-of-Reg
  // dynamic-index write to a per-row `when(rowOf(addrB) === r)` one-hot mux, i.e. a
  // single write port, never two banks' worth of writes landing on one row) -- this
  // fold does not change that shape, it only changes the storage primitive backing
  // it. Matches the Slice-C / task-#240 precondition (writes.size<=1 per bank) by
  // construction, not by new arbitration.
  //
  // Read-timing (checked, not assumed): the pop side reads
  // `bankMems(bankOf(head))(headRow)` / `head1Row` COMBINATIONALLY, in the SAME
  // cycle `head`/`head1` become valid (registered from the PREVIOUS cycle) -- so a
  // synchronous-read (BRAM) Mem would add a cycle of read latency the rename
  // consumer downstream does not expect, regressing the queue's documented II=1
  // dequeue (2026-08-09 ipc-ls-eu-full-pipeline-design.md). `ram_style`=distributed
  // (NOT block) keeps the read asynchronous, matching DcachePlugin's valids/dirtys
  // idiom (task #240) and RobPlugin's nextPcMem/faultDynMem idiom (ROB-fold Slice
  // A/C) exactly.
  //
  // Same-cycle read-after-write hazard: PROVEN unreachable. A push can only target
  // ring address `tail+v`; that coincides with a read address (`head` or `head+1`)
  // only when `tail == head` (mod depth) with `count>0`, i.e. the ring is FULL
  // (count==depth) -- and `io.push.ready` requires >=4 free slots, so push can never
  // fire while full. No `.init` (matches this codebase's convention for
  // Bundle-typed Mems, e.g. RobPlugin's `payload`/`branchTrainMem`/`faultDynMem`:
  // uninitialised, correctness argued from "never read before written" instead) --
  // a never-written row is likewise never read here: `io.pop.valid`/`io.pop1Valid`
  // gate on the registered `count`, which is only >0/>1 for rows a PRIOR cycle's
  // push already wrote.
  val banks = 4
  require(depth % banks == 0, "MicroOpQueue depth must be a multiple of banks")
  val rows  = depth / banks
  val rowW  = log2Up(rows)
  def bankOf(addr: UInt): UInt = addr(1 downto 0)
  def rowOf(addr: UInt):  UInt = addr(ptrW - 1 downto 2)
  def zeroUop(): DecodedUop = {
    val u = DecodedUop(); u.assignFromBits(B(0, widthOf(DecodedUop()) bits)); u
  }
  val bankMems = Seq.fill(banks)(Mem(DecodedUop(), rows))
  bankMems.foreach(_.addAttribute("ram_style", "distributed"))

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
  val atHeadRow  = Vec(bankMems.map(_.readAsync(headRow)))
  val atHead1Row = Vec(bankMems.map(_.readAsync(head1Row)))
  io.pop.payload(0) := atHeadRow(bankOf(head))
  io.pop.payload(1) := atHead1Row(bankOf(head1))
  io.pop1Valid      := count > 1
  val popN = Mux(io.pop.fire, Mux(count > 1, U(2, countW bits), U(1, countW bits)), U(0, countW bits))

  // ── Compacted multi-push via a single tail[1:0] ROTATE into the 4 banks ──────
  // The v-th valid µop targets addr=tail+v; for v=0..3 those hit the 4 distinct banks
  // (tail+v) mod 4 = a rotation by tail[1:0]. So bank b receives uop index
  // vForBank(b) = (b - tail) mod 4, written iff vForBank(b) < count, at row (tail+v)[3:2].
  val tailLo = tail(1 downto 0)
  // Sim-only shadow of the pre-fold `Vec.fill(rows)(RegInit(...))` bank storage,
  // driven by the EXACT SAME enable/address/data as the real `bankMems(b).write`
  // below (statement for statement, matching RobPlugin's Slice-C tripwire idiom) --
  // so the two cannot structurally drift, only actually diverge if the fold is
  // unsound. `null` in any synth/GenVerilog build (GenerationFlags.simulation
  // gates elaboration) => zero synthesis cost.
  val shadowBankRegs = GenerationFlags.simulation {
    Seq.fill(banks)(Vec.fill(rows)(RegInit(zeroUop())))
  }
  for (b <- 0 until banks) {
    // vForBank: which push slot lands in bank b this cycle (0..3).
    val vForBank = (U(b, 2 bits) - tailLo)            // mod-4 by 2-bit wrap
    val addrB    = (tail + vForBank.resize(ptrW)).resized   // = tail + v (the ring addr in bank b)
    val writeEnB = pushFire && (io.push.count > vForBank.resize(3))
    bankMems(b).write(address = rowOf(addrB), data = io.push.uops(vForBank), enable = writeEnB)
    GenerationFlags.simulation {
      when(writeEnB) {
        shadowBankRegs(b)(rowOf(addrB)) := io.push.uops(vForBank)
      }
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

  // ── Tripwire: Mem-fold reads must be bit-identical to the pre-fold shadow ──────
  // Gated on `io.pop.valid`/`io.pop1Valid` (== registered count>0 / count>1), i.e.
  // only compared where `head`/`head1` index a row a PRIOR cycle's push actually
  // wrote -- exactly mirroring RobPlugin's Slice-C tripwire, which likewise only
  // compares at points the value is architecturally consumed (a never-written row
  // legitimately differs: the real Mem is uninitialised while the shadow Reg-Vec
  // resets to zero, and comparing there would be a false positive, not a real bug).
  GenerationFlags.simulation {
    when(io.pop.valid) {
      assert(atHeadRow(bankOf(head)).asBits === shadowBankRegs(bankOf(head))(headRow).asBits,
        "MicroOpQueue: bankMems Mem-fold readAsync(headRow) diverged from the pre-fold shadow Reg-ring -- the head payload the Mem fold produced is not bit-identical to what the old Reg array would have produced",
        FAILURE)
    }
    when(io.pop1Valid) {
      assert(atHead1Row(bankOf(head1)).asBits === shadowBankRegs(bankOf(head1))(head1Row).asBits,
        "MicroOpQueue: bankMems Mem-fold readAsync(head1Row) diverged from the pre-fold shadow Reg-ring -- the head+1 payload the Mem fold produced is not bit-identical to what the old Reg array would have produced",
        FAILURE)
    }
  }
}
