/*
 * Third-party notice: SoftFloat-derived portions of this file and its FPU
 * helpers are adaptations of John R. Hauser's SoftFloat Release 2b.
 * Local hardware adaptation: m68k-core-040-ooo contributors, 2026.
 * See THIRD_PARTY_NOTICES.md for scope; unrelated original code remains MIT.
 * Original notice for retained/adapted SoftFloat portions follows:
 *
 * This C source file is part of the SoftFloat IEC/IEEE Floating-point Arithmetic
 * Package, Release 2b.
 *
 * Written by John R. Hauser.  This work was made possible in part by the
 * International Computer Science Institute, located at Suite 600, 1947 Center
 * Street, Berkeley, California 94704.  Funding was partially provided by the
 * National Science Foundation under grant MIP-9311980.  The original version
 * of this code was written as part of a project to build a fixed-point vector
 * processor in collaboration with the University of California at Berkeley,
 * overseen by Profs. Nelson Morgan and John Wawrzynek.  More information
 * is available through the Web page `http://www.cs.berkeley.edu/~jhauser/
 * arithmetic/SoftFloat.html'.
 *
 * THIS SOFTWARE IS DISTRIBUTED AS IS, FOR FREE.  Although reasonable effort has
 * been made to avoid it, THIS SOFTWARE MAY CONTAIN FAULTS THAT WILL AT TIMES
 * RESULT IN INCORRECT BEHAVIOR.  USE OF THIS SOFTWARE IS RESTRICTED TO PERSONS
 * AND ORGANIZATIONS WHO CAN AND WILL TAKE FULL RESPONSIBILITY FOR ALL LOSSES,
 * COSTS, OR OTHER PROBLEMS THEY INCUR DUE TO THE SOFTWARE, AND WHO FURTHERMORE
 * EFFECTIVELY INDEMNIFY JOHN HAUSER AND THE INTERNATIONAL COMPUTER SCIENCE
 * INSTITUTE (possibly via similar legal warning) AGAINST ALL LOSSES, COSTS, OR
 * OTHER PROBLEMS INCURRED BY THEIR CUSTOMERS AND CLIENTS DUE TO THE SOFTWARE.
 *
 * Derivative works are acceptable, even for commercial purposes, so long as
 * (1) the source code for the derivative work includes prominent notice that
 * the work is derivative, and (2) the source code includes prominent notice with
 * these four paragraphs for those parts of this code that are retained.
 */

package m68k040.execute.fpu

import spinal.core._

object FpCheapPipe {
  val Latency = 10

