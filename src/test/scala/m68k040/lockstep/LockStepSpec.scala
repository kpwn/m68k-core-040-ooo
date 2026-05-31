package m68k040.lockstep

import m68k040.oracle.OracleStep
import org.scalatest.funsuite.AnyFunSuite

class LockStepSpec extends AnyFunSuite {
  private val steps = Vector(
    OracleStep(0x40800002L, 0x2700, Vector(1,0,0,0,0,0,0,0), Vector.fill(8)(0x100000L)),
    OracleStep(0x40800004L, 0x2700, Vector(3,0,0,0,0,0,0,0), Vector.fill(8)(0x100000L))
  )

  private def commitFrom(s: OracleStep, regId: Int, regVal: Long): CommitObservation =
    CommitObservation(pc = s.pc, archRegId = regId, archRegWrite = regVal,
      archRegValid = true, ccr = s.ccr, memAddr = 0, memData = 0, memWrite = false)

  test("matching DUT commits pass lock-step") {
    val dut = Vector(commitFrom(steps(0), 0, 1L), commitFrom(steps(1), 0, 3L))
    val r = LockStep.compare(dut, steps)
    assert(r.ok, s"expected match, got: ${r.firstDivergence}")
  }

  test("a wrong register value is pinpointed at the right index") {
    val dut = Vector(commitFrom(steps(0), 0, 1L), commitFrom(steps(1), 0, 99L))
    val r = LockStep.compare(dut, steps)
    assert(!r.ok)
    assert(r.firstDivergence.exists(_.index == 1), s"divergence should be at index 1: ${r.firstDivergence}")
    assert(r.firstDivergence.exists(_.detail.contains("reg")), s"detail should mention reg: ${r.firstDivergence}")
  }

  test("a PC divergence is detected") {
    val dut = Vector(commitFrom(steps(0), 0, 1L), commitFrom(steps(1).copy(pc = 0xdeadL), 0, 3L))
    val r = LockStep.compare(dut, steps)
    assert(!r.ok)
    assert(r.firstDivergence.exists(d => d.index == 1 && d.detail.toLowerCase.contains("pc")))
  }

  test("length mismatch is reported") {
    val dut = Vector(commitFrom(steps(0), 0, 1L))
    val r = LockStep.compare(dut, steps)
    assert(!r.ok)
    assert(r.firstDivergence.exists(d => d.detail.toLowerCase.contains("count") ||
                                         d.detail.toLowerCase.contains("length")))
  }
}
