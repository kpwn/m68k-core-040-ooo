package m68k040.services

import m68k040.types.CommitTrace
import m68k040.cache.{FetchCmd, FetchRsp, TranslationReq, TranslationRsp}
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
}

/** ROB exposes; BtbPlugin consumes. `update.valid` pulses the cycle a BTB-eligible
  * branch retires. */
trait BtbUpdateService {
  def btbUpdate: Flow[BtbUpdate]
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

/** The ONE 68040 MMU control (TC enable + URP/SRP root pointer), shared by BOTH
  * the I-side ITLB and the D-side DTLB. One owner drives the regs (synth top input /
  * sim poke / future MOVEC); both TLBs read it. `mmuEnable` LOW => identity. */
trait MmuControlService {
  def mmuEnable: Bool
  def rootPtr:   UInt   // 32 bits
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

/** D-side translation (DTLB; identity stub this slice). A SEPARATE service from
  * the I-side TranslationService so the I-cache and D-cache each own a distinct
  * request port (one TranslationService can have only one driver). */
trait DTranslationService {
  def req: TranslationReq
  def rsp: TranslationRsp
}

/** Produced by the fetch/align stage; consumed by the (future) decode stage.
  * Two packets/cycle; slot 0 valid when the stream fires, slot 1 on 2-wide cycles. */
trait DecodeFeedService {
  def feed: Stream[Vec[DecodePacket]]   // Vec length 2
  def slot1Valid: Bool
}

/** Produced by the decode stage; consumed by the (future) rename stage.
  * Two µops/cycle. Plain Stream (directionless) per the service convention. */
trait DecodeUopService {
  def uops: Stream[Vec[DecodedUop]]   // Vec length 2
  def uop1Valid: Bool
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
