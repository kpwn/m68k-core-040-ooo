package m68k040.execute

import m68k040.{M68kSim, VerilatorTest}
import m68k040.fuzz.{FuzzCoreDut, FuzzDut}
import m68k040.ls.BehavioralMemAgent
import m68k040.oracle.ProgramAssembler
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

/** Task 14c: the FPCR -> FP-EU control seam, end to end through a real architectural
  * FMOVE-to-FPCR write.
  *
  * WHITEBOX, NOT LOCK-STEP, AND DELIBERATELY SO. Musashi raises **no** floating-point
  * exceptions at all and never writes FPSR's EXC or AEXC bytes (`m68kfpu.c` touches only
  * the FPCC nibble; grep the file for `REG_FPSR` -- every hit is an FPCC bit), so the two
  * things this spec exists to check have no oracle. That is already recorded as
  * **Divergence Register D4** in the approved design spec: "Every FPSR exception-status
  * output is directed-test-only." The rounding-mode half of Task 14c's seam DOES have an
  * oracle (Musashi's `fmove_fpcr` sets SoftFloat's `float_rounding_mode`) and is therefore
  * lock-stepped instead, in `lockstep.FpuLockStepSpec`.
  *
  * What was actually broken before Task 14c: `DivEuPlugin.fpExcEnableIn` was default-driven
  * all-clear with no driver anywhere, so **no** FPCR enable bit could ever escalate an FP
  * exception to a real trap; and `FpuControlPlugin.orFpsrExc` had no producer, so FPSR's
  * EXC/AEXC bytes were permanently zero no matter what the program computed.
  */
class FpuControlWiringSpec extends AnyFunSuite {
  // ONE Verilator build for the whole suite (the shared-`lazy val` rule that
  // ExecuteLockStepSpec's JVM-OOM incident established).
  private lazy val compiled = M68kSim().withVerilator.compile(new FuzzCoreDut)
  private var simSeq = 0
  private def simName(tag: String): String = { simSeq += 1; s"$tag-$simSeq" }

  private val loadAddr = ProgramAssembler.DefaultLoadAddress
  private val StackTop = 0x00030000L
  private val IttMatchAllWt = 0x00FFC000L
  private val DttMatchAllWt = 0x00FFC000L

  /** MC68040 FP arithmetic exception vectors (`execute.FpVector`), restated here as the
    * test's own independent expectation rather than imported, so a typo in the RTL's table
    * cannot silently agree with itself. */
  private val VecInex = 49; private val VecDz = 50
  private val VecUnfl = 51; private val VecOperr = 52
  private val VecOvfl = 53; private val VecSnan = 54

  /** FPSR EXC byte, FPSR[15:8] (MC68040 UM Figure 9-5). */
  private val ExcSnan = 1 << 14; private val ExcOperr = 1 << 13
  private val ExcOvfl = 1 << 12; private val ExcUnfl  = 1 << 11
  private val ExcDz   = 1 << 10; private val ExcInex2 = 1 << 9
  /** FPSR AEXC byte, FPSR[7:0] (MC68040 UM Figure 9-6). */
  private val AexcIop = 1 << 7; private val AexcOvfl = 1 << 6
  private val AexcUnfl = 1 << 5; private val AexcDz  = 1 << 4
  private val AexcInex = 1 << 3

  private case class Run(fpcr: BigInt, fpsr: BigInt, fp: Vector[BigInt], sawVector: Int)

  private def run(src: String, cycles: Int = 3000, name: String = "fpctl"): Run = {
    val image = ProgramAssembler.assemble(src, loadAddr).getOrElse(fail(s"[$name] assemble failed"))
    var out: Run = null
    compiled.doSim(simName(name), 1) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      FuzzDut.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val zmem = new m68k040.sim.ConstFillSparseMemory(0.toByte)
      val dmem = new BehavioralMemAgent(dut.dcache.logic.axi, cd, sharedMem = zmem)
      new BehavioralMemAgent(dut.dtlb.walkerAxi, cd, sharedMem = zmem)
      new BehavioralMemAgent(dut.itlb.walkerAxi, cd, sharedMem = zmem)

      // Every FP arithmetic vector points at a self-looping handler, so an ESCALATED test
      // lands somewhere defined instead of vectoring through a zero table to a wild PC.
      val handler = loadAddr + 0x200L
      for (v <- VecInex to VecSnan; i <- 0 until 4)
        dmem.pokeByte(v * 4L + i, ((handler >> (8 * (3 - i))) & 0xff).toInt)

      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
      dut.ctrl.logic.itt0 #= 0; dut.ctrl.logic.itt1 #= 0
      dut.ctrl.logic.dtt0 #= 0; dut.ctrl.logic.dtt1 #= 0
      dut.intCtrl.logic.iplIn #= 0
      dut.intCtrl.logic.iackAvec #= true
      dut.intCtrl.logic.iackVector #= 0
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false
      dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false
      dut.wire.logic.seedAddr #= 0
      dut.wire.logic.seedData #= 0

      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling()
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)

