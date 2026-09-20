package m68k040.services

import m68k040.types.CommitTrace
import m68k040.cache.{DTranslationCmd, DTranslationRsp, FetchCmd, FetchRsp, TranslationReq, TranslationRsp}
import m68k040.frontend.DecodePacket
import m68k040.decode.DecodedUop
import m68k040.rename.RenamedUop
import m68k040.rob.CommitSlot
import spinal.core._
import spinal.lib.{Stream, Flow}

/** Catalog of cross-plugin service interfaces (spec invariant #3 / Appendix B).
  * A plugin implements a trait and registers via addService(this); consumers
  * resolve it with host[ServiceName]. Grown as real plugins are added. */

/** Rename exposes; ROB drives. commitPorts = 2-wide retire commit+free; flushPort = rollback. */
trait RenameCommitService {
  def commitPorts: Vec[Flow[CommitSlot]]   // length 2
  def flushPort:   Bool
}

/** Rename-owned committed architectural mappings for coherent debug access.
  *
  * These are the committed RAT entries only; speculative location/bypass state is
  * deliberately absent. `DebugCtrlPlugin` selects one mapping at a time and feeds it
  * to its single shared PRF read/write port while the ROB reports effective halt.
  * PRODUCER: `RenameStage` (exactly one). */
trait CommittedMapService {
  def intPhys:  Vec[UInt] // D0-D7, A0-A7, then internal temps; debug uses 0..15
  def nzvcPhys: UInt      // singleton committed NZVC mapping
  def xPhys:    UInt      // singleton committed X mapping
}

/** Committed FP mappings for precise exception operand capture.
  * PRODUCER: RenameStage, exclusively. Consumers must not use speculative RAT
  * mappings or writeback bypasses for an architectural exception frame. */
trait CommittedFpMapService {
  def fpPhys: Vec[UInt] // FP0..FP7, committed physical locations
}

/** Memory attributes for a nonprivileged instruction using the serialized
  * backend port. PRODUCER: RobPlugin exclusively. Inactive preserves the
  * existing supervisor exception-frame behavior. Cache mode is the translated
  * page mode with the architectural CACR.DE policy already applied. */
trait SerializedMemoryContextService {
  def instructionActive: Bool
  def instructionSupervisor: Bool
  def instructionCacheMode: m68k040.cache.CacheMode.C
}

/** ROB exposes; lock-step harness / sinks consume. Up to 2 retired instr/cycle. */
trait CommitTraceService {
  def trace:     Vec[CommitTrace]   // length 2
  def traceFire: Vec[Bool]          // length 2
}

/** Produced by the redirect/flush owner (commit/branch); consumed by frontend. */
trait FlushService {
  def doFlush: Bool
  def flushPc: UInt
}

/** Owned by the ROB (commit-time mispredict). Consumed by rename/IQ/frontend.
  * doFlush is a REGISTERED pulse (FMax: drives only pointer/bitmap resets). */
trait RedirectService {
  def doFlush: Bool
  def flushPc: UInt
}

/** ROB-owned STOP/fatal-halt state localized at the frontend boundary.
  *
  * `active` is the current architectural state (`stopped || coreHalted`) and is
  * observation/assertion-only at the frontend. `next` is the exact ROB next-state
  * truth captured by FetchAlign so its local quiesce register changes on the SAME
  * edge as the ROB state, without the remote active bit entering the live fetch
  * command cone. RobPlugin is the sole producer.
  */
trait FrontendQuiesceService {
  def active: Bool
  def next: Bool
}

/** Registered frontend PC-breakpoint matcher configuration and skip consumption.
  *
  * The configuration is debug-domain state owned by `DebugCtrlPlugin`; this service
  * copies it into frontend-local registers and stamps match metadata into ordinary uop
  * payloads. The returned pulse reports that a skip-once match entered the registered
  * decode push stage. PRODUCER: `DecodeStage` (exactly one). */
trait FrontendDebugMatchService {
  def configure(pcs: Vec[UInt], enables: Bits, skipOnce: Bits): Unit
  def skipConsumed: Bits // one pulse bit per slot; supports two-wide frontend consumption
}

/** ROB-owned debug halt/resume/step state (design spec
  * `docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md` section 6, Stage 2).
  * `DebugCtrlPlugin` is the sole consumer. Commands enter through `request`, never by
  * reaching into RobPlugin internals; the ROB remains the sole producer of readback. */
