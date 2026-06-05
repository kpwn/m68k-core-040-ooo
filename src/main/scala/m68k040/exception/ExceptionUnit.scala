package m68k040.exception

import m68k040.cache.{DLoadCmd, DLoadRsp, DStoreCmd, TranslationReq, TranslationRsp}
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.fsm._

/** Commit-side exception sequencer (slice 1: format-$0 precise delivery + RTE).
  *
  * Instantiated inside the ROB's commit area. It is a SERIALIZING hardware FSM:
  * commit pauses while it runs (the ROB has already flushed younger work via the
  * registered redirect when the fault retired). Non-speculatively, at commit, it:
  *
  *  ENTRY (illegal / privilege fault):
  *    oldSr = (srSys << 8) | committedCcr
  *    srSys.S := 1 ; srSys.T := 0   (enter supervisor, clear trace)
  *    frameBase = SSP - 8           (A7 is banked to SSP since S becomes 1)
  *    stack format-$0 frame to frameBase (Musashi m68ki_stack_frame_0000 order):
  *      [base+0] = SR (16b, big-endian)         <- pushed LAST  (low addr)
  *      [base+2] = PC hi word                    }  push_32(pc)
  *      [base+4] = PC lo word                    }
  *      [base+6] = format/vector word = vec<<2   <- pushed FIRST (high addr)
  *    SSP := frameBase
  *    vec = load mem[VBR + vector*4]
  *    redirect fetch -> vec (supervisor)
  *
  *  RTE (return-from-exception, Task 4): pop the frame, restore SR (+ maybe bank
  *    A7 back to USP), restore PC, SSP += 8, redirect -> restored PC.
  *
  * The D-cache load/store ports + D-side translation request are exposed and
  * driven by the FSM; the full-core wiring MUXes them onto the real D-cache (the
  * LS EU is idle during a serializing exception). For these tests the MMU is off
  * (identity), so translation is a pass-through.
  */
