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

  // ── SystemState write defaults (the FSM pulses them) ────────────────────────
  ss.setSrSys.valid := False; ss.setSrSys.payload := U(0, 8 bits)
  ss.setSsp.valid   := False; ss.setSsp.payload   := U(0, 32 bits)
  ss.setVbr.valid   := False; ss.setVbr.payload   := U(0, 32 bits)
  ss.setUsp.valid   := False; ss.setUsp.payload   := U(0, 32 bits)
  ss.writeA7.valid  := False; ss.writeA7.payload  := U(0, 32 bits)

  // ── D-cache port defaults ───────────────────────────────────────────────────
  dcLoadCmd.valid         := False
  dcLoadCmd.payload.vaddr := U(0, 32 bits)
  dcLoadCmd.payload.size  := Size.LONG
  dcStore.valid           := False
  dcStore.payload.paddr   := U(0, 32 bits)
  dcStore.payload.data    := B(0, 32 bits)
  dcStore.payload.size    := Size.LONG
  dcStore.payload.useStrb := False
  dcStore.payload.strb    := B(0, 16 bits)
  dcStore.payload.lineData:= B(0, 128 bits)

  dtReq.valid      := False
  dtReq.vpn        := U(0, 20 bits)
  dtReq.supervisor := True
  dtReq.write      := False

  // identity-or-DTLB physical address for the address currently presented.
  def paddrOf(va: UInt): UInt = (dtRsp.ppn ## va(11 downto 0)).asUInt

  // helper: issue one aligned store of `sz` at `va` with right-justified `data`.
  def driveStore(va: UInt, sz: Size.C, data: Bits): Unit = {
    dtReq.valid      := True
    dtReq.vpn        := va(31 downto 12)
    dtReq.write      := True
    dtReq.supervisor := True
    dcStore.valid         := True
    dcStore.payload.paddr := paddrOf(va)
    dcStore.payload.size  := sz
    dcStore.payload.data  := data.resize(32)
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
        goto(E_STORE)
      } elsewhen(rteTrigger) {
        // RTE reads the frame at the CURRENT A7 (SSP). frameBase := SSP.
        frameBase := ss.ssp
        goto(R_SRREQ)
      }
    }

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
      dtReq.valid      := True
      dtReq.vpn        := vecTarget(31 downto 12)
      dtReq.write      := False
      dtReq.supervisor := True
      dcLoadCmd.valid         := True
      dcLoadCmd.payload.vaddr := vecTarget
      dcLoadCmd.payload.size  := Size.LONG
      when(dcLoadCmd.fire) { goto(E_VECWAIT) }
    }
    E_VECWAIT.whenIsActive {
      // keep the translation valid while the load is in flight
      dtReq.valid      := True
      dtReq.vpn        := vecTarget(31 downto 12)
      dtReq.supervisor := True
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
      goto(IDLE)
    }

    // ── RTE: pop the frame, restore SR + PC, SSP += 8, redirect ─────────────────
    R_SRREQ.whenIsActive {
      dtReq.valid := True; dtReq.vpn := (frameBase + 0)(31 downto 12); dtReq.supervisor := True
      dcLoadCmd.valid := True
      dcLoadCmd.payload.vaddr := frameBase + 0
      dcLoadCmd.payload.size  := Size.WORD
      when(dcLoadCmd.fire) { goto(R_SRWAIT) }
    }
    R_SRWAIT.whenIsActive {
      dtReq.valid := True; dtReq.vpn := (frameBase + 0)(31 downto 12); dtReq.supervisor := True
      when(dcLoadRsp.valid) {
        popSr := dcLoadRsp.payload.data(15 downto 0).asUInt
        goto(R_PCREQ)
      }
    }
    R_PCREQ.whenIsActive {
      dtReq.valid := True; dtReq.vpn := (frameBase + 2)(31 downto 12); dtReq.supervisor := True
      dcLoadCmd.valid := True
      dcLoadCmd.payload.vaddr := frameBase + 2
      dcLoadCmd.payload.size  := Size.LONG
      when(dcLoadCmd.fire) { goto(R_PCWAIT) }
    }
    R_PCWAIT.whenIsActive {
      dtReq.valid := True; dtReq.vpn := (frameBase + 2)(31 downto 12); dtReq.supervisor := True
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
      ss.setSsp.valid   := True; ss.setSsp.payload := frameBase + 8
      redirectValid := True
      redirectPc    := popPc
      goto(IDLE)
    }
  }

  // `active` high whenever the FSM is mid-sequence (not IDLE).
  active := !fsm.isActive(fsm.IDLE)
}