trait DebugCommitService {
  def effectiveHalt:    Bool
  def autoHaltLatched:  Bool
  def haltReasonDebug:  UInt
  def livePc:           UInt
  def lastPc:           UInt
  def macroCount:       UInt
  def haltHitInstCount: UInt
  def haltAfterConsumed: Bool
  def haltHitPc:         UInt
  def breakpointHit:     Flow[UInt] // payload is the two-bit slot
  def exceptionPending:  Bool
  def haltExceptionVector: UInt
  def haltExceptionPc:   UInt
  def haltExceptionFaultAddress: UInt
  /** 2026-09-09 double-bus-fault capture, backing the two debug registers the regmap has
    * reserved since Stage 1 and nothing ever wrote (`OFF_DBL_FAULT_PC` 0x94 /
    * `OFF_DBL_FAULT_VEC` 0x98). `dblFaultVec` is 0 until a double fault occurs; the
    * vector-table address that faulted is VBR + `dblFaultVec`*4. */
  def dblFaultPc:  UInt
  def dblFaultVec: UInt
  /** 2026-09-09: WHICH fatal condition halted the core, in the `socket.HaltReason`
    * encoding (0 none, 1 DCACHE_DIAG, 2 FS_XLATE, 3 RESET_VECTOR, 4 ARBITER_WEDGE,
    * 5 WALKER_PORT_WEDGE, 6 DOUBLE_FAULT). `haltReasonDebug` deliberately collapses every
    * one of those to the single `DebugHaltReasonCode.FATAL`, so before this the ONLY place
    * the attribution appeared was an ILA probe -- which needs an ENABLE_ILA bitstream. A
    * core that halts fatally has to be able to say why. Backs `OFF_HALT_KIND` (0x140). */
  def haltKind: UInt
  def request(stop: Bool, resume: Bool, step: Bool, clearSticky: Bool): Unit
  /** Surviving debug-domain halt-after configuration. `invalidate` is the accepted
    * target-write pulse; it cancels a stale pipelined comparison on that same edge. */
  def configureHaltAfter(target: UInt, epoch: UInt, armed: Bool, invalidate: Bool): Unit
  /** Surviving debug-domain halt-on-exception configuration. */
  def configureExceptionMask(mask: Bits): Unit
  /** A7-ODD halt lane (2026-09-09, the boot odd-SSP defect): when `enable`, request a
    * debug stop once the COMMITTED active-bank A7 has stayed odd for `threshold`
    * retired macros (the ROM legitimately runs short odd stretches). Read-side: the
    * PCs retiring at / just before the EVEN->ODD edge and the odd value itself. */
  def configureA7OddHalt(enable: Bool, threshold: UInt): Unit
  /** PC-RANGE halt lane: when `enable`, request a debug stop as soon as a macro
    * RETIRES with its PC inside [lo, hi]. Used to catch a wild jump into memory the
    * OS never allocated (executing DRAM filler) at the instruction it happens. */
  def configurePcRangeHalt(enable: Bool, lo: UInt, hi: UInt): Unit
  def pcRangePc0: UInt   // first in-range PC seen
  def pcRangePc1: UInt   // the PC retired before it
  def pcRangePc2: UInt   // and the one before that
  def pcRangeCount: UInt // in-range entries seen while enabled (16 bits)
  def a7OddPc0:      UInt   // PC retiring in the edge cycle (0 if none)
  def a7OddPc1:      UInt   // newest PC retired before the edge
  def a7OddPc2:      UInt   // the one before that
  def a7OddValue:    UInt   // the odd A7
  def a7OddEpisodes: UInt   // EVEN->ODD edges seen while enabled (16 bits)
}

/** One atomic commit-owner update used by the halted architectural-apply FSM.
  * D/A and split-CCR PRF writes travel through their shared regfile ports; this
  * bundle contains only the non-renamed state whose sole owner is the ROB (or the
  * ROB-routed MMU service), plus the saved restart PC. */
case class DebugSystemApply() extends Bundle {
  val srValid, pcValid, vbrValid = Bool()
  val uspValid, mspValid, ispValid = Bool()
  val cacrValid, sfcValid, dfcValid = Bool()
  val tcValid, itt0Valid, itt1Valid, dtt0Valid, dtt1Valid = Bool()
  val urpValid, srpValid = Bool()
  val sr = UInt(16 bits)
  val pc, vbr, usp, msp, isp, cacr = UInt(32 bits)
  val sfc, dfc = UInt(3 bits)
  val tc, itt0, itt1, dtt0, dtt1, urp, srp = UInt(32 bits)
}

/** ROB-owned coherent committed system-state debug seam.
  *
  * PRODUCER: `RobPlugin` (exactly one). `DebugCtrlPlugin` is the sole consumer.
  * The write command is accepted only as part of that plugin's halted apply FSM;
  * ordinary architectural writes continue to flow through ExceptionUnit. */
trait DebugSystemStateService {
  def sr: UInt
  def vbr: UInt
  def usp: UInt
  def msp: UInt
  def isp: UInt
  def cacr: UInt
  def sfc: UInt
  def dfc: UInt
  def tc: UInt
  def itt0: UInt
  def itt1: UInt
  def dtt0: UInt
  def dtt1: UInt
  def urp: UInt
  def srp: UInt
  def mmusr: UInt
  def requestApply(cmd: Flow[DebugSystemApply]): Unit
}

/** Halted-debug memory-coherence sequencer.
  *
  * The backend wiring layer is the sole producer because it already owns arbitration
  * of the ExceptionUnit and debug requests onto the D-cache maintenance port. Debug
  * control sees only this service: it never reaches into the LS queue, caches, TLBs,
  * or predictors. A completed request has pushed every dirty D-cache line, invalidated
  * both L1s and the fetch predictors, and left memory as the debugger-visible truth.
  * PRODUCER: `BackendWiringPlugin` (exactly one). */
case class DebugMemoryCommand() extends Bundle {
  val push, invalidate = Bool()
  val sel = UInt(2 bits) // CacheMaintCmd encoding: DC=1, IC=2, both=3.
}

trait DebugMemoryService {
  def quiesced: Bool
  def done: Bool
  def error: Bool
  def request(cmd: Flow[DebugMemoryCommand]): Unit
}

case class DebugBranchEvent() extends Bundle {
  val pc, nextPc = UInt(32 bits)
  val taken, mispredicted = Bool()
  val branchType = UInt(2 bits)
}

case class DebugExceptionEvent() extends Bundle {
  val vector = UInt(8 bits)
  val exceptionPc, faultAddress, handlerPc = UInt(32 bits)
}

