package m68k040.execute

import m68k040.{M68kSim, VerilatorTest}
import m68k040.decode.{DecOp, EaAuto, FpSrcKind, SysKind}
import m68k040.execute.fpu.{FpCheapPipe, FpuCore}
import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{
  FpccRegFileService,
  FpRegFileService,
  IntRegFileService,
  RegFilePluginFp,
  RegFilePluginFpcc,
  RegFilePluginInt,
  RegFilePluginNzvc,
  RegFileReadPort,
  RegFileWritePort
}
import m68k040.isa.{Cluster, MemOp, Size}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

import scala.collection.mutable.ArrayBuffer

/** Directed test of the CPLX cluster's NEW FP writeback lane, end to end inside the real
  * `DivEuPlugin` -- not `FpuCore` alone (`FpuCoreSpec` already owns the arithmetic).
  *
  * What this spec is actually for, i.e. everything the arithmetic core cannot see:
  *   1. the source-operand GATEWAY (`fpSrcKind` dispatch + the three format converters),
  *   2. the descriptor shadow pipe / result routing (robId, pFpDst, pFpccDst),
  *   3. the real FP + FPCC physical register writes and the two dynamic wakeups,
  *   4. lane INDEPENDENCE: an int/NZVC result and an FP result completing the same cycle,
  *   5. the flush contract, including the iterative lane's `doneIter`/`iterAck` handshake,
  *      which has no flush input of its own and therefore MUST still be acknowledged for a
  *      flushed FDIV or the engine wedges permanently.
  *
  * Harness shape follows `CplxMulPipelineSpec` (the same plugin/PRF-in-the-loop pattern on
  * the same EU): real register files, a Stream-correct source, and results checked at the
  * external port boundary and then read back out of the PRFs. */
class FpuEuIntegrationSpec extends AnyFunSuite {

  // ── 80-bit extended literals (sign ## 15-bit biased exponent ## 64-bit significand) ──
  private def ext(neg: Boolean, exp: Int, sig: BigInt): BigInt =
    (if (neg) BigInt(1) << 79 else BigInt(0)) | (BigInt(exp) << 64) | sig
  private val Msb  = BigInt(1) << 63
  private val Zero    = BigInt(0)
  private val One     = ext(false, 0x3FFF, Msb)
  private val Two     = ext(false, 0x4000, Msb)
  private val Three   = ext(false, 0x4000, BigInt("C000000000000000", 16))
  private val Four    = ext(false, 0x4001, Msb)
  private val Five    = ext(false, 0x4001, BigInt("A000000000000000", 16))
  private val Six     = ext(false, 0x4001, BigInt("C000000000000000", 16))
  private val Half    = ext(false, 0x3FFE, Msb)
  private val MinusOne= ext(true,  0x3FFF, Msb)

  // FPCC internal layout (2026-08-09 spec section 8): bit0=N, bit1=Z, bit2=I, bit3=NaN.
  private val CcNone = 0
  private val CcN    = 1
  private val CcZ    = 2

  // FP opmodes (raw extension-word [6:0]).
  private val OpFMOVE = 0x00
  private val OpFSQRT = 0x04
  private val OpFDIV  = 0x20
  private val OpFADD  = 0x22
  private val OpFCMP  = 0x38

  /** Test-only source/sink around the real PRFs and the DivEu service. */
  class Src extends FiberPlugin {
    var preInt: RegFileWritePort  = null
    var preFp:  RegFileWritePort  = null
    var rdFp:   RegFileReadPort   = null
    var rdFpcc: RegFileReadPort   = null
    var rdInt:  RegFileReadPort   = null

    during setup {
      preInt = host[IntRegFileService].newWrite(latency = 1, sharingKey = "fpEuTestInt")
      preFp  = host[FpRegFileService].newWrite(latency = 1, sharingKey = "fpEuTestFp")
      rdFp   = host[FpRegFileService].newRead(forceNoBypass = true)
      rdFpcc = host[FpccRegFileService].newRead(forceNoBypass = true)
      rdInt  = host[IntRegFileService].newRead(forceNoBypass = true)
    }

