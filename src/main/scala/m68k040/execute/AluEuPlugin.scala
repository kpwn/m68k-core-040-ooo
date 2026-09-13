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
  /** Synchronous squash. Accepted wrong-path work must not write back, complete, or
    * broadcast a dynamic wakeup after this pulse. */
  def flush: Bool
  def completion: Flow[UInt]   // robId
  /** Dynamic-completion wakeup (mirroring DivEu/LsEu): the four-stage SLOW path
    * broadcasts its int+NZVC+X dsts the cycle the result lands at S3. The IQ holds a
    * dependent of a slow producer until this fires. Fast (lat1) ops do NOT drive it. */
  def slowWakeup: Flow[AluSlowWakeup]
  /** True when a FAST uop selected by the IQ this cycle is guaranteed to be
    * accepted from the registered issue stage next cycle.  The IQ uses this
    * look-ahead to preserve its select-time static-wakeup contract. */
  def fastAcceptNext: Bool
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
  * SLOW path (SHIFT): the retimed barrel-shifter datapath runs through
  * S1/S1a/S2/S3 (lat-4), then writes back (sharing the fast path's physical write
  * ports — see `wbKey`) and broadcasts `slowWakeup` at S3.
  * BITFIELD used to share this pipe; it now lives on the CPLX cluster (DivEuPlugin's
  * bit-field lane).  It was duplicated here because this plugin is instantiated TWICE
  * (eu0/eu1), so each copy carried its own funnel + mask-gen + BFFFO priority encoder
  * for a rare 68020+ family — one shared copy on CPLX replaces both.
  * The slow path is fully occupied as a pipeline (II=1).  A one-cycle reservation
  * blocks only a FAST op that would collide with a slow S3 completion on the shared
  * writeback/completion ports. */
class AluEuPlugin extends FiberPlugin with AluEuService {
  var issuePort: Stream[IqContext] = null
  var flushPort: Bool = null
  var completionPort: Flow[UInt]   = null
  var slowWakeupPort: Flow[AluSlowWakeup] = null
  var fastAcceptNextPort: Bool = null
  // PRF ports (allocated in setup)
  var rdA, rdB, rdC: RegFileReadPort = null
  var intW: RegFileWritePort = null;  var intByp: RegFileBypassPort = null
  var nzvcW: RegFileWritePort = null; var nzvcByp: RegFileBypassPort = null
  var xW: RegFileWritePort = null;    var xByp: RegFileBypassPort = null
  // SLOW-path write + bypass ports driven from the final S3 stage.
  // The fast (S1) and slow (S3) writebacks SHARE one PHYSICAL write port per regfile
  // (see `wbKey` below) because they are structurally mutually exclusive; the bypass
  // ports stay separate (bypasses are pure read-side muxes, they cost no RAM bank).
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
  override def flush: Bool = flushPort
  override def completion: Flow[UInt]   = completionPort
  override def slowWakeup: Flow[AluSlowWakeup] = slowWakeupPort
  override def fastAcceptNext: Bool = fastAcceptNextPort

  // ── PRF write-port SHARING KEY (one per AluEuPlugin INSTANCE) ──────────────────
  // Requests carrying the same key are merged by RegFilePlugin into ONE physical
  // write port (highest-priority valid request wins, combinationally, at the write-
  // port boundary). This EU's FAST (S1) and SLOW (S3) writebacks share a port because
  // they are STRUCTURALLY mutually exclusive; proof under the S3 reservation:
  //
  //   fastFire(T) => issuePort.fire(T-1) && !isSlowIn(T-1)
  //   issue.valid(T-1) && !isSlowIn(T-1) && issue.ready(T-1) => !s2Valid(T-1)
  //   !s2Valid(T-1) => !s3Valid(T)                    [pure RegNext chain]
  //
  // Slow uops always accept and advance one stage per cycle.  Only a presented FAST
  // uop is blocked when the current S2 slow uop will write S3 on its S1 completion
  // cycle.  The design depends on this invariant for the shared `completionPort` and
  // the shared wbObs/ccrObs `Mux(s3Valid, slow, fast)` selects.
  // Belt-and-suspenders: `priority` makes FAST win a (structurally impossible) tie.
  //
  // The key MUST be per-INSTANCE (`new Object`, not a shared string constant): eu0's
  // and eu1's fast paths DO fire in the same cycle routinely, so merging ACROSS the
  // two ALU EUs would be a silent-corruption bug.
  private val wbKey = new Object