/** Committed-only forensic-history events. None of these signals expose speculative
  * execute state: macro PCs and branches pulse at retirement; exceptions pulse only
  * after the entry sequencer has installed the handler PC and final architectural state.
  * PRODUCER: `RobPlugin` (exactly one). */
trait DebugHistoryService {
  def macroRetirePc: Vec[Flow[UInt]] // two program-ordered retire slots
  def branchRetire: Flow[DebugBranchEvent]
  def exceptionEntry: Flow[DebugExceptionEvent]
}

/** Produced by the I-cache; consumed by the fetch/align stage (later). */
trait FetchService {
  def cmd: Stream[FetchCmd]
  def rsp: Flow[FetchRsp]
}

/** Retire-time BTB update (fetch-time predictor, slice 1). The ROB drives this from
  * a retiring branch's per-entry BTB-update capture; the BtbPlugin consumes it to
  * write its table (alloc tag/target/brType + bump the 2-bit bimodal counter). A
  * single update port (1 retiring branch/cycle — branches are retireAlone). */
case class BtbUpdate() extends Bundle {
  val pc     = UInt(32 bits)   // the retiring branch's PC (index/tag source)
  val taken  = Bool()          // resolved taken (bimodal direction)
  val target = UInt(32 bits)   // resolved taken-target (learned)
  val brType = UInt(2 bits)    // 0=cond, 1=uncond
  val len    = UInt(4 bits)    // exact architectural instruction length in 16-bit words
}

/** ROB exposes; BtbPlugin consumes. `update.valid` pulses the cycle a BTB-eligible
  * branch retires. */
trait BtbUpdateService {
  def btbUpdate: Flow[BtbUpdate]
}

/** Exact association tag for a fetch-window predictor lookup. The sequence makes a
  * recycled three-entry fetch-ring slot distinguishable from its prior occupant. */
case class FetchPlanToken() extends Bundle {
  val ringSlot = UInt(2 bits)
  val seq      = UInt(8 bits)
}

/** `drop` is the leading-word drop attached to the very window this command names —
  * the same `cmdDrop` the issuing cycle writes into `ringDrop(ringTail)`. Carrying it
  * here lets the provider register the complete framing verdict (amendment §2.1.1) so
  * the application cycle never re-derives it from the fetch ring. */
case class FtbLookupCmd() extends Bundle {
  val windowPc = UInt(32 bits)
  val drop     = UInt(2 bits)
  val token    = FetchPlanToken()
}

/** `framedOk` is the registered conjunction `hit && brLen =/= 0 &&
  * brWordOff + brLen <= 4 && brWordOff >= cmd.drop`, computed at command time.
  * FetchAlign consumes it as one bit; the live equivalent is retained there only as a
  * simulation oracle and decline telemetry (amendment §2.1.1, §4). */
case class FtbLookupRsp() extends Bundle {
  val windowPc  = UInt(32 bits)
  val token     = FetchPlanToken()
  val hit       = Bool()
  val framedOk  = Bool()
  val brWordOff = UInt(2 bits)
  val brLen     = UInt(4 bits)
  val target    = UInt(32 bits)
  val brType    = UInt(2 bits)
}

/** FtbPlugin is the sole provider. FetchAlign drives the command and mismatch
  * clear Flows through this service and consumes the fixed cmd+1 response. */
trait FtbLookupService {
  def lookupCmd: Flow[FtbLookupCmd]
  def lookupRsp: Flow[FtbLookupRsp]
  def clearOne: Flow[UInt]
}

case class GshareWindowRsp(idxBits: Int) extends Bundle {
  val token  = FetchPlanToken()
  val taken  = Vec(Bool(), 4)
  val phtIdx = Vec(UInt(idxBits bits), 4)
}

/** GsharePlugin is the sole provider. The window result is fixed cmd+1 and uses
  * the same token as the FTB lookup launched for that fetch command. */
trait GshareWindowService {
  def windowCmd: Flow[FtbLookupCmd]
  def windowRsp: Flow[GshareWindowRsp]
}

/** GsharePlugin is the sole producer. Index for the existing secondary-PC
  * query, before this cycle's speculative history shift. No extra table port. */
trait GshareSecondaryLookupService {
  def secondaryPhtIndex: UInt
  def secondaryPhtTaken: Bool
}

/** Retire-time gshare PHT update (direction predictor, slice 3). The ROB drives this
  * from a retiring CONDITIONAL branch that carried a fetch-time `phtIndex`; the
  * GsharePlugin consumes it to train `pht[index]` toward the resolved direction
  * (saturate +/-1). Using the FETCH-TIME index (carried) — not a retire-time recompute
  * — is mandatory: the speculative GHR at retire differs from the GHR at the lookup, so
  * only the carried index trains the exact entry the lookup read. */
case class GshareUpdate(idxBits: Int) extends Bundle {
  val index = UInt(idxBits bits)   // the fetch-time folded XOR index the lookup read
  val taken = Bool()               // resolved actual direction (train toward this)
}

/** ROB exposes; GsharePlugin consumes. `update.valid` pulses the cycle a conditional
  * gshare-predicted branch retires. */
trait GshareUpdateService {
  def gshareUpdate: Flow[GshareUpdate]
}

