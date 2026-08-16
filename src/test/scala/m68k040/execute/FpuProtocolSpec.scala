package m68k040.execute

import m68k040.{M68kSim, VerilatorTest}
import m68k040.decode.{DecOp, EaAuto, FpSrcKind, SysKind}
import m68k040.execute.fpu.{FpDivSqrtCore, FpuCore}
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

/** PROTOCOL-level tests for the CPLX EU's FP writeback lane (`DivEuPlugin`'s FP lane).
  *
  * The 2026-08-09 hardware design doc, section 9, states that the inherited instruction
  * corpus cannot substitute for protocol-level proof, and enumerates six requirements. They
  * are reproduced here verbatim, and each is implemented below as a real sim test:
  *
  *   - drive at least eight independent operations into each fixed lane on consecutive
  *     cycles, require eight consecutive accepts and results at the documented fixed
  *     latency, and check exact ROB/destination/FPCC association;
  *   - prove at least four fixed operations are simultaneously in flight, with no duplicate
  *     or missing PRF write, wakeup, status observation, or completion;
  *   - collide a fixed-pipeline result with FDIV/FSQRT completion and prove the result
  *     arbiter retains both exactly once;
  *   - hold a request valid while result capacity is unavailable and prove stable payload
  *     plus exactly one acceptance when credit returns;
  *   - flush a dense fixed pipeline and an active iterative operation, immediately reuse ROB
  *     and physical destinations, and prove no stale side effect survives; and
  *   - mutation-check the dense-start, collision, and flush/reuse tests so a return to a
  *     busy-gated singleton or global flush latch fails visibly.
  *
  * MUTATION GATE (the sixth requirement, and this project's standing mutation-proof rule).
  * The dense-start/4-in-flight, collision and flush/reuse tests were each proven to FAIL
  * against a deliberately re-broken `DivEuPlugin.scala` before the mutation was reverted.
  * The exact mutations, and the observed failure text, are recorded in this task's report
  * (`.superpowers/sdd/2026-08-15-fpu-fpsp-implementation-plan/task-15-report.md`). Do NOT
  * weaken these tests to make a future refactor convenient -- their whole value is that they
  * have been shown capable of failing.
  *
  * SCOPE, versus the sibling `FpuEuIntegrationSpec`. That spec is about DATAPATH correctness
  * (the nine `fpSrcKind` conversions, FMOVECR's ROM, subnormals/NaNs, the int-lane narrowing
  * converter). This spec is about the ISSUE/COMPLETION PROTOCOL: acceptance cadence,
  * simultaneous occupancy, arbitration between the two FP result sources, backpressure on
  * the one lane that has any, and flush/reuse. The two overlap only incidentally.
  */
class FpuProtocolSpec extends AnyFunSuite {

  // ── 80-bit extended literals (sign ## 15-bit biased exponent ## 64-bit significand) ──
  private def ext(neg: Boolean, exp: Int, sig: BigInt): BigInt =
    (if (neg) BigInt(1) << 79 else BigInt(0)) | (BigInt(exp) << 64) | sig
  private val Msb      = BigInt(1) << 63
  private val Zero     = BigInt(0)
  private val Half     = ext(false, 0x3FFE, Msb)
  private val One      = ext(false, 0x3FFF, Msb)
  private val Two      = ext(false, 0x4000, Msb)
  private val Three    = ext(false, 0x4000, BigInt("C000000000000000", 16))
  private val Four     = ext(false, 0x4001, Msb)
  private val Five     = ext(false, 0x4001, BigInt("A000000000000000", 16))
  private val Six      = ext(false, 0x4001, BigInt("C000000000000000", 16))
  private val Seven    = ext(false, 0x4001, BigInt("E000000000000000", 16))
  private val MinusOne = ext(true,  0x3FFF, Msb)
  private val MinusTwo = ext(true,  0x4000, Msb)
  private val PosInf   = ext(false, 0x7FFF, Msb)

  // FPCC internal layout (2026-08-09 spec section 8): bit0=N, bit1=Z, bit2=I, bit3=NaN.
  private val CcNone = 0
  private val CcN    = 1
  private val CcZ    = 2
  private val CcI    = 4

  // FP opmodes (raw extension-word [6:0]).
  private val OpFSQRT = 0x04
  private val OpFDIV  = 0x20
  private val OpFADD  = 0x22

  /** Physical FP source registers preloaded once per test by `preloadOperands`. */
  private val POne = 1; private val PTwo = 2; private val PFour = 3
  private val PMinusOne = 4; private val PHalf = 5; private val PThree = 6
  /** +0.0, preloaded separately (not by `preloadOperands`) only by the tests that need a
    * genuine divide-by-zero divisor (F1 fix, Task 15 review). */
  private val PZero = 7

  /** One FP operation under test: which two physregs it reads and what it must produce. */
  private case class Op(rob: Int, opmode: Int, a: Int, b: Int, pdst: Int, pcc: Int,
                        want: BigInt, cc: Int, label: String)

  private def fadd(rob: Int, a: Int, b: Int, pdst: Int, want: BigInt, cc: Int, label: String) =
    Op(rob, OpFADD, a, b, pdst, pdst, want, cc, label)

  /** Test-only source/sink plugin around the real PRFs and the `DivEuService` ports.
    *
    * Observation strategy (this is the point the Task 15 brief could not settle in advance):
    * the landed FP lane exposes NO single `fpWbObs`-style bundle. It exposes the four real
    * external service ports (`fpCompletion`/`fpWakeup`/`fpccWakeup`/`fpFault`), the two real
    * PRF write ports (`divEu.fpW`/`divEu.fpccW`), the FPSR accrual Flow, and a set of
    * `simPublic()` internals on `divEu.logic`. That is strictly MORE observation than a
    * single tap would give -- in particular it lets this spec check the PRF write ports
    * INDEPENDENTLY of the wakeups rather than inferring one from the other -- so nothing was
    * added to the production RTL for this task. `FpuEuIntegrationSpec` already reads
    * `divEu.logic.*` the same way; this file only widens the set. */
  class Src extends FiberPlugin {
    var preFp:  RegFileWritePort = null
    var rdFp:   RegFileReadPort  = null
    var rdFpcc: RegFileReadPort  = null

    during setup {
      preFp  = host[FpRegFileService].newWrite(latency = 1, sharingKey = "fpProtoTestFp")
      rdFp   = host[FpRegFileService].newRead(forceNoBypass = true)
      rdFpcc = host[FpccRegFileService].newRead(forceNoBypass = true)
    }