  /** FMOVECR constant ROM, densely indexed (see `cromIndex`). Slots 22..31 are 0.0, which
    * is also what every undefined 7-bit offset maps to (Musashi's `default: source = 0`,
    * m68kfpu.c:1345-1347).
    *
    * PROVENANCE (design-spec Divergence Register D2/D3, VERIFY-1, resolved 2026-08-16):
    *  - $00,$0C,$0D,$0E,$0F,$30,$31,$32..$37 : Musashi's table and an independent exact
    *    correctly-rounded computation agree bit-for-bit.
    *  - $0B (log10(2)) : 0x...FBCFF798, one ULP BELOW correctly-rounded (...F799). Kept
    *    deliberately: QEMU's independent fpu_rom[] carries the same word, and rounding the
    *    exact value to 67 bits and then to 64 (the 68881 constant ROM's documented guard-bit
    *    double rounding) reproduces ...F798 exactly. log10(2) is the ONLY one of the seven
    *    transcendental constants where that intermediate rounding changes the answer, which
    *    is precisely why it is the lone anomaly. This is the genuine 68881 FMOVECR-under-RN
    *    result, not a Musashi bug.
    *  - $38..$3F (10^32..10^4096) : the correctly-rounded / real-ROM values. Musashi's are
    *    double-derived (double_to_fx80(1e32) and repeated squaring of a rounded 1e256) and
    *    differ in the low mantissa bits -- Divergence Register D2. Confirmed against both an
    *    exact rational recomputation and QEMU's fpu_rom[]. Do NOT "fix" them toward Musashi.
    */
  val cromWords: Seq[BigInt] = Seq(
    BigInt("4000C90FDAA22168C235", 16),   //  0  $00  pi
    BigInt("3FFD9A209A84FBCFF798", 16),   //  1  $0B  log10(2)
    BigInt("4000ADF85458A2BB4A9B", 16),   //  2  $0C  e
    BigInt("3FFFB8AA3B295C17F0BC", 16),   //  3  $0D  log2(e)
    BigInt("3FFDDE5BD8A937287195", 16),   //  4  $0E  log10(e)
    BigInt("00000000000000000000", 16),   //  5  $0F  0.0
    BigInt("3FFEB17217F7D1CF79AC", 16),   //  6  $30  ln(2)
    BigInt("4000935D8DDDAAA8AC17", 16),   //  7  $31  ln(10)
    BigInt("3FFF8000000000000000", 16),   //  8  $32  10^0 = 1.0
    BigInt("4002A000000000000000", 16),   //  9  $33  10^1
    BigInt("4005C800000000000000", 16),   // 10  $34  10^2
    BigInt("400C9C40000000000000", 16),   // 11  $35  10^4
    BigInt("4019BEBC200000000000", 16),   // 12  $36  10^8
    BigInt("40348E1BC9BF04000000", 16),   // 13  $37  10^16
    BigInt("40699DC5ADA82B70B59E", 16),   // 14  $38  10^32
    BigInt("40D3C2781F49FFCFA6D5", 16),   // 15  $39  10^64
    BigInt("41A893BA47C980E98CE0", 16),   // 16  $3A  10^128
    BigInt("4351AA7EEBFB9DF9DE8E", 16),   // 17  $3B  10^256
    BigInt("46A3E319A0AEA60E91C7", 16),   // 18  $3C  10^512
    BigInt("4D48C976758681750C17", 16),   // 19  $3D  10^1024
    BigInt("5A929E8B3B5DC53D5DE5", 16),   // 20  $3E  10^2048
    BigInt("7525C46052028A20979B", 16)    // 21  $3F  10^4096
  ) ++ Seq.fill(10)(BigInt(0))

  /** Scala-side (elaboration) mapping of a 7-bit FMOVECR offset to the dense ROM index.
    * Anything undefined maps to slot 22 (0.0). Shared with the spec so the test can index
    * `cromWords` the same way the hardware does. */
  def cromIndexOf(off: Int): Int =
    if (off == 0x00) 0
    else if (off >= 0x0B && off <= 0x0F) off - 0x0B + 1
    else if (off >= 0x30 && off <= 0x3F) off - 0x30 + 6
    else 22

  /** 7-bit FMOVECR offset -> dense 5-bit ROM index; anything undefined -> slot 22 (0.0). */
  def cromIndex(sel: Bits): UInt = {
    val s   = sel.asUInt
    val idx = UInt(5 bits)
    idx := 22
    when(s === 0x00)                     { idx := 0 }
    when(s >= 0x0B && s <= 0x0F)         { idx := (s - 0x0B + 1).resize(5) }
    when(s >= 0x30 && s <= 0x3F)         { idx := (s - 0x30 + 6).resize(5) }
    idx
  }
}