class ExceptionUnit(
    val ss: SystemState,
    entryTrigger: Bool, entryVector: UInt, entryPc: UInt,
    rteTrigger: Bool, rtePc: UInt,
    committedCcr: UInt,
    // Access-fault (vector 2) extras for the format-$7 frame. Default-driven idle
    // (a DUT that does not supply them — e.g. the slice-1 format-$0 unit tests —
    // passes the defaults; the $7 path is selected only when entryVector === 2).
    entryFaultAddr: UInt = U(0, 32 bits),
    entryFaultWr:   Bool = False,
    entryFaultSup:  Bool = False,
    // Instruction-fetch access fault: build a PROGRAM-space SSW (vs data) and force
    // the R/W bit to read. Default False => data fault (unchanged for LS faults).
    entryFaultInstr: Bool = False) extends Area {

  // ── exposed D-cache request ports (wiring MUXes them onto the real cache) ────
  val dcLoadCmd  = Stream(DLoadCmd())
  val dcLoadRsp  = Flow(DLoadRsp())
  val dcLoadBusy = Bool()
  val dcStore    = Flow(DStoreCmd()); dcStore.simPublic()
  val dcStoreAck = Bool()
  // consumer-side inputs default (wiring drives them; allowOverride so a DUT that
  // does NOT wire the exception D-cache ports still elaborates — the wiring layer
  // OVERRIDES these when present).
  dcLoadCmd.ready.allowOverride;   dcLoadCmd.ready := False
  dcLoadRsp.valid.allowOverride;   dcLoadRsp.valid := False
  dcLoadRsp.payload.allowOverride; dcLoadRsp.payload.assignDontCare()
  dcLoadBusy.allowOverride;        dcLoadBusy := False
  dcStoreAck.allowOverride;        dcStoreAck := False

  // ── exposed D-side translation request (wiring MUXes it onto DTranslationService) ─
  val dtReq = TranslationReq()
  val dtRsp = TranslationRsp()
  dtRsp.ready.allowOverride;     dtRsp.ready := True
  dtRsp.ppn.allowOverride;       dtRsp.ppn := U(0, 20 bits)
  dtRsp.cacheMode.allowOverride; dtRsp.cacheMode.assignDontCare()
  dtRsp.fault.allowOverride;     dtRsp.fault := False

  // `active` is high whenever the FSM is mid-sequence; the wiring gates the MUX on it.
  val active = Bool()

  // The store queue is drained (no committed store still heading to memory). The
  // entry FSM waits for this before stacking its frame so it never steals the
  // D-cache store port from an older committed store's in-flight write-through
  // (which would silently drop that store). Default True (unit DUTs w/o an LS EU).
  val sqDrained = Bool(); sqDrained.allowOverride; sqDrained := True

  // ── captured per-event state ────────────────────────────────────────────────
  val curVec   = Reg(UInt(8 bits))
  val curPc    = Reg(UInt(32 bits))   // ENTRY: faulting PC to stack; RTE: restored PC
  val oldSr    = Reg(UInt(16 bits))   // ENTRY: SR to stack
  val frameBase= Reg(UInt(32 bits))   // ENTRY: new SSP = SSP-8 (or -60 for $7); RTE: old SSP
  val vecTarget= Reg(UInt(32 bits))   // redirect target
  // ENTRY: is this an access fault (vector 2)? -> stack a format-$7 frame (30 words)
  // instead of format-$0 (4 words). Captured at trigger.
  val curIs7   = RegInit(False)
  // ENTRY: is this a format-$2 trap (TRAPV / CHK / DIV0 on the 68040)? -> stack a
  // 6-word format-$2 frame {SR, PC, 0x2000|vec<<2, PPC} (Musashi m68ki_stack_frame_0010
  // for CPU_TYPE 68040). Captured at trigger. PPC = the trap instr's own PC.
  val curIs2   = RegInit(False)
  val curPpc   = Reg(UInt(32 bits))   // format-$2 PPC (the trap instruction's PC)
  val curFault = Reg(UInt(32 bits))   // faulting VA (EA + fault-address fields of $7)
  val curSsw   = Reg(UInt(16 bits))   // $7 special status word

  // RTE pop accumulators
  val popSr = Reg(UInt(16 bits))
  val popPc = Reg(UInt(32 bits))
  // RTE format select: the stacked format word @base+6 top nibble (0 = format-$0,
  // 7 = format-$7 access-fault). Chooses the pop size (8 vs 60 bytes). For $7 RTE
  // restores SR/PC and RESUMES at the stacked PC (= faulting instr -> re-executes),
  // discarding the rest of the frame — matching MAME's RTE case 7.
  val popIs7 = RegInit(False); popIs7.simPublic()
  // format-$2 (top nibble 2): the 6-word trap frame (TRAPV/CHK/DIV0). RTE pops 12
  // bytes and resumes at the stacked PC (= the next instruction; TRAPV is not
  // restarted), discarding the format word + PPC. Matches Musashi RTE case 2.
  val popIs2 = RegInit(False); popIs2.simPublic()
  val popFmtWord = Reg(UInt(16 bits)); popFmtWord.simPublic()

  // ── redirect outputs (the ROB ORs these into its registered redirect) ────────
  val redirectValid = Bool(); redirectValid := False
  val redirectPc    = UInt(32 bits); redirectPc := vecTarget

  // ── commit observation for the exception/RTE "instruction" (lock-step). At the
  // redirect cycle the event delivers its POST-state: the handler-entry PC (entry)
  // / restored PC (RTE), the resulting SR (16b) and A7. Mirrors Musashi's trace
  // step for the faulting / RTE instruction. ───────────────────────────────────
  val obsFire    = Bool();        obsFire := False
  val obsPc      = UInt(32 bits); obsPc := U(0, 32 bits)
  val obsSysByte = UInt(8 bits);  obsSysByte := U(0, 8 bits)   // post-event SR system byte
  val obsA7      = UInt(32 bits); obsA7 := U(0, 32 bits)

  // ── Architectural A7 (int reg 15) write-back. The committed A7 lives in BOTH the
  // SystemState bank (ss.ssp/usp) AND the int register file (arch reg 15) the
  // datapath reads. When the exception changes A7 (entry: SSP-=8; RTE: restore +
  // maybe re-bank to USP), it must update reg 15 so the handler's (A7)/disp(A7)
  // stack accesses see the new SP. The full-core wiring connects this to an int PRF
  // write port (phys = committed arch-15 mapping; identity phys-15 while A7 is
  // unrenamed). obsFire qualifies it (same cycle as the event's commit obs). ─────
  val a7WriteValid = Bool();        a7WriteValid := obsFire
  val a7WriteData  = UInt(32 bits); a7WriteData := obsA7

  // ── SystemState write defaults (the FSM pulses them) ────────────────────────
  ss.setSrSys.valid := False; ss.setSrSys.payload := U(0, 8 bits)
  ss.setSsp.valid   := False; ss.setSsp.payload   := U(0, 32 bits)
  ss.setVbr.valid   := False; ss.setVbr.payload   := U(0, 32 bits)
  ss.setUsp.valid   := False; ss.setUsp.payload   := U(0, 32 bits)
  ss.writeA7.valid  := False; ss.writeA7.payload  := U(0, 32 bits)

  // ── D-cache STORE: REGISTERED output (FMax). The frame-word store payload is a
  // combinational mux off the FSM step `stStep`; driving it straight onto the
  // D-cache store port put `stStep -> store-merge -> SQ-overlap-compare` on the
  // LS EU's critical SQ-forward arc. We compute the store into combinational
  // `sto*` and REGISTER it onto `dcStore` (the exception FSM is serializing /
  // multi-cycle + ack-gated, so the extra cycle is free). This cuts the arc. ────
  val stoVld   = Bool();        stoVld := False
  val stoPaddr = UInt(32 bits); stoPaddr := U(0, 32 bits)
  val stoData  = Bits(32 bits); stoData := B(0, 32 bits)
  val stoSize  = Size();        stoSize := Size.LONG
  dcStore.valid           := RegNext(stoVld) init False
  dcStore.payload.paddr   := RegNext(stoPaddr)
  dcStore.payload.data    := RegNext(stoData)
  dcStore.payload.size    := RegNext(stoSize)
  dcStore.payload.useStrb := False
  dcStore.payload.strb    := B(0, 16 bits)
  dcStore.payload.lineData:= B(0, 128 bits)

  // ── D-cache LOAD + D-TLB req: REGISTERED outputs (FMax). The frame/vector load
  // vaddr (off `frameBase`/`vecTarget`) drives the D-cache hit/miss-tag + the LS
  // EU's SQ-overlap compare; combinationally that put `frameBase -> miss-tag ->
  // SQ-compare -> fwdData` on the critical arc. We register the load cmd + the
  // matching D-TLB request. The load states hold the request until the registered
  // `dcLoadCmd.fire`, so the extra cycle is free + the cmd/vpn stay consistent. ──
  val ldoVld   = Bool();        ldoVld := False
  val ldoVaddr = UInt(32 bits); ldoVaddr := U(0, 32 bits)
  val ldoSize  = Size();        ldoSize := Size.LONG
  dcLoadCmd.valid         := RegNext(ldoVld) init False
  dcLoadCmd.payload.vaddr := RegNext(ldoVaddr)
  // exception sequencer runs MMU-off (identity, slice-1): paddr == vaddr.
  dcLoadCmd.payload.paddr := RegNext(ldoVaddr)
  dcLoadCmd.payload.size  := RegNext(ldoSize)

  val dtoVld = Bool();        dtoVld := False
  val dtoVpn = UInt(20 bits); dtoVpn := U(0, 20 bits)
  val dtoWr  = Bool();        dtoWr := False
  dtReq.valid      := RegNext(dtoVld) init False
  dtReq.vpn        := RegNext(dtoVpn)
  dtReq.supervisor := True
  dtReq.write      := RegNext(dtoWr) init False

  // helper: present one aligned store of `sz` at `va`. The store paddr is the
  // identity-translated va (MMU off in slice-1 exception tests; a real-DTLB frame
  // translation is a fast-follow). Both sto* (the store) and dto* (the matching
  // D-TLB request, for the cache's coherence) are REGISTERED onto dcStore/dtReq.
  def driveStore(va: UInt, sz: Size.C, data: Bits): Unit = {
    dtoVld := True; dtoVpn := va(31 downto 12); dtoWr := True
    stoVld   := True
    stoPaddr := va
    stoSize  := sz
    stoData  := data.resize(32)
  }
  // Identity supervisor PHYSICAL store WITHOUT a translation request. The exception
  // frame/vector accesses are physical (paddr == va); the D-cache store port uses the
  // paddr directly. Driving the DTLB here would (with the MMU live) start a walk whose
  // multi-cycle not-ready stalls the store state -> re-pulsed stores. So we don't.
  def driveStoreNoXlate(va: UInt, sz: Size.C, data: Bits): Unit = {
    stoVld   := True
    stoPaddr := va
    stoSize  := sz
    stoData  := data.resize(32)
  }

  // ENTRY frame-store step. The D-cache store path is single-outstanding, so each
  // word is issued THEN we wait for the write-through ACK (AXI B) before the next —
  // otherwise a back-to-back store overwrites the previous write-through beat before
  // it drains (lost word). Word index is from frameBase (LOW address), ascending.
  //   format-$0 (illegal/privilege): 4 words [SR, PC hi, PC lo, vec<<2].
  //   format-$7 (access fault):     30 words ($3C) — MAME m68ki_stack_frame_0111:
  //     [+0x00]=SR [+0x02]=PChi [+0x04]=PClo [+0x06]=0x7000|(vec<<2)
  //     [+0x08]=EAhi [+0x0a]=EAlo [+0x0c]=SSW [+0x0e..0x12]=0 (3 words)
  //     [+0x14]=faultAddr hi [+0x16]=faultAddr lo [+0x18..0x3a]=0 (18 words).
  //   stStep counts WORDS (5 bits, 0..29). lastStep = 3 ($0) or 29 ($7).
  val stStep = Reg(UInt(5 bits)) init 0; stStep.simPublic()
  val lastStep = Mux(curIs7, U(29, 5 bits), Mux(curIs2, U(5, 5 bits), U(3, 5 bits)))
  def frameWordAddr(step: UInt): UInt = (frameBase + (step << 1)).resized
  // Common low-4-word prefix: SR, PC hi, PC lo, format/vector word. The format/vector
  // word is curVec<<2 for $0, 0x2000|(curVec<<2) for $2, 0x7000|(curVec<<2) for $7.
  val fmtVecWord = Mux(curIs7, (U(0x7000, 16 bits) | (curVec << 2).resize(16)),
                   Mux(curIs2, (U(0x2000, 16 bits) | (curVec << 2).resize(16)),
                               (curVec << 2).resize(16)))
  def frameWordData(step: UInt): Bits = {
    val out = Bits(16 bits)
    out := B(0, 16 bits)
    switch(step) {
      is(U(0, 5 bits)) { out := oldSr.asBits }
      is(U(1, 5 bits)) { out := curPc(31 downto 16).asBits }
      is(U(2, 5 bits)) { out := curPc(15 downto 0).asBits }
      is(U(3, 5 bits)) { out := fmtVecWord.asBits }
      // format-$2 words (steps 4..5): PPC (the trap instruction's own PC). For $7
      // these same step indices carry the EA (overridden just below).
      is(U(4, 5 bits))  { out := Mux(curIs2, curPpc(31 downto 16), curFault(31 downto 16)).asBits }
      is(U(5, 5 bits))  { out := Mux(curIs2, curPpc(15 downto 0),  curFault(15 downto 0)).asBits }
      // $7-only words (steps 6..29). For $0/$2 these steps never execute.
      is(U(6, 5 bits))  { out := curSsw.asBits }                   // SSW
      is(U(10, 5 bits)) { out := curFault(31 downto 16).asBits }   // fault addr hi
      is(U(11, 5 bits)) { out := curFault(15 downto 0).asBits }    // fault addr lo
      // all other steps (7,8,9,12..29) stack 0 (internal registers).
    }
    out
  }

  val fsm = new StateMachine {
    val IDLE      = new State with EntryPoint
    // ENTRY path
    val E_DRAIN   = new State    // wait for the SQ to drain before grabbing the port
    val E_STORE   = new State    // issue one frame word store
    val E_STWAIT  = new State    // await its write-through ACK; advance step
    val E_VECREQ  = new State    // issue vector load @ VBR+vec*4
    val E_VECWAIT = new State    // await vector load rsp
    val E_REDIR   = new State    // pulse redirect, commit SSP/SR, done
    // RTE path
    val R_SRREQ   = new State    // load SR word @ base+0
    val R_SRWAIT  = new State
    val R_PCREQ   = new State    // load PC long @ base+2
    val R_PCWAIT  = new State
    val R_FMTREQ  = new State    // load format word @ base+6 (select $0 vs $7 pop)
    val R_FMTWAIT = new State
    val R_REDIR   = new State

    IDLE.whenIsActive {
      when(entryTrigger) {
        val is7 = entryVector === 2   // access fault -> format-$7
        // TRAPV (vector 7) is a group-2 trap -> format-$2 on the 68040 (Musashi
        // m68ki_stack_frame_0010). The 6-word frame stacks {SR, PC(=nextPc),
        // 0x2000|vec<<2, PPC}. PPC = the trap instruction's own PC = entryPc-2
        // (TRAPV is a single 2-byte opword, and entryPc = nextPc = pc+2).
        val is2 = entryVector === 7
        curVec    := entryVector
        curPc     := entryPc
        curIs7    := is7
        curIs2    := is2
        curPpc    := (entryPc - 2).resized
        curFault  := entryFaultAddr
        // SSW = (in_mmu 0x400) | fc | (rw<<8); fc = data space (bit0=1) + supervisor
        // (bit2) if a supervisor access; rw = read?1:write?0 (MAME m68ki_aerr).
        // The faulting access's privilege = the PRE-exception S bit (SR bit13 =
        // srSys bit5), read here BEFORE the FSM sets S. (The LS EU's translate-time
        // supervisor flag is a slice-1 user-only simplification, so the SSW's super
        // bit comes from the architectural SR, matching the MAME oracle which reads
        // the SR S bit.) entryFaultSup is retained for a future MOVES/SFC-driven mode.
        val faultSuper = ss.srSys(5) || entryFaultSup
        // FC space (bit2=supervisor): DATA access => bit0 set (01/101); INSTRUCTION
        // fetch => program space, bit1 set (10/110). Mirrors MAME's m68040 SSW TM/FC
        // (data=...001/101, program=...010/110). An instruction fetch is always a
        // READ (rw bit = 1).
        val spaceBits = Mux(entryFaultInstr, U(0x2, 3 bits), U(0x1, 3 bits))
        val fc  = Mux(faultSuper, U(0x4, 3 bits), U(0x0, 3 bits)) | spaceBits
        val rwB = Mux(entryFaultInstr, U(1, 1 bits), Mux(entryFaultWr, U(0, 1 bits), U(1, 1 bits)))
        curSsw    := (U(0x400, 16 bits) | fc.resize(16) | (rwB ## U(0, 8 bits)).asUInt.resize(16))
        oldSr     := (ss.srSys ## committedCcr.resize(8 bits)).asUInt
        // new SSP = current A7 (SSP, since committed S) - frame size
        //   format-$0 = 8 bytes, format-$2 = 12 bytes, format-$7 = 60 bytes.
        val nb = Mux(is7, ss.ssp - 60, Mux(is2, ss.ssp - 12, ss.ssp - 8))
        frameBase := nb
        // compute vector fetch base = VBR + vec*4
        vecTarget := (ss.vbr + (entryVector << 2)).resized
        stStep    := 0
        goto(E_DRAIN)
      } elsewhen(rteTrigger) {
        // RTE reads the frame at the CURRENT A7 (SSP). frameBase := SSP.
        frameBase := ss.ssp
        goto(R_SRREQ)
      }
    }

    // Wait for older committed stores to fully drain before we use the store port.
    E_DRAIN.whenIsActive { when(sqDrained) { goto(E_STORE) } }

    // ── ENTRY: stack the frame (one word at a time) ─────────────────────────────
    E_STORE.whenIsActive {
      // Issue the store EXACTLY ONCE (one cycle), then unconditionally wait its ACK.
      // The exception sequencer is a supervisor PHYSICAL access: the store paddr is
      // identity, and the D-cache store port is a backpressure-less Flow using that
      // paddr directly — it needs NO translation. We must NOT gate on `dtRsp.ready`
      // (with the MMU live, the frame VPN walks and dtRsp.ready drops for several
      // cycles, during which the combinational `driveStore` would re-pulse the
      // REGISTERED store every cycle -> the SAME word stored many times, a
      // nondeterministic count that races the cache store machine and corrupts the
      // frame). One cycle here -> exactly one registered store pulse.
      driveStoreNoXlate(frameWordAddr(stStep), Size.WORD, frameWordData(stStep))
      goto(E_STWAIT)
    }
    E_STWAIT.whenIsActive {
      // hold nothing on the store port (one-cycle pulse already issued); wait for
      // the write-through to land (storeAck) before the next word / vector fetch.
      // A SETTLE state separates back-to-back stores: the cache store port is a
      // backpressure-less Flow that latches unconditionally, so issuing the next
      // store the cycle after ack (while the cache machine is settling its
      // stAwDone/stWDone) could drop a beat. One idle cycle guarantees the machine
      // is idle before the next store.
      when(dcStoreAck) {
        when(stStep === lastStep) { goto(E_VECREQ) } otherwise { stStep := stStep + 1; goto(E_STORE) }
      }
    }
    // ── ENTRY: fetch the handler vector ─────────────────────────────────────────
    E_VECREQ.whenIsActive {
      dtoVld := True; dtoVpn := vecTarget(31 downto 12); dtoWr := False
      ldoVld := True; ldoVaddr := vecTarget; ldoSize := Size.LONG
      when(dcLoadCmd.fire) { goto(E_VECWAIT) }
    }
    E_VECWAIT.whenIsActive {
      // keep the translation valid while the load is in flight
      dtoVld := True; dtoVpn := vecTarget(31 downto 12)
      when(dcLoadRsp.valid) {
        vecTarget := dcLoadRsp.payload.data.asUInt
        goto(E_REDIR)
      }
    }
    E_REDIR.whenIsActive {
      // commit the architectural side-effects + redirect
      ss.setSsp.valid   := True; ss.setSsp.payload := frameBase
      // enter supervisor, clear trace: set S (bit5), clear T1/T0 (bits 7,6).
      val newSys = (ss.srSys | U(0x20, 8 bits)) & U(0x3f, 8 bits)
      ss.setSrSys.valid := True; ss.setSrSys.payload := newSys
      redirectValid := True
      redirectPc    := vecTarget
      // commit observation: the faulting instruction's trace step == handler entry
      // with the post-exception SR system byte (S set, T cleared) + A7 = new SSP.
      // (CCR is unchanged by the exception -> the whitebox carries it.)
      obsFire    := True
      obsPc      := vecTarget
      obsSysByte := newSys
      obsA7      := frameBase
      goto(IDLE)
    }

    // ── RTE: pop the frame, restore SR + PC, SSP += 8, redirect ─────────────────
    R_SRREQ.whenIsActive {
      dtoVld := True; dtoVpn := (frameBase + 0)(31 downto 12)
      ldoVld := True; ldoVaddr := frameBase + 0; ldoSize := Size.WORD
      when(dcLoadCmd.fire) { goto(R_SRWAIT) }
    }
    R_SRWAIT.whenIsActive {
      dtoVld := True; dtoVpn := (frameBase + 0)(31 downto 12)
      when(dcLoadRsp.valid) {
        popSr := dcLoadRsp.payload.data(15 downto 0).asUInt
        goto(R_PCREQ)
      }
    }
    R_PCREQ.whenIsActive {
      dtoVld := True; dtoVpn := (frameBase + 2)(31 downto 12)
      ldoVld := True; ldoVaddr := frameBase + 2; ldoSize := Size.LONG
      when(dcLoadCmd.fire) { goto(R_PCWAIT) }
    }
    R_PCWAIT.whenIsActive {
      dtoVld := True; dtoVpn := (frameBase + 2)(31 downto 12)
      when(dcLoadRsp.valid) {
        popPc := dcLoadRsp.payload.data.asUInt
        goto(R_FMTREQ)
      }
    }
    // Read the format word @base+6 to select the pop size ($0 = 8 bytes, $7 = 60).
    R_FMTREQ.whenIsActive {
      dtoVld := True; dtoVpn := (frameBase + 6)(31 downto 12)
      ldoVld := True; ldoVaddr := frameBase + 6; ldoSize := Size.WORD
      when(dcLoadCmd.fire) { goto(R_FMTWAIT) }
    }
    R_FMTWAIT.whenIsActive {
      dtoVld := True; dtoVpn := (frameBase + 6)(31 downto 12)
      when(dcLoadRsp.valid) {
        // top nibble selects the pop size: 7 => format-$7 (60 bytes), 2 => format-$2
        // (12 bytes), else format-$0 (8 bytes).
        popFmtWord := dcLoadRsp.payload.data(15 downto 0).asUInt
        popIs7 := dcLoadRsp.payload.data(15 downto 12).asUInt === U(7, 4 bits)
        popIs2 := dcLoadRsp.payload.data(15 downto 12).asUInt === U(2, 4 bits)
        goto(R_REDIR)
      }
    }
    R_REDIR.whenIsActive {
      // restore the full SR (system byte). A7 banks automatically by the new S.
      ss.setSrSys.valid := True; ss.setSrSys.payload := popSr(15 downto 8)
      // SSP += frame size (8 for $0, 60 for $7). The CURRENT A7 is SSP (we were
      // supervisor); after restoring SR the bank may switch to USP, so write SSP
      // explicitly. For $7 the popPc is the faulting instruction's PC -> RTE resumes
      // by RE-EXECUTING it (the handler has fixed the mapping), matching MAME.
      val newSsp = frameBase + Mux(popIs7, U(60, 32 bits), Mux(popIs2, U(12, 32 bits), U(8, 32 bits)))
      ss.setSsp.valid   := True; ss.setSsp.payload := newSsp
      redirectValid := True
      redirectPc    := popPc
      // commit observation: RTE's trace step == restored PC + restored SR sysByte
      // + A7. A7 after RTE = popped-SSP if S restored supervisor, else USP. The CCR
      // is restored from the frame too, but the whitebox carries it (RTE restores
      // the same CCR the matching exception entry saved -> reconstructed CCR holds).
      obsFire    := True
      obsPc      := popPc
      obsSysByte := popSr(15 downto 8)
      obsA7      := Mux(popSr(13), newSsp, ss.usp)   // SR bit13 = S
      goto(IDLE)
    }
  }

  // `active` high whenever the FSM is mid-sequence (not IDLE).
  active := !fsm.isActive(fsm.IDLE)
}
