package m68k040.execute.iq

import m68k040.rename.RenamedUop
import spinal.core._
import spinal.lib._

case class IqContext() extends Bundle {
  val uop   = RenamedUop()
  val robId = UInt(6 bits)
}

/** Dynamic-completion wakeup for the SLOW ALU path (shift, latency-2). The shift
  * produces an int dst AND NZVC AND X atomically at S2, so the broadcast carries all
  * three physreg dsts (with per-class valid). The IQ clears its per-class slow-busy
  * bitmaps + any dependent's `aluSlowWait` matching ANY of the three. (Unlike LS/DIV,
  * which write only an int reg, a shift also writes the flag PRFs — hence the bundle.) */
case class AluSlowWakeup() extends Bundle {
  val pdst      = UInt(6 bits); val pdstValid = Bool()
  val pNzvcDst  = UInt(4 bits); val nzvcValid = Bool()
  val pXDst     = UInt(4 bits); val xValid     = Bool()
}

trait IssueQueueService {
  def push: Stream[Vec[IqContext]]   // length 2
  def pushSlot1Valid: Bool
  def issue: Vec[Stream[IqContext]]  // length 5 (ALU0, ALU1, branch, LS, CPLX/DivEu)
  def flushPort: Bool
  /** Dynamic-completion wakeup (variant A): the LS EU broadcasts the pdst of a
    * just-completed load; slots reading that physreg become ready. */
  def lsWakeup: Flow[UInt]
  /** Dynamic-completion NZVC wakeup: the LS EU broadcasts the pNzvcDst of a
    * just-completed NZVC-writing store (a MOVE-to-memory store) or RTR CCR-restore;
    * a flag-reader of that NZVC becomes ready. Separate from lsWakeup (int pdst): an LS
    * op can write BOTH an int reg AND NZVC, and a reader may depend on one or the other
    * or both. The static IQ scoreboard cannot clear an LS-produced NZVC (an LS op issues
    * on the LS port, generating no static latency-1 ALU/branch wakeup event). */
  def lsNzvcWakeup: Flow[UInt]
  /** Dynamic-completion wakeup for the CPLX cluster (DivEu): a multi-cycle DIV
    * broadcasts the pdst of its just-completed quotient/remainder; slots reading
    * that physreg become ready. Separate Flow from lsWakeup so a same-cycle LS load
    * + DIV completion never collide on one wakeup port. */
  def cplxWakeup: Flow[UInt]
  /** Dynamic-completion NZVC wakeup for the CPLX cluster (DivEu): broadcasts the
    * pNzvcDst of a just-completed CPLX op that writes flags (DIV/MUL normal + overflow,
    * CHK, CMP2/CHK2 — everything on the CPLX/DivEu port except the trailing DIVREM/MULHI
    * crack µops, which write no flags). Task #167 (ported-tests triage): before this
    * port existed, a CPLX NZVC producer was tracked ONLY in the static (latency-1)
    * `sbNzvc` scoreboard, whose busy bit is cleared the cycle the op ISSUES to DivEu
    * (task #141's `!slowFire` clear loop), not when its multi-cycle FSM actually
    * completes — correct only for CMP2/CHK2 (near-single-cycle) and accidentally
    * unnoticed for DIV/MUL (whose flags land many cycles later) because every prior
    * test happened to need a POST-op flag value equal to the STALE pre-op one. A
    * `bvc`/`bvs` immediately after a genuine DIV/MUL overflow (V=1) exposed it: the
    * branch read the stale (pre-multiply) flags instead of waiting for the real
    * writeback. Mirrors `lsNzvcWakeup` exactly, on the CPLX port instead of LS. */
  def cplxNzvcWakeup: Flow[UInt]
  /** Dynamic-completion wakeup for the SLOW ALU path (shift, latency-2): each ALU EU
    * broadcasts the int+NZVC+X dsts of its just-completed shift. A dependent of a shift
    * (int OR flag source) is held NOT-ready until a matching broadcast fires. The shift
    * is tracked in SEPARATE slow-busy bitmaps (NOT the static latency-1 scoreboards), so
    * a dependent wakes at latency-2, not latency-1. ONE port per ALU EU (both can
    * complete a distinct shift the same cycle). */
  def aluSlowWakeup: Vec[Flow[AluSlowWakeup]]
}