/** The cheap fixed pipe: FABS / FNEG / FMOVE / FMOVECR / FTST / FINT / FINTRZ. Real work
  * happens in C0..C2; C3..C9 are delay registers that hold FpCheapPipe.Latency equal to
  * FpAddPipe/FpMulPipe (see FpuCore's header for why).
  *
  * THREE DESIGN POINTS THAT MUST NOT BE "SIMPLIFIED" AWAY:
  *
  *  1. FTST is NOT FCMP-against-zero. It classifies its source directly (Musashi
  *     m68kfpu.c:1547-1554, opmode 0x3a, SET_CONDITION_CODES(source)). The concrete,
  *     testable consequence: FTST of +/-infinity sets FPSR.I, whereas FCMP's infinity table
  *     (m68kfpu.c:1525-1545) deliberately leaves I clear. A decode-time rewrite into
  *     `FCMP #0` would silently lose that bit. Here it falls out for free: FTST is
  *     bypass = True, bypassValue = src, writeFp = False, and FpRoundPack classifies
  *     whatever value it emits.
  *  2. FABS/FNEG are single-cycle logic (one XOR/AND on bit 79, computed in C2), padded to
  *     the common front depth by plain registers. See FpuCore's header for why one uniform
  *     fixed-result port beats a second early port.
  *  3. FINT/FINTRZ produce an EXTENDED-format integer-valued result, not a narrowing
  *     convert. FINT uses io.rmode; FINTRZ forces RZ regardless of FPCR. The algorithm is
  *     SoftFloat's floatx80_round_to_int (softfloat.c:3082-3145) -- NOT Musashi's
  *     m68kfpu.c int32-clamping handler (Divergence Register D1). */
class FpCheapPipe extends Component {
  val io = new Bundle {
    val start    = in Bool ()
    val op       = in(FpOp())
    val src      = in Bits (80 bits)
    val rmode    = in Bits (2 bits)
    val precision = in Bits (2 bits)     // effective FPCR.PREC / forced FS<op>,FD<op>
    val cromSel  = in Bits (7 bits)
    val outValid = out Bool ()
    val outReq   = out(FpRoundReq())
  }

  val rom = Mem(Bits(80 bits), FpCheapPipe.cromWords.map(v => B(v, 80 bits)))

  // -- C0: unpack, ROM read, FINT shift amount ---------------------------------
  val c0 = new Area {
    val romOut = rom.readSync(FpCheapPipe.cromIndex(io.cromSel))   // lands aligned with C0's regs
    val eRZ    = io.op === FpOp.FINTRZ
    val rmEff  = Mux(eRZ, B"01", io.rmode)

    val vld = RegNext(io.start) init False
    val op  = RegNext(io.op)
    val src = RegNext(io.src)
    val rm  = RegNext(rmEff)                 // FINT/FINTRZ's own effective mode
    val rmA = RegNext(io.rmode)              // architectural FPCR[5:4], latched at issue
    val prec = RegNext(io.precision)         // effective rounding precision, latched at issue
    val exp = RegNext(Fp80.exp(io.src))
    val sgn = RegNext(Fp80.sign(io.src))
    val sig = RegNext(Fp80.sig(io.src))
    val sn  = RegNext(Fp80.isSNan(io.src))
    val nan = RegNext(Fp80.isNan(io.src))
    // 0x403E - exp, meaningful only on the 0x3FFF <= exp < 0x403E arm (so 1..63)
    val shN = RegNext((U(0x403E, 16 bits) - Fp80.exp(io.src).resize(16)).resize(6))
  }

