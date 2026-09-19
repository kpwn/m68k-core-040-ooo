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

object FpMulPipe { val Latency = 10 }

/** FMUL: subnormal pre-normalise, exponent add, DSP48E2-inferred 64x64 -> 128 significand
  * multiply, 1-bit post-normalise, hand-off to FpRoundPack. Elastic, II=1, never stalls.
  *
  * Unlike FADD, SoftFloat's floatx80_mul DOES pre-normalise subnormal operands
  * (softfloat.c:3363-3370, normalizeFloatx80Subnormal) before forming zExp = aExp + bExp -
  * 0x3FFE, so this pipe carries a real CLZ + left shift per operand in M0/M1.
  *
  * THE DSP INFERENCE IDIOM, adapted from MulCore.scala:53-74 (this project's already-
  * silicon-validated pattern), with MulCore.scala:49-52's reason quoted: "Two operand stages
  * match AREG/BREG depth=2. The four trailing levels are required for Vivado to distribute
  * this tiled multiply across every DSP's MREG/PREG. Valid bits qualify the result; data
  * deliberately shifts on invalid cycles because per-stage clock enables prevent that DSP
  * retiming." Here the operands are significands, always non-negative, so this is a plain
  * UInt(64) * UInt(64) -> UInt(128) rather than MulCore's 33-bit sign-extension trick (the
  * sign is an XOR of two bits, handled entirely outside the multiplier). The two operand
  * levels and the FOUR trailing product levels are kept verbatim; a 64x64 tiling is strictly
  * deeper than a 33x33 one, so four is a floor, not a ceiling. The critical property to
  * preserve is the one MulCore's comment calls out: NO PER-STAGE CLOCK ENABLE on the
  * opA0/opA1/mulP/prd* chain -- the data shifts unconditionally and the parallel valid chain
  * qualifies it. */
class FpMulPipe extends Component {
  val io = new Bundle {
    val start    = in Bool ()
    val dst      = in Bits (80 bits)
    val src      = in Bits (80 bits)
    val rmode    = in Bits (2 bits)
    val precision = in Bits (2 bits)     // effective FPCR.PREC / forced FSMUL,FDMUL
    val outValid = out Bool ()
    val outReq   = out(FpRoundReq())
  }

  // -- M0: unpack, classify, count leading zeros of both significands -----------
  val m0 = new Area {
    val vld  = RegNext(io.start) init False
    val dst  = RegNext(io.dst); val src = RegNext(io.src)
    val dNan = RegNext(Fp80.isNan(io.dst));  val sNan  = RegNext(Fp80.isNan(io.src))
    val dInf = RegNext(Fp80.isInf(io.dst));  val sInf  = RegNext(Fp80.isInf(io.src))
    val dSN  = RegNext(Fp80.isSNan(io.dst)); val sSN   = RegNext(Fp80.isSNan(io.src))
    // SoftFloat's true-zero test here is (exp | sig) == 0, i.e. the ENTIRE 79 low bits,
    // NOT Musashi's FPCC zero test -- a subnormal is not a zero operand for FMUL.
    val dZero = RegNext(io.dst(78 downto 0) === 0)
    val sZero = RegNext(io.src(78 downto 0) === 0)
    val dSub  = RegNext(Fp80.exp(io.dst) === 0); val sSub = RegNext(Fp80.exp(io.src) === 0)
    val rmod  = RegNext(io.rmode)          // latched at issue, travels with the req
    val prec  = RegNext(io.precision)      // ditto -- see FpRoundReq.prec
    val dClz  = RegNext(Fp80.clz(io.dst(63 downto 0)))
    val sClz  = RegNext(Fp80.clz(io.src(63 downto 0)))
  }