    val logic = during build new Area {
      val eu    = host[DivEuService]
      val divEu = host[DivEuPlugin]

      // ---- issue-side stimulus ----
      val iValid     = in Bool ()
      val iOp        = in(DecOp())
      val iRob       = in UInt (6 bits)
      val iFlush     = in Bool ()
      // FP-specific
      val iFpuOp     = in Bits (7 bits)
      val iFpSrcKind = in(FpSrcKind())
      val iFpSrcFmt  = in Bits (3 bits)
      val iFpWideImm = in Bits (80 bits)
      val iPFpSrcA   = in UInt (4 bits)
      val iPFpSrcB   = in UInt (4 bits)
      val iPFpDst    = in UInt (4 bits)
      val iWritesFp  = in Bool ()
      val iPFpccDst  = in UInt (4 bits)
      // int operands (INTREG / MEMPAIR / MEMEXT sources, and the MUL collision case)
      val iPsrcA     = in UInt (6 bits)
      val iPsrcB     = in UInt (6 bits)
      val iPsrcC     = in UInt (6 bits)
      val iPdst      = in UInt (6 bits)
      val iPdstValid = in Bool ()
      val iPNzvcDst  = in UInt (4 bits)
      val iWritesNzvc= in Bool ()
      val iSize      = in(Size())
      // Task 14b fix pass: `DecOp.FPSTORECVT` carries its 32-bit-chunk index in imm[1:0]
      // (`SFpStIdx0/1/2`), so the imm can no longer be hardwired to 0 here.
      val iImm       = in Bits (32 bits)

      val ctx = IqContext()
      val uop = ctx.uop
      // Drive EVERY RenamedUop field: a newly-consumed field must fail deterministically
      // rather than turn this into a netlist-dependent test (CplxMulPipelineSpec's rule).
      uop.valid := iValid
      uop.pc := U(0x2000, 32 bits); uop.nextPc := U(0x2004, 32 bits)
      uop.op := iOp
      uop.cluster := Cluster.CPLX
      uop.size := iSize
      uop.memOp := MemOp.NONE
      uop.useImm := False; uop.imm := iImm
      uop.isBranch := False; uop.cond := 0; uop.branchDisp := 0
      uop.ibranch := False; uop.anInc := 0; uop.stkPush := False
      uop.eaAuto := EaAuto.NONE; uop.eaDelta := 0
      uop.ccrRestore := False; uop.toCcr := False; uop.unimplemented := False
      uop.faulted := False; uop.faultVector := 0; uop.isRte := False
      uop.faultUsesNextPc := False; uop.isCondTrap := False
      uop.sswInstr := False; uop.faultAtc := True
      uop.divSigned := False; uop.div64 := False; uop.divIsRem := False
      uop.isChk2 := False
      uop.shiftOp := 0; uop.shiftDir := False; uop.bcdSub := False
      uop.bitOp := 0; uop.bfOp := 0; uop.bfDynamic := False
      uop.bfMem := False; uop.bfStoreForm := 0
      uop.extByte := False; uop.isMovea := False
      uop.isScc := False; uop.isDbcc := False
      uop.firstOfInstr := True
      uop.indexLong := False; uop.indexScale := 0
      uop.leaAddr := False; uop.movesAliasStore := False
      uop.fromCcr := False; uop.fromSr := False; uop.needsSupervisor := False
      uop.keepCommit := False
      uop.sysOp := False; uop.sysKind := SysKind.NONE; uop.sysReadDir := False
      uop.predTaken := False; uop.predTarget := 0
      uop.phtValid := False; uop.phtIndex := 0
      uop.casForm := 0
      uop.dstArch := 0
      uop.psrcA := iPsrcA; uop.psrcAValid := True
      uop.psrcB := iPsrcB; uop.psrcBValid := True
      uop.psrcC := iPsrcC; uop.psrcCValid := True
      uop.pdst := iPdst; uop.pdstValid := iPdstValid; uop.pdstOld := 0
      uop.pNzvcSrc := 0; uop.readsNzvc := False
      uop.pNzvcDst := iPNzvcDst; uop.writesNzvc := iWritesNzvc; uop.pNzvcOld := 0
      uop.pXSrc := 0; uop.readsX := False
      uop.pXDst := 0; uop.writesX := False; uop.pXOld := 0
      uop.pFpSrcA := iPFpSrcA; uop.psrcAFpValid := True
      uop.pFpSrcB := iPFpSrcB; uop.psrcBFpValid := True
      uop.pFpDst := iPFpDst; uop.pFpDstValid := iWritesFp; uop.pFpOld := 0; uop.fpDstArch := 0
      uop.pFpccSrc := 0; uop.readsFpcc := False
      uop.pFpccDst := iPFpccDst; uop.writesFpcc := True; uop.pFpccOld := 0
      uop.fpuOp := iFpuOp
      uop.fpSrcKind := iFpSrcKind
      uop.fpSrcFmt := iFpSrcFmt
      uop.fpWideImm := iFpWideImm

      ctx.robId := iRob
      eu.issue.valid := iValid
      eu.issue.payload := ctx
      eu.cplxFlush := iFlush

      val iReady = out Bool (); iReady := eu.issue.ready
      val iFire  = out Bool (); iFire  := eu.issue.fire

      // ---- preload / readback ----
      val preIntValid = in Bool (); val preIntAddr = in UInt (6 bits); val preIntData = in Bits (32 bits)
      preInt.valid := preIntValid; preInt.address := preIntAddr; preInt.data := preIntData
      val preFpValid = in Bool (); val preFpAddr = in UInt (4 bits); val preFpData = in Bits (80 bits)
      preFp.valid := preFpValid; preFp.address := preFpAddr; preFp.data := preFpData
      val rdFpAddr = in UInt (4 bits); val rdFpData = out Bits (80 bits)
      rdFp.addr := rdFpAddr; rdFpData := rdFp.data
      val rdFpccAddr = in UInt (4 bits); val rdFpccData = out Bits (4 bits)
      rdFpcc.addr := rdFpccAddr; rdFpccData := rdFpcc.data
      val rdIntAddr = in UInt (6 bits); val rdIntData = out Bits (32 bits)
      rdInt.addr := rdIntAddr; rdIntData := rdInt.data

      // ---- FP-lane observation ----
      val fpCValid = out Bool (); fpCValid := eu.fpCompletion.valid
      val fpCRob   = out UInt (6 bits); fpCRob := eu.fpCompletion.payload
      val fpWakeV  = out Bool (); fpWakeV := eu.fpWakeup.valid
      val fpWakeP  = out UInt (4 bits); fpWakeP := eu.fpWakeup.payload
      val fpccWakeV = out Bool (); fpccWakeV := eu.fpccWakeup.valid
      val fpccWakeP = out UInt (4 bits); fpccWakeP := eu.fpccWakeup.payload
      val fpFaultV = out Bool (); fpFaultV := eu.fpFault.valid
      val fpData   = out Bits (80 bits); fpData := divEu.logic.fpCompData
      val fpCc     = out Bits (4 bits);  fpCc   := divEu.logic.fpCompFpcc
      val fpSrcObs = out Bits (80 bits); fpSrcObs := divEu.logic.fpSrcVal
      val fpIterBusyO   = out Bool (); fpIterBusyO := divEu.logic.fpIterBusy
      val fpIterFlushedO= out Bool (); fpIterFlushedO := divEu.logic.fpIterFlushed
      val fpIterAckO    = out Bool (); fpIterAckO := divEu.logic.fpIterTake
      val coreIterBusy  = out Bool (); coreIterBusy := divEu.logic.fpu.io.busyIter

      // ---- int-lane observation (lane independence) ----
      val intCValid = out Bool (); intCValid := eu.completion.valid
      val intCRob   = out UInt (6 bits); intCRob := eu.completion.payload
      val intWakeV  = out Bool (); intWakeV := eu.wakeup.valid
      val euFaultV  = out Bool (); euFaultV := eu.euFault.valid
      // ---- int-lane FLUSH-STATE whitebox (Task 14b fix pass, Critical #1) ----
      // The two registers whose post-flush state IS the bug: `fpCvtWait` is the two-cycle
      // converter's own hold bit, and `flushed` is the legacy lane's wrong-path latch that
      // is consumed only by a capture. Both MUST be clear once a flush has settled, or the
      // lane is either wedged (a correct-path op inherits `flushed`) or will read a STALE
      // converter output on its next conversion.
      val cvtWaitO  = out Bool (); cvtWaitO := divEu.logic.fpCvtWait
      val cvtFlushedO = out Bool (); cvtFlushedO := divEu.logic.flushed
      val s1ValidO  = out Bool (); s1ValidO := divEu.logic.s1Valid
    }
  }

