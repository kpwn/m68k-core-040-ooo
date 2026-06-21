package m68k040.frontend

import m68k040.Global
import m68k040.services.GshareUpdateService
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** gshare / GHR direction predictor (fetch-time predictor, slice 3 of 3).
  *
  * OVERRIDES the DIRECTION of CONDITIONAL branches that hit the BTB. The BTB still
  * supplies the TARGET; gshare supplies `predTaken = phtTaken` for a `condBtbHit`
  * (btbHit && brType==cond). Unconditionals, returns (RAS), and BTB misses are
  * UNTOUCHED. A speculative global-history register (GHR) plus a pattern-history table
  * (PHT) of 2-bit saturating counters captures correlated / data-dependent conditional
  * patterns the slice-1 per-PC bimodal misses (e.g. the alternating `beq`).
  *
  * Storage:
  *  - ghr : Reg(UInt(ghrBits))           the speculative global history (shift register).
  *  - pht : Mem(UInt(2 bits), phtEntries) 2-bit saturating counters, init weakly-taken (2)
  *          so a cold conditional the BTB tracks (it has a target ⇒ taken ≥ once) predicts
  *          taken until trained.
  *
  * Index (classic gshare): phtIndex(pc) = fold(pc[31:1], idxBits) XOR fold(ghr, idxBits)
  *  - `fold` reduces a wider value to idxBits by XOR-ing its idxBits-wide chunks (the PC
  *    is 31 bits → folded; the GHR is ghrBits → folded). Documented below.
  *
  * Ports (directionless plain wires — the BtbPlugin/RasPlugin convention; driven/read by
  * the wiring layer, idle-defaulted with concrete zeros so a standalone DUT elaborates):
  *  - queryPc0/1, queryValid0/1   : the two per-instruction lookup PCs (aligner slot0/1).
  *  - phtTaken0/1 (comb, OUT)      : pht.readAsync(index) >= 2 for that PC (the direction).
  *  - phtIndex0/1 (comb, OUT, 11b) : the folded XOR index that lookup read (the carry-down).
  *  - shiftValid + shiftDir (IN)   : GHR shift control (from FetchAlign; shift on every
  *                                   emitted predicted conditional, dir = predicted bit).
  *  - update (Flow, IN via service): retire-time PHT train { index, taken }.
  *  - invalidateAll (IN)           : ghr := 0 on the I-cache invalidate (PHT self-retrains).
  *
  * Recovery = ACCEPT CORRUPTION (no GHR checkpoint/restore): after a mispredict the GHR
  * carries wrong-path bits that shift out naturally; the branch EU always verifies the
  * actual direction/target → a gshare misprediction is only a perf loss (the existing
  * commit-time redirect recovers). Mirrors the RAS. */
class GsharePlugin extends FiberPlugin with GshareUpdateService {

  // ---- public update port (exposed via the service; the ROB drives it at retire) ----
  // Declared at build (idxBits known then); the service accessor reads logic.updateFlow.
  var updateFlow: Flow[m68k040.services.GshareUpdate] = null
  override def gshareUpdate: Flow[m68k040.services.GshareUpdate] = updateFlow