      dut.ctrl.logic.itt0 #= IttMatchAllWt
      dut.ctrl.logic.dtt0 #= DttMatchAllWt
      dut.ctrl.logic.mmuEnable #= true
      dut.rob.logic.exc.ss.cacr #= 0x80008000L   // DE|IE
      dut.rob.logic.exc.ss.isp #= StackTop
      dut.rob.logic.exc.ss.usp #= StackTop
      dut.rob.logic.exc.ss.srSys #= 0x27         // supervisor, IPL 7
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr #= 15
      dut.wire.logic.seedData #= BigInt(StackTop)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()

      dut.fa.logic.redirect.valid #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= false

      var vec = -1
      var i = 0
      while (i < cycles) {
        cd.waitSampling()
        if (vec < 0 && dut.rob.logic.exceptionPending.toBoolean)
          vec = dut.rob.logic.exceptionVector.toInt
        i += 1
      }
      // Architectural FPn, resolved through the COMMITTED FP RAT exactly as
      // FpuLockStepSpec's `dutFp` does (the real Mem is unreadable post-
      // MultiPortWritesSymplifier; `shadow` is RegFilePluginFp's sim-only mirror).
      val fp = Vector.tabulate(8) { a =>
        val phys = dut.ren.logic.fpRat.io.committedPhys(a).toInt
        dut.rfFp.logic.shadow(phys).toBigInt & ((BigInt(1) << 80) - 1)
      }
      out = Run(dut.fpuCtl.logic.fpcr.toBigInt, dut.fpuCtl.logic.fpsr.toBigInt, fp, vec)
    }
    out
  }

  /** `move.l #v,%d0 ; fmove.l %d0,%fpcr`. The IMMEDIATE <ea> form (`fmove.l #v,%fpcr`) is
    * deliberately not implemented -- MicroOpAssembler's `fpCtrlEaReg` admits register-direct
    * modes 000/001 only, because `imm` cannot carry both a 32-bit value and the 3-bit
    * register-select mask through the ROB's single `sysRc` side-channel. The register-direct
    * form is the real, supported architectural FPCR write. */
  private def setFpcr(v: Int): Seq[String] =
    Seq(f"move.l #0x$v%04X,%%d0", "fmove.l %d0,%fpcr")

  private def prog(instrs: Seq[String]): String = (instrs :+ "done: bra.s done").mkString(" ; ")

  /** 1.0 / 0.0 -- the canonical divide-by-zero. */
  private val DivByZero = Seq("fmove.l #1,%fp0", "fmove.l #0,%fp1", "fdiv.x %fp1,%fp0")
  /** 1.0 / 3.0 -- inexact, and nothing else. */
  private val InexactDiv = Seq("fmove.l #1,%fp0", "fmove.l #3,%fp1", "fdiv.x %fp1,%fp0")
  private val PosInf = BigInt("7fff8000000000000000", 16)

  // ── FPSR exception-status accrual (`orFpsrExc`, previously producerless) ──────

  test("FP divide-by-zero with traps disabled substitutes +inf and accrues FPSR DZ/AEXC.DZ",
       VerilatorTest) {
    val r = run(prog(DivByZero), name = "dz-disabled")
    assert(r.sawVector < 0,
      s"a DISABLED FP exception must never vector; saw ${r.sawVector}")
    assert(r.fp(0) == PosInf,
      f"FP0 must be the substituted +inf, got 0x${r.fp(0).toString(16)}")
    assert((r.fpsr.toInt & ExcDz) != 0,
      f"FPSR.EXC.DZ must be set even with the trap disabled, FPSR=0x${r.fpsr.toString(16)}")
    assert((r.fpsr.toInt & AexcDz) != 0,
      f"FPSR.AEXC.DZ must accrue (UM 9.2.3.4: DZ = DZ V DZ), FPSR=0x${r.fpsr.toString(16)}")
    // Nothing else may be claimed: DZ accrues to AEXC.DZ ONLY, never to INEX or IOP.
    assert((r.fpsr.toInt & (AexcInex | AexcIop | AexcOvfl | AexcUnfl)) == 0,
      f"DZ must not accrue into any other AEXC bit, FPSR=0x${r.fpsr.toString(16)}")
  }

  test("inexact FP result accrues FPSR INEX2 and AEXC.INEX", VerilatorTest) {
    val r = run(prog(InexactDiv), name = "inex")
    assert(r.sawVector < 0, s"INEX2 with its trap disabled must not vector; saw ${r.sawVector}")
    assert((r.fpsr.toInt & ExcInex2) != 0,
      f"FPSR.EXC.INEX2 must be set for 1/3, FPSR=0x${r.fpsr.toString(16)}")
    assert((r.fpsr.toInt & AexcInex) != 0,
      f"FPSR.AEXC.INEX must accrue (UM 9.2.3.4: INEX = INEX1 V INEX2 V OVFL), " +
      f"FPSR=0x${r.fpsr.toString(16)}")
    assert((r.fpsr.toInt & (AexcUnfl | AexcDz | AexcIop | AexcOvfl)) == 0,
      f"a plain INEX2 must not accrue UNFL/DZ/IOP/OVFL, FPSR=0x${r.fpsr.toString(16)}")
  }

  test("an exact FP result accrues nothing", VerilatorTest) {
    // The negative control for the two tests above: `orFpsrExc` must be driven by the real
    // per-op flags, not merely pulsed on every FP completion.
    val r = run(prog(Seq("fmove.l #12,%fp0", "fmove.l #3,%fp1", "fdiv.x %fp1,%fp0")),
                name = "exact")
    assert(r.sawVector < 0, s"an exact FDIV must not vector; saw ${r.sawVector}")
    assert(r.fpsr == 0,
      f"FPSR must still be 0 after an exact 12/3, got 0x${r.fpsr.toString(16)}")
  }

  // ── FPCR ENABLE byte actually gates escalation (`fpExcEnableIn`) ──────────────

  test("FPCR.DZ enable escalates the same divide-by-zero to a real vector-50 trap",
       VerilatorTest) {
    val r = run(prog(setFpcr(ExcDz) ++ DivByZero), name = "dz-enabled")
    assert(r.fpcr.toInt == ExcDz,
      f"the architectural FMOVE.L %%d0,%%fpcr must have landed; FPCR=0x${r.fpcr.toString(16)}")
    assert(r.sawVector == VecDz,
      s"an ENABLED DZ must vector to $VecDz (MC68040 UM Table 8-1); saw ${r.sawVector}")
    assert((r.fpsr.toInt & ExcDz) != 0,
      f"FPSR.EXC.DZ must still be set for the handler to read, FPSR=0x${r.fpsr.toString(16)}")
  }

  test("an unrelated FPCR enable bit does NOT escalate a divide-by-zero", VerilatorTest) {
    // The gate is per-exception-class, not "any enable bit set". This is the assertion that
    // would catch a bit-order mistake in the FPCR[15:8] -> FpExcFlags mapping: OVFL sits one
    // bit above UNFL and two above DZ, so an off-by-one would light DZ here.
    val r = run(prog(setFpcr(ExcOvfl) ++ DivByZero), name = "ovfl-enabled-dz-raised")
    assert(r.fpcr.toInt == ExcOvfl, f"FPCR=0x${r.fpcr.toString(16)}")
    assert(r.sawVector < 0,
      s"OVFL-enabled must not trap on a DZ condition; saw vector ${r.sawVector}")
    assert(r.fp(0) == PosInf, f"FP0 must still be +inf, got 0x${r.fp(0).toString(16)}")
    assert((r.fpsr.toInt & ExcDz) != 0, f"FPSR=0x${r.fpsr.toString(16)}")
  }

  test("FPCR.INEX2 enable escalates an inexact result to a real vector-49 trap", VerilatorTest) {
    // A second, independent enable bit, one position away from DZ in the ENABLE byte --
    // together with the DZ test above this pins the mapping rather than one bit of it.
    val r = run(prog(setFpcr(ExcInex2) ++ InexactDiv), name = "inex-enabled")
    assert(r.fpcr.toInt == ExcInex2, f"FPCR=0x${r.fpcr.toString(16)}")
    assert(r.sawVector == VecInex,
      s"an ENABLED INEX must vector to $VecInex; saw ${r.sawVector}")
  }

  test("FPCR.OPERR enable escalates 0.0/0.0 to a real vector-52 trap", VerilatorTest) {
    val r = run(prog(setFpcr(ExcOperr) ++ Seq("fmove.l #0,%fp0", "fdiv.x %fp0,%fp0")),
                name = "operr-enabled")
    assert(r.fpcr.toInt == ExcOperr, f"FPCR=0x${r.fpcr.toString(16)}")
    assert(r.sawVector == VecOperr,
      s"an ENABLED OPERR must vector to $VecOperr; saw ${r.sawVector}")
  }

  test("0.0/0.0 with traps disabled accrues OPERR into AEXC.IOP and does not vector",
       VerilatorTest) {
    val r = run(prog(Seq("fmove.l #0,%fp0", "fdiv.x %fp0,%fp0")), name = "operr-disabled")
    assert(r.sawVector < 0, s"saw vector ${r.sawVector}")
    assert((r.fpsr.toInt & ExcOperr) != 0, f"FPSR=0x${r.fpsr.toString(16)}")
    assert((r.fpsr.toInt & AexcIop) != 0,
      f"UM 9.2.3.4: IOP = IOP V (SNAN V OPERR); FPSR=0x${r.fpsr.toString(16)}")
  }
}
