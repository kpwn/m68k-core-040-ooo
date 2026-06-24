package m68k040.frontend

import spinal.core._

/** One m68k instruction handed to the (future) decode stage. */
case class DecodePacket() extends Bundle {
  val valid     = Bool()
  val pc        = UInt(32 bits)
  val words     = Vec(Bits(16 bits), 6)   // opword + up to 5 following words (12 bytes max; full-format
                                          // memory-indirect bd.l+od.l = 1 op + 5 EA-ext words = 6 words)
  val wordCount = UInt(3 bits)            // valid words in `words` (1..6)
  val simple    = Bool()
  val lenWords  = UInt(3 bits)            // predecode length in words (meaningful iff simple)
  val complex   = Bool()                  // !simple
  val fault     = Bool()
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
}
