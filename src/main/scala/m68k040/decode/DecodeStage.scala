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

    // ── MOVEM micro-sequencer FSM (the core's first multi-cycle-emit instruction) ──
    // MOVEM (`0100 1 d 001 s mmmrrr` + a 16-bit register-mask ext word) moves 0..16
    // registers — far beyond the 3-µop crack budget. The FSM HOLDS `fed`, latches the
    // mask/base/dir/size, and each cycle priority-extracts the LOWEST TWO set mask bits,
    // driving the queue push directly (pushProduced.uops(0..1), count 1 or 2) with plain
    // LOAD/STORE move µops — SIDESTEPPING the AssembledUops crack budget. At mask-empty it
    // emits ONE final `An := base ± count*size` µop (for `(An)+`/`-(An)`), then releases
    // `fed`. `pipeFlush` aborts (the queue flush squashes the partial µops; MOVEM re-decodes
    // from scratch on re-fetch). The µops carry NO flags + NO new EU (reuse the LS LOAD/
    // STORE shapes; `.W` load sign-extends via the isMovea LS-EU marker).
    //
    // The mask bit order is UNIFIED on lowest-bit-first extraction (matching Musashi's
    // `for(i=0;i<16;i++) if(mask&(1<<i))`): forward (control / `(An)+` / `(d16,PC)`) maps
    // bit i -> reg i with ASCENDING addresses (base + k*size); reverse `-(An)` maps bit i ->
    // reg 15-i with DESCENDING addresses (base - (k+1)*size), exactly the `re,pd` form.
    val movemActive   = RegInit(False)
    val movemMask     = Reg(Bits(16 bits))
    val movemBaseReg  = Reg(UInt(5 bits))      // base An reg id (valid iff movemBaseValid)
    val movemBaseValid= Reg(Bool())            // need an An base read (false for abs / PC-rel)
    val movemBaseDisp = Reg(Bits(32 bits))     // folded base disp (abs addr / PC-rel addr / d16; 0 for (An)+/-(An))
    val movemIsLoad   = Reg(Bool())
    val movemSizeLong = Reg(Bool())
    val movemRev      = Reg(Bool())            // -(An): reverse reg map + descending addresses
    val movemDoAnUpd  = Reg(Bool())            // the final An update moves An ((An)+ / -(An))
    // The MOVEM macro maps to ONE oracle step (lock-step): all the move µops are DROPPED
    // (divRem) and a final KEPT µop carries the macro commit. For An-base modes (2/3/4/5)
    // the kept µop is the final `An := An ± delta` (delta 0 for the control (An)/(d16,An)
    // modes — a harmless no-op An write that still commits the macro). For abs/PC modes
    // (no An base) there is no final µop -> the moves are NOT dropped and the LAST move is
    // the kept commit (abs/PC MOVEM is decode-tested only, never lock-stepped).
    val movemHasFinal = Reg(Bool())            // emit a final An-update µop (= baseValid)
    val movemAnReg    = Reg(UInt(5 bits))      // the An updated at the end
    val movemOff      = Reg(SInt(32 bits))     // running byte offset for the NEXT element's address
    val movemEmitted  = Reg(UInt(5 bits))      // elements emitted so far (for the final An delta count)
    val movemPc       = Reg(UInt(32 bits))
    val movemNextPc   = Reg(UInt(32 bits))
    val movemAnUpdPhase = RegInit(False)       // mask drained -> drive the single final An update
    when(pipeFlush) { movemActive := False; movemAnUpdPhase := False }

    val movemSizeBytes = Mux(movemSizeLong, S(4, 32 bits), S(2, 32 bits))
    val movemStep      = Mux(movemRev, -movemSizeBytes, movemSizeBytes)

    // Priority-extract the lowest two set mask bits (the `x & (x-1)` clears the lowest set
    // bit; done in UInt since Bits has no arithmetic).
    val movemMask0 = movemMask.asUInt
    val movemBit0  = OHToUInt(OHMasking.first(movemMask))                  // lowest set bit index
    val movemMask1 = movemMask0 & (movemMask0 - 1)                         // clear the lowest set bit
    val movemHas1  = movemMask1 =/= 0
    val movemBit1  = OHToUInt(OHMasking.first(movemMask1.asBits))
    val movemMask2 = (movemMask1 & (movemMask1 - 1)).asBits                // after clearing two bits
    // bit -> register: forward bit i -> reg i; reverse bit i -> reg 15-i.
    def movemRegOf(bit: UInt): UInt = Mux(movemRev, (U(15, 5 bits) - bit.resize(5)), bit.resize(5))
    val movemReg0 = movemRegOf(movemBit0)
    val movemReg1 = movemRegOf(movemBit1)
    // Element addresses: imm = baseDisp + runningOffset; the 2nd element steps by movemStep.
    val movemImm0 = (movemBaseDisp.asSInt + movemOff).asBits
    val movemImm1 = (movemBaseDisp.asSInt + movemOff + movemStep).asBits
    val movemNumThisCycle = Mux(movemHas1, U(2, 3 bits), U(1, 3 bits))     // 2, or 1 odd tail

    // The two move µops this cycle. movemFirst marks the macro boundary on the VERY FIRST
    // emitted move of the whole MOVEM (movemEmitted===0) only. The moves are DROPPED
    // (divRem) when a final kept An-update µop will carry the macro commit (An-base modes);
    // for abs/PC modes (no final µop) the moves are NOT dropped (the last move is the kept
    // commit). The 2nd-of-pair is never the macro's last µop when a final exists, so it is
    // always dropped alongside the first when movemHasFinal.
    val movemFirst0 = movemEmitted === 0
    val movemUop0 = MicroOpAssembler.movemMoveUop(
      reg = movemReg0, base = movemBaseReg, baseValid = movemBaseValid, disp = movemImm0,
      sizeLong = movemSizeLong, isLoad = movemIsLoad, first = movemFirst0, drop = movemHasFinal,
      valid = True, pc = movemPc, nextPc = movemNextPc)
    val movemUop1 = MicroOpAssembler.movemMoveUop(
      reg = movemReg1, base = movemBaseReg, baseValid = movemBaseValid, disp = movemImm1,
      sizeLong = movemSizeLong, isLoad = movemIsLoad, first = False, drop = movemHasFinal,
      valid = True, pc = movemPc, nextPc = movemNextPc)
    // The final An update (kept macro commit): An := An + emitted*step for (An)+/-(An)
    // (movemStep carries the sign), or An := An + 0 for the control (An)/(d16,An) modes
    // (a no-op An write that commits the macro + advances PC). emitted = the total count.
    val movemAnDelta = Mux(movemDoAnUpd, (movemEmitted.asSInt.resize(32) * movemStep).resize(32), S(0, 32 bits))
    val movemAnUop = MicroOpAssembler.movemAnUpdUop(
      an = movemAnReg, signedDelta = movemAnDelta,
      valid = True, pc = movemPc, nextPc = movemNextPc)

    // ── MOVEM entry detection (slot0 directly, or a slot1 MOVEM stashed as a packet) ──
    // OperationDecoder marks MOVEM via spec.movem; re-decode the head packet's opword to
    // read the marker + dir/size (cheap, EA-agnostic). A slot1 MOVEM cannot be emitted as
    // a normal crack, so when slot0 is non-MOVEM and slot1 IS a MOVEM, slot0 emits this
    // cycle and the slot1 PACKET (opword+mask+pc+lenWords) is stashed; the FSM enters from
    // it next cycle (mirrors the 3-µop slot1 defer, but carries the raw packet).
    val spec0  = OperationDecoder.decode(fed.payload.packets(0).words(0))
    val spec1  = OperationDecoder.decode(fed.payload.packets(1).words(0))
    val slot0IsMovem = fed.valid && spec0.movem
    val slot1IsMovem = fed.valid && fed.payload.slot1Valid && spec1.movem
    val movemPendValid = RegInit(False)
    val movemPendPkt   = Reg(DecodePacket())
    when(pipeFlush) { movemPendValid := False }

    // The source packet the FSM enters from: the stashed slot1 MOVEM, else slot0.
    val movemEntryPkt = Mux(movemPendValid, movemPendPkt, fed.payload.packets(0))
    // Begin a MOVEM: there's a MOVEM to start (a pending slot1 one, or slot0 is MOVEM and
    // not blocked by a stash/replay) and the FSM is idle.
    val movemBegin = !movemActive && (movemPendValid || (slot0IsMovem && !stashValid))

    // Decode the entry packet's EA + mask. The mask is words(1); a (d16,An)/(xxx)/(d16,PC)
    // EA extension word sits at words(2) (after the mask). PC-rel: EA_PCDI = (pc+4) + d16
    // (the disp word is at pc+4: opword@+0, mask@+2, disp@+4).
    val eopw    = movemEntryPkt.words(0)
    val eMode   = eopw(5 downto 3)
    val eReg    = eopw(2 downto 0)
    val eMask   = movemEntryPkt.words(1)
    val eDisp16 = movemEntryPkt.words(2).asSInt.resize(32).asBits
    val eDisp32 = movemEntryPkt.words(2) ## movemEntryPkt.words(3)
    val ePc     = movemEntryPkt.pc
    val eNextPc = (ePc + (movemEntryPkt.lenWords << 1)).resize(32)
    val ePcRel  = (ePc + U(4, 32 bits) + eDisp16.asUInt).asBits
    val eAnReg  = (U(8, 5 bits) + eReg.asUInt).resized
    val eIsPostinc = eMode === B"3'b011"
    val eIsPredec  = eMode === B"3'b100"
    val eBaseValidV = Bits(1 bits); val eBaseRegV = UInt(5 bits)
    val eBaseDispV  = Bits(32 bits)
    eBaseValidV := B"0"; eBaseRegV := 0; eBaseDispV := 0
    switch(eMode) {
      is(B"3'b010", B"3'b011", B"3'b100") { eBaseValidV := B"1"; eBaseRegV := eAnReg; eBaseDispV := 0 }      // (An)/(An)+/-(An)
      is(B"3'b101")                       { eBaseValidV := B"1"; eBaseRegV := eAnReg; eBaseDispV := eDisp16 } // (d16,An)
      is(B"3'b111") {
        switch(eReg) {
          is(B"3'b000") { eBaseValidV := B"0"; eBaseDispV := eDisp16 }   // (xxx).W
          is(B"3'b001") { eBaseValidV := B"0"; eBaseDispV := eDisp32 }   // (xxx).L
          is(B"3'b010") { eBaseValidV := B"0"; eBaseDispV := ePcRel }    // (d16,PC)
        }
      }
    }
    // Entry running-offset init: forward starts at 0, predec at -size (first element -> base-size).
    val eSizeBytes = Mux(eopw(6), S(4, 32 bits), S(2, 32 bits))
    val eMaskEmpty = eMask === 0

    // ── Normal (non-MOVEM) push production ──────────────────────────────────────
    def normUop(i: Int): DecodedUop = i match {
      case 0 => headUop(0)
      case 1 => Mux(nCur >= U(2), headUop(1), a1raw.uops(0))
      case 2 => Mux(nCur === U(3), headUop(2), Mux(nCur === U(2), a1raw.uops(0), a1raw.uops(1)))
      case 3 => a1raw.uops(1)
    }
    // The NORMAL head pushes when: a stash is being replayed (the head is the stash,
    // regardless of what slot0 in the HELD next group is — even a MOVEM that waits), OR a
    // fresh slot0 that is NOT a MOVEM and no slot1 MOVEM is pending. A slot0 MOVEM (when not
    // replaying a stash) is owned by the FSM -> the normal head does not push it.
    val normalHeadValid = stashValid || (fed.valid && !slot0IsMovem && !movemPendValid)

    // Produce the push as a Stream. When the MOVEM FSM is active it OVERRIDES the source
    // (its 2 moves / the final An update); otherwise the normal crack drives it.
    val pushProduced = Stream(PushPayload())
    when(movemActive) {
      pushProduced.valid           := True
      when(movemAnUpdPhase) {
        pushProduced.payload.uops(0) := movemAnUop
        pushProduced.payload.uops(1) := movemAnUop
        pushProduced.payload.count   := U(1, 3 bits)
      } otherwise {
        pushProduced.payload.uops(0) := movemUop0
        pushProduced.payload.uops(1) := movemUop1
        pushProduced.payload.count   := movemNumThisCycle
      }
      pushProduced.payload.uops(2) := movemAnUop      // unused (count <= 2)
      pushProduced.payload.uops(3) := movemAnUop
    } otherwise {
      pushProduced.valid           := normalHeadValid
      pushProduced.payload.uops(0) := normUop(0)
      pushProduced.payload.uops(1) := normUop(1)
      pushProduced.payload.uops(2) := normUop(2)
      pushProduced.payload.uops(3) := normUop(3)
      pushProduced.payload.count   := totalCount
    }

    // P1: register the produced push. The deep `assemble` cone ends at pushReg's input;
    // the ring write in N+1 is a shallow, register-driven broadcast. Flushed by the SAME
    // pipeFlush that squashes `fed` and the queue, so a held wrong-path group is discarded.
    val pushReg = PipeStage(pushProduced, pipeFlush)
    queue.io.push.valid := pushReg.valid
    queue.io.push.count := pushReg.payload.count
    queue.io.push.uops  := pushReg.payload.uops
    pushReg.ready       := queue.io.push.ready

    // ── fed.ready + stash/MOVEM consume/advance (when the produced group is accepted) ──
    // Normal: consume `fed` when the produced group enters pushReg (not while replaying a
    // stash, not while the MOVEM FSM owns the slot). MOVEM: a slot0 MOVEM is consumed on the
    // ENTRY cycle (movemBegin from slot0) — all its state is latched into regs that cycle, so
    // the packet is done; its slot1 (a normal instr OR another MOVEM) is STASHED and replayed
    // after the FSM finishes. A slot1 MOVEM is entered from the stashed PACKET (`fed` was
    // already consumed when the slot1 was stashed); while the FSM runs `fed` holds the
    // FOLLOWING group, NOT consumed until the FSM finishes (movemHoldsFed).
    val movemEnterSlot0 = movemBegin && !movemPendValid                  // entering a slot0 MOVEM (not a pending one)
    val movemHoldsFed   = movemActive || movemPendValid                 // FSM busy -> hold the next group in `fed`
    fed.ready := (!stashValid && !movemHoldsFed && !slot0IsMovem && pushProduced.ready) || movemEnterSlot0
    when(!movemActive && !movemBegin && pushProduced.ready) {
      when(stashValid) {
        stashValid := False                  // the stashed slot1/RTR was emitted (into pushReg) this cycle
      } elsewhen(slot1IsMovem) {
        // slot0 (non-MOVEM) emitted this cycle; stash the slot1 MOVEM packet, consume fed.
        movemPendValid := True
        movemPendPkt   := fed.payload.packets(1)
      } elsewhen(deferSlot1 && fed.valid) {
        stashValid  := True                  // defer slot1 to next cycle (3-µop slot0 or slot1)
        stashCount  := a1raw.count
        for (i <- 0 until 3) { stashUops(i) := a1raw.uops(i) }
      }
    }
    // ── MOVEM FSM transitions ───────────────────────────────────────────────────
    when(movemBegin) {
      // Latch all MOVEM state; start emitting next cycle from these registers.
      movemActive   := True
      movemAnUpdPhase := False
      movemMask     := eMask
      movemBaseReg  := eBaseRegV
      movemBaseValid:= eBaseValidV(0)
      movemBaseDisp := eBaseDispV
      movemIsLoad   := eopw(10)
      movemSizeLong := eopw(6)
      movemRev      := eIsPredec
      movemDoAnUpd  := eIsPostinc || eIsPredec     // the final An update MOVES An (postinc/predec)
      movemHasFinal := eBaseValidV(0)              // emit a final kept µop for every An-base mode
      movemAnReg    := eAnReg
      movemOff      := Mux(eIsPredec, -eSizeBytes, S(0, 32 bits))
      movemEmitted  := 0
      movemPc       := ePc
      movemNextPc   := eNextPc
      // A pending slot1 MOVEM is now being consumed by this entry.
      when(movemPendValid) { movemPendValid := False }
      // Entering a slot0 MOVEM: STASH its slot1 (if any) so it is not lost when `fed` is
      // consumed this cycle. A slot1 MOVEM -> packet stash (movemPendValid); a normal
      // slot1 -> decoded-µop stash (stashValid). Replayed after the FSM finishes.
      when(movemEnterSlot0 && fed.payload.slot1Valid) {
        when(slot1IsMovem) {
          movemPendValid := True
          movemPendPkt   := fed.payload.packets(1)
        } otherwise {
          stashValid  := True
          stashCount  := a1raw.count
          for (i <- 0 until 3) { stashUops(i) := a1raw.uops(i) }
        }
      }
      // Empty mask: no moves (count 0 -> An unchanged); finish immediately (but the slot1
      // stash above still stands, so the following instruction replays next cycle).
      when(eMaskEmpty) {
        movemActive  := False
        // (no An update: count 0 -> An ± 0 unchanged, matching Musashi)
      }
    } elsewhen(movemActive) {
      when(movemAnUpdPhase) {
        // The single final An update is emitted; finish when the queue accepts it.
        when(pushProduced.ready) {
          movemActive     := False
          movemAnUpdPhase := False
        }
      } otherwise {
        when(pushProduced.ready) {
          movemMask    := movemMask2                                  // drop the 1-2 emitted bits
          movemOff     := (movemOff + movemStep * movemNumThisCycle.asSInt.resize(32)).resize(32)
          movemEmitted := movemEmitted + movemNumThisCycle.resize(5)
          // Mask drained after this cycle's extraction? (2 emitted -> movemMask2 / movemMask1
          // both 0 if <=2 bits; 1 emitted (odd tail) -> movemMask1 is the post-clear mask.)
          val remaining = Mux(movemHas1, movemMask2.asUInt, movemMask1)
          when(remaining === 0) {
            when(movemHasFinal) { movemAnUpdPhase := True }           // emit the final kept An-update next
              .otherwise        { movemActive := False }              // abs/PC: last move is the kept commit; done
          }
        }
      }
    }
    // pipeFlush ABORTS the FSM (LAST word, so a flush coinciding with movemBegin still
    // resets it): the partially-emitted µops are squashed by the queue flush; MOVEM
    // re-decodes from scratch on re-fetch.
    when(pipeFlush) {
      movemActive     := False
      movemAnUpdPhase := False
      movemPendValid  := False
    }

    // ── Rename-facing output ───────────────────────────────────────────────────
    val uopsOut = queue.io.pop
    val uop1Sig = queue.io.pop1Valid
  }

  override def uops: Stream[Vec[DecodedUop]] = logic.uopsOut
  override def uop1Valid: Bool               = logic.uop1Sig
}
