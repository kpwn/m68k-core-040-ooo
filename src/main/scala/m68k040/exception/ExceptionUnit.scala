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
    committedCcr: UInt) extends Area {

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
  val frameBase= Reg(UInt(32 bits))   // ENTRY: new SSP = SSP-8 ; RTE: old SSP (read base)
  val vecTarget= Reg(UInt(32 bits))   // redirect target

  // RTE pop accumulators
  val popSr = Reg(UInt(16 bits))
  val popPc = Reg(UInt(32 bits))

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

  // ENTRY frame-store step (0..3): SR, PC hi, PC lo, format/vector word. The
  // D-cache store path is single-outstanding, so each word is issued THEN we wait
  // for the write-through ACK (AXI B) before the next — otherwise a back-to-back
  // store overwrites the previous write-through beat before it drains (lost word).
  val stStep = Reg(UInt(2 bits)) init 0; stStep.simPublic()
  def frameWordAddr(step: UInt): UInt = (frameBase + (step << 1)).resized
  def frameWordData(step: UInt): Bits = step.mux(
    U(0, 2 bits) -> oldSr.asBits,
    U(1, 2 bits) -> curPc(31 downto 16).asBits,
    U(2, 2 bits) -> curPc(15 downto 0).asBits,
    U(3, 2 bits) -> (curVec << 2).resize(16).asBits)

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
    val R_REDIR   = new State

    IDLE.whenIsActive {
      when(entryTrigger) {
        curVec    := entryVector
        curPc     := entryPc
        oldSr     := (ss.srSys ## committedCcr.resize(8 bits)).asUInt
        // new SSP = current A7 (SSP, since committed S) - 8
        val nb = (ss.ssp - 8)
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

    // ── ENTRY: stack the format-$0 frame (4 words, one at a time) ───────────────
    E_STORE.whenIsActive {
      // present the translation + the store this cycle; advance to wait-ack only
      // once translation resolved (identity = same cycle). The store Flow pulse is
      // latched by the cache this cycle.
      driveStore(frameWordAddr(stStep), Size.WORD, frameWordData(stStep))
      when(dtRsp.ready) { goto(E_STWAIT) }
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
        when(stStep === 3) { goto(E_VECREQ) } otherwise { stStep := stStep + 1; goto(E_STORE) }
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
        goto(R_REDIR)
      }
    }
    R_REDIR.whenIsActive {
      // restore the full SR (system byte). A7 banks automatically by the new S.
      ss.setSrSys.valid := True; ss.setSrSys.payload := popSr(15 downto 8)
      // SSP += 8 (pop the frame). The CURRENT A7 is SSP (we were supervisor); after
      // restoring SR the bank may switch to USP, so write SSP explicitly.
      val newSsp = frameBase + 8
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
