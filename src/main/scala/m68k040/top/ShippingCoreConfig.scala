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
  /** THE ENV-OVERRIDE RULE. A knob that is default-OFF *pending measurement* and has
    * no override cannot be measured: every build silently produces the baseline, and a
    * bitstream labelled "feature X" contains no feature X. That has cost this project
    * real board runs. So: any flag here whose default is provisional gets an override,
    * and the override's value is echoed into the `SHIPPING_CONFIG` line
    * (`SocketTop.scala`) so a build log identifies the configuration it actually built.
    *
    * `1`/`true`/`yes`/`on` enable; `0`/`false`/`no`/`off` disable; anything else is a
    * hard error rather than a silent fallback to the default -- a typo in an override
    * must not look like a measurement. */
  private def envFlag(name: String, dflt: Boolean): Boolean =
    sys.env.get(name).map(_.trim.toLowerCase).filter(_.nonEmpty) match {
      case None => dflt
      case Some("1") | Some("true") | Some("yes") | Some("on")   => true
      case Some("0") | Some("false") | Some("no") | Some("off")  => false
      case Some(other) => throw new IllegalArgumentException(
        s"$name=$other is not a boolean (use 1/0, true/false, yes/no, on/off)")
    }

  /** D-cache: serve a resolved command from its already-decided early-probe entry while
    * the FSM refills for an older, provably cacheable access. See
    * `DcachePlugin.hitUnderMiss`.
    *
    * ON. This arm touches NO array: it reads no tag/data port, issues no AXI, and lands
    * in the registered S2 stage. So it cannot widen the `rdEn` cone that sets the design's
    * critical path (below). Worth +4.0% on `dhrystone-x0-cb` in sim.
    */
  val dcacheHitUnderMiss: Boolean = true

  /** D-cache: additionally accept such a command with a REAL S1 array read, parking an S1
    * miss in the bounded replay slot. See `DcachePlugin.hitUnderMissRead`.
    *
    * ⛔ OFF, ON CRITICAL-PATH EVIDENCE. The routed 200 MHz worst path is
    *
    *     DcachePlugin_logic_tagMem_3_reg/CLKARDCLK  ->  tagMem_2_reg/ENARDEN
    *     12 logic levels, 1.781 ns logic + 2.822 ns route, slack -0.198
    *
    * i.e. a tag-array READ RESULT feeding the tag array's own READ ENABLE next cycle --
    * the `rdEn` cone. This arm is the only part of hit-under-miss that drives `rdEn`
    * (it is the one that performs a real array read), so it widens precisely that cone.
    *
    * And it is the cheap half of the feature: probe-only reaches 26,552 cycles on
    * `dhrystone-x0-cb` (+4.0%) and this arm only takes it to 26,322 (+0.9% more). Paying
    * critical-path width for 0.9% is the wrong trade when the design is 198 ps short.
    *
    * The code stays (it is correct, and it carries the REPLAY identity fix and the
    * miss-park handler); only the arm is disabled. */
  val dcacheHitUnderMissRead: Boolean = envFlag("CPU_DCACHE_HUM_READ", false)

  /** D-cache: SLICE D1.2 FILL-FORWARD. Answer a cacheable load miss from the refill
    * beat already latched in `missLine` instead of re-reading the line the refill just
    * wrote. See `DcachePlugin.fillForward` for the mechanism and the exact cycle count.
    *
    * OFF PENDING MEASUREMENT, with an env override (`CPU_DCACHE_FILL_FORWARD=1`).
    *
    * WHAT IT IS WORTH, sized before it was built so the measurement has something to
    * contradict: it removes exactly 2 cycles from every cacheable D-cache load miss.
    * Against the BOARD's own counters that is 24.43 D-miss/kinst at CPI 5.862 on the
    * real OS workload = ~0.83% of cycles, and 1.147 D-miss/kinst at CPI 2.831 on
    * Dhrystone = ~0.08%. So the LATENCY half of this is under 1% and cannot be
    * resolved by a single board run (the board's noise floor is ~0.9% on time-boxed
    * windows). The second effect -- every miss stops consuming the shared tag/data
    * read port a second time -- is OCCUPANCY, is unsized, and must be reported
    * separately rather than folded into the latency claim. */
  val dcacheFillForward: Boolean = envFlag("CPU_DCACHE_FILL_FORWARD", false)
}
