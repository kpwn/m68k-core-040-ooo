package m68k040.decode

import m68k040.frontend.{DecodePacket, PipeStage}
import m68k040.services.{DecodeFeedService, DecodeUopService}
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** DecodeStage: 2-wide decode plugin with µop expansion.
  *
  * Hosts two cracking MicroOpAssembler decodes (one per slot), each producing a
  * 1–2 µop sequence. The two sequences are packed in program order into the
  * MicroOpQueue (up to 4 µops/cycle in), which drains a steady 2/cycle to rename
  * (DecodeUopService.uops). The queue absorbs the variable-rate expansion and is
  * flushed on a mispredict (pipeFlush). The decode→rename skid that the previous
  * 1:1 passthrough provided is now the queue itself.
  *
  * FRONTEND-FMAX SKID: the FetchAlign→DecodeStage boundary is registered by a
  * 1-deep `PipeStage`. This SPLITS the standing route-dominated critical arc
  * `ibuf.count → Aligner predicated-length/branchDisp select → MicroOpQueue ring
  * write-enable` into two shorter halves: (1) `ibuf.count → Aligner → fedStage
  * register`, and (2) `fedStage register → MicroOpAssembler → queue ring`. The
  * decode is LATENCY-AGNOSTIC (whitebox lock-step joins by robId, the MicroOpQueue
  * already buffers), so the extra frontend cycle changes no architectural result.
  * The skid is flushed by the same `pipeFlush` as the queue so a wrong-path group
  * held in the register is squashed on a mispredict/exception redirect.
  */
class DecodeStage extends FiberPlugin with DecodeUopService {

  // Combined skid payload: the 2 DecodePackets plus the slot1-valid flag, carried
  // together through the registered FetchAlign→DecodeStage boundary so the second
  // slot's validity stays aligned with its packet across the pipeline register.
  case class FedPacket() extends Bundle {
    val packets    = Vec(DecodePacket(), 2)
    val slot1Valid = Bool()
  }

  // Push payload registered between the decode/pack output and the MicroOpQueue write
  // (P1 timing skid): the 4 packed µops + their count. Carried through a 1-deep PipeStage
  // so the deep MicroOpAssembler decode cone ends at a register and the ring write
  // consumes only registered signals (kills the decode-cone half of the push critical arc).
  case class PushPayload() extends Bundle {
    val uops  = Vec(DecodedUop(), 4)
    val count = UInt(3 bits)
  }