  // -- C1: FINT masks + the rounding add; every other op's value is already final ---
  val c1 = new Area {
    val isInt  = c0.op === FpOp.FINT || c0.op === FpOp.FINTRZ
    val big    = c0.exp >= 0x403E                     // already integral (or NaN/Inf)
    val small  = c0.exp <  0x3FFF                     // |x| < 1
    val lastM  = (U(1, 64 bits) |<< c0.shN).resize(64)
    val roundM = lastM - 1
    val rnAdd  = c0.sig + (lastM |>> 1)
    val awayAdd= c0.sig + roundM
    val rn     = c0.rm === 0; val rz = c0.rm === 1; val rmD = c0.rm === 2; val rp = c0.rm === 3
    // SoftFloat: sign ^ (mode == round_up) selects "add roundBitsMask"
    val away   = (c0.sgn ^ rp) && (rmD || rp)
    val t      = Mux(rn, rnAdd, Mux(away, awayAdd, c0.sig))

    val vld = RegNext(c0.vld) init False
    val op  = RegNext(c0.op);   val src = RegNext(c0.src); val sgn = RegNext(c0.sgn)
    val exp = RegNext(c0.exp);  val sig = RegNext(c0.sig); val sn  = RegNext(c0.sn)
    val nan = RegNext(c0.nan);  val rom = RegNext(c0.romOut); val rmA = RegNext(c0.rmA)
    val prec = RegNext(c0.prec)
    val rIsInt = RegNext(isInt); val rBig = RegNext(big); val rSmall = RegNext(small)
    val rT = RegNext(t); val rRoundM = RegNext(roundM); val rLastM = RegNext(lastM)
    val rRn = RegNext(rn); val rRz = RegNext(rz); val rRmD = RegNext(rmD); val rRp = RegNext(rp)
    // |x| in [0.5,1) with a nonzero tail -- SoftFloat's RN "return +/-1.0" corner
    val rHalfUp = RegNext(c0.exp === 0x3FFE && c0.src(62 downto 0) =/= 0)
    val rTrueZero = RegNext(c0.exp === 0 && c0.src(62 downto 0) === 0)
  }

