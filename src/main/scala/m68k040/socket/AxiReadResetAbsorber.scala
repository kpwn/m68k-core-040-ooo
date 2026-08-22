package m68k040.socket

import spinal.core._
import spinal.core.sim._

/** Recovers the AXI read channel across a CPU reset.
  *
  * WHY THIS EXISTS.  A CPU reset must be able to RECOVER A HUNG MACHINE -- that is
  * the entire purpose of the debug reset.  But `rst` resets the whole core, including
  * every outstanding-transaction tracker, while the SoC side (L2C / crossbar / MIG)
  * keeps its state and still owes responses for reads issued BEFORE the reset.  Those
  * responses then have nobody willing to accept them: the I-cache raises `r.ready`
  * only for an id matching a LIVE MSHR (`IcachePlugin`: `axi.r.ready := demandRspMatch
  * || pfRspMatch`) and the D-cache only inside REFILL (`axi.r.ready :=
  * !refillWriteHold`) -- and the reset cleared both.  AXI R is in-order per id, so a
  * single un-acked stale beat blocks the read channel FOREVER: the reset-vector fetch
  * never receives its data and the CPU never retires a single instruction.
  *
  * (The write channel was never exposed to this -- the D-cache holds `axi.b.ready`
  * True unconditionally.  Reads were the hole.)
  *
  * HOW.  Count live reads, arm on every observed reset, and until the count returns to
  * zero accept-and-discard every read beat while refusing to issue any new address.
  *
  * TERMINATION is by construction: no new AR can be accepted while `absorbing`, so the
  * count is monotonically non-increasing and reaches zero after the bus delivers what
  * it already owes.  Steady-state cost is nil -- with nothing outstanding, `absorbing`
  * drops the cycle after reset releases.
  *
  * INSTANTIATE THIS IN A DOMAIN `rst` CANNOT CLEAR (`resetKind = BOOT`).  Putting it in
  * the core's own reset domain would zero the very counter whose value is the thing the
  * reset destroyed, which is the bug this exists to fix.
  */
class AxiReadResetAbsorber(maxOutstanding: Int = 31) extends Component {
  val io = new Bundle {
    /** The core reset, observed as a LEVEL (a held reset keeps the absorber armed). */
    val rstObserved = in Bool ()
    /** Bus-side AR handshake: a read the SoC has accepted and now owes a response for. */
    val arFire      = in Bool ()
    /** Bus-side final-beat R handshake (`rvalid && rready && rlast`). */
    val rLastFire   = in Bool ()
    /** While True: discard read beats, refuse new AR. */
    val absorbing   = out Bool ()
  }

  val outstanding = Reg(UInt(log2Up(maxOutstanding + 1) bits)) init 0
  val absorbing   = RegInit(False)

  when(io.arFire && !io.rLastFire) {
    outstanding := outstanding + 1
  } elsewhen (!io.arFire && io.rLastFire) {
    outstanding := outstanding - 1
  }

  when(io.rstObserved) {
    absorbing := True
  } elsewhen (outstanding === 0) {
    absorbing := False
  }

  io.absorbing := absorbing

  outstanding.simPublic(); absorbing.simPublic()

  GenerationFlags.simulation {
    assert(!(io.arFire && !io.rLastFire &&
             (outstanding === U(maxOutstanding, outstanding.getWidth bits))),
      "AxiReadResetAbsorber: outstanding-read counter overflowed",
      FAILURE)
    assert(!(io.rLastFire && !io.arFire && (outstanding === 0)),
      "AxiReadResetAbsorber: a read response arrived with nothing outstanding",
      FAILURE)
  }
}
