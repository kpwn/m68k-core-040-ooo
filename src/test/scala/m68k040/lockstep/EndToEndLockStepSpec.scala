package m68k040.lockstep

import m68k040.{M68kParams, VerilatorTest}
import m68k040.oracle.{Musashi, OracleStep}
import m68k040.verif.TraceReplay
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class EndToEndLockStepSpec extends AnyFunSuite {
  private val src =
    """    moveq #1,%d0
      |    moveq #2,%d1
      |    add.l %d1,%d0
      |    move.l %d0,0xFFFF0000
      |""".stripMargin

  private def driveAndCapture(commits: Seq[CommitObservation]): Seq[CommitObservation] = {
    var result: Seq[CommitObservation] = Seq.empty
    SimConfig.withVerilator.compile(TraceReplay()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.in.fire #= false
      dut.clockDomain.waitSampling()
      val captured = CommitTraceCapture.start(dut.io.out, dut.clockDomain)
      for (c <- commits) {
        dut.io.in.fire         #= true
        dut.io.in.pc           #= c.pc
        dut.io.in.archRegId    #= c.archRegId
        dut.io.in.archRegWrite #= c.archRegWrite
        dut.io.in.archRegValid #= c.archRegValid
        dut.io.in.ccr          #= c.ccr
        dut.io.in.memWrite     #= c.memWrite
        dut.clockDomain.waitSampling()
      }
      dut.io.in.fire #= false
      dut.clockDomain.waitSampling(3)
      result = captured.result()
    }
    result
  }

  private def commitsFromOracle(steps: Seq[OracleStep]): Seq[CommitObservation] =
    steps.map(s => CommitObservation(s.pc, archRegId = 0, archRegWrite = s.d(0),
      archRegValid = true, ccr = s.ccr, memAddr = 0, memData = 0, memWrite = false))

  test("end-to-end: replayed correct trace passes lock-step against Musashi", VerilatorTest) {
    val steps = Musashi.assembleAndTrace(src).fold(e => fail(e.reason), identity).take(3)
    val captured = driveAndCapture(commitsFromOracle(steps))
    val r = LockStep.compare(captured, steps)
    assert(r.ok, s"expected match; divergence=${r.firstDivergence}")
  }

  test("end-to-end: a corrupted replayed commit is pinpointed", VerilatorTest) {
    val steps = Musashi.assembleAndTrace(src).fold(e => fail(e.reason), identity).take(3)
    val good = commitsFromOracle(steps).toVector
    val corrupted = good.updated(2, good(2).copy(archRegWrite = 0x999L))
    val captured = driveAndCapture(corrupted)
    val r = LockStep.compare(captured, steps)
    assert(!r.ok && r.firstDivergence.exists(_.index == 2),
      s"expected divergence at index 2, got ${r.firstDivergence}")
  }
}