/** Owned by the ROB (the committed/architectural S bit — `exc.ss.s`, the SAME signal
  * the privilege-violation check gates on). Consumed by the fetch/execute-stage
  * plugins that must present the CURRENT privilege level on a translation request's
  * function code (supervisor vs user), instead of hardcoding one — the I-cache (ITLB)
  * and the LS EU (DTLB, normal — not the exception sequencer's own always-supervisor
  * physical accesses) both read this. Combinational passthrough of a register (no
  * added latency); S only changes at a serializing exception-entry/RTE/system-op
  * boundary, so it is stable for any access issued between those boundaries. */
trait PrivilegeService {
  def supervisor: Bool
  /** SFC / DFC -- the 3-bit source / destination FUNCTION CODE registers (MOVEC
    * control registers 0x000 / 0x001, stored in `SystemState`). MOVES is the ONE
    * instruction whose data access does not run in the current privilege level's
    * address space: it drives FC from SFC (read form) or DFC (write form), and on
    * a 68040 FC[2] IS the supervisor/user address-space selector -- it picks URP vs
    * SRP for the table search and it is the bit the ATC's supervisor-only page
    * protection is checked against. Everything else (including the SR-changing
    * system ops) uses `supervisor` above.
    *
    * Same ROB-owned, `setup`-allocated-wire shape as `supervisor` for the identical
    * Fiber-cycle reason. Reading these LIVE in an execute-stage plugin is sound
    * because the only writer is MOVEC, which is a SERIALIZING sysOp: it retires
    * alone at the ROB head (so every older MOVES has already executed) and squashes
    * everything younger (so every younger MOVES re-executes after the update). */
  def sourceFc: UInt
  def destFc: UInt
}

/** Owned by the ROB (mirrors `ss.cacr(31)` — the SAME committed register the
  * `cacr_bit31_roundtrip.s` ported test round-trips via MOVEC). Consumed by the
  * LS EU's fast/precise store classification (P2) and the D-cache's
  * fully-uncached CACR.DE=0 semantics (P5). Uses the SAME setup-allocated-wire
  * pattern as PrivilegeService above and for the identical reason: the
  * DcachePlugin <- RobPlugin dependency direction would otherwise deadlock the
  * Fiber chain (RobPlugin.scala's PrivilegeService comment explains the general
  * shape of this hazard). */
trait CacheControlService {
  def dcacheEnabled: Bool
  /** CACR bit 15 (IE), the INSTRUCTION-cache enable -- the I-side twin of bit 31.
    * Consumed by `IcachePlugin`, which folds it into the fetch's cacheability
    * verdict exactly where the LS EU folds `dcacheEnabled` into the D-side access's
    * cache mode. Until 2026-09-09 this bit had NO reader anywhere in the core: the
    * instruction cache was unconditionally enabled and could not be turned off, so
    * software that clears IE and relies on that instead of an explicit CINV executed
    * stale instruction bytes -- silent wrong CODE, and a difference from the v1 core
    * that boots the same machine. */
  def icacheEnabled: Bool
}

/** The ONE 68040 MMU control (TC enable + separate URP/SRP root pointers + the four
  * transparent-translation registers ITT0/ITT1/DTT0/DTT1), shared by BOTH the I-side
  * ITLB and the D-side DTLB. One owner (MmuControlPlugin) drives the regs; both TLBs
  * read `mmuEnable`/`urp`/`srp` and select URP vs SRP themselves per-access (the
  * walker request already carries `isSuper`, mirroring real 68040 hardware: a
  * supervisor-space access walks SRP, a user-space access walks URP). `mmuEnable`
  * LOW => identity. Sim-pokeable AND (task #194, reviving task #131's reverted
  * attempt) commit-time MOVEC-writable via the `setX` Flow ports — see
  * MmuControlPlugin's doc comment for why the original write mechanism was reverted
  * and what changed to make it safe to re-add. */
/** Debug-injected interrupt request (`OFF_IRQ_INJECT`, DebugRegMap 0x020).
  *
  * Exposed as a SERVICE rather than a plain accessor on purpose: `host[...]` BLOCKS the
  * consumer's build body until the provider's body has run, which is the documented cure
  * for the cross-Fiber ordering race (see MmuControl.scala's own note). Reading a bare
  * `var` accessor from SocketTop instead gave a null at elaboration.
  *
  *  - `irqInjectLevel` : requested IPL, HELD until an interrupt entry is actually taken.
  *                       0 = no request. 7 = NMI.
  *  - `irqInjectAck`   : drive from the core's `ipl_ack` to clear the held request.
  */
trait DebugIrqInjectService {
  def irqInjectLevel: UInt
  def irqInjectAck: Bool
}

trait MmuControlService {
  def mmuEnable: Bool
  def urp: UInt   // 32 bits — user root pointer
  def srp: UInt   // 32 bits — supervisor root pointer
  // Transparent-translation registers (task #194): base[31:24]/mask[23:16]/E[15]/
  // S[14:13]/CM[6:5], mirroring the page-descriptor CM field layout (MmuDesc). A
  // matching TTR bypasses the walker entirely (PA=VA) for the covered region.
  def itt0: UInt
  def itt1: UInt
  def dtt0: UInt
  def dtt1: UInt
  // TCR.P (task #195): real MC68040 page-size bit (TCR bit 14, MC68040 UM Fig 3-4).
  // False = 4KB pages (reset/legacy default — every pre-#195 test is unaffected),
  // True = 8KB pages. Consulted by the table walker (pointer->page offset width),
  // the DTLB/ITLB (VA[12] excluded from the TLB tag/index compare in 8K mode), and
  // the two out-of-mmu/ PA-reconstruction sites (LsEuPlugin, IcachePlugin).
  def pageSize8K: Bool
  // MMUSR (task #198): PTEST's result register — MOVEC Rc id 0x805, read-only from the
  // arch side (no MOVEC write case; real hardware has none either). Written at PTEST's
  // S_APPLY (ExceptionUnit) via `setMmusr`.
  def mmusr: UInt