    val logic = during build new Area {
      val eu    = host[DivEuService]
      val divEu = host[DivEuPlugin]

      // ---- issue-side stimulus ----
      val iValid     = in Bool ()
      val iRob       = in UInt (6 bits)
      val iFlush     = in Bool ()
      val iFpuOp     = in Bits (7 bits)
      val iPFpSrcA   = in UInt (4 bits)
      val iPFpSrcB   = in UInt (4 bits)
      val iPFpDst    = in UInt (4 bits)
      val iWritesFp  = in Bool ()
      val iPFpccDst  = in UInt (4 bits)

      val ctx = IqContext()
      val uop = ctx.uop
      // Drive EVERY RenamedUop field: a newly-consumed field must fail deterministically
      // rather than turn this into a netlist-dependent test (CplxMulPipelineSpec's rule).
      uop.valid := iValid
      uop.pc := U(0x2000, 32 bits); uop.nextPc := U(0x2004, 32 bits)
      uop.op := DecOp.FPU
      uop.cluster := Cluster.CPLX
      uop.size := Size.LONG
      uop.memOp := MemOp.NONE
      uop.useImm := False; uop.imm := 0
      uop.isBranch := False; uop.cond := 0; uop.branchDisp := 0
      uop.ibranch := False; uop.anInc := 0; uop.stkPush := False
      uop.eaAuto := EaAuto.NONE; uop.eaDelta := 0
      uop.ccrRestore := False; uop.toCcr := False; uop.unimplemented := False
      uop.faulted := False; uop.faultVector := 0; uop.isRte := False
      uop.faultUsesNextPc := False; uop.isCondTrap := False
      uop.faultAddr := 0; uop.sswInstr := False; uop.faultAtc := True
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
      uop.psrcA := 0; uop.psrcAValid := True
      uop.psrcB := 0; uop.psrcBValid := True
      uop.psrcC := 0; uop.psrcCValid := True
      uop.pdst := 0; uop.pdstValid := False; uop.pdstOld := 0
      uop.pNzvcSrc := 0; uop.readsNzvc := False
      uop.pNzvcDst := 0; uop.writesNzvc := False; uop.pNzvcOld := 0
      uop.pXSrc := 0; uop.readsX := False
      uop.pXDst := 0; uop.writesX := False; uop.pXOld := 0
      uop.pFpSrcA := iPFpSrcA; uop.psrcAFpValid := True
      uop.pFpSrcB := iPFpSrcB; uop.psrcBFpValid := True
      uop.pFpDst := iPFpDst; uop.pFpDstValid := iWritesFp; uop.pFpOld := 0; uop.fpDstArch := 0
      uop.pFpccSrc := 0; uop.readsFpcc := False
      uop.pFpccDst := iPFpccDst; uop.writesFpcc := True; uop.pFpccOld := 0
      uop.fpuOp := iFpuOp
      uop.fpSrcKind := FpSrcKind.FPREG
      uop.fpSrcFmt := 0
      uop.fpWideImm := 0

      ctx.robId := iRob
      eu.issue.valid := iValid
      eu.issue.payload := ctx
      eu.cplxFlush := iFlush

      val iReady = out Bool (); iReady := eu.issue.ready
      val iFire  = out Bool (); iFire  := eu.issue.fire

      // ---- preload / readback ----
      val preFpValid = in Bool (); val preFpAddr = in UInt (4 bits)
      val preFpData  = in Bits (80 bits)
      preFp.valid := preFpValid; preFp.address := preFpAddr; preFp.data := preFpData
      val rdFpAddr = in UInt (4 bits); val rdFpData = out Bits (80 bits)
      rdFp.addr := rdFpAddr; rdFpData := rdFp.data
      val rdFpccAddr = in UInt (4 bits); val rdFpccData = out Bits (4 bits)
      rdFpcc.addr := rdFpccAddr; rdFpccData := rdFpcc.data

      // ---- the four external FP-lane service ports ----
      val fpCValid  = out Bool ();       fpCValid  := eu.fpCompletion.valid
      val fpCRob    = out UInt (6 bits); fpCRob    := eu.fpCompletion.payload
      val fpWakeV   = out Bool ();       fpWakeV   := eu.fpWakeup.valid
      val fpWakeP   = out UInt (4 bits); fpWakeP   := eu.fpWakeup.payload
      val fpccWakeV = out Bool ();       fpccWakeV := eu.fpccWakeup.valid
      val fpccWakeP = out UInt (4 bits); fpccWakeP := eu.fpccWakeup.payload
      val fpFaultV  = out Bool ();       fpFaultV  := eu.fpFault.valid

      // ---- the REAL physical register write ports, observed independently of the wakeups.
      // `fpWakeupPort.valid := fpW.valid` inside the EU, so inferring one from the other
      // would make "no duplicate or missing PRF write" a tautology. These are the actual
      // write ports into the FP and FPCC register files.
      val fpWV   = out Bool ();        fpWV   := divEu.fpW.valid
      val fpWA   = out UInt (4 bits);  fpWA   := divEu.fpW.address
      val fpWD   = out Bits (80 bits); fpWD   := divEu.fpW.data
      val fpccWV = out Bool ();        fpccWV := divEu.fpccW.valid
      val fpccWA = out UInt (4 bits);  fpccWA := divEu.fpccW.address
      val fpccWD = out Bits (4 bits);  fpccWD := divEu.fpccW.data

      // ---- FPSR exception-status accrual (the lane's "status observation") ----
      val excV = out Bool ();       excV := divEu.fpExcAccrualPort.valid
      val excD = out Bits (8 bits); excD := divEu.fpExcAccrualPort.payload

      // ---- whitebox: the two completion SOURCES the FP arbiter chooses between ----
      // `fpFixedDone`/`fpIterDone` are the arbiter's own two inputs. Sampling both is what
      // makes the collision test's witness real rather than assumed: without them the test
      // could only hope a chosen launch separation actually collided.
      val fixedDone = out Bool (); fixedDone := divEu.logic.fpFixedDone
      val iterDone  = out Bool (); iterDone  := divEu.logic.fpIterDone
      val iterTake  = out Bool (); iterTake  := divEu.logic.fpIterTake
      val iterBusy  = out Bool (); iterBusy  := divEu.logic.fpIterBusy
      val iterPoison= out Bool (); iterPoison:= divEu.logic.fpIterFlushed
      // Simultaneous occupancy of the fixed shadow pipe: the direct witness for the
      // "at least four fixed operations simultaneously in flight" requirement.
      val inFlight  = out UInt (5 bits)
      inFlight := CountOne(divEu.logic.fpFixedCtxValid.asBits).resized

      // ---- the issue payload as the EU actually sees it (payload-stability check) ----
      val obsRob   = out UInt (6 bits); obsRob   := eu.issue.payload.robId
      val obsOp    = out Bits (7 bits); obsOp    := eu.issue.payload.uop.fpuOp
      val obsFpDst = out UInt (4 bits); obsFpDst := eu.issue.payload.uop.pFpDst
      val obsSrcA  = out UInt (4 bits); obsSrcA  := eu.issue.payload.uop.pFpSrcA
      val obsSrcB  = out UInt (4 bits); obsSrcB  := eu.issue.payload.uop.pFpSrcB
    }
  }

