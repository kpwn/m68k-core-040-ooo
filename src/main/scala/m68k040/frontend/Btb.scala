package m68k040.frontend

import m68k040.Global
import m68k040.services.BtbUpdateService
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** One BTB table entry (fetch-time predictor, slice 1).
  *  - valid   : occupied (held in a register array, cleared by invalidateAll).
  *  - tag     : the upper fetch-PC bits above the index field (no aliasing false-hits).
  *  - target  : the learned taken-target (PC-relative resolved OR last-seen indirect).
  *  - brType  : 0=cond (Bcc/DBcc), 1=uncond (BRA/BSR/JMP/JSR).
  *  - counter : 2-bit saturating bimodal (0,1 = not-taken; 2,3 = taken).
  * `valid` lives in a Reg Vec (cheap one-cycle invalidate); the rest in a Mem. */
case class BtbEntry(tagBits: Int) extends Bundle {
  val tag     = UInt(tagBits bits)
  val target  = UInt(32 bits)
  val brType  = UInt(2 bits)
  val counter = UInt(2 bits)
}

/** Tagged direct-mapped BTB + 2-bit bimodal predictor (spec slice 1).
  *
  * Read  : a combinational async-read off the fetch PC (LUTRAM, like the I-cache
  *   tagMem), with the hit/predict decision REGISTERED so the fetch-redirect path
  *   starts from a register (FMax: the redirect/target mux is not in the lookup cone).
  * Write : one port, from the ROB retire (BtbUpdateService) — alloc tag/target/brType
  *   + bump the saturating counter. ONLY retiring branches => no wrong-path pollution.
  * Invalidate: all `valid := 0` on the same `invalidateAll` that clears the I-cache.
  *
  * Interface (driven by FetchAlignPlugin / wired by the synth-top + test DUTs):
  *  - queryPc / queryValid : the fetch PC to look up (the issued fetch window base).
  *  - predValid (reg)      : a registered hit-and-predict-taken for the queried PC.
  *  - predTarget (reg)     : the predicted target (valid iff predValid).
  *  - predBranchPc (reg)   : the queried PC the prediction is for (so FetchAlign can
  *                           attribute the prediction + suppress post-branch words).
  *  - invalidateAll        : clear all valid bits (driven from the I-cache invalidate). */
class BtbPlugin extends FiberPlugin {

  // ---- public ports (accessed via host[BtbPlugin].logic.* by the wiring layer) ----
  var queryPcPort:    UInt = null
  var queryValidPort: Bool = null
  var invalidatePort: Bool = null