  // ── commit-time write ports (driven from ExceptionUnit's MOVEC S_APPLY case) ──
  def setEnable: Flow[Bool]
  def setPageSize: Flow[Bool]
  def setUrp: Flow[UInt]
  def setSrp: Flow[UInt]
  def setItt0: Flow[UInt]
  def setItt1: Flow[UInt]
  def setDtt0: Flow[UInt]
  def setDtt1: Flow[UInt]
  // Written from PTEST's S_APPLY case (ExceptionUnit), not the MOVEC write switch —
  // MMUSR has no MOVEC write case (RAZ/WI in real hardware too).
  def setMmusr: Flow[UInt]
}

/** The single owner of the NON-RENAMED floating-point control state (spec Decision 5):
  * FPCR (rounding/precision/exception-enable), FPSR's NON-FPCC bytes (exception status,
  * accrued exception, quotient), and FPIAR. Contrast with FPCC (N/Z/I/NAN), which IS
  * renamed and lives in the FPCC RAT/PRF (Task 2/3) — this service deliberately does not
  * hold it, and `fpsr` below reads 0 in bits [27:24]; ExceptionUnit splices the live
  * committed FPCC in on an architectural FPSR read and routes it back to the FPCC PRF on
  * an architectural FPSR write.
  *
  * Shape follows MmuControlService exactly: plain accessors for the committed values +
  * one default-idle commit-time write Flow each. */
trait FpuControlService {
  def fpcr:  UInt          // 32 bits
  def fpsr:  UInt          // 32 bits, bits [27:24] always 0 (FPCC is renamed elsewhere)
  def fpiar: UInt          // 32 bits

  def setFpcr:  Flow[UInt]
  def setFpsr:  Flow[UInt]   // bits [27:24] of the payload are IGNORED (masked at the writer)
  def setFpiar: Flow[UInt]

  /** The FP EU's exception-status OR-in port. Payload is the 8-bit EXC field
    * {BSUN,SNAN,OPERR,OVFL,UNFL,DZ,INEX2,INEX1} (MSB = BSUN), matching the MC68040 UM's
    * Figure 9-5 layout of FPSR[15:8]. ORs into FPSR[15:8] and folds the derived AEXC
    * bits into FPSR[7:0] per the UM's Section 9.2.3.4 equations.
    *
    * PRODUCER (Task 14c): `DivEuPlugin.fpExcAccrualPort`, wired by
    * `DivEuPlugin.wireFpControl`. It fires from the FP lane's COMPLETION register, i.e.
    * at execute time, not at retirement — so an FP op that completes SPECULATIVELY, before
    * whatever would have squashed it resolves, still accrues, and the sticky AEXC bits it
    * sets are never undone. The suppression window is exactly one cycle wide
    * (`fpCompLive = fpCompValid && !flushSig`), and the condition is the GENERAL one, not a
    * branch-specific one: `flushSig` is `RobPlugin.doFlushReg`, which is
    * `branchRedirect || exc.redirectValid` (`RobPlugin.scala`), so ANY squash of
    * younger-than-the-squash-point work suppresses identically — an older instruction's
    * page fault, bus error, trap, or a delivered interrupt, not just a mispredicted branch.
    * Conversely, an FP op that completes BEFORE any of those events accrues regardless.
    * That is a known, reported limitation, not an oversight; the
    * architecturally correct fix is a retire-time fold (carry the EXC byte per-robId in
    * the ROB exactly as `faultVecStore` carries the fault vector, and OR it in from the
    * commit-port hook that already drives `setEverExecuted`), which is a real ROB
    * retire-path change and belongs in its own gated slice. */
  def orFpsrExc: Flow[Bits]