  val logic = during build new Area {
    val df = host[DecodeFeedService]

    // ── µop expansion queue ──────────────────────────────────────────────────
    val queue = new MicroOpQueue(depth = 16)

    // Flush: default-driven False (allowOverride) so a sibling wiring plugin can
    // OVERRIDE it from RedirectService.doFlush (full core). Driving it from
    // host[RedirectService] here would create a Fiber build-order cycle. The SAME
    // flush squashes both the MicroOpQueue and the FetchAlign→decode skid below.
    val pipeFlush = Bool(); pipeFlush.allowOverride; pipeFlush := False
    queue.io.flush := pipeFlush

    // ── Registered FetchAlign→DecodeStage boundary (timing skid) ────────────────
    // Pack the feed payload + slot1Valid into one stream, register it (PipeStage),
    // then decode the REGISTERED packets. This splits the standing route-dominated
    // arc (ibuf.count → Aligner → MicroOpQueue ring) at this boundary. pipeFlush
    // squashes a held wrong-path group (same broadcast that flushes the queue).
    val fedIn = Stream(FedPacket())
    fedIn.valid              := df.feed.valid
    fedIn.payload.packets(0) := df.feed.payload(0)
    fedIn.payload.packets(1) := df.feed.payload(1)
    fedIn.payload.slot1Valid := df.slot1Valid
    df.feed.ready := fedIn.ready

    val fed = PipeStage(fedIn, pipeFlush)

    // ── 3-µop-instruction handling (RTR / mem-dest RMW) ─────────────────────────
    // A single instruction can crack to 3 µops; the 4-wide MicroOpQueue push then
    // cannot always hold slot0+slot1 (e.g. a 2-µop slot0 + a 3-µop slot1 = 5). We
    // serialize: decode ONE instruction per cycle whenever the group contains a 3-µop
    // crack. A 3-µop SLOT0 (RTR / mem-dest RMW [load,op,store]) emits its 3 µops this
    // cycle; slot1 (the NEXT instruction) is STASHED and replayed next cycle so it is
    // NOT lost. (RTR/RTS redirect -> a stashed wrong-path slot1 is harmlessly squashed
    // by pipeFlush; a mem-dest RMW does NOT redirect, so its slot1 is real and MUST be
    // preserved.) A 3-µop SLOT1 (after a <=2-µop slot0) is likewise stashed + replayed.
    val a0 = MicroOpAssembler.assemble(fed.payload.packets(0))
    val a1raw = MicroOpAssembler.assemble(fed.payload.packets(1))

    // Stash for a deferred slot1. FMax: stash the ALREADY-DECODED slot1 µops (computed
    // from a1raw, no second MicroOpAssembler instance) — the replay cycle reads these
    // REGISTERS instead of re-assembling, keeping the decode-crack cone off the queue-
    // push critical arc. Carries the slot1 µop COUNT (1..3) so a replayed slot1 of any
    // length is emitted correctly.
    val stashValid = RegInit(False)
    val stashUops  = Reg(Vec(DecodedUop(), 3))
    val stashCount = Reg(UInt(2 bits))
    when(pipeFlush) { stashValid := False }

    val slot1Is3 = a1raw.count === U(3, 2 bits)
    val slot0Is3 = a0.count === U(3, 2 bits)

    // slot1 is emitted alongside slot0 only when: not replaying a stash, slot1 present,
    // slot0 is NOT 3-µop, and slot1 itself is NOT 3-µop (a 3-µop slot1 is deferred).
    val slot1Emit = !stashValid && fed.valid && fed.payload.slot1Valid && !slot0Is3 && !slot1Is3
    // Defer slot1 to the stash when EITHER slot0 is a 3-µop crack (slot1 cannot fit
    // alongside 3 µops) OR slot1 itself is a 3-µop crack (cannot fit after a <=2-µop
    // slot0). In both cases emit slot0 this cycle + stash slot1's µops; replay next.
    val deferSlot1 = !stashValid && fed.valid && fed.payload.slot1Valid && (slot0Is3 || slot1Is3)

    // Head instruction this cycle: the stashed slot1 (registered uops) else slot0 (a0).
    val nCur = Mux(stashValid, stashCount, a0.count)       // 1..3
    val n1   = Mux(slot1Emit, a1raw.count, U(0, 2 bits))   // slot1 µops (0 if not emitted)

    // Pack: positions 0..2 = head instruction's µops; the slot1 µops follow at nCur..
    // (only when slot1Emit, where nCur<=2 and n1<=2, so max position 3).
    def headUop(i: Int): DecodedUop = Mux(stashValid, stashUops(i), a0.uops(i))

    val totalCount = (nCur +^ n1).resize(3)            // 1..4

    // Produce the push as a Stream (the deep assemble/pack cone drives this).
    val pushProduced = Stream(PushPayload())
    // Push when there is a head instruction: either a stashed RTR or a valid fed group.
    pushProduced.valid           := stashValid || fed.valid
    pushProduced.payload.uops(0) := headUop(0)
    pushProduced.payload.uops(1) := Mux(nCur >= U(2), headUop(1), a1raw.uops(0))
    pushProduced.payload.uops(2) := Mux(nCur === U(3), headUop(2), Mux(nCur === U(2), a1raw.uops(0), a1raw.uops(1)))
    pushProduced.payload.uops(3) := a1raw.uops(1)
    pushProduced.payload.count   := totalCount

    // P1: register the produced push. The deep `assemble` cone ends at pushReg's input;
    // the ring write in N+1 is a shallow, register-driven broadcast. Flushed by the SAME
    // pipeFlush that squashes `fed` and the queue, so a held wrong-path group is discarded.
    val pushReg = PipeStage(pushProduced, pipeFlush)
    queue.io.push.valid := pushReg.valid
    queue.io.push.count := pushReg.payload.count
    queue.io.push.uops  := pushReg.payload.uops
    pushReg.ready       := queue.io.push.ready

    // Consume the fed group / advance the stash when the PRODUCED group enters pushReg.
    // The "group accepted" signal is now the register's input-ready (pushProduced.ready),
    // not queue.io.push.ready directly. When deferring slot1, we still consume the group
    // THIS cycle (slot0 emitted into pushReg) and register the decoded slot1 µops; the
    // stash replays them next cycle.
    fed.ready := !stashValid && pushProduced.ready
    when(pushProduced.ready) {
      when(stashValid) {
        stashValid := False                  // the stashed slot1/RTR was emitted (into pushReg) this cycle
      } elsewhen(deferSlot1 && fed.valid) {
        stashValid  := True                  // defer slot1 to next cycle (3-µop slot0 or slot1)
        stashCount  := a1raw.count
        for (i <- 0 until 3) { stashUops(i) := a1raw.uops(i) }
      }
    }

    // ── Rename-facing output ───────────────────────────────────────────────────
    val uopsOut = queue.io.pop
    val uop1Sig = queue.io.pop1Valid
  }

  override def uops: Stream[Vec[DecodedUop]] = logic.uopsOut
  override def uop1Valid: Bool               = logic.uop1Sig
}
