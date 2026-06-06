package m68k040.execute

import m68k040.decode.DecOp
import m68k040.isa.Size
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
  val dstArch   = UInt(5 bits)
  val result    = Bits(32 bits)
  val intWrite  = Bool()
  val nzvc      = Bits(4 bits)
  val nzvcWrite = Bool()
  val x         = Bool()
  val xWrite    = Bool()
  // True for the trailing DIVREM crack µop (the 2nd µop of a DIVU.L/DIVS.L that
  // writes the remainder to Dr). The lock-step whitebox DROPS its commit record so a
  // 2-µop divide maps to ONE oracle instruction step (positional alignment); the
  // remainder register write still lands in the PRF and is verified by a later
  // instruction that reads Dr. Default False (all other EUs leave it False).
  val divRem    = Bool()
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

    // ---- S1: size-merge of the int writeback (68k partial-register semantics) ----
    // A .B / .W ALU op updates ONLY the low byte / word of the destination register;
    // the upper bits are PRESERVED. For ADD/SUB/AND/OR/EOR the destination operand is
    // srcA (src1), so the old register value is s1Src1 -> merge its upper bits with the
    // datapath's low `size` result. (MOVE's dst is NOT src1 — MOVE keeps the full
    // datapath result, preserving the existing MOVE.L path; MOVE.B/.W reg-dest is a
    // separate concern outside this slice and is not regressed here.) .L = full result.
    val isMove = u1.op === DecOp.MOVE
    val mergedResult = Mux(isMove, rsp.result, u1.size.mux(
      Size.BYTE -> (s1Src1(31 downto 8)  ## rsp.result(7 downto 0)),
      Size.WORD -> (s1Src1(31 downto 16) ## rsp.result(15 downto 0)),
      Size.LONG -> rsp.result))

    // ---- S1: writeback (gated by masks) ----
    intW.valid   := s1Valid && u1.pdstValid;  intW.address   := u1.pdst;     intW.data   := mergedResult
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
    // Registered (sim-only) so the lock-step harness reading wbObs in onSamplings
    // gets stable one-cycle pulses (reading combinational result there races).
    val wbObs = WbObs()
    wbObs.valid     := RegNext(s1Valid) init False
    wbObs.robId     := RegNext(s1Ctx.robId)
    wbObs.dstArch   := RegNext(u1.dstArch)
    wbObs.result    := RegNext(mergedResult)
    wbObs.intWrite  := RegNext(u1.pdstValid)
    wbObs.nzvc      := RegNext(rsp.nzvc)
    wbObs.nzvcWrite := RegNext(u1.writesNzvc)
    wbObs.x         := RegNext(rsp.xOut)
    wbObs.xWrite    := RegNext(u1.writesX)
    wbObs.divRem    := False
    wbObs.simPublic()
  }
}