  /** FPCR field accessors, for the FP EU. Rounding mode is FPCR[5:4]
    * (00=RN, 01=RZ, 10=RM, 11=RP); precision FPCR[7:6]; exception-enable byte FPCR[15:8],
    * laid out identically to the EXC field above.
    *
    * `roundingMode` and `excEnable` are consumed by `DivEuPlugin.wireFpControl` (Task 14c).
    *
    * `precision` is consumed by `DivEuPlugin.wireFpControl` -> `DivEuPlugin.fpPrecIn`,
    * sampled at ISSUE into `fpS1Prec` (exactly as `roundingMode` is sampled into
    * `fpS1Rmode`, so an FPCR write landing mid-flight cannot re-mux an already-issued
    * result), combined there with the instruction's own opmode by
    * `FpSource.opmodeToPrecision` -- the MC68040's FS<op>/FD<op> encodings OVERRIDE
    * FPCR.PREC (UM 10.7) -- and delivered to `FpuCore.io.precision`, which threads it into
    * every front-end's `FpRoundReq.prec` and on into `FpRoundPack`.
    *
    * It drives BOTH of the things the manual says it drives, not just the first:
    *   - 9.2.2.2 (p.9-3) / 9.4.1 (p.9-12): the MANTISSA ROUNDING BOUNDARY -- 24 / 53 / 64
    *     bits, "All mantissa bits beyond the selected precision are zero".
    *   - 9.4.2 (p.9-13) / 9.7.4 / 9.7.5: RANGE CONTROL -- OVFL/UNFL detected against the
    *     SELECTED precision's exponent range, with the overflow substitution being that
    *     precision's largest finite (UM Table 9-12) and the underflow arm denormalising to
    *     that precision's own minimum exponent (UM Table 9-13).
    * See `FpPrec` and `FpRoundPack`'s headers for the derivation and the limits table.
    *
    * ⚠ NOT LOCK-STEPPABLE, IN EITHER DIRECTION. Musashi ignores PREC (`fmove_fpcr` sets
    * only `float_rounding_mode`; `floatx80_rounding_precision` is never assigned in the
    * vendored tree) and the vendored SoftFloat's own reduced-precision arms implement x87
    * mantissa-only semantics with no range control. The directed vectors in
    * `FpuCoreSpec`'s "PREC:" tests are the only oracle; a green lock-step says nothing
    * about this field.
    *
    * KNOWN REMAINING GAPS, deliberate and recorded rather than overlooked:
    *   - FMOVECR, FINT and FINTRZ are still rounded at extended precision regardless of
    *     PREC. All three are FPSP-emulated on real MC68040 silicon (this core runs them in
    *     hardware as a documented superset), and FMOVECR additionally needs a per-request
    *     range-control suppression bit to honour the PRM's "OVFL Cleared / UNFL Cleared"
    *     rule while still rounding its mantissa. See `FpCheapPipe`.
    *   - UNFL follows this core's pre-existing SoftFloat "tiny AND inexact" rule at every
    *     precision, whereas UM 9.7.5 says the FPSR EXC byte's UNFL is set "any time a tiny
    *     number is generated". That is an EXTENDED-precision behaviour inherited by the
    *     new precisions, not something this slice introduced; changing it would move
    *     lock-stepped extended-precision behaviour and belongs in its own slice.
    *   - FMOVE to a MEMORY destination correctly ignores PREC already: that path is
    *     `FpNarrowPack`, which rounds to the DESTINATION FORMAT (`io.fmt`) and never reads
    *     PREC at all -- "If the destination is a memory location, the FPSR PREC bits are
    *     ignored" (UM 9.4.1). */
  def roundingMode: Bits
  def precision:    Bits
  def excEnable:    Bits

  // ── Task 11: FSAVE state-frame selection state ────────────────────────────────
  /** Sticky "at least one NONCONDITIONAL floating-point instruction has executed since
    * the last hardware reset or FRESTORE of a null state frame". Selects the NULL frame
    * (False) vs the IDLE frame (True) at FSAVE time.
    *
    * MC68040 UM (1989 1st ed.) section 9.7, page 9-30: "A null state frame is saved if
    * no floating-point instructions have been executed since the last hardware reset or
    * FRESTORE of a null state frame ... An idle state frame is saved if no exceptions
    * are pending, and at least one instruction has been executed since the last hardware
    * reset or FRESTORE of a null state frame."  The rev-1 manual (section 9.7) adds the
    * explicit conditional-instruction carve-out: "Floating-point conditional instructions
    * do not set an internal flag, which changes the state frame from null to idle" —
    * FNOP/FBcc/FDBcc/FScc/FTRAPcc must NOT set it.  This project drives it from an FP
    * REGISTER WRITE at commit (`CommitSlot.fpWrite`), which is a conservative subset of
    * "nonconditional": every conditional in that list writes no FP register, so none of
    * them can set it. */
  def everExecuted:    Bool
  def setEverExecuted: Flow[Bool]

  /** Latched unimplemented-instruction state, captured when a RECOGNIZED-but-unsupported
    * FP op is delivered to vector 11 (Task 10's `fpuSoftwareComplete` path). A subsequent
    * FSAVE emits the 52-byte unimplemented-instruction state frame built from it instead
    * of the 4-byte idle frame; emitting the frame CONSUMES the state (`uiValid` clears),
    * which is the whole "route to FPSP" hand-off: the trap tells the handler THAT
    * something unsupported happened, the frame tells it WHAT.
    *
    * `uiSrcOperand`/`uiDstOperand` are 80-bit extended-precision values laid out
    * {sign[79], exponent[78:64], mantissa[63:0]} — the shape the frame's ETS/ETE/ETM
    * (source) and FPTS/FPTE/FPTM (destination) fields want. */
  def uiValid:       Bool
  def uiCmdReg1B:    Bits   // 16 — CMDREG1B, the faulting instruction's command word
  def uiSrcOperand:  Bits   // 80 — ETEMP (source operand, extended precision)
  def uiDstOperand:  Bits   // 80 — FPTEMP (destination operand, extended precision)
  /** Packed {cmd[175:160], src[159:80], dst[79:0]} = 176 bits. */
  def setUnimpFrame: Flow[Bits]
  def clearUnimp:    Flow[Bool]
}

/** The external interrupt inputs (simple protocol): a 3-bit IPL plus the SoC's
  * per-level autovector/vectored selection. One owner drives the regs (synth top
  * input / sim poke / future SoC); the ROB recognition logic reads them.
  *
  *  - `iplIn`      : interrupt priority level (0 = none, 1..7 = level, 7 = NMI).
  *  - `iackAvec`   : True => autovector (vector = 24 + level) for the active level.
  *  - `iackVector` : the vectored vector (8b) used when `!iackAvec`.
  *
  * The vector is computed COMBINATIONALLY at interrupt entry:
  *   curVec = iackAvec ? (24 + iplIn) : iackVector
  * (no faithful IACK bus handshake — postponed). */
