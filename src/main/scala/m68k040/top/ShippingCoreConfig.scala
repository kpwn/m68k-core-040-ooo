package m68k040.top

/** THE SHIPPING CORE'S CONFIGURATION, in one place.
  *
  * Why this exists: the plugin knobs used to live as constructor DEFAULTS, and the
  * shipping top (`SocketTop`) and the benches each built their own plugin lists. Changing
  * a knob therefore meant editing a default that both happened to read -- and since the
  * SoC build regenerates its Verilog from a SEPARATE cpu040 checkout, "the config I
  * simulated" and "the config that was built" were two different things that had to be
  * kept in sync BY HAND. That cost real board measurements: a bisect image could not be
  * proven to contain the configuration it was labelled with.
  *
  * Rule: a knob that differs between sim and the shipping build is a BUG. Anything a
  * bench or test DUT wants to vary must be varied explicitly and named in the test, never
  * by diverging from these values. */
object ShippingCoreConfig {
  /** D-cache: serve a resolved command from its early-probe entry while the FSM refills
    * for an older, provably cacheable access. See `DcachePlugin.hitUnderMiss`.
    *
    * ⛔ OFF: MEASURED SILICON-NEUTRAL AND IT COSTS 200 MHz CLOSURE.
    *
    * Sim liked it -- +4.6% on `dhrystone-x0-cb` and +9.0% on `byteSplit-cb`, seed-robust
    * over four seeds, and it holds at the shorter `l2:3:35` latency too, so it is not an
    * artefact of the memory model. But on the board it measured PARITY: 55K Dhrystones/s
    * at 100 MHz with it on, exactly half the 110K the same CPU scores at 200 MHz without
    * it. The sim kernel is a synthetic approximation (chain load + byte copy + counters)
    * calibrated to match the baseline's cycles-per-iteration; that makes it a good model
    * of the BASELINE and says nothing about whether a change transfers to real Dhrystone,
    * which is dominated by procedure calls, string compares and switches.
    *
    * And it is not free: at a genuine 200 MHz constraint the design enters routing at
    * WNS -0.740 with 9,214 failing endpoints (known-good: -0.430 / 3,507) and post-route
    * phys_opt recovers only to about -0.27, where the known-good build reaches +0.001.
    * The arms add drivers to `loadCmdPort.ready` and a second S1 launch site, both on the
    * cone `DcachePlugin` records as the core's longest.
    *
    * No measured gain and a real FMax cost is not a trade worth making. The plumbing
    * stays (it is inert and it fixed a latent `REPLAY` identity bug); only the arms are
    * disabled. Re-enable only against a board measurement that actually moves. */
  val dcacheHitUnderMiss: Boolean = false

  /** D-cache: additionally accept such a command with a REAL S1 array read, parking an
    * S1 miss in the bounded replay slot. See `DcachePlugin.hitUnderMissRead`.
    * ⛔ OFF for the same reason as `dcacheHitUnderMiss` above. */
  val dcacheHitUnderMissRead: Boolean = false
}
