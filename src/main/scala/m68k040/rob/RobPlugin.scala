package m68k040.rob

import m68k040.services.{RenameUopService, RenameCommitService, CommitTraceService}
import m68k040.rename.RenamedUop
import m68k040.types.CommitTrace
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** RobPlugin: instruction-level reorder buffer ring with 2-wide in-order retire.
  *
  * - Dispatch: consumes RenameUopService.uops (2-wide), allocates ring slots.
  * - Completion: external markComplete.Flow(robId) marks an entry done.
  * - Retire: in-order, up to 2/cycle, drives RenameCommitService.commitPorts
  *   (commit committed-RAT + free old pdsts) and exposes CommitTrace.
  * - Flush: squash all in-flight entries (tail := head, count := 0).
  *
  * retireAlone entries (branches, for now) retire 1-wide.
  */
class RobPlugin extends FiberPlugin with CommitTraceService {

  /** One ROB entry's commit/free + trace payload. */
  case class RobPayload() extends Bundle {
    val predNextPc = UInt(32 bits)
    val archRegId  = UInt(4 bits)
    val intNew     = UInt(6 bits); val intOld = UInt(6 bits); val intWrite = Bool()
    val nzvcNew    = UInt(4 bits); val nzvcOld = UInt(4 bits); val nzvcWrite = Bool()
    val xNew       = UInt(4 bits); val xOld = UInt(4 bits); val xWrite = Bool()
    val retireAlone = Bool()
  }

  val logic = during build new Area {
    val ru = host[RenameUopService]
    val rc = host[RenameCommitService]

    val depth  = 64
    val robIdW = log2Up(depth) // = 6, wraps naturally

    // ── Ring storage ────────────────────────────────────────────────────────
    val payload   = Mem(RobPayload(), depth)
    val valids    = Vec.fill(depth)(RegInit(False))
    val completes = Vec.fill(depth)(RegInit(False))
    val head  = Reg(UInt(robIdW bits)) init 0
    val tail  = Reg(UInt(robIdW bits)) init 0
    val count = Reg(UInt(log2Up(depth + 1) bits)) init 0
    head.simPublic(); tail.simPublic(); count.simPublic()

    // ── External ports ────────────────────────────────────────────────────────
    val markComplete = slave(Flow(UInt(robIdW bits)))
    val flush        = slave(Flow(NoData()))

    // ── Build a RobPayload from a RenamedUop ───────────────────────────────────
    def payloadFrom(u: RenamedUop): RobPayload = {
      val p = RobPayload()
      p.predNextPc := u.pc + 2
      p.archRegId  := u.dstArch
      p.intNew     := u.pdst;     p.intOld := u.pdstOld;   p.intWrite  := u.pdstValid
      p.nzvcNew    := u.pNzvcDst; p.nzvcOld := u.pNzvcOld;  p.nzvcWrite := u.writesNzvc
      p.xNew       := u.pXDst;    p.xOld := u.pXOld;        p.xWrite    := u.writesX
      p.retireAlone := u.isBranch
      p
    }

    // ── Commit / retire (combinational, read at head) ──────────────────────────
    val h0 = head
    val h1 = head + 1
    val p0 = payload.readAsync(h0)
    val p1 = payload.readAsync(h1)
    val retire0 = valids(h0) && completes(h0) && !flush.valid
    val retire1 = retire0 && valids(h1) && completes(h1) && !p0.retireAlone && !p1.retireAlone

    val traceVec     = Vec(CommitTrace(), 2)
    val traceFireVec = Vec(Bool(), 2)

    // defaults
    for (k <- 0 until 2) {
      rc.commitPorts(k).valid := False
      rc.commitPorts(k).payload.assignDontCare()
      traceFireVec(k) := False
      traceVec(k).assignDontCare()
    }

    def driveCommit(k: Int, p: RobPayload): Unit = {
      rc.commitPorts(k).valid     := True
      rc.commitPorts(k).intArch   := p.archRegId
      rc.commitPorts(k).intNew    := p.intNew
      rc.commitPorts(k).intOld    := p.intOld
      rc.commitPorts(k).intWrite  := p.intWrite
      rc.commitPorts(k).nzvcNew   := p.nzvcNew
      rc.commitPorts(k).nzvcOld   := p.nzvcOld
      rc.commitPorts(k).nzvcWrite := p.nzvcWrite
      rc.commitPorts(k).xNew      := p.xNew
      rc.commitPorts(k).xOld      := p.xOld
      rc.commitPorts(k).xWrite    := p.xWrite

      traceFireVec(k)          := True
      traceVec(k).fire         := True
      traceVec(k).pc           := p.predNextPc
      traceVec(k).opword       := 0
      traceVec(k).archRegId    := p.archRegId
      traceVec(k).archRegWrite := 0
      traceVec(k).archRegValid := p.intWrite
      traceVec(k).ccr          := 0
      traceVec(k).ccrValid     := False
      traceVec(k).memAddr      := 0
      traceVec(k).memData      := 0
      traceVec(k).memWrite     := False
      traceVec(k).excTaken     := False
      traceVec(k).excVector    := 0
    }

    when(retire0) { driveCommit(0, p0) }
    when(retire1) { driveCommit(1, p1) }

    val retiredThisCycle = (retire1 ? U(2) | (retire0 ? U(1) | U(0))).resize(count.getWidth)

    // ── Dispatch / alloc ────────────────────────────────────────────────────────
    ru.uops.ready := count <= (depth - 2)
    val alloc0 = ru.uops.fire
    val alloc1 = ru.uops.fire && ru.uop1Valid
    val allocThisCycle = (alloc1 ? U(2) | (alloc0 ? U(1) | U(0))).resize(count.getWidth)

    when(alloc0) {
      payload.write(tail, payloadFrom(ru.uops.payload(0)))
      valids(tail)    := True
      completes(tail) := False
    }
    when(alloc1) {
      payload.write(tail + 1, payloadFrom(ru.uops.payload(1)))
      valids(tail + 1)    := True
      completes(tail + 1) := False
    }
    when(ru.uops.fire) {
      tail := tail + Mux(ru.uop1Valid, U(2, robIdW bits), U(1, robIdW bits))
    }

    // ── Completion mark (retire-clear has priority on same index) ───────────────
    when(markComplete.valid) {
      completes(markComplete.payload) := True
    }

    // ── Retire-side state updates (head advance + valid clear) ──────────────────
    when(retire0) { valids(h0) := False }
    when(retire1) { valids(h1) := False }
    head := head + Mux(retire1, U(2, robIdW bits), Mux(retire0, U(1, robIdW bits), U(0, robIdW bits)))

    // ── count update (alloc + retire) ──────────────────────────────────────────
    count := count + allocThisCycle - retiredThisCycle

    // ── Flush (squash all in-flight) ────────────────────────────────────────────
    when(flush.valid) {
      tail  := head
      count := 0
      valids.foreach(_ := False)
    }
    rc.flushPort := flush.valid
  }

  override def trace     = logic.traceVec
  override def traceFire = logic.traceFireVec
}
