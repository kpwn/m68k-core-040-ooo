package m68k040.execute.fpu

import spinal.core._

object FpuCore {
  /** Registered stages from an accepted fixed-lane `start` through `doneFixed`.
    *
    * HARD CONSTANT, exactly like MulCore.Latency (MulCore.scala:9): the FPU EU's descriptor
    * shadow pipe must be sized from this, and changing it requires updating that pipe in the
    * same commit (Global Constraint GC-F2).
    *
    * 13 = 10 (lane front) + 3 (FpRoundPack). The 10 is set by FMUL, which is the deepest
    * front and the one whose depth is not free to choose: 1 unpack/CLZ + 1 subnormal
    * pre-normalise + 7 DSP48E2 register levels (2 operand + 1 multiply + 4 product -- the
    * exact structure MulCore.scala:49-52 documents as required for Vivado to distribute a
    * tiled multiply across every DSP's MREG/PREG, and a 64x64 tiling is strictly deeper than
    * MulCore's 33x33, so 4 trailing levels is a floor) + 1 post-normalise. FADD and the
    * cheap pipe are held at the same 10 on purpose -- see the header note below.
    *
    * FDIV/FSQRT are NOT covered by this constant: they are a busy/done handshake exactly
    * like DivCore. Worst case FpDivSqrtCore.WorstCaseLatency cycles.
    *
    * ADDENDUM (task #218, FMax closure -- read this before using the constant as an
    * end-to-end latency). This is FpuCore's OWN start-to-doneFixed depth and it is still 13:
    * nothing inside this component was re-timed. What DID change is upstream of it. The EU
    * used to resolve the source operand combinationally in the issue cycle, off the integer
    * PRF's bypass output -- i.e. straight off AluEuPlugin's S1 result cone -- which made
    * `AluEu.s1Ctx -> FpMulPipe.m0_sClz` the whole design's worst path (6.652ns / 27 logic
    * levels / WNS -2.669ns post-route). DivEuPlugin now has an ISSUE REGISTER (`fpS1*`) in
    * front of that conversion cone, so:
    *
    *   EU-visible fixed-lane latency (issue accept -> FP completion register) is
    *   FixedLatency + 1, NOT FixedLatency.
    *
    * GC-F2 is unchanged and still binding: the EU's descriptor shadow pipe is sized from
    * this constant and stays exactly FixedLatency deep -- it is now PUSHED one cycle after
    * acceptance, on the cycle `io.start` actually fires. Re-timing FpuCore still requires
    * updating that pipe in the same commit; and moving that push back to the accept cycle
    * (or bumping this constant to "14" to mean the EU-visible number) breaks the alignment
    * in exactly the way DivEuPlugin's FS1 comment documents. */
  val FixedLatency = 13
}

/** 80-bit IEEE-754 extended-precision FPU arithmetic core (design spec Decision 1:
  * 1 sign + 15 exponent + 64 explicit-integer-bit mantissa).
  *
  * Self-contained in the sense MulCore.scala is: plain start / busy / done / operands /
  * result, no awareness of the IQ, ROB, rename, FP PRF, decode, FPCR/FPSR architectural
  * state, EA generation, or exception vectoring.
  *
  * TWO RESULT PORTS, ONE PER LANE. This is the topology 2026-08-09 spec §3 specifies
  * ("Separate fixed-result FIFO/hold and iterative-result hold feed one atomic arbiter"):
  * the arbiter lives in the EU, above this component, not inside it.
  *
  * WHY ALL FIXED-LATENCY OPS SHARE ONE LATENCY. FpAddPipe, FpMulPipe and FpCheapPipe are all
  * exactly 10 stages deep, so with a single `start` port at most one of them can ever be
  * presenting a result to the shared FpRoundPack in any cycle -- which is what lets the three
  * lanes share one round/pack back-end with no arbitration, and lets the whole fixed side
  * expose a single MulCore-shaped port whose results are strictly in issue order. The cost
  * is that FABS/FNEG/FMOVE (one cycle of real logic) retire at 13. That is deliberate: the
  * alternative is a second early-result port, which would add a second collision source to
  * the EU's completion arbiter for ops whose latency is already hidden by rename and a
  * 64-entry ROB. If profiling later shows cheap-op latency mattering, adding that port is a
  * localised change here plus one arbiter input in the EU.
  *
  * NO BACKPRESSURE ON THE FIXED SIDE. `doneFixed` is an unconditional 1-cycle pulse
  * FixedLatency after an accepted `start`, identical to MulCore. The EU MUST reserve result
  * capacity before asserting `start` (2026-08-09 spec §3). */