  class Dut extends Component {
    val db     = new Database
    val host   = db on (new PluginHost)
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val rfFp   = new RegFilePluginFp
    val rfFpcc = new RegFilePluginFpcc
    val eu     = new DivEuPlugin
    val src    = new Src
    db.on { host.asHostOf(Seq[FiberPlugin](rfInt, rfNzvc, rfFp, rfFpcc, eu, src)) }
  }

  private lazy val dut = M68kSim().withVerilator.compile(new Dut)

  private case class Comp(cycle: Int, rob: Int)
  private case class Wr(cycle: Int, addr: Int, data: BigInt)

  /** Per-cycle recorder. Every FP-lane side effect this spec reasons about is logged on
    * EVERY sampled cycle, so "exactly once" and "never" are checked against a complete
    * trace rather than against a spot read at a chosen moment. */
  private class Harness(d: Dut) {
    val cd = d.clockDomain
    val s  = d.src.logic
    var cycle = 0

    val comps      = ArrayBuffer[Comp]()
    val fpWrites   = ArrayBuffer[Wr]()
    val fpccWrites = ArrayBuffer[Wr]()
    val wakeups    = ArrayBuffer[(Int, Int)]()
    val fpccWakes  = ArrayBuffer[(Int, Int)]()
    val fires      = ArrayBuffer[(Int, Int)]()      // (cycle, robId presented)
    val collisions = ArrayBuffer[Int]()             // cycles where BOTH arbiter inputs fired
    val accruals   = ArrayBuffer[(Int, Int)]()

    def idle(): Unit = {
      s.iValid #= false
      s.iRob #= 0; s.iFlush #= false
      s.iFpuOp #= OpFADD
      s.iPFpSrcA #= 0; s.iPFpSrcB #= 0; s.iPFpDst #= 0; s.iWritesFp #= true; s.iPFpccDst #= 0
      s.preFpValid #= false; s.preFpAddr #= 0; s.preFpData #= BigInt(0)
      s.rdFpAddr #= 0; s.rdFpccAddr #= 0
    }

    def sample(): Unit = {
      // Read every tap into a local first: scalatest renders the ASSERTED EXPRESSION in the
      // failure text, and a raw `s.foo.toBoolean` expands into several hundred characters of
      // Fiber Handle type, burying the message that actually explains the failure.
      val faulted = s.fpFaultV.toBoolean
      assert(!faulted,
        s"unexpected FP enabled-trap escalation at cycle $cycle (FPCR enable byte is all-clear)")
      if (s.fpCValid.toBoolean) comps += Comp(cycle, s.fpCRob.toInt)
      if (s.fpWV.toBoolean)     fpWrites += Wr(cycle, s.fpWA.toInt, s.fpWD.toBigInt)
      if (s.fpccWV.toBoolean)   fpccWrites += Wr(cycle, s.fpccWA.toInt, s.fpccWD.toBigInt)
      if (s.fpWakeV.toBoolean)  wakeups += ((cycle, s.fpWakeP.toInt))
      if (s.fpccWakeV.toBoolean) fpccWakes += ((cycle, s.fpccWakeP.toInt))
      if (s.excV.toBoolean)     accruals += ((cycle, s.excD.toInt))
      if (s.fixedDone.toBoolean && s.iterDone.toBoolean) collisions += cycle
      if (s.iFire.toBoolean)    fires += ((cycle, s.obsRob.toInt))
      // A wakeup with no completion behind it is an orphan broadcast: a consumer would be
      // released against a physreg that was never written.
      val orphanWakeup = !s.fpCValid.toBoolean && (s.fpWakeV.toBoolean || s.fpccWakeV.toBoolean)
      assert(!orphanWakeup, s"orphan FP wakeup at cycle $cycle with no completion")
    }

    /** `sleep(1)` after the edge so COMBINATIONAL taps (`iFire`, `iReady`, `fixedDone`)
      * are settled before `sample()` reads them; registered taps are unaffected. */
    def tick(n: Int = 1): Unit =
      for (_ <- 0 until n) { cd.waitSampling(); sleep(1); cycle += 1; sample() }

    def boot(): Unit = { cd.forkStimulus(10); idle(); tick(80); clearLog() }

    def clearLog(): Unit = {
      comps.clear(); fpWrites.clear(); fpccWrites.clear()
      wakeups.clear(); fpccWakes.clear(); fires.clear(); collisions.clear(); accruals.clear()
    }

