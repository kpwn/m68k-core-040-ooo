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

    // ---- State registers ----
    val decodePc      = Reg(UInt(32 bits)) init 0
    val fetchPc       = Reg(UInt(32 bits)) init 0   // 8-aligned
    spinal.core.sim.SimPublic(decodePc, fetchPc)
    val stalled       = Reg(Bool()) init False       // complex-instruction stall
    val started       = Reg(Bool()) init False       // don't fetch until first redirect
    val fetchInFlight = Reg(Bool()) init False       // single-outstanding guard

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
    ic.cmd.valid      := started && !fetchInFlight && ibuf.io.push.ready && !stalled
    ic.cmd.payload.pc := fetchPc

    when(ic.cmd.fire) {
      fetchInFlight := True
    }

    // ---- Enqueue: handle I-cache responses ----
    // Extract the 4 words from the 64-bit response data (LE: bits[15:0] = word 0 = lowest addr)
    val rspWords = ic.rsp.payload.data.subdivideIn(16 bits)
    val rspPreds = ic.rsp.payload.pred

    when(ic.rsp.valid) {
      fetchInFlight := False
      fetchPc       := fetchPc + 8

      when(!rspStale) {
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
      } otherwise {
        // Stale: discard response, clear stale flag
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

    // When feed fires: consume words from buffer and advance decodePc
    when(feed.fire) {
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
  }

  // ---- DecodeFeedService implementation ----
  override def feed: Stream[Vec[DecodePacket]]  = logic.feed
  override def slot1Valid: Bool                 = logic.slot1ValidOut
}
