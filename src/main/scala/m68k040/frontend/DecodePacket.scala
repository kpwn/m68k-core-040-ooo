package m68k040.frontend

import spinal.core._

/** One m68k instruction handed to the (future) decode stage. */
case class DecodePacket() extends Bundle {
  val valid     = Bool()
  val pc        = UInt(32 bits)
  // Widened 6->10 (deep-audit F3, 2026-07-11): sized to the front-end's actual visibility
  // ceiling (InstructionBuffer.HEAD_WORDS / Aligner.WINDOW = 10), not just the single-EA
  // full-format case. A MOVE with TWO full-format/mem-indirect EAs (e.g. mem-indirect src
  // + (d16,An) dest) can legally need 7 words; sizing to the full 10-word front-end window
  // (rather than a narrower guess) means the packet never silently truncates ANY
  // instruction the aligner could otherwise present as `simple` — PredecodeWord.classify
  // gates any (rare) longer combination as COMPLEX instead of over-running this array.
  val words     = Vec(Bits(16 bits), 10)
  val wordCount = UInt(4 bits)            // valid words in `words` (1..10)
  val simple    = Bool()
  val lenWords  = UInt(4 bits)            // predecode length in words (meaningful iff simple)
  val complex   = Bool()                  // !simple
  val fault     = Bool()
  // Task #211: which cause raised `fault` — True (default/don't-care-safe) for the
  // pre-existing ITLB/MMU-detected translation fault, False for a physical AXI bus
  // error (SLVERR/DECERR) on an I-cache REFILL. Meaningful only when `fault` is set;
  // carried from IcachePlugin's FetchRsp.atc through FetchAlignPlugin's synthetic
  // faulted packet. Mirrors LsFault.atc on the D-side (task #189).
  val faultAtc  = Bool()
  // ── Fetch-time branch prediction (BTB + bimodal, slice 1) ───────────────────
  // predTaken : this instruction's fetch window hit the BTB with predict-taken (the
  //   front-end was redirected to predTarget on its behalf). predTarget : the target
  //   fetch was redirected to (meaningful iff predTaken). Both ride fetch->decode->
  //   rename->IQ/ROB->branch EU so the branch EU can verify predicted-vs-actual.
  //   Non-branch / not-predicted packets carry predTaken=False, predTarget=0.
  val predTaken  = Bool()
  val predTarget = UInt(32 bits)
  // ── gshare direction-predictor carry-down (slice 3) ─────────────────────────
  // phtValid : this instruction is a CONDITIONAL branch that hit the BTB and whose
  //   DIRECTION was sourced from the gshare PHT (condBtbHit at fetch). It carries
  //   `phtIndex` (the 11-bit fetch-time folded-XOR index the lookup read) down to
  //   retire so the ROB trains the EXACT entry the lookup read (the GHR at retire
  //   differs from the GHR at the lookup — only the carried index is right).
  //   Non-conditional / not-gshare-predicted packets carry phtValid=False.
  val phtValid   = Bool()
  val phtIndex   = UInt(11 bits)
  // ── Branch-prediction SIDE-CHANNEL tag (see Global.BR_PRED_TABLE_DEPTH) ─────
  // Names the DecodeStage table entry holding this packet's {predTaken, predTarget,
  // phtValid, phtIndex}. The four fields above stay on the PACKET (only two packet
  // registers exist per pipeline stage, so they are not the area problem); what they
  // no longer do is ride every DecodedUop. 0 = "inert / no prediction" -- a reserved
  // tag that is never allocated, so it needs no table entry.
  // Allocated in FetchAlignPlugin, written (idempotently, from `fed`) in DecodeStage.
  val brPredTag  = UInt(m68k040.Global.BR_PRED_TAG_W bits)
  // ── FMax "Lever B" (2026-08-08): precomputed operand size ────────────────────
  // `OperationDecoder.decode(words(0)).size`, baked at I-cache REFILL time into
  // `ChunkPredecode.size` and carried here by the Aligner. `MicroOpAssembler.computeOffload`
  // consumes THIS instead of re-deriving the size in series with the destination-EA decode
  // — that serial arc was 9 of the 21 logic levels (44%) of the design's OOC WNS path.
  //
  // INVARIANT (the whole correctness argument, gated live by `FedSpecsPacketPairingSpec`):
  //   pkt.size === OperationDecoder.decode(pkt.words(0)).size
  // It holds by construction rather than by proof — `PredecodeWord.classify` populates
  // `ChunkPredecode.size` by CALLING the real decoder — so the only way it can break is
  // PLUMBING (a `preds` index paired with the wrong `words` index, a default that doesn't
  // match its co-located zeroed word, or a stash captured under a different enable).
  //
  // Only meaningful on packets the Aligner actually emits. Hand-built `DecodePacket`s in
  // unit tests leave it unassigned, which is why `assemble(pkt)` (the 1-arg overload)
  // routes to `computeOffloadFromWords` and never reads this field.
  val size       = m68k040.isa.Size()
}
