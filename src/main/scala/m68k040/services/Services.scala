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
  // MMUSR (task #198): PTEST's result register — MOVEC Rc id 0x805, read-only from the
  // arch side (no MOVEC write case; real hardware has none either). Written at PTEST's
  // S_APPLY (ExceptionUnit) via `setMmusr`.
  def mmusr: UInt

  // ── commit-time write ports (driven from ExceptionUnit's MOVEC S_APPLY case) ──
  def setEnable: Flow[Bool]
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
    * NOTE (Task 9): no producer is wired yet — Task 8's `DivEuPlugin` FP lane predates
    * this service and does not yet drive it. The port exists (and is unit-tested) so
    * that wiring lands as a pure addition; until then FPSR's EXC/AEXC bytes only ever
    * change via an architectural FMOVE-to-FPSR. */
  def orFpsrExc: Flow[Bits]

  /** FPCR field accessors, for the FP EU. Rounding mode is FPCR[5:4]
    * (00=RN, 01=RZ, 10=RM, 11=RP); precision FPCR[7:6]; exception-enable byte FPCR[15:8],
    * laid out identically to the EXC field above. */
  def roundingMode: Bits
  def precision:    Bits
  def excEnable:    Bits
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

/** Produced by the fetch/align stage; consumed by the (future) decode stage.
  * Two packets/cycle; slot 0 valid when the stream fires, slot 1 on 2-wide cycles. */
trait DecodeFeedService {
  def feed: Stream[Vec[DecodePacket]]   // Vec length 2
  def slot1Valid: Bool
}

/** Produced by the decode stage; consumed by the (future) rename stage.
  * Two µops/cycle. Plain Stream (directionless) per the service convention.
  * `pipeFlush` is the directionless squash input driven by backend wiring; exposing it
  * here avoids sibling plugins reaching into DecodeStage's implementation Area. */
trait DecodeUopService {
  def uops: Stream[Vec[DecodedUop]]   // Vec length 2
  def uop1Valid: Bool
  def pipeFlush: Bool
  /** DecodeStage is the sole real-core producer. Rare complex packets pulse their exact
    * architectural fall-through target here; frontend wiring adds the consumer-local
    * register required by the 250 MHz FMax contract. */
  def complexResume: Flow[UInt]
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
