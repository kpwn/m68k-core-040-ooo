package m68k040.frontend

import m68k040.cache.{FetchCmd, FetchRsp}
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

    // ---- State registers ----
    val decodePc      = Reg(UInt(32 bits)) init 0
    val fetchPc       = Reg(UInt(32 bits)) init 0   // 8-aligned
    spinal.core.sim.SimPublic(decodePc, fetchPc)
    val stalled       = Reg(Bool()) init False       // complex-instruction stall
    val started       = Reg(Bool()) init False       // don't fetch until first redirect
    // I-fetch fault hold: a translation fault (ITLB non-resident / protect) on a
    // fetch response. We emit ONE faulted DecodePacket (slot0.fault, pc = the faulting
    // fetch PC) and STOP fetching until a redirect (the exception delivers + vectors).
    // Without this hold the front-end would keep fetching the bad page (a flood of
    // faulted uops that starves the commit-side exception sequencer).
    val faultHold     = Reg(Bool()) init False
    val faultEmitted  = Reg(Bool()) init False   // the faulted packet was already emitted
    val faultPc       = Reg(UInt(32 bits)) init 0

    // Complex emit-once is enforced by the `stalled` latch (no separate delay reg):
    // a complex packet has shiftWords=0, so on its fire only `stalled` advances,
    // gating feed.valid until `resume` clears it.

    // Per-fetch outstanding-record tracking (Slice 1: ONE record = single-outstanding;
    // Slice 2 generalizes {recValid,recStale,recDrop} to a depth-N ring once the I-cache
    // is pipelined). The in-flight fetch carries its OWN stale + leading-drop, captured at
    // ISSUE — immune to later redirects overwriting global state (the bug that wedged
    // nested-bsr-during-miss: a stale window mis-attributed to a redirect target).
    val recValid = Reg(Bool()) init False   // a fetch is outstanding (replaces fetchInFlight)
    val recStale = Reg(Bool()) init False   // a redirect happened after it issued -> discard its rsp
    val recDrop  = Reg(UInt(2 bits)) init 0 // leading words to drop on its rsp (target[2:1])
    // Leading-word drop intent for the NEXT fetch to issue (set by a redirect to
    // target[2:1]; latched into recDrop at issue, then cleared).
    val pendingDrop = Reg(UInt(2 bits)) init 0
    spinal.core.sim.SimPublic(recValid, recStale, recDrop, pendingDrop)

    // ---- Default-drive IBuf inputs ----
    ibuf.io.push.valid   := False
    ibuf.io.push.payload.words.foreach(_ := 0)
    ibuf.io.push.payload.preds.foreach { p => p.simple := False; p.lenWords := 0 }
    ibuf.io.push.payload.n := 0
    ibuf.io.shift        := 0
    ibuf.io.flush        := False

    // Any fetch-redirect this cycle (external redirect, complex-resume, or commit
    // mispredict). A fetch issued THIS cycle used the pre-redirect fetchPc -> born stale.
    // (mispredictRedirect/redirect/resume are all declared above; resume only when stalled.)
    val redirectThisCycle = redirect.valid || (resume.valid && stalled) || mispredictRedirect.valid

    // ---- FetchControl: issue fetches (single-outstanding) ----
    // Suppress fetching while holding an I-fetch fault (wait for the redirect).
    ic.cmd.valid      := started && !recValid && ibuf.io.push.ready && !stalled && !faultHold
    ic.cmd.payload.pc := fetchPc

    when(ic.cmd.fire) {
      // Capture this fetch's record. Born stale iff a redirect fires THIS cycle (this
      // fetch used the old, now-wrong fetchPc). Latch the drop intent; consume it.
      recValid := True
      recStale := redirectThisCycle
      recDrop  := pendingDrop
      pendingDrop := 0
    }

    // ---- Enqueue: handle I-cache responses ----
    // Extract the 4 words from the 64-bit response data (LE: bits[15:0] = word 0 = lowest addr)
    val rspWords = ic.rsp.payload.data.subdivideIn(16 bits)
    val rspPreds = ic.rsp.payload.pred

    // A fault response (not stale): latch the fault + the faulting fetch PC and stop
    // fetching. The faulted packet is emitted on the feed below; do NOT enqueue words.
    // Capture the FIRST fault only (gate on !faultHold): a fault response one cycle
    // before the hold engages can be followed by a second in-flight fault response for
    // the next window; the EA must be the FIRST faulting address, not the later one.
    val rspFault = ic.rsp.valid && ic.rsp.payload.fault && !recStale && !faultHold
    when(rspFault) {
      faultHold := True
      faultPc   := ic.rsp.payload.pc
    }

    when(ic.rsp.valid) {
      recValid := False          // the outstanding fetch's response arrived (record consumed)
      // Advance the sequential fetch pointer ONLY for a live (non-stale) response. A
      // STALE response belongs to a fetch issued before a redirect; the redirect already
      // reset fetchPc to the new target window, so the next fetch must use THAT target —
      // advancing here would walk fetchPc off the target (B+8 instead of B), enqueuing a
      // window that the aligner cannot match against decodePc (lenWords=0 self-redirect).
      // (Old global tracking advanced unconditionally; it relied on the redirect-cycle
      // override and broke once the stale rsp arrived a cycle after the redirect.)
      when(!recStale) {
        fetchPc := fetchPc + 8
      }

      when(!recStale && !ic.rsp.payload.fault) {
        // Leading-word drop carried by THIS fetch's record.
        val startWord = recDrop
        val nWords    = U(4, 3 bits) - startWord.resize(3)
        for (j <- 0 until 4) {
          when(U(j) < nWords) {
            val srcIdx = (startWord + U(j, 2 bits)).resize(2)
            ibuf.io.push.payload.words(j)          := rspWords(srcIdx)
            ibuf.io.push.payload.preds(j).simple   := rspPreds(srcIdx).simple
            ibuf.io.push.payload.preds(j).lenWords := rspPreds(srcIdx).lenWords
          }
        }
        ibuf.io.push.payload.n := nWords
        ibuf.io.push.valid     := True
      }
      // stale OR fault: enqueue nothing (discard), as before.
    }

    // ---- Aligner: combinational decode of buffer head ----
    val res = Aligner.align(decodePc, ibuf.io.head, ibuf.io.headPred, ibuf.io.avail)
    spinal.core.sim.SimPublic(ibuf.io.headPred(0).simple, ibuf.io.head(0))

    // ---- Feed valid logic ----
    // Emit when a packet is at head and not stalled. Complex packets emit once:
    // the cycle after they fire, the stalled latch suppresses re-emission.
    feed.payload(0) := res.slot0
    feed.payload(1) := res.slot1
    slot1ValidOut   := res.slot1Valid
    feed.valid      := res.slot0Valid && !stalled
    // I-fetch fault: override the feed with a single faulted DecodePacket (slot0
    // only), emitted EXACTLY ONCE (faultEmitted suppresses re-emission). Its bytes are
    // don't-care; decode turns it into a faulted vector-2 µop that delivers at retire.
    when(faultHold) {
      feed.valid             := !faultEmitted
      slot1ValidOut          := False
      feed.payload(0).valid     := True
      feed.payload(0).fault     := True
      // EA / faulting-instruction PC = the architectural fetch PC (decodePc, = the
      // branch/redirect target), NOT the 8-byte I-cache fetch window (which can be one
      // window ahead of the faulting instruction).
      feed.payload(0).pc        := decodePc
      feed.payload(0).simple    := True
      feed.payload(0).complex   := False
      feed.payload(0).wordCount := 1
      feed.payload(0).lenWords  := 1
      feed.payload(0).words.foreach(_ := 0)
      feed.payload(1).valid     := False
      feed.payload(1).fault     := False
    }
    // Emit-once: latch faultEmitted when the faulted packet fires.
    when(faultHold && feed.fire) { faultEmitted := True }

    // When feed fires (normal, NOT the faulted-packet override): consume words from
    // the buffer and advance decodePc. A faulted feed shifts nothing (its bytes came
    // from faultPc, not the IBuf).
    when(feed.fire && !faultHold) {
      ibuf.io.shift := res.shiftWords
      decodePc      := decodePc + (res.shiftWords.resize(32) |<< 1)
      when(res.complex) {
        // Complex instruction: stall after emitting — wait for resume
        stalled := True
      }
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
      when(recValid) { recStale := True }
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
      when(recValid) { recStale := True }
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
      when(recValid) { recStale := True }
    }
  }

  // ---- DecodeFeedService implementation ----
  override def feed: Stream[Vec[DecodePacket]]  = logic.feed
  override def slot1Valid: Bool                 = logic.slot1ValidOut
}