    def preloadFp(addr: Int, v: BigInt): Unit = {
      s.preFpValid #= true; s.preFpAddr #= addr; s.preFpData #= v
      tick(); s.preFpValid #= false; tick()
    }
    def preloadOperands(): Unit = {
      preloadFp(POne, One); preloadFp(PTwo, Two); preloadFp(PFour, Four)
      preloadFp(PMinusOne, MinusOne); preloadFp(PHalf, Half); preloadFp(PThree, Three)
    }
    def readFp(addr: Int): BigInt = { s.rdFpAddr #= addr; sleep(1); s.rdFpData.toBigInt }
    def readFpcc(addr: Int): Int  = { s.rdFpccAddr #= addr; sleep(1); s.rdFpccData.toInt }

    /** Present one FP uop on the issue port; leaves `iValid` asserted. */
    def present(o: Op): Unit = {
      s.iRob #= o.rob; s.iFpuOp #= o.opmode
      s.iPFpSrcA #= o.a; s.iPFpSrcB #= o.b
      s.iPFpDst #= o.pdst; s.iPFpccDst #= o.pcc; s.iWritesFp #= true
      s.iValid #= true
    }

    /** Require the currently-presented uop to be accepted on the NEXT edge, and return the
      * (post-edge) cycle index of that accepting edge. This is the cadence assertion the
      * dense-fill and 4-in-flight requirements are really about. */
    def acceptNow(label: String): Int = {
      sleep(1)
      val accepted = s.iReady.toBoolean && s.iFire.toBoolean
      assert(accepted,
        s"$label was not accepted on its presented cycle (cycle $cycle, " +
        s"ready=${s.iReady.toBoolean}) -- the fixed FP lane lost II=1")
      tick()
      cycle
    }

    /** Tick until the presented uop is accepted (used only where a stall is EXPECTED). */
    def acceptWhenReady(label: String, budget: Int): Int = {
      var guard = 0
      sleep(1)
      while (!(s.iReady.toBoolean && s.iFire.toBoolean) && guard < budget) {
        tick(); sleep(1); guard += 1
      }
      val accepted = s.iReady.toBoolean && s.iFire.toBoolean
      assert(accepted, s"$label was never accepted in $budget cycles")
      tick()
      cycle
    }

    /** Tick until `n` completions have been seen, WITHOUT asserting. Used where a dedicated
      * downstream assertion can explain the shortfall far better than a generic timeout. */
    def waitFor(n: Int, budget: Int): Unit = {
      var guard = 0
      while (comps.size < n && guard < budget) { tick(); guard += 1 }
    }

    def drainTo(n: Int, budget: Int, label: String): Unit = {
      var guard = 0
      while (comps.size < n && guard < budget) { tick(); guard += 1 }
      assert(comps.size == n,
        s"$label observed ${comps.size}/$n FP completions after $guard cycles " +
        s"(robIds seen: ${comps.map(_.rob).mkString(",")})")
    }

    /** The shared "exactly once, correctly routed, nothing extra" checker: every one of the
      * five observable side effects (completion, FP PRF write, FPCC PRF write, FP wakeup,
      * FPCC wakeup) must appear EXACTLY once per op and never for anything else. */
    def checkExactlyOnce(ops: Seq[Op], label: String): Unit = {
      assert(comps.size == ops.size,
        s"$label: ${comps.size} completions for ${ops.size} ops -- " +
        s"${comps.map(_.rob).mkString(",")}")
      assert(comps.map(_.rob).toList == ops.map(_.rob).toList,
        s"$label: completion robId order ${comps.map(_.rob).mkString(",")} != " +
        s"issue order ${ops.map(_.rob).mkString(",")}")
      assert(fpWrites.size == ops.size,
        s"$label: ${fpWrites.size} FP PRF writes for ${ops.size} ops (duplicate or missing)")
      assert(fpccWrites.size == ops.size,
        s"$label: ${fpccWrites.size} FPCC PRF writes for ${ops.size} ops")
      assert(wakeups.size == ops.size, s"$label: ${wakeups.size} FP wakeups for ${ops.size} ops")
      assert(fpccWakes.size == ops.size, s"$label: ${fpccWakes.size} FPCC wakeups for ${ops.size} ops")
      for ((o, i) <- ops.zipWithIndex) {
        val w = fpWrites(i)
        assert(w.addr == o.pdst,
          s"$label: ${o.label} FP PRF write went to physreg ${w.addr}, expected ${o.pdst}")
        assert(w.data == o.want,
          f"$label: ${o.label} wrote 0x${w.data}%020X to FP[${w.addr}], expected 0x${o.want}%020X")
        val c = fpccWrites(i)
        assert(c.addr == o.pcc,
          s"$label: ${o.label} FPCC write went to ${c.addr}, expected ${o.pcc}")
        assert(c.data == BigInt(o.cc),
          s"$label: ${o.label} FPCC = ${c.data}, expected ${o.cc}")
        assert(wakeups(i)._2 == o.pdst,
          s"$label: ${o.label} FP wakeup broadcast physreg ${wakeups(i)._2}, expected ${o.pdst}")
        assert(fpccWakes(i)._2 == o.pcc,
          s"$label: ${o.label} FPCC wakeup broadcast ${fpccWakes(i)._2}, expected ${o.pcc}")
        // The completion, both PRF writes and both wakeups are one atomic event: any skew
        // between them is a routing bug even if each individual list looks right.
        assert(w.cycle == comps(i).cycle && c.cycle == comps(i).cycle &&
               wakeups(i)._1 == comps(i).cycle && fpccWakes(i)._1 == comps(i).cycle,
          s"$label: ${o.label} side effects were not simultaneous " +
          s"(completion@${comps(i).cycle} fpW@${w.cycle} fpccW@${c.cycle} " +
          s"wake@${wakeups(i)._1} ccWake@${fpccWakes(i)._1})")
      }
    }

    def readBackAll(ops: Seq[Op], label: String): Unit = {
      for (o <- ops) {
        val v = readFp(o.pdst)
        assert(v == o.want,
          f"$label: FP PRF[${o.pdst}] = 0x$v%020X after ${o.label}, expected 0x${o.want}%020X")
        assert(readFpcc(o.pcc) == o.cc,
          s"$label: FPCC PRF[${o.pcc}] = ${readFpcc(o.pcc)} after ${o.label}, expected ${o.cc}")
      }
    }
  }

  /** Eight independent fixed-lane operations, each with its own source pair, destination,
    * FPCC destination, result value and FPCC value. Deliberately NOT eight copies of one
    * op: identical results cannot detect cross-talk between descriptors. */
  private def eightOps(robBase: Int, pdstBase: Int): Seq[Op] = Seq(
    fadd(robBase + 0, POne,      PTwo,      pdstBase + 0, Three,    CcNone, "1.0+2.0"),
    fadd(robBase + 1, POne,      PMinusOne, pdstBase + 1, Zero,     CcZ,    "1.0+(-1.0)"),
    fadd(robBase + 2, PFour,     PTwo,      pdstBase + 2, Six,      CcNone, "4.0+2.0"),
    fadd(robBase + 3, PMinusOne, PMinusOne, pdstBase + 3, MinusTwo, CcN,    "(-1.0)+(-1.0)"),
    fadd(robBase + 4, PTwo,      PTwo,      pdstBase + 4, Four,     CcNone, "2.0+2.0"),
    fadd(robBase + 5, POne,      PFour,     pdstBase + 5, Five,     CcNone, "1.0+4.0"),
    fadd(robBase + 6, PHalf,     PHalf,     pdstBase + 6, One,      CcNone, "0.5+0.5"),
    fadd(robBase + 7, PThree,    PFour,     pdstBase + 7, Seven,    CcNone, "3.0+4.0"))

  // ══════════════════════════════════════════════════════════════════════════════════════
  // Requirement 1: eight independent ops, consecutive accepts, documented fixed latency,
  //                exact ROB/destination/FPCC association.
  // ══════════════════════════════════════════════════════════════════════════════════════

  test("FP fixed lane: 8 independent ops accepted on 8 consecutive cycles, each completing " +
       "at the documented FpuCore.FixedLatency with exact ROB/dest/FPCC association",
       VerilatorTest) {
    dut.doSim { d =>
      val h = new Harness(d); import h._
      boot()
      preloadOperands()
      clearLog()

      val ops = eightOps(robBase = 40, pdstBase = 8)
      val acceptCycles = ArrayBuffer[Int]()
      for (o <- ops) {
        present(o)
        acceptCycles += acceptNow(s"dense-8 ${o.label} (rob=${o.rob})")
      }
      s.iValid #= false

      // (a) EIGHT CONSECUTIVE ACCEPTS. `acceptNow` already required each accept on its
      //     presented cycle; this pins that the accepting edges really were adjacent, which
      //     is the property a busy-gated singleton destroys.
      assert((1 until acceptCycles.size).forall(i => acceptCycles(i) == acceptCycles(i - 1) + 1),
        s"the 8 accepts were not on consecutive cycles: ${acceptCycles.mkString(",")}")
      assert(fires.size == 8, s"${fires.size} issue-port acceptances for 8 ops")

      drainTo(8, FpuCore.FixedLatency + 24, "dense 8-fill")

      // (b) THE DOCUMENTED FIXED LATENCY, asserted as an exact function of the published
      //     `FpuCore.FixedLatency` constant rather than as a hard-coded number -- so a
      //     re-time of FpuCore that forgets to update the constant (GC-F2) fails here.
      //
      //     Accounting, since the exact off-by-one is easy to get wrong and a wrong
      //     constant here would silently make this a weaker test. `acceptCycles(i)` is the
      //     cycle index AFTER the accepting edge, i.e. the cycle in which the descriptor
      //     shadow pipe's stage 0 is already valid. From there: FpuCore.FixedLatency - 1
      //     further edges carry the request to `doneFixed` (13 registered stages measured
      //     from the pre-edge `start`, one of which is the accepting edge itself), and one
      //     more edge captures it into the EU's own `fpComp*` completion register. Net:
      //     exactly FpuCore.FixedLatency cycles from the accept index to the completion
      //     index. Empirically confirmed at 13.
      val ExpectedLatency = FpuCore.FixedLatency
      for (i <- ops.indices) {
        val got = comps(i).cycle - acceptCycles(i)
        assert(got == ExpectedLatency,
          s"${ops(i).label} (rob=${ops(i).rob}) completed $got cycles after its accepting " +
          s"edge, expected FpuCore.FixedLatency = $ExpectedLatency")
      }
      // ... and therefore also 8 consecutive completions (II=1 sustained end to end).
      assert((1 until comps.size).forall(i => comps(i).cycle == comps(i - 1).cycle + 1),
        s"the 8 completions were not consecutive: ${comps.map(_.cycle).mkString(",")}")

      // (c) EXACT ASSOCIATION + no duplicate/missing side effect anywhere.
      checkExactlyOnce(ops, "dense 8-fill")

      // Nothing more may arrive afterwards.
      val settled = comps.size
      tick(FpuCore.FixedLatency + 10)
      assert(comps.size == settled,
        s"${comps.size - settled} extra FP completion(s) arrived after the dense 8-fill drained")
      readBackAll(ops, "dense 8-fill")
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════════════
  // Requirement 2: at least four fixed ops SIMULTANEOUSLY in flight, no duplicate or
  //                missing PRF write / wakeup / status observation / completion.
  //
  // MUTATION-KILLED (Task 15 Step 4). Re-breaking the FP fixed lane's issue-accept term
  // into a single-outstanding busy gate -- the `!fpFixedBusy` singleton the Global
  // Constraints explicitly reject -- makes this test fail on two independent assertions.
  // ══════════════════════════════════════════════════════════════════════════════════════

  test("FP fixed lane: 4 operations are simultaneously in flight, each producing exactly one " +
       "completion, PRF write, FPCC write, wakeup and status observation", VerilatorTest) {
    dut.doSim { d =>
      val h = new Harness(d); import h._
      boot()
      preloadOperands()
      clearLog()

      val ops = eightOps(robBase = 20, pdstBase = 8).take(4)
      val acceptCycles = ArrayBuffer[Int]()
      for (o <- ops) {
        present(o)
        acceptCycles += acceptNow(s"4-in-flight ${o.label} (rob=${o.rob})")
      }
      s.iValid #= false
      assert(acceptCycles.toList == List(acceptCycles.head, acceptCycles.head + 1,
        acceptCycles.head + 2, acceptCycles.head + 3),
        s"the 4 ops were not accepted on 4 consecutive cycles: ${acceptCycles.mkString(",")} " +
        "-- the fixed lane has been reduced to a busy-gated singleton")

      // THE DIRECT WITNESS. Occupancy of the fixed descriptor shadow pipe must actually
      // reach 4 -- "they were accepted quickly" is not the same claim as "four were resident
      // at once", and only the latter is what the design doc requires.
      var peak = 0
      var guard = 0
      while (comps.isEmpty && guard < FpuCore.FixedLatency + 24) {
        if (s.inFlight.toInt > peak) peak = s.inFlight.toInt
        tick(); guard += 1
      }
      assert(peak >= 4,
        s"the fixed FP pipe never held more than $peak descriptor(s) at once -- at least 4 " +
        "simultaneously in-flight operations are required (a busy-gated singleton peaks at 1)")

      drainTo(4, FpuCore.FixedLatency + 24, "4-in-flight")
      checkExactlyOnce(ops, "4-in-flight")

      // STATUS OBSERVATION. These four operations are all exact, so the FPSR exception-status
      // accrual port must stay silent: a spurious accrual would corrupt architectural FPSR
      // for a program that never raised anything. (A duplicate/missing accrual on a raising
      // op is covered by the lock-step corpus, which can see real FPSR values; here the
      // provable protocol property is that a clean op accrues nothing.)
      assert(accruals.isEmpty,
        s"exact FP operations raised ${accruals.size} FPSR exception accrual(s): " +
        accruals.map { case (c, v) => f"cycle $c = 0x$v%02X" }.mkString(", "))

      val settled = comps.size
      tick(FpuCore.FixedLatency + 10)
      assert(comps.size == settled, "an extra completion arrived after the 4 in-flight ops drained")
      readBackAll(ops, "4-in-flight")
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════════════
  // Requirement 2 (raising-op half). Review finding F1 (Task 15 review, 2026-08-15): the
  // 4-in-flight test above only proves "no duplicate or missing status observation" for
  // EXACT operations -- an accrual list that stays empty the whole time is unfalsifiable
  // against a lane that never wires the accrual port at all. This test supplies the
  // missing positive case: a genuinely-raising FDIV (1.0/0.0, architecturally DZ) issued
  // concurrently with 3 exact fixed-lane ops, proving the accrual fires EXACTLY once, with
  // the correct bit, and does not leak onto the exact ops' silence. Kept as its own test
  // (not folded into the 4-in-flight test above) because the two lanes complete at very
  // different latencies -- mixing them breaks that test's issue-order-equals-completion-
  // order assumption (`checkExactlyOnce`), which is exactly the assumption Requirement 3's
  // collision test below also has to route around with per-robId, not per-index, checks.
  // ══════════════════════════════════════════════════════════════════════════════════════

  test("FP status observation: a genuinely-raising FDIV (1.0/0.0) accrues exactly one FPSR " +
       "exception status with DZ set, concurrently with 3 exact fixed-lane ops that still " +
       "accrue nothing", VerilatorTest) {
    dut.doSim { d =>
      val h = new Harness(d); import h._
      boot()
      preloadOperands()
      preloadFp(PZero, Zero)
      clearLog()

      // 3 exact fixed-lane ops (unchanged negative-case shape, reused from `eightOps`) plus
      // one genuinely-raising FDIV on the iterative lane, presented back to back so the
      // FDIV is actually in flight while the 3 fixed ops are still resident -- the same
      // concurrency the 4-in-flight test above establishes, extended with a raising op.
      val exactOps = eightOps(robBase = 50, pdstBase = 9).take(3)
      val divOp    = Op(53, OpFDIV, POne, PZero, 12, 12, PosInf, CcI, "FDIV 1.0/0.0 (DZ)")
      val ops      = exactOps :+ divOp
      for (o <- ops) { present(o); acceptNow(s"raising-op batch ${o.label} (rob=${o.rob})") }
      s.iValid #= false

      drainTo(ops.size, FpDivSqrtCore.WorstCaseLatency + FpuCore.FixedLatency + 60,
        "raising-op batch")

      // Per-op association: exact value/FPCC/wakeup routing for all 4, checked by robId
      // rather than by completion index (the two lanes finish in different orders).
      for (o <- ops) {
        assert(comps.count(_.rob == o.rob) == 1,
          s"${o.label}: ${comps.count(_.rob == o.rob)} completions for rob=${o.rob}")
        val ws = fpWrites.filter(_.addr == o.pdst)
        assert(ws.size == 1,
          s"${o.label}: ${ws.size} FP PRF write(s) to physreg ${o.pdst} (duplicate or missing)")
        assert(ws.head.data == o.want,
          f"${o.label}: wrote 0x${ws.head.data}%020X to FP[${o.pdst}], expected 0x${o.want}%020X")
        val cs = fpccWrites.filter(_.addr == o.pcc)
        assert(cs.size == 1, s"${o.label}: ${cs.size} FPCC write(s) to ${o.pcc}")
        assert(cs.head.data == BigInt(o.cc),
          s"${o.label}: FPCC[${o.pcc}] = ${cs.head.data}, expected ${o.cc}")
        assert(wakeups.count(_._2 == o.pdst) == 1, s"${o.label}: FP wakeup count for ${o.pdst}")
        assert(fpccWakes.count(_._2 == o.pcc) == 1, s"${o.label}: FPCC wakeup count for ${o.pcc}")
      }

      // THE POSITIVE CASE. Correlate accrual events to completions by cycle: the arbiter
      // delivers at most one FP result per cycle absent an engineered collision (Req 3), so
      // a cycle-keyed join is sound here and lets this test tell "the FDIV accrued" apart
      // from "one of the exact ops accrued" without relying on completion order.
      val compByCycle = comps.map(c => c.cycle -> c.rob).toMap
      assert(compByCycle.size == comps.size,
        "two completions shared a cycle -- the cycle-keyed accrual correlation below is unsound")
      val accrualByCycle = accruals.toMap
      assert(accrualByCycle.size == accruals.size, "two accruals shared a cycle")

      assert(accruals.size == 1,
        s"expected exactly one FPSR exception accrual (the raising FDIV), saw " +
        s"${accruals.size}: " + accruals.map { case (c, v) => f"cycle $c = 0x$v%02X" }.mkString(", "))
      val (accCycle, accByte) = accruals.head
      assert(compByCycle.get(accCycle).contains(divOp.rob),
        s"the sole accrual at cycle $accCycle does not line up with the FDIV's completion " +
        s"(rob completing that cycle: ${compByCycle.get(accCycle)}, expected ${divOp.rob})")
      assert((accByte & 0x04) != 0,
        f"the FDIV 1.0/0.0 accrual byte 0x$accByte%02X does not have DZ (bit 2) set")

      // THE NEGATIVE CASE, kept intact and re-proven under real concurrent traffic: none of
      // the 3 exact ops produced an accrual of their own.
      for (o <- exactOps) {
        val c = comps.find(_.rob == o.rob).get.cycle
        assert(!accrualByCycle.contains(c),
          s"${o.label} (an exact fixed-lane op) accrued FPSR status " +
          f"0x${accrualByCycle.getOrElse(c, 0)}%02X at its own completion cycle $c -- exact " +
          "ops must never touch FPSR")
      }

      val settled = comps.size
      tick(FpDivSqrtCore.WorstCaseLatency + 10)
      assert(comps.size == settled, "an extra completion arrived after the raising-op batch drained")
      assert(accruals.size == 1, "an extra FPSR accrual arrived after the raising-op batch drained")
      readBackAll(ops, "raising-op batch")
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════════════
  // Requirement 3: collide a fixed-pipeline result with FDIV/FSQRT completion; the result
  //                arbiter must retain BOTH exactly once.
  //
  // MUTATION-KILLED (Task 15 Step 6). The FP lane has no fixed-result FIFO -- the fixed
  // side cannot be back-pressured at all, so the arbitration lives entirely in
  // `fpIterTake`, which acknowledges FpDivSqrtCore ONLY on a cycle the iterative result
  // actually gets the shared completion register. Acknowledging unconditionally
  // (`fpIterTake := fpIterDone`) is the exact "pop unconditionally instead of on the cycle
  // it wins" bug the plan names, and silently DROPS the divide's result.
  // ══════════════════════════════════════════════════════════════════════════════════════

  test("FP result arbiter: a fixed-lane result colliding with an FDIV completion retains " +
       "both exactly once", VerilatorTest) {
    dut.doSim { d =>
      val h = new Harness(d); import h._
      boot()
      preloadOperands()
      clearLog()

      // Measure the iterative lane's real accept -> doneIter distance first, so the sweep is
      // centred on the genuine collision point instead of on a guess that would silently turn
      // this into a no-collision test.
      val probe = Op(1, OpFDIV, POne, PTwo, 15, 15, Half, CcNone, "probe FDIV 1.0/2.0")
      present(probe)
      val probeAccept = acceptNow("probe FDIV")
      s.iValid #= false
      var g = 0
      while (comps.isEmpty && g < FpDivSqrtCore.WorstCaseLatency + 40) { tick(); g += 1 }
      assert(comps.size == 1, "the probe FDIV never completed")
      val iterLatency = comps.head.cycle - probeAccept
      tick(4)
      clearLog()

      // A fixed op accepted `sep` cycles after the FDIV completes `sep + FixedLatency`
      // cycles after it; the collision is at sep = iterLatency - FixedLatency. Sweep a
      // window around it so the test survives a re-time of either lane, and REQUIRE that at
      // least one separation produced a genuine same-cycle collision at the arbiter inputs.
      val centre = iterLatency - FpuCore.FixedLatency
      assert(centre > 4, s"unexpected lane latencies (iter=$iterLatency); cannot set up a collision")
      var sawCollision = false
      for (sep <- (centre - 3) to (centre + 3)) {
        clearLog()
        val div  = Op(30, OpFDIV, PFour, PTwo, 8, 8, Two,  CcNone, s"FDIV 4.0/2.0 (sep=$sep)")
        val fixd = Op(31, OpFADD, POne,  PTwo, 9, 9, Three, CcNone, s"FADD 1.0+2.0 (sep=$sep)")
        present(div); acceptNow(s"collision FDIV (sep=$sep)")
        s.iValid #= false
        tick(sep)
        present(fixd); acceptNow(s"collision FADD (sep=$sep)")
        s.iValid #= false

        // Deliberately NOT `drainTo`: a dropped divide is the headline bug this test exists
        // to catch, and the per-result assertions below name it precisely, whereas a generic
        // drain timeout would only report "1/2 completions".
        waitFor(2, FpDivSqrtCore.WorstCaseLatency + 60)
        tick(8)
        if (collisions.nonEmpty) sawCollision = true

        // BOTH results, exactly once each, correctly routed. A dropped divide shows up as a
        // drainTo timeout naming the robIds actually seen; a duplicated one shows up here.
        assert(comps.count(_.rob == 30) == 1,
          s"sep=$sep: the FDIV completed ${comps.count(_.rob == 30)} times " +
          "(0 = it was acknowledged on a cycle the fixed lane owned the completion register " +
          "and was silently dropped -- there is no backpressure to recover it)")
        assert(comps.count(_.rob == 31) == 1,
          s"sep=$sep: the fixed FADD completed ${comps.count(_.rob == 31)} times")
        val divW = fpWrites.filter(_.addr == 8)
        val fixW = fpWrites.filter(_.addr == 9)
        def show(ws: Seq[Wr]) = ws.map(w => f"0x${w.data}%020X").mkString("[", ",", "]")
        assert(divW.size == 1 && divW.head.data == Two,
          s"sep=$sep: the FDIV produced ${divW.size} FP PRF write(s) ${show(divW.toSeq)}, " +
          "expected exactly one write of 2.0")
        assert(fixW.size == 1 && fixW.head.data == Three,
          s"sep=$sep: the fixed FADD produced ${fixW.size} FP PRF write(s) ${show(fixW.toSeq)}, " +
          "expected exactly one write of 3.0")
        assert(fpccWrites.size == 2 && wakeups.size == 2 && fpccWakes.size == 2,
          s"sep=$sep: ${fpccWrites.size} FPCC writes / ${wakeups.size} wakeups / " +
          s"${fpccWakes.size} FPCC wakeups for 2 results")
        val stillBusy = s.iterBusy.toBoolean
        assert(!stillBusy, s"sep=$sep: the iterative lane never freed")
      }
      assert(sawCollision,
        s"no launch separation in [${centre - 3}, ${centre + 3}] ever put `fpFixedDone` and " +
        "`fpIterDone` on the same cycle -- the arbiter collision was never actually exercised, " +
        "so this test proved nothing")
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════════════
  // Requirement 4: hold a request valid while result capacity is unavailable; stable
  //                payload, exactly one acceptance when credit returns.
  //
  // WHICH capacity. The FP FIXED lane accepts unconditionally by construction (its result
  // lands in a completion register re-armed every cycle, so it needs no credit), so the
  // only real capacity gate in this lane is the SINGLE-CONTEXT iterative lane's
  // `!fpIterBusy` term. That is the request this test holds.
  // ══════════════════════════════════════════════════════════════════════════════════════

  test("FP iterative lane: a request held valid across an unavailable lane keeps a stable " +
       "payload and is accepted exactly once when capacity returns", VerilatorTest) {
    dut.doSim { d =>
      val h = new Harness(d); import h._
      boot()
      preloadOperands()
      clearLog()

      val first = Op(50, OpFDIV, POne, PTwo, 8, 8, Half, CcNone, "occupying FDIV 1.0/2.0")
      present(first); acceptNow("occupying FDIV")
      s.iValid #= false
      tick(2)
      val occupied = s.iterBusy.toBoolean
      assert(occupied, "the occupying FDIV was not actually in flight")

      // Present the blocked request and HOLD it, untouched, for the whole stall.
      val held = Op(51, OpFSQRT, PFour, PFour, 9, 9, Two, CcNone, "held FSQRT(4.0)")
      present(held)
      sleep(1)
      val readyWhileBusy = s.iReady.toBoolean
      assert(!readyWhileBusy,
        "the issue port was ready for a second iterative op while the single-context lane " +
        "was still busy -- the in-flight FDIV's context would be overwritten")
      clearLog()

      var stalled = 0
      var guard = 0
      while (fires.isEmpty && guard < FpDivSqrtCore.WorstCaseLatency + 80) {
        // Payload stability, checked on EVERY stalled cycle rather than at the ends.
        val payloadStable = s.obsRob.toInt == held.rob && s.obsOp.toInt == held.opmode &&
               s.obsFpDst.toInt == held.pdst && s.obsSrcA.toInt == held.a &&
               s.obsSrcB.toInt == held.b
        assert(payloadStable,
          s"the held request's payload drifted at cycle $cycle " +
          s"(rob=${s.obsRob.toInt} op=0x${s.obsOp.toInt.toHexString} dst=${s.obsFpDst.toInt})")
        if (!s.iterBusy.toBoolean) { /* capacity has returned; the accept may land now */ }
        else {
          val readyTooEarly = s.iReady.toBoolean
          assert(!readyTooEarly,
            s"the issue port accepted a second iterative op at cycle $cycle while the lane " +
            "was still busy")
          stalled += 1
        }
        tick(); guard += 1
      }
      assert(stalled > 10,
        s"the held request only stalled for $stalled cycles -- capacity was never genuinely " +
        "unavailable, so this test proved nothing")
      assert(fires.size == 1,
        s"the held request was accepted ${fires.size} times when capacity returned, expected " +
        "exactly one acceptance")
      assert(fires.head._2 == held.rob,
        s"the acceptance carried robId ${fires.head._2}, expected the held ${held.rob}")
      // `sample()` reads the COMBINATIONAL `issue.fire`, so it reports the accepting edge one
      // cycle before that edge is taken: tick once to actually take it, and only then drop
      // `iValid`. (Dropping it here would retract the request instead of completing it.)
      tick()
      s.iValid #= false
      assert(fires.size == 1,
        s"the held request was accepted ${fires.size} times -- exactly one acceptance is " +
        "required when capacity returns")

      // Both results must be intact: the occupying divide's, and the held request's -- the
      // latter proving the payload the EU finally sampled was the held one, not a stale or
      // partially-latched copy.
      var g2 = 0
      while (comps.size < 2 && g2 < FpDivSqrtCore.WorstCaseLatency + 80) { tick(); g2 += 1 }
      assert(comps.size == 2,
        s"only ${comps.size}/2 completions after the stall (robIds ${comps.map(_.rob).mkString(",")})")
      assert(comps.map(_.rob).toList == List(first.rob, held.rob),
        s"completion order ${comps.map(_.rob).mkString(",")}, expected ${first.rob},${held.rob}")
      tick(8)
      assert(comps.size == 2, "an extra completion arrived after the stalled pair drained")
      assert(fpWrites.count(w => w.addr == held.pdst && w.data == held.want) == 1,
        s"the held FSQRT's PRF write is missing or duplicated: " +
        fpWrites.map(w => f"[${w.addr}]=0x${w.data}%020X").mkString(", "))
      assert(readFp(first.pdst) == first.want, "the occupying FDIV's result was lost")
      assert(readFp(held.pdst) == held.want,
        f"the held FSQRT wrote 0x${readFp(held.pdst)}%020X, expected 0x${held.want}%020X")
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════════════
  // Requirement 5: flush a DENSE fixed pipeline AND an active iterative operation, reuse
  //                the ROB ids and physical destinations immediately, prove no stale side
  //                effect survives.
  //
  // MUTATION-KILLED (Task 15 Step 8), twice:
  //   (a) dropping the iterative lane's in-flight poison (`fpIterFlushed`), so a flushed
  //       FDIV's late result writes back onto a REUSED robId/physreg;
  //   (b) replacing the fixed pipe's per-descriptor flush clear with a single GLOBAL flush
  //       latch that suppresses only the next completion -- the exact regression the design
  //       doc's sixth bullet names.
  // ══════════════════════════════════════════════════════════════════════════════════════

  test("FP lane flush: a dense fixed pipeline and an in-flight FDIV are both killed, and the " +
       "same ROB ids and physical destinations are immediately reusable", VerilatorTest) {
    dut.doSim { d =>
      val h = new Harness(d); import h._
      boot()
      preloadOperands()
      clearLog()

      // ---- fill BOTH lanes: one iterative op plus a dense fixed pipeline ----
      val staleDiv = Op(60, OpFDIV, POne, PTwo, 8, 8, Half, CcNone, "stale FDIV 1.0/2.0")
      present(staleDiv); acceptNow("stale FDIV")
      s.iValid #= false
      val staleFixed = (0 until 6).map { i =>
        fadd(40 + i, POne, PTwo, 9 + i, Three, CcNone, s"stale FADD#$i")
      }
      for (o <- staleFixed) { present(o); acceptNow(s"stale ${o.label}") }
      s.iValid #= false
      tick(2)
      val iterInFlight = s.iterBusy.toBoolean
      assert(iterInFlight, "the iterative op was not in flight when the flush hit")
      assert(s.inFlight.toInt >= 4,
        s"only ${s.inFlight.toInt} fixed descriptors were resident at the flush -- the " +
        "pipeline was not dense, so this test would not exercise multi-descriptor squash")
      assert(comps.isEmpty, "work completed before the flush; the setup window is mistimed")

      s.iFlush #= true
      tick()
      s.iFlush #= false
      tick()
      val poisoned = s.iterPoison.toBoolean
      assert(poisoned,
        "the flush did not poison the in-flight iterative context -- its late result will " +
        "write back onto whatever now owns that robId and physreg")
      clearLog()

      // ---- IMMEDIATE reuse of the very same robIds and physical destinations ----
      // Different operands, so a surviving stale result is distinguishable by VALUE and not
      // merely by count: the stale fixed ops all produce 3.0 and the stale divide 0.5, while
      // every reused identity below produces something else.
      val newFixed = (0 until 6).map { i =>
        fadd(40 + i, PFour, PTwo, 9 + i, Six, CcNone, s"reused FADD#$i")
      }
      for (o <- newFixed) { present(o); acceptNow(s"post-flush ${o.label}") }
      s.iValid #= false
      // The iterative lane is legitimately still occupied by the flushed divide until
      // FpDivSqrtCore's real `doneIter` arrives (it has no abort input by design), so this
      // request is held valid and accepted the moment the lane frees -- reusing robId 60 and
      // physreg 8 while the flushed divide is still physically iterating, which is precisely
      // the window the poison latch exists to cover.
      val newDiv = Op(60, OpFDIV, PFour, PTwo, 8, 8, Two, CcNone, "reused FDIV 4.0/2.0")
      present(newDiv)
      acceptWhenReady("post-flush FDIV", FpDivSqrtCore.WorstCaseLatency + 80)
      s.iValid #= false

      val expected = newFixed :+ newDiv
      drainTo(expected.size, FpDivSqrtCore.WorstCaseLatency + 120, "post-flush reuse")

      // No stale VALUE anywhere: not on the completion stream, not in the PRF writes.
      for (w <- fpWrites) {
        assert(w.data != Three,
          f"a flushed FADD's result (3.0) was written to FP[${w.addr}] at cycle ${w.cycle} " +
          "after its physreg was reused")
        assert(w.data != Half,
          f"the flushed FDIV's result (0.5) was written to FP[${w.addr}] at cycle ${w.cycle} " +
          "after its physreg was reused")
      }
      checkExactlyOnce(expected, "post-flush reuse")

      // A GENEROUS post-check window: a late stale completion is exactly the bug class this
      // test exists to catch, and the iterative engine's worst case is long.
      val settled = comps.size
      tick(FpDivSqrtCore.WorstCaseLatency + 40)
      assert(comps.size == settled,
        s"${comps.size - settled} stale FP completion(s) arrived after the reused identities " +
        s"had already retired (robIds ${comps.drop(settled).map(_.rob).mkString(",")})")
      val laneBusyAtEnd = s.iterBusy.toBoolean
      assert(!laneBusyAtEnd, "the iterative lane never freed after the flush and reuse")
      val poisonAtEnd = s.iterPoison.toBoolean
      assert(!poisonAtEnd, "the iterative poison latch survived past its own op")
      readBackAll(expected, "post-flush reuse")
    }
  }
}