  // -- M1: pre-normalise subnormals, form zExp and zSign, resolve specials ------
  val m1 = new Area {
    val dSig = Mux(m0.dSub, Fp80.sig(m0.dst) |<< m0.dClz.resize(7), Fp80.sig(m0.dst))
    val sSig = Mux(m0.sSub, Fp80.sig(m0.src) |<< m0.sClz.resize(7), Fp80.sig(m0.src))
    val dExp = Mux(m0.dSub, S(1, 18 bits) - m0.dClz.resize(18).asSInt,
                            Fp80.exp(m0.dst).resize(18).asSInt)
    val sExp = Mux(m0.sSub, S(1, 18 bits) - m0.sClz.resize(18).asSInt,
                            Fp80.exp(m0.src).resize(18).asSInt)
    val zSign = Fp80.sign(m0.dst) ^ Fp80.sign(m0.src)
    val zExp  = dExp + sExp - S(0x3FFE, 18 bits)

    val anyNan  = m0.dNan || m0.sNan
    val infZero = (m0.dInf && m0.sZero) || (m0.sInf && m0.dZero)

    val req = FpRoundReq().overridable()
    req.sign := zSign; req.exp := zExp; req.sig := 0; req.round := False; req.sticky := False
    req.writeFp := True; req.fpccFromSrc := False; req.fpccOverride := 0
    req.rmode := m0.rmod
    req.prec  := m0.prec
    req.exc.clearExc()
    req.exc.snan  := m0.dSN || m0.sSN
    req.exc.operr := infZero && !anyNan
    req.bypass := False; req.bypassValue := Fp80.defaultNan
    when(anyNan) {
      req.bypass := True; req.bypassValue := Fp80.propagateNan(m0.dst, m0.src)
    } elsewhen(infZero) {
      req.bypass := True; req.bypassValue := Fp80.defaultNan
    } elsewhen(m0.dInf || m0.sInf) {
      req.bypass := True; req.bypassValue := Fp80.packInf(zSign)
    } elsewhen(m0.dZero || m0.sZero) {
      req.bypass := True; req.bypassValue := Fp80.packZero(zSign)
    }

    val vld  = RegNext(m0.vld) init False
    val rReq = RegNext(req); val rA = RegNext(dSig); val rB = RegNext(sSig)
  }

  // -- M2..M8: the DSP chain. MulCore.scala:53-74's idiom, unconditional shift, no CE. --
  val opA0 = Reg(UInt(64 bits)); val opB0 = Reg(UInt(64 bits))
  val opA1 = Reg(UInt(64 bits)); val opB1 = Reg(UInt(64 bits))
  val mulP = Reg(UInt(128 bits))
  val prd0 = Reg(UInt(128 bits)); val prd1 = Reg(UInt(128 bits))
  val prd2 = Reg(UInt(128 bits)); val prd3 = Reg(UInt(128 bits))
  opA0 := m1.rA; opB0 := m1.rB
  opA1 := opA0;  opB1 := opB0
  mulP := opA1 * opB1
  prd0 := mulP; prd1 := prd0; prd2 := prd1; prd3 := prd2

  val ctxValid = Vec.fill(7)(RegInit(False))
  val ctxReq   = Vec.fill(7)(Reg(FpRoundReq()))
  ctxValid(0) := m1.vld; ctxReq(0) := m1.rReq
  for (i <- 1 until 7) { ctxValid(i) := ctxValid(i - 1); ctxReq(i) := ctxReq(i - 1) }

  // -- M9: 1-bit post-normalise + sticky, form the FpRoundReq ------------------
  val m9 = new Area {
    // SoftFloat: if the product's bit 127 is clear, shift left one and decrement zExp
    // (softfloat.c:3373-3376). Product of two normalised significands is in [2^126, 2^128).
    val norm  = Mux(prd3(127), prd3, prd3 |<< 1)
    val expAd = Mux(prd3(127), S(0, 18 bits), S(-1, 18 bits))
    val req   = FpRoundReq().overridable()
    req := ctxReq(6)
    req.sig    := norm(127 downto 64)
    req.round  := norm(63)
    req.sticky := norm(62 downto 0) =/= 0
    req.exp    := ctxReq(6).exp + expAd
    val vld  = RegNext(ctxValid(6)) init False
    val rReq = RegNext(req)
  }

  io.outValid := m9.vld
  io.outReq   := m9.rReq
}