  val logic = during build new Area {
    val entries  = Global.BTB_ENTRIES.get
    require((entries & (entries - 1)) == 0, "btbEntries must be a power of two")
    val idxBits  = log2Up(entries)
    // Word-granular index: PC bit 0 is always 0 for instructions; index off PC bits
    // above the 2-byte word. index = pc[1+idxBits downto 2], tag = pc above that.
    val idxLo    = 1
    val idxHi    = idxLo + idxBits - 1
    val tagLo    = idxHi + 1
    val tagBits  = 32 - tagLo

    def idxOf(pc: UInt): UInt = pc(idxHi downto idxLo)
    def tagOf(pc: UInt): UInt = pc(31 downto tagLo)

    // ---- storage ----
    // valid : register array (one-cycle invalidate). entry payload : async-read LUTRAM.
    val valids = Vec.fill(entries)(RegInit(False))
    val mem    = Mem(BtbEntry(tagBits), entries)

    // ---- ports (directionless plain wires, idle-defaulted with concrete zeros so a
    //      standalone DUT elaborates; the wiring layer / FetchAlign overrides them) ----
    val queryPc = UInt(32 bits)
    queryPc.allowOverride; queryPc := U(0, 32 bits)
    val queryValid = Bool()
    queryValid.allowOverride; queryValid := False
    // Second combinational lookup port (the aligner's slot1 PC). Two per-instruction
    // BTB reads per cycle so a 2-wide emit predicts a branch in EITHER slot.
    //
    // FMax "Lever D" (2026-08-08): this port is addressed as (base, sel) — NOT as a
    // single pre-summed PC — so the RAM read never waits on `sel`. See the
    // `spec2*` block below for the full derivation.
    //   query2BasePc : the aligner's slot0 PC (== FetchAlign's `decodePc` REGISTER).
    //   query2Sel    : the aligner's `L0` (slot0's lenWords). Legal range 1..9.
    // The effective queried PC is `query2BasePc + 2*query2Sel`, exactly what
    // `Aligner.align` computes for `slot1.pc`.
    val query2BasePc = UInt(32 bits)
    query2BasePc.allowOverride; query2BasePc := U(0, 32 bits)
    val query2Sel = UInt(4 bits)
    query2Sel.allowOverride; query2Sel := U(0, 4 bits)
    val query2Valid = Bool()
    query2Valid.allowOverride; query2Valid := False
    val invalidateAll = Bool()
    invalidateAll.allowOverride; invalidateAll := False
    queryPcPort = queryPc; queryValidPort = queryValid; invalidatePort = invalidateAll

    // ---- combinational lookup helper (async LUTRAM read + tag/counter decode) ----
    // Returns (predTaken, target, hit, brType): the bimodal predict-taken decision, the
    // learned target, the raw tag-hit (gshare needs `hit && brType==cond` to override the
    // direction of a CONDITIONAL BTB hit), and the entry's brType (0=cond, 1=uncond).
    def lookup(pc: UInt, valid: Bool): (Bool, UInt, Bool, UInt) = {
      val idx   = idxOf(pc)
      val tag   = tagOf(pc)
      val entry = mem.readAsync(idx)
      val rawHit = valid && valids(idx) && (entry.tag === tag)
      // predict-taken: hit with counter in the taken band (>=2) OR an unconditional
      // (brType==1, force-taken even on the cycle it is first installed).
      val predTaken = rawHit && ((entry.counter >= U(2, 2 bits)) || (entry.brType === U(1, 2 bits)))
      (predTaken, entry.target, rawHit, entry.brType)
    }

    // ---- COMBINATIONAL lookup outputs (per-instruction PC, slice 1) ─────────────
    // FetchAlign queries with the aligner's slot0/slot1 instruction PCs (decodePc and
    // decodePc+slot0-len) and uses THESE combinational outputs IN THE SAME CYCLE to
    // attribute the prediction to the emitted instruction + redirect fetch. `hit`/`brType`
    // (slice 3) let FetchAlign form `condBtbHit = hit && brType==cond` to source the
    // conditional's predicted-taken from gshare instead of the bimodal counter.
    val (predTakenComb,  predTargetComb,  predHitComb,  predTypeComb)  = lookup(queryPc,  queryValid)

    // ── FMax "Lever D" (2026-08-08): L0-LATE-SELECT speculative slot-1 lookup ─────
    // docs/superpowers/specs/2026-08-08-fmax-leverd-btb-late-select-design.md
    //
    // WHAT CHANGED. Slot 1 used to be a plain `lookup(query2Pc, query2Valid)` whose
    // address was `decodePc + 2*L0` (`Aligner.align`'s `slot1.pc`). `L0` is the buffer
    // head's predecoded length, resolved COMBINATIONALLY out of the InstructionBuffer's
    // head window — so the whole chain `headPtr -> IBuf head mux -> L0 -> 7-bit index
    // adder -> RAM read -> 24-bit tag compare -> slot1WouldPred -> io_shift -> headPtr`
    // was serialized inside the front end's own feedback loop. A netlist trace of the
    // routed checkpoint measured that loop at -1.289ns with 45.2% of it attributable to
    // the `L0`-addressed read: the `L0 -> address` arc 1.187ns, the RAM read itself only
    // 0.419ns, the tag compare behind it 0.776ns. ALL 16,400 enumerated
    // `headPtr -> headPtr` paths pass through this RAM; the SAME physical cells
    // (`..._mem_reg_r2_0_63_0_6_i_1` CARRY8, the `ADDRC3` net, the `RAMC` RAMD64E) also
    // form Frontend Lever C's residual `fed.slot1Valid` floor (-0.943ns). The slot-0
    // port — identical RAM, identical tag compare, identical downstream cone, but
    // addressed from a plain REGISTER — closes 0.77ns faster (-0.594ns). The address
    // asymmetry is the entire story.
    //
    // THE RESTRUCTURING. Read ALL candidate entries `L0` could possibly select, off the
    // already-registered `query2BasePc` alone (indices `base + 2k`, k = 1..9), and let
    // `L0` do nothing but MUX one of the 9 precomputed results at the point of
    // consumption. Nothing is approximated and nothing is predicted differently: way `k`
    // is `lookup(base + 2k, ...)` — the SAME function, the SAME memory, the SAME
    // `valids`/tag/counter decode — so selecting way `L0` yields the bit-identical value
    // the old single addressed read produced. This is a pure re-association of WHEN `L0`
    // is consumed (after 9 parallel reads instead of before 1 selected read), NOT a
    // change to WHAT is computed. Proven directly, RTL-against-RTL, by
    // `BtbLateSelectEquivalenceSpec` (which drives THIS component's untouched slot-0
    // port with the old `base + 2*L0` address and asserts all four outputs match, over
    // an exhaustive sweep of the reachable input space).
    //
    // NO REGISTER IS ADDED ANYWHERE. That is what makes this IPC-neutral: `io_shift`
    // still resolves combinationally every cycle, so `headPtr` still advances every
    // cycle exactly as before. (Registering anything on this arc would halve front-end
    // IPC — see the design spec's rejected alternatives, which is also why a sync-BRAM
    // conversion of this RAM was disconfirmed: it needs its address a cycle early, and
    // "a cycle early" is inside the loop that produces it.)
    //
    // WHY `k` ∈ 1..9 IS EXHAUSTIVE. The slot-1 lookup only matters when
    // `query2Valid` is set, i.e. when `Aligner.align`'s `slot1Ok` holds:
    //   slot1Ok = p1.simple && !p1.ambiguousLine && avail >= L0+L1 && L0+L1 <= WINDOW(10)
    // reached only inside the `p0.simple` arm. `PredecodeWord.classify(...).simple` never
    // coincides with `lenWords === 0` — proven exhaustively over all 65536 opwords by
    // `PredecodeSimpleLenSpec` (the same property Frontend Lever A's tautological-mask
    // deletion already rests on) — so L0 >= 1 and L1 >= 1, and L0 + L1 <= 10 forces
    // L0 <= 9. Hence 1 <= L0 <= 9 whenever the result is consumed.
    //
    // AND THE OUT-OF-RANGE CASE DEGRADES SAFELY ANYWAY. Ways 0 and 10..15 read constant
    // not-taken. Should `query2Sel` ever land outside 1..9 with `query2Valid` set, the
    // only consumer of `predTaken2Comb` is FetchAlign's `slot1WouldPred`, which merely
    // DEFERS slot 1 to the next cycle's slot 0; a False there means "do not defer", so
    // the branch simply issues unpredicted and the EU's resolve + commit-time redirect
    // recovers it. A lost dual-issue opportunity at worst — never an architectural
    // commitment. (`predTarget2Comb`/`predType2Comb` read 0 there; both are architecturally
    // dead — nothing in FetchAlignPlugin reads `btbPredTarget1`/`gsBtbType1`/`gsBtbHit1`,
    // and synthesis already prunes them today.)
    //
    // COST. 9 replicated async read ports instead of 1 (~+500 LUTRAM primitives after
    // synthesis prunes the dead 32-bit target field) + 9 tag comparators + the 16:1
    // select mux — roughly +0.3% utilization. `query2Valid` is factored OUT of the 9
    // ways and applied ONCE after the mux (`v && sel(x_k)` == `sel(v && x_k)`), so it
    // does not deepen any way.
    val spec2Taken  = Vec(Bool(),        16)
    val spec2Target = Vec(UInt(32 bits), 16)
    val spec2Hit    = Vec(Bool(),        16)
    val spec2Type   = Vec(UInt(2 bits),  16)
    for (k <- 0 until 16) {
      if (k >= 1 && k <= 9) {
        // The SAME `lookup` slot 0 uses; `query2Valid` is applied after the mux instead.
        val (kTaken, kTarget, kHit, kType) = lookup(query2BasePc + U(2 * k, 32 bits), True)
        spec2Taken(k)  := kTaken
        spec2Target(k) := kTarget
        spec2Hit(k)    := kHit
        spec2Type(k)   := kType
      } else {
        spec2Taken(k)  := False
        spec2Target(k) := U(0, 32 bits)
        spec2Hit(k)    := False
        spec2Type(k)   := U(0, 2 bits)
      }
    }
    // The late select: `query2Sel` (== L0) is consumed HERE and nowhere earlier.
    val predTaken2Comb  = query2Valid && spec2Taken(query2Sel)
    val predTarget2Comb = spec2Target(query2Sel)
    val predHit2Comb    = query2Valid && spec2Hit(query2Sel)
    val predType2Comb   = spec2Type(query2Sel)

    predTakenComb.simPublic(); predTargetComb.simPublic()
    predTaken2Comb.simPublic(); predTarget2Comb.simPublic()
    predHitComb.simPublic(); predTypeComb.simPublic()
    predHit2Comb.simPublic(); predType2Comb.simPublic()

    // ---- retire-time update (from the ROB's BtbUpdateService) ----
    val upd = host[BtbUpdateService].btbUpdate
    val uIdx   = idxOf(upd.payload.pc)
    val uTag   = tagOf(upd.payload.pc)
    val uOld   = mem.readAsync(uIdx)
    val uValid = valids(uIdx)
    // Hit on THIS branch's entry (same tag): a real counter update. Else (cold or a
    // tag-aliased other branch) it is a fresh allocation overwriting the slot.
    val uEntryHit = uValid && (uOld.tag === uTag)
    // Counter update: taken -> min(c+1,3); not-taken -> max(c-1,0). Allocation seeds
    // counter := taken ? 2 : 1 (weakly, in the resolved direction); unconditional pegs 3.
    val cInc = Mux(uOld.counter === U(3, 2 bits), U(3, 2 bits), (uOld.counter + 1).resized)
    val cDec = Mux(uOld.counter === U(0, 2 bits), U(0, 2 bits), (uOld.counter - 1).resized)
    val ctrUpdated = Mux(upd.payload.taken, cInc, cDec)
    val ctrAlloc   = Mux(upd.payload.brType === U(1, 2 bits), U(3, 2 bits),
                     Mux(upd.payload.taken, U(2, 2 bits), U(1, 2 bits)))
    val newEntry = BtbEntry(tagBits)
    newEntry.tag     := uTag
    newEntry.target  := upd.payload.target
    newEntry.brType  := upd.payload.brType
    newEntry.counter := Mux(uEntryHit, ctrUpdated, ctrAlloc)

    val memWrEn = Bool(); memWrEn := False
    mem.write(uIdx, newEntry, enable = memWrEn)
    when(upd.valid) {
      memWrEn := True
      valids(uIdx) := True
    }

    // ---- invalidateAll: clear every valid bit (BTB re-learns; no snoop) ----
    when(invalidateAll) {
      for (i <- 0 until entries) valids(i) := False
    }
  }
}
