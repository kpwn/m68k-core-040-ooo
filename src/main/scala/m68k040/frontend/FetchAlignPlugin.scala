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
    val fetchInFlight = Reg(Bool()) init False       // single-outstanding guard
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

    // Track whether the next rsp should drop leading words (set on redirect/resume)
    val dropPending = Reg(Bool()) init False
    val dropCount   = Reg(UInt(2 bits)) init 0      // words to drop (0..3)

    // Track whether the in-flight fetch is stale (redirect/resume happened after cmd fired)
    val rspStale = Reg(Bool()) init False

    // ---- Default-drive IBuf inputs ----
    ibuf.io.push.valid   := False
    ibuf.io.push.payload.words.foreach(_ := 0)
    ibuf.io.push.payload.preds.foreach { p => p.simple := False; p.lenWords := 0 }
    ibuf.io.push.payload.n := 0
    ibuf.io.shift        := 0
    ibuf.io.flush        := False

    // ---- FetchControl: issue fetches (single-outstanding) ----
    // Suppress fetching while holding an I-fetch fault (wait for the redirect).
    ic.cmd.valid      := started && !fetchInFlight && ibuf.io.push.ready && !stalled && !faultHold
    ic.cmd.payload.pc := fetchPc

    when(ic.cmd.fire) {
      fetchInFlight := True
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
    val rspFault = ic.rsp.valid && ic.rsp.payload.fault && !rspStale && !faultHold
    when(rspFault) {
      faultHold := True
      faultPc   := ic.rsp.payload.pc
    }

    when(ic.rsp.valid) {
      fetchInFlight := False
      fetchPc       := fetchPc + 8

      when(!rspStale && !ic.rsp.payload.fault) {
        // Determine starting word index within this window
        val startWord = UInt(2 bits)
        when(dropPending) {
          startWord   := dropCount
          dropPending := False
        } otherwise {
          startWord := U(0, 2 bits)
        }

        // n = 4 - startWord words to enqueue
        val nWords = U(4, 3 bits) - startWord.resize(3)

        // Build push payload: map incoming window[startWord..3] -> push[0..n-1]
        for (j <- 0 until 4) {
          when(U(j) < nWords) {
            // srcIdx = startWord + j, always in 0..3; resize(2) is safe since guard ensures range
            val srcIdx = (startWord + U(j, 2 bits)).resize(2)
            ibuf.io.push.payload.words(j) := rspWords(srcIdx)
            ibuf.io.push.payload.preds(j).simple   := rspPreds(srcIdx).simple
            ibuf.io.push.payload.preds(j).lenWords := rspPreds(srcIdx).lenWords
          }
        }
        ibuf.io.push.payload.n := nWords
        ibuf.io.push.valid     := True
      } elsewhen(rspStale) {
        // Stale: discard response, clear stale flag (a fault response is handled
        // above via faultHold; it pushes nothing either).
        rspStale := False
      }
    }

    // ---- Aligner: combinational decode of buffer head ----
    val res = Aligner.align(decodePc, ibuf.io.head, ibuf.io.headPred, ibuf.io.avail)

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
      // Drop leading words on the next rsp: startWord = newPc[2:1] (word index within 8-byte window)
      dropPending    := True
      dropCount      := newPc(2 downto 1)
      // Mark any in-flight rsp as stale
      when(fetchInFlight) {
        rspStale      := True
        fetchInFlight := False
      }
    }

    // ---- resume (lower priority than redirect, active when stalled) ----
    when(resume.valid && stalled) {
      val newPc   = resume.payload
      decodePc    := newPc
      fetchPc     := newPc(31 downto 3) @@ U(0, 3 bits)
      ibuf.io.flush  := True
      stalled        := False
      dropPending    := True
      dropCount      := newPc(2 downto 1)
      when(fetchInFlight) {
        rspStale      := True
        fetchInFlight := False
      }
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
      dropPending    := True
      dropCount      := newPc(2 downto 1)
      when(fetchInFlight) {
        rspStale      := True
        fetchInFlight := False
      }
    }
  }

  // ---- DecodeFeedService implementation ----
  override def feed: Stream[Vec[DecodePacket]]  = logic.feed
  override def slot1Valid: Bool                 = logic.slot1ValidOut
}
