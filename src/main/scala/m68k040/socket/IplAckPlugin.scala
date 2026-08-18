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
  * thing that quietly changes. The count-equality assertion below, checked against
  * `RobPlugin`'s independently-registered `commitObs(2)` (NOT a re-derivation of this
  * plugin's own `takenEntry`), is what makes either choice checkable at every simulation run
  * -- see the assertion's own comment for why the comparison is genuinely independent.
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

    // D17's required pin: over any simulation run, the count of ipl_ack pulses equals an
    // INDEPENDENTLY-DERIVED count of interrupt exception entries taken. Sim-only; needs
    // `.includeSimulation` on the enclosing SpinalConfig (see M68kSim.scala). Because this
    // lives in the plugin rather than in one spec, it is enforced over EVERY simulation that
    // instantiates it -- including the full lock-step corpus, which spec section 13 asks for
    // by name.
    //
    // == Why counting `takenEntry` itself (the ORIGINAL, WRONG version) was worthless ==
    // `ack` IS `RegNext(takenEntry)` by definition. Counting `takenEntry` pulses as
    // "nEntry" and `ack` pulses as "nAck" and asserting they track within one cycle is a
    // mathematical identity of ANY register and its own input -- it holds unconditionally
    // no matter what `takenEntry` is wired to, including the explicitly-rejected
    // `RegNext(interruptPending)` form or a flat-out wrong signal. It compares `ack` against
    // a relabeled copy of its own source, never against independent ground truth, so it
    // caught nothing.
    //
    // == The genuinely independent signal ==
    // `RobPlugin`'s own `commitObs(2)` (`RobPlugin.scala:1636,1644`) is a SEPARATE pair of
    // registers that RobPlugin maintains for its own lock-step-whitebox purpose, wired
    // directly off the same `ExceptionUnit` instance's `obsFire`/`obsIsInterrupt` through
    // RobPlugin's OWN hookup -- not through anything in this plugin. `commitObs(2).fire &&
    // commitObs(2).isInterrupt` is already the established, independently-trusted "an
    // interrupt entry committed" signal consumed throughout the lock-step corpus
    // (`ExecuteLockStepSpec.scala`, `FuzzLockStepSpec.scala`, `IpcBenchSpec.scala` all gate
    // on exactly this pair). A bug local to THIS plugin -- wrong signal, wrong polarity, an
    // extra/missing register stage, or the rejected `RegNext(interruptPending)` form -- would
    // diverge from this independent count, since `commitObs(2)`'s derivation never passes
    // through `IplAckPlugin`.
    //
    // == Cycle alignment: why the tolerance is now EXACT, not "one behind" ==
    // `obsIsInterrupt` is driven ONLY at ExceptionUnit's single entry-commit site
    // (`ExceptionUnit.scala:1507-1512`), in the SAME when-block/cycle as `obsFire` and
    // `obsIsEntry` -- so `obsIsInterrupt` structurally IMPLIES `obsFire && obsIsEntry`, and
    // `takenEntry` (the 3-term AND) is equal to `obsIsInterrupt` alone, cycle for cycle.
    // `commitObs(2).fire`/`.isInterrupt` are each a single `RegNext` of that same cycle's
    // `obsFire`/`obsIsInterrupt`, exactly like `ack` is a single `RegNext` of that same
    // cycle's `takenEntry`. So `commitObs(2).fire && commitObs(2).isInterrupt` pulses on
    // EXACTLY the same cycle as `ack` -- zero skew, not one-cycle skew -- and a correct
    // derivation must keep the two running counts EQUAL on every cycle, not merely within
    // one of each other.
    GenerationFlags.simulation {
      val indep = rob.logic.commitObs(2)
      val indepTakenEntry = indep.fire && indep.isInterrupt
      val nAck   = Reg(UInt(32 bits)) init 0
      val nIndep = Reg(UInt(32 bits)) init 0
      when(ack)             { nAck   := nAck + 1 }
      when(indepTakenEntry) { nIndep := nIndep + 1 }
      nAck.simPublic(); nIndep.simPublic()
      assert(nAck === nIndep,
        "ipl_ack pulse count diverged from RobPlugin's independent commitObs(2) " +
          "interrupt-entry count -- ipl_ack's derivation no longer matches ground truth",
        FAILURE)
    }
  }
}
