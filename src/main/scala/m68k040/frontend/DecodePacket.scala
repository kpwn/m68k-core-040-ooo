package m68k040.frontend

import spinal.core._

/** One m68k instruction handed to the (future) decode stage. */
case class DecodePacket() extends Bundle {
  val valid     = Bool()
  val pc        = UInt(32 bits)
  val words     = Vec(Bits(16 bits), 5)   // opword + up to 4 following words (10 bytes max simple)
  val wordCount = UInt(3 bits)            // valid words in `words` (1..5)
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
}
