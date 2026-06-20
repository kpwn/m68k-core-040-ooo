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

/** The registered BTB prediction handed to FetchAlign: a fetch window predicted-taken.
  *  - target   : the predicted-taken target (the next fetch PC).
  *  - branchPc : the predicted branch instruction's PC (so the aligner attributes the
  *               prediction to the right word + suppresses post-branch words). */
case class PredictRedirect() extends Bundle {
  val target   = UInt(32 bits)
  val branchPc = UInt(32 bits)
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
    val invalidateAll = Bool()
    invalidateAll.allowOverride; invalidateAll := False
    queryPcPort = queryPc; queryValidPort = queryValid; invalidatePort = invalidateAll

    // ---- combinational lookup (async read) ----
    val qIdx   = idxOf(queryPc)
    val qTag   = tagOf(queryPc)
    val qEntry = mem.readAsync(qIdx)
    val qValid = valids(qIdx)
    val qHit   = qValid && (qEntry.tag === qTag)
    // predict-taken: a hit with the bimodal counter in the taken band (>=2) OR an
    // unconditional (brType==1 — force-taken even on the cycle it is first installed).
    val qPredTaken = qHit && ((qEntry.counter >= U(2, 2 bits)) || (qEntry.brType === U(1, 2 bits)))

    // ---- REGISTERED lookup output (the redirect path starts here) ----
    val predValid    = RegInit(False)
    val predTarget   = Reg(UInt(32 bits))
    val predBranchPc = Reg(UInt(32 bits))
    predValid.simPublic(); predTarget.simPublic(); predBranchPc.simPublic()
    when(queryValid) {
      predValid    := qPredTaken
      predTarget   := qEntry.target
      predBranchPc := queryPc
    } otherwise {
      predValid    := False
    }

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