  class Dut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val rfFp   = new RegFilePluginFp
    val rfFpcc = new RegFilePluginFpcc
    val eu = new DivEuPlugin
    val src = new Src
    db.on { host.asHostOf(Seq[FiberPlugin](rfInt, rfNzvc, rfFp, rfFpcc, eu, src)) }
  }

  private lazy val dut = M68kSim().withVerilator.compile(new Dut)

  /** One captured FP completion. */
  private case class FpSeen(cycle: Int, rob: Int, pdst: Int, data: BigInt, cc: Int,
                            fpWrote: Boolean, fpccDst: Int)

  /** Shared driver: boots the DUT past both PRFs' reset zero-sweeps and exposes helpers. */
  private class Harness(d: Dut) {
    val cd = d.clockDomain
    val s  = d.src.logic
    var cycle = 0
    val fpSeen = ArrayBuffer[FpSeen]()
    val intSeen = ArrayBuffer[(Int, Int)]()   // (cycle, robId)
    var sawCollision = false

    def idle(): Unit = {
      s.iValid #= false
      s.iOp #= DecOp.FPU
      s.iRob #= 0; s.iFlush #= false
      s.iFpuOp #= OpFADD; s.iFpSrcKind #= FpSrcKind.FPREG; s.iFpSrcFmt #= 0
      s.iFpWideImm #= BigInt(0)
      s.iPFpSrcA #= 0; s.iPFpSrcB #= 0; s.iPFpDst #= 0; s.iWritesFp #= true; s.iPFpccDst #= 0
      s.iPsrcA #= 0; s.iPsrcB #= 0; s.iPsrcC #= 0
      s.iPdst #= 0; s.iPdstValid #= false; s.iPNzvcDst #= 0; s.iWritesNzvc #= false
      s.iSize #= Size.LONG; s.iImm #= BigInt(0)
      s.preIntValid #= false; s.preIntAddr #= 0; s.preIntData #= 0
      s.preFpValid #= false; s.preFpAddr #= 0; s.preFpData #= BigInt(0)
      s.rdFpAddr #= 0; s.rdFpccAddr #= 0; s.rdIntAddr #= 0
    }

    def sample(): Unit = {
      assert(!s.fpFaultV.toBoolean,
        s"unexpected FP enabled-trap escalation at cycle $cycle (FPCR enables are all clear)")
      if (s.fpCValid.toBoolean && s.intCValid.toBoolean) sawCollision = true
      if (s.fpCValid.toBoolean) {
        fpSeen += FpSeen(cycle, s.fpCRob.toInt, s.fpWakeP.toInt, s.fpData.toBigInt,
          s.fpCc.toInt, s.fpWakeV.toBoolean, s.fpccWakeP.toInt)
        assert(s.fpccWakeV.toBoolean,
          s"FP completion rob=${s.fpCRob.toInt} did not broadcast an FPCC wakeup")
      } else {
        assert(!s.fpWakeV.toBoolean && !s.fpccWakeV.toBoolean,
          s"orphan FP wakeup at cycle $cycle")
      }
      if (s.intCValid.toBoolean) intSeen += ((cycle, s.intCRob.toInt))
    }

    def tick(n: Int = 1): Unit = for (_ <- 0 until n) { cd.waitSampling(); cycle += 1; sample() }

    def boot(): Unit = { cd.forkStimulus(10); idle(); tick(80) }

    def preloadFp(addr: Int, v: BigInt): Unit = {
      s.preFpValid #= true; s.preFpAddr #= addr; s.preFpData #= v
      tick(); s.preFpValid #= false; tick()
    }
    def preloadInt(addr: Int, v: BigInt): Unit = {
      s.preIntValid #= true; s.preIntAddr #= addr; s.preIntData #= v
      tick(); s.preIntValid #= false; tick()
    }
    def readFp(addr: Int): BigInt = { s.rdFpAddr #= addr; sleep(1); s.rdFpData.toBigInt }
    def readFpcc(addr: Int): Int  = { s.rdFpccAddr #= addr; sleep(1); s.rdFpccData.toInt }
    def readInt(addr: Int): BigInt = { s.rdIntAddr #= addr; sleep(1); s.rdIntData.toBigInt }

    /** Present one INT-lane uop (any `DecOp` other than `FPU`) and wait for the accepting
      * edge. The `issue` helper above is FP-lane shaped (it hardwires `DecOp.FPU` and an
      * FP destination); the int lane needs the integer operand/destination fields and, for
      * `FPSTORECVT`, both the FP source address and the imm-borne chunk index. Restores the
      * idle FP-lane defaults on the way out so it composes with `issue`. */
    def issueInt(rob: Int, op: SpinalEnumElement[DecOp.type],
                 size: SpinalEnumElement[Size.type] = Size.LONG,
                 psrcA: Int = 0, psrcB: Int = 0, pdst: Int = 0, pdstValid: Boolean = false,
                 pNzvcDst: Int = 0, writesNzvc: Boolean = false,
                 pFpSrcA: Int = 0, imm: BigInt = BigInt(0), fmt: Int = 0,
                 label: String = "int issue"): Unit = {
      s.iOp #= op
      s.iRob #= rob; s.iSize #= size
      s.iPsrcA #= psrcA; s.iPsrcB #= psrcB; s.iPsrcC #= 0
      s.iPdst #= pdst; s.iPdstValid #= pdstValid
      s.iPNzvcDst #= pNzvcDst; s.iWritesNzvc #= writesNzvc
      s.iPFpSrcA #= pFpSrcA; s.iPFpSrcB #= 0; s.iPFpDst #= 0; s.iWritesFp #= false
      s.iPFpccDst #= 0; s.iImm #= imm; s.iFpSrcFmt #= fmt
      s.iValid #= true
      sleep(1)
      var guard = 0
      while (!(s.iReady.toBoolean && s.iFire.toBoolean) && guard < 200) { tick(); sleep(1); guard += 1 }
      assert(s.iReady.toBoolean && s.iFire.toBoolean, s"$label was never accepted")
      tick()
      s.iValid #= false
      s.iOp #= DecOp.FPU; s.iPdstValid #= false; s.iWritesNzvc #= false
      s.iSize #= Size.LONG; s.iImm #= BigInt(0); s.iWritesFp #= true
    }

    /** Wait for `n` more int-lane completions (the CPLX legacy lane's shared port). */
    def drainIntTo(n: Int, budget: Int, label: String): Unit = {
      var guard = 0
      while (intSeen.size < n && guard < budget) { tick(); guard += 1 }
      assert(intSeen.size == n,
        s"$label observed ${intSeen.size}/$n int-lane completions after $guard cycles")
    }

    /** Present one uop and wait for the accepting edge. */
    def issue(rob: Int, opmode: Int, kind: SpinalEnumElement[FpSrcKind.type],
              pFpSrcA: Int = 0, pFpSrcB: Int = 0, pFpDst: Int = 0, pFpccDst: Int = 0,
              writesFp: Boolean = true, fmt: Int = 0, wideImm: BigInt = BigInt(0),
              psrcA: Int = 0, psrcB: Int = 0, psrcC: Int = 0, label: String = "issue"): Unit = {
      s.iOp #= DecOp.FPU
      s.iRob #= rob; s.iFpuOp #= opmode; s.iFpSrcKind #= kind; s.iFpSrcFmt #= fmt
      s.iFpWideImm #= wideImm
      s.iPFpSrcA #= pFpSrcA; s.iPFpSrcB #= pFpSrcB; s.iPFpDst #= pFpDst
      s.iWritesFp #= writesFp; s.iPFpccDst #= pFpccDst
      s.iPsrcA #= psrcA; s.iPsrcB #= psrcB; s.iPsrcC #= psrcC
      s.iPdstValid #= false; s.iWritesNzvc #= false
      s.iValid #= true
      sleep(1)
      var guard = 0
      while (!(s.iReady.toBoolean && s.iFire.toBoolean) && guard < 200) { tick(); sleep(1); guard += 1 }
      assert(s.iReady.toBoolean && s.iFire.toBoolean, s"$label was never accepted")
      tick()
      s.iValid #= false
    }

    def drainTo(n: Int, budget: Int, label: String): Unit = {
      var guard = 0
      while (fpSeen.size < n && guard < budget) { tick(); guard += 1 }
      assert(fpSeen.size == n, s"$label observed ${fpSeen.size}/$n FP completions after $guard cycles")
    }
  }

  /** Covers every fpSrcKind that actually SOURCES AN OPERAND, i.e. all nine minus
    * `ROMCONST`: FMOVECR has no source operand at all (the gateway drives 0 and the value
    * comes out of `FpCheapPipe`'s constant ROM instead), and it also overrides the opmode
    * -> `FpOp` mapping, so it cannot ride this test's shared `FADD src,1.0` shape. The
    * ninth kind is covered end to end by the FMOVECR test immediately below. */
  test("FP lane: every operand-sourcing fpSrcKind converts, executes, and writes the FP + FPCC files", VerilatorTest) {
    dut.doSim { d =>
      val h = new Harness(d); import h._
      boot()

      // FP PRF: p1 = 1.0 (the FPn destination read back), p2 = 2.0 (an FPm source).
      preloadFp(1, One)
      preloadFp(2, Two)
      // Int PRF operands for the register/memory source kinds.
      preloadInt(10, BigInt(2))                         // Long 2
      preloadInt(11, BigInt("FFFFFFFE", 16))            // Word  -2 in [15:0]
      preloadInt(12, BigInt("000000FF", 16))            // Byte  -1 in [7:0]
      preloadInt(13, BigInt("40000000", 16))            // Single 2.0f
      preloadInt(14, BigInt("40100000", 16))            // Double 4.0 hi
      preloadInt(15, BigInt(0))                         // Double 4.0 lo
      preloadInt(16, BigInt("40010000", 16))            // Extended 5.0: sign/exp in [31:16]
      preloadInt(17, BigInt("A0000000", 16))            // Extended 5.0: mantissa hi
      preloadInt(18, BigInt(0))                         // Extended 5.0: mantissa lo

      // (label, opmode, kind, fmt, wideImm, psrcA/B/C, expected 80-bit source, expected sum, cc)
      case class Case(label: String, kind: SpinalEnumElement[FpSrcKind.type], fmt: Int,
                      imm: BigInt, a: Int, b: Int, c: Int, src: BigInt, sum: BigInt, cc: Int)
      val cases = Seq(
        Case("FPREG FPm",      FpSrcKind.FPREG,     0, BigInt(0), 0, 0, 0, Two,  Three, CcNone),
        Case("INTREG .L",      FpSrcKind.INTREG,    0, BigInt(0), 10, 0, 0, Two,  Three, CcNone),
        Case("INTREG .W (-2)", FpSrcKind.INTREG,    4, BigInt(0), 11, 0, 0, ext(true, 0x4000, Msb), MinusOne, CcN),
        Case("INTREG .B (-1)", FpSrcKind.INTREG,    6, BigInt(0), 12, 0, 0, MinusOne, Zero, CcZ),
        Case("INTREG .S 2.0f", FpSrcKind.INTREG,    1, BigInt(0), 13, 0, 0, Two,  Three, CcNone),
        Case("INTIMM 4",       FpSrcKind.INTIMM,    0, BigInt(4), 0, 0, 0, Four, Five,  CcNone),
        Case("SINGLEIMM 4.0f", FpSrcKind.SINGLEIMM, 1, BigInt("40800000", 16), 0, 0, 0, Four, Five, CcNone),
        Case("DOUBLEIMM 4.0",  FpSrcKind.DOUBLEIMM, 5, BigInt("4010000000000000", 16), 0, 0, 0, Four, Five, CcNone),
        Case("EXTIMM 5.0",     FpSrcKind.EXTIMM,    2, Five, 0, 0, 0, Five, Six, CcNone),
        Case("MEMPAIR 4.0",    FpSrcKind.MEMPAIR,   5, BigInt(0), 14, 15, 0, Four, Five, CcNone),
        Case("MEMEXT 5.0",     FpSrcKind.MEMEXT,    2, BigInt(0), 16, 17, 18, Five, Six, CcNone))

      for ((cse, i) <- cases.zipWithIndex) {
        val rob = 8 + i
        val pdst = 4 + i          // distinct FP physregs: no two write ports share an address
        val pcc  = 4 + i
        val before = fpSeen.size
        // The gateway output is checked ONE CYCLE PAST the accepting edge -- this isolates a
        // conversion bug from an arithmetic one. (Task #218 moved the conversion cone behind
        // the EU's new FP issue register, so `fpSrcVal` is now the FS1 stage's output: in the
        // presented cycle it still shows the PREVIOUS request. Reading it here, after the
        // accepting edge and a `sleep(1)` settle, observes exactly this uop's conversion --
        // and the operands themselves are still SAMPLED on the accepting edge, unchanged.)
        s.iOp #= DecOp.FPU
        s.iRob #= rob; s.iFpuOp #= OpFADD; s.iFpSrcKind #= cse.kind; s.iFpSrcFmt #= cse.fmt
        s.iFpWideImm #= cse.imm
        s.iPFpSrcA #= 1; s.iPFpSrcB #= 2; s.iPFpDst #= pdst
        s.iWritesFp #= true; s.iPFpccDst #= pcc
        s.iPsrcA #= cse.a; s.iPsrcB #= cse.b; s.iPsrcC #= cse.c
        s.iPdstValid #= false; s.iWritesNzvc #= false
        s.iValid #= true
        sleep(1)
        assert(s.iReady.toBoolean && s.iFire.toBoolean, s"${cse.label} was not accepted")
        tick()
        s.iValid #= false
        sleep(1)
        assert(s.fpSrcObs.toBigInt == cse.src,
          f"${cse.label}: gateway produced 0x${s.fpSrcObs.toBigInt}%020X, expected 0x${cse.src}%020X")
        drainTo(before + 1, FpuCore.FixedLatency + 12, cse.label)
        val g = fpSeen.last
        assert(g.rob == rob, s"${cse.label}: completed rob=${g.rob}, expected $rob")
        assert(g.data == cse.sum,
          f"${cse.label}: result 0x${g.data}%020X, expected 0x${cse.sum}%020X")
        assert(g.cc == cse.cc, s"${cse.label}: FPCC=${g.cc}, expected ${cse.cc}")
        assert(g.fpWrote && g.pdst == pdst, s"${cse.label}: FP wakeup pdst=${g.pdst}, expected $pdst")
        assert(g.fpccDst == pcc, s"${cse.label}: FPCC wakeup dst=${g.fpccDst}, expected $pcc")
        // Prove the real PRF writes landed, not just the ports.
        tick()
        val back = readFp(pdst)
        assert(back == cse.sum,
          f"${cse.label}: FP PRF[$pdst] = 0x$back%020X, expected 0x${cse.sum}%020X")
        assert(readFpcc(pcc) == cse.cc, s"${cse.label}: FPCC PRF[$pcc] = ${readFpcc(pcc)}, expected ${cse.cc}")
      }
    }
  }

  /** The ninth `fpSrcKind`: ROMCONST / FMOVECR.
    *
    * REGRESSION GUARD (Task 8 review, Critical #1). For FMOVECR the extension word's [6:0]
    * field is the constant-ROM OFFSET, not an opmode, and `MicroOpAssembler.fpEmit` admits
    * the FMOVECR form for EVERY offset value (its `fpNative` opmode whitelist does not
    * apply to that form). `$04` and `$20` are therefore perfectly encodable offsets whose
    * bit patterns collide with the FSQRT/FDIV opmodes. `FpSource.isIterativeOpmode`
    * originally dispatched on the raw field alone, so those two offsets were classified as
    * ITERATIVE while `FpuCore` -- whose `io.op` already had the `romConst` override applied
    * -- ran them on the cheap FIXED pipe: `fpIterBusy` latched with a descriptor no
    * `doneIter` would ever match, wedging the iterative lane permanently AND leaving that
    * uop's ROB entry uncompletable (a hard, un-interruptible hang). Hence the two aliasing
    * offsets below, and the explicit "the iterative lane was never touched" assertions. */
  test("FP lane: ROMCONST/FMOVECR reads the constant ROM on the FIXED lane, including the " +
       "offsets that alias the FSQRT/FDIV opmodes", VerilatorTest) {
    dut.doSim { d =>
      val h = new Harness(d); import h._
      boot()

      // Expected values are NOT invented here: the literals are the ones FpuCoreSpec's own
      // "FpuCore FMOVECR constant ROM read-back" directed vectors assert (Task 7), and each
      // is additionally cross-checked against FpCheapPipe.cromWords so a silent ROM/index
      // drift fails here too rather than being papered over by a stale literal.
      case class Cr(off: Int, expect: BigInt, cc: Int, label: String)
      val cases = Seq(
        Cr(0x00, BigInt("4000C90FDAA22168C235", 16), CcNone, "#$00 pi"),
        Cr(0x32, One,                                CcNone, "#$32 1.0"),
        Cr(0x0F, Zero,                               CcZ,    "#$0F 0.0"),
        // The two regression offsets. Both are UNDEFINED ROM offsets, which the 68881 (and
        // Musashi's `default: source = 0`) read back as +0.0 -- the same answer FpuCoreSpec
        // asserts for its own undefined-offset list ($01/$0A/$10/$2F/$40/$7F).
        Cr(0x04, Zero, CcZ, "#$04 (undefined; ALIASES the FSQRT opmode)"),
        Cr(0x20, Zero, CcZ, "#$20 (undefined; ALIASES the FDIV opmode)"))
      for (c <- cases)
        assert(FpCheapPipe.cromWords(FpCheapPipe.cromIndexOf(c.off)) == c.expect,
          f"expected literal for FMOVECR ${c.label} disagrees with FpCheapPipe.cromWords")

      // A sentinel in every destination: "the ROM value landed" must be distinguishable
      // from "the write never happened", especially for the +0.0 cases.
      val Sentinel = ext(true, 0x4003, BigInt("DEADBEEFDEADBEEF", 16))

      for ((c, i) <- cases.zipWithIndex) {
        val rob  = 40 + i
        val pdst = 4 + i
        val pcc  = 4 + i
        preloadFp(pdst, Sentinel)
        val before = fpSeen.size

        // The gateway contributes NO operand for ROMCONST (io.cromSel carries the offset
        // instead), so its output must be exactly zero -- checked at the accepting edge,
        // the same way the operand-sourcing kinds are checked above.
        s.iOp #= DecOp.FPU
        s.iRob #= rob; s.iFpuOp #= c.off; s.iFpSrcKind #= FpSrcKind.ROMCONST; s.iFpSrcFmt #= 7
        s.iFpWideImm #= BigInt(0)
        s.iPFpSrcA #= 1; s.iPFpSrcB #= 2; s.iPFpDst #= pdst
        s.iWritesFp #= true; s.iPFpccDst #= pcc
        s.iPsrcA #= 0; s.iPsrcB #= 0; s.iPsrcC #= 0
        s.iPdstValid #= false; s.iWritesNzvc #= false
        s.iValid #= true
        sleep(1)
        assert(s.iReady.toBoolean && s.iFire.toBoolean, s"FMOVECR ${c.label} was not accepted")
        tick()
        s.iValid #= false

        // THE regression assertion: an FMOVECR must never claim the single-context
        // iterative lane, whatever its ROM offset happens to look like. (`sleep(1)` past
        // the accepting edge so this reads the SETTLED post-edge register, not the value
        // `tick()` returns on the edge itself -- otherwise it is a vacuous check.)
        sleep(1)
        // Same edge is also where this uop's gateway output settles (task #218's FP issue
        // register): ROMCONST must drive a hard zero source, FpuCore's `cromSel` carries the
        // ROM offset instead.
        assert(s.fpSrcObs.toBigInt == BigInt(0),
          f"FMOVECR ${c.label}: gateway drove 0x${s.fpSrcObs.toBigInt}%020X, expected 0")
        assert(!s.fpIterBusyO.toBoolean,
          s"FMOVECR ${c.label} was misrouted onto the ITERATIVE lane -- no doneIter can " +
          "ever arrive for it, so the lane and this uop's ROB entry wedge permanently")
        assert(!s.coreIterBusy.toBoolean,
          s"FMOVECR ${c.label} started FpDivSqrtCore")

        drainTo(before + 1, FpuCore.FixedLatency + 12, s"FMOVECR ${c.label}")
        val g = fpSeen.last
        assert(g.rob == rob, s"FMOVECR ${c.label}: completed rob=${g.rob}, expected $rob")
        assert(g.data == c.expect,
          f"FMOVECR ${c.label}: result 0x${g.data}%020X, expected 0x${c.expect}%020X")
        assert(g.cc == c.cc, s"FMOVECR ${c.label}: FPCC=${g.cc}, expected ${c.cc}")
        assert(g.fpWrote && g.pdst == pdst,
          s"FMOVECR ${c.label}: FP wakeup pdst=${g.pdst}, expected $pdst")
        assert(g.fpccDst == pcc, s"FMOVECR ${c.label}: FPCC wakeup dst=${g.fpccDst}, expected $pcc")
        assert(!s.fpIterBusyO.toBoolean,
          s"FMOVECR ${c.label} left the iterative lane busy")
        tick()
        val back = readFp(pdst)
        assert(back == c.expect,
          f"FMOVECR ${c.label}: FP PRF[$pdst] = 0x$back%020X, expected 0x${c.expect}%020X " +
          f"(sentinel was 0x$Sentinel%020X)")
        assert(readFpcc(pcc) == c.cc,
          s"FMOVECR ${c.label}: FPCC PRF[$pcc] = ${readFpcc(pcc)}, expected ${c.cc}")
      }

      // A genuine FDIV right behind the aliasing FMOVECRs proves the iterative lane is
      // still USABLE -- i.e. the bug's second symptom (a permanently occupied lane) is
      // covered, not just the first (the wedged uop).
      preloadFp(1, One); preloadFp(2, Two)
      val before = fpSeen.size
      issue(rob = 50, opmode = OpFDIV, kind = FpSrcKind.FPREG,
        pFpSrcA = 1, pFpSrcB = 2, pFpDst = 9, pFpccDst = 9, label = "FDIV after FMOVECR")
      drainTo(before + 1, 200, "FDIV after FMOVECR")
      assert(fpSeen.last.data == Half,
        f"FDIV after the aliasing FMOVECRs gave 0x${fpSeen.last.data}%020X, expected 0.5")
    }
  }

  test("FP lane: subnormal single/double conversion and SNaN preservation", VerilatorTest) {
    dut.doSim { d =>
      val h = new Harness(d); import h._
      boot()
      preloadFp(1, One)

      // FMOVE passes its (converted) source through verbatim, so the completed 80-bit
      // result IS the gateway output -- an end-to-end check of the converters.
      case class Conv(label: String, kind: SpinalEnumElement[FpSrcKind.type], fmt: Int,
                      imm: BigInt, expect: BigInt)
      val convs = Seq(
        // Smallest single subnormal 2^-149: frac=1 -> clz23=22 -> exp 0x3F80-22, sig 1<<63.
        Conv("single 2^-149", FpSrcKind.SINGLEIMM, 1, BigInt(1), ext(false, 0x3F80 - 22, Msb)),
        // Smallest double subnormal 2^-1074: frac=1 -> clz52=51 -> exp 0x3C00-51, sig 1<<63.
        Conv("double 2^-1074", FpSrcKind.DOUBLEIMM, 5, BigInt(1), ext(false, 0x3C00 - 51, Msb)),
        // Largest double subnormal (frac all ones) -> clz52=0 -> exp 0x3C00, sig frac<<12.
        Conv("double max subnormal", FpSrcKind.DOUBLEIMM, 5, (BigInt(1) << 52) - 1,
          ext(false, 0x3C00, ((BigInt(1) << 52) - 1) << 12)),
        // Single infinity.
        Conv("single +Inf", FpSrcKind.SINGLEIMM, 1, BigInt("7F800000", 16), ext(false, 0x7FFF, Msb)),
        // Single QUIET NaN: payload MSB set -> bit62 set -> identical to SoftFloat's
        // commonNaNToFloatx80 (which force-ORs 0xC000000000000000).
        Conv("single QNaN", FpSrcKind.SINGLEIMM, 1, BigInt("7FC00001", 16),
          ext(false, 0x7FFF, Msb | (BigInt(3) << 62) | (BigInt(1) << 40))),
        // Single SIGNALLING NaN: stays signalling (bit62 clear) so FpuCore can raise SNAN,
        // instead of being pre-quieted by the conversion.
        Conv("single SNaN", FpSrcKind.SINGLEIMM, 1, BigInt("7F800001", 16),
          ext(false, 0x7FFF, Msb | (BigInt(1) << 40))),
        // Negative zero survives with its sign.
        Conv("double -0.0", FpSrcKind.DOUBLEIMM, 5, BigInt(1) << 63, ext(true, 0, BigInt(0))),
        // INTIMM 0 -> +0 (SoftFloat forces sign 0 for a==0).
        Conv("int 0", FpSrcKind.INTIMM, 0, BigInt(0), Zero),
        // INTIMM INT_MIN -> -2^31.
        Conv("int -2^31", FpSrcKind.INTIMM, 0, BigInt("80000000", 16), ext(true, 0x401E, Msb)))

      for ((c, i) <- convs.zipWithIndex) {
        val before = fpSeen.size
        val pdst = 4 + i
        s.iOp #= DecOp.FPU
        s.iRob #= 20 + i; s.iFpuOp #= OpFMOVE; s.iFpSrcKind #= c.kind; s.iFpSrcFmt #= c.fmt
        s.iFpWideImm #= c.imm
        s.iPFpSrcA #= 1; s.iPFpSrcB #= 0; s.iPFpDst #= pdst
        s.iWritesFp #= true; s.iPFpccDst #= pdst
        s.iPsrcA #= 0; s.iPsrcB #= 0; s.iPsrcC #= 0
        s.iPdstValid #= false; s.iWritesNzvc #= false
        s.iValid #= true
        sleep(1)
        assert(s.iFire.toBoolean, s"${c.label} was not accepted")
        tick(); s.iValid #= false
        // One cycle past the accepting edge: `fpSrcVal` is the FP issue register's output
        // since task #218 (see the first fpSrcKind test for the full note).
        sleep(1)
        assert(s.fpSrcObs.toBigInt == c.expect,
          f"${c.label}: gateway 0x${s.fpSrcObs.toBigInt}%020X, expected 0x${c.expect}%020X")
        drainTo(before + 1, FpuCore.FixedLatency + 12, c.label)
        assert(fpSeen.last.data == c.expect,
          f"${c.label}: FMOVE result 0x${fpSeen.last.data}%020X, expected 0x${c.expect}%020X")
      }
    }
  }

  test("FP lane: FCMP writes FPCC only, and a dense fixed stream keeps issue order", VerilatorTest) {
    dut.doSim { d =>
      val h = new Harness(d); import h._
      boot()
      preloadFp(1, One)
      preloadFp(2, Two)

      // FCMP 2.0 vs 1.0 -> 1.0-2.0 < 0 -> N set, and NO FP register write at all.
      val before = fpSeen.size
      issue(rob = 30, opmode = OpFCMP, kind = FpSrcKind.FPREG,
        pFpSrcA = 1, pFpSrcB = 2, pFpDst = 9, pFpccDst = 9, writesFp = false, label = "FCMP")
      drainTo(before + 1, FpuCore.FixedLatency + 12, "FCMP")
      val g = fpSeen.last
      assert(g.rob == 30, s"FCMP completed rob=${g.rob}")
      assert(!g.fpWrote, "FCMP must not write the FP register file")
      assert(g.cc == CcN, s"FCMP(1.0,2.0) FPCC=${g.cc}, expected N (${CcN})")
      tick()
      assert(readFpcc(9) == CcN, s"FCMP did not write the FPCC file: ${readFpcc(9)}")

      // Dense II=1 fixed stream: 6 FADDs accepted on consecutive edges, completing in
      // issue order with the descriptor pipe keeping each result on its own robId.
      val start = fpSeen.size
      val robs = (40 until 46).toList
      s.iOp #= DecOp.FPU
      s.iFpuOp #= OpFADD; s.iFpSrcKind #= FpSrcKind.FPREG; s.iFpSrcFmt #= 0
      s.iFpWideImm #= BigInt(0)
      s.iPFpSrcA #= 1; s.iPFpSrcB #= 2; s.iWritesFp #= true
      s.iPsrcA #= 0; s.iPsrcB #= 0; s.iPsrcC #= 0
      s.iPdstValid #= false; s.iWritesNzvc #= false
      s.iValid #= true
      for ((r, i) <- robs.zipWithIndex) {
        s.iRob #= r; s.iPFpDst #= 4 + i; s.iPFpccDst #= 4 + i
        sleep(1)
        assert(s.iReady.toBoolean && s.iFire.toBoolean,
          s"dense FP stream lost II=1 at rob=$r (cycle $cycle)")
        tick()
      }
      s.iValid #= false
      drainTo(start + robs.length, FpuCore.FixedLatency + 20, "dense FP stream")
      val got = fpSeen.slice(start, start + robs.length).toSeq
      assert(got.map(_.rob) == robs, s"dense FP stream reordered: ${got.map(_.rob)}")
      assert(got.map(_.cycle).sliding(2).forall { case Seq(a, b) => b == a + 1; case _ => true },
        s"dense FP completions were not consecutive: ${got.map(_.cycle)}")
      for ((r, i) <- got.zipWithIndex) {
        assert(r.data == Three, f"dense FP rob=${r.rob} result 0x${r.data}%020X, expected 3.0")
        assert(r.pdst == 4 + i, s"dense FP rob=${r.rob} routed to pdst=${r.pdst}")
      }
    }
  }

  test("FP lane is independent of the int lane: a MUL and an FADD complete the same cycle",
       VerilatorTest) {
    dut.doSim { d =>
      val h = new Harness(d); import h._
      boot()
      preloadFp(1, One); preloadFp(2, Two)
      preloadInt(20, BigInt(7)); preloadInt(21, BigInt(9))

      // The two lanes have DIFFERENT completion depths (FpuCore.FixedLatency + the FP
      // capture register, versus MulCore.Latency + the result FIFO + the shared int capture
      // register), and pinning the exact difference in a test would just encode today's
      // pipeline depths. Sweep the launch separation instead and require that at least one
      // separation puts both completions on the SAME cycle -- a real, non-vacuous witness
      // that survives either lane being re-timed -- while checking every separation's
      // results for loss or cross-talk.
      for ((lead, i) <- (0 until 16).zipWithIndex) {
        val fpRob = 32 + (i % 16)
        val fpBefore = fpSeen.size
        val intBefore = intSeen.size
        issue(rob = fpRob, opmode = OpFADD, kind = FpSrcKind.FPREG,
          pFpSrcA = 1, pFpSrcB = 2, pFpDst = 5, pFpccDst = 5, label = s"collision FADD lead=$lead")
        if (lead > 0) tick(lead)
        s.iOp #= DecOp.MUL
        s.iRob #= 63; s.iPsrcA #= 20; s.iPsrcB #= 21
        s.iPdst #= 22; s.iPdstValid #= true; s.iPNzvcDst #= 3; s.iWritesNzvc #= true
        s.iSize #= Size.LONG
        s.iValid #= true
        sleep(1)
        assert(s.iReady.toBoolean && s.iFire.toBoolean, s"collision MUL (lead=$lead) was not accepted")
        tick()
        s.iValid #= false
        s.iOp #= DecOp.FPU; s.iPdstValid #= false; s.iWritesNzvc #= false

        var guard = 0
        while ((fpSeen.size == fpBefore || intSeen.size == intBefore) && guard < 60) {
          tick(); guard += 1
        }
        tick(4)
        assert(fpSeen.size == fpBefore + 1,
          s"lead=$lead: FP lane produced ${fpSeen.size - fpBefore} completions, expected 1")
        assert(intSeen.size == intBefore + 1,
          s"lead=$lead: int lane produced ${intSeen.size - intBefore} completions, expected 1")
        assert(fpSeen.last.rob == fpRob && fpSeen.last.data == Three,
          s"lead=$lead: FP result lost/corrupted (rob=${fpSeen.last.rob})")
        assert(intSeen.last._2 == 63, s"lead=$lead: int result lost (rob=${intSeen.last._2})")
        assert(readFp(5) == Three, s"lead=$lead: FP PRF write lost")
        s.rdIntAddr #= 22; sleep(1)
        assert(s.rdIntData.toBigInt == BigInt(63), s"lead=$lead: int PRF write lost")
      }
      assert(sawCollision,
        "no launch separation ever put an int and an FP completion on the same cycle -- " +
        "the independence claim was never actually exercised")
    }
  }

  test("FP iterative lane: FDIV/FSQRT complete, and a FLUSHED FDIV is still acknowledged",
       VerilatorTest) {
    dut.doSim { d =>
      val h = new Harness(d); import h._
      boot()
      preloadFp(1, One); preloadFp(2, Two); preloadFp(3, Four)

      // ---- baseline: FDIV 1.0/2.0 = 0.5 on the single-context iterative lane ----
      var before = fpSeen.size
      issue(rob = 60, opmode = OpFDIV, kind = FpSrcKind.FPREG,
        pFpSrcA = 1, pFpSrcB = 2, pFpDst = 6, pFpccDst = 6, label = "FDIV")
      drainTo(before + 1, 200, "FDIV")
      assert(fpSeen.last.rob == 60, s"FDIV completed rob=${fpSeen.last.rob}")
      assert(fpSeen.last.data == Half, f"FDIV result 0x${fpSeen.last.data}%020X, expected 0.5")
      tick()
      assert(readFp(6) == Half, "FDIV FP PRF write did not land")
      assert(!s.fpIterBusyO.toBoolean, "iterative lane still busy after a completed FDIV")

      // ---- FSQRT 4.0 = 2.0 (the same lane, the other op) ----
      before = fpSeen.size
      issue(rob = 61, opmode = OpFSQRT, kind = FpSrcKind.FPREG,
        pFpSrcA = 3, pFpSrcB = 3, pFpDst = 7, pFpccDst = 7, label = "FSQRT")
      drainTo(before + 1, 200, "FSQRT")
      assert(fpSeen.last.data == Two, f"FSQRT(4.0) = 0x${fpSeen.last.data}%020X, expected 2.0")

      // ── THE HAZARD ──────────────────────────────────────────────────────────────
      // FpDivSqrtCore has NO flush input by design; its `done` is a LEVEL held until `ack`
      // and `busy` stays high across that hold. So a flush must NOT be allowed to make the
      // ack unreachable. The naive "clear the busy tracker on flush" would do exactly that
      // and wedge the engine for the rest of the program's execution. This asserts the
      // whole contract: no wrong-path writeback, the ack DOES eventually fire, and the lane
      // becomes genuinely reusable.
      before = fpSeen.size
      issue(rob = 62, opmode = OpFDIV, kind = FpSrcKind.FPREG,
        pFpSrcA = 1, pFpSrcB = 2, pFpDst = 8, pFpccDst = 8, label = "flushed FDIV")
      tick(10)
      assert(s.fpIterBusyO.toBoolean && s.coreIterBusy.toBoolean,
        "the FDIV under test was not actually in flight when the flush hit")
      s.iFlush #= true
      tick()
      s.iFlush #= false
      tick()          // the poke is sampled on the edge `tick()` above ends; observe after it
      assert(s.fpIterBusyO.toBoolean,
        "flush cleared the iterative busy tracker -- the eventual doneIter can then never " +
        "be acknowledged and FpDivSqrtCore wedges forever (the exact deadlock this guards)")
      assert(s.fpIterFlushedO.toBoolean,
        "flush did not poison the in-flight iterative context")

      // Watch for the acknowledge. It must arrive, and it must free BOTH the plugin's
      // tracker and FpDivSqrtCore's own busy state.
      var sawAck = false
      var guard = 0
      while (!sawAck && guard < 300) {
        tick()
        if (s.fpIterAckO.toBoolean) sawAck = true
        guard += 1
      }
      assert(sawAck,
        "the flushed FDIV was NEVER acknowledged -- FpDivSqrtCore is wedged in S_HOLD")
      tick(2)
      assert(!s.fpIterBusyO.toBoolean, "iterative busy tracker never cleared after the ack")
      assert(!s.coreIterBusy.toBoolean, "FpDivSqrtCore never returned to idle after the ack")
      assert(fpSeen.size == before,
        s"a flushed FDIV produced ${fpSeen.size - before} wrong-path completion(s)")
      assert(readFp(8) == BigInt(0), "a flushed FDIV wrote the FP register file")

      // ---- and the lane genuinely recovers: a NEW FDIV runs to completion ----
      before = fpSeen.size
      issue(rob = 63, opmode = OpFDIV, kind = FpSrcKind.FPREG,
        pFpSrcA = 3, pFpSrcB = 2, pFpDst = 9, pFpccDst = 9, label = "post-flush FDIV")
      drainTo(before + 1, 200, "post-flush FDIV")
      assert(fpSeen.last.rob == 63, s"post-flush FDIV completed rob=${fpSeen.last.rob}")
      assert(fpSeen.last.data == Two,
        f"post-flush FDIV(4.0/2.0) = 0x${fpSeen.last.data}%020X, expected 2.0")
      tick()
      assert(readFp(9) == Two, "post-flush FDIV FP PRF write did not land")

      // ── The other half of the iterative-lane handshake ──────────────────────────
      // The fixed lane cannot be back-pressured, so it wins the shared FP completion
      // register; the iterative lane must therefore be acknowledged ONLY on a cycle it
      // actually gets that register. Bury the FDIV's completion window under a dense
      // back-to-back FADD stream (doneFixed asserted on ~25 consecutive cycles) and require
      // the divide to still emerge, exactly once, with the right value: acknowledging
      // unconditionally would silently drop it, with no backpressure to recover it.
      before = fpSeen.size
      issue(rob = 33, opmode = OpFDIV, kind = FpSrcKind.FPREG,
        pFpSrcA = 1, pFpSrcB = 2, pFpDst = 10, pFpccDst = 10, label = "buried FDIV")
      tick(45)
      s.iOp #= DecOp.FPU
      s.iFpuOp #= OpFADD; s.iFpSrcKind #= FpSrcKind.FPREG; s.iFpSrcFmt #= 0
      s.iFpWideImm #= BigInt(0)
      s.iPFpSrcA #= 1; s.iPFpSrcB #= 2; s.iWritesFp #= true
      s.iPsrcA #= 0; s.iPsrcB #= 0; s.iPsrcC #= 0
      s.iPdstValid #= false; s.iWritesNzvc #= false
      s.iValid #= true
      val burialRobs = (0 until 30).toList
      for (r <- burialRobs) {
        s.iRob #= r; s.iPFpDst #= 11; s.iPFpccDst #= 11
        sleep(1)
        assert(s.iFire.toBoolean, s"burial FADD $r was not accepted")
        tick()
      }
      s.iValid #= false
      drainTo(before + 1 + burialRobs.length, 250, "buried FDIV + burial stream")
      val buried = fpSeen.slice(before, fpSeen.size).toSeq
      val divHits = buried.filter(_.rob == 33)
      assert(divHits.length == 1,
        s"the FDIV buried under a dense fixed stream completed ${divHits.length} times " +
        "(0 = it was acknowledged on a cycle the fixed lane owned the completion register, " +
        "and silently dropped)")
      assert(divHits.head.data == Half,
        f"buried FDIV result 0x${divHits.head.data}%020X, expected 0.5")
      assert(buried.count(_.rob != 33) == burialRobs.length,
        s"the burial stream lost completions: ${buried.count(_.rob != 33)}/${burialRobs.length}")
      assert(!s.fpIterBusyO.toBoolean, "iterative lane never freed after the buried FDIV")
    }
  }

  test("FP fixed lane: a flush kills in-flight work and lets the ROB ids be reused",
       VerilatorTest) {
    dut.doSim { d =>
      val h = new Harness(d); import h._
      boot()
      preloadFp(1, One); preloadFp(2, Two); preloadFp(3, Four)

      // Fill the whole fixed shadow pipe with FADDs, then flush mid-flight.
      s.iOp #= DecOp.FPU
      s.iFpuOp #= OpFADD; s.iFpSrcKind #= FpSrcKind.FPREG; s.iFpSrcFmt #= 0
      s.iFpWideImm #= BigInt(0)
      s.iPFpSrcA #= 1; s.iPFpSrcB #= 2; s.iWritesFp #= true
      s.iPdstValid #= false; s.iWritesNzvc #= false
      s.iValid #= true
      for (i <- 0 until 6) {
        s.iRob #= 40 + i; s.iPFpDst #= 4 + i; s.iPFpccDst #= 4 + i
        sleep(1)
        assert(s.iFire.toBoolean, s"pre-flush FADD $i was not accepted")
        tick()
      }
      s.iValid #= false
      val afterIssue = fpSeen.size
      s.iFlush #= true
      tick()
      s.iFlush #= false
      tick(FpuCore.FixedLatency + 8)
      assert(fpSeen.size == afterIssue,
        s"${fpSeen.size - afterIssue} flushed FP result(s) completed after the squash")

      // Reuse the very same robIds/destinations with different operands: nothing from
      // before the flush may appear.
      val start = fpSeen.size
      s.iValid #= true
      s.iPFpSrcA #= 3; s.iPFpSrcB #= 2      // 4.0 + 2.0 = 6.0, distinct from 3.0
      for (i <- 0 until 6) {
        s.iRob #= 40 + i; s.iPFpDst #= 4 + i; s.iPFpccDst #= 4 + i
        sleep(1)
        assert(s.iFire.toBoolean, s"post-flush FADD $i was not accepted")
        tick()
      }
      s.iValid #= false
      drainTo(start + 6, FpuCore.FixedLatency + 20, "post-flush reuse")
      val got = fpSeen.slice(start, start + 6).toSeq
      assert(got.map(_.rob) == (40 until 46).toList, s"post-flush reuse order: ${got.map(_.rob)}")
      got.foreach { g =>
        assert(g.data == Six, f"post-flush rob=${g.rob} result 0x${g.data}%020X, expected 6.0")
      }
      tick(6)
      assert(fpSeen.size == start + 6, "a killed pre-flush FADD completed after ROB-id reuse")
    }
  }

  // ════════════════════════════════════════════════════════════════════════════════════
  // Task 14b's INT-lane narrowing converter (`DecOp.FPSTORECVT`, the `FMOVE FPn,<ea>`
  // store direction). These sit here rather than in `FpuLockStepSpec` because neither is
  // refereeable by Musashi: the flush contract is a microarchitectural property with no
  // architectural trace at all, and the register-direct `.W`/`.B` partial-register merge
  // is a CONFIRMED oracle divergence (Divergence Register D11 -- Musashi's
  // `WRITE_EA_16`/`WRITE_EA_8` zero-extend over the whole register).
  // ════════════════════════════════════════════════════════════════════════════════════

  /** -1234.0 as 80-bit extended: 1234 = 0b100_1101_0010 (11 bits) => significand
    * 1234 << 53 = 0x9A40000000000000, exponent 10 => biased 0x3FFF + 10 = 0x4009.
    * `floatx80_to_int32` of it is -1234 = 0xFFFFFB2E, whose low word is 0xFB2E and low
    * byte 0x2E -- three distinct nibbles patterns, so a wrong-width merge cannot alias. */
  private val Minus1234 = ext(true, 0x4009, BigInt("9A40000000000000", 16))

  test("int lane FPSTORECVT: a flush on ANY cycle of the two-cycle conversion leaves the lane " +
       "clean -- no leaked `flushed`, no stuck `fpCvtWait`, no stale converter read",
       VerilatorTest) {
    dut.doSim { d =>
      val h = new Harness(d); import h._
      boot()

      // ── THE HAZARD (Task 14b review, Critical #1) ────────────────────────────────────
      // `FpNarrowPack` carries one registered stage, so an `FPSTORECVT` occupies S1 for TWO
      // cycles and is the FIRST S1 arm in this EU with a "continue, don't capture" state.
      // Every other arm (CHK / CMP2 / FPCTRLRD / DIVREM / ...) captures unconditionally
      // whenever `s1Valid` is set -- including on a flushed cycle, where the capture is a
      // deliberate DISCARD that also consumes the `flushed` latch. A conversion that simply
      // fell through to its continuation arm on a flushed cycle would:
      //   1. leave `flushed` set with no owner. The NEXT legacy-lane op -- a correct-path
      //      one -- inherits it via `legacyResult.flushed`, so `compFlushed` suppresses its
      //      completion/writeback/wakeup and its ROB entry never retires: a real deadlock,
      //      reachable from an ordinary branch mispredict.
      //   2. leave `fpCvtWait` stuck at 1 (the FSM's `:= True` is a LATER assignment in the
      //      same clocked block than the flush-clear, so it wins). The next conversion then
      //      takes the "read the result" branch on its FIRST S1 cycle and samples the
      //      converter's output register, which was clocked from the PREVIOUS cycle's
      //      source -- a STALE value, on its way to memory.
      // The sweep below lands the flush on every cycle of the conversion (delay 0 is the
      // first S1 cycle, delay 1 the second), and asserts all three consequences directly:
      // the whitebox latch state, a following CHK actually retiring, and a following
      // conversion producing ITS OWN value rather than the previous one's.
      preloadFp(1, One)       // 1.0 -> Long 1  (the flushed conversion's source)
      preloadFp(2, Five)      // 5.0 -> Long 5  (the post-flush conversion's source)
      preloadInt(30, BigInt(3))     // CHK.W Dn    = 3
      preloadInt(31, BigInt(10))    // CHK.W bound = 10 -> in bounds, completes, no fault

      for (delay <- 0 to 4) {
        val before = intSeen.size
        issueInt(rob = 20, op = DecOp.FPSTORECVT, pFpSrcA = 1, pdst = 40, pdstValid = true,
          label = s"flushed FPSTORECVT (delay=$delay)")
        if (delay > 0) tick(delay)
        s.iFlush #= true
        tick()
        s.iFlush #= false
        tick(8)

        assert(!s.cvtWaitO.toBoolean,
          s"delay=$delay: `fpCvtWait` is STILL SET after the flush -- the next conversion " +
          "would read the converter's stale output register on its first S1 cycle")
        assert(!s.cvtFlushedO.toBoolean,
          s"delay=$delay: the legacy lane's `flushed` latch LEAKED past the flush -- the " +
          "next correct-path CPLX op will have its completion suppressed (ROB deadlock)")
        assert(!s.s1ValidO.toBoolean, s"delay=$delay: s1Valid still set after the flush")
        assert(intSeen.size - before <= 1,
          s"delay=$delay: the flushed conversion completed more than once")

        // (1) DEADLOCK CHECK: an ordinary legacy-lane op issued after the flush must retire.
        val chkBefore = intSeen.size
        issueInt(rob = 21, op = DecOp.CHK, size = Size.WORD, psrcA = 30, psrcB = 31,
          pNzvcDst = 5, writesNzvc = true, label = s"post-flush CHK (delay=$delay)")
        drainIntTo(chkBefore + 1, 40,
          s"delay=$delay: the CHK issued after a flushed FPSTORECVT")
        assert(intSeen.last._2 == 21,
          s"delay=$delay: post-flush CHK completed with robId ${intSeen.last._2}, expected 21")
        assert(!s.euFaultV.toBoolean, s"delay=$delay: in-bounds CHK raised a fault")

        // (2) STALE-READ CHECK: a fresh conversion must convert ITS OWN source (FP2 = 5.0),
        //     not the flushed one's (FP1 = 1.0) or the CHK's unused FP0 (= 0).
        val cvtBefore = intSeen.size
        issueInt(rob = 22, op = DecOp.FPSTORECVT, pFpSrcA = 2, pdst = 41, pdstValid = true,
          label = s"post-flush FPSTORECVT (delay=$delay)")
        drainIntTo(cvtBefore + 1, 40,
          s"delay=$delay: the FPSTORECVT issued after a flushed FPSTORECVT")
        assert(intSeen.last._2 == 22,
          s"delay=$delay: post-flush FPSTORECVT completed with robId ${intSeen.last._2}")
        tick(2)
        assert(readInt(41) == BigInt(5),
          f"delay=$delay: post-flush conversion wrote 0x${readInt(41)}%08X, expected 5 " +
          "(a 1 or 0 here is the STALE converter-register read)")
      }
    }
  }

  test("int lane FPSTORECVT: register-direct .W/.B write the converted low bits and PRESERVE " +
       "the rest of Dn (partial-register merge, Divergence D11)", VerilatorTest) {
    dut.doSim { d =>
      val h = new Harness(d); import h._
      boot()

      // `FMOVE.W FPn,Dn` / `FMOVE.B FPn,Dn` are ordinary 68k partial-register writes: only
      // the low word/byte of Dn changes. Musashi's `WRITE_EA_16`/`WRITE_EA_8` assign
      // `REG_D[reg] = data` from a uint16/uint8 and therefore ZERO-EXTEND over the whole
      // register, so this behaviour is deliberately excluded from `FpuLockStepSpec` (D11)
      // and has to be proven here instead. The merge source is the destination register's
      // own old value, read through `psrcA`. Memory rows are `Size.LONG` by construction
      // (their own `MStore` row carries the real access size), so the merge is unreachable
      // for them -- the LONG case below pins that.
      preloadFp(3, Minus1234)                     // -1234.0 -> int32 0xFFFFFB2E
      preloadInt(32, BigInt("AAAA5555", 16))      // the old Dn every merge preserves

      case class Merge(size: SpinalEnumElement[Size.type], pdst: Int, want: BigInt, label: String)
      val cases = Seq(
        Merge(Size.LONG, 33, BigInt("FFFFFB2E", 16), "FMOVE.L FP3,Dn -- full 32-bit write"),
        Merge(Size.WORD, 34, BigInt("AAAAFB2E", 16), "FMOVE.W FP3,Dn -- low word merged"),
        Merge(Size.BYTE, 35, BigInt("AAAA552E", 16), "FMOVE.B FP3,Dn -- low byte merged"))

      for (c <- cases) {
        val before = intSeen.size
        issueInt(rob = 30, op = DecOp.FPSTORECVT, size = c.size, psrcA = 32, pFpSrcA = 3,
          pdst = c.pdst, pdstValid = true, label = c.label)
        drainIntTo(before + 1, 40, c.label)
        tick(2)
        val got = readInt(c.pdst)
        assert(got == c.want, f"${c.label}: int PRF[${c.pdst}] = 0x$got%08X, expected 0x${c.want}%08X")
      }
    }
  }
}
