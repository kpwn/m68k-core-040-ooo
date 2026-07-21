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
    // The ONE 68040 MMU control (task #131): the MOVEC READ case below reads
    // mmuCtrl.mmuEnable/urp/srp for the Rc->Rn direction (TCR/URP/SRP). The WRITE
    // direction (real supervisor code programming the MMU) was ATTEMPTED and
    // REVERTED — see MmuControlPlugin's doc comment for the confirmed regression
    // it caused. Those Rc values fall through to the default RAZ/WI case on write.
    mmuCtrl: m68k040.services.MmuControlService,
    entryTrigger: Bool, entryVector: UInt, entryPc: UInt,
    // Format-$2 group-2 trap PPC = the trapping INSTRUCTION's PC (TRAPV/CHK/DIV0). For
    // TRAPV this equals entryPc-2 (a 2-byte op), but CHK/DIV0 are variable-length, so
    // the PPC is supplied explicitly. Default entryPc-2 keeps the old TRAPV-only unit
    // tests (which don't pass entryPpc) byte-for-byte.
    entryPpc: UInt = null,
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
    entryFaultInstr: Bool = False,
    // INTERRUPT entry (vs fault/trap). When True the entry SR-write additionally
    // raises the SR I-mask to `entryIplLevel` (so equal/lower interrupts are held
    // until RTE; NMI sets 7). Fault/trap entries leave the mask unchanged (S=1 /
    // T=0 only). Default False => the existing fault/trap behavior is unchanged.
    entryIsInterrupt: Bool = False,
    entryIplLevel:    UInt = U(0, 3 bits),
    // ── Commit-time PRIVILEGED SYSTEM op (MOVE-to-SR / MOVE-USP / MOVEC) ─────────
    // A serializing system op at the head (S=1 supervisor — the user-mode case is a
    // vector-8 fault delivered via entryTrigger instead). The FSM applies the effect
    // (write committed SR/USP/VBR + re-bank A7, or read system->Rn), pulses the obs
    // (post-state sysByte + re-banked A7), and redirects to sysNextPc (serialize).
    //   sysKind     : 1=MOVE-to-SR, 2=MOVE-USP, 3=MOVEC (mirrors decode.SysKind enum).
    //   sysReadDir  : read SYSTEM->Rn (True) vs write Rn->SYSTEM (False).
    //   sysVal      : the captured source VALUE (for a write).
    //   sysRc       : the 12-bit MOVEC control-reg id.
    //   sysDstArch  : the Rn arch reg for a READ (the FSM writes the int PRF).
    //   sysPc       : the sysOp instruction's PC (the obs commit PC).
    //   sysNextPc   : the next instruction's PC (the redirect target after serialize).
    sysTrigger:   Bool = False,
    sysKind:      UInt = U(0, 3 bits),
    sysReadDir:   Bool = False,
    sysVal:       Bits = B(0, 32 bits),
    sysRc:        UInt = U(0, 12 bits),
    // The PHYSICAL dst reg for a READ (the rename-allocated pdst of the read µop). The
    // ROB commits the arch->pdst mapping at the serializing retire; the FSM writes the
    // system VALUE into PRF[pdst]. (An arbitrary Rn is renamed, unlike the identity A7.)
    sysDstPhys:   UInt = U(0, 6 bits),
    sysPc:        UInt = U(0, 32 bits),
    sysNextPc:    UInt = U(0, 32 bits)) extends Area {

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

  // ── Live committed A7 (arch reg 15) read back from the int PRF ────────────────
  // The full-core wiring drives this every cycle with the committed A7 value (PRF
  // read at the committed arch-15 phys mapping). It feeds ss.writeA7 so the committed
  // bank (usp/isp/msp selected by committed S,M) continuously mirrors the architectural
  // A7 — making a MOVE-to-SR (S,M) switch load a correctly-preserved bank and the
  // exception FSM read a live supervisor SP. Default = ss.a7 (a self-hold echo: writeA7
  // writes the active bank back to itself = no-op) so standalone unit-test DUTs that
  // don't wire the PRF readback are NOT stomped to 0; allowOverride lets the full-core
  // wiring override it with the real committed-A7 readback.
  val committedA7In = UInt(32 bits); committedA7In.allowOverride; committedA7In := ss.a7

  // The store queue is drained (no committed store still heading to memory). The
  // entry FSM waits for this before stacking its frame so it never steals the
  // D-cache store port from an older committed store's in-flight write-through
  // (which would silently drop that store). Default True (unit DUTs w/o an LS EU).
  val sqDrained = Bool(); sqDrained.allowOverride; sqDrained := True

  // ── captured per-event state ────────────────────────────────────────────────
  val curVec   = Reg(UInt(8 bits)); curVec.simPublic()
  val curPc    = Reg(UInt(32 bits)); curPc.simPublic()   // ENTRY: faulting PC to stack; RTE: restored PC
  val oldSr    = Reg(UInt(16 bits))   // ENTRY: SR to stack
  val frameBase= Reg(UInt(32 bits))   // ENTRY: new SP = supervisor bank (M?MSP:ISP) - frame size; RTE: old SP
  val vecTarget= Reg(UInt(32 bits))   // redirect target
  // ENTRY: is this an access fault (vector 2)? -> stack a format-$7 frame (30 words)
  // instead of format-$0 (4 words). Captured at trigger.
  val curIs7   = RegInit(False)
  // ENTRY: is this a format-$2 trap (TRAPV / CHK / DIV0 on the 68040)? -> stack a
  // 6-word format-$2 frame {SR, PC, 0x2000|vec<<2, PPC} (Musashi m68ki_stack_frame_0010
  // for CPU_TYPE 68040). Captured at trigger. PPC = the trap instr's own PC.
  val curIs2   = RegInit(False)
  val curPpc   = Reg(UInt(32 bits))   // format-$2 PPC (the trap instruction's PC)
  // ENTRY: is this an INTERRUPT entry? -> raise the SR I-mask to curLevel in the
  // entry SR-write (fault/trap entries leave the mask unchanged). Captured at trigger.
  val curIsInt = RegInit(False)
  val curLevel = Reg(UInt(3 bits))    // interrupt level for the I-mask raise
  val curFault = Reg(UInt(32 bits))   // faulting VA (EA + fault-address fields of $7)
  val curSsw   = Reg(UInt(16 bits))   // $7 special status word

  // ── Task #132: interrupt-with-M=1 throwaway frame (format-$1, Slice B) ──────────
  // Real 68040 semantics (Musashi m68ki_exception_interrupt, m68kcpu.h:2226-2235):
  // an INTERRUPT taken while M=1 stacks a NORMAL format-$0 frame on the CURRENT
  // active stack (MSP, since M hasn't been cleared yet), THEN clears M (rebanking
  // to ISP) and stacks a SECOND, format-$1 "throwaway" frame — same {PC,SR,vector}
  // content, just a different format nibble — on the NOW-active ISP. The handler
  // runs on ISP with M=0. RTE reads the throwaway frame first: it applies the
  // frame's SR (which still carries M=1, so A7 re-banks BACK to MSP) and DISCARDS
  // the frame's PC, then — still within the SAME rte instruction — re-reads the
  // format word now sitting at the top of MSP (the real format-$0 frame) and pops
  // THAT one for real (PC used, final SR applied). Net effect: both stacks end up
  // back at their pre-entry depth; only ONE observable commit (the final pop).
  // curThrowaway/frameBase2/stFrame2 are the ENTRY-side bookkeeping; the RTE side
  // needs no extra state — `popIs1` (mirrors popIs7/popIs2) drives the loop-back.
  val curThrowaway = RegInit(False)   // this INTERRUPT entry needs a $1 throwaway frame
  val frameBase2   = Reg(UInt(32 bits))   // ISP-relative base for the $1 frame (entry only)
  val stFrame2     = RegInit(False)       // E_STORE loop is currently on the 2nd ($1) frame

  // ── Commit-time SYSTEM op captured state (latched at sysTrigger) ─────────────
  val sysCapKind    = Reg(UInt(3 bits))
  val sysCapReadDir = Reg(Bool())
  val sysCapVal     = Reg(Bits(32 bits))
  val sysCapRc      = Reg(UInt(12 bits))
  val sysCapDstPhys = Reg(UInt(6 bits))
  val sysCapPc      = Reg(UInt(32 bits))
  val sysCapNextPc  = Reg(UInt(32 bits))

  // RTE-own-PC, captured at rteTrigger (task #177): `rtePc` aliases a LIVE ROB
  // signal (pcStore(h0)) indexed by the head pointer. The RTE FSM is multi-cycle
  // (IDLE -rteTrigger-> R_DRAIN -> ... -> the format-check state that actually
  // consumes rtePc, many cycles later). While the FSM runs, `excSquash` (asserted
  // every cycle once `exc.active`) forces the ROB's `tail := head` every cycle, so
  // any younger speculative µop the front-end allocates in the meantime reuses the
  // SAME physical ROB slot h0 still points at -- silently overwriting pcStore(h0)
  // with a DIFFERENT (soon-to-be-squashed) instruction's PC before the format-error
  // path ever reads it. Reading the live `rtePc` wire late (as the format-error path
  // used to) therefore returns garbage, not RTE's own PC -- it must be LATCHED here,
  // on the SAME cycle rteTrigger fires (before excActive/excSquash starts reusing
  // the slot), exactly like sysCapPc is latched at sysTrigger below.
  val rteCapPc = Reg(UInt(32 bits))

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
  // format-$1 (top nibble 1, task #132): the M=1-interrupt throwaway frame. RTE
  // applies its SR (re-banking back to MSP, M restored to 1) and DISCARDS its PC,
  // then loops back to pop the REAL format-$0 frame now at the top of MSP — see
  // R_REDIR. Musashi: m68k_in.c rte's `case 1: /* Throwaway */ ... goto rte_loop`.
  val popIs1 = RegInit(False); popIs1.simPublic()
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
  // MOVE-to-SR writes the FULL CCR (X N Z V C = sysVal[4:0]) as an ABSOLUTE value (vs
  // the per-bit fold of NZVC the CHK entry uses). When `obsSetCcr5Valid`, the lock-step
  // whitebox SETS the running CCR to `obsSetCcr5` for this obs step (the only commit
  // path that writes the full CCR outside a normal Wb). Default invalid.
  val obsSetCcr5Valid = Bool();       obsSetCcr5Valid := False
  val obsSetCcr5      = UInt(5 bits); obsSetCcr5      := U(0, 5 bits)
  // True when this obs is an INTERRUPT entry (vs a fault/trap entry or RTE). The
  // lock-step harness drops the separate interrupt-entry record because Musashi's
  // trace BUNDLES the interrupt entry with the first handler instruction in one
  // step (an async interrupt consumes no user instruction); a synchronous
  // fault/trap entry is its own oracle step (the faulting instruction consumed it),
  // so its obs is kept. The post-entry state is still verified by the first handler
  // instruction's commit (it carries the mask-raised SR + decremented A7).
  val obsIsInterrupt = Bool();    obsIsInterrupt := False
  // True when this obs is an exception/trap ENTRY (vs an RTE). The ROB uses it to
  // apply a faulting-instruction CCR fold (CHK) only to the entry step, not RTE.
  val obsIsEntry = Bool();        obsIsEntry := False

  // ── Architectural A7 (int reg 15) write-back. The committed A7 lives in BOTH the
  // SystemState bank (ss.isp/ss.msp/ss.usp) AND the int register file (arch reg 15) the
  // datapath reads. When the exception changes A7 (entry: SSP-=8; RTE: restore +
  // maybe re-bank to USP), it must update reg 15 so the handler's (A7)/disp(A7)
  // stack accesses see the new SP. The full-core wiring connects this to an int PRF
  // write port (phys = committed arch-15 mapping; identity phys-15 while A7 is
  // unrenamed). obsFire qualifies it (same cycle as the event's commit obs). ─────
  val a7WriteValid = Bool();        a7WriteValid := obsFire
  val a7WriteData  = UInt(32 bits); a7WriteData := obsA7

  // ── Generalized arch-reg PRF write (commit-time system op READ direction) ─────
  // MOVE-USP / MOVEC READ (system reg -> Rn) writes an ARBITRARY int arch reg (the Rn),
  // not just A7. The full-core wiring connects this to an int PRF write port at the
  // committed arch->phys mapping (identity while unrenamed in the tested programs). The
  // S_APPLY FSM state pulses it. Default idle.
  val sysRegWriteValid = Bool();        sysRegWriteValid := False;        sysRegWriteValid.simPublic()
  val sysRegWritePhys  = UInt(6 bits);  sysRegWritePhys  := U(0, 6 bits);  sysRegWritePhys.simPublic()
  val sysRegWriteData  = UInt(32 bits); sysRegWriteData  := U(0, 32 bits); sysRegWriteData.simPublic()
  // PFLUSHA: a 1-cycle pulse consumed by DtlbPlugin/ItlbPlugin's `flushAll` port (mirrors
  // the existing `umFlush` top-level fan-out — see FullCoreSynth.scala/the test DUTs).
  // Only PFLUSHA drives this (S_APPLY sysCapKind=7); everything else leaves it False.
  val sysFlushAllValid = Bool();        sysFlushAllValid := False;        sysFlushAllValid.simPublic()

  // ── RTE CCR restore -> REAL flags PRF (task #176, redesigned task-176-regression) ──
  // Fires exactly at RTE's REAL frame pop (R_REDIR, non-throwaway branch — the SAME
  // cycle obsFire/redirectValid fire for RTE). Carries the frame's popped CCR bits
  // {X,N,Z,V,C}.
  //
  // ORIGINAL (task #176) design wrote these into a FRESH rename-allocated pNzvcDst/
  // pXDst (RTE's µop carried writesNzvc/writesX so decode/rename popped new physical
  // registers), then a RobPlugin commit block folded the new arch->phys mapping into
  // nzvcRat/xRat. That mechanism caused a CONFIRMED regression under back-to-back/
  // nested exception storms (exc_stack_atomicity_stress, pea_aline_irq_storm,
  // via1_t1_irq_storm): RTE's OWN freelist pop sits "uncommitted" (from the
  // Freelist's `commHead` perspective) for the ENTIRE multi-cycle R_DRAIN..R_REDIR
  // FSM run, because `flushing` (which gates the ROB's retire0/1 AND, via
  // `rc.flushPort`, RenameStage's RAT-rollback/freelist-flush) stays asserted the
  // WHOLE time via `excSquash`. The freelist's `head := commHead` fires EVERY cycle
  // during that window, so RTE's own not-yet-pushed pNzvcDst/pXDst are treated as
  // still-speculative and their ring slot is handed right back out — and since
  // `IssueQueuePlugin` forces `push.ready` True during its OWN matching flush
  // (`readyReg := True` in its flush branch), the frontend/rename keep firing and
  // popping the SAME (never-advanced) id for the whole window. A wrong-path
  // instruction renamed during this window can therefore receive the EXACT SAME
  // physical nzvc/x register RTE itself is mid-flight with; if that wrong-path uop's
  // EU write lands (it can survive briefly once the IQ's flush finally drops for one
  // cycle before the frontend's OWN redirect lands), it silently clobbers the
  // register RTE's OWN restore -- now also the live nzvcRat/xRat mapping, because of
  // the same commit -- is relying on. Confirmed via direct trace + a bisection
  // matrix: disabling ONLY the RobPlugin commit-block (keeping the write ports)
  // cured all 3 regressions; disabling ONLY the write ports (keeping the commit)
  // did not.
  //
  // NEW design mirrors the ALREADY-PROVEN-SAFE `a7WriteValid`/`a7WriteData` pattern
  // (SystemState's committed A7 restore, ExceptionUnit.scala class-level comment
  // above): NZVC/X are archDepth=1 singleton "architectural registers" whose
  // COMMITTED physical mapping essentially never needs to change here -- only its
  // CONTENTS do. So RTE's µop keeps writesNzvc/writesX FALSE (no rename allocation,
  // no freelist interaction, no RAT remap, no exposure window at all) and the wiring
  // plugins write `rteNzvcWriteData`/`rteXWriteData` DIRECTLY into whatever physical
  // register `RenameStage.committedPhysNzvc`/`committedPhysX` CURRENTLY names (a
  // plain in-place content update, exactly like `a7Wr.address := committedPhysA7`) --
  // see FullCoreSynth.scala/FuzzDut.scala/IpcBenchSpec.scala/ExecuteLockStepSpec.scala.
  // Safe for the same reason A7's direct write is safe: nothing else can be
  // committing to this same physical register while RTE's serializing FSM owns the
  // ROB head (retire0/1 are blocked the whole time), so a same-cycle multi-writer
  // collision cannot occur, and there is no freelist pop/push at all to race.
  val rteNzvcWriteValid = Bool();       rteNzvcWriteValid := False;       rteNzvcWriteValid.simPublic()
  val rteNzvcWriteData  = Bits(4 bits); rteNzvcWriteData  := B(0, 4 bits); rteNzvcWriteData.simPublic()
  val rteXWriteValid    = Bool();       rteXWriteValid    := False;       rteXWriteValid.simPublic()
  val rteXWriteData     = Bool();       rteXWriteData     := False;       rteXWriteData.simPublic()

  // ── SystemState write defaults (the FSM pulses them) ────────────────────────
  ss.setSrSys.valid := False; ss.setSrSys.payload := U(0, 8 bits)
  ss.setIsp.valid   := False; ss.setIsp.payload   := U(0, 32 bits)
  ss.setMsp.valid   := False; ss.setMsp.payload   := U(0, 32 bits)
  ss.setVbr.valid   := False; ss.setVbr.payload   := U(0, 32 bits)
  ss.setUsp.valid   := False; ss.setUsp.payload   := U(0, 32 bits)
  // LIVE-COHERENT committed A7: drive writeA7 EVERY cycle with the live committed A7
  // (routed by committed S,M inside SystemState). This keeps ss.usp/isp/msp mirroring
  // the architectural A7 of the active bank. The FSM's setIsp/setMsp/setUsp pulses on
  // serializing cycles WIN over writeA7 (they are listed AFTER writeA7 in SystemState's
  // when-chain — later-when-wins), so a frame-store SP write is not clobbered.
  // SETTLE CAVEAT (Slice A scope): committedA7In (the PRF readback) lags the architectural
  // A7 by the Mem-write->async-read latency, so the ACTIVE bank tracks A7 with ~1-2 cycle
  // lag. This is invisible to real consumers: the exc FSM reads the bank only after E_DRAIN
  // (settled), and MOVE-to-SR (S,M) switches are serializing. A RAPID M re-toggle
  // (M=0->1->0 within the settle window) is NOT validated here — it could leave a
  // briefly-active bank's shadow stale when deselected. Add a directed test for that before
  // Slice B (interrupt throwaway frame) relies on cross-toggle preservation.
  ss.writeA7.valid  := True; ss.writeA7.payload  := committedA7In

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
  // Task #163: a frame word can land at a D-cache LINE-relative offset of 15 (the
  // last byte of a 16-byte line) whenever frameBase/frameBase2 is ODD-aligned such
  // that (frameBase + step*2) & 0xF == 15 -- the WORD's second byte then belongs to
  // the NEXT cache line entirely. `driveStoreNoXlate`'s plain {paddr,size} store
  // path (DcachePlugin's non-useStrb DcacheByteLane.storeStrb/storeData) has NO
  // cross-line-boundary handling (unlike the ordinary LsEuPlugin store path, which
  // explicitly computes a two-access split for exactly this case) -- it silently
  // DROPS the byte that would fall at offset 16 (storeStrb's `bits` array only spans
  // indices 0..15, so a would-be index-16 strobe bit never gets set) while
  // storeData's `out(off+1)` wraps a 4-bit index mod 16 back to offset 0 (harmless
  // there ONLY because storeStrb correctly leaves that merged byte un-strobed).  Net
  // effect: the crossing word's SECOND byte is never written -- neither into the
  // cache nor the AXI write-through -- leaving stale memory content, discovered via
  // exc_aline_odd_sp_mmu_dcache (frameBase=0xFFFD lands word[1]=PC[31:16] at line
  // offset 15..16). FIX: when a frame word would cross, split it into two ordinary
  // (non-crossing, single-byte) driveStoreNoXlate pushes instead of one WORD push.
  // `stSplitLow` sequences the second (low) byte of a just-split word.
  val stSplitLow = Reg(Bool()) init False
  // lastStep: curIs7/curIs2 are always False for an interrupt entry (never $7/$2),
  // so this already correctly reads 4 words (U(3)) for BOTH passes of a throwaway
  // entry (frame $0 then frame $1, each 8 bytes) — no stFrame2 dependency needed.
  val lastStep = Mux(curIs7, U(29, 5 bits), Mux(curIs2, U(5, 5 bits), U(3, 5 bits)))
  // Task #132: while stacking the 2nd (throwaway) frame, address off frameBase2
  // (ISP-relative) instead of frameBase (MSP-relative).
  def frameWordAddr(step: UInt): UInt = (Mux(stFrame2, frameBase2, frameBase) + (step << 1)).resized
  // Common low-4-word prefix: SR, PC hi, PC lo, format/vector word. The format/vector
  // word is curVec<<2 for $0, 0x2000|(curVec<<2) for $2, 0x7000|(curVec<<2) for $7,
  // 0x1000|(curVec<<2) for the $1 throwaway frame (task #132; stFrame2 selects it —
  // curIs7/curIs2 are already False for any interrupt, so no conflict with those).
  val fmtVecWord = Mux(stFrame2,        (U(0x1000, 16 bits) | (curVec << 2).resize(16)),
                   Mux(curIs7, (U(0x7000, 16 bits) | (curVec << 2).resize(16)),
                   Mux(curIs2, (U(0x2000, 16 bits) | (curVec << 2).resize(16)),
                               (curVec << 2).resize(16))))
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
    val R_DRAIN   = new State    // wait for the SQ to drain + the live-A7 readback to
                                  // settle before capturing frameBase (mirrors E_DRAIN)
    val R_SRREQ   = new State    // load SR word @ base+0
    val R_SRWAIT  = new State
    val R_PCREQ   = new State    // load PC long @ base+2
    val R_PCWAIT  = new State
    val R_FMTREQ  = new State    // load format word @ base+6 (select $0 vs $7 pop)
    val R_FMTWAIT = new State
    val R_REDIR   = new State
    // Commit-time SYSTEM-op path (MOVE-to-SR / MOVE-USP / MOVEC). S_APPLY writes the
    // committed system state + the int PRF (read dir) in one cycle; S_REDIR pulses the
    // obs (post-state) + redirects (so the re-banked A7 / new S settle before the obs).
    val S_APPLY   = new State
    val S_REDIR   = new State

    IDLE.whenIsActive {
      when(entryTrigger) {
        // An INTERRUPT entry is always a format-$0 frame (never $7/$2), regardless
        // of its vector value (autovector 24+level or a vectored 0..255). Fault/trap
        // entries select $7 (access fault, vector 2) / $2 (TRAPV, vector 7) by vector.
        val is7 = !entryIsInterrupt && (entryVector === 2)   // access fault -> format-$7
        // The 68040 group-2 traps stack a 6-word format-$2 frame {SR, PC(=nextPc),
        // 0x2000|vec<<2, PPC} (Musashi m68ki_stack_frame_0010): TRAPV (vector 7), CHK
        // (vector 6), DIV0/integer-divide-by-zero (vector 5), AND F-line (vector 11).
        //
        // F-line's inclusion here is DISPUTED, not settled — flagging honestly rather
        // than re-asserting task #176's original "confirmed" framing, which a code
        // review found overconfident. Musashi's own m68ki_exception_1111 unconditionally
        // uses format-$0 for vector 11 regardless of CPU_TYPE, and the MC68040 User's
        // Manual (9.6.1) ties format-$2 specifically to a RECOGNIZED-but-hardware-
        // unimplemented FPU coprocessor-ID-1 opcode (cpID = op[11:9] == 001, Table
        // 9-10) — a genuinely illegal/unrecognized F-line opcode (which is arguably ALL
        // this FPU-less core can ever produce, since it has zero FPU decode) should by
        // that reading stack format-$0 instead, matching Musashi. BUT the vendored
        // m68k-ooo ported test `exc_user_vbr_rte_matrix.s` hardcodes an explicit
        // `cmp.l #0x0001FFF4,%a7` check (i.e. format-$2, 12 bytes) for its own F-line
        // opcode (`0xF123`, cpID=000 — the "illegal" case by the manual reading above),
        // and this project's standing goal is to match the m68k-ooo test corpus. Kept
        // format-$2 here (diverging from Musashi, like the already-established CPUSH
        // gap below) to match the test corpus; the corresponding ExecuteLockStepSpec
        // "line-F opcode" test uses `pcOnly` for the same reason. If a future session
        // gets more definitive primary-source clarity (e.g. finding real 68040 silicon
        // or a more complete manual excerpt that resolves the cpID question), revisit
        // this — it's a genuine unresolved disagreement between two sources of truth,
        // not a confidently-verified fact either direction.
        val is2 = !entryIsInterrupt &&
                  ((entryVector === 7) || (entryVector === 6) || (entryVector === 5) || (entryVector === 11))
        // PPC: supplied explicitly (variable-length CHK/DIV0); fall back to entryPc-2
        // for callers that don't pass it (the TRAPV-only unit tests, 2-byte op).
        val ppc = if (entryPpc != null) entryPpc else (entryPc - 2).resized
        curVec    := entryVector
        curPc     := entryPc
        curIs7    := is7
        curIs2    := is2
        curIsInt  := entryIsInterrupt
        curLevel  := entryIplLevel
        curPpc    := ppc.resized
        curFault  := entryFaultAddr
        // SSW = (in_mmu 0x400) | fc | (rw<<8); fc = data space (bit0=1) + supervisor
        // (bit2) if a supervisor access; rw = read?1:write?0 (MAME m68ki_aerr).
        // The faulting access's privilege = the PRE-exception S bit (SR bit13 =
        // srSys bit5), read here BEFORE the FSM sets S. (The LS EU's translate-time
        // supervisor flag — reqDrvSup, PrivilegeService-driven since the I/D-side MMU
        // privilege fix — now tracks this same architectural S bit too, so the two
        // agree; the SSW still reads the SR directly here to stay exactly byte-for-byte
        // with the MAME oracle, which reads the SR S bit, not a future MOVES/SFC mode.)
        // entryFaultSup is retained for a future MOVES/SFC-driven mode.
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
        // new SP = supervisor bank (M?MSP:ISP) - frame size
        //   format-$0 = 8 bytes, format-$2 = 12 bytes, format-$7 = 60 bytes.
        // (PRELIMINARY value; recomputed from the settled live bank on E_DRAIN->E_STORE.)
        // new SP = current supervisor stack (M ? MSP : ISP) - frame size. M is PRESERVED
        // across fault/trap entry (the &0x3f mask in E_REDIR keeps bit4), so the
        // post-stack SP is written back to this SAME bank.
        val supSp = Mux(ss.m, ss.msp, ss.isp)
        val nb = Mux(is7, supSp - 60, Mux(is2, supSp - 12, supSp - 8))
        frameBase := nb
        // Task #132: an INTERRUPT taken while M=1 needs the format-$1 throwaway
        // frame. ss.m itself has no readback lag (it's a plain srSys bit, unlike
        // ss.msp/ss.isp which mirror the committed-A7 PRF readback with settle
        // latency) — safe to read live here, same as is7/is2 above.
        curThrowaway := entryIsInterrupt && ss.m
        stFrame2     := False
        // compute vector fetch base = VBR + vec*4
        vecTarget := (ss.vbr + (entryVector << 2)).resized
        stStep    := 0
        stSplitLow := False   // task #163: clear any split-word carry from a prior entry
        goto(E_DRAIN)
      } elsewhen(rteTrigger) {
        // RTE reads the frame at the CURRENT A7 (SSP). Do NOT capture frameBase yet —
        // ss.msp/ss.isp mirror the committed-A7 PRF readback with the SAME ~1-cycle
        // settle latency the ENTRY path's E_DRAIN state exists to wait out (see its
        // comment). An RTE whose immediately-preceding instruction is an ordinary
        // renamed A7-modifying store (e.g. a hand-built frame pushed via `move -(%a7)`,
        // as opposed to the exception FSM's OWN internal E_STORE writes, which apply
        // synchronously and were therefore always already-settled by the time a LATER
        // RTE observed them) would otherwise capture a STALE pre-push frameBase here,
        // reading garbage SR/PC/format from the wrong stack address — a wild-PC hang.
        // Route through R_DRAIN first (mirrors E_DRAIN) to wait for the SQ to drain
        // and recompute frameBase from the SETTLED bank.
        // Task #177: latch RTE's own PC NOW (this cycle, before excActive/excSquash
        // starts reusing the ROB slot h0 still points at — see rteCapPc's comment).
        rteCapPc := rtePc
        goto(R_DRAIN)
      } elsewhen(sysTrigger) {
        // Commit-time SYSTEM op (supervisor; the user-mode case is a vector-8 fault via
        // entryTrigger). Latch the captured context; apply next cycle.
        sysCapKind    := sysKind
        sysCapReadDir := sysReadDir
        sysCapVal     := sysVal
        sysCapRc      := sysRc
        sysCapDstPhys := sysDstPhys
        sysCapPc      := sysPc
        sysCapNextPc  := sysNextPc
        goto(S_APPLY)
      }
    }

    // Wait for older committed stores to fully drain before we use the store port.
    E_DRAIN.whenIsActive {
      when(sqDrained) {
        // RECOMPUTE frameBase from the SETTLED live supervisor bank (M ? MSP : ISP).
        // The IDLE entry-capture computed frameBase from ss.supBank on the IDLE->entry
        // edge, but the live committed-A7 readback has a 1-cycle latency, so that early
        // value can be stale. By E_DRAIN->E_STORE the readback has settled, so recompute
        // from the current bank: new SP = supervisor bank (M?MSP:ISP) - frame size
        // (format-$0 = 8, format-$2 = 12, format-$7 = 60 bytes).
        val supSp = Mux(ss.m, ss.msp, ss.isp)
        frameBase := Mux(curIs7, supSp - 60, Mux(curIs2, supSp - 12, supSp - 8))
        // Task #132: the throwaway ($1) frame's base — settled ISP minus 8 bytes.
        // ss.m is True here (curThrowaway only set when it was), so ss.isp is
        // exactly the bank that will become active once M clears — same settle
        // treatment as frameBase above (read from the settled bank at E_DRAIN, not
        // the possibly-stale IDLE-time snapshot).
        frameBase2 := ss.isp - 8
        goto(E_STORE)
      }
    }

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
      // Task #163: split a line-crossing word (line-relative offset 15) into two
      // single-byte pushes — see stSplitLow's doc comment above.
      val addr    = frameWordAddr(stStep)
      val data    = frameWordData(stStep)
      val crosses = addr(3 downto 0) === U(15, 4 bits)
      when(stSplitLow) {
        driveStoreNoXlate(addr + U(1, 32 bits), Size.BYTE, data(7 downto 0))
      } elsewhen(crosses) {
        driveStoreNoXlate(addr, Size.BYTE, data(15 downto 8))
      } otherwise {
        driveStoreNoXlate(addr, Size.WORD, data)
      }
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
        val addr    = frameWordAddr(stStep)
        val crosses = addr(3 downto 0) === U(15, 4 bits)
        when(crosses && !stSplitLow) {
          // Just issued the HIGH byte of a split word; issue the LOW byte next
          // (same stStep, same frame word — do not advance).
          stSplitLow := True
          goto(E_STORE)
        } otherwise {
          stSplitLow := False
          when(stStep === lastStep) {
            // Task #132: after finishing frame $0 (stFrame2 still False), a throwaway
            // entry loops back into E_STORE for the SECOND ($1) frame instead of
            // proceeding to the vector fetch. frameWordAddr/fmtVecWord above already
            // switch to frameBase2/format-1 once stFrame2 is True.
            when(curThrowaway && !stFrame2) {
              stFrame2 := True; stStep := 0; goto(E_STORE)
            } otherwise {
              goto(E_VECREQ)
            }
          } otherwise { stStep := stStep + 1; goto(E_STORE) }
        }
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
      // NB: ss.writeA7 (live readback) also fires every cycle, but setIsp/setMsp here
      // WIN by SystemState's later-when ordering — this serializing SP write is authoritative.
      // Task #132: a throwaway entry stacked to BOTH banks (frame $0 -> MSP, frame $1
      // -> ISP) — write BOTH, not just the (single, old-ss.m-selected) bank the
      // non-throwaway path uses.
      when(curThrowaway) {
        ss.setMsp.valid := True; ss.setMsp.payload := frameBase
        ss.setIsp.valid := True; ss.setIsp.payload := frameBase2
      } .elsewhen(ss.m) { ss.setMsp.valid := True; ss.setMsp.payload := frameBase }
      .otherwise { ss.setIsp.valid := True; ss.setIsp.payload := frameBase }
      // enter supervisor, clear trace: set S (bit5), clear T1/T0 (bits 7,6).
      // For an INTERRUPT entry ALSO raise the SR I-mask (bits 2:0) to the interrupt
      // level so equal/lower interrupts are held until RTE (NMI sets 7); fault/trap
      // entries leave the mask unchanged. (newSysBase clears S/T only; the mask bits
      // 2:0 are preserved for faults, overwritten with curLevel for interrupts.)
      // Task #132: a throwaway entry ALSO clears M (bit4) here — the handler runs
      // on ISP with M=0; M=1 only comes back when RTE re-pops the $1 frame's SR.
      val keepMask = Mux(curThrowaway, U(0x2f, 8 bits), U(0x3f, 8 bits))
      val newSysBase = (ss.srSys | U(0x20, 8 bits)) & keepMask
      val newSys = Mux(curIsInt,
                       (newSysBase & U(0xf8, 8 bits)) | curLevel.resize(8),
                       newSysBase)
      ss.setSrSys.valid := True; ss.setSrSys.payload := newSys
      redirectValid := True
      redirectPc    := vecTarget
      // commit observation: the faulting instruction's trace step == handler entry
      // with the post-exception SR system byte (S set, T cleared) + A7 = new SSP.
      // (CCR is unchanged by the exception -> the whitebox carries it.) For a
      // throwaway entry the HANDLER runs on ISP (M now 0), so obsA7 = frameBase2
      // (the new ISP), not frameBase (the now-inactive MSP result).
      obsFire    := True
      obsIsEntry := True
      obsPc      := vecTarget
      obsSysByte := newSys
      obsA7      := Mux(curThrowaway, frameBase2, frameBase)
      obsIsInterrupt := curIsInt
      goto(IDLE)
    }

    // ── RTE: pop the frame, restore SR + PC, SSP += 8, redirect ─────────────────
    // Wait for older committed stores to fully drain (mirrors E_DRAIN) before reading
    // the live supervisor-bank A7 for frameBase -- see the rteTrigger comment above.
    R_DRAIN.whenIsActive {
      when(sqDrained) {
        frameBase := Mux(ss.m, ss.msp, ss.isp)   // RTE reads the SETTLED current supervisor stack
        goto(R_SRREQ)
      }
    }
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
        // (12 bytes), 1 => format-$1 throwaway (task #132, see below), else format-$0
        // (8 bytes).
        val nib = dcLoadRsp.payload.data(15 downto 12).asUInt
        // This core only ever STACKS formats $0/$1/$2/$7 (see frameWordData/fmtVecWord
        // above) — any OTHER format nibble in a popped frame is malformed (hand-built,
        // corrupted, or a format this core never produces) and real 68020+ silicon
        // raises the format-error exception (vector 14) instead of blindly restoring
        // SR/PC from it (task #170-cluster10; previously there was NO vector-14
        // dispatch anywhere — a bad format nibble silently fell through to the
        // format-$0 8-byte-pop default and mis-executed). Musashi/PRM semantics: the
        // malformed frame is left UNTOUCHED on the stack (SR/PC are never applied from
        // it), and a NEW format-$0 frame is pushed BELOW it for vector 14, with the
        // saved PC = the address of the RTE instruction itself (so a vec-14 handler
        // that patches the frame and RTEs again re-attempts the SAME original RTE).
        popFmtWord := dcLoadRsp.payload.data(15 downto 0).asUInt
        popIs7 := nib === U(7, 4 bits)
        popIs2 := nib === U(2, 4 bits)
        popIs1 := nib === U(1, 4 bits)
        val fmtOk = (nib === U(0, 4 bits)) || (nib === U(1, 4 bits)) ||
                    (nib === U(2, 4 bits)) || (nib === U(7, 4 bits))
        when(fmtOk) {
          goto(R_REDIR)
        } otherwise {
          // Synthesize a vector-14 format-$0 ENTRY, reusing the normal E_* frame-push
          // path unchanged (mirrors the IDLE->entryTrigger setup above, specialized to
          // a non-interrupt/non-$7/non-$2/non-throwaway format-$0 case).
          curVec       := U(14, 8 bits)
          // Task #177: use the LATCHED rteCapPc (captured at rteTrigger), not the
          // live `rtePc` wire — by this state (many cycles past rteTrigger) the ROB
          // slot h0 pointed at has been continuously reused by excSquash's per-cycle
          // `tail := head`, so a late live read of `rtePc` no longer reflects RTE's
          // own PC.
          curPc        := rteCapPc
          curIs7       := False
          curIs2       := False
          curIsInt     := False
          curLevel     := U(0, 3 bits)
          curPpc       := rteCapPc
          curFault     := U(0, 32 bits)
          curThrowaway := False
          stFrame2     := False
          oldSr        := (ss.srSys ## committedCcr.resize(8 bits)).asUInt
          vecTarget    := (ss.vbr + (U(14, 8 bits) << 2)).resized
          stStep       := 0
          stSplitLow   := False
          goto(E_DRAIN)
        }
      }
    }
    R_REDIR.whenIsActive {
      // restore the SR (system byte) — same mechanism for BOTH the throwaway ($1)
      // pop and the real pop; only WHICH bytes were popped / what happens next differs.
      ss.setSrSys.valid := True; ss.setSrSys.payload := popSr(15 downto 8)
      when(popIs1) {
        // Task #132: this was the format-$1 THROWAWAY frame — its PC is discarded
        // (Musashi: m68ki_fake_pull_32 for the PC). Its SR still carries M=1 (it was
        // captured before M was cleared at entry), so applying it re-banks A7 back
        // to MSP. Reclaim the frame's 8 bytes on the CURRENT (ISP) bank — we are
        // guaranteed supervisor here (RTE only runs at S=1) and M was 0 while the
        // handler ran, so ISP was the active bank; frameBase is exactly where THIS
        // frame was popped from. Do NOT redirect / do NOT fire an obs — this is not
        // a real completion yet (Musashi's `goto rte_loop`, still inside ONE rte
        // instruction). Re-read ss.msp: it was untouched since entry (only ISP/M
        // moved while M=0), so it is exactly frame $0's base — loop back to pop it.
        ss.setIsp.valid := True; ss.setIsp.payload := frameBase + 8
        frameBase := ss.msp
        goto(R_SRREQ)
      } .otherwise {
        // SSP += frame size (8 for $0/$1, 12 for $2, 60 for $7). The CURRENT A7 is
        // SSP (we were supervisor); after restoring SR the bank may switch to USP, so
        // write SSP explicitly. For $7 the popPc is the faulting instruction's PC ->
        // RTE resumes by RE-EXECUTING it (the handler has fixed the mapping), matching
        // MAME. For a throwaway's SECOND (real, format-$0) pop, popIs1 is False here
        // (this frame's own format nibble is 0) — the bank write below correctly
        // targets ss.m, which by this point already reads True (the throwaway pass's
        // setSrSys landed a cycle ago, restoring M=1) — same code path as any other
        // format-$0 RTE, no throwaway-specific branch needed.
        val newSsp = frameBase + Mux(popIs7, U(60, 32 bits), Mux(popIs2, U(12, 32 bits), U(8, 32 bits)))
        when(ss.m) { ss.setMsp.valid := True; ss.setMsp.payload := newSsp }
        .otherwise { ss.setIsp.valid := True; ss.setIsp.payload := newSsp }
        redirectValid := True
        redirectPc    := popPc
        // task #176 (redesigned, task-176-regression): restore the popped CCR
        // {X,N,Z,V,C} into the REAL flags PRF (not just committedCcr) so a later
        // Bcc/flag-reader actually observes it -- a DIRECT in-place write into
        // whatever physical register is CURRENTLY nzvcRat/xRat's committed mapping
        // (see the class-level doc comment on rteNzvcWriteValid above), driven by the
        // wiring plugins exactly like a7WriteValid/a7WriteData. SR bit layout: bit4=X,
        // bits3..0=N,Z,V,C (matches our internal 4-bit nzvc field).
        rteNzvcWriteValid := True
        rteNzvcWriteData  := popSr(3 downto 0).asBits
        rteXWriteValid    := True
        rteXWriteData     := popSr(4)
        // commit observation: RTE's trace step == restored PC + restored SR sysByte
        // + A7. A7 after RTE = popped-SSP if S restored supervisor, else USP. The CCR
        // is restored from the frame too, but the whitebox carries it (RTE restores
        // the same CCR the matching exception entry saved -> reconstructed CCR holds).
        obsFire    := True
        obsPc      := popPc
        obsSysByte := popSr(15 downto 8)
        // A7 after RTE = restored-(S,M) bank. popSr(13)=S, popSr(12)=M. For a plain
        // (non-throwaway) format-$0/$2/$7 RTE, M is unchanged so the popped bank ==
        // the restored supervisor bank and obsA7 resolves to Mux(S, newSsp, usp) —
        // identical to the old (pre-#132) behavior. For a throwaway's SECOND pop,
        // popSr(12) is again M=1 (frame $0's own captured SR) and ss.m already reads
        // True (set by the first pass), so poppedSameBank is True and obsA7 = newSsp
        // (the fully-unwound MSP) — correct, matches the "final A7==MSP_TOP" spec.
        val poppedSameBank = (popSr(12) === ss.m)
        val rsupBank = Mux(popSr(12), ss.msp, ss.isp)
        obsA7 := Mux(popSr(13), Mux(poppedSameBank, newSsp, rsupBank), ss.usp)
        goto(IDLE)
      }
    }

    // ── Commit-time SYSTEM op: APPLY the effect to committed state (1 cycle) ──────
    // sysCapKind: 1=MOVE-to-SR, 2=MOVE-USP, 3=MOVEC. The write direction's source value
    // is sysCapVal; the read direction writes the int PRF arch-reg (sysRegWrite*).
    S_APPLY.whenIsActive {
      switch(sysCapKind) {
        is(U(1, 3 bits)) {                          // MOVE to SR : sysVal.W -> SR
          // System byte = sysVal[15:8], CCR = sysVal[4:0]. Writing srSys may flip S ->
          // A7 re-banks. The committed CCR is tracked in the ROB; we surface the new SR
          // (sysByte) in the obs, and the ROB folds sysVal[4:0] into committedCcr (so the
          // whitebox's running CCR resyncs). Re-bank A7: write the int PRF arch-15 with
          // the NEW-S bank's value (computed from the post-write S in S_REDIR via ss.a7).
          ss.setSrSys.valid := True; ss.setSrSys.payload := sysCapVal(15 downto 8).asUInt
        }
        is(U(2, 3 bits)) {                          // MOVE USP : An<->USP
          when(sysCapReadDir) {                     // USP -> An : write the int PRF[pdst]
            sysRegWriteValid := True
            sysRegWritePhys  := sysCapDstPhys
            sysRegWriteData  := ss.usp
          } otherwise {                             // An -> USP : write the USP bank
            ss.setUsp.valid := True; ss.setUsp.payload := sysCapVal.asUInt
          }
        }
        is(U(3, 3 bits)) {                          // MOVEC : Rc<->Rn
          when(sysCapReadDir) {                     // Rc -> Rn : read the committed reg
            sysRegWriteValid := True
            sysRegWritePhys  := sysCapDstPhys
            // Rc id: VBR=0x801, USP=0x800, SFC=0x000, DFC=0x001 (3-bit, zero-extended),
            // CACR=0x002 (RAZ), TCR=0x003 (E bit only — bit 15; P/page-size + other
            // bits RAZ, this core is 4K-pages-only, task #131), URP=0x806, SRP=0x807,
            // MSP=0x803, ISP=0x804 (task #170-cluster10: MSP/ISP banking itself already
            // works via the S/M-bit A7 Mux -- ss.msp/ss.isp ARE the real committed
            // registers backing it -- but they were not yet separately MOVEC-addressable;
            // exposing them here is a direct passthrough to those same registers, no new
            // storage). SFC/DFC are real 3-bit committed regs (Musashi reads them
            // zero-extended; round-trips with the write below). ITT0/ITT1/DTT0/DTT1 are
            // NOT modeled (RAZ via default) — deferred, no transparent-translation
            // windows yet.
            sysRegWriteData  := sysCapRc.mux(
              U(0x801, 12 bits) -> ss.vbr,
              U(0x800, 12 bits) -> ss.usp,
              U(0x803, 12 bits) -> ss.msp,
              U(0x804, 12 bits) -> ss.isp,
              U(0x000, 12 bits) -> ss.sfc.resize(32),
              U(0x001, 12 bits) -> ss.dfc.resize(32),
              U(0x003, 12 bits) -> Mux(mmuCtrl.mmuEnable, U(0x8000, 32 bits), U(0, 32 bits)),
              U(0x002, 12 bits) -> ss.cacr,
              U(0x004, 12 bits) -> ss.itt0,
              U(0x806, 12 bits) -> mmuCtrl.urp,
              U(0x807, 12 bits) -> mmuCtrl.srp,
              default           -> U(0, 32 bits))   // other unmodeled Rc -> RAZ (read 0)
          } otherwise {                             // Rn -> Rc : write the committed reg
            switch(sysCapRc) {
              is(U(0x801, 12 bits)) { ss.setVbr.valid := True; ss.setVbr.payload := sysCapVal.asUInt }
              is(U(0x800, 12 bits)) { ss.setUsp.valid := True; ss.setUsp.payload := sysCapVal.asUInt }
              // SFC/DFC: write the low 3 bits (Musashi masks `& 7`); upper bits ignored.
              is(U(0x000, 12 bits)) { ss.setSfc.valid := True; ss.setSfc.payload := sysCapVal(2 downto 0).asUInt }
              is(U(0x001, 12 bits)) { ss.setDfc.valid := True; ss.setDfc.payload := sysCapVal(2 downto 0).asUInt }
              // CACR (0x002): real committed storage (task #170-cluster10), round-
              // trippable via MOVEC but with NO functional effect on the D-cache (it
              // always caches regardless — matches this core's pre-existing behavior,
              // only the storage/round-trip half was missing). Unlike TCR/URP/SRP
              // (0x003/0x806/0x807, still WI below) CACR has no page-walker consumer,
              // so it doesn't share the sim-poke-persistence regression risk that got
              // task #131's TCR/URP/SRP write mechanism reverted.
              is(U(0x002, 12 bits)) { ss.setCacr.valid := True; ss.setCacr.payload := sysCapVal.asUInt }
              // ITT0 (0x004), task #180: real committed storage (mirrors CACR exactly),
              // round-trippable via MOVEC with NO functional transparent-translation
              // effect — this core's ITLB has no TT-window matching logic (deferred,
              // much larger gap; see the Cluster-7 MMU findings). ITT1/DTT0/DTT1 stay
              // unimplemented (RAZ/WI via default below) — no test in this corpus
              // exercises them individually.
              is(U(0x004, 12 bits)) { ss.setItt0.valid := True; ss.setItt0.payload := sysCapVal.asUInt }
              // MSP (0x803) / ISP (0x804), task #170-cluster10: direct writes to the
              // SAME committed registers the S/M-bit A7 Mux already reads (ss.msp/
              // ss.isp) — no new storage, this is purely exposing the existing bank
              // registers as MOVEC-addressable. Low regression risk (unlike TCR/URP/
              // SRP below): nothing outside SystemState's own A7 Mux consumes these.
              is(U(0x803, 12 bits)) { ss.setMsp.valid := True; ss.setMsp.payload := sysCapVal.asUInt }
              is(U(0x804, 12 bits)) { ss.setIsp.valid := True; ss.setIsp.payload := sysCapVal.asUInt }
              // TCR (0x003) / URP (0x806) / SRP (0x807) / other: WI (write-ignored,
              // RAZ-WI). Real-write support was ATTEMPTED (task #131) and REVERTED
              // after a confirmed sim-poke-persistence regression — see
              // MmuControlPlugin's doc comment. The READ side above still surfaces
              // whatever urp/srp/mmuEnable currently hold (sim-poke or default).
            }
          }
        }
        is(U(4, 3 bits)) {                          // RESET : no architectural state change
          // The external reset line is not modeled for lock-step; RESET is an internal NOP.
          // S_REDIR just advances PC (the obs carries the UNCHANGED sysByte + A7).
        }
        is(U(5, 3 bits)) {                          // STOP : SR := sysVal[15:0]
          // Identical SR write to MOVE-to-SR: system byte = sysVal[15:8] (S/T/I incl. the
          // new I-mask), CCR = sysVal[4:0]. A7 re-banks on an S flip (S_REDIR via ss.a7).
          // The HALT itself is the ROB `stopped` state (set on the STOP sysRetire).
          ss.setSrSys.valid := True; ss.setSrSys.payload := sysCapVal(15 downto 8).asUInt
        }
        is(U(6, 3 bits)) {                          // CPUSH : no cache hierarchy modeled
          // No cache to push/invalidate in this core — an internal NOP, like RESET.
        }
        is(U(7, 3 bits)) {                          // PFLUSHA : flush all ATC/TLB entries
          // A REAL effect, unlike CPUSH/RESET — pulses the 1-cycle flushAll signal that
          // DtlbPlugin/ItlbPlugin clear their TLB + walk-result latch on.
          sysFlushAllValid := True
        }
      }
      goto(S_REDIR)
    }
    // S_REDIR: the system-state writes from S_APPLY have now COMMITTED (a cycle later),
    // so ss.a7 / ss.srSys reflect the new state. Pulse the obs (post-state sysByte +
    // re-banked A7) + write the int PRF arch-15 with the re-banked A7 (a MOVE-to-SR S
    // flip switches the active bank), and redirect to the next instruction (serialize).
    S_REDIR.whenIsActive {
      // Re-bank A7 in the int PRF: ss.a7 = Mux(s, Mux(m, msp, isp), usp) with the
      // POST-write (S,M). The
      // a7Write port is qualified by obsFire (below). For MOVE-to-SR this carries the
      // user/supervisor SP switch into the datapath's arch-15. For MOVE-USP/MOVEC that
      // wrote USP while in supervisor, ss.a7 (=isp/msp) is unchanged -> a harmless re-write.
      obsFire    := True
      obsPc      := sysCapNextPc            // the sysOp's commit step == its nextPc
      obsSysByte := ss.srSys                // post-write system byte (S/T/I)
      obsA7      := ss.a7                   // re-banked A7 (Mux on post-write S)
      // MOVE-to-SR (sysCapKind==1) AND STOP (sysCapKind==5) write the full CCR (sysVal[4:0])
      // -> surface it so the whitebox resyncs its running CCR to this absolute value. Other
      // sysOps (MOVE-USP/MOVEC/RESET) leave CCR untouched.
      when(sysCapKind === U(1, 3 bits) || sysCapKind === U(5, 3 bits)) {
        obsSetCcr5Valid := True
        obsSetCcr5      := sysCapVal(4 downto 0).asUInt
      }
      redirectValid := True
      redirectPc    := sysCapNextPc
      goto(IDLE)
    }
  }

  // `active` high whenever the FSM is mid-sequence (not IDLE).
  active := !fsm.isActive(fsm.IDLE)

  // ---- debug-only observability (task #139 wild-PC / a7-minus-8 investigation) ----
  // Zero synth impact (sim tap only, not referenced by any RTL logic).
  active.simPublic()
  redirectValid.simPublic(); redirectPc.simPublic()
  val dbgFsmIsIdle    = fsm.isActive(fsm.IDLE);      dbgFsmIsIdle.simPublic()
  val dbgFsmIsEDrain  = fsm.isActive(fsm.E_DRAIN);   dbgFsmIsEDrain.simPublic()
  val dbgFsmIsEStore  = fsm.isActive(fsm.E_STORE);   dbgFsmIsEStore.simPublic()
  val dbgFsmIsEStWait = fsm.isActive(fsm.E_STWAIT);  dbgFsmIsEStWait.simPublic()
  val dbgFsmIsEVecReq = fsm.isActive(fsm.E_VECREQ);  dbgFsmIsEVecReq.simPublic()
  val dbgFsmIsEVecWait= fsm.isActive(fsm.E_VECWAIT); dbgFsmIsEVecWait.simPublic()
  val dbgFsmIsERedir  = fsm.isActive(fsm.E_REDIR);   dbgFsmIsERedir.simPublic()
}
