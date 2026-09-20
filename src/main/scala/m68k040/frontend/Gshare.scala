package m68k040.frontend

import m68k040.Global
import m68k040.services.{FtbLookupCmd, GshareUpdateService, GshareWindowRsp, GshareWindowService}
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
  *  - invalidateAll (IN)           : ghr/ghrArch := 0 on the I-cache invalidate (PHT self-retrains).
  *  - flushRepair (IN)             : the ROB's registered commit-flush pulse; arms the
  *                                   whole-GHR repair one cycle later (see below).
  *
  * Recovery = WHOLE-GHR REPAIR FROM THE ARCHITECTURAL HISTORY. `ghrArch` mirrors `ghr`
  * but shifts at RETIRE, with the RESOLVED direction, on exactly the branches the fetch
  * side shifted for; on the ROB's registered commit flush (`flushRepair`) the whole
  * speculative `ghr` is overwritten with it, two cycles later (`RegNext` -> no new
  * combinational term on the redirect path). Wrong-path bits are therefore ERASED
  * rather than left to shift out over the next ghrBits branches. Without this a
  * mispredicting alternating branch folds a DIFFERENT GHR on every execution, lands on
  * a different PHT entry, and the 2-bit counters never accumulate -- "trains one entry,
  * then reads another" rather than "learns slowly".
  *
  * The branch EU still verifies every direction/target, so a gshare miss remains a pure
  * perf loss. */
class GsharePlugin extends FiberPlugin with GshareUpdateService with GshareWindowService {

  // ---- public update port (exposed via the service; the ROB drives it at retire) ----
  // Declared at build (idxBits known then); the service accessor reads logic.updateFlow.
  var updateFlow: Flow[m68k040.services.GshareUpdate] = null
  var windowCmdFlow: Flow[FtbLookupCmd] = null
  var windowRspFlow: Flow[GshareWindowRsp] = null
  override def gshareUpdate: Flow[m68k040.services.GshareUpdate] = updateFlow
  override def windowCmd: Flow[FtbLookupCmd] = windowCmdFlow
  override def windowRsp: Flow[GshareWindowRsp] = windowRspFlow

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
    // ---- flushRepair (IN): the ROB's REGISTERED commit-time flush pulse ----------
    // One cycle wide and already a flop at the source (RobPlugin's `doFlushReg`). It is
    // consumed ONLY through `repairArm = RegNext(flushRepair)` below, so this plugin adds
    // NO combinational term to the redirect / ftbBlocked cone: the repair deliberately
    // lands TWO cycles after the flush. This was originally justified by a full refetch;
    // Tier-1 redirect can now preserve an already-refetched frontend across Tier 2.
    // See the measured recovery limitation at repairArm below.
    val flushRepair = Bool();        flushRepair.allowOverride; flushRepair := False

    // ---- combinational read: phtTaken (>=2) + the 11-bit index, per query PC ----
    val phtIndex0 = indexOf(queryPc0)
    val phtIndex1 = indexOf(queryPc1)
    val phtTaken0 = (pht.readAsync(phtIndex0) >= U(2, 2 bits))
    val phtTaken1 = (pht.readAsync(phtIndex1) >= U(2, 2 bits))
    phtIndex0.simPublic(); phtIndex1.simPublic()
    phtTaken0.simPublic(); phtTaken1.simPublic()

    // Registered four-word window lookup for the fetch-directed FTB. All four reads
    // use the same pre-edge GHR and return with the exact fetch-ring token at cmd+1.
    val winCmd = Flow(FtbLookupCmd())
    winCmd.valid.allowOverride; winCmd.valid := False
    winCmd.payload.windowPc.allowOverride; winCmd.payload.windowPc := U(0, 32 bits)
    // Shared `FtbLookupCmd` bundle; gshare ignores `drop` (amendment §2.1.1) but must
    // still default-drive it so the Flow is complete when no consumer is elaborated.
    winCmd.payload.drop.allowOverride; winCmd.payload.drop := U(0, 2 bits)
    winCmd.payload.token.ringSlot.allowOverride; winCmd.payload.token.ringSlot := U(0, 2 bits)
    winCmd.payload.token.seq.allowOverride; winCmd.payload.token.seq := U(0, 8 bits)
    windowCmdFlow = winCmd