trait InterruptControlService {
  def iplIn:      UInt   // 3 bits
  def iackAvec:   Bool
  def iackVector: UInt   // 8 bits
}

/** Produced by the MMU/ITLB (identity stub this slice); consumed by the I-cache.
  * Combinational: drive `rsp` from `req` within the same cycle. */
trait TranslationService {
  def req: TranslationReq
  def rsp: TranslationRsp
}

/** Tagged, elastic D-side translation service. A SEPARATE service from the I-side
  * TranslationService so instruction and data translation each have exactly one
  * request producer and the LSU can associate registered results across VPNs. */
trait DTranslationService {
  def req: Stream[DTranslationCmd]
  def rsp: Stream[DTranslationRsp]
}

/** A table walker's D-cache client port pair, exposed by the owning TLB plugin and
  * arbitrated onto `DcacheService`'s single load/store port pair by `LsEuPlugin`.
  *
  * WHY THIS IS A SERVICE RATHER THAN A TOP-LEVEL WIRE BUNDLE. The pre-existing
  * `umCommitValid`/`umFlush`/`excLoadCmdValid` idiom is a plugin-level `var` wired by
  * every DUT's own top level. Doing that here would mean editing ~20 DUTs to re-wire
  * eight signals apiece for a change that is internal to the core. Resolving the ports
  * through `PluginHost` instead means any DUT that already contains an `LsEuPlugin`,
  * a `DcachePlugin` and the TLB plugins gets the arbitration for free, with no wiring
  * edit at all. DUTs that host a TLB plugin but no D-cache attach a sim-side
  * `DcacheClientMemAgent` to these same ports (they are `simPublic`).
  *
  * DIRECTION. The TLB plugin drives `loadCmd.valid`/`loadCmd.payload` and
  * `store.valid`/`store.payload`; the arbiter drives `loadCmd.ready`, `store.ready`,
  * `loadRsp`, `storeAck` and `storeErr`. Every arbiter-driven signal is declared with
  * `allowOverride` and a default-idle drive inside the TLB plugin, so a DUT with no
  * arbiter still elaborates (the walker then simply never makes progress unless a sim
  * agent drives the ports).
  *
  * `cacheMode` and `token` on `loadCmd`/`store` are STAMPED BY THE ARBITER, not by the
  * walker: the descriptor fetch's own cache mode is a fixed architectural policy
  * (`CACR.DE ? WRITETHROUGH : INHIBITED`) that cannot be derived from a descriptor
  * without circularity, and the walker has no access to CACR. The values the TLB
  * plugin drives are inert defaults. */
trait WalkerDcacheClient {
  def walkLoadCmd:  Stream[m68k040.cache.DLoadCmd]
  def walkLoadRsp:  Flow[m68k040.cache.DLoadRsp]
  def walkStore:    Stream[m68k040.cache.DStoreCmd]
  def walkStoreAck: Bool
  def walkStoreErr: Bool

  /** The arbiter's fixed descriptor-access cache-mode POLICY (`CACR.DE ? WRITETHROUGH
    * : INHIBITED`), driven by `LsEuPlugin` and read by this plugin.
    *
    * WHY THE POLICY AND NOT THE STAMP (race audit, 2026-09-18). The arbiter used to
    * STAMP `cacheMode` onto both walker legs, live, at each leg
    * (`dcache.loadCmd.payload.cacheMode` and `dcache.store.payload.cacheMode`). Both
    * legs read the same net -- which is SPATIAL agreement, and the invariant needs
    * TEMPORAL agreement: one table search spans the three descriptor reads, the
    * deferred U/M drain's re-read and that drain's merged store, hundreds of cycles,
    * with a MOVEC to CACR free to retire anywhere inside it.
    *
    * `quiesceHold` made the split MORE likely rather than less: a CACR write is a
    * sysOp, and its `S_DRAIN`/`S_APPLY` deny the walker a fresh STORE grant, so a
    * drain whose re-read had already completed under the old `CACR.DE` was parked
    * until after the write landed and then stamped with the NEW mode. DE 1->0 is the
    * damaging direction: the re-read ran WRITETHROUGH and ALLOCATED the descriptor
    * line, then the store ran INHIBITED and "never touches the cache array", so memory
    * got the U/M update and the resident copy kept the pre-update byte. Re-enable DE
    * without a CINV and that stale copy answers the next descriptor read -- M lost, and
    * a dirty page later evicted as clean.
    *
    * Exporting the policy instead lets the client LATCH it once for the read/write pair
    * that must agree, which is the only place in the search that MUTATES. */
  def walkCmodePolicy: m68k040.cache.CacheMode.C
}

/** The I-side walker's client port (`ItlbPlugin`). A SEPARATE trait from the D-side
  * one purely so `LsEuPlugin` can resolve the two walkers deterministically —
  * `host.list[WalkerDcacheClient]` would give no stable ITLB/DTLB ordering, and the
  * arbiter's round-robin, its response tag and its `WalkerIdx` vectors all depend on
  * a fixed positional identity. */
trait ItlbWalkerDcacheClient extends WalkerDcacheClient

/** The D-side walker's client port (`DtlbPlugin`). See `ItlbWalkerDcacheClient`. */
trait DtlbWalkerDcacheClient extends WalkerDcacheClient

/** Produced by the fetch/align stage; consumed by the (future) decode stage.
  * Two packets/cycle; slot 0 valid when the stream fires, slot 1 on 2-wide cycles. */
