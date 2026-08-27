package m68k040.socket

import m68k040.M68kSim
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** The reset must be able to recover a HUNG machine.
  *
  * Observed on hardware 2026-08-22: after a runtime `reset` issued while a read was
  * outstanding, the CPU retired ZERO instructions, indefinitely.  `cpu_rst` clears the
  * core's outstanding-transaction trackers, but the SoC still owes responses for reads
  * issued before the reset -- and nothing then accepts them (the I-cache raises
  * `r.ready` only for a live-MSHR id, the D-cache only inside REFILL).  AXI R is
  * in-order per id, so one un-acked stale beat blocks the read channel forever and the
  * reset-vector fetch never completes.
  *
  * These pin the recovery contract.  Note the DUT is instantiated in an ordinary
  * domain: what is exercised here is the state machine.  That it must live in a domain
  * `rst` cannot clear is a STRUCTURAL property of SocketTop (`axiPorCd`, resetKind =
  * BOOT) -- if it were reset by `rst`, the counter would be zeroed by the very event it
  * exists to survive and every test below would still pass.
  */
class AxiReadResetAbsorberSpec extends AnyFunSuite {

  private def sim(name: String)(body: (AxiReadResetAbsorber, ClockDomain) => Unit): Unit =
    M68kSim().compile(new AxiReadResetAbsorber()).doSim(name) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      dut.io.rstObserved #= false
      dut.io.arFire      #= false
      dut.io.rLastFire   #= false
      cd.waitSampling(2)
      body(dut, cd)
    }

  /** Drive one cycle's worth of events, then lower every input again.  A poked value is
    * not observable in the register until AFTER the following edge, so every read below
    * goes through `settle`. */
  private def tick(dut: AxiReadResetAbsorber, cd: ClockDomain,
                   ar: Boolean = false, r: Boolean = false, rst: Boolean = false): Unit = {
    dut.io.arFire      #= ar
    dut.io.rLastFire   #= r
    dut.io.rstObserved #= rst
    cd.waitSampling()
    dut.io.arFire      #= false
    dut.io.rLastFire   #= false
    dut.io.rstObserved #= false
  }

  /** One idle cycle: no AR, no R, no reset.  Nothing changes except the absorber's own
    * release condition, which is exactly what several of these tests are watching for. */
  private def settle(cd: ClockDomain): Unit = cd.waitSampling()

  test("with nothing outstanding, a reset leaves nothing armed -- the recovery is free") {
    sim("clean-reset") { (dut, cd) =>
      tick(dut, cd, rst = true)
      settle(cd)
      assert(dut.outstanding.toInt == 0, "a quiet bus owes nothing")
      // One further cycle: the release condition samples `outstanding` on the same edge
      // that changes it, so disarming always trails the last beat by exactly one cycle.
      settle(cd)
      assert(!dut.absorbing.toBoolean,
        "with nothing outstanding the absorber must release at once, costing no throughput")
    }
  }

  test("a read outstanding ACROSS a reset is absorbed, and absorb holds until it lands") {
    sim("stale-beat") { (dut, cd) =>
      // A read the SoC accepted before the reset.
      tick(dut, cd, ar = true); settle(cd)
      assert(dut.outstanding.toInt == 1, "the accepted read must be counted")

      // Reset lands while that response is still owed.
      tick(dut, cd, rst = true); settle(cd)
      assert(dut.absorbing.toBoolean, "a reset with a read outstanding must arm absorb")

      // THE REGRESSION.  This is the window in which the core has forgotten the
      // transaction but the bus has not.  Without a persistent absorb the stale beat is
      // never acked, and AXI's in-order-per-id R channel wedges permanently.
      for (_ <- 0 until 20) {
        settle(cd)
        assert(dut.absorbing.toBoolean,
          "absorb must hold until the bus has delivered what it already owes")
      }

      // The stale beat finally arrives and is discarded.
      tick(dut, cd, r = true); settle(cd)
      assert(dut.outstanding.toInt == 0, "the stale response must clear the count")
      settle(cd)
      assert(!dut.absorbing.toBoolean, "absorb releases once nothing is owed")
    }
  }

  test("a burst of outstanding reads is absorbed to completion, not just the first") {
    sim("many-stale") { (dut, cd) =>
      for (_ <- 0 until 4) { tick(dut, cd, ar = true); settle(cd) }
      assert(dut.outstanding.toInt == 4, "all four accepted reads must be counted")
      tick(dut, cd, rst = true); settle(cd)
      for (n <- 4 to 1 by -1) {
        assert(dut.absorbing.toBoolean, s"absorb must still hold with $n response(s) owed")
        tick(dut, cd, r = true); settle(cd)
      }
      assert(dut.outstanding.toInt == 0)
      settle(cd)
      assert(!dut.absorbing.toBoolean, "absorb releases only after the LAST owed beat")
    }
  }

  test("a held reset keeps the absorber armed (level-sensitive, not edge)") {
    sim("held-reset") { (dut, cd) =>
      dut.io.rstObserved #= true
      cd.waitSampling(10)
      assert(dut.absorbing.toBoolean, "a held reset must keep absorb armed")
      dut.io.rstObserved #= false
      cd.waitSampling(2)
      assert(!dut.absorbing.toBoolean, "and release once let go, with nothing owed")
    }
  }

  test("same-cycle AR accept and R completion leave the count unchanged") {
    sim("concurrent") { (dut, cd) =>
      tick(dut, cd, ar = true); settle(cd)
      tick(dut, cd, ar = true, r = true); settle(cd)
      assert(dut.outstanding.toInt == 1,
        "one in and one out on the same edge must not double-count in either direction")
    }
  }
}
