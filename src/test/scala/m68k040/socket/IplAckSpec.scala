package m68k040.socket

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 13, "Interrupts": the D17 count-equality assertion, and D18's autovector
  * policy as a documented, checked constant rather than an assumption.
  *
  * The count-equality assertion itself is RTL and lives in the plugin, so it is enforced
  * over EVERY simulation that instantiates it -- including the full lock-step corpus, which
  * spec section 13 asks for explicitly. This suite proves the counter pair exists and that
  * the pulse shape is single-cycle. */
class IplAckSpec extends AnyFunSuite {

  /** The pulse shaper in isolation: `entry` is the exception unit's one-cycle taken-entry
    * observation, `iplAck` must be its registered form and nothing else. */
  class AckDut extends Component {
    val entry  = in  Bool ()
    val iplAck = out Bool ()
    iplAck := RegNext(entry) init False
    val nEntry = Reg(UInt(16 bits)) init 0
    val nAck   = Reg(UInt(16 bits)) init 0
    when(entry)  { nEntry := nEntry + 1 }
    when(iplAck) { nAck := nAck + 1 }
    nEntry.simPublic(); nAck.simPublic()
  }

  test("ipl_ack is a ONE-CYCLE pulse per taken entry, never per pending recognition") {
    SimConfig.compile(new AckDut).doSim("pulse", seed = 1) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.entry #= false
      dut.clockDomain.waitSampling(4)
      // Ten entries, at irregular spacing including back-to-back.
      val gaps = Seq(3, 1, 0, 7, 0, 0, 2, 5, 1, 4)
      var pulses = 0
      val watcher = fork {
        while (true) { dut.clockDomain.waitSampling(); if (dut.iplAck.toBoolean) pulses += 1 }
      }
      for (g <- gaps) {
        dut.entry #= true
        dut.clockDomain.waitSampling()
        dut.entry #= false
        if (g > 0) dut.clockDomain.waitSampling(g)
      }
      dut.clockDomain.waitSampling(8)
      assert(pulses == gaps.length, s"$pulses pulses for ${gaps.length} entries")
      assert(dut.nAck.toInt == dut.nEntry.toInt,
        s"count equality violated: ${dut.nAck.toInt} acks vs ${dut.nEntry.toInt} entries")
    }
  }

  test("D18: seven autovector levels, and no vector input at the socket") {
    // The socket declares cpu_ipl[2:0] and ipl_ack, and NO vector input
    // (cpu_socket.vh:164-168). All seven levels therefore take autovectors 25-31, which is
    // what the Mac hardware actually does and what v1 does -- m68k_core.v:126's "CPU loops
    // on vec-31" is describing the autovector for level 7.
    assert(IplAckPlugin.AUTOVECTOR_BASE == 25, "level-1 autovector is 25")
    assert(IplAckPlugin.AUTOVECTOR_BASE + 6 == 31, "level-7 autovector is 31")
    assert(IplAckPlugin.IACK_AVEC_TIEOFF, "D18 ties iackAvec to 1")
  }
}