class FpuCore extends Component {
  val io = new Bundle {
    // -- request --
    val start   = in Bool ()
    val op      = in(FpOp())
    val dst     = in Bits (80 bits)   // FPn destination operand (SoftFloat's `a`)
    val src     = in Bits (80 bits)   // <ea>/FPm source operand (SoftFloat's `b`)
    val rmode   = in Bits (2 bits)    // FPCR[5:4] verbatim: 0=RN 1=RZ 2=RM 3=RP
    val cromSel = in Bits (7 bits)    // FMOVECR offset (command word bits [6:0])
    val ready   = out Bool ()         // combinational, a function of `op`

    // -- fixed lane (FADD/FSUB/FMUL/FABS/FNEG/FMOVE/FCMP/FTST/FINT/FINTRZ/FMOVECR) --
    val doneFixed = out Bool ()
    val resFixed  = out(FpResult())

    // -- iterative lane (FDIV/FSQRT) --
    val busyIter = out Bool ()
    val doneIter = out Bool ()        // LEVEL, held until iterAck
    val iterAck  = in Bool ()
    val resIter  = out(FpResult())

    // -- combinational operand classification (VERIFY-4; policy belongs to the EU) --
    val srcUnnormal = out Bool ()
    val dstUnnormal = out Bool ()
    val srcDenorm   = out Bool ()
    val dstDenorm   = out Bool ()
  }

  val isIter  = io.op === FpOp.FDIV || io.op === FpOp.FSQRT
  val isAdd   = io.op === FpOp.FADD || io.op === FpOp.FSUB || io.op === FpOp.FCMP
  val isMul   = io.op === FpOp.FMUL
  val isCheap = !isIter && !isAdd && !isMul

  val addPipe   = new FpAddPipe
  val mulPipe   = new FpMulPipe
  val cheapPipe = new FpCheapPipe
  val iterCore  = new FpDivSqrtCore
  val roundPack = new FpRoundPack

  addPipe.io.start := io.start && isAdd
  addPipe.io.op    := io.op
  addPipe.io.dst   := io.dst
  addPipe.io.src   := io.src
  addPipe.io.rmode := io.rmode

  mulPipe.io.start := io.start && isMul
  mulPipe.io.dst   := io.dst
  mulPipe.io.src   := io.src
  mulPipe.io.rmode := io.rmode

  cheapPipe.io.start   := io.start && isCheap
  cheapPipe.io.op      := io.op
  cheapPipe.io.src     := io.src
  cheapPipe.io.rmode   := io.rmode
  cheapPipe.io.cromSel := io.cromSel

  iterCore.io.start  := io.start && isIter && !iterCore.io.busy
  iterCore.io.isSqrt := io.op === FpOp.FSQRT
  iterCore.io.dst    := io.dst
  iterCore.io.src    := io.src
  iterCore.io.rmode  := io.rmode
  iterCore.io.ack    := io.iterAck

  // Mutually exclusive by construction (one `start` port, three equal-depth fronts).
  GenerationFlags.simulation {
    val n = addPipe.io.outValid.asUInt.resize(2) +
            mulPipe.io.outValid.asUInt.resize(2) +
            cheapPipe.io.outValid.asUInt.resize(2)
    assert(n <= 1, "FpuCore: two fixed fronts presented a result in the same cycle")
  }

  roundPack.io.inValid := addPipe.io.outValid || mulPipe.io.outValid || cheapPipe.io.outValid
  roundPack.io.inReq   := Mux(addPipe.io.outValid, addPipe.io.outReq,
                          Mux(mulPipe.io.outValid, mulPipe.io.outReq, cheapPipe.io.outReq))

  io.doneFixed := roundPack.io.outValid
  io.resFixed  := roundPack.io.outRes
  io.busyIter  := iterCore.io.busy
  io.doneIter  := iterCore.io.done
  io.resIter   := iterCore.io.res

  io.ready := Mux(isIter, !iterCore.io.busy, True)

  io.srcUnnormal := Fp80.isUnnormal(io.src)
  io.dstUnnormal := Fp80.isUnnormal(io.dst)
  io.srcDenorm   := Fp80.isDenorm(io.src)
  io.dstDenorm   := Fp80.isDenorm(io.dst)
}
