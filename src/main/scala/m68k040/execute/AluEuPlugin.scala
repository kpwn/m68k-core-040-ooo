package m68k040.execute

import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{IntRegFileService, NzvcRegFileService, XRegFileService,
  RegFileReadPort, RegFileWritePort, RegFileBypassPort}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Plain-wire ports: producer (IQ/test) drives `issue`; consumer (ROB/test) reads `completion`. */
trait AluEuService {
  def issue: Stream[IqContext]
  def completion: Flow[UInt]   // robId
}

/** Sim-only per-instruction writeback observation (NaxRiscv-style whitebox). */
case class WbObs() extends Bundle {
  val valid     = Bool()
  val robId     = UInt(6 bits)
  val dstArch   = UInt(4 bits)
  val result    = Bits(32 bits)
  val intWrite  = Bool()
  val nzvc      = Bits(4 bits)
  val nzvcWrite = Bool()
  val x         = Bool()
  val xWrite    = Bool()
}

/** Fixed-latency-1 integer ALU EU. S0 read | M2S | S1 execute+writeback+bypass+completion. */
class AluEuPlugin extends FiberPlugin with AluEuService {
  var issuePort: Stream[IqContext] = null
  var completionPort: Flow[UInt]   = null
  // PRF ports (allocated in setup)
  var rdA, rdB: RegFileReadPort = null
  var intW: RegFileWritePort = null;  var intByp: RegFileBypassPort = null
  var nzvcW: RegFileWritePort = null; var nzvcByp: RegFileBypassPort = null
  var xW: RegFileWritePort = null;    var xByp: RegFileBypassPort = null

  override def issue: Stream[IqContext] = issuePort
  override def completion: Flow[UInt]   = completionPort

  during setup {
    issuePort      = Stream(IqContext())
    completionPort = Flow(UInt(6 bits))
    val irf = host[IntRegFileService]
    rdA = irf.newRead(); rdB = irf.newRead()
    intW = irf.newWrite(latency = 1); intByp = irf.newBypass()
    val nz = host[NzvcRegFileService]
    nzvcW = nz.newWrite(latency = 1); nzvcByp = nz.newBypass()
    val xrf = host[XRegFileService]
    xW = xrf.newWrite(latency = 1); xByp = xrf.newBypass()
  }

  val logic = during build new Area {
    // ---- S0: read ----
    issuePort.ready := True              // fixed-latency EU never structurally stalls
    val u0 = issuePort.payload.uop
    rdA.addr := u0.psrcA
    rdB.addr := u0.psrcB
    val src1 = rdA.data
    val src2 = Mux(u0.useImm, u0.imm, rdB.data)

    // ---- S0 -> S1 register (M2S) ----
    val s1Valid = RegNext(issuePort.valid) init False
    val s1Ctx   = RegNext(issuePort.payload)   // IqContext (uop + robId)
    val s1Src1  = RegNext(src1)
    val s1Src2  = RegNext(src2)
    val u1 = s1Ctx.uop

    // ---- S1: execute ----
    val cmd = AluCmd()
    cmd.op   := u1.op
    cmd.size := u1.size
    cmd.src1 := s1Src1
    cmd.src2 := s1Src2
    cmd.xIn  := False                    // no flag-read ops yet
    val rsp = AluDatapath(cmd)

    // ---- S1: writeback (gated by masks) ----
    intW.valid   := s1Valid && u1.pdstValid;  intW.address   := u1.pdst;     intW.data   := rsp.result
    nzvcW.valid  := s1Valid && u1.writesNzvc; nzvcW.address  := u1.pNzvcDst;  nzvcW.data  := rsp.nzvc
    xW.valid     := s1Valid && u1.writesX;    xW.address     := u1.pXDst;     xW.data     := B(rsp.xOut)

    // ---- S1: bypass (mirror the writes; forwards to a dependent reading this cycle) ----
    intByp.valid  := intW.valid;  intByp.address  := intW.address;  intByp.data  := intW.data
    nzvcByp.valid := nzvcW.valid; nzvcByp.address := nzvcW.address; nzvcByp.data := nzvcW.data
    xByp.valid    := xW.valid;    xByp.address    := xW.address;    xByp.data    := xW.data

    // ---- S1: completion ----
    completionPort.valid   := s1Valid
    completionPort.payload := s1Ctx.robId

    // ---- S1: sim-only whitebox writeback observation (NaxRiscv-style) ----
    // Per-instruction value+flags+masks keyed by robId; the lock-step harness
    // joins this with the ROB commit-obs (retire order + pc) to reconstruct the
    // architectural CommitObservation stream. No synthesizable cost (sim-only).
    val wbObs = WbObs()
    wbObs.valid     := s1Valid
    wbObs.robId     := s1Ctx.robId
    wbObs.dstArch   := u1.dstArch
    wbObs.result    := rsp.result
    wbObs.intWrite  := u1.pdstValid
    wbObs.nzvc      := rsp.nzvc
    wbObs.nzvcWrite := u1.writesNzvc
    wbObs.x         := rsp.xOut
    wbObs.xWrite    := u1.writesX
    wbObs.simPublic()
  }
}
