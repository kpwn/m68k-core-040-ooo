package m68k040.frontend

import m68k040.Global
import m68k040.cache.{ChunkPredecode, FetchCmd, FetchRsp, IcacheInstructionOrder}
import m68k040.services.{DecodeFeedService, FetchService, FrontendQuiesceService,
  FtbLookupCmd, FtbLookupRsp, FtbLookupService, GshareWindowRsp, GshareWindowService}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** FetchAlignPlugin: integrates FetchService (I-cache), InstructionBuffer, and
  * Aligner into a 2-wide DecodePacket stream (DecodeFeedService).
  *
  * Key design points:
  *  - Depth-3 in-order fetch ring: resident hits may occupy all three I-cache
  *    stages, including same-cycle head-response/tail-replacement turnover.
  *  - Per-request leading-word drop on redirect/resume: only enqueue words from
  *    decodePc's offset within the fetched 8-byte target window.
  *  - Per-request staleness: responses for pre-redirect requests are dropped.
  *  - Complex-stall: emit the complex packet once, then hold feed.valid low
  *    until resume. Emit-once is enforced by the `stalled` latch: when a complex
  *    packet fires, `stalled` latches True the next cycle and suppresses
  *    feed.valid until resume clears it. (No separate complexPending delay; the
  *    complex packet is valid for one cycle, observed by per-cycle sampling.)
  */
case class FetchTargetQueueEntry() extends Bundle {
  val brPc     = UInt(32 bits)
  val brLen    = UInt(4 bits)
  val target   = UInt(32 bits)
  val phtIdx   = UInt(11 bits)
  val isCond   = Bool()
}