  val logic = during build new Area {
    val ghrBits    = Global.GHR_BITS.get
    val phtEntries = Global.PHT_ENTRIES.get
    require((phtEntries & (phtEntries - 1)) == 0, "gshareEntries must be a power of two")
    val idxBits    = log2Up(phtEntries)            // = 11 for 2048

    // ---- fold: reduce a wide UInt to idxBits by XOR-ing its idxBits-wide chunks ----
    // e.g. fold(pc[31:1]) over 31 bits with idxBits=11 → chunk0[10:0] ^ chunk1[21:11] ^
    // chunk2[31:22]; fold(ghr) over 16 bits → chunk0[10:0] ^ chunk1[15:11] (the high
    // chunk is zero-extended). XOR-folding spreads the upper PC/GHR bits into the index.
    def fold(v: UInt): UInt = {
      val w = v.getWidth
      var acc = U(0, idxBits bits)
      var lo = 0
      while (lo < w) {
        val hi = scala.math.min(lo + idxBits - 1, w - 1)
        acc = acc ^ v(hi downto lo).resize(idxBits)
        lo += idxBits
      }
      acc
    }

    // ---- storage ----
    // GHR : RegInit(0) so SpinalSim does not seed-randomize it (a randomized GHR would
    //       flip the index and make the directed test flaky).
    val ghr = RegInit(U(0, ghrBits bits)); ghr.simPublic()
    // PHT : a Mem of 2-bit counters, init weakly-taken (2). A power-of-two depth + an
    //       async read off the folded XOR index (LUTRAM, like the BTB tagMem).
    val pht = Mem(UInt(2 bits), phtEntries) init Seq.fill(phtEntries)(U(2, 2 bits))

    // ---- index helper (combinational) ----
    def indexOf(pc: UInt): UInt = fold(pc(31 downto 1)) ^ fold(ghr)

    // ---- ports (directionless plain wires, idle-defaulted with concrete zeros) ----
    val queryPc0    = UInt(32 bits); queryPc0.allowOverride;    queryPc0    := U(0, 32 bits)
    val queryValid0 = Bool();        queryValid0.allowOverride; queryValid0 := False
    val queryPc1    = UInt(32 bits); queryPc1.allowOverride;    queryPc1    := U(0, 32 bits)
    val queryValid1 = Bool();        queryValid1.allowOverride; queryValid1 := False
    val shiftValid  = Bool();        shiftValid.allowOverride;  shiftValid  := False
    val shiftDir    = Bool();        shiftDir.allowOverride;    shiftDir    := False
    val invalidateAll = Bool();      invalidateAll.allowOverride; invalidateAll := False

    // ---- combinational read: phtTaken (>=2) + the 11-bit index, per query PC ----
    val phtIndex0 = indexOf(queryPc0)
    val phtIndex1 = indexOf(queryPc1)
    val phtTaken0 = (pht.readAsync(phtIndex0) >= U(2, 2 bits))
    val phtTaken1 = (pht.readAsync(phtIndex1) >= U(2, 2 bits))
    phtIndex0.simPublic(); phtIndex1.simPublic()
    phtTaken0.simPublic(); phtTaken1.simPublic()

    // ---- retire-time PHT update (from the ROB's GshareUpdateService) ----
    // Declared here (idxBits known) + default-driven idle so a standalone DUT elaborates;
    // the ROB OVERRIDES it (allowOverride). saturate ±1 toward the resolved direction.
    val upd = Flow(m68k040.services.GshareUpdate(idxBits))
    upd.valid.allowOverride;        upd.valid        := False
    upd.payload.index.allowOverride;upd.payload.index := U(0, idxBits bits)
    upd.payload.taken.allowOverride;upd.payload.taken := False
    upd.simPublic()
    updateFlow = upd

    val uOld = pht.readAsync(upd.payload.index)
    val uInc = Mux(uOld === U(3, 2 bits), U(3, 2 bits), (uOld + 1).resized)
    val uDec = Mux(uOld === U(0, 2 bits), U(0, 2 bits), (uOld - 1).resized)
    val uNew = Mux(upd.payload.taken, uInc, uDec)
    pht.write(upd.payload.index, uNew, enable = upd.valid)

    // ---- GHR speculative shift (from FetchAlign, on every emitted predicted cond) ----
    // shift in the predicted direction bit: ghr := (ghr << 1) | shiftDir. NOT
    // checkpointed/restored on a flush — wrong-path bits shift out (accept-corruption).
    when(shiftValid) {
      ghr := (ghr(ghrBits - 2 downto 0) ## shiftDir).asUInt
    }
    // ---- invalidateAll: clear the GHR (PHT left as-is; it self-retrains) ----
    when(invalidateAll) {
      ghr := U(0, ghrBits bits)
    }
  }
}