trait DecodeFeedService {
  def feed: Stream[Vec[DecodePacket]]   // Vec length 2
  def slot1Valid: Bool
}

/** The fetch-time branch-prediction record that `DecodedUop.brPredTag` names: what used
  * to be four separate fields on every decoded µop. Only DecodeStage (which owns the side
  * table) produces one; only RenameStage consumes one, copying it onto `RenamedUop`. */
case class BranchPredRec() extends Bundle {
  val predTaken  = Bool()
  val predTarget = UInt(32 bits)
  val phtValid   = Bool()
  val phtIndex   = UInt(11 bits)

  /** No prediction. The value a `brPredTag` of 0 expands to. */
  def setInert(): Unit = {
    predTaken := False; predTarget := U(0, 32 bits)
    phtValid  := False; phtIndex   := U(0, 11 bits)
  }
}

/** Produced by the decode stage; consumed by the (future) rename stage.
  * Two µops/cycle. Plain Stream (directionless) per the service convention.
  * `pipeFlush` is the directionless squash input driven by backend wiring; exposing it
  * here avoids sibling plugins reaching into DecodeStage's implementation Area. */
trait DecodeUopService {
  def uops: Stream[Vec[DecodedUop]]   // Vec length 2
  def uop1Valid: Bool
  /** The fetch-time branch-prediction record for each of the two popped uops, re-expanded
    * from the uop's `brPredTag` (see DecodedUop.brPredTag / Global.BR_PRED_TABLE_DEPTH).
    * Parallel to `uops.payload`: index k describes uops.payload(k) in the same cycle, and
    * is valid under exactly the same conditions. Rename copies it straight onto
    * `RenamedUop`, which still carries the four fields, so nothing downstream of rename
    * changed when the 45 bits came off the DECODE record. */
  def uopPred: Vec[BranchPredRec]     // Vec length 2
  def pipeFlush: Bool
  /** The BACKEND squash pulse (RobPlugin `doFlush || excActive`, i.e. exactly what
    * clears the issue queue and the rename->dispatch skid). Distinct from `pipeFlush`
    * (the FRONTEND squash, which additionally carries Tier-1 `earlyFire` and is withheld
    * on a Tier-2 `feSuppress` flush). DecodeStage needs BOTH because it owns the FP
    * wide-immediate side table, whose entries are held by uops on either side of the
    * decode->rename boundary. Default-driven False (allowOverride); backend wiring
    * drives it alongside `IssueQueueService.flushPort`. */
  def backendFlush: Bool
  /** DecodeStage is the sole real-core producer. Rare complex packets pulse their exact
    * architectural fall-through target here; frontend wiring adds the consumer-local
    * register required by the 250 MHz FMax contract. */
  def complexResume: Flow[UInt]
}

/** The FP wide-immediate side table (docs/PLAN_routing_congestion_architectural.md
  * item 3). Producer: DecodeStage (allocates an entry when an `F<op>.<fmt> #imm,FPn`
  * uop enters the MicroOpQueue and stamps the entry tag into that uop's
  * `imm[Global.FP_IMM_TAG_W-1:0]`).
  * Consumer: DivEuPlugin (the ONLY reader of the immediate) -- it presents the tag on
  * `fpImmRdAddr` on the accepting cycle, captures `fpImmRdData` (asynchronous LUTRAM
  * read) into its own `fpS1Imm` register, and pulses `fpImmFree` with the same tag to
  * release the entry. All three are directionless wires created in the producer's setup
  * phase (so no Fiber build-order edge exists between the two plugins); the producer
  * default-drives the consumer-owned ones (allowOverride) so a decode-only harness
  * still elaborates, and a DivEu-only harness supplies a stub implementation.
  * Reclamation on squash is the producer's job and is NOT "clear everything on flush":
  * with the two-tier reschedule (RobPlugin `earlyFire`/`feSuppress`) the frontend and
  * the backend are squashed by DIFFERENT pulses, so an entry is freed by the pulse that
  * squashes the domain its uop currently sits in (see DecodeStage `fpImmTable`). */
trait FpImmTableService {
  def fpImmRdAddr: UInt      // FP_IMM_TAG_W bits, consumer-driven
  def fpImmRdData: Bits      // 80 bits, async read of the entry at fpImmRdAddr
  def fpImmFree: Flow[UInt]  // consumer-driven: release this tag (fires with the capture)
}

/** Read-only exception view of FP immediates. Sole producer: DecodeStage.
  * RobPlugin reads the head's retained tag at exception entry. The backend
  * squash AFTER capture releases it; ordinary EU consumption remains separate. */
trait FpTrapImmediateService {
  def trapImmAddr: UInt
  def trapImmData: Bits
  def trapImmValid: Bool
}

/** Produced by rename; consumed by the (future) dispatch/ROB. Plain Stream. */
trait RenameUopService {
  def uops: Stream[Vec[RenamedUop]]   // Vec length 2
  def uop1Valid: Bool
}

/** ROB exposes a passive allocation interface; DispatchPlugin drives it.
  * robId0/robId1 are the ring indices the next 0th/1st µop will occupy. */
trait RobAllocService {
  def allocReady: Bool                 // room for 2 (ROB drives)
  def robId0: UInt                     // = tail
  def robId1: UInt                     // = tail+1
  def allocFire: Bool                  // dispatch drives: commit the allocation this cycle
  def allocUop: Vec[RenamedUop]        // dispatch drives: the 2 µops (length 2)
  def allocSlot1: Bool                 // dispatch drives: 2nd µop valid
}