  during setup {
    issuePort      = Stream(IqContext())
    flushPort      = Bool()
    completionPort = Flow(UInt(6 bits))
    slowWakeupPort = Flow(AluSlowWakeup())
    fastAcceptNextPort = Bool()
    val irf = host[IntRegFileService]
    rdA = irf.newRead(); rdB = irf.newRead()
    // 3rd int read port: CASOP's `casC` (Du for CAS, or the packed eq/bothEq status temp
    // for CAS2) reads psrcC.  DO NOT DELETE THIS PORT when auditing bit-field removal —
    // an older comment here claimed the BITFIELD-dynamic µop was the ONLY psrcC user on
    // this EU, and that was already stale when CAS/CAS2 landed.  BITFIELD has since moved
    // to the CPLX cluster; CASOP has not, and it still needs three int sources.
    rdC = irf.newRead()
    intW = irf.newWrite(latency = 1, sharingKey = wbKey, priority = 1); intByp = irf.newBypass()
    intWs = irf.newWrite(latency = 1, sharingKey = wbKey, priority = 0); intByps = irf.newBypass()
    val nz = host[NzvcRegFileService]
    nzvcW = nz.newWrite(latency = 1, sharingKey = wbKey, priority = 1); nzvcByp = nz.newBypass()
    nzvcWs = nz.newWrite(latency = 1, sharingKey = wbKey, priority = 0); nzvcByps = nz.newBypass()
    nzvcRd = nz.newRead(forceNoBypass = false)
    val xrf = host[XRegFileService]
    xW = xrf.newWrite(latency = 1, sharingKey = wbKey, priority = 1); xByp = xrf.newBypass()
    xWs = xrf.newWrite(latency = 1, sharingKey = wbKey, priority = 0); xByps = xrf.newBypass()
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
    rdC.addr := u0.psrcC                 // CASOP srcC = Du / the CAS2 status temp
    // srcAValid=False does NOT mean "reads zero" on its own, and the value it does read
    // is worse than undefined — it is a LIVE ARCHITECTURAL REGISTER. EaDecoder gives every
    // EA a nominal `base = 8 + reg` and merely clears `baseValid` for the modes that have
    // no base (EaDecoder.scala's defaults + the mode-7 arms), so an absolute (xxx).L EA
    // (mode 7, reg 1) still carries srcAReg = A1, which rename resolves to A1's current
    // value. Reading it is not a rare corruption — it is a deterministic read of whatever
    // the program last left in an unrelated register.
    //
    // Both other EUs that can see an invalid srcA already force the contribution to ZERO
    // for exactly this reason (BranchEuPlugin's `s1TgtBase`, "base contribution must be
    // ZERO"; LsEuPlugin's abs/PC-rel "the assembler folded the absolute/PC value into imm"
    // path). The ALU was the odd one out, and that inconsistency was a real ISA gap: the
    // bit-field DYNAMIC-offset (Do=1) byte-base recompute is `UBfAdd srcA=SEaBase,
    // srcB=T0(byteDelta)`, so at an absolute EA all five DO1 chains (RD/FFO/RMW/INS and the
    // RMW store recompute) would have added A1 to byteDelta and accessed a wrong address.
    // That shape had to be carved out to a vector-4 ILLEGAL trap to keep it from silently
    // reading and WRITING the wrong memory. With srcA gated to zero here the add yields
    // byteDelta alone and `eaDispLo` (the absolute address) applies on top at the load/store
    // rows, exactly as it already does for the Do=0 abs chain, so the carve-outs are gone —
    // see `ucBfDynRdEntry`/`ucBfRmwDynEntry` in DecodeStage.scala and the regression
    // bf_abs_dyn_offset.s, whose A1 poison is what makes its negative control bite.
    //
    // Audited at the same time: `UMove` and `UMiPtrLoad` are the only other micro-ops that
    // take srcA = SEaBase, and both are LS-cluster, where the gate already existed.
    val src1 = Mux(u0.psrcAValid, rdA.data, B(0, 32 bits))
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
    // s1RdC = CASOP's third source (Du for CASS, or the CAS2 packed eq/bothEq status temp).
    val s1RdC   = RegNext(rdC.data)
    val s1Nzvc  = RegNext(nzvcRd.data)         // {N(3),Z(2),V(1),C(0)} (toCcr)
    val s1X     = RegNext(xRd.data(0))         // X (toCcr)
    val u1 = s1Ctx.uop

    // ── slow-op classification: the barrel shifter (DecOp.SHIFT) — the 64-wide
    // funnel + shTable + the ROX rmod subtract chain — is the deep part of the
    // 24-level `s1Src2 -> NZVC` cone, so it uses the retimed S1-through-S3 path.
    // The CCR-RMW (toCcr) STAYS on the fast path: it is a shallow 5-bit logic fold
    // (NOT the deep cone), and keeping it fast avoids a flag-class dynamic wakeup —
    // toCcr writes only NZVC+X (flag physregs), which are tracked by the STATIC
    // latency-1 flag scoreboards (sbNzvc/sbX); a dynamic slow write would desync them.
    // (Re-confirmed by synth: moving the shifter alone clears the cone.) ──
    // DecOp.BITFIELD used to share this classification (it added two 32-bit barrel
    // rotates + a BFFFO CLZ — a comparable cone). It now runs on the CPLX cluster's own
    // bit-field lane, so SHIFT is the sole slow class here.
    val isShift = u1.op === DecOp.SHIFT
    val isSlow  = isShift

    // SLOW is a genuine FOUR-stage pipeline (S1/S1a/S2/S3).  A slow uop is always
    // accepted.  A FAST uop is blocked only when the slow uop currently in S2 will reach
    // S3 next cycle, exactly when the accepted fast uop would reach S1 and use the shared
    // writeback.  (s1aValid/s2Valid/s3Valid are the slow valid chain, forward-declared
    // here.)
    //
    // It was SIX stages (S1/S1a/S1a2/S1b/S2/S3) until bit-field moved to the CPLX cluster.
    // Two of those six were cuts the BIT-FIELD cone needed and SHIFT merely rode through
    // as pure pass-throughs, purely to stay lat-matched with the deeper bit-field pipe:
    // FMax#4's S1b (splitting the bit-field forward funnel from its modify) and task
    // #123's S1a2 (splitting the bit-field modify's clz+mux from its rotate/shift half).
    // With no bit-field left to match, they carried no logic at all and are deleted.
    // SHIFT keeps BOTH of its own cuts: FMax#3's split of stage1a (count-derived amounts)
    // from stage1b (the wide variable barrel shifts) is the S1->S1a boundary, and stage2
    // stays in S2.  So this is a latency reduction with NO change to any shifter cone.
    val s1aValid = Bool()
    val s2Valid  = Bool()
    val s3Valid  = Bool()
    s1Valid.simPublic(); s1aValid.simPublic(); s2Valid.simPublic(); s3Valid.simPublic()
    // SHIFT is the only slow class left on this pipe: BITFIELD moved to the CPLX
    // cluster, so `DecOp.isAluSlow` (= SHIFT || BITFIELD) would now mark a uop that
    // never reaches this EU.  Name the class that actually arrives.
    val isSlowIn = issuePort.payload.uop.op === DecOp.SHIFT
    // An empty upstream register must always see capacity: its retained payload is
    // stale and must not prevent the IQ from loading a safe slow candidate.
    issuePort.ready := !flushPort && (!issuePort.valid || isSlowIn || !s2Valid)
    // A selection at C reaches this issue port at C+1.  This must name the stage ONE
    // BEFORE S2, because that is the stage whose valid becomes s2Valid next cycle:
    // s2Valid(C+1) == s1aValid(C).  It said `!s1bValid` while the pipe was six stages
    // deep and S1b held that position; deleting S1b moved the position to S1a.
    //
    // GETTING THIS WRONG IS SILENT.  The IQ uses this as select-time look-ahead to
    // preserve its static-wakeup contract; if it reports "safe" a cycle early, a FAST S1
    // writeback collides with a SLOW S3 writeback on the `wbKey`-MERGED physical write
    // port.  The fast request carries priority=1, so the collision does not assert or
    // corrupt -- it silently DROPS THE SLOW RESULT.
    fastAcceptNextPort := !flushPort && !s1aValid

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
    // NBCD (task #159) reuses this SAME cone (bcdSub=True, the subtract formula) with
    // `dx` forced to the constant 0 (Musashi: res = 0 - dst - X): srcA still carries Dn
    // (for the .B-merge upper-24 preserve via s1Src1) and srcB also carries Dn (dy), but
    // the "dx" operand position in the formula is overridden to 0 regardless of s1Src1.
    val isNbcd = u1.op === DecOp.NBCD
    val isBcd = u1.op === DecOp.BCD || isNbcd
    val dx    = Mux(isNbcd, U(0, 8 bits), s1Src1(7 downto 0).asUInt)
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
    // ── PACK MEMORY-form (task #198): u1.extByte is repurposed as the mode marker (see
    // Microcode.scala resolve()'s doc comment) — True ONLY for the microcode-ROM PACK
    // compute row (PACK_MEM_ENTRY); the register form (a single-µop MicroOpAssembler fast
    // crack, never routed through that ROM) always leaves extByte at its ordinary False
    // default. When set, combine the two predec-loaded byte temps — srcA=T0 (the FIRST
    // read, Musashi's un-shifted `src=read8`) and s1RdB=T1 (the SECOND read, Musashi's
    // `src|=read8<<8`) — into the SAME 16-bit "src" the register form reads directly from
    // a single Dy, bit-identical to Musashi's own mem-form src assembly.
    val packMemForm = isPack && u1.extByte
    // PACK: src = (Dy + adj) & 0xffff (Dy from s1RdB, adj from s1Src2)
    val packDy      = Mux(packMemForm,
                          ((s1RdB(7 downto 0) ## s1Src1(7 downto 0)).asUInt).resize(32),
                          s1RdB.asUInt)                                      // Dy (32-bit)
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
    // ── ANDI/ORI/EORI #imm,SR (word form): PRIVILEGED commit-time SR read-modify- ──
    // write. Shares SysKind.MOVE_TO_SR's ExceptionUnit S_APPLY case (write srSys from
    // result[15:8] + surface result[4:0] as the new CCR) with the TRUE MOVE.W <ea>,SR
    // (a plain direct write, op=MOVE) — distinguished here by op being AND/OR/EOR
    // instead: combine the OLD SR (read from the committed srSysIn/CCR via the SAME
    // fromSrRes fold MOVE-from-SR uses, NOT a register — this µop's srcA/srcB carry
    // no real operand) with the immediate (s1Src2, threaded via the generic IMMEXT
    // srcB slot in MicroOpAssembler). result -> wbObs.result -> ROB sysValStore ->
    // the SAME S_APPLY MOVE_TO_SR write-through (see RobPlugin/ExceptionUnit).
    val isLogicSr  = u1.sysOp && (u1.sysKind === m68k040.decode.SysKind.MOVE_TO_SR) && (u1.op =/= DecOp.MOVE)
    val srOldVal   = fromSrRes(15 downto 0).asUInt
    val srImmVal   = s1Src2(15 downto 0).asUInt
    val srLogicRes = u1.op.mux(
      DecOp.AND -> (srOldVal & srImmVal),
      DecOp.OR  -> (srOldVal | srImmVal),
      default   -> (srOldVal ^ srImmVal))              // EOR (the only other reachable op)
    val srLogicResult = (B(0, 16 bits) ## srLogicRes.asBits)
    // CASOP writes the FULL computed value (the .B/.W partial-register merge is folded
    // INSIDE the CASOP datapath, since the merge depends on the match predicate) -> bypass
    // the generic size-merge (like MOVEA / fromCcr-Sr).
    // anWide (ADDA/SUBA): the full-32 datapath result, NO merge (the WORD size field
    // would otherwise merge the old An's upper 16 over the carry-propagated result).
    val mergedResult = Mux(isLogicSr, srLogicResult,
                       Mux(casIsOp, casResult,
                       Mux(anWide, opResult,
                       Mux(u1.isMovea, moveaResult, Mux(isFromCcrSr, sizeMergedMove, sizeMerged)))))

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
    val fastFire = s1Valid && !isSlow && !flushPort
    intW.valid   := fastFire && u1.pdstValid;  intW.address   := u1.pdst;     intW.data   := mergedResult
    nzvcW.valid  := fastFire && u1.writesNzvc; nzvcW.address  := u1.pNzvcDst;  nzvcW.data  := finalNzvc
    xW.valid     := fastFire && u1.writesX;    xW.address     := u1.pXDst;     xW.data     := B(finalX)
    fastFire.simPublic(); mergedResult.simPublic(); finalNzvc.simPublic(); s1Ctx.robId.simPublic()  // debug-only (task #144)

    // ---- S1: FAST bypass (mirror the writes; forwards to a dependent reading now) ----
    intByp.valid  := intW.valid;  intByp.address  := intW.address;  intByp.data  := intW.data
    nzvcByp.valid := nzvcW.valid; nzvcByp.address := nzvcW.address; nzvcByp.data := nzvcW.data
    xByp.valid    := xW.valid;    xByp.address    := xW.address;    xByp.data    := xW.data

    // ======================= SLOW PATH (S1 through S3) ============================
    // Pipeline the barrel shifter across the retimed slow stages: compute its first
    // (from the s1 operands, in parallel with the fast ALU but NOT on the fast S1
    // writeback cone — its outputs feed pipeline registers, not the fast write ports).
    // S2/S3 perform only the shallow finish/merge/writeback. This keeps the deep cone
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
    // The shift is the LAT-4 (S1, S1a, S2, S3) SLOW path, and every one of those four
    // stages now does real work: S1 = stage1a, S1a = stage1b, S2 = stage2, S3 =
    // merge/writeback. (It was lat-6 while the bit-field pipe shared the path; the two
    // extra stages were bit-field cuts SHIFT only rode through — see the pipeline comment
    // at the valid-chain declaration. An even older comment here called it "lat-5", which
    // was stale from the moment task #123 added the S1a2 stage.)
    // The dynamic slowWakeup (broadcast at S3) makes the stage count latency-agnostic
    // (no static scoreboard constant — the IQ waits on the wakeup). RegNext (chained
    // s*Valid) gates issue.
    //
    // S1: stage1a (the cheap count-derived amounts) -> register into S1a.
    val s1Stage1a = Shifter.stage1a(shiftCmd)
    s1aValid     := RegNext(s1Valid && isSlow && !flushPort) init False
    val s1aStage1a = RegNext(s1Stage1a)
    val s1aCtx     = RegNext(s1Ctx)
    val s1aSrc1    = RegNext(s1Src1)

    // ── S1a: stage1b (the wide variable barrel shifts) off the registered amounts ->
    // register the ShiftStage1 midpoint into S2. This is the FMax#3 cut, unchanged: the
    // deep funnel cone is still halved across the S1->S1a boundary. ──
    val s1Stage1 = Shifter.stage1b(s1aStage1a)
    s2Valid      := RegNext(s1aValid && !flushPort) init False
    val s2Stage1 = RegNext(s1Stage1)
    val s2Ctx    = RegNext(s1aCtx)
    val s2Src1   = RegNext(s1aSrc1)        // merge source preserved to S3

    // SLOW S2: bit-extract + mux on the registered midpoint. Register the finished
    // result/flags into final stage S3.
    val s2Rsp    = Shifter.stage2(s2Stage1)
    s3Valid     := RegNext(s2Valid && !flushPort) init False
    val s3Ctx    = RegNext(s2Ctx)
    val s3Src1   = RegNext(s2Src1)
    val s3ShiftRes  = RegNext(s2Rsp.result)
    val s3ShiftNzvc = RegNext(s2Rsp.n ## s2Rsp.z ## s2Rsp.v ## s2Rsp.c)
    val s3ShiftX    = RegNext(s2Rsp.xOut)
    val u3 = s3Ctx.uop
    val slowFire = s3Valid && !flushPort
    s2Valid.simPublic(); s3Valid.simPublic(); slowFire.simPublic()

    // ── S3: SHIFT int writeback — the shift dst is Dr = src1, so the .B/.W upper-
    // preserve merge applies exactly as for a fast op (merge against s3Src1). ──
    val slowMerged = u3.size.mux(
      Size.BYTE -> (s3Src1(31 downto 8)  ## s3ShiftRes(7 downto 0)),
      Size.WORD -> (s3Src1(31 downto 16) ## s3ShiftRes(15 downto 0)),
      Size.LONG -> s3ShiftRes)
    // SHIFT is now the only class on this pipe, so the former BITFIELD-vs-SHIFT result
    // and flag muxes are gone (BITFIELD wrote the full 32 bits with no size merge).
    val slowResult = slowMerged
    val slowNzvc   = s3ShiftNzvc
    val slowX      = s3ShiftX

    // ---- S3: SLOW writeback. These drive the SAME physical PRF write
    // ports as the fast S1 writeback above (merged by `wbKey`); the S3 reservation
    // makes `fastFire` and `s3Valid` mutually exclusive, and the fast requests carry
    // the higher merge priority as a defensive tie-break. ----
    intWs.valid   := slowFire && u3.pdstValid;  intWs.address  := u3.pdst;     intWs.data  := slowResult
    nzvcWs.valid  := slowFire && u3.writesNzvc; nzvcWs.address := u3.pNzvcDst;  nzvcWs.data := slowNzvc
    xWs.valid     := slowFire && u3.writesX;    xWs.address    := u3.pXDst;     xWs.data    := B(slowX)
    // ---- S3: SLOW bypass (forwards to a dependent reading the cycle S3 commits) ----
    intByps.valid  := intWs.valid;  intByps.address  := intWs.address;  intByps.data  := intWs.data
    nzvcByps.valid := nzvcWs.valid; nzvcByps.address := nzvcWs.address; nzvcByps.data := nzvcWs.data
    xByps.valid    := xWs.valid;    xByps.address    := xWs.address;    xByps.data    := xWs.data

    // ---- SLOW dynamic-completion wakeup (mirror LsEu/DivEu): broadcast the shift's
    // int + NZVC + X dsts. The IQ holds a dependent of the shift (int OR flag source)
    // until this fires (aluSlowWait). ----
    //
    // BROADCAST TWO STAGES EARLY, from S1a rather than S3 (2026-09-13, IPC).
    //
    // The rule this machine obeys is "wake the consumer exactly 2 cycles before the
    // producer's bypass cycle" -- which is why the FAST path's static `events` trigger
    // clears at SELECT time, not at writeback. The slow path was broadcasting at S3,
    // i.e. AT the bypass cycle, so a dependent became ready one cycle later and read one
    // cycle after that: TWO CYCLES LATE on every shift -> dependent edge.
    //
    // Broadcasting from S1a restores the 2-cycle lead: wake at S3-2 -> consumer ready at
    // S3-1 -> consumer selects at S3-1 -> consumer's S0 read lands exactly on S3, which
    // is the cycle `intByps`/`nzvcByps`/`xByps` forward the result. Not early, not late.
    //
    // SOUND because the slow pipe CANNOT STALL, so S1a is a reliable 2-cycle predictor
    // of S3: `issuePort.ready` (above) is unconditional for a slow uop, the valid chain
    // s1a/s2/s3 is plain `RegNext` with no back-pressure, and the S3 writeback
    // port is reserved ahead by `fastAcceptNextPort`. If any of those three ever stops
    // holding, this becomes a SILENT stale read -- there is no replay path -- so treat
    // them as the invariant this line depends on.
    //
    // FLUSH-SAFE because `doFlush` is retire-gated (commit-time redirect): a flush kills
    // the producer and any consumer woken early TOGETHER, since the consumer is strictly
    // younger. A mid-pipeline (non-commit) flush source would break that and must not be
    // added without revisiting this.
    //
    // ⚠️ THE ANCHOR STAGE IS TIED TO THE PIPE DEPTH, NOT TO A NAME.  This was S1b in
    // the six-stage pipe; deleting S1a2/S1b (bit-field's cuts) made S1a the S3-2 stage.
    // Any future change to the slow depth MUST move this line with it -- the rule is
    // "two stages before S3", and getting it wrong is a silent stale read, not an error.
    //
    // This captures the whole IPC benefit a fully STATIC scoreboard conversion would
    // deliver (the gain is anticipation, not staticness) at ~zero area and no change to
    // the issue queue. See docs/PLAN_fmax_area_ipc_campaign.md.
    val slowWakeEarly = s1aValid && !flushPort
    val uWake         = s1aCtx.uop
    slowWakeupPort.valid            := slowWakeEarly
    slowWakeupPort.payload.pdst     := uWake.pdst
    slowWakeupPort.payload.pdstValid:= uWake.pdstValid
    slowWakeupPort.payload.pNzvcDst := uWake.pNzvcDst
    slowWakeupPort.payload.nzvcValid:= uWake.writesNzvc
    slowWakeupPort.payload.pXDst    := uWake.pXDst
    slowWakeupPort.payload.xValid   := uWake.writesX

    // ---- completion: fast (S1) OR slow (S3) — single port, never both same cycle
    // (the S3 reservation guarantees S1-fast and S3-slow never coincide). ----
    completionPort.valid   := fastFire || slowFire
    completionPort.payload  := Mux(slowFire, s3Ctx.robId, s1Ctx.robId)

    // ---- sim-only whitebox writeback observation (NaxRiscv-style) ----
    // Per-instruction value+flags+masks keyed by robId; the lock-step harness joins
    // this with the ROB commit-obs to reconstruct the architectural CommitObservation
    // stream. Registered (sim-only) so the harness reading wbObs in onSamplings gets
    // stable one-cycle pulses. A FAST op publishes from S1 (one extra reg => same cycle
    // as its completion's downstream consumers expect); a SLOW op publishes from S3.
    // The two never fire in the same source cycle (S3 reservation), so a single
    // muxed record per cycle is correct.
    val obsValidS  = fastFire || slowFire
    val obsRobId   = Mux(slowFire, s3Ctx.robId, s1Ctx.robId)
    val obsDstArch = Mux(slowFire, u3.dstArch, u1.dstArch)
    val obsResult  = Mux(slowFire, slowResult, mergedResult)
    val obsIntW    = Mux(slowFire, u3.pdstValid, u1.pdstValid)
    val obsNzvc    = Mux(slowFire, slowNzvc, finalNzvc)
    val obsNzvcW   = Mux(slowFire, u3.writesNzvc, u1.writesNzvc)
    val obsX       = Mux(slowFire, slowX, finalX)
    val obsXW      = Mux(slowFire, u3.writesX, u1.writesX)
    val wbObs = WbObs()
    val wbObsValid = RegNext(obsValidS) init False
    wbObs.valid     := wbObsValid && !flushPort
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
    wbObs.divRem    := RegNext(Mux(slowFire, u3.divIsRem, u1.divIsRem)) init False
    // Keep the macro-commit marker through (the mem-dest MOVE-from-CCR/SR op µop -> T1
    // sets keepCommit so the whitebox keeps it as the instruction's single oracle step;
    // its trailing store is an rmwStore drop). fromCcr/fromSr are fast-path only.
    wbObs.keepCommit := RegNext(Mux(slowFire, u3.keepCommit, u1.keepCommit)) init False
    wbObs.simPublic()

    // ---- REAL-TIME completion (task #176 fix) ----
    // `wbObs` above is DELIBERATELY delayed one extra cycle past `completionPort`/the
    // real PRF nzvcW/xW writes, so the sim-only lock-step whitebox join lines up with
    // the ROB's matching-delayed `commitObs` (see RobPlugin.scala's commitObs, also a
    // RegNext of retire0/retire1 — a SEPARATE, sim-only reconstruction mechanism).
    // `ccrObs` is the SYNTHESIZABLE counterpart: it fires the SAME cycle as
    // `completionPort.valid` (S1 for a fast op via `fastFire`, S3 for a slow op via
    // `s3Valid`) — exactly the cycle the real nzvcW/xW/intW hardware writes land. This
    // is what must feed the ROB's `ccrCompletion` (-> nzvcWrStore/nzvcValStore ->
    // committedCcr), because `completes(robId)` (which gates retire0/retire1) is ALSO
    // driven from `completionPort` with no extra delay. Wiring `ccrCompletion` from the
    // delayed `wbObs` instead (the pre-#176 bug) let a fast flag-writer (e.g. CMP)
    // retire ONE CYCLE BEFORE its flags landed in nzvcWrStore/nzvcValStore, permanently
    // dropping the write (the ROB entry frees the same cycle it retires — no 2nd chance).
    val ccrObs = WbObs()
    ccrObs.valid      := obsValidS
    ccrObs.robId      := obsRobId
    ccrObs.dstArch    := obsDstArch
    ccrObs.result     := obsResult
    ccrObs.intWrite   := obsIntW
    ccrObs.nzvc       := obsNzvc
    ccrObs.nzvcWrite  := obsNzvcW
    ccrObs.x          := obsX
    ccrObs.xWrite     := obsXW
    ccrObs.divRem     := Mux(slowFire, u3.divIsRem, u1.divIsRem)
    ccrObs.keepCommit := Mux(slowFire, u3.keepCommit, u1.keepCommit)
    ccrObs.simPublic()
  }
}
