package m68k040.execute

import m68k040.decode.DecOp
import m68k040.isa.Size
import m68k040.execute.iq.{IqContext, AluSlowWakeup}
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
  /** Dynamic-completion wakeup (mirroring DivEu/LsEu): the SLOW path (shift, lat2)
    * broadcasts its int+NZVC+X dsts the cycle the result lands (S2). The IQ holds a
    * dependent of a slow producer until this fires. Fast (lat1) ops do NOT drive it. */
  def slowWakeup: Flow[AluSlowWakeup]
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

/** Fast/slow integer ALU EU. S0 read | M2S | S1 execute.
  * FAST path (ADD/SUB/AND/OR/EOR/CMP/MOVE/imm/CLR/NEGX/EXT/SWAP): writeback +
  * bypass + completion at S1 (latency-1) — the dependent-chain IPC path, UNCHANGED.
  * SLOW path (isShift || toCcr): the barrel shifter + the CCR-RMW are the 24-level
  * `s1Src2 -> NZVC` cone; they REGISTER into S2 and compute there, completing +
  * writing back (separate write ports) + broadcasting `slowWakeup` at latency-2.
  * A slow op in S1 deasserts `issue.ready` for that one cycle (single-outstanding)
  * so a fast op cannot enter S1 and collide with the slow op's S2 completion. */
class AluEuPlugin extends FiberPlugin with AluEuService {
  var issuePort: Stream[IqContext] = null
  var completionPort: Flow[UInt]   = null
  var slowWakeupPort: Flow[AluSlowWakeup] = null
  // PRF ports (allocated in setup)
  var rdA, rdB: RegFileReadPort = null
  var intW: RegFileWritePort = null;  var intByp: RegFileBypassPort = null
  var nzvcW: RegFileWritePort = null; var nzvcByp: RegFileBypassPort = null
  var xW: RegFileWritePort = null;    var xByp: RegFileBypassPort = null
  // SLOW-path write + bypass ports (latency-1 off the S2 stage => arch latency-2).
  // Separate from the fast ports so a fast op (S1) and a slow op (S2) never contend
  // for one write port; the single-outstanding S1 stall keeps their COMPLETION ports
  // from colliding, but the writeback ports are independent for safety/clarity.
  var intWs: RegFileWritePort = null;  var intByps: RegFileBypassPort = null
  var nzvcWs: RegFileWritePort = null; var nzvcByps: RegFileBypassPort = null
  var xWs: RegFileWritePort = null;    var xByps: RegFileBypassPort = null
  // Flag SOURCE read ports — used ONLY by the ANDI/ORI/EORI #imm,CCR read-modify-write
  // (toCcr): the op reads the current NZVC + X to fold the immediate into the CCR.
  var nzvcRd: RegFileReadPort = null
  var xRd: RegFileReadPort = null

  override def issue: Stream[IqContext] = issuePort
  override def completion: Flow[UInt]   = completionPort
  override def slowWakeup: Flow[AluSlowWakeup] = slowWakeupPort

  during setup {
    issuePort      = Stream(IqContext())
    completionPort = Flow(UInt(6 bits))
    slowWakeupPort = Flow(AluSlowWakeup())
    val irf = host[IntRegFileService]
    rdA = irf.newRead(); rdB = irf.newRead()
    intW = irf.newWrite(latency = 1); intByp = irf.newBypass()
    intWs = irf.newWrite(latency = 1); intByps = irf.newBypass()
    val nz = host[NzvcRegFileService]
    nzvcW = nz.newWrite(latency = 1); nzvcByp = nz.newBypass()
    nzvcWs = nz.newWrite(latency = 1); nzvcByps = nz.newBypass()
    nzvcRd = nz.newRead(forceNoBypass = false)
    val xrf = host[XRegFileService]
    xW = xrf.newWrite(latency = 1); xByp = xrf.newBypass()
    xWs = xrf.newWrite(latency = 1); xByps = xrf.newBypass()
    xRd = xrf.newRead(forceNoBypass = false)
  }