  // -- C2: mask, carry fix-up, op mux, FpRoundReq ------------------------------
  val c2 = new Area {
    // FINT main arm (SoftFloat softfloat.c:3126-3143)
    val tieClr  = Mux(c1.rRn && (c1.rT & c1.rRoundM) === 0, c1.rT & ~c1.rLastM, c1.rT)
    val masked  = tieClr & ~c1.rRoundM
    val carry   = masked === 0
    val intMain = Fp80.pack(c1.sgn,
                            Mux(carry, c1.exp + 1, c1.exp),
                            Mux(carry, U(BigInt(1) << 63, 64 bits), masked))
    // FINT |x| < 1 arm (softfloat.c:3096-3122)
    val one     = Fp80.pack(c1.sgn, U(0x3FFF, 15 bits), U(BigInt(1) << 63, 64 bits))
    val zeroS   = Fp80.packZero(c1.sgn)
    val intSmall = Mux(c1.rTrueZero, c1.src,
                   Mux(c1.rRn,  Mux(c1.rHalfUp, one, zeroS),
                   Mux(c1.rRmD, Mux(c1.sgn, one, Fp80.packZero(False)),
                   Mux(c1.rRp,  Mux(c1.sgn, Fp80.packZero(True), one),
                                zeroS))))
    val intVal  = Mux(c1.rBig,   Mux(c1.nan, Fp80.propagateNan(c1.src, c1.src), c1.src),
                  Mux(c1.rSmall, intSmall, intMain))
    val intInex = !c1.rBig && Mux(c1.rSmall, !c1.rTrueZero, masked =/= c1.sig)

    // The final EXTENDED-precision answer for every op this pipe runs. At FPCR.PREC =
    // Extend that IS the result and `bypass` ships it untouched, exactly as before.
    val val80   = Bits(80 bits);  val80   := c1.src
    val wrFp    = Bool();         wrFp    := True
    val snanF   = Bool();         snanF   := c1.sn
    val inexF   = Bool();         inexF   := False
    // ── WHICH OPS STILL HAVE TO BE ROUNDED WHEN PREC != EXTEND ────────────────────
    // FABS/FNEG/FMOVE do. They are the three that have real 68040 forced-precision
    // opmodes (FSABS/FDABS $58/$5C, FSNEG/FDNEG $5A/$5E, FSMOVE/FDMOVE $40/$44), and the
    // M68000PRM says so for the FPCR case too: "FMOVE will round the result to the
    // precision selected in the floating-point control register"; FABS/FNEG carry the
    // identical sentence. Their `val80` is a sign-flip of an arbitrary 64-bit-significand
    // extended value, so it is NOT generally representable at 24 or 53 bits and it can sit
    // outside the single/double exponent range -- both of which FpRoundPack must see.
    //
    // FTST does NOT: it stores nothing, only classifies (see design point 1 above), so
    // there is no destination for PREC to apply to.
    //
    // FINT/FINTRZ/FMOVECR do NOT, and that is a DELIBERATE, RECORDED GAP rather than an
    // oversight. All three are FPSP-emulated on real MC68040 silicon (see this file's and
    // FpOp's headers: they are a documented superset of real hardware), so no 68040
    // program can observe our choice; and FMOVECR in particular cannot simply be routed
    // here, because the PRM specifies its exception byte as "OVFL Cleared / UNFL Cleared"
    // while still requiring the mantissa to be rounded to FPCR.PREC -- honouring both
    // needs a per-request range-control suppression bit that this slice does not add.
    val moveLike = Bool();        moveLike := False
    switch(c1.op) {
      is(FpOp.FABS)    { val80 := False ## c1.src(78 downto 0);      moveLike := True }
      is(FpOp.FNEG)    { val80 := !c1.src(79) ## c1.src(78 downto 0); moveLike := True }
      is(FpOp.FMOVE)   { val80 := c1.src;                            moveLike := True }
      is(FpOp.FMOVECR) { val80 := c1.rom; snanF := False }
      is(FpOp.FTST)    { val80 := c1.src; wrFp := False }
      is(FpOp.FINT)    { val80 := intVal; inexF := intInex }
      is(FpOp.FINTRZ)  { val80 := intVal; inexF := intInex }
    }

    // A NaN or an infinity is exact at every precision and must pass through untouched
    // (rounding one would corrupt the payload and could raise a bogus OVFL), so those
    // keep the bypass. Everything else -- normals, denormals and zeros -- is re-opened
    // into the rounding datapath with round = sticky = 0, which is EXACT: the 64-bit
    // significand is the whole value.
    val isNanInf = c1.exp === U(Fp80.ExpInf, 15 bits)
    val precExt  = (c1.prec =/= B(FpPrec.Sgl, 2 bits)) && (c1.prec =/= B(FpPrec.Dbl, 2 bits))
    val reRound  = moveLike && !precExt && !isNanInf
    val vExp     = val80(78 downto 64).asUInt
    val req = FpRoundReq().overridable()
    req.sign := val80(79)
    // An extended exponent FIELD of 0 denotes the effective exponent 1 (the denormal
    // encoding), which is the convention FpRoundReq.exp already uses -- so a denormal
    // source arrives at FpRoundPack as an un-normalised significand with exp 1, and the
    // single/double UNFL arm re-shifts it to that precision's own denormal boundary.
    req.exp  := Mux(vExp === 0, S(1, 18 bits), vExp.resize(18).asSInt)
    req.sig  := val80(63 downto 0).asUInt
    req.round := False; req.sticky := False
    req.bypass := !reRound
    req.rmode := c1.rmA
    req.prec  := c1.prec
    req.fpccFromSrc := False; req.fpccOverride := 0
    req.exc.clearExc(); req.exc.snan := snanF; req.exc.inex2 := inexF
    req.writeFp := wrFp
    req.bypassValue := val80

    val vld = RegNext(c1.vld) init False
    val rReq = RegNext(req)
  }

  // -- C3..C9: delay to the common front depth ---------------------------------
  val dlyV = Vec.fill(7)(RegInit(False))
  val dlyR = Vec.fill(7)(Reg(FpRoundReq()))
  dlyV(0) := c2.vld; dlyR(0) := c2.rReq
  for (i <- 1 until 7) { dlyV(i) := dlyV(i - 1); dlyR(i) := dlyR(i - 1) }

  io.outValid := dlyV(6)
  io.outReq   := dlyR(6)
}
