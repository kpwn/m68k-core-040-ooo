package m68k040.cache

import m68k040.services.{FetchService, TranslationService, PrivilegeService}
import m68k040.frontend.PredecodeWord
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4Config, Axi4ReadOnly, Axi4}
import spinal.lib.fsm._
import spinal.lib.misc.plugin.FiberPlugin

/** Synthesizable VIPT L1 instruction cache.
  *
  * Geometry: 64 sets, 4 ways, 64-byte lines (16 KiB total).
  * AXI read interface: 256-bit (32 B/beat) × 2 beats per line refill.
  * Implements FetchService (cmd/rsp) and resolves TranslationService.
  *
  * FSM: IDLE → REFILL → REPLAY → IDLE.
  */
class IcachePlugin extends FiberPlugin with FetchService {

  // ---- geometry (single source of truth) ----
  private val geo          = CacheGeometry.l1i040
  geo.requireViptSafe(4096, "L1I")         // alias-safety guard (4 KiB pages)
  private val ways         = geo.ways
  private val sets         = geo.sets
  private val tagBits      = geo.tagBits
  private val beatsPerLine = geo.lineBytes / 32   // 256-bit (32 B) beats
  private val setBits      = geo.indexBits
  private val wayBits      = log2Up(ways)

  val axiCfg = Axi4Config(addressWidth = 32, dataWidth = 256, idWidth = 2)

