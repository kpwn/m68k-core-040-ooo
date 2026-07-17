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
  // KEEP this commit as its own oracle step even though it writes only a TEMP (T1) and
  // no flags: a mem-dest MOVE-from-CCR/SR's op µop produces the CCR/SR byte into T1 (the
  // trailing store -> memory), but the macro instruction HAS an architectural effect (the
  // memory write) and so needs exactly one kept commit observation (its PC). Without this
  // the temp-only op µop would be dropped AND the store is an rmwStore drop -> the whole
  // instruction would vanish from the commit stream (PC misalign). Default False; only the
  // ALU sets it (fromCcr/fromSr to a temp dst). The reg-dest forms write a real Dn and are
  // kept normally (keepCommit stays False there, harmlessly).
  val keepCommit = Bool()
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
  var rdA, rdB, rdC: RegFileReadPort = null
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
  // Committed SR system byte (for MOVE-from-SR's int result = {srSysIn, CCR}). Wired
  // from the ROB's exc.ss.srSys in BackendWiringPlugin; defaults to 0 if unwired (the
  // standalone AluEu DUTs never exercise fromSr). Safe to read combinationally: the only
  // writer of srSys (exception entry / RTE) fully flushes, so no in-flight fromSr µop
  // ever observes a stale srSys.
  var srSysIn: UInt = null

  override def issue: Stream[IqContext] = issuePort
  override def completion: Flow[UInt]   = completionPort
  override def slowWakeup: Flow[AluSlowWakeup] = slowWakeupPort

  during setup {
    issuePort      = Stream(IqContext())
    completionPort = Flow(UInt(6 bits))
    slowWakeupPort = Flow(AluSlowWakeup())
    val irf = host[IntRegFileService]
    rdA = irf.newRead(); rdB = irf.newRead()
    // 3rd int read port: the BITFIELD-dynamic µop's srcC = T0 (BFRESOLVE-packed
    // offset/width). Only that op uses psrcC on the ALU EU (idle/psrcC=0 otherwise).
    rdC = irf.newRead()
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
    // Committed SR system byte input (for MOVE-from-SR). Created in setup (stable ref for
    // the wiring plugin); default-idle in build, the wiring overrides with exc.ss.srSys.
    srSysIn = UInt(8 bits)
  }

  val logic = during build new Area {
    // ---- S0: read ----
    val u0 = issuePort.payload.uop
    rdA.addr := u0.psrcA
    rdB.addr := u0.psrcB
    rdC.addr := u0.psrcC                 // BITFIELD-dynamic srcC = T0 (packed offset/width)
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
    // Raw rdB.data (bypassing the useImm mux): used by PACK/UNPK so they can have both
    // srcA=Dx (old, merge source) AND srcB=Dy (source data) in s1RdB, while imm=adj16
    // rides s1Src2. All other ops that set useImm=False read s1Src2 == rdB.data anyway.
    val s1RdB   = RegNext(rdB.data)
    // s1RdC = the BITFIELD-dynamic packed offset/width (srcC = T0 from BFRESOLVE).
    val s1RdC   = RegNext(rdC.data)
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
    // The bit-field datapath (DecOp.BITFIELD) reuses the slow path: it adds two 32-bit
    // barrel rotates + a CLZ (BFFFO) — a comparable cone to the shifter — and writes
    // NZ only (V=C=0, X untouched). Routed lat-matched (S1->S3) like SHIFT, with the
    // same dynamic slowWakeup so a dependent waits on the result landing.
    val isBitfield = u1.op === DecOp.BITFIELD
    val isSlow  = isShift || isBitfield

    // Single-outstanding SLOW: hold new issue while a slow op occupies ANY of S1/S2/S3
    // (it now completes at S3). Prevents (a) two slow ops contending for the single slow
    // write/completion ports and (b) a fast op's completion coinciding with the slow op's
    // S3 completion. Slow ops are rare; the sibling ALU EU + 2-wide IQ absorb the bubble.
    // (s1aValid/s2Valid/s3Valid are the RegNext chain of `s1Valid && isSlow`, declared in
    // the SLOW PATH section below; forward-declared here as plain Bools and wired there.)
    // FMax #3 added the S1a stage (split stage1); FMax #4 added the S1b stage (split the
    // bit-field forward-funnel -> modify cone); task #123 added the S1a2 stage (split the
    // bit-field modify's clz+mux from its rotate/shift half), so the slow pipe is now
    // S1/S1a/S1a2/S1b/S2/S3.
    val s1aValid = Bool()
    val s1a2Valid = Bool()
    val s1bValid = Bool()
    val s2Valid  = Bool()
    val s3Valid  = Bool()
    issuePort.ready := !((s1Valid && isSlow) || s1aValid || s1a2Valid || s1bValid || s2Valid || s3Valid)

    // ---- S1: FAST execute (ALU datapath; NO shifter, NO CCR-RMW on this cone) ----
    // ── ADDA/SUBA/CMPA "An-wide" marker: isMovea on a non-MOVE ALU op. The op runs
    // FULL-32 regardless of the size field (An has no partial write), the .W source
    // is SIGN-EXTENDED to 32 first (Musashi MAKE_INT_16), and (CMPA) the flags are
    // computed at 32-bit width. The size field still sized the cracked LOAD for a
    // .W memory source; here it only selects the sign-extension. ──────────────────
    val anWide = u1.isMovea && (u1.op =/= DecOp.MOVE)
    val anWideSrc2 = Mux(u1.size === Size.WORD,
                         s1Src2(15 downto 0).asSInt.resize(32).asBits, s1Src2)
    val cmd = AluCmd()
    cmd.op      := u1.op
    cmd.size    := Mux(anWide, Size.LONG, u1.size)
    cmd.src1    := s1Src1
    cmd.src2    := Mux(anWide, anWideSrc2, s1Src2)
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

    // ── BCD decimal-adjust datapath (ABCD/SBCD, byte) — Musashi-bit-matched ──────
    // Transcribed verbatim from tools/musashi/musashi/m68k_in.c m68k_op_abcd_8_rr /
    // m68k_op_sbcd_8_rr. `res` is a C `uint` (32-bit unsigned): the low-nibble sum/diff
    // can wrap negative (SBCD) into a huge value, which makes the `>9` / `>0x99` tests
    // and the corrections behave decimally. We mirror that with 32-bit UInt lanes (no
    // pre-masking — the un-masked intermediate is what the C compares). Widths are kept
    // at Musashi's 32 bits deliberately (the lock-step is the arbiter; not hand-narrowed).
    //   dx = s1Src1[7:0] (the dst byte), dy = s1Src2[7:0] (the source byte), xin = old X.
    val isBcd = u1.op === DecOp.BCD
    val dx    = s1Src1(7 downto 0).asUInt
    val dy    = s1Src2(7 downto 0).asUInt
    val xin   = s1X.asUInt                                   // 0/1
    val dxLo  = dx(3 downto 0).resize(32)                    // LOW_NIBBLE(dst)
    val dyLo  = dy(3 downto 0).resize(32)                    // LOW_NIBBLE(src)
    val dxHi  = (dx & U"8'h_f0").resize(32)                  // HIGH_NIBBLE(dst)
    val dyHi  = (dy & U"8'h_f0").resize(32)                  // HIGH_NIBBLE(src)
    // ── ABCD (add): res = lo(src)+lo(dst)+X; Vraw=~res; if(res>9) res+=6;
    //    res += hi(src)+hi(dst); C=X=(res>0x99); if(C) res-=0xA0; V=bit7(Vraw&res);
    //    N=bit7(res); res8=res&0xff. ──
    val aLoSum = (dxLo + dyLo + xin.resize(32))              // 0..0x13
    val aVraw  = ~aLoSum                                      // FLAG_V = ~res (part I)
    val aAdj   = Mux(aLoSum > 9, aLoSum + 6, aLoSum)
    val aFull  = aAdj + dxHi + dyHi
    val aCarry = aFull > 0x99
    val aRes   = Mux(aCarry, aFull - 0xA0, aFull)            // pre-mask res (V/N read this)
    val aV     = (aVraw & aRes)(7)                           // FLAG_V &= res; CCR V = bit7
    val aN     = aRes(7)                                     // FLAG_N = NFLAG_8(res)
    val aRes8  = aRes(7 downto 0)
    // ── SBCD (subtract): res = lo(dst)-lo(src)-X (unsigned wrap); Vraw=~res;
    //    if(res>9) res-=6; res += hi(dst)-hi(src); C=X=(res>0x99); if(C) res+=0xA0;
    //    res8=res&0xff (BEFORE V/N here); V=bit7(Vraw&res8); N=bit7(res8). ──
    val sLoSub = (dxLo - dyLo - xin.resize(32))             // wraps in 32-bit unsigned
    val sVraw  = ~sLoSub                                     // FLAG_V = ~res (part I)
    val sAdj   = Mux(sLoSub > 9, sLoSub - 6, sLoSub)
    val sFull  = sAdj + dxHi - dyHi
    val sBorrow= sFull > 0x99
    val sFix   = Mux(sBorrow, sFull + 0xA0, sFull)
    val sRes8  = sFix(7 downto 0)                            // res = MASK_OUT_ABOVE_8(res)
    val sV     = (sVraw(7) & sRes8(7))                       // FLAG_V &= res8; CCR V = bit7
    val sN     = sRes8(7)                                    // FLAG_N = NFLAG_8(res)
    // ── select by bcdSub; C=X=decimal carry/borrow; Z is CLEAR-ONLY (FLAG_Z |= res). ──
    val bcdRes8  = Mux(u1.bcdSub, sRes8, aRes8)
    val bcdCarry = Mux(u1.bcdSub, sBorrow, aCarry)
    val bcdN     = Mux(u1.bcdSub, sN, aN)
    val bcdV     = Mux(u1.bcdSub, sV, aV)
    val bcdZ     = s1Nzvc(2) && (bcdRes8 === 0)              // Z := Z_old && res8==0
    val bcdNzvc  = bcdN ## bcdZ ## bcdV ## bcdCarry          // {N,Z,V,C}

    // ── PACK/UNPK datapath (register forms, no CCR effect) ──────────────────────
    // s1Src1 = Dx (old value, used as merge source for upper-bit preservation via sizeMerged).
    // s1RdB  = Dy (raw rdB.data, the source register — bypasses the useImm mux).
    // s1Src2 = adj16 (the 16-bit adjustment, sign-extended, from imm since useImm=True).
    //
    // PACK Dy,Dx,#adj: src=(Dy+adj)&0xffff; Dx[7:0] := ((src>>4)&0xF0) | (src&0x0F);
    //   Dx[31:8] preserved (.B merge uses s1Src1=Dx).
    // UNPK Dy,Dx,#adj: src=Dy&0xffff; Dx[15:0] := (((src<<4)&0x0F00)|(src&0x000F)+adj)&0xffff;
    //   Dx[31:16] preserved (.W merge uses s1Src1=Dx).
    // Both transcribed verbatim from Musashi m68k_op_pack_16_rr / m68k_op_unpk_16_rr.
    val isPack = u1.op === DecOp.PACK
    val isUnpk = u1.op === DecOp.UNPK
    // PACK: src = (Dy + adj) & 0xffff (Dy from s1RdB, adj from s1Src2)
    val packDy      = s1RdB.asUInt                                          // Dy (32-bit)
    val packAdj     = s1Src2.asUInt                                         // adj16 (sign-extended 32-bit)
    val packSrc32   = (packDy + packAdj) & U(0xFFFF, 32 bits)              // (Dy+adj) & 0xffff
    val packNibHi   = (packSrc32 >> 4)(7 downto 0) & U(0xF0, 8 bits)      // (src>>4) & 0xF0, 8-bit masked
    val packNibLo   = packSrc32(3 downto 0).resize(8) & U(0x0F, 8 bits)   // src & 0x0F, 4->8
    val packByte    = (packNibHi | packNibLo).resize(8).asBits             // 8-bit result
    val packRes8    = B(0, 24 bits) ## packByte                            // 32-bit, result in [7:0]
    // UNPK: src = Dy & 0xffff; expand = (src<<4)&0x0F00 | src&0x000F; result = (expand+adj)&0xffff
    val unpkDy      = s1RdB.asUInt                                          // Dy (32-bit)
    val unpkAdj     = s1Src2.asUInt                                         // adj16 (32-bit)
    val unpkSrc16   = unpkDy & U(0xFFFF, 32 bits)                          // Dy & 0xffff
    val unpkShifted = ((unpkSrc16 << 4) & U(0x0F00, 36 bits)).resize(32)  // (src<<4)&0x0F00, back to 32
    val unpkHi      = unpkShifted & U(0x0F00, 32 bits)                    // high nibble -> upper byte
    val unpkLo      = unpkSrc16 & U(0x000F, 32 bits)                      // low nibble
    val unpkExpand  = unpkHi | unpkLo                                       // expanded BCD
    val unpkRes16   = (unpkExpand + unpkAdj) & U(0xFFFF, 32 bits)         // + adj, mask 16-bit
    val unpkRes32   = unpkRes16.resize(32).asBits                          // 32-bit, result in [15:0]

    // ── BFRESOLVE datapath (bit-field dynamic offset/width resolve, FAST lat-1) ──
    // s1Src1 = offset-Dn (read iff Do); s1RdB = width-Dn (read iff Dw); s1Src2 = imm
    // (useImm=True): imm[4:0]=static offset, imm[9:5]=static raw-width, imm[10]=Do,
    // imm[11]=Dw. Produce packed = (Do?offDn[4:0]:immOff) | ((Dw?wdDn[4:0]:immWd)<<5),
    // the SAME layout the static imm uses. Result T0; size LONG (full-32 writeback).
    val isBfResolve = u1.op === DecOp.BFRESOLVE
    val bfrDo       = s1Src2(10)
    val bfrDw       = s1Src2(11)
    // imm[12] = MEM mode (slice 3c): produce the bfMem packed layout instead of the
    // register-form layout. The register form uses {rawWidth[9:5], offset[4:0]}; the
    // memory form needs {origOff[18:14], needHi[13], bitOff[12:10], rawWidth[9:5], 0[4:0]}
    // (the SAME layout the static bfMem imm uses, so the funnel reads it via srcC unchanged).
    val bfrMem      = s1Src2(12)
    val bfrOff      = Mux(bfrDo, s1Src1(4 downto 0), s1Src2(4 downto 0))   // offset & 31
    val bfrWd       = Mux(bfrDw, s1RdB(4 downto 0),  s1Src2(9 downto 5))
    val bfrRegRes   = (B(0, 22 bits) ## bfrWd ## bfrOff)
    // bitOff = offset & 7 (low 3 bits, valid for negative offsets via two's-complement);
    // needHi forced True (always-5-byte span — the funnel leaves the spill byte unchanged
    // when the field doesn't reach it); origOff = Do ? 0 : staticOff (Do=1 BFFFO adds the
    // full signed offset in a trailing cold ADD, so its funnel base must be 0).
    val bfrBitOff   = bfrOff(2 downto 0)
    val bfrOrigOff  = Mux(bfrDo, B(0, 5 bits), s1Src2(4 downto 0))
    val bfrMemRes   = (bfrOrigOff ## True ## bfrBitOff ## bfrWd ## B(0, 5 bits))   // 19 bits
    // imm[13] = BYTE-DELTA mode (slice 3c Do=1): output = offsetDn >>>signed 3 = the signed
    // byteBase delta (a FAST shift-by-constant, NOT the slow barrel shifter — so the dynamic-
    // mem crack has only ONE slow-pipe op, the funnel, matching the register-form dynamic).
    val bfrDelta    = s1Src2(13)
    val bfrByteDelta = (s1Src1.asSInt >> 3).resize(32).asBits                      // offset >>>signed 3 (sign-extended)
    val bfResolveRes = Mux(bfrDelta, bfrByteDelta,
                           Mux(bfrMem, bfrMemRes.resize(32), bfrRegRes.resize(32)))

    // ── CAS / CAS2 compute datapath (DecOp.CASOP, FAST lat-1) ───────────────────
    // The whole CAS/CAS2 instruction is cracked through the v2 microcode engine; this
    // is the compute kernel selected by u1.casForm (CasForm). Operands:
    //   a   = s1Src1            (the loaded memory value T0/T1)
    //   bRaw= s1RdB             (the compare/update register Dc/Du — RAW rdB, bypassing
    //                            the useImm mux so CAS2DC can carry the D/A bit in imm)
    //   c   = s1RdC             (Du for CASS / the packed eq/bothEq status temp for CAS2)
    //   oldNzvc = s1Nzvc        (preserve res1 in CAS2C2's !eq1 case)
    //   daBit  = s1Src2(0)      (= imm[0] when useImm: the CAS2.W Rn D/A bit, BIT_1F/BIT_F)
    // `eq` is the size-masked match (loaded == Dc); the CMP NZVC (res = loaded - Dc) reuses
    // the AluDatapath SUB/CMP cone via a dedicated instance below.
    val casIsOp   = u1.op === DecOp.CASOP
    val casA      = s1Src1
    val casBraw   = s1RdB
    val casC      = s1RdC
    val casDaBit  = s1Src2(0)
    // size mask + size-masked equality (the "match" predicate).
    def maskOf(sz: Size.C): Bits = sz.mux(
      Size.BYTE -> B(0x000000ffL, 32 bits), Size.WORD -> B(0x0000ffffL, 32 bits),
      Size.LONG -> B(0xffffffffL, 32 bits))
    val casMask   = maskOf(u1.size)
    val casEqAB   = (casA & casMask) === (casBraw & casMask)
    // CMP NZVC = res = loaded(casA) - Dc(casBraw), size-correct. A dedicated AluDatapath
    // (op=CMP) reuses the exact subtract/flag cone (matches Musashi's NFLAG/VFLAG_SUB/...).
    val casCmpCmd = AluCmd()
    casCmpCmd.op := DecOp.CMP; casCmpCmd.size := u1.size
    casCmpCmd.src1 := casA; casCmpCmd.src2 := casBraw
    casCmpCmd.xIn := False; casCmpCmd.extByte := False; casCmpCmd.bitOp := 0
    val casCmpRsp = AluDatapath(casCmpCmd)         // nzvc = cmp(loaded, Dc)
    // merge.sz(Dc, loaded): .L = loaded full; .B/.W = Dc upper bits + loaded low byte/word.
    val casMerge  = u1.size.mux(
      Size.BYTE -> (casBraw(31 downto 8)  ## casA(7 downto 0)),
      Size.WORD -> (casBraw(31 downto 16) ## casA(15 downto 0)),
      Size.LONG -> casA)
    // CAS2 .W Dc-update (Musashi BIT_1F/BIT_F rule): da ? sext16(loaded) : merge16(Dc,loaded).
    // .L is the plain full assignment (= loaded). The size-mux picks .W vs .L; CAS2 is
    // never .B, so the BYTE arm is a don't-care (defaults to the .W form).
    val casSext16 = casA(15 downto 0).asSInt.resize(32).asBits
    val cas2DcW   = Mux(casDaBit, casSext16, casBraw(31 downto 16) ## casA(15 downto 0))
    val cas2DcUpd = Mux(u1.size === Size.LONG, casA, cas2DcW)
    // Status-temp bits (CAS2): eq1 rides bit0, eq2 bit1, bothEq bit2.
    val casEq1In  = casC(0)                         // CAS2C2 reads eq1 from the C1 status temp
    val casBothEq = casC(2)                         // CAS2DC/SEL read bothEq from the C2 status
    // Per-form result + NZVC.
    import m68k040.decode.CasForm
    val casResult = u1.casForm.mux(
      B(CasForm.CASS,    3 bits) -> Mux(casEqAB, casC, casA),          // store-data = eq?Du:T0
      B(CasForm.CASC,    3 bits) -> Mux(casEqAB, casBraw, casMerge),   // Dc = eq?Dc:merge
      B(CasForm.CAS2C1,  3 bits) -> (B(0, 31 bits) ## casEqAB),        // status T2 = {eq1}
      B(CasForm.CAS2C2,  3 bits) -> (B(0, 29 bits) ## (casEq1In && casEqAB) ## casEqAB ## casEq1In), // {bothEq,eq2,eq1}
      B(CasForm.CAS2DC,  3 bits) -> Mux(casBothEq, casBraw, cas2DcUpd),// Dc = bothEq?Dc:update
      B(CasForm.CAS2SEL, 3 bits) -> Mux(casBothEq, casBraw, casA),     // store-data = bothEq?Du:T0
      default                    -> casA)
    // NZVC: CASC + CAS2C1 = cmp(loaded,Dc); CAS2C2 = eq1 ? cmp(T1,Dc2) : oldNZVC (preserve
    // res1). The store-data / Dc-update / status forms write no flags (writesNzvc=False).
    val casNzvc = u1.casForm.mux(
      B(CasForm.CAS2C2, 3 bits) -> Mux(casEq1In, casCmpRsp.nzvc, s1Nzvc(3 downto 0)),
      default                   -> casCmpRsp.nzvc)

    // ---- S1: size-merge of the FAST int writeback (68k partial-register semantics) ----
    // A .B / .W op updates ONLY the low byte / word of the destination register; the
    // upper bits are PRESERVED. For ADD/SUB/AND/OR/EOR the destination operand is srcA
    // (src1), so the old register value is s1Src1 -> merge its upper bits with the
    // datapath's low `size` result. MOVE.B/.W to a DATA register also reaches this
    // merge: the decoder makes such a MOVE READ its destination Dn as srcA (the merge
    // source), so s1Src1 holds the old Dn and the upper bytes are preserved. MOVE.L
    // (and any .L op) takes the full result. The fast path no longer muxes the shifter
    // result (a SHIFT is slow), so opResult is the ALU datapath result directly.
    // BCD overrides the datapath result low byte (BYTE size -> the merge preserves Dx[31:8]).
    // PACK overrides result byte (BYTE size -> .B merge preserves Dx[31:8]).
    // UNPK overrides result low word (WORD size -> .W merge preserves Dx[31:16]).
    // For PACK: opResult must present the result in low 8 bits (upper 24 don't-care for .B merge).
    // For UNPK: opResult must present the result in low 16 bits (upper 16 don't-care for .W merge).
    val opResult = Mux(isBfResolve, bfResolveRes,
                   Mux(isPack, packRes8,
                   Mux(isUnpk, unpkRes32,
                   Mux(isBcd,  B(0, 24 bits) ## bcdRes8, rsp.result))))
    val sizeMerged = u1.size.mux(
      Size.BYTE -> (s1Src1(31 downto 8)  ## opResult(7 downto 0)),
      Size.WORD -> (s1Src1(31 downto 16) ## opResult(15 downto 0)),
      Size.LONG -> opResult)
    // MOVEA (MOVE to An): An is ALWAYS written full-32 — NO partial merge. The source
    // (src2) is SIGN-EXTENDED to 32 from the op size. Normal MOVEA reaches only .W/.L
    // (byte MOVEA is illegal in the ISA); the MOVES read-to-An form (which reuses isMovea)
    // ALSO reaches .B -> add the BYTE sign-extend (Musashi MAKE_INT_8). .L writes whole.
    val moveaResult = u1.size.mux(
      Size.BYTE -> s1Src2(7 downto 0).asSInt.resize(32).asBits,
      Size.WORD -> s1Src2(15 downto 0).asSInt.resize(32).asBits,
      Size.LONG -> s1Src2)
    // ── MOVE from CCR / from SR int result (fromCcr / fromSr) ───────────────────
    // fromCcr: zero-extended CCR byte {0..0, X,N,Z,V,C} (CCR layout X=4,N=3,Z=2,V=1,C=0).
    // fromSr : zero-extended 16-bit SR = {srSys(8), 0,0,0, X,N,Z,V,C}. The op is .W so the
    // WORD size-merge writes the low 16 (the SR/CCR word) into a Dn (preserving Dn[31:16])
    // or stores the low 16. s1SrSys is the committed system byte registered to S1.
    val s1SrSys   = RegNext(srSysIn)
    val ccrByte5  = (s1X ## s1Nzvc(3 downto 0))                    // {X,N,Z,V,C}
    val fromCcrRes = (U(0, 27 bits) ## ccrByte5).asBits            // 32-bit, low 5 = CCR
    val fromSrRes  = (U(0, 16 bits) ## s1SrSys ## U(0, 3 bits) ## ccrByte5).asBits  // low 16 = SR
    // The MOVE source for the size-merge: fromSr/fromCcr override src2 with the SR/CCR
    // value (so the existing WORD size-merge applies uniformly).
    val srcForMove = Mux(u1.fromSr, fromSrRes, Mux(u1.fromCcr, fromCcrRes, s1Src2))
    val sizeMergedMove = u1.size.mux(
      Size.BYTE -> (s1Src1(31 downto 8)  ## srcForMove(7 downto 0)),
      Size.WORD -> (s1Src1(31 downto 16) ## srcForMove(15 downto 0)),
      Size.LONG -> srcForMove)
    val isFromCcrSr = u1.fromCcr || u1.fromSr
    // CASOP writes the FULL computed value (the .B/.W partial-register merge is folded
    // INSIDE the CASOP datapath, since the merge depends on the match predicate) -> bypass
    // the generic size-merge (like MOVEA / fromCcr-Sr).
    // anWide (ADDA/SUBA): the full-32 datapath result, NO merge (the WORD size field
    // would otherwise merge the old An's upper 16 over the carry-propagated result).
    val mergedResult = Mux(casIsOp, casResult,
                       Mux(anWide, opResult,
                       Mux(u1.isMovea, moveaResult, Mux(isFromCcrSr, sizeMergedMove, sizeMerged))))

    // ---- S1: ANDI/ORI/EORI #imm,CCR (toCcr) — CCR read-modify-write (FAST path) ----
    // Assemble the current 5-bit CCR {X,N,Z,V,C} from the flag PRFs, apply the logical
    // op against imm[4:0] (s1Src2, the imm byte), split the result back: NZVC =
    // ccr5'[3:0], X = ccr5'[4]. CCR layout X=4,N=3,Z=2,V=1,C=0 lines the flag halves up
    // directly (no reshuffling). This is a shallow 5-bit fold (NOT the deep cone), so
    // it stays latency-1 — toCcr's NZVC+X writes remain statically tracked (sbNzvc/sbX).
    val ccrOld = s1X ## s1Nzvc(3 downto 0)         // {X,N,Z,V,C}
    val ccrImm = s1Src2(4 downto 0)
    val ccrNew = u1.op.mux(
      DecOp.AND  -> (ccrOld & ccrImm),
      DecOp.OR   -> (ccrOld | ccrImm),
      DecOp.MOVE -> ccrImm,                         // MOVE-to-CCR: CCR := src[4:0] (direct)
      default    -> (ccrOld ^ ccrImm))              // EOR (toCcr AND/OR/EOR/MOVE reach here)
    val ccrNzvc = ccrNew(3 downto 0)
    val ccrX    = ccrNew(4)

    // Fast final flags: CASOP cmp/preserve flags for CAS/CAS2, CCR-rmw for toCcr, BCD
    // decimal carry/quirky-N/V for BCD, else the ALU datapath (NO shifter). X = the BCD
    // decimal carry/borrow for a BCD op (CAS/CAS2 never write X).
    val finalNzvc = Mux(casIsOp, casNzvc, Mux(u1.toCcr, ccrNzvc, Mux(isBcd, bcdNzvc, aluNzvc)))
    val finalX    = Mux(u1.toCcr, ccrX, Mux(isBcd, bcdCarry, rsp.xOut))

    // ---- S1: FAST writeback (gated by masks; suppressed for a slow op) ----
    val fastFire = s1Valid && !isSlow
    intW.valid   := fastFire && u1.pdstValid;  intW.address   := u1.pdst;     intW.data   := mergedResult
    nzvcW.valid  := fastFire && u1.writesNzvc; nzvcW.address  := u1.pNzvcDst;  nzvcW.data  := finalNzvc
    xW.valid     := fastFire && u1.writesX;    xW.address     := u1.pXDst;     xW.data     := B(finalX)
    fastFire.simPublic(); mergedResult.simPublic(); finalNzvc.simPublic(); s1Ctx.robId.simPublic()  // debug-only (task #144)

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

    // FMax #3: SPLIT stage 1 across an extra register. The single-cycle stage1 cone
    // `s1Src2(count) -> rmod -> variable barrel shift -> s2Stage1` was the post-fmax#1/#2
    // limiter (s1Src2 -> s2Stage1_roxlRes, 19 levels, WNS -0.805). stage1a computes the
    // (cheap) count-derived SHIFT AMOUNTS + masked source; we REGISTER that midpoint
    // (s1aStage1a) and stage1b performs the (wide) variable barrel shifts off the
    // registered amounts. The deep funnel cone is now halved across the S1->S1a boundary.
    // After FMax#4 (the bit-field S1b stage) the shift is the lat-5 (S1,S1a,S1b,S2,S3)
    // SLOW path (it passes through S1b unmodified, lat-matched to the now-deeper bit-field
    // pipe); the dynamic slowWakeup (broadcast at S3) makes the extra cycles latency-
    // agnostic (no static scoreboard constant — the IQ waits on the wakeup). RegNext
    // (chained s*Valid) gates issue.
    //
    // S1: stage1a (amounts) -> register into S1a.
    val s1Stage1a = Shifter.stage1a(shiftCmd)
    s1aValid     := RegNext(s1Valid && isSlow) init False
    val s1aStage1a = RegNext(s1Stage1a)
    val s1aCtx     = RegNext(s1Ctx)
    val s1aSrc1    = RegNext(s1Src1)

    // ── S1: bit-field datapath (DecOp.BITFIELD) ────────────────────────────────
    // Dy = s1Src1 (field reg, srcA=op[2:0]); Dn2 = s1RdB (insert source for BFINS,
    // srcB); offset/width = the packed imm (imm[4:0]=offset, imm[9:5]=raw width). The
    // datapath is computed combinationally here (parallel to the shifter, NOT on the
    // fast S1 writeback cone) and its finished result+flags are pipelined to S3 so the
    // slow writeback/wakeup mechanism is shared with SHIFT (lat-matched).
    // STATIC: offset/raw-width from the imm (s1Src2[9:0]). DYNAMIC (bfDynamic): from the
    // BFRESOLVE-packed T0 read via srcC (s1RdC[9:0]) — SAME packed layout (offset[4:0],
    // raw width[9:5]). The EU normalizes width = ((rawWidth-1)&31)+1 (5-bit field; the
    // register-value low 5 bits are equivalent to Musashi's ((width-1)&31)+1 on the full
    // value). Dy/Dn2 (BFINS) are unchanged.
    val bfPacked = Mux(u1.bfDynamic, s1RdC, s1Src2)
    // ── MEMORY bit-field load-only form (bfMem): the field comes from a (possibly
    // misaligned) memory LONG `lo` (= srcA = T0) plus an optional spill BYTE `hi`
    // (= srcB = T1, valid only when bitOff+width>32). The funnel left-justifies the
    // `width`-bit field into bits[31:32-width]:
    //   field32 = (lo << bitOff) | (needHi ? (hi[7:0] >> (8-bitOff)) : 0)
    // then feeds the SAME datapath with dy=field32, rotate offset=0, rawWidth=width,
    // and ffoBase = the ORIGINAL memory bit offset (for BFFFO). All of bitOff/width/
    // needHi/origOffset are STATIC, packed into imm by the assembler:
    //   imm[4:0]=0 (rotate offset), imm[9:5]=rawWidth, imm[12:10]=bitOff,
    //   imm[13]=needHi, imm[18:14]=origOffset.
    val bfMem      = u1.bfMem
    // DYNAMIC mem (slice 3c): the packed {origOff/needHi/bitOff/rawWidth} rides srcC (the
    // BFRESOLVE-produced temp), not the static imm. (bfPacked already muxes rawWidth.)
    val bfMemImm   = Mux(bfMem && u1.bfDynamic, s1RdC, s1Src2)
    // LO5RAW/HI5RAW (bfStoreForm 4/5, slice 3c Do=1 RMW): the inverse funnel reads bitOff
    // from srcC = the raw offset reg Dn[off] (bitOff = Dn[off]&7) so the resolved `packed`
    // temp is NOT held across the store reconstruction — keeps the live set within T0/T1/T2
    // (no archDepth bump). needHi/origOff are unused by the LO5/HI5 inverse funnel.
    val bfRawOffForm = (u1.bfStoreForm === U(4, 3 bits)) || (u1.bfStoreForm === U(5, 3 bits))
    // FFOFULL (bfStoreForm=6, slice 3c dynamic-mem BFFFO): the field is ALREADY left-aligned
    // (prefunnelled into srcA) so bitOff=0 / needHi=0; ffoBase = srcB = the FULL signed Dn[off]
    // (Musashi result = original_offset + first-set-index). A bfMem op (NOT register-form) so it
    // rides the proven bfMem slow path.
    val bfFfoFull   = u1.bfStoreForm === U(6, 3 bits)
    val bfMemBitOff = Mux(bfRawOffForm, s1RdC(2 downto 0).asUInt,
                         Mux(bfFfoFull, U(0, 3 bits), bfMemImm(12 downto 10).asUInt))  // 0..7
    val bfMemNeedHi = Mux(bfFfoFull, False, bfMemImm(13))
    val bfMemOrigOff= bfMemImm(18 downto 14).asUInt          // 0..31
    val bfLo       = s1Src1                                   // T0 (the misaligned long)
    val bfHi       = s1RdB                                    // T1 (the spill byte, low 8 bits)
    val bfFieldLo  = (bfLo.asUInt << bfMemBitOff)(31 downto 0)
    // hi >> (8 - bitOff): keep the top `bitOff` bits of the byte. (8-bitOff) in 1..8.
    val bfHiShAmt  = (U(8, 4 bits) - bfMemBitOff.resize(4))   // 1..8
    val bfFieldHi  = Mux(bfMemNeedHi, (bfHi(7 downto 0).asUInt.resize(32) >> bfHiShAmt)(31 downto 0), U(0, 32 bits))
    val bfField32  = (bfFieldLo | bfFieldHi).asBits
    // FMax #4 — SPLIT the forward-funnel -> modify cone. The post-route critical path was
    // the bit-field forward funnel (bfField32 = (lo<<bitOff)|(hi>>(8-bitOff))) feeding the
    // Bitfield datapath modify (chg/clr/set/ins) ALL in one S1 cone (16 LUT levels, WNS
    // -1.142 @ 250). We now REGISTER the Bitfield inputs (the funnelled `dy`, dn2, offset,
    // rawWidth, op, ffoBase) at the end of S1 and compute the modify in the NEXT stage
    // (S1a), then the inverse funnel in S1b. This adds ONE cycle of latency to the slow
    // pipe (now S1/S1a/S1b/S2/S3); the dynamic slowWakeup (broadcast at S3) makes the
    // extra cycle latency-agnostic (the IQ waits on the wakeup — no static scoreboard
    // constant). Register-form (bfMem=False) ops ride the same added stage (uniform latency,
    // byte-correct) since dy=s1Src1 is registered into the same s1aBfCmd.
    val bfCmd = BitfieldCmd()
    bfCmd.dy       := Mux(bfMem, bfField32, s1Src1)
    // BFINS insert source Dn2: the register form / 3a load-only path carries it on srcB
    // (s1RdB). The mem-RMW chains route Dn2 via srcC (s1RdC) because srcB carries the spill
    // byte `hi` (5-byte RES form) — keyed on a valid srcC for a bfMem compute.
    bfCmd.dn2      := Mux(bfMem && u1.psrcCValid, s1RdC, s1RdB)
    bfCmd.offset   := Mux(bfMem, U(0, 5 bits), bfPacked(4 downto 0).asUInt)
    bfCmd.rawWidth := bfPacked(9 downto 5).asUInt
    bfCmd.bfOp     := u1.bfOp
    // FFO additive base (32-bit): static-mem -> origOff (the memory bit offset); register form
    // -> the field offset (offset&31). DYNAMIC mem BFFFO (slice 3c, prefunnelled register-form:
    // bfMem=False, bfOp=5, with a valid srcB carrying Dn[off]) -> the FULL signed 32-bit offset
    // (Musashi result = original_offset + first-set-index). Register-form BFFFO (Dn) has srcB
    // invalid -> falls to offset&31.
    bfCmd.ffoBase  := Mux(bfFfoFull, s1RdB.asUInt,
                         Mux(bfMem, bfMemOrigOff.resize(32),
                             bfPacked(4 downto 0).asUInt.resize(32)))
    val bfStoreForm = u1.bfStoreForm
    // ── S1 -> S1a CUT: register the Bitfield inputs (post forward-funnel) + the inverse-
    // funnel raw inputs (bitOff, storeForm, the lo/hi merge source s1Src1, the LO5/HI5 res
    // source s1RdB=T2). The Bitfield datapath modify now runs in S1a off these registers. ──
    val s1aBfCmd     = RegNext(bfCmd)
    val s1aBfStoreF1 = RegNext(bfStoreForm)
    val s1aBfBitOff1 = RegNext(bfMemBitOff)
    val s1aBfInvSrc1 = RegNext(s1Src1)        // lo/hi merge source (srcA = T0/T1)
    val s1aBfT2      = RegNext(s1RdB)          // LO5/HI5 res source (srcB = T2)

    // S1a: the Bitfield datapath rotate/shift/left-justify cone (task #123, Fable audit
    // 2026-07-12 finding F1). This is the FIRST half of the (now further-split) modify
    // cone — see Bitfield.stage1/stage2's doc comment. The clz + mask-modify + result
    // mux (the deep half, 20 LUT levels combined with the rotates in the ORIGINAL
    // single-cycle `Bitfield.apply`) now runs a cycle later in S1a2, off a REGISTERED
    // BitfieldMid — mirroring the FMax#4 precedent that split the forward-funnel from
    // the modify (S1 -> S1a) below.
    val s1aBfMid = Bitfield.stage1(s1aBfCmd)
    // ── S1a -> S1a2 CUT: register the rotate/shift midpoint + the OTHER S1a-registered
    // inverse-funnel inputs (they must move in lockstep with bfMid, one more cycle). ──
    val s1a2BfMid    = RegNext(s1aBfMid)
    val s1a2BfStoreF1 = RegNext(s1aBfStoreF1)
    val s1a2BfBitOff1 = RegNext(s1aBfBitOff1)
    val s1a2BfInvSrc1 = RegNext(s1aBfInvSrc1)
    val s1a2BfT2      = RegNext(s1aBfT2)

    // S1a2: the clz + mask-modify + 8-way result/flag mux, off the REGISTERED midpoint.
    val bfRsp = Bitfield.stage2(s1a2BfMid)
    // ── MEMORY bit-field RMW INVERSE FUNNEL (store-back, slice 3b) ───────────────
    // The RMW chains (BFCHG/BFCLR/BFSET/BFINS) route through the microcode engine; each
    // compute µop carries a bfStoreForm (DecodedUop): 0=RES, 1=LO4 (4-byte combined),
    // 2=LO5, 3=HI5. The RES/LO4 forms compute `res` via the funnel+modify (bfRsp.result
    // over the funnelled field32) + the NZ flags from the loaded field. The LO5/HI5 forms
    // read `res` directly from srcB (= T2, the b2 result) — NO funnel.
    //   lomask = (bitOff==0) ? 0 : (0xffffffff << (32-bitOff))
    //   lo' = (lo & lomask) | (res >> bitOff)                  [LO4/LO5 output]
    //   himask = 0xff >> bitOff
    //   hi' = (hi & himask) | ((res << (8-bitOff)) & 0xff)     [HI5 output]
    // The inverse funnel is PIPELINED into the S1b stage (off a registered `res`/`lo`/`hi`/
    // bitOff) so it does NOT stack in series with the modify in one cone; for LO5/HI5 each
    // is its OWN µop (one funnel-depth, naturally shallow).
    // `res` driving the inverse funnel: LO4 (form 1) recomputes it inline from the funnel;
    // LO5/HI5 (forms 2/3) read it from T2 (registered s1a2BfT2). The lo/hi source for the
    // merge = srcA (T0 for lo / T1 for hi) = registered s1a2BfInvSrc1.
    val bfInvResS1a2 = Mux(s1a2BfStoreF1 === U(1, 2 bits), bfRsp.result, s1a2BfT2)
    val bfInvSrcS1a2 = s1a2BfInvSrc1
    // ── S1a2 -> S1b CUT: register the inverse-funnel inputs + the modify result/flags. ──
    val s1bInvRes    = RegNext(bfInvResS1a2)
    val s1bInvSrc    = RegNext(bfInvSrcS1a2)
    val s1bBitOff    = RegNext(s1a2BfBitOff1)
    val s1bStoreForm = RegNext(s1a2BfStoreF1)
    val s1bBfFunnelRes = RegNext(bfRsp.result)
    val s1bBfN       = RegNext(bfRsp.n)
    val s1bBfZ       = RegNext(bfRsp.z)
    // S1b: the inverse-funnel masks/shifts (off the registered inputs).
    val s1bLomask = Mux(s1bBitOff === 0, B(0, 32 bits),
                        (B(0xffffffffL, 32 bits).asUInt << (U(32, 6 bits) - s1bBitOff.resize(6)))(31 downto 0).asBits)
    val s1bLoStore = ((s1bInvSrc.asUInt & s1bLomask.asUInt) | (s1bInvRes.asUInt >> s1bBitOff)).asBits
    val s1bHiShL   = (U(8, 4 bits) - s1bBitOff.resize(4))     // 8-bitOff, 1..8
    val s1bHimask  = (B(0xff, 8 bits).asUInt >> s1bBitOff)(7 downto 0).asBits
    val s1bHiStore = ((s1bInvSrc(7 downto 0).asUInt & s1bHimask.asUInt) |
                      (s1bInvRes.asUInt << s1bHiShL)(7 downto 0))(7 downto 0).asBits
    // S1b: select the bit-field result by store form. RES/load-only/LO4 take the modify
    // result (registered from S1a); LO5/HI5 take the inverse-funnel output computed THIS
    // stage. HI5 writes only the low byte (the store µop is BYTE-sized; upper bits d/c).
    val s1bBfRes  = s1bStoreForm.mux(
      U(1, 3 bits) -> s1bLoStore,                          // LO4
      U(2, 3 bits) -> s1bLoStore,                          // LO5
      U(3, 3 bits) -> s1bHiStore.resize(32),               // HI5 (low byte)
      U(4, 3 bits) -> s1bLoStore,                          // LO5RAW (3c Do=1: bitOff from Dn[off])
      U(5, 3 bits) -> s1bHiStore.resize(32),               // HI5RAW (low byte)
      default      -> s1bBfFunnelRes)                      // 0 = RES / load-only
    // ── S1a -> S1a2: pure pass-through for the shift/ctx/valid/src1 chain (no shifter
    // computation here) — keeps the shift pipe lat-matched with the bit-field pipe's
    // NEW S1a2 stage (task #123). ──
    s1a2Valid      := RegNext(s1aValid) init False
    val s1a2Stage1a = RegNext(s1aStage1a)
    val s1a2Ctx    = RegNext(s1aCtx)
    val s1a2Src1   = RegNext(s1aSrc1)
    // ── S1a2: stage1b (the deep variable shifts) off the registered amounts -> register
    // the ShiftStage1 midpoint into S1b (the FMax#3 cut). FMax#4 added the S1b stage, and
    // task #123 added this S1a2 stage, so the shift pipe stays lat-matched with the (now
    // two cycles longer) bit-field pipe; the shift midpoint and ctx/src1 pass THROUGH S1b
    // unmodified into S2. ──
    val s1Stage1 = Shifter.stage1b(s1a2Stage1a)
    s1bValid     := RegNext(s1a2Valid) init False
    val s1bStage1 = RegNext(s1Stage1)
    val s1bCtx    = RegNext(s1a2Ctx)
    val s1bSrc1   = RegNext(s1a2Src1)
    // ── S1b -> S2: register the shift midpoint (pass-through) + the finished bit-field
    // result/flags (computed in S1b above), so a single S2/S3 stage serves both. ──
    s2Valid      := RegNext(s1bValid) init False
    val s2Stage1 = RegNext(s1bStage1)
    val s2Ctx    = RegNext(s1bCtx)
    val s2Src1   = RegNext(s1bSrc1)        // merge source preserved to S3
    val s2BfRes  = RegNext(s1bBfRes)
    val s2BfN    = RegNext(s1bBfN)
    val s2BfZ    = RegNext(s1bBfZ)

    // SLOW path stage 2 (S2): bit-extract + mux on the registered midpoint. Register
    // the finished result/flags into S3 (arch latency-3).
    val s2Rsp    = Shifter.stage2(s2Stage1)
    s3Valid     := RegNext(s2Valid) init False
    val s3Ctx    = RegNext(s2Ctx)
    val s3Src1   = RegNext(s2Src1)
    val s3ShiftRes  = RegNext(s2Rsp.result)
    val s3ShiftNzvc = RegNext(s2Rsp.n ## s2Rsp.z ## s2Rsp.v ## s2Rsp.c)
    val s3ShiftX    = RegNext(s2Rsp.xOut)
    val s3BfRes     = RegNext(s2BfRes)
    val s3BfNzvc    = RegNext(s2BfN ## s2BfZ ## False ## False)   // {N,Z,V=0,C=0}
    val u3 = s3Ctx.uop
    val s3IsBitfield = u3.op === DecOp.BITFIELD

    // ── S3: SHIFT int writeback — the shift dst is Dr = src1, so the .B/.W upper-
    // preserve merge applies exactly as for a fast op (merge against s3Src1). ──
    val slowMerged = u3.size.mux(
      Size.BYTE -> (s3Src1(31 downto 8)  ## s3ShiftRes(7 downto 0)),
      Size.WORD -> (s3Src1(31 downto 16) ## s3ShiftRes(15 downto 0)),
      Size.LONG -> s3ShiftRes)
    // BITFIELD writes the FULL 32-bit result (no .B/.W merge); the shifter takes the
    // size-merge. The slow path completes ONE op per cycle (single-outstanding), so a
    // simple op-class mux is correct.
    val slowResult = Mux(s3IsBitfield, s3BfRes, slowMerged)
    val slowNzvc   = Mux(s3IsBitfield, s3BfNzvc, s3ShiftNzvc)
    val slowX      = s3ShiftX   // BITFIELD leaves X untouched (writesX=False)

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
    // Keep the macro-commit marker through (the mem-dest MOVE-from-CCR/SR op µop -> T1
    // sets keepCommit so the whitebox keeps it as the instruction's single oracle step;
    // its trailing store is an rmwStore drop). fromCcr/fromSr are fast-path only.
    wbObs.keepCommit := RegNext(Mux(s3Valid, u3.keepCommit, u1.keepCommit)) init False
    wbObs.simPublic()
  }
}
