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
  val dcacheHitUnderMissRead: Boolean = false

  /** ITLB victim buffer depth — 0 disables it and elaborates NO hardware.
    *
    * ⛔ DEFAULT OFF, PENDING A BOARD MEASUREMENT. The sizing is trace-driven (real
    * System 7.5.3, per-instruction logical PCs out of MAME, `TC = 0x0000C000` so 8 KB
    * pages and all four TTRs zero): the shipping 32-entry array walks **10.25 times
    * per kinst** and a 32-entry victim buffer of its own evictions removes **73.9%** of
    * those walks, which is the infinite-ITLB floor. See `ItlbPlugin`'s class comment
    * for the full table and why a victim buffer beats both more ways and more sets.
    *
    * It is OFF rather than ON because the CYCLE value is bounded, not measured: the
    * board's `OFF_PERF_STALL_WALK` is the OR of both walkers' busy cycles and reads
    * 6.7% of all cycles, which caps the whole I+D walk bucket and puts the ITLB's
    * share at 3.0-5.4% depending on how much the two walkers overlap. `ITLB_VICTIM=32`
    * in the environment builds it for a one-off A/B. The counter that settles it is
    * `OFF_PERF_ITLB_WALK` (0x010A4) — already in every bitstream, never yet read.
    *
    * ⚠️ This lever CANNOT be measured on Dhrystone: the board's Dhrystone-only window
    * walks the ITLB 0.061 times per kinst. Its workload is boot and the Finder. */
  val itlbVictimEntries: Int = sys.env.get("ITLB_VICTIM") match {
    case Some(s) =>
      val n = try s.toInt catch {
        case _: NumberFormatException =>
          throw new IllegalArgumentException(s"ITLB_VICTIM must be an integer, got '$s'")
      }
      require(n == 0 || (n >= 2 && n <= 64),
        s"ITLB_VICTIM must be 0 (off) or 2..64, got $n")
      n
    case None => 0
  }
}