    val winIdxComb = Vec(UInt(idxBits bits), 4)
    val winTakenComb = Vec(Bool(), 4)
    for (i <- 0 until 4) {
      winIdxComb(i) := indexOf(winCmd.payload.windowPc + U(i * 2, 32 bits))
      winTakenComb(i) := pht.readAsync(winIdxComb(i)) >= U(2, 2 bits)
    }
    val winPayload = Reg(GshareWindowRsp(idxBits))
    when(winCmd.valid) {
      winPayload.token := winCmd.payload.token
      for (i <- 0 until 4) {
        winPayload.taken(i) := winTakenComb(i)
        winPayload.phtIdx(i) := winIdxComb(i)
      }
    }
    val winRsp = Flow(GshareWindowRsp(idxBits))
    winRsp.valid := RegNext(winCmd.valid) init False
    winRsp.payload := winPayload
    windowRspFlow = winRsp
    winRsp.valid.simPublic(); winRsp.payload.flatten.foreach(_.simPublic())

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
    // shift in the predicted direction bit: ghr := (ghr << 1) | shiftDir. This is the
    // PRIMARY (and, absent a flush, the only) producer of history; the repair below
    // never inserts single bits alongside it, it overwrites the whole register.
    when(shiftValid) {
      ghr := (ghr(ghrBits - 2 downto 0) ## shiftDir).asUInt
    }

    // ---- ARCHITECTURAL (retire-time) GHR + whole-GHR repair on a commit flush -----
    // `ghrArch` is the history of RETIRED branches only. It shifts on exactly the same
    // event set as the speculative `ghr` -- `upd.valid` pulses once per retiring branch
    // that carried a fetch-time `phtValid`, and FetchAlignPlugin stamps `phtValid` on
    // EXACTLY the branches it raises `gsShiftValid` for -- but with the
    // RESOLVED direction instead of the predicted one, and in strict program order
    // (retire is in-order). So `ghrArch` is, by construction, the bit-exact GHR the
    // fetch side WOULD have had if every prediction had been right.
    //
    // On a commit-time flush every non-retired entry is discarded, so at that instant
    // the correct speculative history IS `ghrArch`. `repairArm` therefore OVERWRITES
    // THE WHOLE REGISTER with it. This is a repair, never a per-bit insertion: the
    // fetch-time shift stays the primary/only producer of history in the common case,
    // and nothing is ever interleaved between the two sources, so the GHR cannot stop
    // representing the executed path.
    //
    // TIMING. `upd.valid` and the ROB's `doFlushReg` are both RegNext of the SAME
    // retire-cycle decision, so they are coincident; `ghrArch` therefore absorbs the
    // flushing branch's own bit at the end of that cycle, and `repairArm` (one more
    // flop) reads the already-updated value. Two cycles late on purpose.
    val ghrArch = RegInit(U(0, ghrBits bits)); ghrArch.simPublic()
    when(upd.valid) {
      ghrArch := (ghrArch(ghrBits - 2 downto 0) ## upd.payload.taken).asUInt
    }
    val repairArm = RegNext(flushRepair) init False; repairArm.simPublic()
    // Repair WINS over a same-cycle speculative shift (whole-GHR overwrite semantics).
    // Known performance limitation: Tier 1 can emit conditional history before Tier 2
    // preserves that frontend. This overwrite then deletes those retained speculative
    // bits. PipelineProfileSpec's history-window/IPC_GHR_TRACE observations reproduce
    // it; flush->first-retire latency does NOT prove fetch->history-shift separation.
    // Repair must distinguish a kept frontend from a discarded one before changing
    // this behavior. Branch execution still checks predictions for correctness.
    when(repairArm) {
      ghr := ghrArch
    }
    // ---- invalidateAll: clear BOTH histories (PHT left as-is; it self-retrains) ----
    // Last, so it beats the repair: the repair would otherwise reinstall the pre-clear
    // `ghrArch` (its own clear lands on the same edge).
    when(invalidateAll) {
      ghr := U(0, ghrBits bits)
      ghrArch := U(0, ghrBits bits)
    }
  }
}
