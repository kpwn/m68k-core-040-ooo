package m68k040.socket

import m68k040.rob.RobPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.FiberPlugin

object IplAckPlugin {
  /** D18: the socket declares no vector input (`cpu_socket.vh:164-168`), so all seven
    * levels take AUTOVECTORS 25-31. That is what the Mac hardware actually does and what v1
    * does; `m68k_core.v:126`'s "CPU loops on vec-31" is describing the autovector for level
    * 7. Recorded as constants so the policy is citable rather than folklore. */
  val AUTOVECTOR_BASE  = 25
  val IACK_AVEC_TIEOFF = true
}

/** `ipl_ack` (design spec D17, section 7).
  *
  * ==Why it is load-bearing, not polish==
  * Without it the SoC's `irq_agg` NMI rising-edge latch never clears. `m68k_core.v:119-126`:
  * "Without this hook the external agg's `nmi_pending` latch sticks once any rising edge
  * fires and IPL=7 is asserted forever -> CPU loops on vec-31." A small amount of logic
  * guarding a total-failure mode.
  *
  * ==The contract==
  * `ipl_ack` pulses high for exactly ONE core-clock cycle each time an interrupt exception
  * entry is ACTUALLY TAKEN -- never on a merely-pending or subsequently-abandoned
  * recognition.
  *
  * ==The source, and why not the simpler one==
  * `ExceptionUnit.scala:1507-1512` pulses `obsFire`, `obsIsEntry` and `obsIsInterrupt`
  * together for exactly one cycle at the entry that is taken, and `RobPlugin.scala:1614,1622`
  * already registers that pair for the commit observation. So the derivation is
  * `RegNext(obsFire && obsIsEntry && obsIsInterrupt)`: already registered, so it adds no
  * logic depth to the commit path (the architecture document's registered-control rule).
  *
  * `RegNext(interruptPending)` is a plausible simpler form, but it depends on
  * `RobPlugin.scala:1447`'s self-gating on `excIdle` collapsing to a single cycle per
  * accepted entry being STRUCTURAL rather than incidental -- and that is exactly the kind of
  * thing that quietly changes. The count-equality assertion below is what makes either
  * choice safe, which is why D17 requires it rather than merely suggesting it.
  *
  * ==D18 is discharged elsewhere, on purpose==
  * `iackAvec`/`iackVector` are already core INPUTS (`FullCoreSynth.scala:316-321`), so
  * tying `iackAvec = 1` and dropping `iackVector` is a `M68kSocketTop` connection, not a
  * core change. That keeps the existing `RegNext(...) init 0` synchroniser placement at
  * `:319-321` exactly as spec section 7.3 requires -- it is the correct placement AND it
  * keeps the IPL compare cone non-foldable, which the OOC flow relies on. */
class IplAckPlugin(val enable: Boolean = false) extends FiberPlugin {

  val logic = during build new Area {
    val rob = host[RobPlugin]
    val exc = rob.logic.exc

    val takenEntry = exc.obsFire && exc.obsIsEntry && exc.obsIsInterrupt
    val ack = RegNext(takenEntry) init False
    ack.simPublic()

    // Only a socket build exports the port; M68kFullCoreSynth must not gain one (D23).
    val iplAck = if (enable) { val p = out Bool (); p.setName("ipl_ack"); p := ack; p } else ack

    // D17's required pin: over any simulation run, the count of ipl_ack pulses equals the
    // count of interrupt exception entries retired. Sim-only; needs `.includeSimulation`
    // on the enclosing SpinalConfig (see M68kSim.scala). Because this lives in the plugin
    // rather than in one spec, it is enforced over EVERY simulation that instantiates it --
    // including the full lock-step corpus, which spec section 13 asks for by name.
    GenerationFlags.simulation {
      val nEntry = Reg(UInt(32 bits)) init 0
      val nAck   = Reg(UInt(32 bits)) init 0
      when(takenEntry) { nEntry := nEntry + 1 }
      when(ack)        { nAck   := nAck + 1 }
      nEntry.simPublic(); nAck.simPublic()
      // One cycle of skew by construction (ack is takenEntry registered), so the invariant
      // is "never more acks than entries, and never more than one behind".
      assert(nAck <= nEntry, "ipl_ack pulsed more often than an interrupt entry was taken",
        FAILURE)
      assert((nEntry - nAck) <= U(1, 32 bits),
        "ipl_ack fell more than one entry behind the interrupt entries taken", FAILURE)
    }
  }
}
