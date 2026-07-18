package m68k040.decode

import m68k040.frontend.{DecodePacket, PipeStage}
import m68k040.services.{DecodeFeedService, DecodeUopService}
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
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
    slot1IsUcodeEarly.simPublic()  // debug-only (task #144)
    // A slot1 MOVEP, like a slot1 MOVEM, cannot be emitted as a normal crack — its real
    // µops come from the MOVEP FSM (entered next cycle from the stashed packet). Exclude
    // it from the normal slot1 push (the assembler's benign MOVE placeholder would
    // otherwise be a phantom commit).
    val slot1IsMovepEarly = fed.valid && fed.payload.slot1Valid && slot1Spec0.movep
    // A slot1 full-format MEMORY-INDIRECT host (mirror slot1IsUcodeEarly): its real µops come
    // from the µcode engine entered from the stashed slot1 packet, so it MUST be excluded from
    // the normal slot1 push (else the assembler's illegal/placeholder crack is a phantom).
    val s1mi_pkt   = fed.payload.packets(1)
    val s1mi_srcEa = fed.payload.specs(1).srcEa
    val s1mi_dstEa = fed.payload.specs(1).dstEa
    val s1mi_opw   = s1mi_pkt.words(0)
    val s1mi_line  = s1mi_opw(15 downto 12).asUInt
    val s1mi_opmode= s1mi_opw(8 downto 6).asUInt
    val s1mi_isMove= (s1mi_line === U(1, 4 bits)) || (s1mi_line === U(2, 4 bits)) || (s1mi_line === U(3, 4 bits))
    // ADDA/SUBA/CMPA (opmode 3/7, An-dest arithmetic) mirror s0AnArith/ucAnArith below —
    // see the s0AnArith comment (task #144 follow-up) for why this is needed and why it
    // does not affect DIVU/DIVS (line 8) / MULU/MULS (line C).
    val s1mi_anArith = (slot1Spec0.dst.kind === OperandKind.REGFIELD) && slot1Spec0.dst.isAddr &&
                       ((slot1Spec0.op === DecOp.ADD) || (slot1Spec0.op === DecOp.SUB) || (slot1Spec0.op === DecOp.CMP))
    val s1mi_isAluLine = (s1mi_line === U(8, 4 bits)) || (s1mi_line === U(9, 4 bits)) || (s1mi_line === U(0xB, 4 bits)) ||
                         (s1mi_line === U(0xC, 4 bits)) || (s1mi_line === U(0xD, 4 bits))
    val s1mi_isAlu = s1mi_isAluLine &&
                     ((s1mi_opmode === U(0, 3 bits)) || (s1mi_opmode === U(1, 3 bits)) || (s1mi_opmode === U(2, 3 bits)) ||
                      (s1mi_anArith && ((s1mi_opmode === U(3, 3 bits)) || (s1mi_opmode === U(7, 3 bits)))))
    // ALU Dn,<ea> RMW dst-EA (task #150, mirrors s0AluDstMode/ucAluDstMi): opmode 4/5/6.
    val s1mi_isAluDst = s1mi_isAluLine &&
                        ((s1mi_opmode === U(4, 3 bits)) || (s1mi_opmode === U(5, 3 bits)) || (s1mi_opmode === U(6, 3 bits)))
    val s1mi_isSingle = (slot1Spec0.op === DecOp.CLR) || (slot1Spec0.op === DecOp.NEG) || (slot1Spec0.op === DecOp.NEGX) ||
                        (slot1Spec0.op === DecOp.NOT) || (slot1Spec0.op === DecOp.TST)
    // ADDQ/SUBQ #n,<ea> (task #150 follow-up, mirrors s0IsAddqSubq/ucAddqSubqMi).
    val s1mi_isAddqSubq = slot1Spec0.srcB.kind === OperandKind.IMMQ3
    val s1mi_isImm = slot1Spec0.srcB.kind === OperandKind.IMMEXT
    // task #152: exclude the bit-op tt/.L-size field collision (see ucImmIsL's comment).
    val s1mi_immL  = (slot1Spec0.op =/= DecOp.BITOP) && (s1mi_opw(7 downto 6) === B"10")
    val s1mi_immVec= Mux(s1mi_immL, Vec(s1mi_opw, s1mi_pkt.words(3), s1mi_pkt.words(4), s1mi_pkt.words(5)),
                                    Vec(s1mi_opw, s1mi_pkt.words(2), s1mi_pkt.words(3), s1mi_pkt.words(4)))
    val s1mi_immEa = EaDecoder.decode(s1mi_opw(5 downto 0), slot1Spec0.size, s1mi_immVec)
    val slot1IsMemIndEarly = fed.valid && fed.payload.slot1Valid && (
      (s1mi_isMove && (s1mi_srcEa.klass === EaClass.MEMINDIRECT)) ||
      (s1mi_isMove && (s1mi_dstEa.klass === EaClass.MEMINDIRECT)) ||
      (s1mi_isAlu && (s1mi_srcEa.klass === EaClass.MEMINDIRECT)) ||
      (s1mi_isAluDst && (s1mi_srcEa.klass === EaClass.MEMINDIRECT)) ||
      (s1mi_isAddqSubq && (s1mi_srcEa.klass === EaClass.MEMINDIRECT)) ||
      // task #153: the .L-imm case (s1mi_immL) is now routed too (predecode correctly
      // frames it via extW3) -- see the s0IsLineImm mirror below for the full explanation.
      (s1mi_isImm && (s1mi_immEa.klass === EaClass.MEMINDIRECT)) ||
      (s1mi_isSingle && (s1mi_srcEa.klass === EaClass.MEMINDIRECT)))
    slot1IsMemIndEarly.simPublic()  // debug-only (task #144)
    // slot1 DYNAMIC read-only bit-field (slice 3c) — mirror slot1IsMemIndEarly: its real µops
    // come from the engine (entered from the stashed slot1 packet), so EXCLUDE it from the
    // normal slot1 push (the 3a crack misreads Do/Dw as static -> phantom). Scoped to An-base.
    val s1bfExt    = s1mi_pkt.words(1)
    val s1bfEa     = EaDecoder.decode(s1mi_opw(5 downto 0), Size.LONG,
                                      Vec(s1mi_opw, s1mi_pkt.words(2), s1mi_pkt.words(3)))
    val slot1IsBfDynMemEarly = fed.valid && fed.payload.slot1Valid &&
      (slot1Spec0.op === DecOp.BITFIELD) && !slot1Spec0.microcoded &&
      ((slot1Spec0.bfOp === 0) || (slot1Spec0.bfOp === 1) || (slot1Spec0.bfOp === 3) || (slot1Spec0.bfOp === 5)) &&
      (s1bfExt(11) || s1bfExt(5)) &&
      (s1bfEa.klass === EaClass.MEMSIMPLE) && (s1bfEa.autoMode === EaAuto.NONE) && s1bfEa.baseValid

    // slot1 is emitted alongside slot0 only when: not replaying a stash, slot1 present,
    // slot0 is NOT 3-µop, slot1 itself is NOT 3-µop (a 3-µop slot1 is deferred), and
    // slot1 is NOT a MOVEM/MOVEP (the FSM owns it — see slot1IsMovemEarly/slot1IsMovepEarly).
    val slot1Emit = !stashValid && fed.valid && fed.payload.slot1Valid && !slot0Is3 && !slot1Is3 && !slot1IsMovemEarly && !slot1IsUcodeEarly && !slot1IsMovepEarly && !slot1IsMemIndEarly && !slot1IsBfDynMemEarly
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
    // FUZZER-CAUGHT (B6): a POSTINC MOVEM *LOAD* whose target register IS the base An
    // must DISCARD the loaded value — Musashi (movem, er, pi) loads REG_DA[i] in the
    // loop and then overwrites An with `AY = ea` (the post-incremented address) AFTER
    // it. Redirect that element's load DEST to the T0 scratch: the memory access (and
    // its fault behavior) is kept, but the base An is never renamed mid-macro, so
    // (a) later elements' addresses still read the ORIGINAL base and (b) the final
    // An-update µop computes origAn + count*step (not loadedValue + count*step).
    // Control-mode loads ((An)/(d16,An), movemDoAnUpd=False) keep An := loaded value
    // (Musashi's er,. variant has no AY writeback). Stores are unaffected (isLoad).
    def movemLoadDst(reg: UInt): UInt =
      Mux(movemIsLoad && movemDoAnUpd && movemBaseValid && (reg === movemBaseReg),
          U(MicroOpAssembler.T0, 5 bits), reg)
    val movemUop0 = MicroOpAssembler.movemMoveUop(
      reg = movemLoadDst(movemReg0), base = movemBaseReg, baseValid = movemBaseValid, disp = movemImm0,
      sizeLong = movemSizeLong, isLoad = movemIsLoad, first = movemFirst0, drop = movemHasFinal,
      valid = True, pc = movemPc, nextPc = movemNextPc)
    val movemUop1 = MicroOpAssembler.movemMoveUop(
      reg = movemLoadDst(movemReg1), base = movemBaseReg, baseValid = movemBaseValid, disp = movemImm1,
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
    // ── FULL-format MEMORY-INDIRECT host detection (slot0) ───────────────────────
    // An EA-taking op whose EA is a full-format memory-indirect mode routes through the
    // µcode engine (like a microcoded slot0). Detect it here (off the offloaded slot0 EAs +
    // a shifted re-decode for the immediate-dst family) so the begin/hold/consume guards can
    // treat it like slot0IsMicrocoded. The full Ctx + entry are built in the ucEntryCtx block.
    val s0pkt    = fed.payload.packets(0)
    val s0srcEa  = fed.payload.specs(0).srcEa   // offloaded EA on op[5:0] @ words(1)
    val s0dstEa  = fed.payload.specs(0).dstEa   // offloaded EA on the MOVE dst field
    val s0opw    = s0pkt.words(0)
    val s0line   = s0opw(15 downto 12).asUInt
    val s0opmode = s0opw(8 downto 6).asUInt
    val s0IsMove = (s0line === U(1, 4 bits)) || (s0line === U(2, 4 bits)) || (s0line === U(3, 4 bits))
    val s0IsAluSrcLine = (s0line === U(8, 4 bits)) || (s0line === U(9, 4 bits)) ||
                         (s0line === U(0xB, 4 bits)) || (s0line === U(0xC, 4 bits)) || (s0line === U(0xD, 4 bits))
    // ADDA/SUBA/CMPA (opmode 3/7, An-dest arithmetic — mirrors MicroOpAssembler.scala's
    // `anArith`): these are ALSO ALU-src-EA ops (source = EA, dest = An) and need the SAME
    // mem-indirect routing as the Dn-dest opmode 0/1/2 forms (task #144 follow-up: ADDA
    // with a full-format mem-indirect source silently fell through to the ordinary,
    // mem-indirect-unaware assembler path instead of tripping any illegal-gate, producing
    // a garbage EA/wild PC). DIVU/DIVS (line 8) and MULU/MULS (line C) also use opmode
    // 3/7 but resolve to a different DecOp (not ADD/SUB/CMP), so s0AnArith naturally
    // excludes them — this does NOT change their routing.
    val s0AnArith = (spec0.dst.kind === OperandKind.REGFIELD) && spec0.dst.isAddr &&
                    ((spec0.op === DecOp.ADD) || (spec0.op === DecOp.SUB) || (spec0.op === DecOp.CMP))
    val s0AluSrcMode = (s0opmode === U(0, 3 bits)) || (s0opmode === U(1, 3 bits)) || (s0opmode === U(2, 3 bits)) ||
                       (s0AnArith && ((s0opmode === U(3, 3 bits)) || (s0opmode === U(7, 3 bits))))
    // ALU Dn,<ea> RMW dst-EA (task #150, mirrors ucAluDstMi below): opmode 4/5/6 on the
    // SAME op[5:0] EA field, just read/written instead of only read. Needed here too —
    // this early slot0 gate is what decides whether the instruction enters the µcode
    // engine AT ALL; without it a memory-indirect RMW dst falls through to the ordinary
    // fast head with a garbage EA (wild PC), regardless of the later ucAluDstMi fix.
    val s0AluDstMode = (s0opmode === U(4, 3 bits)) || (s0opmode === U(5, 3 bits)) || (s0opmode === U(6, 3 bits))
    // ADDQ/SUBQ #n,<ea> (task #150 follow-up, mirrors ucAddqSubqMi below): another
    // dst-EA RMW form, srcB.kind=IMMQ3 (distinct from the line-0 IMMEXT immediate).
    val s0IsAddqSubq = spec0.srcB.kind === OperandKind.IMMQ3
    val s0IsSingleEa = (spec0.op === DecOp.CLR) || (spec0.op === DecOp.NEG) || (spec0.op === DecOp.NEGX) ||
                       (spec0.op === DecOp.NOT) || (spec0.op === DecOp.TST)
    val s0IsLineImm  = spec0.srcB.kind === OperandKind.IMMEXT
    // task #152: op[7:6] doubles as the bit-op tt sub-kind (BCLR=10 collides with the .L
    // size encoding) -- see ucImmIsL's comment below for the full explanation. Excluded
    // here too (this early gate is what decides engine entry in the first place).
    val s0ImmIsL     = (spec0.op =/= DecOp.BITOP) && (s0opw(7 downto 6) === B"10")
    val s0ImmEaVec   = Mux(s0ImmIsL, Vec(s0opw, s0pkt.words(3), s0pkt.words(4), s0pkt.words(5)),
                                     Vec(s0opw, s0pkt.words(2), s0pkt.words(3), s0pkt.words(4)))
    val s0ImmEa      = EaDecoder.decode(s0opw(5 downto 0), spec0.size, s0ImmEaVec)
    // FORMERLY a SILENT-CORRUPTION HOLE (task #153 fix): a .L-immediate op with a
    // FULL-FORMAT dst EA (mem-indirect here) places the EA's first ext word at op+3, one
    // word beyond the ORIGINAL 2-word predecode lookahead (op+1/op+2 only) -- it used to
    // frame BRIEF (too short), mis-fetching the FOLLOWING instruction, and was never routed
    // to the engine (fell through to the normal head, gated ILLEGAL by
    // MicroOpAssembler's `limmFullFmtDstBad`, vector 4). PredecodeWord.classify/
    // IcachePlugin now thread a 3rd lookahead word (extW3, op+3) so this frames correctly
    // (see PredecodeWord.scala's extW3 doc comment) and routes here exactly like the
    // .B/.W imm-dst mem-indirect forms below. `s0LimmFullDstBad` now only fires for the
    // residual, much narrower edge case where extW3 itself is unavailable (this exact
    // opword landing at the very end of a fetched cache line) — predecode's F5-precedent
    // fallback there still frames BRIEF, so this op still correctly stays ungated/illegal
    // rather than silently mis-executing. (Declared for documentation/future diagnostic
    // use; not currently read elsewhere.)
    val s0LimmFullDstBad = s0IsLineImm && s0ImmIsL && (s0ImmEa.klass === EaClass.MEMINDIRECT)
    // ported-tests triage (move_l_abs_memind_dst): the OFFLOADED s0dstEa (Offload /
    // computeOffload in MicroOpAssembler.scala) reads the MOVE dst's own ext word at a
    // FIXED words(1)/words(2) position — correct only when the SOURCE EA is register-direct
    // (0 ext words). Whenever the source itself consumes >=1 ext word (abs.W/abs.L/(d16,An)/
    // mem-indirect/...), a full-format (bit8=1) dst EA's base ext word actually sits further
    // down the stream than the offload assumes, so s0dstEa misreads a stale word there — for
    // dst mode 6 / mode 7-reg3 this silently misclassifies a REAL memory-indirect dst as
    // brief MEMSIMPLE (the misread bit8 happens to be 0), producing a false-negative
    // slot0IsMemInd: the instruction never enters the µcode engine and instead mis-executes
    // on the normal fast path (wrong store address, not a trap). This mirrors the
    // ucMiDstEa/ucDstEaWords shift fix (task #119) applied further down for the µcode
    // engine's OWN entry re-decode — duplicated locally here (rather than reordering ~80
    // lines to hoist ucMiDstEa above this point) because THIS classification is what gates
    // entry into the engine in the first place. Repro: move_l_abs_memind_dst.s (MOVE.L
    // (abs).W src -> ([bd.W,An],od) memind dst, no index) — now correctly detected.
    val s0dstModeIsFullCandidate = s0IsMove &&
      ((s0opw(8 downto 6) === B"110") || ((s0opw(8 downto 6) === B"111") && (s0opw(11 downto 9) === B"011")))
    def s0miWordsOf(extW: Bits): UInt = {
      val bdSize  = extW(5 downto 4).asUInt
      val bdWords = Mux(bdSize === U(2, 2 bits), U(1, 3 bits), Mux(bdSize === U(3, 2 bits), U(2, 3 bits), U(0, 3 bits)))
      val odWords = Mux(!extW(1), U(0, 3 bits), Mux(extW(0), U(2, 3 bits), U(1, 3 bits)))
      (U(1, 3 bits) + bdWords + odWords).resize(3)
    }
    val s0srcModeF = s0opw(5 downto 3).asUInt
    val s0srcRegF  = s0opw(2 downto 0).asUInt
    val s0srcWordCount = UInt(3 bits)
    s0srcWordCount := 0
    switch(s0srcModeF) {
      is(U(5, 3 bits)) { s0srcWordCount := 1 }                         // (d16,An)
      is(U(6, 3 bits)) { s0srcWordCount := Mux(s0pkt.words(1)(8), s0miWordsOf(s0pkt.words(1)), U(1, 3 bits)) }
      is(U(7, 3 bits)) {
        switch(s0srcRegF) {
          is(U(0, 3 bits)) { s0srcWordCount := 1 }                     // (xxx).W
          is(U(1, 3 bits)) { s0srcWordCount := 2 }                     // (xxx).L
          is(U(2, 3 bits)) { s0srcWordCount := 1 }                     // (d16,PC)
          is(U(3, 3 bits)) { s0srcWordCount := Mux(s0pkt.words(1)(8), s0miWordsOf(s0pkt.words(1)), U(1, 3 bits)) }
          is(U(4, 3 bits)) { s0srcWordCount := Mux(spec0.size === Size.LONG, U(2, 3 bits), U(1, 3 bits)) }  // #imm
        }
      }
    }
    def s0WordAtDyn(idx: UInt): Bits = {
      val out = Bits(16 bits); out := B(0, 16 bits)
      switch(idx) { for (i <- 0 until s0pkt.words.length) { is(U(i, idx.getWidth bits)) { out := s0pkt.words(i) } } }
      out
    }
    val s0dstExtW0Shifted = s0WordAtDyn((U(1, 5 bits) + s0srcWordCount.resize(5)).resize(5))
    val s0dstIsMemIndShifted = s0dstModeIsFullCandidate && s0dstExtW0Shifted(8) &&
                               (s0dstExtW0Shifted(2 downto 0).asUInt =/= U(0, 3 bits))
    val slot0IsMemInd = fed.valid && (
      ((s0IsMove && (s0srcEa.klass === EaClass.MEMINDIRECT))) ||
      ((s0IsMove && ((s0dstEa.klass === EaClass.MEMINDIRECT) || s0dstIsMemIndShifted))) ||
      (s0IsAluSrcLine && s0AluSrcMode && (s0srcEa.klass === EaClass.MEMINDIRECT)) ||
      (s0IsAluSrcLine && s0AluDstMode && (s0srcEa.klass === EaClass.MEMINDIRECT)) ||
      (s0IsAddqSubq && (s0srcEa.klass === EaClass.MEMINDIRECT)) ||
      // task #153: the .L-imm case (s0ImmIsL) is NOW routed too -- predecode correctly
      // frames it (extW3), so s0ImmEa's klass is trustworthy for it exactly like .B/.W.
      (s0IsLineImm && (s0ImmEa.klass === EaClass.MEMINDIRECT)) ||
      (s0IsSingleEa && (s0srcEa.klass === EaClass.MEMINDIRECT)))
    // ── Bit-field DYNAMIC read-only MEMORY detection (slice 3c) ──────────────────
    // BFTST/BFEXTU/BFEXTS/BFFFO at a memory EA with Do(ext[11])||Dw(ext[5]) set route through
    // the µcode engine (the static-offset/width read-only forms keep the 3a MicroOpAssembler
    // crack). OperationDecoder names them BITFIELD (NOT microcoded — the RMW forms are), so we
    // detect them here off the bf-ext word + the EA decode, exactly like slot0IsMemInd. The 3a
    // crack output (which misreads Do/Dw as static) is discarded by the head gate. Scoped to An-
    // base EAs (MEMSIMPLE, no auto, baseValid) — abs/PC-rel dynamic deferred (stay on 3a).
    val s0bfExt    = s0pkt.words(1)
    val s0bfDynM   = s0bfExt(11) || s0bfExt(5)
    // All four read-only ops route: BFTST(0)/BFEXTU(1)/BFEXTS(3)/BFFFO(5). BFFFO rides the
    // FFOFULL redesign (prefunnel -> one committed bfMem funnel carrying Dn2+NZ; no
    // flag-carrying trailing ADD -> X untouched by construction).
    val s0bfRdOnly = (spec0.bfOp === 0) || (spec0.bfOp === 1) || (spec0.bfOp === 3) || (spec0.bfOp === 5)
    val s0bfEa     = EaDecoder.decode(s0opw(5 downto 0), Size.LONG,
                                      Vec(s0opw, s0pkt.words(2), s0pkt.words(3)))
    val s0bfEaOk   = (s0bfEa.klass === EaClass.MEMSIMPLE) && (s0bfEa.autoMode === EaAuto.NONE) && s0bfEa.baseValid
    val slot0IsBfDynMem = fed.valid && (spec0.op === DecOp.BITFIELD) && !spec0.microcoded &&
                          s0bfRdOnly && s0bfDynM && s0bfEaOk
    // A slot0 owned by the µcode engine: an OperationDecoder-microcoded op OR a full-format
    // mem-indirect host OR a dynamic read-only bit-field (all enter the engine, off the fast head).
    val slot0OwnedByUc = slot0IsMicrocoded || slot0IsMemInd || slot0IsBfDynMem
    // µcode engine state (declared early — referenced by normalHeadValid below). The
    // sequencer logic + transitions live in the µcode SEQUENCER region further down.
    val ucActive    = RegInit(False)
    val ucPendValid = RegInit(False)
    ucActive.simPublic(); ucPendValid.simPublic()
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
    val normalHeadValid = stashValid || (fed.valid && !slot0IsMovem && !slot0OwnedByUc && !slot0IsMovep && !movemPendValid && !ucPendValid && !ucActive && !movepPendValid && !movepActive)

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
    val ucPc     = Reg(UInt(7 bits))   // µPC into the ROM (romSize 87 with 3c dyn-mem -> needs 7 bits)
    ucPc.simPublic()
    val ucCtx    = Reg(Microcode.Ctx())
    ucCtx.miOther.simPublic(); ucCtx.miOtherValid.simPublic()  // debug-only (task #144)
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
    ucEntryPkt.pc.simPublic(); ucEntryPkt.words(0).simPublic()  // debug-only (task #144)

    // Begin a µcode op: a pending slot1 microcoded op, OR a microcoded slot0 (not blocked
    // by a stash), while the engine + the MOVEM FSM are idle.
    val ucBegin = !ucActive && !movemActive && !movemPendValid && !movepActive && !movepPendValid &&
                  (ucPendValid || (slot0OwnedByUc && !stashValid))
    // Entering a slot0 microcoded op (not a pending one): its group is consumed on entry.
    val ucEnterSlot0 = ucBegin && !ucPendValid
    ucEnterSlot0.simPublic()  // debug-only (task #144)
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
    // ── DYNAMIC offset/width (slice 3c) ─────────────────────────────────────────
    val ucBfDo      = ucBfExt(11)                              // offset is dynamic (Dn[off])
    val ucBfDw      = ucBfExt(5)                               // width  is dynamic (Dn[wd])
    val ucBfOffDn   = ucBfExt(8 downto 6).asUInt.resize(5)     // Dn[off]
    val ucBfWdDn    = ucBfExt(2 downto 0).asUInt.resize(5)     // Dn[wd]
    // BFRESOLVE imm: [4:0]=staticOff, [9:5]=staticWidth, [10]=Do, [11]=Dw, [12]=memMode(1).
    val ucBfResImm  = (B(0, 19 bits) ## True ## ucBfDw ## ucBfDo ##
                       ucBfWidthRaw.asBits.resize(5) ## ucBfOffset5.asBits.resize(5)).resize(32)
    // byteOff folds into the disp ONLY when the offset is STATIC (Do=0); for Do=1 the runtime
    // byteDelta = Dn[off]>>>3 is added to eaBase in a pre-µop (the load base = Tb), so the disp
    // stays the raw EA disp.
    val ucBfByteOff = Mux(ucBfDo, U(0, 5 bits), (ucBfOffset5 >> 3).resize(5))   // 0..3 (folded into disp)
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
    ucEntryCtx.bfOffDn      := ucBfOffDn
    ucEntryCtx.bfWdDn       := ucBfWdDn
    ucEntryCtx.bfDo         := ucBfDo
    ucEntryCtx.bfDw         := ucBfDw
    ucEntryCtx.bfResImm     := ucBfResImm
    // ── CAS / CAS2 Ctx population (the ext words; OperationDecoder is ext-word-free) ──
    // CAS  `0000 1ss0 11 mmm rrr` + ext1: Dc=ext1[2:0], Du=ext1[8:6]; the EA (op[5:0],
    // memory-alterable control) is decoded from the SHIFTED window (the EA ext FOLLOWS
    // ext1, so words(2..) hold it). CAS2 `...111100` + ext1 + ext2: Rn1=ext1[15:12]
    // (REG_DA), Du1=ext1[8:6], Dc1=ext1[2:0], Da1=ext1[15]; ext2 -> Rn2/Du2/Dc2/Da2.
    val ucCasExt1 = ucEntryPkt.words(1)
    val ucCasExt2 = ucEntryPkt.words(2)
    val ucIsCas   = ucEntrySpec.microcoded && (ucEntrySpec.op === DecOp.CASOP)
    val ucIsCas2  = ucIsCas && (ucEntryPkt.words(0)(5 downto 0) === B"111100")
    // CAS EA from the shifted vector (opword + the words AFTER ext1).
    val ucCasEaDec = EaDecoder.decode(ucEntryPkt.words(0)(5 downto 0), ucEntrySpec.size,
                                      Vec(ucEntryPkt.words(0), ucEntryPkt.words(2), ucEntryPkt.words(3)))
    ucEntryCtx.casDc   := ucCasExt1(2 downto 0).asUInt.resize(5)              // Dn 0..7
    ucEntryCtx.casDu   := ucCasExt1(8 downto 6).asUInt.resize(5)              // Dn 0..7
    ucEntryCtx.cas2Rn1 := ucCasExt1(15 downto 12).asUInt.resize(5)           // REG_DA 0..15
    ucEntryCtx.cas2Du1 := ucCasExt1(8 downto 6).asUInt.resize(5)
    ucEntryCtx.cas2Dc1 := ucCasExt1(2 downto 0).asUInt.resize(5)
    ucEntryCtx.cas2Da1 := ucCasExt1(15)                                       // BIT_1F
    ucEntryCtx.cas2Rn2 := ucCasExt2(15 downto 12).asUInt.resize(5)
    ucEntryCtx.cas2Du2 := ucCasExt2(8 downto 6).asUInt.resize(5)
    ucEntryCtx.cas2Dc2 := ucCasExt2(2 downto 0).asUInt.resize(5)
    ucEntryCtx.cas2Da2 := ucCasExt2(15)                                       // BIT_F
    // CAS (An)+/-(An) auto side effect: carried on the LOAD + STORE (same address) + the An
    // write-back rides the STORE. NONE for the control modes (no side effect). EaDecoder set
    // autoMode/autoDelta for modes 3/4 (the A7-byte even rule folded in autoDelta).
    ucEntryCtx.casAutoMode  := ucCasEaDec.autoMode
    ucEntryCtx.casAutoDelta := ucCasEaDec.autoDelta
    // ── MOVES Ctx population (spec §5) ────────────────────────────────────────────
    // MOVES `0000 1110 ss mmm rrr` + ext1 + EA ext. ext1: dr=ext[11], A/D=ext[15],
    // reg=ext[14:12]. movesRn = REG_DA[ext15:12] (0..15). The EA reuses the CAS EA decode
    // (ucCasEaDec: the memory-alterable EA from the shifted window; auto modes populate
    // casAutoMode/Delta above). movesDelta = the SIGNED An write-back (+size POSTINC /
    // -size PREDEC / 0) for the READ-form An update µop. The signed byte count from the EA
    // auto delta (already A7-byte-even-corrected by EaDecoder). needsSup defaults False;
    // True only for a MOVES entry (the privilege trap, set on the first µop in resolve).
    val ucMovesExt = ucEntryPkt.words(1)
    val ucMovesRn  = ucMovesExt(15 downto 12).asUInt.resize(5)   // REG_DA 0..15 (A/D ## reg)
    ucEntryCtx.movesRn    := ucMovesRn
    ucEntryCtx.movesRnIsA := ucMovesExt(15)                      // 1 = An (sign-extend on read)
    // signed An delta: POSTINC -> +autoDelta ; PREDEC -> -autoDelta ; NONE -> 0.
    val ucMovesAutoDelta32 = ucCasEaDec.autoDelta.resize(32).asSInt
    ucEntryCtx.movesDelta := ucCasEaDec.autoMode.mux(
      EaAuto.POSTINC -> ucMovesAutoDelta32.asBits,
      EaAuto.PREDEC  -> (-ucMovesAutoDelta32).asBits,
      default        -> B(0, 32 bits))
    val ucIsMoves = ucEntrySpec.microcoded && (ucEntrySpec.op === DecOp.MOVES)
    ucEntryCtx.needsSup := ucIsMoves
    // ── FULL-format MEMORY-INDIRECT host-op Ctx population (spec §5) ──────────────
    // A general EA-taking op (MOVE/ALU/imm/single-EA) whose EA is a full-format memory-
    // indirect mode routes through the engine: [LOAD.L pointer -> T0] then the host op at
    // (T0 + od (+post-index)). OperationDecoder is ext-word-free + the host op is a NORMAL
    // op (not pre-marked microcoded), so the routing is decided HERE from the re-decoded EA.
    val ucEopw    = ucEntryPkt.words(0)
    val ucMiSrcEa = EaDecoder.decode(ucEopw(5 downto 0), ucEntrySpec.size, ucEntryPkt.words)
    // task #119 (deep-audit follow-up): the destination's own ext-word data does NOT
    // unconditionally start at words(1) — MOVE's extension-word ORDER is always
    // src-then-dst, so the dst's ext data starts wherever the SOURCE's own ext words
    // end. This was previously masked: every op family that consumes `ucMiDstEa` paired
    // it with a REGISTER-direct source (0 ext words), so the unshifted read happened to
    // be correct by coincidence. Now that a non-register (plain-memory / mem-indirect)
    // source is possible, the shift is REQUIRED — and is a no-op (0 words) for every
    // previously-supported register-direct-source case, so this is a strict, backward-
    // compatible generalization, not a new special case.
    //
    // The mem-indirect base ext word's own bd/od-size fields (mirrors EaDecoder's
    // fBdSize/fOdPresent/fOdLong extraction, EaDecoder.scala:76-96) — used to count a
    // FULL-FORMAT source's own word count (1 base + bd words + od words, up to 5).
    def miEaWordCount(baseExtW: Bits): UInt = {
      val bdSize    = baseExtW(5 downto 4).asUInt
      val bdWords   = Mux(bdSize === U(2, 2 bits), U(1, 3 bits),
                       Mux(bdSize === U(3, 2 bits), U(2, 3 bits), U(0, 3 bits)))
      val odPresent = baseExtW(1)
      val odLong    = baseExtW(0)
      val odWords   = Mux(!odPresent, U(0, 3 bits), Mux(odLong, U(2, 3 bits), U(1, 3 bits)))
      (U(1, 3 bits) + bdWords + odWords).resize(3)
    }
    // General per-mode EA word count (0/1/2 for the plain modes; the full-format
    // formula above for mode 6 / mode 7-3 when bit8 is set). Computed directly against
    // the ALREADY-FRAMED full packet — no predecode lookahead limit, unlike
    // PredecodeWord.eaExt (which this mirrors structurally).
    def eaWordCount(mode: UInt, reg: UInt): UInt = {
      val n = UInt(3 bits); n := 0
      switch(mode) {
        is(U(5, 3 bits)) { n := 1 }                                  // (d16,An)
        is(U(6, 3 bits)) {                                           // (d8,An,Xn) brief / full-format
          when(ucEntryPkt.words(1)(8)) { n := miEaWordCount(ucEntryPkt.words(1)) }
            .otherwise { n := 1 }
        }
        is(U(7, 3 bits)) {
          switch(reg) {
            is(U(0, 3 bits)) { n := 1 }   // (xxx).W
            is(U(1, 3 bits)) { n := 2 }   // (xxx).L
            is(U(2, 3 bits)) { n := 1 }   // (d16,PC)
            is(U(3, 3 bits)) {            // (d8,PC,Xn) brief / full-format
              when(ucEntryPkt.words(1)(8)) { n := miEaWordCount(ucEntryPkt.words(1)) }
                .otherwise { n := 1 }
            }
            is(U(4, 3 bits)) { n := Mux(ucEntrySpec.size === Size.LONG, U(2, 3 bits), U(1, 3 bits)) }  // #imm
          }
        }
      }
      n   // default 0: Dn/An/(An)/(An)+/-(An)
    }
    // Dynamic (runtime-indexed) word read, bounded by the Vec length (mirrors
    // EaDecoder.fOdWordAt's pattern).
    def wordAtDyn(words: Vec[Bits], idx: UInt): Bits = {
      val out = Bits(16 bits); out := B(0, 16 bits)
      switch(idx) {
        for (i <- 0 until words.length) { is(U(i, idx.getWidth bits)) { out := words(i) } }
      }
      out
    }
    val ucMoveSrcWordCount = eaWordCount(ucEopw(5 downto 3).asUInt, ucEopw(2 downto 0).asUInt)
    // Shifted view for the destination's EA decode: index 0 unused (EaDecoder.decode
    // never reads it), indices 1..5 = words(1+shift .. 5+shift) (covers the full-format
    // fOdWordAt range, bd=LONG+od=LONG needing relative index up to 5).
    val ucDstEaWords = Vec.tabulate(6) { i =>
      if (i == 0) B(0, 16 bits)
      else wordAtDyn(ucEntryPkt.words, (U(i, 5 bits) + ucMoveSrcWordCount.resize(5)).resize(5))
    }
    val ucMiDstEa = EaDecoder.decode(ucEopw(8 downto 6) ## ucEopw(11 downto 9),
                                     ucEntrySpec.size, ucDstEaWords)
    val ucLine    = ucEopw(15 downto 12).asUInt
    val ucOpmode  = ucEopw(8 downto 6).asUInt
    // The host op + which EA carries the mem-indirect. The in-scope host families:
    //   MOVE (line 1/2/3): src op[5:0] (load to Dn) OR dst op[11:6] (store from Dn).
    //   ALU src (line 8/9/B/C/D, opmode 0/1/2 = <ea>,Dn ; CMP opmode 0/1/2): src op[5:0].
    //   line-0 immediate op (ADDI/SUBI/ANDI/ORI/EORI/CMPI): dst op[5:0] (RMW; CMPI flags-only).
    //   single-EA (CLR/NEG/NEGX/NOT/TST, line-4): dst op[5:0] (RMW; TST flags-only).
    val ucIsMove   = (ucLine === U(1, 4 bits)) || (ucLine === U(2, 4 bits)) || (ucLine === U(3, 4 bits))
    val ucMoveSrcMi= ucIsMove && (ucMiSrcEa.klass === EaClass.MEMINDIRECT)
    val ucMoveDstMi= ucIsMove && (ucMiDstEa.klass === EaClass.MEMINDIRECT)
    // task #119 (deep-audit follow-up): a mem-indirect MOVE whose OTHER side is NOT a
    // register (newly reachable once F2's predecode fix let a 7+-word dual-full-EA MOVE
    // frame correctly at all — previously it just livelocked before execution got this
    // far). Distinguish register-direct (Dn=000/An=001 mode) from everything else.
    val ucMoveDstModeIsReg = ucIsMove && ((ucEopw(8 downto 6) === B"000") || (ucEopw(8 downto 6) === B"001"))
    val ucMoveSrcModeIsReg = ucIsMove && ((ucEopw(5 downto 3) === B"000") || (ucEopw(5 downto 3) === B"001"))
    val ucMoveSrcMiEaEa = ucMoveSrcMi && !ucMoveDstModeIsReg && !ucMoveDstMi  // src=MI, dst=plain memory
    // dst=MI, src=plain memory (the MIRROR direction): a `MI_MOVE_EAEA_REV_ENTRY` chain
    // exists in the ROM for this shape. Previously left unwired ("store lands at the
    // wrong address" per an earlier debugging session, not root-caused at the time) —
    // task #147 (ported-tests triage) root-caused it: the earlier failure predated the
    // ucDstEaWords word-count SHIFT (task #119, added for the forward MI_MOVE_EAEA
    // direction's dst-side re-decode) ever being re-validated against THIS reverse
    // direction. ucMiDstEa (the pointer side here) already reads through that same
    // shifted-words decode regardless of direction, and ucMiOtherEa's Mux already
    // selects ucMiSrcEa (the plain side) when ucMoveDstMiEaEa is true — both were
    // already correct, just never wired live. Verified via move_l_abs_memind_dst.s
    // (abs-src -> memind-dst no-index): now PASSes.
    val ucMoveDstMiEaEa = ucMoveDstMi && !ucMoveSrcModeIsReg && !ucMoveSrcMi
    val ucMoveBothMi    = ucMoveSrcMi && ucMoveDstMi   // both sides mem-indirect -> scoped-out illegal (below)
    // (ucMoveDstMiEaEa/ucMoveSrcMiEaEa/ucMoveBothMi are already simPublic'd further
    // below, in the existing "debug-only observability (task #139...)" block.)
    // The mem-indirect side's counterpart EA (the plain, non-register side) for the EA<->EA
    // chains: whichever of src/dst is NOT the pointer-load target. Computed unconditionally
    // (mirrors the movesRn/bit-field-EA ctx groups elsewhere in this function) — harmless
    // when unused, since only the new MI_MOVE_EAEA*/rows read ctx.miOtherEa*.
    val ucMiOtherEa = Mux(ucMoveDstMiEaEa, ucMiSrcEa, ucMiDstEa)
    // ALU/CMP source (opmode 0/1/2): line 8/9/B/C/D with the EA as a source operand.
    val ucIsAluSrcLine = (ucLine === U(8, 4 bits)) || (ucLine === U(9, 4 bits)) ||
                         (ucLine === U(0xB, 4 bits)) || (ucLine === U(0xC, 4 bits)) ||
                         (ucLine === U(0xD, 4 bits))
    // ADDA/SUBA/CMPA (opmode 3/7, An-dest arithmetic) mirror s0AnArith/s1mi_anArith above —
    // see s0AnArith's comment (task #144 follow-up) for why this is needed and why it does
    // not affect DIVU/DIVS (line 8) / MULU/MULS (line C), whose opmode 3/7 resolve to a
    // different DecOp (not ADD/SUB/CMP).
    val ucAnArith = (ucEntrySpec.dst.kind === OperandKind.REGFIELD) && ucEntrySpec.dst.isAddr &&
                    ((ucEntrySpec.op === DecOp.ADD) || (ucEntrySpec.op === DecOp.SUB) || (ucEntrySpec.op === DecOp.CMP))
    val ucAluSrcMode   = (ucOpmode === U(0, 3 bits)) || (ucOpmode === U(1, 3 bits)) || (ucOpmode === U(2, 3 bits)) ||
                         (ucAnArith && ((ucOpmode === U(3, 3 bits)) || (ucOpmode === U(7, 3 bits))))
    val ucAluSrcMi = ucIsAluSrcLine && ucAluSrcMode && (ucMiSrcEa.klass === EaClass.MEMINDIRECT)
    // ALU Dn,<ea> RMW dst-EA (OR/SUB/AND/ADD/EOR opmode 4/5/6 -- `Dn op <ea> -> <ea>`,
    // OperationDecoder's "ALU Dn,<ea> RMW" arm @ line ~791 + the EOR arm @ ~782): the
    // SAME op[5:0] EA field as the ALU-src form above, just read/written instead of
    // only read. Task #150 (ported-tests memind cluster, add_l_dn_memind_dst): this
    // shape had NO classifier reaching ucIsMemInd at all -- ucAluSrcMi is gated to
    // ucAluSrcMode (opmode 0/1/2 only), so a full-format mem-indirect RMW dst-EA
    // (opmode 4/5/6) fell all the way through to the ordinary non-microcoded fast
    // path with a garbage EA, producing a wild PC (same class of bug as #144/#145).
    // Opmode 4/5/6 on lines 8/9/B/C/D is EXCLUSIVELY this Dn-op-mem RMW form when the
    // ea klass is MEMINDIRECT (mode 6 / mode-7-reg-3 full-format ext) -- the other
    // opmode-4/5/6 encodings sharing this opcode space (ADDX/SUBX reg, ABCD/SBCD,
    // EXG, PACK/UNPK) are all register-DIRECT (mode 000/001), which can never
    // classify as MEMINDIRECT, so this condition cannot misfire onto them.
    val ucAluDstMode = (ucOpmode === U(4, 3 bits)) || (ucOpmode === U(5, 3 bits)) || (ucOpmode === U(6, 3 bits))
    val ucAluDstMi = ucIsAluSrcLine && ucAluDstMode && (ucMiSrcEa.klass === EaClass.MEMINDIRECT)
    // ADDQ/SUBQ #n,<ea> (line 5, `0101 ddd q ss mmmrrr`, ss=/=11): another dst-EA RMW
    // form (task #150 follow-up, b2_addq_subq_memind_null_od) sharing the SAME gap as
    // ucAluDstMi above -- OperationDecoder names it srcB.kind=IMMQ3 (distinct from the
    // line-0 IMMEXT immediate ucImmDstMi below), so neither existing classifier caught
    // it. The "other operand" is the 3-bit quick immediate (op[11:9], 0 means 8), not a
    // register -- reuses the SAME miOtherIsImm/miHostImm immediate-feed mechanism the
    // line-0 immediate family already uses (SMiOther resolves to the imm when
    // miOtherIsImm is set), just with a different immediate SOURCE (opword bits, not an
    // ext word).
    val ucIsAddqSubq = ucEntrySpec.srcB.kind === OperandKind.IMMQ3
    val ucAddqSubqMi = ucIsAddqSubq && (ucMiSrcEa.klass === EaClass.MEMINDIRECT)
    val ucAddqSubqQuick = ucEopw(11 downto 9).asUInt
    val ucAddqSubqImm = Mux(ucAddqSubqQuick === U(0, 3 bits), U(8, 32 bits), ucAddqSubqQuick.resize(32 bits)).asBits
    // line-0 immediate op dst-EA (ADDI/SUBI/ANDI/ORI/EORI/CMPI #imm,<ea>): the immediate
    // PRECEDES the EA ext, so re-decode the EA from a SHIFTED window. immWords = .L?2:1.
    // op[7:6] is a SIZE field (ss) for ADDI/SUBI/.../CMPI, but the SAME bit position is the
    // tt bit-op sub-kind (00 BTST/01 BCHG/10 BCLR/11 BSET) for a static bit op (task #152:
    // an unguarded check here misfired "true" for BCLR (tt=10 happens to equal ss=.L),
    // mis-shifting its EA re-decode and silently mis-detecting it as NOT mem-indirect --
    // it fell through to the ordinary fast path instead of the µcode engine). A static bit
    // op's immediate (the bit-number ext word) is ALWAYS exactly 1 word, so it can never be
    // the .L-immediate shape -> unconditionally excluded here.
    val ucImmIsL   = (ucEntrySpec.op =/= DecOp.BITOP) && (ucEopw(7 downto 6) === B"10")
    val ucImmWords = Mux(ucImmIsL, U(2, 3 bits), U(1, 3 bits))
    val ucImmEaVec = Mux(ucImmIsL, Vec(ucEopw, ucEntryPkt.words(3), ucEntryPkt.words(4), ucEntryPkt.words(5)),
                                   Vec(ucEopw, ucEntryPkt.words(2), ucEntryPkt.words(3), ucEntryPkt.words(4)))
    val ucImmEa    = EaDecoder.decode(ucEopw(5 downto 0), ucEntrySpec.size, ucImmEaVec)
    val ucIsLineImm= ucEntrySpec.srcB.kind === OperandKind.IMMEXT
    val ucImmDstMi = ucIsLineImm && (ucImmEa.klass === EaClass.MEMINDIRECT)
    // single-EA op (CLR/NEG/NEGX/NOT/TST): the EA is op[5:0], the host op is spec.op.
    val ucIsSingleEa = (ucEntrySpec.op === DecOp.CLR) || (ucEntrySpec.op === DecOp.NEG) ||
                       (ucEntrySpec.op === DecOp.NEGX) || (ucEntrySpec.op === DecOp.NOT) ||
                       (ucEntrySpec.op === DecOp.TST)
    val ucSingleMi = ucIsSingleEa && (ucMiSrcEa.klass === EaClass.MEMINDIRECT)
    // The chosen mem-indirect EaSpec (the pointer load's base/bd/index + od/post).
    val ucMiEa = Mux(ucMoveDstMi, ucMiDstEa, Mux(ucImmDstMi, ucImmEa, ucMiSrcEa))
    // The op IS a full-format mem-indirect host (route to the engine). The entry packet
    // is valid either because it's the correctly-latched stash (ucPendValid) or because
    // it's this cycle's live fed packet -- gating on bare fed.valid alone was WRONG (task
    // #144: on the stashed-slot1 path, this cycle's live fed.valid is unrelated to the
    // stashed packet's validity and can independently be false, e.g. a bubble, silently
    // zeroing ucIsMemInd and misrouting the µcode entry to the wrong default row).
    val ucIsMemInd = (ucMoveSrcMi || ucMoveDstMi || ucAluSrcMi || ucAluDstMi || ucAddqSubqMi || ucImmDstMi || ucSingleMi) &&
                     (ucPendValid || fed.valid)
    // The host op's OTHER operand register:
    //   MOVE src-EA (load to a reg)  -> the dst reg  = op[11:9] (Dn) / +8 for An (isMovea n/a here).
    //   MOVE dst-EA (store from reg) -> the src reg  = op[5:0] (Dn/An, register-direct src).
    //   ALU src-EA                   -> the other Dn = op[11:9].
    // For a register-direct MOVE src/dst, the full reg id includes the An bit (mode 001).
    val ucMoveSrcReg = ucEopw(2 downto 0).asUInt   // MOVE src field (the store-data reg for dst-EA)
    val ucMoveSrcAn  = ucEopw(5 downto 3) === B"001"
    // MOVEA (a MOVE whose DST mode field op[8:6] is An-direct 001) with a mem-indirect
    // SOURCE: the dst register is an ADDRESS reg (+8), the host MOVE µop is isMovea
    // (full-32 An write, .W sign-extend) and writes NO CCR (FUZZER-CAUGHT: the crack
    // set NZVC and targeted the Dn register file half).
    val ucMoveDstIsAn = ucIsMove && (ucEopw(8 downto 6) === B"001")
    // ucMiMovea ("An-wide" marker) also covers ADDA/SUBA/CMPA-with-mem-indirect-source
    // (ucAnArith, task #144 follow-up): same full-32/.W-sign-extend widening the ALU EU
    // already applies via `anWide := isMovea && op=/=MOVE` (AluEuPlugin.scala) for the
    // ordinary (non-mem-indirect) ADDA/SUBA/CMPA path. miWNzvc below discriminates the
    // CMPA-writes-flags case from the ADDA/SUBA/MOVEA-writes-no-flags case.
    val ucMiMovea     = (ucMoveSrcMi && ucMoveDstIsAn) || ucAnArith
    val ucMiOtherReg = Mux(ucMoveDstMi,
                           Mux(ucMoveSrcAn, (U(8, 5 bits) + ucMoveSrcReg).resize(5), ucMoveSrcReg.resize(5)),
                           Mux(ucMiMovea, (U(8, 5 bits) + ucEopw(11 downto 9).asUInt).resize(5),
                               ucEopw(11 downto 9).asUInt.resize(5)))   // Dn for MOVE-src/ALU; An for MOVEA/ADDA-src
    // CMPI / TST -> flags-only (no store). The op writes NZVC and (ADD/SUB/NEG/NEGX) X.
    val ucMiOpIsCmp = ucEntrySpec.op === DecOp.CMP
    val ucMiOpIsTst = ucEntrySpec.op === DecOp.TST
    // BTST (BITOP tt==00) is ALSO flags-only -- it tests but never writes memory (unlike
    // BCHG/BCLR/BSET, tt 01/10/11, which are real RMW). Task #152 follow-up: without this,
    // BTST via the mem-indirect engine still issued a (harmless but real, non-architectural)
    // store of the read-back-unchanged value through MI_RMW_ENTRY.
    val ucIsBitOp   = ucEntrySpec.op === DecOp.BITOP
    val ucMiOpIsBtst = ucIsBitOp && (ucEopw(7 downto 6) === B"00")
    val ucMiFlagsOnly = ucMiOpIsCmp || ucMiOpIsTst || ucMiOpIsBtst
    // Entry select: both-MI (illegal) / EA<->EA (task #119) / MOVE-src / MOVE-dst / ALU-src /
    // imm-RMW (or FLAGS) / single-EA RMW (or FLAGS). The EA<->EA + both-MI checks are
    // strictly narrower subsets of ucMoveSrcMi/ucMoveDstMi, so placing them FIRST in the
    // priority chain correctly carves them out before the register-case fallbacks below.
    val ew = ucEntrySpec.ucEntry.getWidth
    val ucMiEntry =
      Mux(ucMoveBothMi,    U(Microcode.MI_MOVE_BOTH_MI_ILLEGAL_ENTRY, ew bits),
      Mux(ucMoveSrcMiEaEa, U(Microcode.MI_MOVE_EAEA_ENTRY,     ew bits),
      Mux(ucMoveDstMiEaEa, U(Microcode.MI_MOVE_EAEA_REV_ENTRY, ew bits),
      Mux(ucMoveSrcMi, U(Microcode.MI_MOVE_SRC_ENTRY, ew bits),
      Mux(ucMoveDstMi, U(Microcode.MI_MOVE_DST_ENTRY, ew bits),
      Mux(ucAluSrcMi,  U(Microcode.MI_ALU_SRC_ENTRY,  ew bits),
      Mux(ucMiFlagsOnly, U(Microcode.MI_FLAGS_ENTRY, ew bits),
                         U(Microcode.MI_RMW_ENTRY,    ew bits))))))))
    // ---- debug-only observability (task #139 mechanism #2 investigation) ----
    // Zero synth impact (sim tap only, not referenced by any RTL logic).
    ucMiEntry.simPublic()
    ucIsMemInd.simPublic()
    ucLine.simPublic(); ucOpmode.simPublic()
    ucAluSrcMi.simPublic(); ucAluDstMi.simPublic(); ucAddqSubqMi.simPublic()
    ucMoveSrcMi.simPublic(); ucMoveDstMi.simPublic()
    ucMoveSrcMiEaEa.simPublic(); ucMoveDstMiEaEa.simPublic(); ucMoveBothMi.simPublic()
    ucMiFlagsOnly.simPublic()
    // Populate the MI Ctx group + (reuse the EA infra) the pointer-load EA fields. The host
    // size = spec.size; the pointer load is always LONG. od/post from the chosen EaSpec.
    ucEntryCtx.miOd         := ucMiEa.od
    ucEntryCtx.miPost       := ucMiEa.memPost
    ucEntryCtx.miOp         := ucEntrySpec.op
    // BITOP (BTST/BCHG/BCLR/BSET) tt sub-kind (task #152): OperationDecoder carries it as
    // opword[7:6] regardless of static/dynamic form (see OperationDecoder.scala's `tt`),
    // so it can be read directly off the opword here without any static/dynamic split.
    ucEntryCtx.miBitOp      := ucEopw(7 downto 6)
    // BITOP's size is dest-dependent (Dn -> LONG mod32, memory -> BYTE mod8) and
    // OperationDecoder deliberately leaves spec.size at its unrelated default (the bit-op
    // opword has no size field -- op[7:6] is the tt sub-kind) for the fast-path assembler
    // to resolve (MicroOpAssembler.scala's `bitOpSize`). The mem-indirect entry here reads
    // ucEntrySpec.size directly and never went through that resolution -- every mem-indirect
    // BITOP host access always targets MEMORY (the EA classified MEMINDIRECT), so BYTE is
    // unconditionally correct here (task #152; mirrors bitOpSize's `bitOpIsMem` case).
    // Without this, the host load over-read into ADJACENT memory (wrong size), and because
    // 68k is big-endian the loaded temp's low-order bits came from the WRONG byte -- the
    // subsequent bit-test/mask read a stale neighbor byte instead of the real target.
    ucEntryCtx.miHostSize   := Mux(ucIsBitOp, Size.BYTE, ucEntrySpec.size)
    ucEntryCtx.miIsDstEa    := ucMoveDstMi || ucImmDstMi || ucSingleMi || ucAluDstMi || ucAddqSubqMi
    ucEntryCtx.miIsRmw      := (ucImmDstMi || ucSingleMi || ucAluDstMi || ucAddqSubqMi) && !ucMiFlagsOnly
    ucEntryCtx.miOther      := ucMiOtherReg
    ucEntryCtx.miOtherValid := ucMoveSrcMi || ucMoveDstMi || ucAluSrcMi || ucAluDstMi || (ucImmDstMi && !ucIsSingleEa)
    ucEntryCtx.miOtherIsImm := ucImmDstMi || ucAddqSubqMi
    // The line-0 immediate VALUE precedes the EA ext: words(1) (.B/.W, sign-extended) or
    // words(1)##words(2) (.L). ADDQ/SUBQ's immediate is instead the 3-bit quick field
    // (op[11:9], 0 means 8) carried directly in the opword, no ext word. (Only consumed
    // when miOtherIsImm.)
    ucEntryCtx.miHostImm    := Mux(ucAddqSubqMi, ucAddqSubqImm,
                                Mux(ucImmIsL, ucEntryPkt.words(1) ## ucEntryPkt.words(2),
                                              ucEntryPkt.words(1).asSInt.resize(32).asBits))
    ucEntryCtx.miOtherIsDst := ucMoveSrcMi
    ucEntryCtx.miMovea      := ucMiMovea
    // Host op flag effects. All in-scope hosts (MOVE/ADD/SUB/AND/OR/EOR/CMP/CLR/NEG/NEGX/
    // NOT/TST) write NZVC — EXCEPT MOVEA (An dst), which never touches CCR. X is written
    // only by ADD/SUB/NEG/NEGX (not MOVE/logical/CMP/TST/CLR). NEGX additionally READS
    // old NZ (clear-only Z) + X.
    val ucMiWriteX    = (ucEntrySpec.op === DecOp.ADD) || (ucEntrySpec.op === DecOp.SUB) ||
                        (ucEntrySpec.op === DecOp.NEG) || (ucEntrySpec.op === DecOp.NEGX)
    // ADDA/SUBA/CMPA (ucAnArith): An-dest arithmetic never writes X, and NZVC is written
    // ONLY by CMPA (not ADDA/SUBA) — the plain `!ucMiMovea`/`ucMiWriteX` formulas below are
    // correct for the Dn-dest ALU-src case and the MOVEA case, but wrong for ucAnArith
    // (which reuses ucMiMovea for its OWN, different reason — the widening, not "no
    // flags"). Resolve from ucEntrySpec.writesNzvc directly for this case, which
    // OperationDecoder.scala already computes correctly (True only for CMPA, opmode 3/7
    // on line 0xB; False for ADDA/SUBA).
    ucEntryCtx.miWNzvc := Mux(ucAnArith, ucEntrySpec.writesNzvc, !ucMiMovea)
    ucEntryCtx.miWX    := Mux(ucAnArith, False, ucMiWriteX)
    ucEntryCtx.miRNzvc := ucEntrySpec.op === DecOp.NEGX   // NEGX reads old NZ (clear-only Z)
    ucEntryCtx.miRX    := ucEntrySpec.op === DecOp.NEGX
    // The EA<->EA plain side's OWN address (task #119): independent of the pointer-load's
    // eaBase/eaDispLo/eaIndex group above, which stays dedicated to whichever side IS
    // mem-indirect. Computed unconditionally (harmless when unused — only the new
    // MI_MOVE_EAEA*/rows' Desc entries read ctx.miOtherEa*).
    ucEntryCtx.miOtherEaBase       := ucMiOtherEa.base
    ucEntryCtx.miOtherEaBaseValid  := ucMiOtherEa.baseValid
    ucEntryCtx.miOtherEaIndexReg   := ucMiOtherEa.indexReg
    ucEntryCtx.miOtherEaIndexValid := ucMiOtherEa.indexValid
    ucEntryCtx.miOtherEaIndexLong  := ucMiOtherEa.indexLong
    ucEntryCtx.miOtherEaIndexScale := ucMiOtherEa.indexScale
    ucEntryCtx.miOtherEaDispLo     := ucMiOtherEa.disp
    // (An)+/-(An) auto-update on the "other" side (task #154): EaDecoder already computes
    // this correctly as part of the normal EaSpec decode, just never threaded into Ctx.
    ucEntryCtx.miOtherEaAutoMode   := ucMiOtherEa.autoMode
    ucEntryCtx.miOtherEaAutoDelta  := ucMiOtherEa.autoDelta
    // Pointer-load EA fields (reuse the bit-field EA infra). disp = bd (the EaSpec disp);
    // pcRel mem-indirect is rejected at decode (read-only EA), so no pc fold needed here.
    when(ucIsMemInd) {
      ucEntryCtx.eaBase       := ucMiEa.base
      ucEntryCtx.eaBaseValid  := ucMiEa.baseValid
      ucEntryCtx.eaIndexReg   := ucMiEa.indexReg
      ucEntryCtx.eaIndexValid := ucMiEa.indexValid
      ucEntryCtx.eaIndexLong  := ucMiEa.indexLong
      ucEntryCtx.eaIndexScale := ucMiEa.indexScale
      ucEntryCtx.eaDispLo     := ucMiEa.disp
    }
    // CAS (single address): the LOAD/STORE address is the memory-alterable EA. Override the
    // shared EA group with the CAS EA decode (mutually exclusive with bit-field/mem-indirect,
    // which are different opwords). CAS2 needs no eaBase override (Rn1/Rn2 are the addresses).
    when(ucIsCas && !ucIsCas2) {
      ucEntryCtx.eaBase       := ucCasEaDec.base
      ucEntryCtx.eaBaseValid  := ucCasEaDec.baseValid
      ucEntryCtx.eaIndexReg   := ucCasEaDec.indexReg
      ucEntryCtx.eaIndexValid := ucCasEaDec.indexValid
      ucEntryCtx.eaIndexLong  := ucCasEaDec.indexLong
      ucEntryCtx.eaIndexScale := ucCasEaDec.indexScale
      ucEntryCtx.eaDispLo     := ucCasEaDec.disp
    }
    // MOVES: the LOAD/STORE address is the memory-alterable EA (SAME decode infra as CAS).
    when(ucIsMoves) {
      ucEntryCtx.eaBase       := ucCasEaDec.base
      ucEntryCtx.eaBaseValid  := ucCasEaDec.baseValid
      ucEntryCtx.eaIndexReg   := ucCasEaDec.indexReg
      ucEntryCtx.eaIndexValid := ucCasEaDec.indexValid
      ucEntryCtx.eaIndexLong  := ucCasEaDec.indexLong
      ucEntryCtx.eaIndexScale := ucCasEaDec.indexScale
      ucEntryCtx.eaDispLo     := ucCasEaDec.disp
    }
    // debug-only observability (ported-tests triage: move_l_abs_memind_dst / MI_MOVE_EAEA_REV)
    ucEntryCtx.eaBase.simPublic(); ucEntryCtx.eaBaseValid.simPublic(); ucEntryCtx.eaDispLo.simPublic()
    ucEntryCtx.miOd.simPublic(); ucEntryCtx.miPost.simPublic()
    ucEntryCtx.miOtherEaBase.simPublic(); ucEntryCtx.miOtherEaBaseValid.simPublic()
    ucEntryCtx.miOtherEaDispLo.simPublic()

    // The REAL entry: a bit-field RMW (microcoded BITFIELD) picks 5B vs 4B by needHi; a
    // full-format mem-indirect host picks its shape entry; MOVES picks WRITE vs READ by the
    // ext-word dr bit (ext1[11]); every other microcoded op (BCD/ADDX/SUBX/CAS) keeps its
    // OperationDecoder ucEntry.
    val ucIsBfRmw  = ucEntrySpec.microcoded && (ucEntrySpec.op === DecOp.BITFIELD)
    val ucMovesDr  = ucMovesExt(11)        // 1 = WRITE (Rn -> ea) ; 0 = READ (ea -> Rn)
    val ucMovesEntry = Mux(ucMovesDr, U(Microcode.MOVES_WRITE_ENTRY, ew bits),
                                      U(Microcode.MOVES_READ_ENTRY,  ew bits))
    // Bit-field DYNAMIC read-only (slice 3c): NON-microcoded BITFIELD routed via slot0IsBfDynMem
    // (the static read-only forms keep the 3a crack). Do=0 -> RD_DO0 (byteBase folds; FFO too,
    // origOff=staticOff); Do=1 -> RD_DO1 (non-FFO) / FFO_DO1 (FFO emits index + a trailing add).
    val ucBfEntOp     = ucEntryPkt.words(0)(10 downto 8)
    val ucBfEntRdOnly = (ucBfEntOp === 0) || (ucBfEntOp === 1) || (ucBfEntOp === 3) || (ucBfEntOp === 5)
    val ucIsBfDynRd   = !ucEntrySpec.microcoded && (ucEntrySpec.op === DecOp.BITFIELD) &&
                        ucBfEntRdOnly && (ucBfDo || ucBfDw)
    val ucBfDynRdEntry = Mux(ucBfDo,
      Mux(ucBfEntOp === 5, U(Microcode.BF_DYN_FFO_DO1_ENTRY, ew bits),
                           U(Microcode.BF_DYN_RD_DO1_ENTRY,  ew bits)),
      U(Microcode.BF_DYN_RD_DO0_ENTRY, ew bits))
    // Bit-field RMW DYNAMIC (slice 3c): BFCHG(2)/BFCLR(4)/BFSET(6) with Do||Dw -> the dynamic
    // RMW entry (Do=1 recomputes byteBase; Do=0 folds). BFINS(7) dynamic -> the dedicated INS
    // entries (prefunnel -> register-form insert -> reload+inverse-funnel; X untouched).
    // Do=1 at an ABS EA (baseValid=False, (xxx).W/.L) is OUT OF SCOPE (the DO1 chains'
    // UBfAdd reads SEaBase as a REGISTER — an abs EA has none, so the byteBase add would
    // read garbage): route it to the vector-4 ILLEGAL entry (traps, NOT silent-wrong) —
    // mirroring the read-only path, where the !baseValid dynamic falls to the 3a bfmBad
    // illegal gate. Do=0 abs is IN scope (byteBase folds into the disp; the loads/stores
    // take the LS disp-only path exactly like the static 3b abs chain).
    val ucBfRmwOp   = ucEntryPkt.words(0)(10 downto 8)
    val ucIsBfRmwDyn = ucIsBfRmw && (ucBfDo || ucBfDw)
    val ucBfRmwDynEntry = Mux(ucBfDo && !ucBfEaDec.baseValid,
      U(Microcode.BF_DYN_ILLEGAL_ENTRY, ew bits),
      Mux(ucBfRmwOp === 7,
        Mux(ucBfDo, U(Microcode.BF_DYN_INS_DO1_ENTRY, ew bits),
                    U(Microcode.BF_DYN_INS_DO0_ENTRY, ew bits)),
        Mux(ucBfDo, U(Microcode.BF_DYN_RMW_DO1_ENTRY, ew bits),
                    U(Microcode.BF_DYN_RMW_DO0_ENTRY, ew bits))))
    val ucRealEntry = Mux(ucIsMemInd, ucMiEntry,
      Mux(ucIsBfDynRd, ucBfDynRdEntry,
      Mux(ucIsBfRmwDyn, ucBfRmwDynEntry,
      Mux(ucIsBfRmw,
        Mux(ucBfNeedHi, U(Microcode.BF_RMW_5B_ENTRY, ew bits),
                        U(Microcode.BF_RMW_4B_ENTRY, ew bits)),
      Mux(ucIsMoves, ucMovesEntry,
        ucEntrySpec.ucEntry)))))
    ucRealEntry.simPublic()  // debug-only (task #144)

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
    // ── sim-only debug probes (bf3c bring-up; TEMPORARY) ──
    pushReg.valid.simPublic(); pushReg.payload.count.simPublic()
    queue.io.push.ready.simPublic()
    for (i <- 0 until 4) {
      pushReg.payload.uops(i).pc.simPublic(); pushReg.payload.uops(i).faulted.simPublic()
      pushReg.payload.uops(i).faultVector.simPublic(); pushReg.payload.uops(i).firstOfInstr.simPublic()
    }
    fed.valid.simPublic(); fed.ready.simPublic()
    fed.payload.packets(0).pc.simPublic(); fed.payload.packets(1).pc.simPublic()
    fed.payload.packets(1).words(0).simPublic(); fed.payload.packets(1).words(1).simPublic()
    fed.payload.packets(1).words(2).simPublic(); fed.payload.packets(1).words(3).simPublic()
    fed.payload.packets(1).words(4).simPublic(); fed.payload.packets(1).wordCount.simPublic()
    fed.payload.packets(1).simple.simPublic(); fed.payload.packets(1).complex.simPublic()  // debug-only (task #144)
    fed.payload.packets(0).words(0).simPublic(); fed.payload.packets(0).words(1).simPublic()
    fed.payload.packets(0).simple.simPublic(); fed.payload.packets(0).lenWords.simPublic()
    fed.payload.packets(0).fault.simPublic(); fed.payload.packets(0).wordCount.simPublic()
    fed.payload.slot1Valid.simPublic()
    stashValid.simPublic(); normalHeadValid.simPublic(); ucBegin.simPublic()
    pipeFlush.simPublic()

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
    val ucHoldsFed    = ucActive || ucPendValid || slot0OwnedByUc
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
      } elsewhen(slot1IsUcodeEarly || slot1IsMemIndEarly || slot1IsBfDynMemEarly) {
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
        } elsewhen(slot1IsUcodeEarly || slot1IsMemIndEarly || slot1IsBfDynMemEarly) {
          // A µCODE-OWNED slot1 (CAS/MOVES/BCD-mem/mem-indirect/bf-dyn) behind a slot0
          // MOVEM must stash the PACKET for the engine (ucPend), NOT the a1raw µops —
          // the assembler's placeholder crack of a microcoded opword is an ILLEGAL/
          // benign-MOVE phantom that would replay as a spurious committed µop. The
          // ucBegin/movepBegin stash arms below already had this elsewhen; this arm was
          // MISSING it (latent phantom-commit bug for a {MOVEM, µcoded} fetch pair —
          // proven by the movem-ucode-s1 lock-step, which diverges without this arm).
          ucPendValid := True
          ucPendPkt   := fed.payload.packets(1)
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
        } elsewhen(slot1IsUcodeEarly || slot1IsMemIndEarly || slot1IsBfDynMemEarly) {
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
        } elsewhen(slot1IsUcodeEarly || slot1IsMemIndEarly || slot1IsBfDynMemEarly) {
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

    // ── A1 FIX (deep-audit 2026-07-11): flush-ordering phantom-commit ──────────────
    // `stashValid`/`movemPendValid`/`ucPendValid` are each SET from inside THREE different
    // FSM-entry blocks above (movemBegin/ucBegin/movepBegin can each stash a slot1 owned by
    // ANY of the other two engines, or a plain decoded-µop stash) as well as (stashValid
    // only) the normal fed-consume arm. Each of those signals ALSO has an early flush-clear
    // (stashValid @ its declaration; movemPendValid/ucPendValid a bit further down), but
    // that clear is textually BEFORE some of the later SET arms — and in SpinalHDL, multiple
    // drivers to the same signal resolve by SOURCE ORDER (the LAST `:=` wins), not by which
    // `when` "looks more specific". So a flush landing the SAME cycle as, say, ucBegin's
    // slot1-is-MOVEM stash (`movemPendValid := True` inside the ucBegin block, which is
    // textually AFTER movemPendValid's own early clear) left the pending marker VALID after
    // the flush — the stashed/pending wrong-path op then PHANTOM-COMMITS on replay, one
    // cycle after it should have been squashed. `movepPendValid` was accidentally immune:
    // its own late clear (right above, at the very end of the Area) happens to be the LAST
    // statement touching it, so it already wins over every SET arm.
    //
    // Fix: mirror movepPendValid's shape for the other three — an unconditional
    // `when(pipeFlush){ ... := False }` as the ABSOLUTE LAST statement in this Area touching
    // each signal, so it always wins regardless of which FSM-begin block set it this same
    // cycle. (The earlier per-signal flush-clears above are left in place — harmless,
    // redundant, and matches the existing movepPendValid style of a belt-and-suspenders
    // early clear plus an authoritative late one.)
    when(pipeFlush) {
      stashValid     := False
      movemPendValid := False
      ucPendValid    := False
      movepPendValid := False
    }

    // ── Rename-facing output ───────────────────────────────────────────────────
    val uopsOut = queue.io.pop
    val uop1Sig = queue.io.pop1Valid
  }

  override def uops: Stream[Vec[DecodedUop]] = logic.uopsOut
  override def uop1Valid: Bool               = logic.uop1Sig
}
