package m68k040.fuzz

import m68k040.M68kSim
import m68k040.oracle.ProgramAssembler
import spinal.core.sim._

/** Outcome of running one m68k-ooo-ported directed asm test. */
sealed trait PortedOutcome
case object PortedPass extends PortedOutcome
final case class PortedFail(word: Long) extends PortedOutcome
final case class PortedHang(cycles: Long) extends PortedOutcome
final case class PortedGenFail(reason: String) extends PortedOutcome

/** Runs m68k-ooo's self-checking directed asm tests against this core.
  *
  * Each test is a standalone program that writes a sentinel word to
  * 0xFFFF0000 before halting: 0xC0FFEE00 = PASS, anything else = FAIL, no
  * write before the timeout = HANG. This mirrors m68k-ooo's own C++
  * testbench (tb/tb_top.cpp) detection exactly -- see
  * docs/superpowers/specs/2026-07-16-port-m68kooo-asm-tests-design.md.
  *
  * JVM discipline: ONE Verilator compile per JVM (lazy, shared across every
  * test), mirroring FuzzRunner.compiled -- never re-compile per test.
  */
object PortedTestRunner {
  val loadAddr: Long = ProgramAssembler.DefaultLoadAddress
  val SentinelAddr: Long = 0xFFFF0000L
  val PassWord: Long = 0xC0FFEE00L

  lazy val compiled = M68kSim().withVerilator.compile(new FuzzCoreDut)
  private var runIdx = 0

  def run(name: String, src: String, timeoutCycles: Long, simSeed: Int = 1): PortedOutcome = {
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => return PortedGenFail(s"assemble: ${err.reason}")
    }

    runIdx += 1
    var outcome: PortedOutcome = PortedHang(0)
    compiled.doSim(s"ported_$runIdx", simSeed) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)

      FuzzDut.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)

      // The sentinel word must start at a KNOWN value, not SparseMemory's
      // random fill for never-written bytes (same reasoning as the sandbox
      // pre-fill in FuzzLockStepSpec.scala's task #143 fix) -- otherwise a
      // random nonzero value there could be misread as an immediate (wrong)
      // sentinel write before the program has even started.
      for (i <- 0 until 4) dmem.mem.write(SentinelAddr + i, 0.toByte)

      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.urp   #= 0
      dut.ctrl.logic.srp   #= 0
      dut.intCtrl.logic.iplIn #= 0
      dut.intCtrl.logic.iackAvec #= false
      dut.intCtrl.logic.iackVector #= 0

      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid    #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)

      // Boot: supervisor (SR 0x2700), SSP/ISP 0x00100000, USP 0 -- identical
      // to FuzzRunner.run's boot sequence (FuzzLockStepSpec.scala).
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.usp #= 0L
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr  #= 15
      dut.wire.logic.seedData  #= BigInt(0x00100000L)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= false

      var cyc = 0L
      var word = 0L
      while (word == 0 && cyc < timeoutCycles) {
        cd.waitSampling()
        cyc += 1
        val b0 = dmem.mem.read(SentinelAddr).toLong & 0xffL
        val b1 = dmem.mem.read(SentinelAddr + 1).toLong & 0xffL
        val b2 = dmem.mem.read(SentinelAddr + 2).toLong & 0xffL
        val b3 = dmem.mem.read(SentinelAddr + 3).toLong & 0xffL
        word = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3
      }
      outcome =
        if (word == 0) PortedHang(cyc)
        else if (word == PassWord) PortedPass
        else PortedFail(word)
    }
    outcome
  }
}