  val logic = during build new Area {
    // ---- S0: read ----
    val u0 = issuePort.payload.uop
    rdA.addr := u0.psrcA
    rdB.addr := u0.psrcB
    val src1 = rdA.data
    val src2 = Mux(u0.useImm, u0.imm, rdB.data)
    // Flag sources (only the toCcr read-modify-write uses them).
    nzvcRd.addr := u0.pNzvcSrc
    xRd.addr    := u0.pXSrc

    // ---- S0 -> S1 register (M2S) ----
    val s1Valid = RegNext(issuePort.valid && issuePort.ready) init False
    val s1Ctx   = RegNext(issuePort.payload)   // IqContext (uop + robId)
    val s1Src1  = RegNext(src1)
    val s1Src2  = RegNext(src2)
    val s1Nzvc  = RegNext(nzvcRd.data)         // {N(3),Z(2),V(1),C(0)} (toCcr)
    val s1X     = RegNext(xRd.data(0))         // X (toCcr)
    val u1 = s1Ctx.uop

    // ── slow-op classification: the barrel shifter (DecOp.SHIFT) — the 64-wide
    // funnel + shTable + the ROX rmod subtract chain — is the deep part of the
    // 24-level `s1Src2 -> NZVC` cone, so it goes to the 2-cycle (S1->S2) path.
    // The CCR-RMW (toCcr) STAYS on the fast path: it is a shallow 5-bit logic fold
    // (NOT the deep cone), and keeping it fast avoids a flag-class dynamic wakeup —
    // toCcr writes only NZVC+X (flag physregs), which are tracked by the STATIC
    // latency-1 flag scoreboards (sbNzvc/sbX); a lat2 flag write would desync them.
    // (Re-confirmed by synth: moving the shifter alone clears the cone.) ──
    val isShift = u1.op === DecOp.SHIFT
    val isSlow  = isShift

    // Single-outstanding SLOW: hold new issue while a slow op occupies ANY of S1/S2/S3
    // (it now completes at S3). Prevents (a) two slow ops contending for the single slow
    // write/completion ports and (b) a fast op's completion coinciding with the slow op's
    // S3 completion. Slow ops are rare; the sibling ALU EU + 2-wide IQ absorb the bubble.
    // (s2Valid/s3Valid are the RegNext chain of `s1Valid && isSlow`, declared in the SLOW
    // PATH section below; forward-declared here as plain Bools and wired there.)
    val s2Valid = Bool()
    val s3Valid = Bool()
    issuePort.ready := !((s1Valid && isSlow) || s2Valid || s3Valid)

    // ---- S1: FAST execute (ALU datapath; NO shifter, NO CCR-RMW on this cone) ----
    val cmd = AluCmd()
    cmd.op      := u1.op
    cmd.size    := u1.size
    cmd.src1    := s1Src1
    cmd.src2    := s1Src2
    cmd.xIn     := s1X                    // current X (NEGX: 0 - Dn - X)
    cmd.extByte := u1.extByte             // EXT/EXTB byte-source marker
    cmd.bitOp   := u1.bitOp               // BTST/BCHG/BCLR/BSET sub-kind
    val rsp = AluDatapath(cmd)

    // ── Extended-arith family (NEGX/ADDX/SUBX): the 68k Z is CLEAR-ONLY ─────────
    // Z := Z_old && (result == 0). These ops read NZVC (-> s1Nzvc holds the old CCR), so
    // the old Z is s1Nzvc(2). The datapath produced the raw Z (rsp.nzvc(2)); AND it with
    // the old Z so a zero result PRESERVES a prior Z=0 (only clears, never sets, Z) — the
    // multi-precision rule. N/V/C and X are unchanged from the datapath (X = carry/borrow
    // out). ADDX/SUBX reuse NEGX's merge verbatim (all three set readsNzvc).
    val isExtended = (u1.op === DecOp.NEGX) || (u1.op === DecOp.ADDX) || (u1.op === DecOp.SUBX)
    val extZ       = rsp.nzvc(2) && s1Nzvc(2)
    val extNzvc    = rsp.nzvc(3) ## extZ ## rsp.nzvc(1 downto 0)

    // ── BITOP: Z-only flag write (preserve N/V/C; X untouched) ──────────────────
    // Bit-ops set ONLY Z (= the tested bit's complement, in rsp.nzvc(2)); N/V/C come
    // from the OLD CCR (s1Nzvc — BITOP readsNzvc, so it holds the old flags). X is not
    // written (writesX=False). Same shallow merge mechanism as NEGX's partial Z.
    val isBitOp  = u1.op === DecOp.BITOP
    val bitNzvc  = s1Nzvc(3) ## rsp.nzvc(2) ## s1Nzvc(1) ## s1Nzvc(0)   // {N_old, Z, V_old, C_old}
    val aluNzvc  = Mux(isBitOp, bitNzvc, Mux(isExtended, extNzvc, rsp.nzvc))

    // ---- S1: size-merge of the FAST int writeback (68k partial-register semantics) ----
    // A .B / .W op updates ONLY the low byte / word of the destination register; the
    // upper bits are PRESERVED. For ADD/SUB/AND/OR/EOR the destination operand is srcA
    // (src1), so the old register value is s1Src1 -> merge its upper bits with the
    // datapath's low `size` result. MOVE.B/.W to a DATA register also reaches this
    // merge: the decoder makes such a MOVE READ its destination Dn as srcA (the merge
    // source), so s1Src1 holds the old Dn and the upper bytes are preserved. MOVE.L
    // (and any .L op) takes the full result. The fast path no longer muxes the shifter
    // result (a SHIFT is slow), so opResult is the ALU datapath result directly.
    val opResult = rsp.result
    val sizeMerged = u1.size.mux(
      Size.BYTE -> (s1Src1(31 downto 8)  ## opResult(7 downto 0)),
      Size.WORD -> (s1Src1(31 downto 16) ## opResult(15 downto 0)),
      Size.LONG -> opResult)
    // MOVEA (MOVE to An): An is ALWAYS written full-32 — NO partial merge. The .W form
    // SIGN-EXTENDS the 16-bit source (the MOVE source is src2); .L writes it whole.
    // (MOVEA never reaches a .B form — byte MOVEA is illegal in the ISA.)
    val moveaResult = Mux(u1.size === Size.WORD,
      s1Src2(15 downto 0).asSInt.resize(32).asBits, s1Src2)
    val mergedResult = Mux(u1.isMovea, moveaResult, sizeMerged)

    // ---- S1: ANDI/ORI/EORI #imm,CCR (toCcr) — CCR read-modify-write (FAST path) ----
    // Assemble the current 5-bit CCR {X,N,Z,V,C} from the flag PRFs, apply the logical
    // op against imm[4:0] (s1Src2, the imm byte), split the result back: NZVC =
    // ccr5'[3:0], X = ccr5'[4]. CCR layout X=4,N=3,Z=2,V=1,C=0 lines the flag halves up
    // directly (no reshuffling). This is a shallow 5-bit fold (NOT the deep cone), so
    // it stays latency-1 — toCcr's NZVC+X writes remain statically tracked (sbNzvc/sbX).
    val ccrOld = s1X ## s1Nzvc(3 downto 0)         // {X,N,Z,V,C}
    val ccrImm = s1Src2(4 downto 0)
    val ccrNew = u1.op.mux(
      DecOp.AND -> (ccrOld & ccrImm),
      DecOp.OR  -> (ccrOld | ccrImm),
      default   -> (ccrOld ^ ccrImm))              // EOR (toCcr only AND/OR/EOR reach here)
    val ccrNzvc = ccrNew(3 downto 0)
    val ccrX    = ccrNew(4)

    // Fast final flags: CCR-rmw for toCcr, else the ALU datapath (NO shifter).
    val finalNzvc = Mux(u1.toCcr, ccrNzvc, aluNzvc)
    val finalX    = Mux(u1.toCcr, ccrX, rsp.xOut)

    // ---- S1: FAST writeback (gated by masks; suppressed for a slow op) ----
    val fastFire = s1Valid && !isSlow
    intW.valid   := fastFire && u1.pdstValid;  intW.address   := u1.pdst;     intW.data   := mergedResult
    nzvcW.valid  := fastFire && u1.writesNzvc; nzvcW.address  := u1.pNzvcDst;  nzvcW.data  := finalNzvc
    xW.valid     := fastFire && u1.writesX;    xW.address     := u1.pXDst;     xW.data     := B(finalX)

    // ---- S1: FAST bypass (mirror the writes; forwards to a dependent reading now) ----
    intByp.valid  := intW.valid;  intByp.address  := intW.address;  intByp.data  := intW.data
    nzvcByp.valid := nzvcW.valid; nzvcByp.address := nzvcW.address; nzvcByp.data := nzvcW.data
    xByp.valid    := xW.valid;    xByp.address    := xW.address;    xByp.data    := xW.data

    // ============================ SLOW PATH (S1 -> S2) ============================
    // Pipeline the barrel shifter ACROSS the S1->S2 boundary: COMPUTE the shifter in S1
    // (from the s1 operands, in parallel with the fast ALU but NOT on the fast S1
    // writeback cone — its outputs go to S2 registers, not the fast write ports), then
    // S2 does only the shallow size-merge + writeback. This keeps the deep shifter cone
    // OFF both the fast S1 writeback path AND the S2->NZVC-RAM write path: the shifter's
    // long cone now ends at the local s2Shift* FFs (a register endpoint, off the central
    // flag-RAM routing), and S2's write cone is a shallow mux + RAM write.
    //
    // ── S1: line-E barrel shifter (DecOp.SHIFT) ────────────────────────────────
    // The shift INPUT (Dr) is src1; the count is the immediate (useImm -> imm[5:0]) or
    // the 2nd data-reg source Dc (src2[5:0] = Dc & 0x3f). X-in is the current X. The
    // barrel shifter produces result + NZVCX; ROL/ROR leave X (writesX=False), and the
    // count-0 / count>=size specials are inside it.
    val shiftCmd = ShiftCmd()
    shiftCmd.shiftOp := u1.shiftOp.asUInt
    shiftCmd.dirLeft := u1.shiftDir
    shiftCmd.size    := u1.size
    shiftCmd.data    := s1Src1
    shiftCmd.count   := s1Src2(5 downto 0).asUInt
    shiftCmd.isImm   := u1.useImm
    shiftCmd.xIn     := s1X

    // SLOW path stage 1 (S1): the deep variable-shift networks only. Register the
    // ShiftStage1 midpoint into S2 (the cut that halves the cone).
    val s1Stage1 = Shifter.stage1(shiftCmd)
    s2Valid     := RegNext(s1Valid && isSlow) init False
    val s2Stage1 = RegNext(s1Stage1)
    val s2Ctx    = RegNext(s1Ctx)
    val s2Src1   = RegNext(s1Src1)        // merge source preserved to S3

    // SLOW path stage 2 (S2): bit-extract + mux on the registered midpoint. Register
    // the finished result/flags into S3 (arch latency-3).
    val s2Rsp    = Shifter.stage2(s2Stage1)
    s3Valid     := RegNext(s2Valid) init False
    val s3Ctx    = RegNext(s2Ctx)
    val s3Src1   = RegNext(s2Src1)
    val s3ShiftRes  = RegNext(s2Rsp.result)
    val s3ShiftNzvc = RegNext(s2Rsp.n ## s2Rsp.z ## s2Rsp.v ## s2Rsp.c)
    val s3ShiftX    = RegNext(s2Rsp.xOut)
    val u3 = s3Ctx.uop

    // ── S3: SHIFT int writeback — the shift dst is Dr = src1, so the .B/.W upper-
    // preserve merge applies exactly as for a fast op (merge against s3Src1). ──
    val slowMerged = u3.size.mux(
      Size.BYTE -> (s3Src1(31 downto 8)  ## s3ShiftRes(7 downto 0)),
      Size.WORD -> (s3Src1(31 downto 16) ## s3ShiftRes(15 downto 0)),
      Size.LONG -> s3ShiftRes)
    val slowResult = slowMerged
    val slowNzvc   = s3ShiftNzvc
    val slowX      = s3ShiftX

    // ---- S3: SLOW writeback (separate ports; latency-3) ----
    intWs.valid   := s3Valid && u3.pdstValid;  intWs.address  := u3.pdst;     intWs.data  := slowResult
    nzvcWs.valid  := s3Valid && u3.writesNzvc; nzvcWs.address := u3.pNzvcDst;  nzvcWs.data := slowNzvc
    xWs.valid     := s3Valid && u3.writesX;    xWs.address    := u3.pXDst;     xWs.data    := B(slowX)
    // ---- S3: SLOW bypass (forwards to a dependent reading the cycle S3 commits) ----
    intByps.valid  := intWs.valid;  intByps.address  := intWs.address;  intByps.data  := intWs.data
    nzvcByps.valid := nzvcWs.valid; nzvcByps.address := nzvcWs.address; nzvcByps.data := nzvcWs.data
    xByps.valid    := xWs.valid;    xByps.address    := xWs.address;    xByps.data    := xWs.data

    // ---- SLOW dynamic-completion wakeup (mirror LsEu/DivEu): broadcast the shift's
    // int + NZVC + X dsts the cycle the result lands in the PRF (S3). The IQ holds a
    // dependent of the shift (int OR flag source) until this fires (aluSlowWait). ----
    slowWakeupPort.valid            := s3Valid
    slowWakeupPort.payload.pdst     := u3.pdst
    slowWakeupPort.payload.pdstValid:= u3.pdstValid
    slowWakeupPort.payload.pNzvcDst := u3.pNzvcDst
    slowWakeupPort.payload.nzvcValid:= u3.writesNzvc
    slowWakeupPort.payload.pXDst    := u3.pXDst
    slowWakeupPort.payload.xValid   := u3.writesX

    // ---- completion: fast (S1) OR slow (S3) — single port, never both same cycle
    // (the single-outstanding stall guarantees S1-fast and S3-slow never coincide). ----
    completionPort.valid   := fastFire || s3Valid
    completionPort.payload  := Mux(s3Valid, s3Ctx.robId, s1Ctx.robId)

    // ---- sim-only whitebox writeback observation (NaxRiscv-style) ----
    // Per-instruction value+flags+masks keyed by robId; the lock-step harness joins
    // this with the ROB commit-obs to reconstruct the architectural CommitObservation
    // stream. Registered (sim-only) so the harness reading wbObs in onSamplings gets
    // stable one-cycle pulses. A FAST op publishes from S1 (one extra reg => same cycle
    // as its completion's downstream consumers expect); a SLOW op publishes from S2.
    // The two never fire in the same source cycle (single-outstanding), so a single
    // muxed record per cycle is correct.
    val obsValidS  = Mux(s3Valid, True, fastFire)
    val obsRobId   = Mux(s3Valid, s3Ctx.robId, s1Ctx.robId)
    val obsDstArch = Mux(s3Valid, u3.dstArch, u1.dstArch)
    val obsResult  = Mux(s3Valid, slowResult, mergedResult)
    val obsIntW    = Mux(s3Valid, u3.pdstValid, u1.pdstValid)
    val obsNzvc    = Mux(s3Valid, slowNzvc, finalNzvc)
    val obsNzvcW   = Mux(s3Valid, u3.writesNzvc, u1.writesNzvc)
    val obsX       = Mux(s3Valid, slowX, finalX)
    val obsXW      = Mux(s3Valid, u3.writesX, u1.writesX)
    val wbObs = WbObs()
    wbObs.valid     := RegNext(obsValidS) init False
    wbObs.robId     := RegNext(obsRobId)
    wbObs.dstArch   := RegNext(obsDstArch)
    wbObs.result    := RegNext(obsResult)
    wbObs.intWrite  := RegNext(obsIntW)
    wbObs.nzvc      := RegNext(obsNzvc)
    wbObs.nzvcWrite := RegNext(obsNzvcW)
    wbObs.x         := RegNext(obsX)
    wbObs.xWrite    := RegNext(obsXW)
    // Generic ALU crack-drop marker: divIsRem is set ONLY on the CPLX DIVREM µop
    // (never a real ALU op), so the LINK/UNLK A7-fold ALU µops reuse it to DROP their
    // commit record (their A7 write still folds into the running architectural A7).
    wbObs.divRem    := RegNext(Mux(s3Valid, u3.divIsRem, u1.divIsRem)) init False
    wbObs.simPublic()
  }
}