  // ---- logic Area (built during build phase) ----
  val logic = during build new Area {

    // ---- FetchService ports: plain directionless Stream/Flow (NOT slave/master) ----
    val cmdPort       = Stream(FetchCmd())
    val rspPort       = Flow(FetchRsp())
    val axi           = master(Axi4ReadOnly(axiCfg))
    val invalidateAll = in Bool()

    // ---- resolve TranslationService ----
    val xlate = host[TranslationService]
    // The current architectural S bit (ROB-owned, same signal the privilege-violation
    // check gates on) — an instruction fetch's function code must reflect the ACTUAL
    // current privilege level, not a hardcoded one. A hardcoded False here meant any
    // supervisor-only code page (the normal kernel configuration) permission-denied
    // EVERY fetch once the MMU was enabled — a permanent boot-blocker (the CPU could
    // never fetch its own supervisor code). Mirrors the DTLB-side fix in LsEuPlugin.
    // `host.get` (optional): a standalone I-cache DUT with no RobPlugin/PrivilegeService
    // wired defaults to False (user), exactly the prior hardcoded behavior — unchanged
    // for every existing non-full-core test.
    val privCtrl = host.get[PrivilegeService]
    val activePc = UInt(32 bits)
    xlate.req.valid      := True
    xlate.req.vpn        := activePc(31 downto 12)
    xlate.req.supervisor := privCtrl.map(_.supervisor).getOrElse(False)
    xlate.req.write      := False

    // ---- storage arrays ----
    // Tags + pred: async-read LUTRAM (single write port -> distributed RAM). Kept
    // async so hit/miss is resolved in the accept cycle (a miss starts REFILL with
    // no added latency).
    val tagMem  = Seq.fill(ways)(Mem(UInt(tagBits bits), sets))
    // Packed per-line predecode width = ChunkPredecode's bit width * 32 words/line. Was a
    // hardcoded 128 (= 4 bits/word * 32) back when ChunkPredecode was 4 bits; DERIVED now
    // (deep-audit F1/F2/F3, 2026-07-11: ChunkPredecode widened 4->5 bits, so this grows to
    // 160) so a future width change can't silently desync this packing from the real size.
    val PRED_BITS_PER_WORD = ChunkPredecode().getBitsWidth
    val PRED_BITS_PER_LINE = PRED_BITS_PER_WORD * 32
    val predMem = Seq.fill(ways)(Mem(Bits(PRED_BITS_PER_LINE bits), sets))
    // Data: synchronous-read BRAM. 2 beats x 64 sets = 128 entries per way.
    val dataMem = Seq.fill(ways)(Mem(Bits(256 bits), sets * beatsPerLine))
    // Valid bits: register array, cleared by invalidateAll
    val valids  = Vec.fill(ways)(Vec.fill(sets)(RegInit(False)))
    // Round-robin victim pointer per set
    val victim  = Vec.fill(sets)(RegInit(U(0, wayBits bits)))

    // ---- invalidateAll: priority clear of all valid bits ----
    when(invalidateAll) {
      for (w <- 0 until ways; s <- 0 until sets) valids(w)(s) := False
    }

    // ---- T-stage: REGISTERED ITLB translation (FMax pipeline split) ----
    // The cmd is accepted into this stage once the ITLB has RESOLVED the translation
    // (xlate.rsp.ready). We REGISTER the translated physical address ({ppn, pc[11:0]})
    // + fault here, so the I-cache hit-detect below tags on the REGISTERED physical
    // paddr (tPaddr) instead of the LIVE `xlate.rsp.ppn`. This removes the ITLB `Tlb`
    // way-mux / walk-latch mux from the VIPT hit cone (tag-compare -> hit ->
    // dataMem ENARDEN / s1Pred), which was the post-ITLB route-dominated limiter.
    // VIPT: index on the page-invariant pc[11:6] set bits; tag on the registered
    // physical bits. MMU-off (identity, ppn==pc[31:12]) flows through the SAME
    // register so both modes are pipelined uniformly. Costs ONE translate-latency
    // cycle on a fetch (the frontend is single-outstanding + latency-agnostic).
    val tValid = RegInit(False)
    val tPc    = Reg(UInt(32 bits))
    val tPaddr = Reg(UInt(32 bits))   // {ppn, pc[11:0]} captured at translate time
    val tFault = RegInit(False)

    // ---- miss-state latches ----
    val missPC    = Reg(UInt(32 bits))
    val missPA    = Reg(UInt(32 bits))   // translated physical line address (refill AXI)
    val missSet   = Reg(UInt(setBits bits))
    val missTag   = Reg(UInt(tagBits bits))
    val victimWay = Reg(UInt(wayBits bits))
    val beatCnt   = Reg(UInt(1 bits)) init U(0, 1 bits)
    val arSent    = Reg(Bool()) init False
    val lineReg   = Reg(Bits(512 bits))

    // ---- shared data-array read port (synchronous; BRAM) ----
    // Address+enable are driven by the FSM (IDLE hit accept, or REPLAY). The
    // result `dataBeat` is the registered BRAM output, valid the NEXT cycle.
    val dataReadAddr = UInt((setBits + 1) bits)
    val dataReadEn   = Bool()
    dataReadAddr := U(0, (setBits + 1) bits)
    dataReadEn   := False
    val dataBeat = Vec(dataMem.map(_.readSync(dataReadAddr, dataReadEn)))

    // ---- S1 (response-build) pipeline registers ----
    // The cycle after a read is launched, dataBeat is ready: mux by the latched
    // hit-way, lane-select, and register into the rsp output stage below.
    val s1Valid = Reg(Bool()) init False
    val s1Way   = Reg(UInt(wayBits bits))
    val s1Pc    = Reg(UInt(32 bits))
    val s1Fault = Reg(Bool())
    val s1Lane  = Reg(UInt(2 bits))
    // FMax: register the RAW per-way predMem entries (the readAsync results, indexed
    // by the page-invariant set bits — NOT a hit-way select), and defer the way-mux +
    // window-decode to the S1->rsp stage keyed off the REGISTERED s1Way. This mirrors
    // the data path (s1Beat = dataBeat(s1Way)) and keeps the hitWayIdx/predMem way-mux
    // + windowPred OUT of the IDLE consume cone (which was the route-dominated arc into
    // s1Pred). A fault placeholder registers all-zero entries (windowPred of zero = a
    // zeroed predecode, matching the old getZero placeholder).
    val s1PredEntries = Reg(Vec(Bits(PRED_BITS_PER_LINE bits), ways))
    s1Valid := False   // default each cycle; armed in IDLE-hit / REPLAY below

    // ---- rsp output register stage ----
    val rspValidReg = Reg(Bool()) init False
    val rspPcReg    = Reg(UInt(32 bits))
    val rspDataReg  = Reg(Bits(64 bits))
    val rspFaultReg = Reg(Bool())
    val rspPredReg  = Reg(Vec(ChunkPredecode(), 4))

    // ---- window predecode helper ----
    // A "window" = 4 words (8 bytes, matching the 64-bit FetchRsp granularity). A 64B line
    // holds 32/4=8 windows, selected by pc(5:3) (3 bits, 0..7 — independent of
    // PRED_BITS_PER_WORD, so this selector width is unaffected by the F1/F2/F3 widening).
    // Each window-chunk is 4*PRED_BITS_PER_WORD bits (was the hardcoded 16 = 4*4 when
    // ChunkPredecode was 4 bits); each of the 4 per-window entries is PRED_BITS_PER_WORD
    // bits (was the hardcoded 4).
    def windowPred(entry: Bits, pc: UInt): Vec[ChunkPredecode] = {
      val win  = entry.subdivideIn(4 * PRED_BITS_PER_WORD bits)(pc(5 downto 3))
      val nibs = win.subdivideIn(PRED_BITS_PER_WORD bits)
      Vec(nibs.map(b => b.as(ChunkPredecode())))
    }

    // ---- S1 -> rsp output register (runs every cycle; meaningful when s1Valid) ----
    // Way-mux the registered raw beats/pred by s1Way, then window-decode — all off
    // REGISTERED state (s1Way/s1Lane/s1Pc), so neither the data nor the pred select
    // is in the IDLE hit cone.
    val s1Beat   = dataBeat(s1Way)
    val s1Window = s1Beat.subdivideIn(64 bits)(s1Lane)
    val s1PredW  = windowPred(s1PredEntries(s1Way), s1Pc)
    rspValidReg := s1Valid
    rspPcReg    := s1Pc
    rspDataReg  := s1Window
    rspFaultReg := s1Fault
    rspPredReg  := s1PredW

    // ---- rsp outputs (combinational from the registered stage) ----
    rspPort.valid         := rspValidReg
    rspPort.payload.pc    := rspPcReg
    rspPort.payload.data  := rspDataReg
    rspPort.payload.fault := rspFaultReg
    rspPort.payload.pred  := rspPredReg

    // ---- default output assignments ----
    cmdPort.ready := False
    axi.ar.valid  := False
    axi.ar.payload.assignDontCare()
    axi.r.ready   := False
    activePc      := cmdPort.payload.pc

    // ---- hit detection (combinational, from the REGISTERED T-stage paddr) ----
    // VIPT: index with the page-invariant pc[11:6] set bits (from the registered tPc);
    // tag with the REGISTERED physical page number tPaddr[31:12]. The live ITLB
    // way-mux is NOT in this cone (it was already resolved into tPaddr the prior
    // cycle), so the tag-compare -> hit -> dataMem ENARDEN arc is route-clean.
    val idlePc    = tPc
    val idleSet   = idlePc(11 downto 6)
    val idleTag   = tPaddr(31 downto 12)
    val hitVec = Vec(Bool(), ways)
    for (w <- 0 until ways)
      hitVec(w) := valids(w)(idleSet) && (tagMem(w).readAsync(idleSet) === idleTag)
    val isHit       = hitVec.orR
    val hitWayIdx   = OHToUInt(hitVec)
    val idleBeatSel = idlePc(5)
    val idleLaneIdx = idlePc(4 downto 3)
    val idleReadAddr  = (idleSet ## idleBeatSel).asUInt
    val idlePredEntry = Vec(predMem.map(_.readAsync(idleSet)))

    // Depth-2 pipelined accept (VARIANT 1): the cache is a flow-through 3-stage pipe
    // (T-stage -> S1 -> rsp), each a single register that ADVANCES every cycle. The
    // T-stage is consumed (freed) every IDLE cycle it is valid, so a NEW cmd may be
    // accepted INTO the T-stage the SAME cycle the prior translation is consumed into
    // S1 — i.e. ≥2 fetches in flight (T + S1 + rsp). The downstream S1/rsp never
    // back-pressure (flow-through), so the only accept block is the FSM not being in
    // IDLE (a refill in progress). The single-outstanding `inFlight` gate is REMOVED;
    // accept gates ONLY on the ITLB resolve (xlate.rsp.ready) inside IDLE. The consume
    // `tValid := False` is reordered to NOT clobber a same-cycle cmdPort.fire (see the
    // tAccept/tConsume split below) so the back-to-back accept is not dropped.

    // ---- FSM ----
    val fsm = new StateMachine {
      val IDLE      = new State with EntryPoint
      val REFILL    = new State
      val PREDECODE = new State
      val REPLAY    = new State

      // ----- IDLE: T-accept (translate) + consume the REGISTERED T-stage -----
      // Two decoupled, flow-through actions (depth-2: accept may overlap consume):
      //
      // (1) T-ACCEPT: present `cmdPort.pc` to the ITLB; once it has RESOLVED the
      //     translation (xlate.rsp.ready), REGISTER {ppn,pc[11:0]} + fault into the
      //     T-stage. MMU-off identity is always ready, so MMU-disabled fetch is
      //     unchanged. On an ITLB MISS rsp.ready is LOW (the walker is running): we do
      //     not accept (stall) until it resolves.
      // (2) CONSUME: when a translated fetch is registered (tValid), tag-compare off
      //     the REGISTERED tPaddr (the live ITLB way-mux is OUT of this cone) and
      //     either emit a fault placeholder, arm the S1 hit read, or start a refill
      //     from the registered physical line base. A resolved translation that FAULTS
      //     (non-resident / supervisor I-page) is consumed as a fault placeholder
      //     (s1Fault) WITHOUT a refill — the rsp carries fault -> DecodePacket.fault.
      IDLE.whenIsActive {
        activePc      := cmdPort.payload.pc
        // Depth-2 accept: gate ONLY on the ITLB resolve (no single-outstanding block).
        // The T-stage is freed every IDLE cycle (consume below), so a new cmd may enter
        // it the same cycle. NOTE: a MISS this cycle leaves IDLE (goto REFILL); the
        // accept is still permitted (cmdPort.ready is combinational and we are still in
        // IDLE), and the same-cycle-accepted translation is held in the T-stage and
        // re-consumed after the refill returns to IDLE — so it is NOT lost.
        cmdPort.ready := xlate.rsp.ready

        // (2) Consume the registered translation FIRST (so its `tValid := False` is
        // OVERRIDDEN by a same-cycle (1) T-accept's `tValid := True` below — the
        // depth-2 back-to-back accept must not be dropped). The consume reads the
        // REGISTERED tPc/tPaddr (old values), unaffected by the new accept's writes.
        when(tValid) {
          // FMax: arm the data-BRAM read whenever a translated fetch is present —
          // INDEPENDENT of the hit/fault decision. The result is only CONSUMED when
          // s1Valid is set (a real hit below), so a redundant read on a miss/fault
          // cycle is harmless (power only). Off the registered tPaddr, so the tag-
          // compare is no longer in the data-BRAM `ENARDEN` critical path.
          dataReadAddr := idleReadAddr
          dataReadEn   := True
          tValid := False   // consumed this cycle (a same-cycle accept re-sets it True)

          // Register the RAW per-way predMem entries (way-mux + window-decode deferred
          // to S1 keyed off s1Way). On a fault placeholder the entries are zeroed
          // (windowPred(0) == the old getZero placeholder).
          when(tFault) {
            // Translation fault: emit a fault response (no data, no refill).
            s1Valid := True
            s1Way   := U(0, wayBits bits)
            s1Pc    := idlePc
            s1Fault := True
            s1Lane  := idleLaneIdx
            s1PredEntries := Vec.fill(ways)(B(0, PRED_BITS_PER_LINE bits))
          } elsewhen(isHit) {
            // Hit: the data BRAM read is already armed above; latch the S1 control.
            s1Valid := True
            s1Way   := hitWayIdx
            s1Pc    := idlePc
            s1Fault := False
            s1Lane  := idleLaneIdx
            s1PredEntries := idlePredEntry
          } otherwise {
            missPC    := idlePc
            missSet   := idleSet
            missTag   := idleTag
            // PHYSICAL line base for the refill: the registered translated PA =
            // {ppn, pc[11:0]} (MMU-off identity: ppn == pc[31:12], == the VA).
            missPA    := tPaddr
            victimWay := victim(idleSet)
            beatCnt   := U(0, 1 bits)
            arSent    := False
            goto(REFILL)
          }
        }

        // (1) T-accept: register the resolved translation. Placed LAST so its
        // `tValid := True` wins over the consume's `tValid := False` on a back-to-back
        // accept cycle (depth-2). On a miss cycle this same-cycle accept is preserved
        // across the refill (tValid stays True through REFILL/PREDECODE/REPLAY and is
        // consumed on the IDLE return).
        when(cmdPort.fire) {
          tValid := True
          tPc    := cmdPort.payload.pc
          tPaddr := (xlate.rsp.ppn ## cmdPort.payload.pc(11 downto 0)).asUInt
          tFault := xlate.rsp.fault
        }
      }

      // ----- REFILL: issue AXI AR; collect 2 R beats into dataMem -----
      REFILL.whenIsActive {
        activePc := missPC
        // Refill from the PHYSICAL line base (missPA, the translated PA); identity
        // when the MMU is off (missPA == missPC).
        val lineBase = missPA & ~U(63, 32 bits)

        when(!arSent) {
          axi.ar.valid         := True
          axi.ar.payload.addr  := lineBase
          axi.ar.payload.id    := U(0, 2 bits)
          axi.ar.payload.len   := U(1, 8 bits)
          axi.ar.payload.size  := U(5, 3 bits)
          axi.ar.payload.burst := Axi4.burst.INCR
          when(axi.ar.ready) { arSent := True }
        }

        axi.r.ready := True
        when(axi.r.valid) {
          val writeAddr = (missSet ## beatCnt).asUInt
          for (w <- 0 until ways) {
            when(victimWay === U(w, wayBits bits)) {
              dataMem(w).write(writeAddr, axi.r.payload.data)
            }
          }
          when(beatCnt === U(0, 1 bits)) {
            lineReg(255 downto 0)   := axi.r.payload.data
          } otherwise {
            lineReg(511 downto 256) := axi.r.payload.data
          }
          beatCnt := beatCnt + 1
          when(axi.r.payload.last) { goto(PREDECODE) }
        }
      }

      // ----- PREDECODE: classify line, write predMem + tag/valid -----
      PREDECODE.whenIsActive {
        activePc := missPC
        val words  = lineReg.subdivideIn(16 bits)
        // Per-word predecode. A full-format indexed EA's length depends on its EXTENSION
        // word: at op+1 (an EA-first op) or op+2 (a line-0 immediate / static bit-op, whose
        // EA ext follows a 1-word imm/bit word). Pass words(i+1) + words(i+2) (0 past the
        // line end — a full-format opword whose ext word spills to the next line is the
        // inherent per-line predecode boundary).
        //
        // F5 FIX (deep-audit 2026-07-11): the OLD code zero-filled the spilled word and
        // relied on a "handled on re-frame" comment for a re-frame mechanism that never
        // existed anywhere in the tree — a zero-filled ext word reads as brief-format
        // (bit8=0), so any full-format op landing at this exact per-line boundary silently
        // mis-framed as brief (same silent-corruption class as F1), and nothing ever
        // corrected it. There is no general "wait for the next line" mechanism here (predecode
        // runs per-line, independently, before the next line may even be fetched), so —
        // mirroring how this predecoder already treats any OTHER out-of-scope EA (reject ->
        // COMPLEX, the assembler's illegal/refetch path) — we now tell `classify` explicitly
        // that a boundary-zero-filled word is NOT real data (`extWValid`/`extW2Valid`, below).
        // `classify` then refuses to guess brief-vs-full for anything that would need to read
        // that word's content (mode 6 / mode7-reg3 full-format detection) and instead rejects
        // (COMPLEX) — a safe trap instead of a silent mis-frame. `i` is a plain Scala Int
        // here (this whole predecode is elaborated once per line-word, 32 instances), so
        // "is word i+1/i+2 past the line end" is a compile-time constant, not new hardware.
        // task #153 (ported-tests memind cluster): also pass a 3rd lookahead word (op+3) —
        // needed ONLY to correctly frame a line-0 .L-immediate op with a full-format
        // mem-indirect destination (the 2-word .L immediate pushes the EA's first ext word
        // from op+2 to op+3, one word beyond the original 2-word lookahead). `words` is the
        // WHOLE cache line, already resident in `lineReg` this same cycle (see the F5 comment
        // above) — words(i+3) costs nothing new in hardware, just a wider static mux inside
        // `classify` itself. Same F5 boundary discipline: unavailable (line-end) -> `classify`
        // falls back to its pre-existing "assume brief" framing for this specific shape (NOT
        // the F5 reject-as-COMPLEX doctrine — see `classify`'s extW3 comment for why).
        val nWords = words.length
        val chunks = Vec((0 until nWords).map(i =>
          PredecodeWord.classify(words(i),
            if (i + 1 < nWords) words(i + 1) else B(0, 16 bits),
            if (i + 2 < nWords) words(i + 2) else B(0, 16 bits),
            if (i + 3 < nWords) words(i + 3) else B(0, 16 bits),
            extWValid  = i + 1 < nWords,
            extW2Valid = i + 2 < nWords,
            extW3Valid = i + 3 < nWords)))
        val packed = chunks.asBits
        for (w <- 0 until ways) {
          when(victimWay === U(w, wayBits bits)) {
            predMem(w).write(missSet, packed)
            tagMem(w).write(missSet, missTag)
            valids(w)(missSet) := True
          }
        }
        victim(missSet) := victim(missSet) + 1
        goto(REPLAY)
      }

      // ----- REPLAY: arm S1 read for the just-filled line, then IDLE -----
      REPLAY.whenIsActive {
        activePc := missPC
        val replayBeatSel  = missPC(5)
        val replayReadAddr = (missSet ## replayBeatSel).asUInt
        val replayPredEntry = Vec(predMem.map(_.readAsync(missSet)))

        dataReadAddr := replayReadAddr
        dataReadEn   := True
        s1Valid := True
        s1Way   := victimWay
        s1Pc    := missPC
        // A refill only happens for a NON-faulting translation (the T-stage fault
        // path emits a placeholder without refilling), so the replayed line is fault-
        // free by construction — off the live ITLB rsp entirely.
        s1Fault := False
        s1Lane  := missPC(4 downto 3)
        s1PredEntries := replayPredEntry

        goto(IDLE)
      }
    }

    // FetchService accessors
    def cmd: Stream[FetchCmd] = cmdPort
    def rsp: Flow[FetchRsp]   = rspPort
  }

  // ---- FetchService trait implementation ----
  override def cmd: Stream[FetchCmd] = logic.cmdPort
  override def rsp: Flow[FetchRsp]   = logic.rspPort
}