class FetchAlignPlugin(enableFetchDirected: Boolean = false, ftqDepth: Int = 32,
                       deferSlot1Conditional: Boolean = false)
    extends FiberPlugin with DecodeFeedService {

  require(ftqDepth > 0 && (ftqDepth & (ftqDepth - 1)) == 0,
    "FTQ depth must be a positive power of two")

  val logic = during build new Area {
    // Driven by the top level from the CPUSH/CINV I-cache invalidate pulse (the same
    // `icMaintPulse` that feeds IcachePlugin.maintInvalidateAll and the BTB). Defaults
    // False so a DUT that wires nothing keeps the previous behaviour.
    val icMaintFlush = Bool(); icMaintFlush.allowOverride; icMaintFlush := False

    // ── The REGISTERED arm. Every internal consumer uses THIS, never the port ─────
    // FMax (2026-09-17). `icMaintFlush` is a combinational function of the ROB
    // EXCEPTION FSM state -- `icMaintPulse = isActive(S_MAINTWAIT) && maintDoneIn &&
    // (cacheSel === IC || cacheSel === BC)`, OR'd at the top level with the debug
    // bridge's own maintenance-done pulse. Feeding that raw into `ftbBlocked` broke the
    // design contract this file already records on `applyNow` -- "every remaining term is
    // a register or a one-level select of one" -- of which `ftbBlocked` is a term, and
    // which the FTQ-capacity note on `ftbBlocked` restates by REFUSING to admit a live
    // `ftqCount === 32` comparator there. `ftbBlocked` sits at the head of the frontend's
    // longest cone --
    //     ftbBlocked -> applyNow -> cmdWindowPc -> ic.cmd.pc
    //                -> live ITLB CAM/permission -> L1I cacheability
    //                -> speculative prefetch installer control
    // -- and anything joining it is carried through all of it in ONE cycle.
    //
    // Measured on `build/vivado200_allfix` place.dcp (200 MHz, 5.000 ns):
    //   -1.524 ns, 24 levels, logic 1.625 / route 4.709
    //   RobPlugin_logic_exc_fsm_stateReg_reg[1]/C  (fo=120, 0.628 ns of route)
    //     -> ... -> FetchAlignPlugin_logic_applyNow (fo=146)
    //     -> ItlbPlugin missReqReg_vpn -> the 32-entry ITLB CAM + CARRY8 -> tlb_io_hit
    //     -> IcachePlugin lookupPageCacheable -> s0Cacheable -> nonSpecFetch
    //     -> mshrPa -> IcachePlugin_logic_pfNextPa_reg[*]/CE
    // and 9 further paths differing only in the destination `pfNextPa` bit. Those TEN are
    // the only violated setup paths the CORE contributes to that run at all; every other
    // violated endpoint in it is SoC infrastructure at >= -0.370 ns.
    //
    // THE SAME ENDPOINT ALREADY FAILED before this arm existed, and the comparison is the
    // whole argument for the cut. Same flow, same worktree, one change (`build/
    // vivado200_rob32`):
    //   -0.429 ns, 22 levels, data path 5.205 (logic 1.191 / route 4.014)
    //   FetchAlignPlugin_logic_quiesce_reg/C -> IcachePlugin_logic_pfNextPa_reg[7]/CE
    // i.e. the TAIL (a `ftbBlocked` term -> applyNow -> ... -> pfNextPa/CE) is
    // pre-existing and structural. The exception-FSM source added +2 logic levels,
    // +0.434 ns of logic and +0.695 ns of route -- 1.129 ns of pure PREFIX -- in front of
    // it. `quiesce` is itself a `RegNext` of the ROB's published next-state for exactly
    // this reason (see its declaration above); this register is that same treatment, and
    // should return the endpoint to the quiesce-sourced level.
    //
    // WHY ONE CYCLE OF LATENCY IS FREE, and why it does not weaken the flush.
    // ALL THIRTEEN consumers move together -- the action block below AND the six shared
    // decision terms -- so the arm stays self-consistent by construction, which is the
    // entire property the completeness fix exists to hold. It is a pure retime of one
    // restart arm, not a partial one; a partial cut (action at C, `issueBornStale` at
    // C+1) would re-open hole 1 verbatim and is the one thing NOT to do here.
    //
    // The arm's job is to land strictly BETWEEN the I-cache invalidate and the
    // architectural refetch. Both fences have a full cycle of room:
    //   C   : `S_MAINTWAIT && maintDoneIn` -> `icMaintPulse`. `maintInvalidateAll`
    //         clears the I-cache/BTB/FTB valids at this edge (effective C+1).
    //         `goto(S_REDIR)`.
    //   C+1 : THIS ARM fires. The I-cache is already empty, so the refetch it seeds
    //         cannot repopulate from a line the CPUSH was meant to drop -- the ordering
    //         the action block's own comment demands, with one cycle MORE margin than
    //         the unregistered form had. `S_REDIR` asserts `exc.redirectValid`.
    //   C+2 : `RobPlugin.doFlushReg` -> `redirect.valid` here -> `commonRedirect` to
    //         `sysCapNextPc`, which supersedes this arm (the redirect block is textually
    //         later, so it wins) and is the architecturally correct restart.
    // `excActive = (fsm.state =/= IDLE)` is high at C and C+1 and `doFlush` at C+2, so
    // `pipeFlush` is CONTINUOUS across the gap: any uop decoded out of the pre-flush
    // IBuf during cycle C is squashed and refetched after C+2 regardless. That is not a
    // new reliance -- the frontend free-runs for the WHOLE maintenance walk (1500+
    // cycles for a BC-selector CPUSH ALL, `ic.cmd.valid` has no `excActive` term), so
    // one more speculative cycle before the buffer drop changes nothing architectural.
    //
    // The three holes the completeness fix closed, re-checked at the new cycle:
    //   1. `issueBornStale`. A command firing at C+1 is born stale AND has its ring
    //      record marked stale in the same cycle -> `resultStaleProof` holds. A command
    //      that fired at C launched a LIVE lookup; at C+1 the oracle compares
    //      `ringStale(resultSlot)` (still False -- `ringStale.foreach(_ := True)` is a
    //      register write landing at C+2) against `resultExpectedBornStale` (False).
    //      They AGREE. In the unfixed RTL they disagreed precisely because the ring
    //      write happened at C while the born-stale flag did not; shifting the whole arm
    //      keeps the two on the same edge, which is what the oracle actually checks.
    //   2. `ftqFlush`. The FTQ entries and `targetHoldValid` describing the discarded
    //      stream are dropped at C+1, before the C+2 refetch can be steered by them.
    //      Anything pushed at C describes the still-current stream and is flushed with
    //      the rest.
    //   3. `ftbBlocked`. No FTB plan can be APPLIED on the flush cycle. The plan from
    //      the C command is vetoed at C+1 by this very term, and its ring window is
    //      marked stale in the same cycle, so its cache response is discarded too.
    // The debug-bridge source needs no argument at all: the bridge issues maintenance
    // only while the core is halted, and `quiesce` already holds `ic.cmd.valid` low and
    // `ftbBlocked` high for the whole of it.
    val icMaintFlushArm = RegNext(icMaintFlush) init False
    icMaintFlushArm.simPublic()

    // ---- Resolve I-cache service ----
    val ic = host[FetchService]

    // Production/full-core instances opt into the registered-token services. Small
    // standalone frontend fixtures retain an inert local Flow so they do not need a
    // predictor-training/ROB stack merely to test framing or cache handshakes.
    val ftbCmd = if (enableFetchDirected) host[FtbLookupService].lookupCmd else Flow(FtbLookupCmd())
    val ftbRsp = if (enableFetchDirected) host[FtbLookupService].lookupRsp else Flow(FtbLookupRsp())
    val ftbClear = if (enableFetchDirected) host[FtbLookupService].clearOne else Flow(UInt(32 bits))
    val gshareWindowCmd = if (enableFetchDirected) host[GshareWindowService].windowCmd else Flow(FtbLookupCmd())
    val gshareWindowRsp = if (enableFetchDirected) host[GshareWindowService].windowRsp else Flow(GshareWindowRsp(11))
    if (!enableFetchDirected) {
      ftbRsp.valid := False
      ftbRsp.payload.assignFromBits(B(0, ftbRsp.payload.getBitsWidth bits))
      gshareWindowRsp.valid := False
      gshareWindowRsp.payload.assignFromBits(B(0, gshareWindowRsp.payload.getBitsWidth bits))
    }

    // ---- Sub-component: InstructionBuffer ----
    val ibuf = new InstructionBuffer()

    // ---- Public ports (accessible from testbench via dut.fa.logic.*) ----
    // feed is a plain directionless Stream (service convention: plain wires so a
    // sibling plugin in the same Component can drive feed.ready without hierarchy
    // violation; matches IcachePlugin.cmdPort convention).
    val feed          = Stream(Vec(DecodePacket(), 2))   // producer drives valid/payload; consumer drives ready
    spinal.core.sim.SimPublic(feed.valid, feed.ready, feed.payload(0).pc)
    spinal.core.sim.SimPublic(feed.payload(0).simple, feed.payload(0).lenWords)
    val slot1ValidOut = out(Bool())
    val slot1Valid    = slot1ValidOut   // alias for testbench access via logic.slot1Valid
    val redirect      = slave(Flow(UInt(32 bits)))
    val resume        = slave(Flow(UInt(32 bits)))
    // Commit-time mispredict redirect (full core): a sibling wiring plugin drives
    // this from the ROB's RedirectService. Directionless, idle-defaulted with
    // CONCRETE zeros (allowOverride) — NOT assignDontCare (which would hide the
    // sibling's drive / a sim poke from the consumer). Highest fetch-redirect
    // priority (applied last, below).
    val mispredictRedirect = Flow(UInt(32 bits))
    mispredictRedirect.valid.allowOverride;   mispredictRedirect.valid   := False
    // Sim-only: the DELIVERED redirect, at the port, before FetchAlign consumes it. The
    // whole redirect-delivery question is "does this equal the ROB's resolved target,
    // and does the fetch pointer end up there", so both halves must be observable.
    mispredictRedirect.valid.simPublic(); mispredictRedirect.payload.simPublic()
    mispredictRedirect.payload.allowOverride; mispredictRedirect.payload := U(0, 32 bits)

    // axi-socket adapter D12/D16: the reset-vector reader's redirect. Declared with the
    // SAME shape as mispredictRedirect above and for the same reason -- the `redirect`
    // slave port at :72 is an INPUT of M68kCore and a sibling plugin cannot drive it (see
    // the `feed` comment at :64-66). Idle-defaulted with CONCRETE zeros and allowOverride,
    // never assignDontCare, so a sibling's drive is not hidden from the consumer. With no
    // ResetVectorPlugin in the plugin list this stays constantly idle and folds away.
    val resetRedirect = Flow(UInt(32 bits))
    resetRedirect.valid.allowOverride;   resetRedirect.valid   := False
    resetRedirect.payload.allowOverride; resetRedirect.payload := U(0, 32 bits)

    // vio-jtag-debug spec V17/V19: the VIO boot-PC injector's own redirect source. Same
    // directionless/allowOverride/concrete-idle-default shape as resetRedirect immediately
    // above (a sibling plugin cannot drive `redirect` itself -- that port is an INPUT of
    // M68kCore, per the FetchAlignPlugin.scala:64-66 comment both resetRedirect and this seam
    // already cite). Priority: placed after the `resume` arm and before the
    // `mispredictRedirect` arm (see the priority-arm comment at that `when(vioRedirect.valid)`
    // block below for why -- there are actually THREE automatic/external arms ahead of it,
    // not the two the original design spec assumed).
    val vioRedirect = Flow(UInt(32 bits))
    vioRedirect.valid.allowOverride;   vioRedirect.valid   := False
    vioRedirect.payload.allowOverride; vioRedirect.payload := U(0, 32 bits)

    // ── STOP/fatal-halt quiesce ─────────────────────────────────────────────────
    // The ROB remains the sole state owner. It publishes the exact combinational
    // NEXT state through a setup-allocated service; registering that truth locally
    // makes this bit change on the same edge as `stopped || coreHalted` while cutting
    // the measured remote ROB -> predictor/ITLB/I-cache cone. A standalone FetchAlign
    // fixture without a ROB service remains unquiesced.
    val quiesceService = host.get[FrontendQuiesceService]
    val quiesceNext = Bool()
    val quiesceActive = Bool()
    quiesceService match {
      case Some(q) =>
        quiesceNext   := q.next
        quiesceActive := q.active
      case None =>
        quiesceNext   := False
        quiesceActive := False
    }
    val quiesce = RegNext(quiesceNext) init False
    quiesce.simPublic()
    quiesceNext.simPublic()
    quiesceActive.simPublic()
    GenerationFlags.simulation {
      when(!ClockDomain.current.isResetActive) {
        assert(quiesce === quiesceActive,
          "FetchAlign local quiesce diverged from ROB architectural halt state",
          FAILURE)
      }
    }

    // ── Fetch-time BTB prediction interface (slice 1) ────────────────────────────
    // FetchAlign DRIVES the two per-instruction query PCs (slot0/slot1 of the aligner
    // head) + valids; the BtbPlugin returns the COMBINATIONAL prediction this cycle.
    // Directionless plain wires (the IcachePlugin convention): the wiring layer
    // connects the query OUTPUTS to the BTB and the prediction INPUTS back. Concrete
    // idle defaults so FetchAlign elaborates standalone (predictor inert).
    //   btbQueryPc0/1, btbQueryValid0/1 : DRIVEN here (the aligner slot PCs; slot1's
    //     pair also doubles as the gshare slot-1 query below).
    //   btbPredTaken0, btbPredTarget0 : INPUT (the BTB's combinational predict for slot0
    //     only). The slot-1 speculative BTB read ("Lever D", the `query2*`/`spec2*` block
    //     in `Btb.scala`) was deleted (task #248): it was the design's #1 failing setup
    //     cone, and its only consumer (the old BTB-sourced `slot1WouldPred` slot1 defer)
    //     is now redundant with `slot1WouldFtq` below, sourced from the fetch-directed
    //     FTB/FTQ (task #126) instead — a 4-bit register compare, not a speculative
    //     9-way BTB read.
    val btbQueryPc0    = UInt(32 bits); val btbQueryValid0 = Bool()
    val btbQueryPc1    = UInt(32 bits); val btbQueryValid1 = Bool()
    val btbPredTaken0  = Bool(); val btbPredTarget0 = UInt(32 bits)
    // Inputs: idle-defaulted (allowOverride) so a standalone DUT (no BTB) reads not-taken.
    btbPredTaken0.allowOverride;  btbPredTaken0  := False
    btbPredTarget0.allowOverride; btbPredTarget0 := U(0, 32 bits)

    // ── Fetch-time RAS interface (return-address stack, slice 2) ─────────────────
    // FetchAlign DRIVES the push (call retPC) + pop (predicted return) and READS the
    // combinational predict (top-of-stack, valid iff non-empty). Directionless plain
    // wires (the BtbPlugin convention): the wiring layer connects push/pop OUT to the
    // RasPlugin and the predict inputs back. Concrete idle defaults so FetchAlign
    // elaborates standalone (RAS inert: no predict -> rasPredValid reads False).
    //   rasPushValid/rasPushRetPc, rasPopValid : DRIVEN here (the emitted call/return).
    //   rasPredValid/rasPredTarget : INPUT (the RAS's combinational top-of-stack).
    // Push/pop are OUTPUTS always driven by FetchAlign below (no idle default — they
    // are assigned exactly once, like the btbQueryPc* ports).
    val rasPushValid = Bool(); val rasPushRetPc = UInt(32 bits)
    val rasPopValid  = Bool()
    val rasPredValid = Bool(); val rasPredTarget = UInt(32 bits)
    rasPredValid.allowOverride;  rasPredValid  := False
    rasPredTarget.allowOverride; rasPredTarget := U(0, 32 bits)

    // ── Fetch-time gshare interface (direction predictor, slice 3) ───────────────
    // FetchAlign DRIVES the two per-instruction query PCs (same slot0/slot1 PCs as the
    // BTB) + the GHR shift (shiftValid/shiftDir) on the emitted predicted conditional,
    // and READS the combinational PHT direction (gsPhtTaken0/1) + the folded-XOR index
    // (gsPhtIndex0/1) for the carry-down. Directionless plain wires (the BtbPlugin
    // convention): the wiring layer connects query/shift OUT to the GsharePlugin and the
    // predict inputs back. Concrete idle defaults so FetchAlign elaborates standalone
    // (gshare inert: gsPhtTaken reads not-taken, gsPhtIndex 0).
    //   btbQueryPc0/1 (above) double as the gshare query PCs (same aligner slot PCs).
    //   gsBtbHit0, gsBtbType0 : the BTB hit + brType (INPUT, to form condBtbHit) for
    //     slot0 only (slot1's BTB hit/type input was deleted with Lever D; see above).
    //   gsPhtTaken0/1 : the PHT direction for that PC (INPUT).
    //   gsPhtIndex0/1 : the 11-bit folded-XOR index (INPUT, carried down).
    //   gsShiftValid/gsShiftDir : the GHR shift (OUTPUT, driven below).
    val gsBtbHit0   = Bool(); val gsBtbType0 = UInt(2 bits)
    val gsPhtTaken0 = Bool(); val gsPhtIndex0 = UInt(11 bits)
    val gsPhtTaken1 = Bool(); val gsPhtIndex1 = UInt(11 bits)
    gsBtbHit0.allowOverride;   gsBtbHit0   := False
    gsBtbType0.allowOverride;  gsBtbType0  := U(0, 2 bits)
    gsPhtTaken0.allowOverride; gsPhtTaken0 := False
    gsPhtIndex0.allowOverride; gsPhtIndex0 := U(0, 11 bits)
    gsPhtTaken1.allowOverride; gsPhtTaken1 := False
    gsPhtIndex1.allowOverride; gsPhtIndex1 := U(0, 11 bits)
    // GHR shift outputs (driven below on the emitted predicted conditional).
    val gsShiftValid = Bool()
    val gsShiftDir   = Bool()

    // ---- State registers ----
    val decodePc      = Reg(UInt(32 bits)) init 0
    val fetchPc       = Reg(UInt(32 bits)) init 0   // 8-aligned
    spinal.core.sim.SimPublic(decodePc, fetchPc)
    // FMax (frontend floor, 2026-09-15 — the `decodePc_reg[*]/D` family, measured at
    // -0.632 ns / 22 logic levels in the SoC build, the deepest cone in the design).
    //
    // `decodePc` closes a COMBINATIONAL LOOP ON ITSELF in one cycle:
    //   decodePc -> `ftqDiff = ftqHeadE.brPc - decodePc` (32-bit subtract, 4 chained
    //   CARRY8) -> `ftqNear`/`ftqDelta` -> `availEff` -> `Aligner.align` -> slot lengths
    //   -> `decodePc + len*2` (a second 32-bit carry chain) -> the `suppressSlot1` mux
    //   -> decodePc.
    // Both 32-bit ripples are avoidable WITHOUT touching the loop's cycle structure,
    // because both of them only ever need a ±32-byte window of the PC:
    //   * the FTQ compare asks `brPc - decodePc ∈ [0,31]` (a 5-bit subtract plus an
    //     equality on the upper 27 bits), and
    //   * the decode advance adds at most WINDOW*2 = 20 < 32 bytes.
    // So the upper 27 bits of `decodePc` only ever move by 0 or +1. Keeping
    // `decodePc(31 downto 5) + 1` LIVE IN A REGISTER turns both 27-bit ripples into a
    // registered operand: the compare becomes a 27-bit equality tree (2 levels, no
    // carry) and the advance becomes a 27-bit 2:1 select (1 level, no carry).
    //
    // `decodePcHiP1` is a pure DERIVED register: the invariant
    // `decodePcHiP1 === decodePc(31 downto 5) + 1` holds on EVERY cycle, and is asserted
    // as such in simulation below. It is maintained at every one of `decodePc`'s writers
    // via `setDecodePc` / the sequential-advance arm; nothing else may write it.
    val decodePcHiP1  = Reg(UInt(27 bits)) init 1    // == decodePc(31 downto 5) + 1
    val decodePcHi    = decodePc(31 downto 5)        // pure slice, free
    spinal.core.sim.SimPublic(decodePcHiP1)
    // Every NON-sequential `decodePc` writer goes through this helper so the companion
    // register can never be forgotten. The 27-bit increment it carries is off the
    // frontend loop by construction: every call site's `x` is a register output or an
    // FTQ-memory read, never a value derived from `decodePc` this cycle.
    def setDecodePc(x: UInt): Unit = {
      decodePc     := x
      decodePcHiP1 := x(31 downto 5) + 1
    }
    val stalled       = Reg(Bool()) init False       // complex-instruction stall
    val started       = Reg(Bool()) init False       // don't fetch until first redirect
    // Sim-only taps (2026-09-18, redirect-delivery trace). `quiesce` was already
    // published; these three are the rest of the front end's "why am I not fetching"
    // state, and without them a hang cannot be told apart from a wrong redirect target.
    // `.simPublic()` emits no hardware -- it only keeps the net name through elaboration.
    stalled.simPublic(); started.simPublic()
    // I-fetch fault hold: a translation fault (ITLB non-resident / protect) on a
    // fetch response. We STOP issuing further real fetches immediately (the fetch
    // pipeline can be up to RING windows ahead of decode), but do NOT immediately
    // squash whatever is still legitimately buffered in the IBuf ahead of decodePc —
    // those are real, already-fetched, pre-fault instructions that must still
    // execute (a real 68040 defers a prefetch fault until the CPU actually reaches
    // it). We only emit the ONE synthetic faulted DecodePacket (slot0.fault, pc =
    // decodePc) once decode has genuinely drained everything buffered before the
    // fault (see `emittingFaultPacket` below) — at that point decodePc IS the
    // faulting instruction's PC. STOP fetching resumes only via a redirect (the
    // exception delivers + vectors, or an earlier buffered branch mispredict-
    // redirects away from the fault entirely — both existing redirect paths already
    // clear faultHold).
    val faultHold     = Reg(Bool()) init False
    faultHold.simPublic()   // sim-only tap, see `stalled`/`started` above
    val faultEmitted  = Reg(Bool()) init False   // the faulted packet was already emitted
    // Task #211: which cause raised the held fault — latched from the FIRST fault
    // response's `atc` bit (True=ITLB/MMU translation fault, False=physical AXI bus
    // error) alongside faultHold below, and carried onto the synthetic faulted
    // DecodePacket's `faultAtc` so it survives to ExceptionUnit's SSW ATC bit.
    val faultAtcHold  = Reg(Bool()) init True

    // Complex emit-once is enforced by the `stalled` latch (no separate delay reg):
    // a complex packet has shiftWords=0, so on its fire only `stalled` advances,
    // gating feed.valid until `resume` clears it.

    // Per-fetch outstanding-record tracking — VARIANT 1: a DEPTH-2 RING (Slice 2
    // generalizes the single-outstanding {recValid,recStale,recDrop} to a depth-N ring
    // now that the I-cache is pipelined). The I-cache pipeline returns responses IN
    // ORDER, so a simple 2-entry FIFO (head=oldest, consumed by the next rsp; tail=newest
    // issued) tracks ≥2 fetches in flight. Each entry carries its OWN stale + leading-drop
    // captured at ISSUE — immune to later redirects overwriting global state (the bug
    // that wedged nested-bsr-during-miss: a stale window mis-attributed to a redirect
    // target). A redirect marks ALL in-flight entries stale (recStale bug class).
    // ANGLE D (depth-3): extend the outstanding ring to 3. The I-cache is a 3-stage
    // flow-through pipe (T-stage -> S1 -> rsp), so up to THREE fetches are physically in
    // flight (one in each stage) before the oldest is consumed. With the 3-cycle hit
    // latency, depth-3 fully hides it -> a fresh 8-byte (4-word) window can land EVERY
    // cycle. The window stays NARROW (push-4) -> the IBuf shift mux stays small (the
    // v3 wide-window FMax limiter is avoided). The ring is parameterized by RING, so the
    // generalization is purely RING=3 (all per-entry: ringStale/ringDrop FIFO of 3).
    val RING = 3
    val ringStale = Vec.fill(RING)(Reg(Bool()) init False)  // per-entry: redirect after issue -> discard rsp
    val ringDrop  = Vec.fill(RING)(Reg(UInt(2 bits)) init 0) // per-entry: leading words to drop (target[2:1])
    val ringKeep  = Vec.fill(RING)(Reg(UInt(3 bits)) init 4) // per-entry: exclusive trailing word, 1..4
    val ringPlanSeq = Vec.fill(RING)(Reg(UInt(8 bits)) init 0)
    val ringHead  = Reg(UInt(log2Up(RING) bits)) init 0      // oldest in-flight: the NEXT rsp belongs to it
    val ringTail  = Reg(UInt(log2Up(RING) bits)) init 0      // next slot to ISSUE into
    val ringCount = Reg(UInt(log2Up(RING + 1) bits)) init 0  // # outstanding (0..RING)
    val planSeq   = Reg(UInt(8 bits)) init 0
    // Back-compat alias: recValid (≥1 outstanding) drives the SAME guards/markers the
    // single-outstanding code used. ringFull blocks issue when both slots are occupied.
    val recValid  = ringCount =/= 0
    val ringFull  = ringCount === RING
    // Modular increment of a ring index (head/tail). RING=3 is NOT a power of two, so the
    // log2Up(RING)=2-bit index cannot rely on natural binary wrap (2+1=3, not 0). Wrap
    // explicitly at RING. For a power-of-two RING this folds to the plain +1 wrap.
    def ringInc(idx: UInt): UInt =
      if (isPow2(RING)) (idx + 1).resized
      else Mux(idx === U(RING - 1, idx.getWidth bits), U(0, idx.getWidth bits), (idx + 1).resized)
    // Leading-word drop intent for the NEXT fetch to issue (set by a redirect to
    // target[2:1]; latched into the ring entry at issue, then cleared).
    val pendingDrop = Reg(UInt(2 bits)) init 0
    spinal.core.sim.SimPublic(recValid, ringCount, ringHead, ringTail, pendingDrop)
    ringDrop.foreach(spinal.core.sim.SimPublic(_))
    ringKeep.foreach(spinal.core.sim.SimPublic(_))
    ringPlanSeq.foreach(spinal.core.sim.SimPublic(_))
    spinal.core.sim.SimPublic(planSeq)

    // Fetch-target queue. The 32-entry depth is the binding legal run-ahead bound:
    // three cache requests plus at most twenty one-word windows resident in the IBuf.
    require(ftqDepth >= RING + ibuf.BUF_WORDS + 1,
      s"FTQ depth $ftqDepth is below the legal frontend run-ahead bound")
    val ftqMem   = Mem(FetchTargetQueueEntry(), ftqDepth)
    val ftqHead  = Reg(UInt(log2Up(ftqDepth) bits)) init 0
    val ftqTail  = Reg(UInt(log2Up(ftqDepth) bits)) init 0
    val ftqCount = Reg(UInt(log2Up(ftqDepth + 1) bits)) init 0
    val ftqHeadE = ftqMem.readAsync(ftqHead)
    val ftqValid = ftqCount =/= 0
    val ftqFull  = ftqCount === ftqDepth
    def ftqInc(idx: UInt): UInt = (idx + 1).resized

    // A registered result may arrive while I-cache command acceptance is blocked.
    // Capture the target once; no younger command can overtake the held command.
    val targetHoldValid = Reg(Bool()) init False
    val targetHoldPc    = Reg(UInt(32 bits)) init 0
    val targetHoldDrop  = Reg(UInt(2 bits)) init 0
    val ftbSuppress     = Reg(Bool()) init False

    // Forward-declared controls whose decisions live after the aligner. The live mismatch
    // detector terminates here in a small recovery register; only that registered action
    // reaches fetchPc, FTB clear, ring staleness, and fetch command control. This cuts the
    // routed async-FTQ-head -> 29-level mismatch -> fetchPc/FTB-valid family while charging
    // one cycle only to a malformed fetch-plan claim.
    val ftqPop = Bool(); ftqPop.allowOverride; ftqPop := False
    val ftqFlush = Bool(); ftqFlush.allowOverride; ftqFlush := False
    val ftqMismatchPending = Reg(Bool()) init False
    // FMax (task #219, Fix 1 -- netlist-grounded against
    // `synth/archive/866437c_fmax_fanout_fix_decode_fetch/fullcore_route_timing.rpt`,
    // Design State: Routed): 26 of that report's 100 worst endpoints were
    // `decodePc_reg[2] -> ftqMismatch{Pc,BrPc}Reg[*]/CE`, i.e. the *clock-enable* pins of
    // these 64 flops, driven by `ftqMismatchDetect` at fo=71 (0.379ns of routing on the
    // final net alone, plus a whole LUT6 level to fold the four detector terms together).
    // The capture DATA is early -- `ftqHeadE.brPc` has no `decodePc` dependence at all, and
    // the PC capture is a 2:1 select between two values that are both already computed. So:
    // capture UNCONDITIONALLY every cycle into `Cur`/`Next`/`Sel` registers, and do the
    // select on the REGISTERED side at C+1.
    //
    // WHY THIS IS EXACTLY EQUIVALENT (not an approximation): `ftqMismatchPcReg` and
    // `ftqMismatchBrPcReg` are read ONLY through `ftqMismatchPc` (-> the `when(ftqMismatch)`
    // recovery block) and `ftbClear.payload` (`ftbClear.valid := ftqMismatch`), so every
    // reader is gated by `ftqMismatch` (== `ftqMismatchPending`). `ftqMismatchPending` has
    // exactly one setter, `when(ftqMismatchDetect)`. So the captured values are observable
    // ONLY on the cycle immediately after a detect -- precisely the cycle on which an
    // unconditional capture still holds the detect cycle's values. Writes on every other
    // cycle are architecturally dead. (And no second capture can land before the read:
    // `ftqMismatchDetect` itself requires `!ftqMismatchPending`.) A sim-only shadow that
    // captures under the ORIGINAL `when(ftqMismatchDetect)` gating and asserts equality on
    // every pending cycle is wired up at the detector site below.
    val ftqMismatchPcCurReg  = Reg(UInt(32 bits)) init 0
    val ftqMismatchPcNextReg = Reg(UInt(32 bits)) init 0
    val ftqMismatchPcSelReg  = Reg(Bool()) init False
    val ftqMismatchBrPcReg   = Reg(UInt(32 bits)) init 0
    val ftqMismatchPcReg     = UInt(32 bits)
    ftqMismatchPcReg := Mux(ftqMismatchPcSelReg, ftqMismatchPcNextReg, ftqMismatchPcCurReg)
    ftqMismatchPending := False
    val ftqMismatchDetect = Bool(); ftqMismatchDetect.allowOverride; ftqMismatchDetect := False
    val ftqMismatchDetectPc = UInt(32 bits)
    ftqMismatchDetectPc.allowOverride
    ftqMismatchDetectPc := decodePc
    val ftqMismatch = Bool(); ftqMismatch := ftqMismatchPending
    val ftqMismatchPc = UInt(32 bits); ftqMismatchPc := ftqMismatchPcReg
    val ftqPush = Bool(); ftqPush.allowOverride; ftqPush := False
    val applyNow = Bool(); applyNow.allowOverride; applyNow := False
    spinal.core.sim.SimPublic(ftqHead, ftqTail, ftqCount, ftqValid, ftqFull)
    spinal.core.sim.SimPublic(targetHoldValid, targetHoldPc, targetHoldDrop, ftbSuppress)
    spinal.core.sim.SimPublic(ftqPop, ftqFlush, ftqMismatchDetect, ftqMismatchPending,
      ftqMismatchPcReg, ftqMismatchBrPcReg, ftqMismatch, ftqPush, applyNow)
    ftqHeadE.flatten.foreach(spinal.core.sim.SimPublic(_))

    // ---- Default-drive IBuf inputs ----
    ibuf.io.push.valid   := False
    ibuf.io.push.payload.words.foreach(_ := 0)
    // FMax Lever B: the `size` default here must be BYTE, not LONG — it pairs with the
    // `words.foreach(_ := 0)` default-drive on the line above, and `decode(0x0000).size`
    // is `Size.BYTE` (`ORI.B #imm,D0`). See ChunkPredecode.size.
    ibuf.io.push.payload.preds.foreach { p =>
      p.simple := False; p.lenWords := 0; p.ambiguousLine := False; p.size := m68k040.isa.Size.BYTE
      // Fail-closed: an un-pushed predecode slot never enables a prediction.
      p.ctrlXfer := False }
    ibuf.io.push.payload.n := 0
    ibuf.io.shift        := 0
    ibuf.io.flush        := False

    // The live decode-time BTB/RAS decision terminates here. `predictDetect` is driven
    // after the aligner; it may only capture the target. The registered one-cycle
    // `predictFire` action owns fetch/ring/FTQ controls, cutting the measured
    // DecodeStage -> fallback -> ITLB CAM -> I-cache-enable timing family without
    // delaying the target command: a detect in C may issue the captured target in C+1.
    val predictDetect = Bool()
    predictDetect.allowOverride
    predictDetect := False
    val predictTargetReg = Reg(UInt(32 bits)) init 0
    val predictPending = Reg(Bool()) init False
    predictPending := False
    val predictFire = predictPending
    spinal.core.sim.SimPublic(predictDetect, predictPending, predictTargetReg, predictFire)

    // Any redirect-like ACTION this cycle. Architectural redirects still issue the old
    // sequential command and make it born stale. The registered fallback action instead
    // selects its captured target at the command mux; its ring entry is re-marked live
    // after the common born-stale assignment below.
    // ── `icMaintFlush` IS a frontend restart arm (2026-09-16) ────────────────────
    // The CPUSH/CINV instruction-buffer flush below (`when(icMaintFlush)`) does the same
    // four things every other restart arm does -- reset decodePc/fetchPc, flush the IBuf,
    // and mark EVERY outstanding ring entry stale -- but it was absent from the four
    // shared decision terms (`redirectThisCycle`, `issueBornStale`, `ftbBlocked`,
    // `ftqFlush`) and from the two pending-action cancels. That left three live holes,
    // none of which any simulation could see because `icMaintFlush` was wired ONLY in
    // FullCoreSynth and in none of the test DUTs:
    //   1. `issueBornStale`: a command accepted on the maintenance-flush cycle still
    //      launched a LIVE FTB/gshare fetch-plan lookup for a window the flush had just
    //      discarded, while `ringStale.foreach(_ := True)` marked its ring record stale.
    //      The two then disagreed at C+1 and the design's own oracle fired:
    //      "delayed fetch-plan stale decision no longer matches its resident ring record"
    //      (reproduced on ifstage_smc_l0_backbranch / _victim_cinv_ic / _victim_dc_ic with
    //      caches ON, within ~100 cycles, once the harnesses wire `icMaintFlush`).
    //   2. `ftqFlush`: the FTQ entries and `targetHoldValid` describing the DISCARDED
    //      stream survived the flush, so the next command could be steered to a held
    //      target from that stream and a stale `brPc` could clamp `availEff` or claim a
    //      confirm against the re-fetched stream.
    //   3. `ftbBlocked`: an FTB plan could still be APPLIED on the flush cycle, truncating
    //      a ring window and pushing an FTQ entry for bytes that are being thrown away.
    // Folding it into the existing terms (rather than adding a parallel decision tree) is
    // what keeps all four consistent by construction.
    val redirectThisCycle = redirect.valid || (resume.valid && stalled) ||
                            mispredictRedirect.valid || predictFire || ftqMismatch ||
                            icMaintFlushArm

    // Join the two fixed-C+1 lookup results. A command which is born stale still launches
    // the I-cache request (its response must drain), but it cannot use a read-only fetch
    // plan. Under ic.cmd.fire the complex-stall/mismatch gates are already false, and the
    // decode-fallback action explicitly re-marks its target live. Thus the final new ring
    // record is stale exactly for an external or registered internal redirect.
    // Suppressing that lookup at ISSUE removes the measured 7.117-ns ringStale -> plan ->
    // ITLB/tag/I-cache cone without adding a cycle or changing the one-command wrong-path
    // bound. The delayed issue/stale state below remains an assertion oracle.
    val issueBornStale = redirect.valid || mispredictRedirect.valid || icMaintFlushArm
    val planLookupFire = ic.cmd.fire && !issueBornStale
    val resultExpectedIssueValid = RegNext(ic.cmd.fire) init False
    val resultExpectedValid = RegNext(planLookupFire) init False
    val resultExpectedBornStale = RegNextWhen(issueBornStale, ic.cmd.fire) init False
    val resultExpectedSlot  = Reg(UInt(ringTail.getWidth bits)) init 0
    val resultExpectedSeq   = Reg(UInt(planSeq.getWidth bits)) init 0
    when(ic.cmd.fire) {
      resultExpectedSlot := ringTail
      resultExpectedSeq  := planSeq
    }
    val resultSlot      = resultExpectedSlot
    val resultSlotLegal = resultSlot < U(RING, resultSlot.getWidth bits)
    val resultProvidersValid = ftbRsp.valid && gshareWindowRsp.valid
    val resultTokenProof = resultExpectedValid && resultProvidersValid && resultSlotLegal &&
      (ftbRsp.payload.token.ringSlot === resultExpectedSlot) &&
      (gshareWindowRsp.payload.token.ringSlot === resultExpectedSlot) &&
      (ftbRsp.payload.token.seq === resultExpectedSeq) &&
      (gshareWindowRsp.payload.token.seq === resultExpectedSeq) &&
      (ringPlanSeq(resultSlot) === resultExpectedSeq) &&
      (ringCount =/= 0)
    val resultStaleProof = resultExpectedIssueValid && resultSlotLegal &&
      (ringStale(resultSlot) === resultExpectedBornStale) &&
      (resultExpectedValid === !resultExpectedBornStale)
    spinal.core.sim.SimPublic(planLookupFire, resultExpectedIssueValid,
      resultExpectedValid, resultExpectedBornStale, resultTokenProof, resultStaleProof)
    // `resultEnd` is still needed at result time: `ringKeep(resultSlot)` is written from
    // it on application. It is a two-register add that terminates at a register write,
    // not an input to the fetch-PC mux.
    val resultEnd = ftbRsp.payload.brWordOff.resize(5) + ftbRsp.payload.brLen.resize(5)
    val resultDirection = (ftbRsp.payload.brType =/= 0) ||
                          gshareWindowRsp.payload.taken(ftbRsp.payload.brWordOff)
    // FMax recovery (framing-verdict retime, amendment §2.1.1). `resultInWindow` and
    // `resultAfterDrop` below are the LIVE oracle/telemetry forms ONLY. The functional
    // application input is the provider's registered `framedOk`, which is the same
    // conjunction evaluated one cycle earlier from the identical values:
    //   * `ringDrop(resultSlot)` at C+1 is bit-identical to the `cmdDrop` that this
    //     window's command carried at C — `ringDrop` has one writer, gated on
    //     `ic.cmd.fire`, writing `ringTail`, and `resultExpectedSlot` latches that same
    //     `ringTail` at the same edge, with the three-deep ring advancing one slot per
    //     fire so no second write can reach the slot in one cycle; and
    //   * `hit`/`brWordOff`/`brLen` are the provider's own asynchronous entry read.
    // The routed `28ec738` checkpoint measured every top-100 path starting at
    // `ringDrop_1_reg[0]/C`: six levels and 1.536 ns of drop compare, in-window sum and
    // application AND-tree sat AHEAD of the ITLB CAM, the L1I tag qualification and the
    // prefetch window seed, all in one cycle. Only the first six levels are removable
    // without changing behaviour, and this is that removal. The equivalence is asserted
    // live below on every result cycle, so a divergence fails loudly instead of
    // silently mis-applying a prediction.
    val resultInWindow = (ftbRsp.payload.brLen =/= 0) && (resultEnd <= U(4, 5 bits))
    val resultAfterDrop = ftbRsp.payload.brWordOff >= ringDrop(resultSlot)
    val resultFramedOk = ftbRsp.payload.framedOk
    val ftbCandidate = resultExpectedValid && resultProvidersValid &&
                       resultSlotLegal && ftbRsp.payload.hit
    // FMax recovery: keep the live IBuf/aligner cone out of the registered-result
    // application and next-fetch command enables. `predictFire` and `ftqMismatch` are
    // now registered actions; before that retiming their decode-local decisions fed
    // ftbBlocked -> applyNow -> ic.cmd.fire and created a 28-level 8.2 ns path from head
    // predecode state into the fetch ring, I-cache, FTB, gshare, and ITLB enables. A
    // coincident physical application is harmless: either registered action drives
    // ftqFlush with last-assignment priority and invalidates the older plan. The same
    // kill-after-apply rule is required for the registered internal redirect: retaining
    // it here created the measured 24-level ROB-flush/exception -> ITLB/I-cache ->
    // target-hold path. External and standalone resume inputs retain the stronger
    // no-physical-application collision contract.
    // FMax recovery (FTQ capacity cut): `ftqFull` is deliberately ABSENT from this term.
    // The binding token amendment's section 5 terminates FTQ capacity at the elaboration
    // bound (`ftqDepth >= RING + BUF_WORDS + 1`) plus the simulation assertion below, not
    // at a live functional veto: every applied prediction consumes either a ring slot or
    // at least one genuine IBuf word, so legal run-ahead cannot exceed RING + BUF_WORDS.
    // `FtqCapacitySpec` measures a peak of 17 of 32 under maximum run-ahead with decode
    // held, full ring turnover, backpressure/target holds, redirects, and refill. Keeping
    // the defensive `ftqCount === 32` comparator here made the FTQ counter the source of
    // every routed top-100 path: ftqFull -> ftbBlocked -> applyNow -> fetch-PC mux ->
    // live ITLB CAM/permission -> L1I tag/way qualification -> speculative installer
    // control, 7.430 ns over 23 levels. Per that amendment, restoring the combinational
    // count equality is NOT an acceptable fallback; an undersized configuration must be
    // rejected at elaboration or given a separately pipelined reservation mechanism.
    val ftbBlocked = redirect.valid || (resume.valid && stalled) ||
                     quiesce || stalled || faultHold || ftbSuppress ||
                     targetHoldValid || icMaintFlushArm
    val ftbDeclineDirection = ftbCandidate && !resultDirection
    val ftbDeclineFraming = ftbCandidate && resultDirection &&
                            !(resultInWindow && resultAfterDrop)
    val ftbDeclineBlocked = ftbCandidate && resultDirection &&
                           resultInWindow && resultAfterDrop && ftbBlocked
    spinal.core.sim.SimPublic(ftbCandidate, ftbDeclineDirection,
      ftbDeclineFraming, ftbDeclineBlocked, resultFramedOk, resultInWindow,
      resultAfterDrop)

    if (enableFetchDirected) {
      when(resultExpectedIssueValid) {
        assert(resultStaleProof,
          "delayed fetch-plan stale decision no longer matches its resident ring record")
        assert(ftbRsp.valid === resultExpectedValid,
          "FTB responded to a born-stale command or dropped a live lookup")
        assert(gshareWindowRsp.valid === resultExpectedValid,
          "gshare responded to a born-stale command or dropped a live lookup")
      }
      when(ftbRsp.valid || gshareWindowRsp.valid || resultExpectedValid) {
        assert(ftbRsp.valid && gshareWindowRsp.valid && resultExpectedValid,
          "FTB/gshare responses are not exactly one cycle after live fetch-plan issue")
        assert(resultTokenProof,
          "fixed-latency fetch-plan response no longer names its resident ring record")
        // Amendment §9 item 8a: the registered framing verdict must equal the live
        // ring-drop oracle on every result cycle. This is the differential proof that
        // the retime below is a pure restructuring; it runs in every existing
        // fetch-directed simulation, not only in a dedicated test.
        when(resultSlotLegal) {
          assert(resultFramedOk === (ftbRsp.payload.hit && resultInWindow && resultAfterDrop),
            "registered FTB framing verdict diverged from its live ring-drop oracle")
        }
      }
      // Functionally identical to `ftbCandidate && resultInWindow && resultAfterDrop &&
      // resultDirection && !ftbBlocked` — `resultFramedOk` already carries the provider's
      // tag hit, so the only change is WHERE the hit/in-window/after-drop conjunction is
      // evaluated. Every remaining term is a register or a one-level select of one.
      applyNow := resultExpectedValid && resultProvidersValid && resultSlotLegal &&
                  resultFramedOk && resultDirection && !ftbBlocked
      when(applyNow) {
        assert(resultSlotLegal && resultEnd > ringDrop(resultSlot).resize(5),
          "applied FTB plan violates its ring slot/drop framing contract")
        assert(!(ic.rsp.valid && (ringHead === resultSlot)),
          "fetch-plan application no longer precedes its associated cache response")
      }
    }

    // Every live accepted cache command launches both registered lookups with the same
    // token. A born-stale cache command intentionally launches neither provider. The
    // providers' ports are directionless service wires with idle defaults, matching the
    // existing BTB/Gshare integration style.
    ftbCmd.valid := planLookupFire
    ftbCmd.payload.windowPc := ic.cmd.payload.pc
    ftbCmd.payload.token.ringSlot := ringTail
    ftbCmd.payload.token.seq := planSeq
    // Amendment §2.1.1: the command also carries this window's leading-word drop, so the
    // provider can register the whole framing verdict. `cmdDrop` is defined with the
    // paired `cmdWindowPc` further down (§3 requires them selected together); the
    // assignment is placed there so the pairing stays impossible to break by editing one
    // of the two. `gshareWindowCmd.payload := ftbCmd.payload` below is a net connection,
    // so it carries whatever `ftbCmd.payload.drop` finally resolves to.
    gshareWindowCmd.valid := planLookupFire
    gshareWindowCmd.payload := ftbCmd.payload
    ftbClear.valid := ftqMismatch
    ftbClear.payload := ftqMismatchBrPcReg

    val ftqPushEntry = FetchTargetQueueEntry()
    ftqPushEntry.brPc   := ftbRsp.payload.windowPc(31 downto 3) @@
                           ftbRsp.payload.brWordOff @@ U(0, 1 bits)
    ftqPushEntry.brLen  := ftbRsp.payload.brLen
    ftqPushEntry.target := ftbRsp.payload.target
    ftqPushEntry.phtIdx := gshareWindowRsp.payload.phtIdx(ftbRsp.payload.brWordOff)
    ftqPushEntry.isCond := ftbRsp.payload.brType === 0
    ftqMem.write(ftqTail, ftqPushEntry, enable = ftqPush)
    ftqPush := applyNow

    // Every redirect-like action clears speculative FTQ/held-target state. Fetch-side
    // application is intentionally absent from this flush term: it truncates one live
    // ring window and appends its target without invalidating any older bytes.
    ftqFlush := redirect.valid || (resume.valid && stalled) ||
                mispredictRedirect.valid || predictFire || ftqMismatch ||
                icMaintFlushArm

    when(applyNow && (predictFire || ftqMismatch || mispredictRedirect.valid)) {
      assert(ftqFlush && redirectThisCycle,
        "registered FTB collision must be killed by redirect/FTQ-flush priority")
    }

    // ---- FetchControl: issue fetches (DEPTH-3 multi-outstanding, ANGLE D) ----
    // Issue while the ring has a free slot — up to 3 in flight. A non-fault response
    // consumes the HEAD this cycle, so it also makes room for a same-cycle replacement
    // even when the pre-cycle count is full. `ic.rsp` is a registered Flow independent
    // of `ic.cmd`, hence this creates no combinational ready loop. Suppress fetching
    // while holding an I-fetch fault OR while STOP-quiesced (wait for the redirect /
    // IRQ-entry vector to clear it).
    //
    // IBuf absorption (depth-3): the IBuf's own push.ready only reserves space for ONE
    // window (the push that lands this cycle). With up to 3 outstanding fetches, ALL three
    // responses (up to 12 words) can land while the consumer is stalled. So the ISSUE gate
    // reserves landing space for EVERY outstanding window PLUS the new one: cnt +
    // (ringCount+1)*4 <= BUF_WORDS. (Reserves a full 4 words/window — a leading-word drop
    // only shrinks a window, so this is a safe upper bound.) With BUF_WORDS=20 the deepest
    // (3rd) outstanding fetch (ringCount=2) admits up to cnt<=8, exactly v1's depth-2 head
    // headroom. This SUPERSEDES the single-window push.ready for the issue decision and is
    // the no-overflow invariant: post-issue worst-case occupancy = cnt + (ringCount+1)*4
    // <= BUF_WORDS (cnt only shrinks via shift before those windows land).
    // The landing reservation deliberately still counts the response arriving THIS
    // cycle among `ringCount`: its up-to-4 words move from the outstanding reservation
    // into the IBuf at the edge, while the replacement adds a new 4-word reservation.
    // Thus the existing `cnt + (ringCount+1)*4` bound remains both safe and exact for
    // the worst case; only the record-slot capacity needs post-consume credit.
    val ibufRoomForIssue =
      (ibuf.io.cnt +^ ((ringCount +^ U(1)) * U(4))) <= U(ibuf.BUF_WORDS)
    // Only a NON-FAULT response earns full-ring turnover credit. On a fault,
    // `faultHold` first latches at this edge; admitting a replacement from the
    // pre-edge `!faultHold` value would widen speculation by one younger request.
    // When the ring is not full, behavior remains exactly as before.
    val fullRingTurnover = ic.rsp.valid && !ic.rsp.payload.fault
    val ringSlotAvailable = !ringFull || fullRingTurnover
    val directTargetPc = ftbRsp.payload.target(31 downto 3) @@ U(0, 3 bits)
    val predictWindowPc = predictTargetReg(31 downto 3) @@ U(0, 3 bits)
    val cmdWindowPc = Mux(predictFire, predictWindowPc,
                      Mux(targetHoldValid, targetHoldPc,
                      Mux(applyNow, directTargetPc, fetchPc)))
    val cmdDrop = Mux(predictFire, predictTargetReg(2 downto 1),
                  Mux(targetHoldValid, targetHoldDrop,
                  Mux(applyNow, ftbRsp.payload.target(2 downto 1), pendingDrop)))
    // Amendment §2.1.1 — the SAME value that `ic.cmd.fire` writes into
    // `ringDrop(ringTail)` below is what the provider framing verdict must be computed
    // against. Driving it from this exact expression (rather than re-deriving it at the
    // command assignment) is what makes the equivalence structural: there is one
    // `cmdDrop`, and the ring record and the lookup command both consume it.
    ftbCmd.payload.drop := cmdDrop
    // The action flushes the old IBuf on this edge, so its old occupancy must not block
    // the C+1 target command. Ring capacity remains physical and is never bypassed.
    val ibufRoomForCmd = ibufRoomForIssue || predictFire
    ic.cmd.valid      := started && ringSlotAvailable && ibufRoomForCmd &&
                         !stalled && !faultHold && !quiesce && !ftqMismatch
    ic.cmd.payload.pc := cmdWindowPc

    when(ic.cmd.fire) {
      // Capture this fetch's record into the TAIL ring slot. Born stale iff a redirect
      // fires THIS cycle (this fetch used the old, now-wrong fetchPc). Latch the drop
      // intent; consume it. Advance tail + count.
      ringStale(ringTail) := redirectThisCycle
      ringDrop(ringTail)  := cmdDrop
      ringKeep(ringTail)  := U(4, 3 bits)
      ringPlanSeq(ringTail) := planSeq
      ringTail := ringInc(ringTail)
      planSeq := planSeq + 1
      pendingDrop := 0
      // Advance the SEQUENTIAL fetch pointer at ISSUE (depth-2 needs fetchPc+8 ready for
      // the NEXT cycle's issue while this fetch is still in flight). Only for a live
      // (non-redirect) issue: a redirect this cycle resets fetchPc to its target below
      // (and overrides this), and a stale issue's window is discarded. Guarding on
      // !redirectThisCycle keeps fetchPc from walking past a coincident redirect target.
      when(!redirectThisCycle) {
        fetchPc := cmdWindowPc + 8
      }
    }

    // The registered lookup result's architectural claim is committed once, whether or
    // not the cache accepts its target command in this cycle. Backpressure only decides
    // whether the target needs the one-entry hold register.
    when(applyNow) {
      ringKeep(resultSlot) := resultEnd.resize(3)
      ftqTail := ftqInc(ftqTail)
      when(!ic.cmd.fire) {
        targetHoldValid := True
        targetHoldPc    := directTargetPc
        targetHoldDrop  := ftbRsp.payload.target(2 downto 1)
      }
    }
    when(targetHoldValid && ic.cmd.fire) {
      targetHoldValid := False
    }
    // ringCount: +1 on a fire that is NOT consumed this cycle, -1 on a rsp consume that
    // is not re-issued; net handled below where the rsp is consumed (both can happen the
    // same cycle in steady state: one in, one out -> count unchanged).

    // ---- Enqueue: handle I-cache responses ----
    // The response is raw byte-at-address data (bits[7:0] = byte at pc). Assemble
    // each pair as a numeric big-endian 68k opword before it enters the instruction
    // buffer and reaches decode. Refill predecode performs the identical conversion.
    val rawRspWords = ic.rsp.payload.data.subdivideIn(16 bits)
    val rspWords = Vec((0 until 4).map(i => IcacheInstructionOrder.opword(rawRspWords(i))))
    val rspPreds = ic.rsp.payload.pred

    // The response belongs to the HEAD ring entry (in-order pipeline). Its OWN
    // stale/drop govern discard + leading-word drop.
    // A response consumed in the registered fallback action cycle still observes the
    // pre-edge stale bit. Kill it explicitly; the newly issued target cannot return for
    // three cycles and is re-marked live below.
    val rspStaleHead = ringStale(ringHead) || predictFire
    val rspDropHead  = ringDrop(ringHead)

    // A fault response (not stale): latch the fault + the faulting fetch PC and stop
    // fetching. The faulted packet is emitted on the feed below; do NOT enqueue words.
    // Capture the FIRST fault only (gate on !faultHold): a fault response one cycle
    // before the hold engages can be followed by a second in-flight fault response for
    // the next window; the EA must be the FIRST faulting address, not the later one.
    val rspFault = ic.rsp.valid && ic.rsp.payload.fault && !rspStaleHead && !faultHold
    when(rspFault) {
      faultHold    := True
      faultAtcHold := ic.rsp.payload.atc   // task #211: capture the cause of the FIRST fault
    }

    when(ic.rsp.valid) {
      // Consume the HEAD ring entry: advance head. (fetchPc is advanced at ISSUE for
      // depth-2, NOT here — the sequential pointer must already be +8 for the 2nd
      // outstanding fetch while the 1st is in flight.)
      ringHead := ringInc(ringHead)

      when(!rspStaleHead && !ic.rsp.payload.fault) {
        // Leading-word drop carried by THIS fetch's record (the head entry's drop).
        val startWord = rspDropHead
        // ringKeep is an exclusive offset from word zero. A fetch-directed prediction
        // lowers it to the learned branch end; the paired FTQ entry is written in the
        // same application event, so target bytes can never enter as fall-through.
        val nWords    = ringKeep(ringHead) - startWord.resize(3)
        assert(ringKeep(ringHead) > startWord.resize(3),
          "live fetch-ring response contains no genuine words")
        for (j <- 0 until 4) {
          when(U(j) < nWords) {
            val srcIdx = (startWord + U(j, 2 bits)).resize(2)
            ibuf.io.push.payload.words(j)                 := rspWords(srcIdx)
            ibuf.io.push.payload.preds(j).simple          := rspPreds(srcIdx).simple
            ibuf.io.push.payload.preds(j).lenWords        := rspPreds(srcIdx).lenWords
            ibuf.io.push.payload.preds(j).ambiguousLine   := rspPreds(srcIdx).ambiguousLine
            // FMax Lever B: indexed by the SAME `srcIdx` as `words(j)` above, which is what
            // keeps `preds(j).size` paired with `words(j)` across the leading-word drop.
            ibuf.io.push.payload.preds(j).size            := rspPreds(srcIdx).size
            // Control-transfer gate: same `srcIdx` pairing as `words(j)`/`size` above, so
            // the bit the fetch-side prediction gate reads always belongs to the opword it
            // is gating. Dropping this line would silently pin `ctrlXfer` at its False
            // default and disable prediction outright (caught exactly that way).
            ibuf.io.push.payload.preds(j).ctrlXfer        := rspPreds(srcIdx).ctrlXfer
          }
        }
        ibuf.io.push.payload.n := nWords
        ibuf.io.push.valid     := True
      }
      // stale OR fault: enqueue nothing (discard), as before.
    }

    // ---- Ring occupancy update (single driver for ringCount) ----
    // +1 when a fetch issues, -1 when a response is consumed; both can happen the same
    // cycle (steady-state: one in / one out -> count unchanged). At full turnover
    // `ringHead == ringTail`: rspStaleHead/rspDropHead read the OLD record
    // combinationally, while the replacement metadata is written at the edge and both
    // pointers advance. The availability/reservation guards keep count in [0, RING].
    val issFire = ic.cmd.fire
    val rspFire = ic.rsp.valid
    when(issFire && !rspFire) {
      ringCount := (ringCount + 1).resized
    } elsewhen(!issFire && rspFire) {
      ringCount := (ringCount - 1).resized
    }

    // ---- p0LiveReg: REGISTERED live re-classify of the buffer head ----
    // (FMax closure slice 1, 2026-08-07 —
    //  docs/superpowers/specs/2026-08-07-fmax-frontend-p0live-pipelining-design.md)
    //
    // Computed EVERY cycle from the LIVE (unregistered) head words/avail — the identical
    // `PredecodeWord.classify()` call `Aligner.align` used to run combinationally on its
    // own `L0` consume path (see that file's task #202 comment block for the full
    // rationale this preserves). The ONLY change: the result now lands in a REGISTER,
    // consumed by `Aligner.align` one cycle later, instead of being charged against
    // `L0`'s arrival time same-cycle for every instruction.
    //
    // CORRECTNESS (this is the load-bearing part — see below for why the design spec's
    // "the head is immutable while stalled" argument alone is NOT sufficient):
    // `classify()`'s effective inputs here are exactly (a) `ibuf.io.head(0..3)` and
    // (b) the three avail-derived Bool valid flags, which SATURATE at avail>=4 (they are
    // avail>=2 / >=3 / >=4). A registered value is therefore reusable next cycle iff
    // NEITHER can have changed. `ibuf.io.head(i)` is `entries[(headPtr+i) mod BUF]` gated
    // to 0 for `i >= count`, so head(0..3) / the valid flags can only change via:
    //   1. `flush`      — resets headPtr/count.
    //   2. `shift != 0` — advances headPtr (shift is only ever nonzero on a feed.fire).
    //   3. a `push` that lands in logical words 0..3, i.e. `cnt < 4` (a push writes
    //      logical `cnt .. cnt+n-1`); this ALSO covers every avail change that can move a
    //      valid flag, since `avail = min(cnt, HEAD_WORDS)` and the flags saturate at 4.
    // A push with `cnt >= 4` writes only beyond word 3 and leaves all three flags true —
    // provably no effect on this classify.
    // Forcing `ambiguousLine := True` ("not resolved yet") on exactly that union makes
    // `p0LiveReg` EITHER bit-identical to a same-cycle classify of the current head, OR
    // an explicit "not resolved" that lands in `Aligner`'s pre-existing
    // `.elsewhen(p0.ambiguousLine)` stall arm — i.e. architecturally behaviour-identical
    // to the old same-cycle code, costing only a bounded handful of extra stall cycles on
    // the already-rare, already-multi-cycle-tolerant ambiguous-head case. During such a
    // stall `shift` stays 0 (no feed.fire on a stalled head — note this is the ordinary
    // no-fault stall path; it does not need to reason about fault-packet overrides
    // elsewhere in `Aligner`, since those only affect what gets EMITTED, not whether the
    // IBuf head itself advances); `cnt` may cross 4 over more than one push while stalled
    // (each push can land at most `n<=4` words), which is exactly why condition 3 is
    // checked on `push.fire` every cycle rather than assumed to resolve in one shot.
    //
    // `flush` (condition 1) is provably redundant with `push`/`shift` becoming true again
    // after a flush (a flush alone doesn't change `head`/`avail` until the next push or
    // shift touches them) — kept anyway as explicit defense-in-depth so a reset-to-known-
    // state is never load-bearing on the other two conditions' exact timing.
    //
    // NB the design spec's simpler argument ("the IBuf head entry is immutable while the
    // aligner stalls on ambiguousLine") is true but NOT sufficient on its own: it does not
    // cover the cycle the aligner TRANSITIONS INTO an ambiguous head (the previous cycle's
    // non-ambiguous head was emitted + shifted out, so a bare register would still hold
    // the PREVIOUS instruction's fully-resolved classify and `Aligner` would consume it as
    // this instruction's length — a silent mis-frame). Condition 2 above is what closes
    // that; conditions 1/3 close the flush and buffer-refill equivalents.
    //
    // `ibuf.io.flush`/`ibuf.io.shift` are plain nets driven further down this same Area;
    // SpinalHDL resolves them as single nets, so reading them here sees their final value.
    // No combinational loop: they only reach `p0LiveReg`'s D input (through the register).
    // ── Decode-side FTQ view and genuine-word clamp ─────────────────────────────
    // The head entry describes the sole splice nearest decode. Limit the aligner's
    // visible word count to bytes proven to precede that splice. This is the framing
    // safety mechanism: a stale/wrong learned length can only make the aligner stall or
    // emit a packet made entirely of genuine bytes, never consume target bytes as an
    // extension of a fall-through instruction.
    val ftqDiff  = ftqHeadE.brPc - decodePc
    // FMax (frontend floor, 2026-09-15 — see `decodePcHiP1`'s declaration): `ftqDelta`
    // and `ftqNear` used to be slices of the FULL 32-bit `ftqDiff` subtract, putting four
    // chained CARRY8s plus a 27-bit zero-detect at the HEAD of the `decodePc -> availEff
    // -> Aligner -> decodePc` loop (and at the head of the `ftqHead -> ftqMem -> 8 CARRY8`
    // family too, since `brPc` is an asynchronous FTQ-memory read). Restructured, NOT
    // re-timed — bit-identical every cycle, asserted below:
    //   * the low 5 bits of a subtract do not depend on anything above bit 4, so
    //     `ftqDiffLow(4 downto 0) === ftqDiff(4 downto 0)` by construction, and
    //     `ftqDiffLow(5)` is exactly the borrow OUT of bit 4;
    //   * `ftqDiff(31 downto 5) === 0` iff `brPc(31:5) === decodePc(31:5) + borrow`
    //     (mod 2^27) — i.e. a 2:1 select between two 27-bit EQUALITY compares, with the
    //     `+1` operand supplied by the `decodePcHiP1` register instead of a ripple.
    // Net effect: three chained carry hops and a 27-bit NOR tree leave the loop head,
    // replaced by one 6-bit carry plus a 2-level equality tree. `ftqDiff` itself survives
    // for `ftqPast` (a genuine full-width sign test) and for the simulation oracle; that
    // residue feeds only the two-level `feed.valid` AND, not the deep aligner cone.
    val ftqDiffLow = (False ## ftqHeadE.brPc(4 downto 0)).asUInt -
                     (False ## decodePc(4 downto 0)).asUInt      // 6 bits; [5] = borrow
    val ftqBorrow  = ftqDiffLow(5)
    val ftqDelta = ftqDiffLow(4 downto 1)
    val ftqHiEq  = Mux(ftqBorrow, ftqHeadE.brPc(31 downto 5) === decodePcHiP1,
                                  ftqHeadE.brPc(31 downto 5) === decodePcHi)
    val ftqNear  = ftqValid && ftqHiEq
    val ftqAt0   = ftqNear && (ftqDelta === 0)
    // Differential proof of the restructuring above: the live oracle is the original
    // full-width form. Elaborated in simulation only (`.includeSimulation`), so the
    // synthesized netlist keeps only the restructured form.
    GenerationFlags.simulation {
      assert(ftqDelta === ftqDiff(4 downto 1),
        "FetchAlignPlugin: narrowed ftqDelta diverged from the full-width subtract", FAILURE)
      assert(ftqHiEq === (ftqDiff(31 downto 5) === U(0, 27 bits)),
        "FetchAlignPlugin: registered-carry ftqNear diverged from the full-width subtract", FAILURE)
    }
    val spliceWords = ftqDelta.resize(5) + ftqHeadE.brLen.resize(5)
    val availEff = UInt(4 bits)
    when(ftqNear && (spliceWords < ibuf.io.avail.resize(5))) {
      availEff := spliceWords.resize(4)
    } otherwise {
      availEff := ibuf.io.avail
    }
    spinal.core.sim.SimPublic(ftqDiff, ftqDelta, ftqNear, ftqAt0, spliceWords, availEff)

    val p0LiveReg = Reg(ChunkPredecode())
    p0LiveReg.simple        init False
    p0LiveReg.lenWords      init 0
    p0LiveReg.ambiguousLine init True    // reset state must never read as "already resolved"
    // `p0LiveReg.ctrlXfer` gets the SAME treatment as `.size` (see the task #250 note
    // below): it is never assigned and never read. `ctrlXfer` is a pure function of the
    // opword, so it is not ambiguity-qualified — the prediction gates read
    // `ibuf.io.headPred(0).ctrlXfer` DIRECTLY, exactly as `Aligner.align` reads
    // `preds(0).size` directly and for the identical reason (both mux arms carry the same
    // value, and bypassing the `p0` mux keeps the term off `L0`'s arrival chain). Leaving
    // it undriven is what lets synthesis prune the field out of this register entirely.
    // task #250: `p0LiveReg.size` is UNREAD by construction — `Aligner.align` sources
    // slot0's/slot1's `size` from `preds(0).size`/`p1.size` directly (see Aligner.scala's
    // "Lever B" comments), never from the `p0 = Mux(preds(0).ambiguousLine, p0LiveReg,
    // preds(0))` mux this register feeds. Confirmed fresh against Aligner.align: `p0.size`
    // does not appear anywhere in that function.
    //
    // A prior version of this comment claimed the field was therefore PRUNED from the
    // netlist ("not a 33rd hardware instance of the size decoder") — that claim was
    // checked against the real netlist and found FALSE: `p0LiveReg_size` survived
    // elaboration as a real register (117/118 occurrences in the probed netlists) despite
    // being unread, because the bulk bundle assignment below drove it from `classify()`
    // every cycle same as every other field, giving synthesis no dead-code signal. Fixed
    // by assigning only the fields Aligner actually consumes (`simple`/`lenWords`/
    // `ambiguousLine`) instead of bulk-assigning the whole `ChunkPredecode` — `.size` is
    // now never written after its init below, so it is a true constant and synthesis can
    // fold away both the register and `classify()`'s size-decode subtree feeding it. The
    // init is kept for reset determinism / bundle-completeness only (an uninitialised Reg
    // randomises per sim seed, and `ChunkPredecode.size` must have SOME driven value to
    // elaborate); it is never re-driven by the live reclassify below.
    p0LiveReg.size          init m68k040.isa.Size.BYTE
    // task #242 (memind_wide_disp_dst HANG): pass 3 more lookahead words (op+4/+5/+6) --
    // `ibuf.io.head` is a HEAD_WORDS=10-word window, already resident here, so this is
    // real data (not the constant-zero the bake-time IcachePlugin call still passes via
    // the narrower overload). This is the ONLY thing that lets the live reclassify
    // actually resolve a MOVE dst-mode-6/mode7-3 EA whose source consumed 3-5 of its own
    // ext words (see PredecodeWord.classify's dstEaW0/dstEaKnown comment) -- without it,
    // `ambiguousLine` could never clear for that shape (the old 4-word-capped classify
    // call re-derives the identical "unknown" verdict every cycle, forever), which is
    // exactly the HANG this task fixes: the Aligner's `.elsewhen(p0.ambiguousLine)` stall
    // arm (Aligner.scala) never had a real chance to resolve, so fetch stalled permanently.
    //
    // task #242 restructure (2026-08-19): the ORIGINAL version of this fix regressed
    // synth-only WNS by -0.482ns. Root cause traced to PredecodeWord.classify()'s
    // dst-EA-position select, which the original fix implemented as a LINEARLY NESTED
    // Mux chain (depth 6, vs the pre-fix depth-3 chain) sitting directly on THIS
    // register's D-input combinational cone -- doubling the depth of the one signal path
    // that matters here, even though `p0LiveReg`'s Q side is already isolated to its own
    // cycle (FMax closure slice 1). The wider word reads / avail comparisons below are
    // NOT the regression source (this bus and comparison family already existed here);
    // see PredecodeWord.scala's classify() for the actual restructuring (flat Vec-indexed
    // select instead of the nested-Mux chain), which is what fixes the regression while
    // this call site's own wiring stays functionally identical to the original fix.
    // FMax (200 MHz campaign, 2026-09-13): classify against the REGISTERED availEff.
    // The worst cone in the 200 MHz build was ftqHead -> ftqMem async read -> the 32-bit
    // `ftqDiff = ftqHeadE.brPc - decodePc` carry chain -> availEff -> THIS classify ->
    // p0LiveReg (-1.694 ns, 30 logic levels, 74% route; the top five violations in the
    // design all sourced from ftqHead_reg[1]). Feeding the valid flags from a register
    // cuts the FTQ read + subtract out of the classify cone entirely.
    //
    // SAFE BY THE EXISTING CONTRACT, not by a new argument: `p0LiveInvalidate` below
    // already contains `availEffPrev =/= availEff`, i.e. it forces ambiguousLine := True
    // (-> Aligner's pre-existing stall arm) on exactly the cycles where the registered
    // and live values differ. When they are equal this classify is bit-identical to the
    // live one; when they differ the result is discarded anyway. Same "either identical
    // or explicitly not-resolved" guarantee the block comment above states.
    val availEffPrev = RegNext(availEff) init 0
    val p0LiveClassified = PredecodeWord.classify(ibuf.io.head(0), ibuf.io.head(1), ibuf.io.head(2), ibuf.io.head(3),
      ibuf.io.head(4), ibuf.io.head(5), ibuf.io.head(6),
      extWValid  = availEffPrev >= U(2, 4 bits),
      extW2Valid = availEffPrev >= U(3, 4 bits),
      extW3Valid = availEffPrev >= U(4, 4 bits),
      extW4Valid = availEffPrev >= U(5, 4 bits),
      extW5Valid = availEffPrev >= U(6, 4 bits),
      extW6Valid = availEffPrev >= U(7, 4 bits))
    // task #250: assign only the fields `Aligner.align` actually reads off `p0LiveReg`
    // (`.simple`/`.lenWords`/`.ambiguousLine`, via the `p0` mux) — NOT a bulk bundle
    // assign. `.size` is deliberately left undriven here (see the field's own comment
    // above): leaving `p0LiveClassified.size` unconsumed lets synthesis prune the whole
    // size-decode subtree of this `classify()` call along with the now-constant register.
    p0LiveReg.simple        := p0LiveClassified.simple
    p0LiveReg.lenWords      := p0LiveClassified.lenWords
    p0LiveReg.ambiguousLine := p0LiveClassified.ambiguousLine
    // task #242: the content-immutability threshold widens from `cnt<4` to `cnt<7` to
    // match the classify() call now reading `ibuf.io.head(4..6)` too -- a push landing at
    // cnt in [4,6] writes exactly those newly-read logical positions (see the `cnt<4`
    // derivation in the block comment above: "a push landing with cnt<N writes logical
    // words N..N+n-1, i.e. affects visible head words < N"), which the OLD `cnt<4` gate
    // did not cover (those words weren't read by classify() at all before this task).
    // `availEffPrev =/= availEff` already independently covers every VALIDITY-flag change
    // (any avail change, not just crossing 2/3/4), so only the content-immutability term
    // needed widening.
    val p0LiveInvalidate = ibuf.io.flush || (ibuf.io.shift =/= 0) ||
                           (ibuf.io.push.fire && (ibuf.io.cnt < U(7, ibuf.io.cnt.getWidth bits))) ||
                           (availEffPrev =/= availEff)
    when(p0LiveInvalidate) {
      p0LiveReg.ambiguousLine := True
    }

    // ---- Aligner: combinational decode of buffer head ----
    val res = Aligner.align(decodePc, ibuf.io.head, ibuf.io.headPred, availEff, p0LiveReg)
    spinal.core.sim.SimPublic(ibuf.io.headPred(0).simple, ibuf.io.head(0))
    // The FTQ capacity proof measures real buffer occupancy, not an inferred one: the
    // legal run-ahead bound is `RING + BUF_WORDS`, so a stress test must show which of
    // the two terms it actually saturated. Simulation-only annotation.
    spinal.core.sim.SimPublic(ibuf.io.cnt, ibuf.io.avail)

    // ── Fetch-time prediction (BTB + bimodal, slice 1) ───────────────────────────
    // Query the BTB combinationally with the aligner's slot0/slot1 instruction PCs;
    // the BTB returns predict-taken + target THIS cycle. An emitted slot that predicts
    // taken IS the predicted-taken branch. We:
    //   (a) stamp predTaken/predTarget on that slot's DecodePacket (rides to the EU),
    //   (b) SUPPRESS everything after it in the window (slot1 after a predicted slot0 is
    //       wrong-path),
    //   (c) on feed.fire capture predTarget, then run the registered fallback action in
    //       C+1 at priority BELOW commit/external/resume.
    // Architectural correctness does NOT depend on the prediction being right: the
    // branch EU verifies predicted-vs-actual + the commit-time redirect recovers a
    // mispredict. A correct prediction simply avoids the squash.
    val predEnable = !faultHold && !quiesce && !stalled
    // ── Predecoded control-transfer gate (2026-09-16) ────────────────────────────
    // `slot0IsCtrlXfer` is a PURE MEMORY OUTPUT: the `ctrlXfer` bit of the buffer-head
    // predecode entry, baked at I-cache REFILL time (ChunkPredecode.ctrlXfer,
    // PredecodeWord.classify). NOTHING is computed here — no opword comparison, no
    // aligner mux, no dependence on `L0`. It is read from `ibuf.io.headPred(0)`
    // DIRECTLY and not through `Aligner`'s `p0` ambiguity mux, for the same reason
    // `Aligner.align` reads `preds(0).size` directly: `ctrlXfer` needs only the opword,
    // which is `words(0)` in BOTH arms of that mux, so the two arms are identical — and
    // bypassing the mux keeps this term off `L0`'s arrival chain, which feeds the whole
    // `slot0Predicted -> suppressSlot1 -> decodePcNext` arc (the measured front-end FMax
    // floor). The gate is therefore one extra input to the AND that already forms
    // `btbQueryValid0`, arriving at t~=0 — no new logic level in that arc.
    //
    // WHAT IT CLOSES: `btbQueryValid0` was gated only on "a slot was emitted", never on
    // the slot BEING a branch, and `predTaken` — stamped on every µop of the emitted
    // instruction — is read ONLY by `BranchEuPlugin`. So a BTB entry that survived a
    // change of the bytes at its (fully-tagged, exact) virtual PC redirected fetch on an
    // ordinary ALU/LS instruction with nothing downstream able to detect it, and the
    // wrong-path instructions RETIRED. With this gate, a prediction can only ever be
    // stamped on an instruction that decodes to a branch-EU µop, and `BranchEuPlugin`'s
    // `mispredict` check then verifies direction AND target — so the worst case becomes a
    // correctly-recovered mispredict instead of silent wrong-path retirement.
    //
    // WHY A STALE ENTRY IS REACHABLE AT ALL (the honest severity): the BTB is fully
    // tagged on the complete 32-bit PC (`Btb.scala`: idx = pc[7:1], tag = pc[31:8]) and
    // allocates ONLY at retire on `btbIsBranchStore` (`RobPlugin.scala`), so a non-branch
    // PC cannot alias into a branch's entry and a non-branch can never train one. The
    // entry must therefore have been LEGITIMATELY trained and the bytes at that exact VA
    // must then have CHANGED underneath it. Self-modifying code is already closed
    // (`IcachePlugin.maintInvalidateAll` fans CINV/CPUSH-IC out to the BTB). What is NOT
    // closed is a TRANSLATION change — PFLUSH/PFLUSHA, a URP/SRP/TC write, or a 24/32-bit
    // addressing-mode switch — remapping that VA to different physical code: the L1I is
    // VIPT with a PHYSICAL tag, so it correctly misses and refills the new bytes, but the
    // predictors are keyed on VIRTUAL PC and nothing invalidates them. That asymmetry is
    // the reachable route, and it is not an everyday path.
    //
    // PROVEN, not argued. Two self-checking corpus programs take exactly that route on
    // the full core (train a branch at an MMU-translated VA, repoint its leaf descriptor,
    // PFLUSHA, execute) and issue NO cache-maintenance instruction, so the predictors
    // survive: `mmu_remap_stale_btb_predict.s` (the FTB confirmation path -- fails
    // 0xDEAD0B03 with the `ftqConfirm` gate removed) and `mmu_remap_stale_btb_nonbranch.s`
    // (the decode-time BTB path, with the branch placed so the FTB declines to frame it --
    // fails 0xDEAD0C03 with the `btbQueryValid0` gate removed). Each fails with ITS gate
    // removed and passes with it present, so both gates are independently load-bearing.
    val slot0IsCtrlXfer = ibuf.io.headPred(0).ctrlXfer
    spinal.core.sim.SimPublic(slot0IsCtrlXfer)
    btbQueryPc0    := res.slot0.pc
    btbQueryValid0 := predEnable && res.slot0Valid && slot0IsCtrlXfer
    btbQueryPc1    := res.slot1.pc
    btbQueryValid1 := predEnable && res.slot1Valid
    // ── gshare direction composition (slice 3) ──────────────────────────────────
    // gshare OVERRIDES the DIRECTION of a CONDITIONAL branch that hit the BTB. The BTB
    // still supplies the target; the predicted-taken bit becomes phtTaken (NOT the BTB
    // bimodal counter) for a `condBtbHit`. Unconditionals (brType==uncond) keep the BTB
    // force-taken; misses stay not-taken. `cond` brType == 0.
    val condBtbHit0 = gsBtbHit0 && (gsBtbType0 === U(0, 2 bits))
    // slot0 predicted-taken: for a conditional BTB hit use the PHT direction; else the
    // BTB's own predict-taken (unconditional force-taken, or a non-cond bimodal). In
    // STEP 1 (gshare inert) gsBtbHit0=False -> condBtbHit0=False -> this reduces to the
    // BTB predict-taken (behavior-neutral); STEP 2 wires gsBtbHit0/gsPhtTaken0 live.
    val slot0PredTaken = Mux(condBtbHit0, gsPhtTaken0, btbPredTaken0)
    // slot0 predicted-taken: stamp slot0 + suppress slot1 + redirect (the proven path).
    val slot0IsPred = slot0PredTaken && res.slot0Valid
    // NOTE (task #248): a slot1 predicted-taken branch used to be deferred to slot0 next
    // cycle via a dedicated BTB-sourced `slot1WouldPred` (Lever D's 9-way speculative
    // slot-1 BTB read, `Btb.scala`'s deleted `spec2*` block — the design's #1 failing
    // setup cone). That deferral is now covered by `slot1WouldFtq` below, sourced from
    // the fetch-directed FTB/FTQ (task #126) instead of a speculative BTB read — a 4-bit
    // register compare rather than a 9-way RAM lookup. We still do NOT defer a
    // predicted-NOT-taken slot1 conditional (only slot0's GHR bit shifts if both slot0
    // and slot1 are conditionals emitted the same cycle). Slot1 has no prediction
    // metadata at all: it neither shifts history nor trains the PHT. This is an
    // accuracy limitation, not architectural corruption; branch execution checks
    // its implicit not-taken prediction. See the inert-tag invariant below.

    // ── RAS classification of the EMITTED slot0 (slice 2) ────────────────────────
    // isCall / isReturn are recomputed from the emitted slot0 opword (already in the
    // aligner slot) — the same classification the assembler uses to crack BSR/JSR/
    // RTS/RTR. (ChunkPredecode carries only simple/lenWords, so deriving the call/
    // return bit locally from the opword is the minimal threading; the opword is right
    // here.) BSR = 0x61xx; JSR = 0100111010 mmmrrr; RTS = 0x4E75; RTR = 0x4E77.
    val s0op       = res.slot0.words(0)
    val s0IsBsr    = s0op(15 downto 8) === B"8'h61"
    val s0IsJsr    = s0op(15 downto 6) === B"10'b0100111010"
    val s0IsRts    = s0op === B"16'h4E75"
    val s0IsRtr    = s0op === B"16'h4E77"
    val s0IsCall   = res.slot0Valid && res.slot0.simple && (s0IsBsr || s0IsJsr)
    val s0IsReturn = res.slot0Valid && res.slot0.simple && (s0IsRts || s0IsRtr)
    // Return PC = the call's fall-through (slotPc + lenWords*2), from predecode.
    val s0RetPc    = res.slot0.pc + (res.slot0.lenWords.resize(32) |<< 1)
    // RAS predict for slot0 (a return with a non-empty stack predicts top-of-stack).
    val rasPredictSlot0 = s0IsReturn && rasPredValid

    // A RETURN in slot1 (slot0 not predicted): defer it — suppress slot1 so the return
    // becomes slot0 NEXT cycle and takes the (single-slot) slot0 RAS-predict path. This
    // mirrors slot1WouldFtq below (the FTQ-sourced slot1-branch deferral) and is essential: a leaf
    // `add ; rts` emits add=slot0 + rts=slot1 in one cycle, so without deferral the rts
    // never reaches the slot0 RAS-predict and every return mispredicts (the flush the
    // RAS is meant to remove). The opword classification only needs slot1's opword.
    val s1op       = res.slot1.words(0)
    // Optional single-port admission experiment: Bcc conditions 2..15 use the
    // existing slot-0 predictor next cycle. No extra BTB read or prediction tag.
    val slot1WouldCondPred = if (deferSlot1Conditional)
      res.slot0Valid && res.slot1Valid && res.slot1.simple &&
        (s1op(15 downto 12) === B"4'h6") && (s1op(11 downto 8).asUInt >= U(2, 4 bits))
    else False
    val s1IsRts    = s1op === B"16'h4E75"
    val s1IsRtr    = s1op === B"16'h4E77"
    val s1IsReturn = res.slot1Valid && res.slot1.simple && (s1IsRts || s1IsRtr)
    val slot1WouldRasPred = s1IsReturn && rasPredValid && res.slot0Valid && !slot0IsPred && !rasPredictSlot0

    // Exact decode-time confirmation of the fetch-side framing claim. A predicted branch
    // in slot1 is deferred to slot0 just like the retained BTB/RAS fallbacks; neither
    // fallback is removed by the FTB.
    // `slot0IsCtrlXfer` (see its declaration above) closes the same hole on the
    // fetch-directed path. The FTB's confirmation was a 4-bit LENGTH match and nothing
    // else, so a stale window entry whose claimed branch PC now holds a same-length
    // NON-branch confirmed, stamped `predTaken`+`ftqHeadE.target` on it, and suppressed
    // slot1 — again with no downstream verifier. Failing the confirm is the already-built
    // FAIL-CLOSED path: `ftqLenBad` (below) fires, raising `ftqMismatchDetect`, which
    // clears the FTB entry and re-steers to the instruction's real fall-through. Same
    // pure-memory-output term, same zero added logic levels.
    val ftqConfirm = ftqAt0 && res.slot0Valid && res.slot0.simple && slot0IsCtrlXfer &&
                     (res.slot0.lenWords === ftqHeadE.brLen)
    val slot1WouldFtq = ftqNear && !ftqAt0 && res.slot0Valid && res.slot1Valid &&
                        (ftqDelta === res.slot0.lenWords) && !ftqConfirm
    spinal.core.sim.SimPublic(ftqConfirm, slot1WouldFtq)

    // ── Compose the slot0 prediction source: isReturn ? RAS : BTB (slice 2) ───────
    // A return is predicted by the RAS (top-of-stack); everything else by the BTB
    // (slice 1). Returns + BTB hits are mutually exclusive (slice 1 never learns a
    // return), so this is a clean either/or. The composed predicted-taken + target
    // drive the SAME slice-1 machinery: slot1 suppression, predTaken/predTarget
    // stamping, the effShift consume, and the registered fallback action — so a
    // RAS-predicted return redirects identically to a BTB taken branch.
    val slot0Predicted    = slot0IsPred || rasPredictSlot0
    val predictedThisEmit = slot0Predicted && !ftqConfirm
    val predTargetSel     = Mux(rasPredictSlot0, rasPredTarget, btbPredTarget0)

    // A permanently clamped, non-emittable instruction signals a wrong learned framing
    // claim. Dwell for three cycles because p0LiveReg intentionally reports one-cycle
    // ambiguity whenever availEff changes. `ftqPast` is defensive and deliberately not
    // gated by ftqNear (near already forces the sign bit low).
    val ftqStarveRaw = ftqNear && !res.slot0Valid && (availEff < ibuf.io.avail) &&
                       !quiesce && !stalled
    val ftqStarveCnt = Reg(UInt(2 bits)) init 0
    when(!ftqStarveRaw) {
      ftqStarveCnt := 0
    } elsewhen(ftqStarveCnt =/= U(3, 2 bits)) {
      ftqStarveCnt := ftqStarveCnt + 1
    }
    val ftqStarved = ftqStarveRaw && (ftqStarveCnt === U(3, 2 bits))
    val ftqPast = ftqValid && ftqDiff(31)
    val ftqStarvedOrPast = ftqStarved || ftqPast
    spinal.core.sim.SimPublic(ftqStarveRaw, ftqStarveCnt, ftqStarved, ftqPast)

    // ---- Feed valid logic ----
    // Emit when a packet is at head and not stalled. Complex packets emit once:
    // the cycle after they fire, the stalled latch suppresses re-emission.
    feed.payload(0) := res.slot0
    feed.payload(1) := res.slot1
    slot1ValidOut   := res.slot1Valid
    // Suppress slot1 when slot0 is the predicted-taken branch (slot1 is wrong-path) OR
    // when slot1 WOULD be a predicted branch (defer it to slot0 next cycle). slot0Predicted
    // folds in a RAS-predicted return (slice 2): its slot1 is equally wrong-path.
    when(slot0Predicted || slot1WouldRasPred || slot1WouldCondPred || slot1WouldFtq || ftqConfirm) {
      slot1ValidOut := False
    }
    // Stamp the prediction onto slot0 (rides to the EU). The target is the composed
    // source (RAS top-of-stack for a return, BTB target otherwise).
    when(slot0Predicted) {
      feed.payload(0).predTaken  := True
      feed.payload(0).predTarget := predTargetSel
    }
    // ── gshare carry-down stamp (slice 3) ───────────────────────────────────────
    // When slot0 is a CONDITIONAL BTB hit (condBtbHit0), it is the emitted conditional
    // whose DIRECTION came from the PHT — carry its fetch-time index down so the ROB
    // trains pht[gsPhtIndex0] toward the resolved direction at retire (the GHR will have
    // shifted by retire, so only the carried index is right). This is INDEPENDENT of the
    // predicted direction: a predicted-NOT-taken conditional (slot0 not redirecting) is
    // ALSO trained, so both directions of the alternating beq learn.
    // `!faultHold` makes this stamp EXACTLY the set `gsShiftValid` shifts the GHR for
    // (below). That 1:1 correspondence is what lets the GsharePlugin rebuild the
    // speculative GHR from its retire-time `ghrArch`: `gshareUpdate.valid` pulses once
    // per retiring `phtValid` branch, so arch and speculative histories are the SAME
    // bit sequence, one lagging the other. Without it, a branch emitted while an
    // I-fetch fault is draining would be counted at retire but never at fetch, and
    // every later repair would install a history shifted by one bit.
    when(condBtbHit0 && res.slot0Valid && !faultHold) {
      feed.payload(0).phtValid := True
      feed.payload(0).phtIndex := gsPhtIndex0
    }
    // Fetch-time confirmation wins over the decode-time fallback stamps. The carried
    // PHT index names the exact registered window lookup, even though GHR has since moved.
    when(ftqConfirm) {
      feed.payload(0).predTaken  := True
      feed.payload(0).predTarget := ftqHeadE.target
      feed.payload(0).phtValid   := ftqHeadE.isCond
      feed.payload(0).phtIndex   := ftqHeadE.phtIdx
    }
    // ── Branch-prediction side-channel TAG allocation (Global.BR_PRED_TABLE_DEPTH) ──
    // The 45 prediction bits stay on the packet but no longer ride every DecodedUop; the
    // uop carries this tag instead and DecodeStage re-expands it at the queue pop.
    //
    // Read AFTER every stamp above: `predTaken`/`phtValid` are plain combinational nets,
    // so this sees their FINAL value however far up the file they were assigned (that is
    // also why this block sits below the `ftqConfirm` override -- textual order only
    // matters for the assignments, not for the reads).
    //
    // Only a packet that actually carries a prediction consumes a tag, and the counter
    // advances only when such a packet is CONSUMED (`feed.fire`), so the wrapping distance
    // is measured in predicted packets, not cycles. Everything else -- slot 1 (which never
    // carries a prediction: `Aligner` zeroes its four fields and nothing here overrides
    // them), a non-predicted slot 0, and the synthetic faulted packet -- gets the reserved
    // inert tag 0.
    val brPredCtr = Reg(UInt(Global.BR_PRED_TAG_W bits)) init 1
    val brPredLive0 = feed.payload(0).predTaken || feed.payload(0).phtValid
    feed.payload(0).brPredTag.allowOverride   // unconditional re-drive over `:= res.slot0`
    feed.payload(0).brPredTag := Mux(brPredLive0, brPredCtr, U(0, Global.BR_PRED_TAG_W bits))
    // slot 1 keeps the Aligner's inert 0 -- deliberately NOT re-driven here.
    when(feed.fire && brPredLive0) {
      brPredCtr := Mux(brPredCtr === U(Global.BR_PRED_TABLE_DEPTH - 1, Global.BR_PRED_TAG_W bits),
                       U(1, Global.BR_PRED_TAG_W bits), brPredCtr + 1)
    }
    // The invariant the "slot1 is always inert" half of the scheme rests on. Checked live
    // rather than trusted: if a future front-end change ever predicts slot 1, this fires
    // instead of silently handing slot 1 a stale slot-0 record.
    GenerationFlags.simulation {
      assert(!(feed.valid && slot1ValidOut && (feed.payload(1).predTaken || feed.payload(1).phtValid)),
        "FetchAlign: slot1 must never carry a branch prediction (the side-channel tag reserves 0 for it)")
    }

    // Gate feed low while STOP-quiesced so no buffered successor word is dispatched /
    // allocated into the ROB while halted (the quiesce only ends on the wake redirect).
    feed.valid      := res.slot0Valid && !stalled && !quiesce && !ftqPast &&
                       !ftqMismatch && !predictFire
    // I-fetch fault: once decode has drained everything legitimately buffered ahead
    // of the fault (no more aligned instruction available -> !res.slot0Valid), AND
    // we're not mid-complex-stall/quiesce (waiting on an earlier, unrelated resume),
    // decodePc IS the faulting instruction's PC. Only THEN override the feed with a
    // single faulted DecodePacket (slot0 only), emitted EXACTLY ONCE (faultEmitted
    // suppresses re-emission). Its bytes are don't-care; decode turns it into a
    // faulted vector-2 µop that delivers at retire. Gating on !res.slot0Valid (rather
    // than the raw faultHold latch) is what lets still-buffered pre-fault instructions
    // keep draining normally instead of being squashed the instant the fault response
    // lands (up to RING windows before decode actually reaches it).
    val emittingFaultPacket = faultHold && !res.slot0Valid && !stalled && !quiesce &&
                              !ftqStarvedOrPast && !ftqMismatch && !predictFire
    when(emittingFaultPacket) {
      feed.valid             := !faultEmitted
      slot1ValidOut          := False
      feed.payload(0).valid     := True
      feed.payload(0).fault     := True
      feed.payload(0).faultAtc  := faultAtcHold   // task #211
      // EA / faulting-instruction PC = the architectural fetch PC (decodePc, = the
      // branch/redirect target), NOT the 8-byte I-cache fetch window (which can be one
      // window ahead of the faulting instruction).
      feed.payload(0).pc        := decodePc
      feed.payload(0).simple    := True
      feed.payload(0).complex   := False
      feed.payload(0).wordCount := 1
      feed.payload(0).lenWords  := 1
      feed.payload(0).words.foreach(_ := 0)
      // FMax Lever B: the synthetic faulted packet zeroes `words`, so its `size` must be
      // the size of opword 0x0000 — `Size.BYTE` (`ORI.B #imm,D0`) — to keep the pairing
      // invariant `pkt.size === decode(pkt.words(0)).size` true on this path too. (The
      // faulted packet's bytes are architecturally don't-care — decode turns it straight
      // into a faulted vector-2 µop — but the invariant is checked unconditionally by
      // FedSpecsPacketPairingSpec, and `FetchFaultSpec` exercises this path.)
      feed.payload(0).size      := m68k040.isa.Size.BYTE
      feed.payload(0).predTaken  := False
      feed.payload(0).predTarget := U(0, 32 bits)
      feed.payload(0).phtValid   := False
      feed.payload(0).phtIndex   := U(0, 11 bits)
      feed.payload(1).valid     := False
      feed.payload(1).fault     := False
      feed.payload(1).predTaken  := False
      feed.payload(1).predTarget := U(0, 32 bits)
      feed.payload(1).phtValid   := False
      feed.payload(1).phtIndex   := U(0, 11 bits)
    }
    // Emit-once: latch faultEmitted when the faulted packet fires.
    when(emittingFaultPacket && feed.fire) { faultEmitted := True }

    // When feed fires (normal, NOT the faulted-packet override): consume words from
    // the buffer and advance decodePc. A faulted feed shifts nothing (its bytes came
    // from decodePc directly, not the IBuf).
    // Effective shift: when slot1 is SUPPRESSED (a predicted slot0 branch, or a slot1
    // branch deferred to next cycle), consume only slot0's words (lenWords); else the
    // aligner's full shift. Without this, suppressing slot1 would still CONSUME its
    // words from the IBuf — losing the deferred branch / the wrong-path successor.
    val suppressSlot1 = slot0Predicted || slot1WouldRasPred || slot1WouldCondPred ||
                        slot1WouldFtq || ftqConfirm
    val effShift = Mux(suppressSlot1, res.slot0.lenWords.resize(res.shiftWords.getWidth), res.shiftWords)
    // FMax (front-end floor): RETIME the decodePc advance so the late suppressSlot1 decision
    // (BTB/RAS prediction cones) muxes the 32-bit ADD RESULT instead of the adder's addend.
    // effShift is either slot0.lenWords (suppress) or shiftWords (full) — both aligner outputs;
    // precompute decodePc + (each<<1) in PARALLEL and select by suppressSlot1. This pulls the
    // 6-deep decodePc CARRY8 chain OUT from behind the effShift mux (the `pred_lenWords ->
    // L0L1 -> slot1Ok/suppress -> addend -> adder` arc was the bistable-ordering 194MHz
    // limiter): the adders now start as soon as their length is ready, and suppressSlot1 only
    // drives a final 1-LUT 32-bit 2:1 result mux. Byte-identical: same value, restructured.
    //
    // FMax 2026-09-15 (same lever as `decodePcHiP1`'s declaration): that retime is KEPT
    // exactly — `suppressSlot1` still passes through precisely ONE 2:1 select before the
    // flop — but the two 32-bit adders behind it are replaced by two 6-bit adds plus a
    // registered high half. `res.slot0.lenWords`/`res.shiftWords` are aligner outputs
    // bounded by WINDOW=10, so the byte increment is at most 20 and the upper 27 bits of
    // `decodePc` can only stay put or advance by one. Hence, for each candidate:
    //     low   = decodePc(4 downto 0) + (len << 1)        -- 6 bits, [5] = carry out
    //     high  = carry ? decodePcHiP1 : decodePc(31 downto 5)
    // which is bit-identical to the 32-bit add (a ripple carry out of bit 4 into bit 5 is
    // exactly `low(5)`), and drops three of the four chained CARRY8s from the LATE
    // `pred_lenWords -> aligner -> decodePc[31:5]` arrival — 27 of this family's 32
    // endpoints. `decodePcHiP1` is a register, so the `+1` it supplies costs nothing here.
    //
    // The companion register must advance with it: next(decodePc(31:5)) + 1 is
    // `carry ? decodePcHiP1 + 1 : decodePcHiP1`, and that increment is likewise a
    // register-to-register ripple that never sees an aligner output.
    val pcLow        = decodePc(4 downto 0)
    // NB `|<<` is SpinalHDL's FIXED-WIDTH shift: the widening `.resize(6)` MUST come
    // before it, exactly as the full-width reference below resizes to 32 before shifting.
    // Shifting a 4-bit `lenWords` in place drops its MSB, which is wrong for every
    // instruction of 8 words or more — caught by the equivalence assert below on
    // `lock-step F2 FIX` (the 8-word mem-indirect case), not by inspection.
    val lowSuppress  = (False ## pcLow).asUInt + (res.slot0.lenWords.resize(6) |<< 1)
    val lowFull      = (False ## pcLow).asUInt + (res.shiftWords.resize(6)     |<< 1)
    val hiP1Inc      = decodePcHiP1 + 1
    val hiSuppress   = Mux(lowSuppress(5), decodePcHiP1, decodePcHi)
    val hiFull       = Mux(lowFull(5),     decodePcHiP1, decodePcHi)
    val hiP1Suppress = Mux(lowSuppress(5), hiP1Inc,      decodePcHiP1)
    val hiP1Full     = Mux(lowFull(5),     hiP1Inc,      decodePcHiP1)
    val decodePcNext     = Mux(suppressSlot1, hiSuppress, hiFull) @@
                           Mux(suppressSlot1, lowSuppress(4 downto 0), lowFull(4 downto 0))
    val decodePcNextHiP1 = Mux(suppressSlot1, hiP1Suppress, hiP1Full)
    // Differential proof of the split-field advance: the live oracle is the original
    // full-width form. Simulation only; the netlist keeps only the split form.
    //
    // These run UNGATED, on every cycle: they caught the `|<<` width bug above on the
    // lock-step corpus, and holding them everywhere (not only where `decodePcNext` is
    // consumed) is what makes the restructuring a proof rather than a spot check.
    GenerationFlags.simulation {
      val decodePcSuppress = decodePc + (res.slot0.lenWords.resize(32) |<< 1)
      val decodePcFull     = decodePc + (res.shiftWords.resize(32) |<< 1)
      val refNext          = Mux(suppressSlot1, decodePcSuppress, decodePcFull)
      assert(decodePcNext === refNext,
        "FetchAlignPlugin: split-field decodePc advance diverged from the full-width adders",
        FAILURE)
      assert(decodePcNextHiP1 === (refNext(31 downto 5) + 1),
        "FetchAlignPlugin: split-field decodePc companion diverged from its invariant",
        FAILURE)
      assert(decodePcHiP1 === (decodePcHi + 1),
        "FetchAlignPlugin: decodePcHiP1 lost its decodePc(31 downto 5)+1 invariant",
        FAILURE)
    }

    // A shorter real instruction at the claimed branch PC may emit because every one of
    // its words is still genuine; recover at its fall-through. Likewise, an instruction
    // beginning before the claimed PC may emit only when all its words precede the splice,
    // then recover at its fall-through before another packet is framed. Backpressure must
    // not skip an instruction, hence both guards are fire-gated.
    val ftqLenBad = ftqAt0 && res.slot0Valid && !ftqConfirm && feed.fire
    val ftqOvershoot = ftqNear && !ftqAt0 && feed.fire && !emittingFaultPacket &&
                       (effShift > ftqDelta)
    ftqMismatchDetect := (ftqStarved || ftqPast || ftqLenBad || ftqOvershoot) &&
                         !quiesce && !stalled && !ftqMismatchPending
    ftqMismatchDetectPc := Mux(ftqLenBad || ftqOvershoot, decodePcNext, decodePc)
    // FMax Fix 1 (see the capture-register declaration above for the full equivalence
    // argument): unconditional capture. `ftqMismatchDetect` no longer reaches ANY of the
    // 64 capture flops' CE pins -- it now drives only `ftqMismatchPending` (1 flop) and
    // the pre-existing `predictDetectBlocked` term.
    ftqMismatchPcCurReg  := decodePc
    ftqMismatchPcNextReg := decodePcNext
    ftqMismatchPcSelReg  := ftqLenBad || ftqOvershoot
    ftqMismatchBrPcReg   := ftqHeadE.brPc
    when(ftqMismatchDetect) {
      ftqMismatchPending := True
    }
    // Mandatory drift proof for the retime above: a sim-only shadow captured under the
    // ORIGINAL gating must agree with the restructured registers on every cycle the values
    // are architecturally observable (i.e. every pending cycle). Elaborated ONLY in
    // simulation (`.includeSimulation`, see M68kSim.scala) -- putting a
    // `when(ftqMismatchDetect)`-enabled 32-bit register in the SYNTHESIZED netlist would
    // reintroduce the exact fo=71 CE cone this fix removes.
    GenerationFlags.simulation {
      val refPcReg   = Reg(UInt(32 bits)) init 0
      val refBrPcReg = Reg(UInt(32 bits)) init 0
      when(ftqMismatchDetect) {
        refPcReg   := ftqMismatchDetectPc
        refBrPcReg := ftqHeadE.brPc
      }
      when(ftqMismatchPending) {
        assert(ftqMismatchPc === refPcReg,
          "FetchAlignPlugin: FTQ mismatch recovery PC drifted from its gated-capture reference",
          FAILURE)
        assert(ftqMismatchBrPcReg === refBrPcReg,
          "FetchAlignPlugin: FTQ mismatch recovery branch PC drifted from its gated-capture reference",
          FAILURE)
      }
    }
    // A live fallback may only fill the action register. Architectural redirects and
    // a malformed FTB claim on this detector edge cancel it; those actions already
    // discard or replace the speculative stream.
    predictDetect := feed.fire && !faultHold && predictedThisEmit
    when(predictDetect) {
      predictPending := True
      predictTargetReg := predTargetSel
    }
    val predictDetectBlocked = redirect.valid || (resume.valid && stalled) ||
                               mispredictRedirect.valid || ftqMismatchDetect ||
                               icMaintFlushArm
    when(predictDetectBlocked) {
      predictPending := False
    }
    val predictDetectKept = predictDetect && !predictDetectBlocked
    val predictDetectKeptD = RegNext(predictDetectKept) init False
    when(predictFire) {
      assert(predictDetectKeptD,
        "registered fallback action lost its exact detector association")
    }
    when(predictDetectKeptD) {
      assert(predictFire,
        "accepted fallback detector did not produce its C+1 action")
    }
    // An architectural redirect on the detector edge already discards the malformed
    // plan, so it cancels the pending action. A same-cycle fallback detector is itself
    // canceled above: the registered mismatch action owns the next cycle.
    when(redirect.valid || (resume.valid && stalled) || mispredictRedirect.valid ||
         icMaintFlushArm) {
      ftqMismatchPending := False
    }
    val ftqMismatchDetectKept = ftqMismatchDetect &&
      !(redirect.valid || (resume.valid && stalled) || mispredictRedirect.valid ||
        icMaintFlushArm)
    val ftqMismatchDetectKeptD = RegNext(ftqMismatchDetectKept) init False
    when(ftqMismatch) {
      assert(ftqMismatchDetectKeptD,
        "registered FTQ mismatch action lost its exact detector association")
      assert(!feed.fire && !ic.cmd.fire,
        "registered FTQ mismatch recovery must block decode and fetch for its action cycle")
    }
    when(ftqMismatchDetectKeptD) {
      assert(ftqMismatch,
        "accepted FTQ mismatch detector did not produce its C+1 recovery action")
    }
    spinal.core.sim.SimPublic(ftqLenBad, ftqOvershoot, ftqMismatchDetectPc, ftqMismatchPc)

    when(feed.fire && !emittingFaultPacket) {
      ibuf.io.shift := effShift
      decodePc      := decodePcNext
      decodePcHiP1  := decodePcNextHiP1
      when(res.complex) {
        // Complex instruction: stall after emitting — wait for resume
        stalled := True
      }
    }

    // Exact confirmation consumes only the branch, then jumps decode to target bytes
    // already appended by the fetch ring. It does not flush the IBuf or stale the ring.
    val ftqConfirmFire = ftqConfirm && feed.fire && !emittingFaultPacket
    when(ftqConfirmFire) {
      setDecodePc(ftqHeadE.target)
    }
    ftqPop := ftqConfirmFire
    spinal.core.sim.SimPublic(ftqConfirmFire)

    // ── gshare GHR speculative shift (slice 3) ──────────────────────────────────
    // Shift the GHR on the emitted slot0 predicted CONDITIONAL (taken OR not — gated on
    // condBtbHit0, not the taken-only fallback detector/action), with the predicted bit.
    // We shift for slot0 only: a slot1-predicted-TAKEN conditional is deferred to slot0
    // next cycle (slot1WouldFtq / slot1WouldRasPred); a slot1 not-taken conditional that co-emits with slot0
    // has neither a GHR bit nor a PHT training index (slot1's prediction tag is inert).
    // The slot0 lookup index used the GHR
    // BEFORE this shift; the carried phtIndex (stamped above) matches. The GHR IS now
    // repaired on a commit flush -- see GsharePlugin's `ghrArch`/`flushRepair`; this
    // shift set and the `phtValid` stamp set above are deliberately identical so that
    // repair installs a bit-exact history.
    val ftqConfirmCond = ftqConfirmFire && ftqHeadE.isCond
    gsShiftValid := ftqConfirmCond ||
                    (feed.fire && !faultHold && condBtbHit0 && res.slot0Valid && !ftqConfirm)
    gsShiftDir   := Mux(ftqConfirmCond, True, slot0PredTaken)

    // ── RAS push/pop bookkeeping (slice 2) ───────────────────────────────────────
    // On the cycle the emitted slot0 fires (NOT a faulted packet): a call pushes its
    // return PC; a (predicted) return pops. push XOR pop (a call XOR a return). The
    // pop only fires when the RAS predicted (count>0); an empty-RAS return does not pop.
    // A predicted return also fills the fallback target register on this edge; the C+1
    // action invalidates every old in-flight window while issuing the captured target.
    // The EU always verifies the real return target, so a wrong RAS guess recovers via
    // the existing commit-time redirect (a corrupt RAS is only a perf loss).
    rasPushValid := feed.fire && !faultHold && s0IsCall
    rasPushRetPc := s0RetPc
    rasPopValid  := feed.fire && !faultHold && rasPredictSlot0

    // ── registered fallback action (BTB/RAS hit) — below architectural redirects ──
    // `predictDetect` captured the target when the branch fired in C. In C+1 this
    // registered action blocks decode, flushes old bytes/state, and may issue the target
    // command immediately. Thus the cache command remains C+1 while no live decode term
    // reaches the ITLB/cache/ring enables.
    when(predictFire) {
      val newPc   = predictTargetReg
      val newBase = predictWindowPc
      val actionWins = !(redirect.valid || (resume.valid && stalled) ||
                         mispredictRedirect.valid || ftqMismatch)
      setDecodePc(newPc)
      fetchPc     := Mux(ic.cmd.fire && actionWins, newBase + 8, newBase)
      ibuf.io.flush  := True
      stalled        := False
      started        := True
      pendingDrop    := Mux(ic.cmd.fire && actionWins, U(0, 2 bits), newPc(2 downto 1))
      // Every pre-action request is wrong-path. The target command issued in this action
      // cycle used the captured PC, so make its newly allocated tail record live after
      // the blanket stale assignment. A higher-priority redirect leaves it stale.
      ringStale.foreach(_ := True)
      when(ic.cmd.fire && actionWins) {
        ringStale(ringTail) := False
      }
    }

    // task #250: shared helper for the five redirect arms below (`redirect`,
    // `resetRedirect`, `resume`, `vioRedirect`, `mispredictRedirect`) -- each one restarts
    // fetch at `newPc` and was previously ~15 nearly-identical inline lines setting this
    // same field set. Pure mechanical dedup, zero behavioral change: every call site below
    // sets exactly what its original inline block set, including `resume`'s pre-existing
    // omission of `faultHold`/`faultEmitted`/`started` (`clearFaultHold = false`). NOT used
    // by `ftqMismatch`/`predictFire` above: `ftqMismatch` has its own subtly different
    // priority/suppression story and `predictFire`'s `fetchPc`/`pendingDrop` derivation is
    // genuinely different (the `actionWins` window-reuse logic), so folding either in here
    // would blur a real distinction rather than remove a redundant one.
    def commonRedirect(newPc: UInt, clearFaultHold: Boolean = true): Unit = {
      setDecodePc(newPc)
      // fetchPc = 8-aligned base of the window containing newPc
      fetchPc       := newPc(31 downto 3) @@ U(0, 3 bits)
      ibuf.io.flush := True
      stalled       := False
      if (clearFaultHold) {
        // Clear the I-fetch-fault hold: the exception delivered + vectored, resume fetch.
        faultHold    := False
        faultEmitted := False
        started      := True
      }
      pendingDrop := newPc(2 downto 1)
      // Per-fetch stale tracking (recValid/recStale/recDrop) replaces the single-bit
      // rspStale/dropPending. Mark the in-flight fetch (if any) stale: a redirect
      // invalidates it. Depth-2: mark ALL ring entries stale -- every fetch issued before
      // this redirect (and one issued THIS cycle, born stale via redirectThisCycle) is
      // wrong-path; its response must be discarded. Free slots' stale bits are don't-care
      // (overwritten at their next issue). This is the recStale bug class: ALL outstanding
      // fetches stale.
      ringStale.foreach(_ := True)
    }

    // Wrong framing claim: restart sequentially at the first not-yet-emitted PC (or the
    // just-emitted packet's fall-through), clear only this FTB entry, and suppress one
    // refetch application so the same stale claim cannot livelock. Architectural redirects
    // below retain their existing higher priority.
    // ── I-cache maintenance (CPUSH / CINV) must also flush the INSTRUCTION BUFFER ──
    // The canonical 68040 self-modifying-code sequence is `store / CPUSHL / jump`. The
    // store lands, the CPUSHL correctly drops the I-cache line -- and the pre-patch bytes
    // then get executed anyway, because they were already sitting in `ibuf`, which until
    // now was flushed ONLY by branch redirects and FTQ mismatch. Nothing connected
    // `icMaintPulse` (the CPUSH/CINV I-cache invalidate, already wired to IcachePlugin and
    // the BTB) to the fetch buffer, so the buffer kept serving stale instructions and no
    // amount of re-execution cleared it -- the instruction is simply never re-fetched.
    //
    // Observed on hardware as legal instructions that "cannot be decoded": a `JMP xxx.L`
    // thunk in a runtime-built dispatch table, and an FP store, both verified correct in
    // memory and both decoding correctly in simulation. Reproduced by the 7 ifstage_smc_*
    // tests, which fail 7/7 with caches ON (they run cache-OFF by default -- see the
    // cache-sweep manifest note in PortedM68kOooSpec).
    //
    // Handled as a self-redirect rather than a bare flush: dropping the buffer alone would
    // lose the fetch stream position. Re-fetching from `decodePc` is exactly what the
    // architecture requires after cache maintenance, and it is ordered AFTER the pulse --
    // which ExceptionUnit deliberately fires on the maintenance walk's COMPLETION -- so the
    // refetch cannot repopulate from a line that is about to be invalidated.
    when(icMaintFlushArm) {
      val newPc       = decodePc
      setDecodePc(newPc)
      fetchPc         := newPc(31 downto 3) @@ U(0, 3 bits)
      ibuf.io.flush   := True
      stalled         := False
      started         := True
      pendingDrop     := newPc(2 downto 1)
      ringStale.foreach(_ := True)
    }

    when(ftqMismatch) {
      val newPc       = ftqMismatchPc
      setDecodePc(newPc)
      fetchPc         := newPc(31 downto 3) @@ U(0, 3 bits)
      ibuf.io.flush   := True
      stalled         := False
      started         := True
      pendingDrop     := newPc(2 downto 1)
      faultHold       := False
      faultEmitted    := False
      ringStale.foreach(_ := True)
    }

    // ---- redirect (highest priority) ----
    // (No !ic.rsp.valid guard — the per-fetch recStale bit is robust; a stale rsp is
    // consumed + discarded in the rsp block. recValid is NOT cleared: the fetch is still
    // physically coming; its stale response clears recValid normally, preserving the
    // single-outstanding invariant — a new fetch issues only after that frees occupancy.)
    when(redirect.valid) {
      commonRedirect(redirect.payload)
    }

    // ---- reset-vector redirect (axi-socket adapter D12/D16) ----
    // Same effect as the external redirect above (now the same `commonRedirect` call, only
    // the payload source differs): placed AFTER the external redirect so a directed harness
    // driving the external port still wins on the impossible cycle where both fire, and
    // BEFORE mispredictRedirect so a commit-time redirect keeps its top priority.
    when(resetRedirect.valid) {
      commonRedirect(resetRedirect.payload)
    }

    // ---- resume (lower priority than redirect, active when stalled) ----
    // Pre-existing behavior preserved exactly: unlike the other four arms, this one does
    // NOT clear faultHold/faultEmitted/started -- `commonRedirect(clearFaultHold = false)`.
    when(resume.valid && stalled) {
      commonRedirect(resume.payload, clearFaultHold = false)
    }

    // ---- VIO boot-PC injector redirect (vio-jtag-debug spec V17/V19) ----
    // Placed AFTER `redirect`, `resetRedirect`, AND `resume` (a task-3-implementation-time
    // correction to the plan brief, which only knew about `redirect` and `resetRedirect` --
    // a third arm, `resume` (automatic stall-resume), also sits between them and was missed
    // by the brief's own placement instruction). Outranking all three automatic/external
    // frontend mechanisms is the correct, UNIFORM application of V19's own stated rationale
    // ("the operator is physically present and pressing a button; [the automatic mechanism]
    // is automatic. Deliberate manual override of an automatic mechanism is the correct
    // precedence") -- that rationale does not carve out an exception for `resume` just
    // because the brief's author had not yet found it. Placed BEFORE `mispredictRedirect` so
    // a genuine ROB commit-time correction -- real architectural state a debug action must
    // not corrupt -- always keeps final say. Same effect as the `resetRedirect` arm above
    // (now the same `commonRedirect` call), payload source changed to `vioRedirect.payload`.
    when(vioRedirect.valid) {
      commonRedirect(vioRedirect.payload)
    }

    // ---- commit-time mispredict redirect (HIGHEST priority) ----
    // `mispredictRedirect` is an internal directionless Flow, default-driven idle
    // (allowOverride) so FetchAlign elaborates standalone. In the full core a
    // sibling wiring plugin OVERRIDES it from the ROB's RedirectService (doFlush /
    // flushPc) — a REGISTERED one-cycle pulse. We drive it from the wiring plugin
    // (not by reading host[RedirectService] here) to avoid a Fiber build-order
    // cycle (FetchAlign build -> ROB build -> ... -> FetchAlign.feed). On the pulse
    // we restart fetch at the resolved flushPc, exactly like an external redirect
    // (same `commonRedirect` call). Placed LAST so it wins (later when in SpinalHDL
    // overrides the external one).
    when(mispredictRedirect.valid) {
      commonRedirect(mispredictRedirect.payload)
    }

    // FTQ occupancy is updated once, with flush last so no same-cycle prediction can
    // survive an architectural/decode redirect. Pop credit is deliberately absent from
    // applyNow; the legal depth makes full unreachable without a decode-ready→fetch path.
    when(ftqPop) {
      ftqHead := ftqInc(ftqHead)
    }
    when(ftqPush && !ftqPop) {
      ftqCount := (ftqCount + 1).resized
    } elsewhen(!ftqPush && ftqPop) {
      ftqCount := (ftqCount - 1).resized
    }
    when(ftqFlush) {
      ftqHead := 0
      ftqTail := 0
      ftqCount := 0
      targetHoldValid := False
    }

    // One-shot mismatch suppression priority: ordinary progress clears, mismatch sets,
    // and a fresh architectural/decode redirect clears last.
    when(feed.fire) {
      ftbSuppress := False
    }
    when(ftqMismatch) {
      ftbSuppress := True
    }
    when(redirect.valid || (resume.valid && stalled) ||
         mispredictRedirect.valid || predictFire || icMaintFlushArm) {
      ftbSuppress := False
    }

    // Hard simulation-only tripwire for the capacity proof. This is the sole remaining
    // consumer of `ftqFull`; it is emitted inside `ifndef SYNTHESIS` and drives no
    // netlist, so the elaboration bound plus this assertion carry capacity correctness
    // while the fetch/VIPT cone stays free of the occupancy comparator.
    assert(!ftqFull, "FTQ reached its defensive full state despite the run-ahead bound")
  }

  // ---- DecodeFeedService implementation ----
  override def feed: Stream[Vec[DecodePacket]]  = logic.feed
  override def slot1Valid: Bool                 = logic.slot1ValidOut
}
