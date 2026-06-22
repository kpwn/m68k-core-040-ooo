package m68k040.decode

import m68k040.frontend.{DecodePacket, PipeStage}
import m68k040.services.{DecodeFeedService, DecodeUopService}
import m68k040.isa.Size
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
    // FRONTEND-FMAX ANGLE E + LEVER 2 (predecode-offload): the per-slot decode Offload
    // (OpSpec + the two primary EaDecoder EAs srcEa/dstEa), computed on the PRE-register
    // (aligner-output) opword/words and carried through this register. The OperationDecoder
    // masked-pattern table AND the EaDecoder size-mux/mode-switch (which feeds the op-µop
    // `imm` via srcEa.imm — regen1's residual `fed_payload -> pushReg.uops_*_imm` limiter)
    // are the dominant combinational cone halves on the post-register critical arc; both are
    // PURE functions of the opword+words (available a full cycle pre-register), so evaluating
    // them on the input side of the FetchAlign->Decode register rebalances the two halves and
    // shrinks the decode+imm cone. Byte-identical (same functions, one stage earlier). 2
    // instances (per slot), NOT per-IBuf-entry -> the IBuf shift mux is unchanged (no IBuf bloat).
    val specs      = Vec(MicroOpAssembler.Offload(), 2)
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
    // ANGLE E + LEVER 2 offload: compute the per-slot Offload (OpSpec + srcEa/dstEa EAs)
    // on the PRE-register packet (the aligner output, available a full cycle before `fed`).
    // The result is registered alongside the packets so the post-register decode+imm cone
    // collapses to a select. Pure-function of the opword+words -> byte-identical to decoding
    // the registered packet in `assemble`.
    fedIn.payload.specs(0)   := MicroOpAssembler.computeOffload(df.feed.payload(0))
    fedIn.payload.specs(1)   := MicroOpAssembler.computeOffload(df.feed.payload(1))
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
    // ANGLE E: pass the REGISTERED (offloaded) per-slot OpSpec into the assembler so the
    // deep masked-pattern table is NOT re-evaluated on the post-register critical arc.
    val a0 = MicroOpAssembler.assemble(fed.payload.packets(0), fed.payload.specs(0))
    val a1raw = MicroOpAssembler.assemble(fed.payload.packets(1), fed.payload.specs(1))

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

    // A slot1 MOVEM cannot be emitted as a normal crack — its real µops come from the
    // micro-sequencer FSM (entered next cycle from the stashed packet). It MUST be
    // excluded from the normal slot1 push, otherwise the assembler's benign MOVE
    // PLACEHOLDER crack of the MOVEM opword (OperationDecoder marks MOVEM as DecOp.MOVE)
    // is pushed as a SPURIOUS extra kept µop alongside slot0 — the phantom commit.
    // (`slot1IsMovem` below reuses this; the OperationDecoder cone is shared via CSE.)
    // ONE shared OperationDecoder cone on the slot1 opword (FMax: a single decode feeds
    // both the MOVEM and the microcoded slot1 markers — they cannot be emitted as a normal
    // crack; their real µops come from the FSM / µcode sequencer entered from the stashed
    // packet, so both MUST be excluded from the normal slot1 push to avoid a phantom commit).
    val slot1Spec0        = fed.payload.specs(1).spec   // ANGLE E: registered offloaded spec
    val slot1IsMovemEarly = fed.valid && fed.payload.slot1Valid && slot1Spec0.movem
    val slot1IsUcodeEarly = fed.valid && fed.payload.slot1Valid && slot1Spec0.microcoded
    // A slot1 MOVEP, like a slot1 MOVEM, cannot be emitted as a normal crack — its real
    // µops come from the MOVEP FSM (entered next cycle from the stashed packet). Exclude
    // it from the normal slot1 push (the assembler's benign MOVE placeholder would
    // otherwise be a phantom commit).
    val slot1IsMovepEarly = fed.valid && fed.payload.slot1Valid && slot1Spec0.movep

    // slot1 is emitted alongside slot0 only when: not replaying a stash, slot1 present,
    // slot0 is NOT 3-µop, slot1 itself is NOT 3-µop (a 3-µop slot1 is deferred), and
    // slot1 is NOT a MOVEM/MOVEP (the FSM owns it — see slot1IsMovemEarly/slot1IsMovepEarly).
    val slot1Emit = !stashValid && fed.valid && fed.payload.slot1Valid && !slot0Is3 && !slot1Is3 && !slot1IsMovemEarly && !slot1IsUcodeEarly && !slot1IsMovepEarly
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
    val spec0  = fed.payload.specs(0).spec   // ANGLE E: registered offloaded spec
    val slot0IsMovem = fed.valid && spec0.movem
    // MOVEP slot0 marker (shared CSE with spec0): owned by the MOVEP FSM (below),
    // mutually exclusive with the fast head AND the MOVEM/µcode sequencers.
    val slot0IsMovep = fed.valid && spec0.movep
    // The head packet's microcode marker (shared CSE with spec0): a `microcoded` opword
    // is owned by the µcode SEQUENCER (below), mutually exclusive with the fast head AND
    // the MOVEM FSM (exactly as a MOVEM slot0 is owned by the MOVEM FSM).
    val slot0IsMicrocoded = fed.valid && spec0.microcoded
    // µcode engine state (declared early — referenced by normalHeadValid below). The
    // sequencer logic + transitions live in the µcode SEQUENCER region further down.
    val ucActive    = RegInit(False)
    val ucPendValid = RegInit(False)
    val slot1IsMovem = slot1IsMovemEarly   // slot1's MOVEM marker (decoded above, shared via CSE)
    val movemPendValid = RegInit(False)
    val movemPendPkt   = Reg(DecodePacket())
    when(pipeFlush) { movemPendValid := False }
    // Hoisted early (also used by movemBegin and ucBegin mutual-exclusion guards):
    val movepActive    = RegInit(False)
    val movepPendValid = RegInit(False)

    // The source packet the FSM enters from: the stashed slot1 MOVEM, else slot0.
    val movemEntryPkt = Mux(movemPendValid, movemPendPkt, fed.payload.packets(0))
    // Begin a MOVEM: there's a MOVEM to start (a pending slot1 one, or slot0 is MOVEM and
    // not blocked by a stash/replay) and the FSM is idle.
    val movemBegin = !movemActive && !ucActive && !ucPendValid && !movepActive && !movepPendValid &&
                     (movemPendValid || (slot0IsMovem && !stashValid))

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

    // ── MOVEP micro-sequencer FSM (alternating-byte peripheral move) ──────────────
    // MOVEP (`0000 rrr 1 oo 001 aaa` + disp16) moves a data register <-> alternating
    // EVEN memory bytes. Like the MOVEM FSM it HOLDS `fed`, latches Dx/Ay/disp/dir/size,
    // and emits a per-byte LOAD/STORE + SHIFT/AND/OR assembly sequence ONE µop/cycle (via
    // pushProduced) using EXISTING DecOps — SIDESTEPPING the ≤3-µop crack budget. The
    // intermediate temp writes (T0 load scratch + T1 accumulator) are DROPPED (divIsRem);
    // the single KEPT macro commit is the final `MOVE T1->Dx` (mem->reg) or the LAST byte
    // store's keepCommit (reg->mem writes NO register). `pipeFlush` aborts (the queue flush
    // squashes the partial µops; MOVEP re-decodes from scratch on re-fetch).
    // movepActive and movepPendValid declared above (hoisted for movemBegin/ucBegin guards).
    val movepStep      = Reg(UInt(4 bits))    // 0..10 (the longest variant = mem->reg .L = 11 steps)
    val movepDirReg    = Reg(Bool())          // 1 = register->memory (store bytes), 0 = memory->register
    val movepSizeLong  = Reg(Bool())          // 1 = .L (4 bytes), 0 = .W (2 bytes)
    val movepDx        = Reg(UInt(5 bits))     // the data register
    val movepAy        = Reg(UInt(5 bits))     // the base address register (8 + op[2:0])
    val movepDisp      = Reg(Bits(32 bits))    // sign-extended disp16 (EA = Ay + disp)
    val movepPc        = Reg(UInt(32 bits))
    val movepNextPc    = Reg(UInt(32 bits))
    when(pipeFlush) { movepActive := False }

    // MOVEP entry: a pending slot1 MOVEP (movepPendValid), else slot0. Decode Dx/Ay/disp16.
    // movepPendValid declared above (hoisted).
    val movepPendPkt   = Reg(DecodePacket())
    when(pipeFlush) { movepPendValid := False }
    val movepEntryPkt  = Mux(movepPendValid, movepPendPkt, fed.payload.packets(0))
    val mpOpw    = movepEntryPkt.words(0)
    val mpDx     = mpOpw(11 downto 9).asUInt.resize(5)
    val mpAy     = (U(8, 5 bits) + mpOpw(2 downto 0).asUInt).resize(5)
    val mpDisp   = movepEntryPkt.words(1).asSInt.resize(32).asBits   // disp16 sign-extended
    val mpDir    = mpOpw(7)                                          // 1 = reg->mem
    val mpSizeL  = mpOpw(6)                                          // 1 = .L
    val mpPc     = movepEntryPkt.pc
    val mpNextPc = (mpPc + (movepEntryPkt.lenWords << 1)).resize(32) // = pc + 4 (opword + disp16)

    // Begin a MOVEP: a pending slot1 one, OR a slot0 MOVEP (not blocked by a stash/replay),
    // while the MOVEP FSM AND the MOVEM/µcode sequencers are all idle.
    val movepBegin = !movepActive && !movemActive && !movemPendValid && !ucActive && !ucPendValid &&
                     (movepPendValid || (slot0IsMovep && !stashValid))

    // The per-step µop. The address disp for the byte at position k is movepDisp + k
    // (k ∈ {0,2,4,6}). The shift counts assemble/extract the big-endian alternating bytes:
    //   reg->mem .L: [ea]:=Dx[31:24]; [ea+2]:=Dx[23:16]; [ea+4]:=Dx[15:8]; [ea+6]:=Dx[7:0]
    //   reg->mem .W: [ea]:=Dx[15:8];  [ea+2]:=Dx[7:0]
    //   mem->reg .L: Dx := ([ea]<<24)|([ea+2]<<16)|([ea+4]<<8)|[ea+6]
    //   mem->reg .W: Dx[15:0] := ([ea]<<8)|[ea+2]; Dx[31:16] PRESERVED
    // Default = a no-op final-move placeholder (overwritten by the switches below).
    def mpDispK(k: Int): Bits = (movepDisp.asSInt + S(k, 32 bits)).asBits
    val movepUop  = DecodedUop()
    val movepLast = Bool(); movepLast := True
    val T0 = MicroOpAssembler.T0; val T1 = MicroOpAssembler.T1
    // Default (overwritten by the per-variant/step switches below): the final move
    // placeholder (also the natural step for the mem->reg last step).
    movepUop := MicroOpAssembler.movepFinalMoveUop(U(T1, 5 bits), movepDx, movepPc, movepNextPc)
    when(movepDirReg) {
      when(movepSizeLong) {
        // reg->mem .L : 7 steps (3 shift+store pairs + the position-0 store of Dx).
        switch(movepStep) {
          is(0) { movepUop := MicroOpAssembler.movepShiftUop(movepDx, U(T1,5 bits), 24, dirLeft=false, first=true,  movepPc, movepNextPc); movepLast := False }
          is(1) { movepUop := MicroOpAssembler.movepStoreUop(movepAy, mpDispK(0), U(T1,5 bits), keep=false, first=false, movepPc, movepNextPc); movepLast := False }
          is(2) { movepUop := MicroOpAssembler.movepShiftUop(movepDx, U(T1,5 bits), 16, dirLeft=false, first=false, movepPc, movepNextPc); movepLast := False }
          is(3) { movepUop := MicroOpAssembler.movepStoreUop(movepAy, mpDispK(2), U(T1,5 bits), keep=false, first=false, movepPc, movepNextPc); movepLast := False }
          is(4) { movepUop := MicroOpAssembler.movepShiftUop(movepDx, U(T1,5 bits),  8, dirLeft=false, first=false, movepPc, movepNextPc); movepLast := False }
          is(5) { movepUop := MicroOpAssembler.movepStoreUop(movepAy, mpDispK(4), U(T1,5 bits), keep=false, first=false, movepPc, movepNextPc); movepLast := False }
          default { movepUop := MicroOpAssembler.movepStoreUop(movepAy, mpDispK(6), movepDx, keep=true, first=false, movepPc, movepNextPc); movepLast := True }   // step 6
        }
      } otherwise {
        // reg->mem .W : 3 steps (LSR Dx>>8 -> T1 ; STORE T1 ; STORE Dx (keep)).
        switch(movepStep) {
          is(0) { movepUop := MicroOpAssembler.movepShiftUop(movepDx, U(T1,5 bits), 8, dirLeft=false, first=true,  movepPc, movepNextPc); movepLast := False }
          is(1) { movepUop := MicroOpAssembler.movepStoreUop(movepAy, mpDispK(0), U(T1,5 bits), keep=false, first=false, movepPc, movepNextPc); movepLast := False }
          default { movepUop := MicroOpAssembler.movepStoreUop(movepAy, mpDispK(2), movepDx, keep=true, first=false, movepPc, movepNextPc); movepLast := True }   // step 2
        }
      }
    } otherwise {
      when(movepSizeLong) {
        // mem->reg .L : 11 steps. Assemble in T1 (acc); T0 = load scratch.
        //  0 LOAD[ea]->T0 ; 1 LSL T0<<24->T1 ; 2 LOAD[ea+2]->T0 ; 3 LSL T0<<16->T0 ; 4 OR T1|T0->T1 ;
        //  5 LOAD[ea+4]->T0 ; 6 LSL T0<<8->T0 ; 7 OR T1|T0->T1 ; 8 LOAD[ea+6]->T0 ; 9 OR T1|T0->T1 ;
        // 10 MOVE T1->Dx (keep).
        switch(movepStep) {
          is(0)  { movepUop := MicroOpAssembler.movepLoadUop(movepAy, mpDispK(0), first=true,  movepPc, movepNextPc); movepLast := False }
          is(1)  { movepUop := MicroOpAssembler.movepShiftUop(U(T0,5 bits), U(T1,5 bits), 24, dirLeft=true, first=false, movepPc, movepNextPc); movepLast := False }
          is(2)  { movepUop := MicroOpAssembler.movepLoadUop(movepAy, mpDispK(2), first=false, movepPc, movepNextPc); movepLast := False }
          is(3)  { movepUop := MicroOpAssembler.movepShiftUop(U(T0,5 bits), U(T0,5 bits), 16, dirLeft=true, first=false, movepPc, movepNextPc); movepLast := False }
          is(4)  { movepUop := MicroOpAssembler.movepOrUop(U(T1,5 bits), U(T0,5 bits), U(T1,5 bits), movepPc, movepNextPc); movepLast := False }
          is(5)  { movepUop := MicroOpAssembler.movepLoadUop(movepAy, mpDispK(4), first=false, movepPc, movepNextPc); movepLast := False }
          is(6)  { movepUop := MicroOpAssembler.movepShiftUop(U(T0,5 bits), U(T0,5 bits),  8, dirLeft=true, first=false, movepPc, movepNextPc); movepLast := False }
          is(7)  { movepUop := MicroOpAssembler.movepOrUop(U(T1,5 bits), U(T0,5 bits), U(T1,5 bits), movepPc, movepNextPc); movepLast := False }
          is(8)  { movepUop := MicroOpAssembler.movepLoadUop(movepAy, mpDispK(6), first=false, movepPc, movepNextPc); movepLast := False }
          is(9)  { movepUop := MicroOpAssembler.movepOrUop(U(T1,5 bits), U(T0,5 bits), U(T1,5 bits), movepPc, movepNextPc); movepLast := False }
          default { movepUop := MicroOpAssembler.movepFinalMoveUop(U(T1,5 bits), movepDx, movepPc, movepNextPc); movepLast := True }   // step 10
        }
      } otherwise {
        // mem->reg .W : 7 steps. Preserve Dx[31:16] via AND mask into T1, assemble low word.
        //  0 AND Dx & 0xFFFF0000 -> T1 ; 1 LOAD[ea]->T0 ; 2 LSL T0<<8->T0 ; 3 OR T1|T0->T1 ;
        //  4 LOAD[ea+2]->T0 ; 5 OR T1|T0->T1 ; 6 MOVE T1->Dx (keep, full write).
        switch(movepStep) {
          is(0) { movepUop := MicroOpAssembler.movepAndMaskUop(movepDx, U(T1,5 bits), 0xFFFF0000L, first=true, movepPc, movepNextPc); movepLast := False }
          is(1) { movepUop := MicroOpAssembler.movepLoadUop(movepAy, mpDispK(0), first=false, movepPc, movepNextPc); movepLast := False }
          is(2) { movepUop := MicroOpAssembler.movepShiftUop(U(T0,5 bits), U(T0,5 bits), 8, dirLeft=true, first=false, movepPc, movepNextPc); movepLast := False }
          is(3) { movepUop := MicroOpAssembler.movepOrUop(U(T1,5 bits), U(T0,5 bits), U(T1,5 bits), movepPc, movepNextPc); movepLast := False }
          is(4) { movepUop := MicroOpAssembler.movepLoadUop(movepAy, mpDispK(2), first=false, movepPc, movepNextPc); movepLast := False }
          is(5) { movepUop := MicroOpAssembler.movepOrUop(U(T1,5 bits), U(T0,5 bits), U(T1,5 bits), movepPc, movepNextPc); movepLast := False }
          default { movepUop := MicroOpAssembler.movepFinalMoveUop(U(T1,5 bits), movepDx, movepPc, movepNextPc); movepLast := True }   // step 6
        }
      }
    }

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
    val normalHeadValid = stashValid || (fed.valid && !slot0IsMovem && !slot0IsMicrocoded && !slot0IsMovep && !movemPendValid && !ucPendValid && !ucActive && !movepPendValid && !movepActive)

    // Produce the push as a Stream. When the MOVEM FSM is active it OVERRIDES the source
    // (its 2 moves / the final An update); otherwise the normal crack drives it.
    // ── µcode SEQUENCER (the ROM-driven generalization of the MOVEM FSM) ─────────
    // On a `microcoded` opword the engine takes over (mutually exclusive with the fast
    // head AND the MOVEM FSM): latch the instruction CONTEXT (Microcode.Ctx), walk
    // Microcode.rom from `ucEntry` emitting ONE resolved µop/cycle (v1 straight-line),
    // hold `fed` until the `isLast` descriptor, `pipeFlush` aborts. Same contract as
    // MOVEM, parameterized by the ROM instead of MOVEM's bespoke mask loop. v1 LIMIT: a
    // microcoded op immediately FOLLOWED by another microcoded/MOVEM op in the SAME fetch
    // group is the untested edge — the slot1 stash carries a NORMAL slot1 (the tested
    // programs put a normal instr / NOP after each X-mem op).
    val ucPc     = Reg(UInt(5 bits))
    val ucCtx    = Reg(Microcode.Ctx())
    when(pipeFlush) { ucActive := False }

    // Pending slot1 microcoded op (mirrors movemPendValid/movemPendPkt): when slot0 is a
    // NORMAL instruction and slot1 IS microcoded, slot0 emits this cycle and the slot1
    // PACKET is stashed; the engine enters from it next cycle. `fed` was already consumed
    // when the slot1 was stashed (the FOLLOWING group is held until the engine finishes).
    val ucPendPkt   = Reg(DecodePacket())
    when(pipeFlush) { ucPendValid := False }

    // The source packet the engine enters from: the stashed slot1 microcoded op, else slot0.
    val ucEntryPkt = Mux(ucPendValid, ucPendPkt, fed.payload.packets(0))
    // ucEntrySpec: reuse spec0 (the slot0 decode) for a slot0 entry; only the PENDING-slot1
    // entry needs its own decode (its opword differs from slot0). One extra cone, not two.
    val ucPendSpec  = OperationDecoder.decode(ucPendPkt.words(0))
    val ucEntrySpec = Mux(ucPendValid, ucPendSpec, spec0)

    // Begin a µcode op: a pending slot1 microcoded op, OR a microcoded slot0 (not blocked
    // by a stash), while the engine + the MOVEM FSM are idle.
    val ucBegin = !ucActive && !movemActive && !movemPendValid && !movepActive && !movepPendValid &&
                  (ucPendValid || (slot0IsMicrocoded && !stashValid))
    // Entering a slot0 microcoded op (not a pending one): its group is consumed on entry.
    val ucEnterSlot0 = ucBegin && !ucPendValid
    // The entry context, latched on ucBegin from the entry packet.
    val ucEntryCtx = Microcode.Ctx()
    ucEntryCtx.opword       := ucEntryPkt.words(0)
    ucEntryCtx.pc           := ucEntryPkt.pc
    ucEntryCtx.nextPc       := (ucEntryPkt.pc + (ucEntryPkt.lenWords << 1)).resize(32)
    ucEntryCtx.op           := ucEntrySpec.op
    ucEntryCtx.bcdSub       := ucEntrySpec.bcdSub
    ucEntryCtx.size         := ucEntrySpec.size
    ucEntryCtx.sizeBytesLog := ucEntrySpec.size.mux(
      Size.BYTE -> U(0, 2 bits), Size.WORD -> U(1, 2 bits), default -> U(2, 2 bits))

    // ── v2 bit-field RMW Ctx population (slice 3b) ────────────────────────────────
    // The bit-field RMW ops route through the engine. OperationDecoder is ext-word-free,
    // so the EA + static params are resolved HERE (ucBegin has the full packet words),
    // mirroring the 3a MicroOpAssembler `bfm` block. The bf-ext word is words(1); the EA's
    // OWN ext words FOLLOW it (words(2..)) -> re-decode the EA from a SHIFTED vector.
    val ucBfExt    = ucEntryPkt.words(1)
    val ucBfEaDec  = EaDecoder.decode(ucEntryPkt.words(0)(5 downto 0), Size.LONG,
                                      Vec(ucEntryPkt.words(0), ucEntryPkt.words(2), ucEntryPkt.words(3)))
    val ucBfOffset5 = ucBfExt(10 downto 6).asUInt              // static offset 0..31
    val ucBfWidthRaw= ucBfExt(4 downto 0).asUInt               // raw width (0->32)
    val ucBfWidth   = (((ucBfWidthRaw - 1) & U(31, 5 bits)) + 1)   // 1..32
    val ucBfDn2     = ucBfExt(14 downto 12).asUInt.resize(5)
    val ucBfByteOff = ucBfOffset5 >> 3                         // 0..3 (folded into disp)
    val ucBfBitOff  = (ucBfOffset5 & U(7, 5 bits)).resize(3)   // 0..7
    val ucBfNeedHi  = (ucBfBitOff.resize(6) + ucBfWidth.resize(6)) > U(32, 6 bits)
    // byteAddr disp = EA disp + (offset>>3). PC-rel is ILLEGAL for RMW (never reaches the
    // engine), so the base is always an An or absolute -> no pcRel fold needed.
    val ucBfDispLo  = (ucBfEaDec.disp.asUInt + ucBfByteOff).asBits
    val ucBfDispHi  = (ucBfDispLo.asUInt + U(4, 32 bits)).asBits   // byteAddr+4 (spill byte)
    // imm packing identical to the 3a bfmImm (AluEu already decodes this layout):
    //   imm[4:0]=0 (rotate offset), imm[9:5]=rawWidth, imm[12:10]=bitOff, imm[13]=needHi,
    //   imm[18:14]=origOffset.
    val ucBfImm = (B(0, 13 bits) ## ucBfOffset5.asBits.resize(5) ## ucBfNeedHi ##
                   ucBfBitOff.asBits.resize(3) ## ucBfWidthRaw.asBits.resize(5) ## B(0, 5 bits)).resize(32)
    ucEntryCtx.eaBase       := ucBfEaDec.base
    ucEntryCtx.eaBaseValid  := ucBfEaDec.baseValid
    ucEntryCtx.eaIndexReg   := ucBfEaDec.indexReg
    ucEntryCtx.eaIndexValid := ucBfEaDec.indexValid
    ucEntryCtx.eaIndexLong  := ucBfEaDec.indexLong
    ucEntryCtx.eaIndexScale := ucBfEaDec.indexScale
    ucEntryCtx.eaDispLo     := ucBfDispLo
    ucEntryCtx.eaDispHi     := ucBfDispHi
    ucEntryCtx.bfOp         := ucEntryPkt.words(0)(10 downto 8)
    ucEntryCtx.bfDn2        := ucBfDn2
    ucEntryCtx.bfImm        := ucBfImm
    ucEntryCtx.bfNeedHi     := ucBfNeedHi
    // The REAL entry: a bit-field RMW (microcoded BITFIELD) picks 5B vs 4B by needHi;
    // every other microcoded op (BCD/ADDX/SUBX) keeps its OperationDecoder ucEntry.
    val ucIsBfRmw  = ucEntrySpec.microcoded && (ucEntrySpec.op === DecOp.BITFIELD)
    val ucRealEntry = Mux(ucIsBfRmw,
      Mux(ucBfNeedHi, U(Microcode.BF_RMW_5B_ENTRY, ucEntrySpec.ucEntry.getWidth bits),
                      U(Microcode.BF_RMW_4B_ENTRY, ucEntrySpec.ucEntry.getWidth bits)),
      ucEntrySpec.ucEntry)

    // Resolve every ROM row against the LATCHED ctx, then index by ucPc -> this cycle's
    // µop (the ROM is a compile-time Scala Vector; resolve each row to hardware + mux).
    val ucResolved = Vec(Microcode.rom.map(d => Microcode.resolve(d, ucCtx, True)))
    val ucLastVec  = Vec(Microcode.rom.map(d => Bool(d.isLast)))
    val ucIdx      = ucPc.resize(log2Up(Microcode.romSize))
    val ucCurUop   = ucResolved(ucIdx)
    val ucCurLast  = ucLastVec(ucIdx)

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
    } elsewhen(ucActive) {
      // µcode SEQUENCER drive: emit the resolved ROM µop at ucPc (1/cycle in v1).
      pushProduced.valid           := True
      pushProduced.payload.uops(0) := ucCurUop
      pushProduced.payload.uops(1) := ucCurUop
      pushProduced.payload.uops(2) := ucCurUop
      pushProduced.payload.uops(3) := ucCurUop
      pushProduced.payload.count   := U(1, 3 bits)
    } elsewhen(movepActive) {
      // MOVEP FSM drive: emit the per-step byte LOAD/STORE + shift/and/or µop (1/cycle).
      pushProduced.valid           := True
      pushProduced.payload.uops(0) := movepUop
      pushProduced.payload.uops(1) := movepUop
      pushProduced.payload.uops(2) := movepUop
      pushProduced.payload.uops(3) := movepUop
      pushProduced.payload.count   := U(1, 3 bits)
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
    // µcode: hold `fed` while the engine is busy or about to begin; release exactly when
    // the LAST µop is accepted into pushReg (ucReleaseFed). A microcoded slot0 is owned by
    // the engine (like a MOVEM slot0), so the NORMAL fed.ready arm must NOT consume it.
    val ucHoldsFed    = ucActive || ucPendValid || slot0IsMicrocoded
    // MOVEP: identical contract to MOVEM. A slot0 MOVEP is consumed on its ENTRY cycle
    // (movepEnterSlot0) — its state is latched into regs that cycle; its slot1 is stashed
    // and replayed after the FSM finishes. A slot1 MOVEP is entered from the stashed PACKET
    // (`fed` already consumed when stashed); while the FSM runs `fed` holds the FOLLOWING
    // group (movepHoldsFed), released by the normal head once movepActive clears.
    val movepEnterSlot0 = movepBegin && !movepPendValid
    val movepHoldsFed   = movepActive || movepPendValid
    // NOTE: the engine does NOT consume `fed` on its last µop — the held FOLLOWING group
    // is emitted by the normal head once ucActive clears (ucHoldsFed drops). Consuming it
    // here would DROP that group. The sbcd's OWN group was already consumed at entry
    // (ucEnterSlot0) or when its slot1 was stashed (ucPendValid set in the normal consume).
    fed.ready := (!stashValid && !movemHoldsFed && !slot0IsMovem && !ucHoldsFed &&
                  !movepHoldsFed && !slot0IsMovep && pushProduced.ready) ||
                 movemEnterSlot0 || ucEnterSlot0 || movepEnterSlot0
    when(!movemActive && !movemBegin && !ucBegin && !ucActive && !movepActive && !movepBegin && pushProduced.ready) {
      when(stashValid) {
        stashValid := False                  // the stashed slot1/RTR was emitted (into pushReg) this cycle
      } elsewhen(slot1IsMovem) {
        // slot0 (non-MOVEM) emitted this cycle; stash the slot1 MOVEM packet, consume fed.
        movemPendValid := True
        movemPendPkt   := fed.payload.packets(1)
      } elsewhen(slot1IsMovepEarly) {
        // slot0 (normal) emitted this cycle; stash the slot1 MOVEP packet, consume fed.
        // The MOVEP FSM enters from it next cycle (mirrors the slot1 MOVEM pend).
        movepPendValid := True
        movepPendPkt   := fed.payload.packets(1)
      } elsewhen(slot1IsUcodeEarly) {
        // slot0 (normal) emitted this cycle; stash the slot1 MICROCODED packet, consume fed.
        // The engine enters from it next cycle (mirrors the slot1 MOVEM pend).
        ucPendValid := True
        ucPendPkt   := fed.payload.packets(1)
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
        } elsewhen(slot1IsMovepEarly) {
          movepPendValid := True
          movepPendPkt   := fed.payload.packets(1)
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

    // ── µcode SEQUENCER transitions (mirror the MOVEM FSM begin/advance/abort) ────
    when(ucBegin) {
      // Latch the entry context; start emitting next cycle from ucEntry. For a bit-field
      // RMW the real entry is picked from the latched bfNeedHi (4-byte vs 5-byte chain),
      // since OperationDecoder is ext-word-free and emitted only the 4-byte default.
      ucActive := True
      ucPc     := ucRealEntry
      ucCtx    := ucEntryCtx
      // A pending slot1 microcoded op is now consumed by this entry.
      when(ucPendValid) { ucPendValid := False }
      // Entering a slot0 microcoded op: STASH its slot1 (if any) so it is not lost when `fed`
      // is consumed this cycle. A slot1 MOVEM -> packet stash (movemPendValid); a slot1
      // microcoded -> packet stash (ucPendValid); a normal slot1 -> decoded-µop stash. Replayed
      // after the engine finishes.
      when(ucEnterSlot0 && fed.payload.slot1Valid) {
        when(slot1IsMovem) {
          movemPendValid := True
          movemPendPkt   := fed.payload.packets(1)
        } elsewhen(slot1IsMovepEarly) {
          movepPendValid := True
          movepPendPkt   := fed.payload.packets(1)
        } elsewhen(slot1IsUcodeEarly) {
          ucPendValid := True
          ucPendPkt   := fed.payload.packets(1)
        } otherwise {
          stashValid := True
          stashCount := a1raw.count
          for (i <- 0 until 3) { stashUops(i) := a1raw.uops(i) }
        }
      }
    } elsewhen(ucActive) {
      when(pushProduced.ready) {
        when(ucCurLast) { ucActive := False }       // last µop accepted -> release fed (ucReleaseFed)
          .otherwise    { ucPc := ucPc + 1 }        // advance the µPC (straight-line)
      }
    }
    // pipeFlush ABORTS the engine (LAST word, so a flush coinciding with ucBegin still
    // resets it): the partially-emitted µops are squashed by the queue flush; the op
    // re-decodes from scratch on re-fetch.
    when(pipeFlush) { ucActive := False; ucPendValid := False }

    // ── MOVEP FSM transitions (mirror the MOVEM/µcode FSM begin/advance/abort) ─────
    when(movepBegin) {
      // Latch all MOVEP state; start emitting next cycle from these registers.
      movepActive   := True
      movepStep     := 0
      movepDirReg   := mpDir
      movepSizeLong := mpSizeL
      movepDx       := mpDx
      movepAy       := mpAy
      movepDisp     := mpDisp
      movepPc       := mpPc
      movepNextPc   := mpNextPc
      // A pending slot1 MOVEP is now consumed by this entry.
      when(movepPendValid) { movepPendValid := False }
      // Entering a slot0 MOVEP: STASH its slot1 (if any) so it is not lost when `fed` is
      // consumed this cycle. A slot1 MOVEM -> movemPend; a slot1 MOVEP -> movepPend; a slot1
      // microcoded -> ucPend; a normal slot1 -> decoded-µop stash. Replayed after the FSM finishes.
      when(movepEnterSlot0 && fed.payload.slot1Valid) {
        when(slot1IsMovem) {
          movemPendValid := True
          movemPendPkt   := fed.payload.packets(1)
        } elsewhen(slot1IsMovepEarly) {
          movepPendValid := True
          movepPendPkt   := fed.payload.packets(1)
        } elsewhen(slot1IsUcodeEarly) {
          ucPendValid := True
          ucPendPkt   := fed.payload.packets(1)
        } otherwise {
          stashValid := True
          stashCount := a1raw.count
          for (i <- 0 until 3) { stashUops(i) := a1raw.uops(i) }
        }
      }
    } elsewhen(movepActive) {
      when(pushProduced.ready) {
        when(movepLast) { movepActive := False }    // last µop accepted -> release fed
          .otherwise    { movepStep := movepStep + 1 }
      }
    }
    // pipeFlush ABORTS the MOVEP FSM (LAST word, so a flush coinciding with movepBegin still
    // resets it): the partially-emitted µops are squashed by the queue flush; MOVEP
    // re-decodes from scratch on re-fetch.
    when(pipeFlush) { movepActive := False; movepPendValid := False }

    // ── Rename-facing output ───────────────────────────────────────────────────
    val uopsOut = queue.io.pop
    val uop1Sig = queue.io.pop1Valid
  }

  override def uops: Stream[Vec[DecodedUop]] = logic.uopsOut
  override def uop1Valid: Bool               = logic.uop1Sig
}
