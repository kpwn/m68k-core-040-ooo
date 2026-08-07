package m68k040.frontend

import m68k040.cache.{ChunkPredecode, FetchCmd, FetchRsp}
import m68k040.services.{DecodeFeedService, FetchService}
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** FetchAlignPlugin: integrates FetchService (I-cache), InstructionBuffer, and
  * Aligner into a 2-wide DecodePacket stream (DecodeFeedService).
  *
  * Key design points:
  *  - Single-outstanding fetch: one cmd in flight at a time.
  *  - Leading-word drop on redirect/resume: only enqueue words from decodePc's
  *    offset within the fetched 8-byte window.
  *  - Staleness: in-flight rsp after a redirect/resume is silently dropped.
  *  - Complex-stall: emit the complex packet once, then hold feed.valid low
  *    until resume. Emit-once is enforced by the `stalled` latch: when a complex
  *    packet fires, `stalled` latches True the next cycle and suppresses
  *    feed.valid until resume clears it. (No separate complexPending delay; the
  *    complex packet is valid for one cycle, observed by per-cycle sampling.)
  */
class FetchAlignPlugin extends FiberPlugin with DecodeFeedService {

  val logic = during build new Area {

    // ---- Resolve I-cache service ----
    val ic = host[FetchService]

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
    mispredictRedirect.payload.allowOverride; mispredictRedirect.payload := U(0, 32 bits)

    // ── STOP-halt quiesce (full core): the ROB drives this high while the core is in the
    // STOP `stopped` state. It suppresses fetch + feed (the front-end holds at the STOP
    // successor PC the post-STOP redirect set) until the wake redirect (the IRQ-entry
    // vector) clears it — exactly like faultHold, but it emits NO packet. Directionless,
    // idle-defaulted with a concrete zero (allowOverride) so a DUT that does NOT wire it
    // still elaborates (the wiring layer overrides it). ──────────────────────────────
    val quiesce = Bool()
    quiesce.allowOverride; quiesce := False

    // ── Fetch-time BTB prediction interface (slice 1) ────────────────────────────
    // FetchAlign DRIVES the two per-instruction query PCs (slot0/slot1 of the aligner
    // head) + valids; the BtbPlugin returns the COMBINATIONAL prediction this cycle.
    // Directionless plain wires (the IcachePlugin convention): the wiring layer
    // connects the query OUTPUTS to the BTB and the prediction INPUTS back. Concrete
    // idle defaults so FetchAlign elaborates standalone (predictor inert).
    //   btbQueryPc0/1, btbQueryValid0/1 : DRIVEN here (the aligner slot PCs).
    //   btbPredTaken0/1, btbPredTarget0/1 : INPUT (the BTB's combinational predict).
    val btbQueryPc0    = UInt(32 bits); val btbQueryValid0 = Bool()
    val btbQueryPc1    = UInt(32 bits); val btbQueryValid1 = Bool()
    val btbPredTaken0  = Bool(); val btbPredTarget0 = UInt(32 bits)
    val btbPredTaken1  = Bool(); val btbPredTarget1 = UInt(32 bits)
    // Inputs: idle-defaulted (allowOverride) so a standalone DUT (no BTB) reads not-taken.
    btbPredTaken0.allowOverride;  btbPredTaken0  := False
    btbPredTarget0.allowOverride; btbPredTarget0 := U(0, 32 bits)
    btbPredTaken1.allowOverride;  btbPredTaken1  := False
    btbPredTarget1.allowOverride; btbPredTarget1 := U(0, 32 bits)

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
    //   gsBtbHit0/1, gsBtbType0/1 : the BTB hit + brType (INPUT, to form condBtbHit).
    //   gsPhtTaken0/1 : the PHT direction for that PC (INPUT).
    //   gsPhtIndex0/1 : the 11-bit folded-XOR index (INPUT, carried down).
    //   gsShiftValid/gsShiftDir : the GHR shift (OUTPUT, driven below).
    val gsBtbHit0   = Bool(); val gsBtbType0 = UInt(2 bits)
    val gsBtbHit1   = Bool(); val gsBtbType1 = UInt(2 bits)
    val gsPhtTaken0 = Bool(); val gsPhtIndex0 = UInt(11 bits)
    val gsPhtTaken1 = Bool(); val gsPhtIndex1 = UInt(11 bits)
    gsBtbHit0.allowOverride;   gsBtbHit0   := False
    gsBtbType0.allowOverride;  gsBtbType0  := U(0, 2 bits)
    gsBtbHit1.allowOverride;   gsBtbHit1   := False
    gsBtbType1.allowOverride;  gsBtbType1  := U(0, 2 bits)
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
    val stalled       = Reg(Bool()) init False       // complex-instruction stall
    val started       = Reg(Bool()) init False       // don't fetch until first redirect
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
    val ringHead  = Reg(UInt(log2Up(RING) bits)) init 0      // oldest in-flight: the NEXT rsp belongs to it
    val ringTail  = Reg(UInt(log2Up(RING) bits)) init 0      // next slot to ISSUE into
    val ringCount = Reg(UInt(log2Up(RING + 1) bits)) init 0  // # outstanding (0..RING)
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

    // ---- Default-drive IBuf inputs ----
    ibuf.io.push.valid   := False
    ibuf.io.push.payload.words.foreach(_ := 0)
    ibuf.io.push.payload.preds.foreach { p => p.simple := False; p.lenWords := 0; p.ambiguousLine := False }
    ibuf.io.push.payload.n := 0
    ibuf.io.shift        := 0
    ibuf.io.flush        := False

    // A predicted-taken BTB redirect fires this cycle (forward-declared; driven below
    // after the aligner + BTB lookup). Like the architectural redirects, a fetch issued
    // the SAME cycle used the pre-redirect fetchPc and MUST be born stale — otherwise it
    // fetches the (now wrong-path) sequential window and enqueues garbage that is NEVER
    // squashed (a correctly-predicted branch does NOT flush the downstream). This was the
    // staleness bug: predictFire was missing from redirectThisCycle.
    val predictFire = Bool()
    predictFire.allowOverride
    predictFire := False   // default; overridden below (driven after the aligner/BTB lookup)

    // Any fetch-redirect this cycle (external redirect, complex-resume, commit mispredict,
    // OR a fetch-time prediction). A fetch issued THIS cycle used the pre-redirect fetchPc
    // -> born stale. (mispredictRedirect/redirect/resume declared above; resume only when stalled.)
    val redirectThisCycle = redirect.valid || (resume.valid && stalled) || mispredictRedirect.valid || predictFire

    // ---- FetchControl: issue fetches (DEPTH-3 multi-outstanding, ANGLE D) ----
    // Issue while the ring has a free slot (!ringFull) — up to 3 in flight. Suppress
    // fetching while holding an I-fetch fault OR while STOP-quiesced (wait for the
    // redirect / IRQ-entry vector to clear it).
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
    val ibufRoomForIssue =
      (ibuf.io.cnt +^ ((ringCount +^ U(1)) * U(4))) <= U(ibuf.BUF_WORDS)
    ic.cmd.valid      := started && !ringFull && ibufRoomForIssue && !stalled && !faultHold && !quiesce
    ic.cmd.payload.pc := fetchPc

    when(ic.cmd.fire) {
      // Capture this fetch's record into the TAIL ring slot. Born stale iff a redirect
      // fires THIS cycle (this fetch used the old, now-wrong fetchPc). Latch the drop
      // intent; consume it. Advance tail + count.
      ringStale(ringTail) := redirectThisCycle
      ringDrop(ringTail)  := pendingDrop
      ringTail := ringInc(ringTail)
      pendingDrop := 0
      // Advance the SEQUENTIAL fetch pointer at ISSUE (depth-2 needs fetchPc+8 ready for
      // the NEXT cycle's issue while this fetch is still in flight). Only for a live
      // (non-redirect) issue: a redirect this cycle resets fetchPc to its target below
      // (and overrides this), and a stale issue's window is discarded. Guarding on
      // !redirectThisCycle keeps fetchPc from walking past a coincident redirect target.
      when(!redirectThisCycle) {
        fetchPc := fetchPc + 8
      }
    }
    // ringCount: +1 on a fire that is NOT consumed this cycle, -1 on a rsp consume that
    // is not re-issued; net handled below where the rsp is consumed (both can happen the
    // same cycle in steady state: one in, one out -> count unchanged).

    // ---- Enqueue: handle I-cache responses ----
    // Extract the 4 words from the 64-bit response data (LE: bits[15:0] = word 0 = lowest addr)
    val rspWords = ic.rsp.payload.data.subdivideIn(16 bits)
    val rspPreds = ic.rsp.payload.pred

    // The response belongs to the HEAD ring entry (in-order pipeline). Its OWN
    // stale/drop govern discard + leading-word drop.
    val rspStaleHead = ringStale(ringHead)
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
        val nWords    = U(4, 3 bits) - startWord.resize(3)
        for (j <- 0 until 4) {
          when(U(j) < nWords) {
            val srcIdx = (startWord + U(j, 2 bits)).resize(2)
            ibuf.io.push.payload.words(j)                 := rspWords(srcIdx)
            ibuf.io.push.payload.preds(j).simple          := rspPreds(srcIdx).simple
            ibuf.io.push.payload.preds(j).lenWords        := rspPreds(srcIdx).lenWords
            ibuf.io.push.payload.preds(j).ambiguousLine   := rspPreds(srcIdx).ambiguousLine
          }
        }
        ibuf.io.push.payload.n := nWords
        ibuf.io.push.valid     := True
      }
      // stale OR fault: enqueue nothing (discard), as before.
    }

    // ---- Ring occupancy update (single driver for ringCount) ----
    // +1 when a fetch issues, -1 when a response is consumed; both can happen the same
    // cycle (steady-state: one in / one out -> count unchanged). The issue/rsp guards
    // above (!ringFull / ringHead consume) keep this in [0, RING].
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
    // the already-rare, already-multi-cycle-tolerant ambiguous-head case (during such a
    // stall `feed.fire` is False so shift==0, and `cnt` grows past 4 within one push).
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
    val p0LiveReg = Reg(ChunkPredecode())
    p0LiveReg.simple        init False
    p0LiveReg.lenWords      init 0
    p0LiveReg.ambiguousLine init True    // reset state must never read as "already resolved"
    p0LiveReg := PredecodeWord.classify(ibuf.io.head(0), ibuf.io.head(1), ibuf.io.head(2), ibuf.io.head(3),
      extWValid  = ibuf.io.avail >= U(2, 4 bits),
      extW2Valid = ibuf.io.avail >= U(3, 4 bits),
      extW3Valid = ibuf.io.avail >= U(4, 4 bits))
    val p0LiveInvalidate = ibuf.io.flush || (ibuf.io.shift =/= 0) ||
                           (ibuf.io.push.fire && (ibuf.io.cnt < U(4, ibuf.io.cnt.getWidth bits)))
    when(p0LiveInvalidate) {
      p0LiveReg.ambiguousLine := True
    }

    // ---- Aligner: combinational decode of buffer head ----
    val res = Aligner.align(decodePc, ibuf.io.head, ibuf.io.headPred, ibuf.io.avail, p0LiveReg)
    spinal.core.sim.SimPublic(ibuf.io.headPred(0).simple, ibuf.io.head(0))

    // ── Fetch-time prediction (BTB + bimodal, slice 1) ───────────────────────────
    // Query the BTB combinationally with the aligner's slot0/slot1 instruction PCs;
    // the BTB returns predict-taken + target THIS cycle. An emitted slot that predicts
    // taken IS the predicted-taken branch. We:
    //   (a) stamp predTaken/predTarget on that slot's DecodePacket (rides to the EU),
    //   (b) SUPPRESS everything after it in the window (slot1 after a predicted slot0 is
    //       wrong-path),
    //   (c) on feed.fire redirect fetch to predTarget (predictFire, below) — reusing the
    //       redirect machinery, at priority BELOW commit/external/resume.
    // Architectural correctness does NOT depend on the prediction being right: the
    // branch EU verifies predicted-vs-actual + the commit-time redirect recovers a
    // mispredict. A correct prediction simply avoids the squash.
    val predEnable = !faultHold && !quiesce && !stalled
    btbQueryPc0    := res.slot0.pc
    btbQueryValid0 := predEnable && res.slot0Valid
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
    // slot1 predicted-taken: keep the SLICE-1 BTB source (NOT gshare). FMax: the slot1
    // predict feeds slot1ValidOut -> the ibuf shift/suppress cone, which is the front-end
    // critical path; injecting the gshare slot1 PHT read + condBtbHit1 there regressed
    // FMax. A slot1 predicted-taken branch is only DEFERRED to slot0 next cycle anyway
    // (slot1WouldPred), where slot0's gshare direction takes over — so sourcing the slot1
    // DEFER decision from the BTB bimodal (vs gshare) is a pure micro-perf nuance on the
    // defer cycle, never a correctness issue (the EU verifies; gshare drives it as slot0
    // next cycle). This keeps the slot1ValidOut cone identical to slice-1/2.
    val slot1PredTaken = btbPredTaken1
    // slot0 predicted-taken: stamp slot0 + suppress slot1 + redirect (the proven path).
    val slot0IsPred = slot0PredTaken && res.slot0Valid
    // slot1 predicted-taken (and slot0 is NOT): SUPPRESS slot1 this cycle so ONLY slot0
    // emits; decodePc advances to the branch, which becomes slot0 NEXT cycle and takes
    // the (correct, single-slot) slot0 prediction path. Costs one dual-issue slot on the
    // predicted branch but reuses the proven slot0 redirect and keeps recovery simple.
    val slot1WouldPred = slot1PredTaken && res.slot1Valid && res.slot0Valid && !slot0IsPred
    // NOTE (FMax): we deliberately do NOT defer a predicted-NOT-taken slot1 conditional.
    // If both slot0 and slot1 are conditionals emitted the same cycle, only slot0's GHR
    // bit shifts (the single-ported GHR shifts once) — slot1's history bit is lost. That
    // is a pure ACCURACY imperfection (the accept-corruption philosophy already tolerates
    // GHR imprecision), NOT a correctness bug: each conditional carries its OWN fetch-time
    // phtIndex down, so the retire-time train hits the exact entry the lookup read
    // regardless of the GHR shift. Deferring slot1 instead pulled the BTB slot1 hit/brType
    // (condBtbHit1) into the ibuf shift/suppress cone and regressed the fetch FMax, so it
    // is intentionally omitted (the slot1-predicted-TAKEN defer, slot1WouldPred, stays —
    // it already existed for the BTB and is needed for the redirect discipline).

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
    // mirrors slot1WouldPred (the BTB slot1-branch deferral) and is essential: a leaf
    // `add ; rts` emits add=slot0 + rts=slot1 in one cycle, so without deferral the rts
    // never reaches the slot0 RAS-predict and every return mispredicts (the flush the
    // RAS is meant to remove). The opword classification only needs slot1's opword.
    val s1op       = res.slot1.words(0)
    val s1IsRts    = s1op === B"16'h4E75"
    val s1IsRtr    = s1op === B"16'h4E77"
    val s1IsReturn = res.slot1Valid && res.slot1.simple && (s1IsRts || s1IsRtr)
    val slot1WouldRasPred = s1IsReturn && rasPredValid && res.slot0Valid && !slot0IsPred && !rasPredictSlot0

    // ── Compose the slot0 prediction source: isReturn ? RAS : BTB (slice 2) ───────
    // A return is predicted by the RAS (top-of-stack); everything else by the BTB
    // (slice 1). Returns + BTB hits are mutually exclusive (slice 1 never learns a
    // return), so this is a clean either/or. The composed predicted-taken + target
    // drive the SAME slice-1 machinery: slot1 suppression, predTaken/predTarget
    // stamping, the effShift consume, and predictFire/redirect — so a RAS-predicted
    // return is a predicted-taken redirect identical to a BTB taken branch.
    val slot0Predicted    = slot0IsPred || rasPredictSlot0
    val predictedThisEmit = slot0Predicted
    val predTargetSel     = Mux(rasPredictSlot0, rasPredTarget, btbPredTarget0)

    // ---- Feed valid logic ----
    // Emit when a packet is at head and not stalled. Complex packets emit once:
    // the cycle after they fire, the stalled latch suppresses re-emission.
    feed.payload(0) := res.slot0
    feed.payload(1) := res.slot1
    slot1ValidOut   := res.slot1Valid
    // Suppress slot1 when slot0 is the predicted-taken branch (slot1 is wrong-path) OR
    // when slot1 WOULD be a predicted branch (defer it to slot0 next cycle). slot0Predicted
    // folds in a RAS-predicted return (slice 2): its slot1 is equally wrong-path.
    when(slot0Predicted || slot1WouldPred || slot1WouldRasPred) {
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
    when(condBtbHit0 && res.slot0Valid) {
      feed.payload(0).phtValid := True
      feed.payload(0).phtIndex := gsPhtIndex0
    }
    // Gate feed low while STOP-quiesced so no buffered successor word is dispatched /
    // allocated into the ROB while halted (the quiesce only ends on the wake redirect).
    feed.valid      := res.slot0Valid && !stalled && !quiesce
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
    val emittingFaultPacket = faultHold && !res.slot0Valid && !stalled && !quiesce
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
    val suppressSlot1 = slot0Predicted || slot1WouldPred || slot1WouldRasPred
    val effShift = Mux(suppressSlot1, res.slot0.lenWords.resize(res.shiftWords.getWidth), res.shiftWords)
    // FMax (front-end floor): RETIME the decodePc advance so the late suppressSlot1 decision
    // (BTB/RAS prediction cones) muxes the 32-bit ADD RESULT instead of the adder's addend.
    // effShift is either slot0.lenWords (suppress) or shiftWords (full) — both aligner outputs;
    // precompute decodePc + (each<<1) in PARALLEL and select by suppressSlot1. This pulls the
    // 6-deep decodePc CARRY8 chain OUT from behind the effShift mux (the `pred_lenWords ->
    // L0L1 -> slot1Ok/suppress -> addend -> adder` arc was the bistable-ordering 194MHz
    // limiter): the adders now start as soon as their length is ready, and suppressSlot1 only
    // drives a final 1-LUT 32-bit 2:1 result mux. Byte-identical: same value, restructured.
    val decodePcSuppress = decodePc + (res.slot0.lenWords.resize(32) |<< 1)   // slot1 suppressed
    val decodePcFull     = decodePc + (res.shiftWords.resize(32) |<< 1)       // full aligner shift
    val decodePcNext     = Mux(suppressSlot1, decodePcSuppress, decodePcFull)
    when(feed.fire && !emittingFaultPacket) {
      ibuf.io.shift := effShift
      decodePc      := decodePcNext
      when(res.complex) {
        // Complex instruction: stall after emitting — wait for resume
        stalled := True
      }
    }

    // ── gshare GHR speculative shift (slice 3) ──────────────────────────────────
    // Shift the GHR on the emitted slot0 predicted CONDITIONAL (taken OR not — gated on
    // condBtbHit0, NOT predictFire which is taken-only), with the predicted direction bit.
    // We shift for slot0 only: a slot1-predicted-TAKEN conditional is deferred to slot0
    // next cycle (slot1WouldPred); a slot1 not-taken conditional that co-emits with slot0
    // simply loses its GHR bit (accept-corruption — its carried phtIndex still trains the
    // right entry at retire, so correctness is unaffected). The lookup index used the GHR
    // BEFORE this shift; the carried phtIndex (stamped above) matches. NOT checkpointed/
    // restored on a flush (accept-corruption).
    gsShiftValid := feed.fire && !faultHold && condBtbHit0 && res.slot0Valid
    gsShiftDir   := slot0PredTaken

    // ── RAS push/pop bookkeeping (slice 2) ───────────────────────────────────────
    // On the cycle the emitted slot0 fires (NOT a faulted packet): a call pushes its
    // return PC; a (predicted) return pops. push XOR pop (a call XOR a return). The
    // pop only fires when the RAS predicted (count>0); an empty-RAS return does not
    // pop (it stays unpredicted, exactly as today). A predicted return ALSO drives the
    // fetch redirect (predictFire below), so it is folded into redirectThisCycle — a
    // same-cycle in-flight fetch is born stale (the slice-1 staleness-bug class). The
    // EU always verifies the real return target, so a wrong RAS guess recovers via the
    // existing commit-time redirect (a corrupt RAS is only a perf loss).
    rasPushValid := feed.fire && !faultHold && s0IsCall
    rasPushRetPc := s0RetPc
    rasPopValid  := feed.fire && !faultHold && rasPredictSlot0

    // ── predict redirect (BTB hit, slice 1) — BELOW commit/external/resume ────────
    // When a predicted-taken branch was emitted this cycle (feed.fire), redirect fetch
    // to the predicted target — exactly like an external redirect (decodePc/fetchPc/
    // pendingDrop/recStale). Placed AFTER the normal decodePc advance (so it overrides
    // it) but BEFORE the external/resume/mispredict redirects (so an architectural
    // redirect coincident with a prediction always wins). When slot0 is the predicted
    // branch, slot1 was suppressed above (slot1Valid:=False); when slot1 is the
    // predicted branch, both slots emitted and we redirect after slot1.
    predictFire := feed.fire && !faultHold && predictedThisEmit
    when(predictFire) {
      val newPc   = predTargetSel
      decodePc    := newPc
      fetchPc     := newPc(31 downto 3) @@ U(0, 3 bits)
      ibuf.io.flush  := True
      stalled        := False
      started        := True
      pendingDrop    := newPc(2 downto 1)
      // Mark the in-flight fetch (if any) stale — the speculative target window
      // supersedes it (the same recStale discipline the architectural redirects use).
      // Depth-2: mark ALL ring entries stale — every fetch issued before this redirect
      // (and one issued THIS cycle, born stale via redirectThisCycle) is wrong-path; its
      // response must be discarded. Free slots' stale bits are don't-care (overwritten at
      // their next issue). This is the recStale bug class: ALL outstanding fetches stale.
      ringStale.foreach(_ := True)
    }

    // ---- redirect (highest priority) ----
    when(redirect.valid) {
      val newPc   = redirect.payload
      decodePc    := newPc
      // fetchPc = 8-aligned base of the window containing newPc
      fetchPc     := newPc(31 downto 3) @@ U(0, 3 bits)
      ibuf.io.flush  := True
      stalled        := False
      // Clear the I-fetch-fault hold: the exception delivered + vectored, resume fetch.
      faultHold      := False
      faultEmitted   := False
      started        := True
      pendingDrop    := newPc(2 downto 1)
      // Per-fetch stale tracking (recValid/recStale/recDrop) replaces the single-bit rspStale/dropPending.
      // Mark the in-flight fetch (if any) stale: a redirect invalidates it.
      // (No !ic.rsp.valid guard — the per-fetch recStale bit is robust; a stale rsp is
      // consumed + discarded in the rsp block. recValid is NOT cleared: the fetch is still
      // physically coming; its stale response clears recValid normally, preserving the
      // single-outstanding invariant — a new fetch issues only after that frees occupancy.)
      // Depth-2: mark ALL ring entries stale — every fetch issued before this redirect
      // (and one issued THIS cycle, born stale via redirectThisCycle) is wrong-path; its
      // response must be discarded. Free slots' stale bits are don't-care (overwritten at
      // their next issue). This is the recStale bug class: ALL outstanding fetches stale.
      ringStale.foreach(_ := True)
    }

    // ---- resume (lower priority than redirect, active when stalled) ----
    when(resume.valid && stalled) {
      val newPc   = resume.payload
      decodePc    := newPc
      fetchPc     := newPc(31 downto 3) @@ U(0, 3 bits)
      ibuf.io.flush  := True
      stalled        := False
      pendingDrop    := newPc(2 downto 1)
      // Per-fetch stale tracking (recValid/recStale/recDrop) replaces the single-bit rspStale/dropPending.
      // Depth-2: mark ALL ring entries stale — every fetch issued before this redirect
      // (and one issued THIS cycle, born stale via redirectThisCycle) is wrong-path; its
      // response must be discarded. Free slots' stale bits are don't-care (overwritten at
      // their next issue). This is the recStale bug class: ALL outstanding fetches stale.
      ringStale.foreach(_ := True)
    }

    // ---- commit-time mispredict redirect (HIGHEST priority) ----
    // `mispredictRedirect` is an internal directionless Flow, default-driven idle
    // (allowOverride) so FetchAlign elaborates standalone. In the full core a
    // sibling wiring plugin OVERRIDES it from the ROB's RedirectService (doFlush /
    // flushPc) — a REGISTERED one-cycle pulse. We drive it from the wiring plugin
    // (not by reading host[RedirectService] here) to avoid a Fiber build-order
    // cycle (FetchAlign build -> ROB build -> ... -> FetchAlign.feed). On the pulse
    // we restart fetch at the resolved flushPc, exactly like an external redirect.
    // Placed LAST so it wins (later when in SpinalHDL overrides the external one).
    when(mispredictRedirect.valid) {
      val newPc   = mispredictRedirect.payload
      decodePc    := newPc
      fetchPc     := newPc(31 downto 3) @@ U(0, 3 bits)
      ibuf.io.flush  := True
      stalled        := False
      started        := True
      // Clear the I-fetch-fault hold: the commit-side exception delivered the fault
      // and vectored here -> resume fetching at the redirected (handler) PC.
      faultHold      := False
      faultEmitted   := False
      pendingDrop    := newPc(2 downto 1)
      // Per-fetch stale tracking (recValid/recStale/recDrop) replaces the single-bit rspStale/dropPending.
      // Depth-2: mark ALL ring entries stale — every fetch issued before this redirect
      // (and one issued THIS cycle, born stale via redirectThisCycle) is wrong-path; its
      // response must be discarded. Free slots' stale bits are don't-care (overwritten at
      // their next issue). This is the recStale bug class: ALL outstanding fetches stale.
      ringStale.foreach(_ := True)
    }
  }

  // ---- DecodeFeedService implementation ----
  override def feed: Stream[Vec[DecodePacket]]  = logic.feed
  override def slot1Valid: Bool                 = logic.slot1ValidOut
}
