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

  /** Front-end: COMPUTE a PC-relative unconditional branch's target from the
    * displacement already in the aligner slot, instead of only ever recalling one from
    * the BTB/FTB. See `FetchAlignPlugin.computeDirectTargets` for the mechanism and the
    * soundness gates.
    *
    * OFF BY DEFAULT, pending a board measurement. Everything about it is cheap --
    * one 32-bit adder plus an extra input on an existing mux, no table, no BRAM, no
    * predecode widening -- and it reaches only the DATA input of `predictTargetReg`, so
    * it adds no term to the combinational redirect / `ftbBlocked` cone. But it makes the
    * front end redirect on branches it previously left for the branch EU, and the only
    * thing that can rank it against the board's real branch footprint is
    * `mispredicts/kinst` read on silicon (~0.95% noise floor); the bench's kernels
    * cannot, which is why `BranchPredictIpcSpec`'s `br-cap` probe exists.
    *
    * Enable at generation time with `CPU_COMPUTE_DIRECT_TARGETS=1`; the value is echoed
    * in the `SHIPPING_CONFIG` line. */
  val computeDirectTargets: Boolean =
    sys.env.get("CPU_COMPUTE_DIRECT_TARGETS").contains("1")

  /** Front-end: DEFER an UNCONDITIONAL control transfer that lands in SLOT 1, so it
    * becomes slot 0 next cycle and the decode-time BTB / RAS / computed-target paths can
    * see it at all. See `FetchAlignPlugin.deferSlot1Uncond`.
    *
    * OFF BY DEFAULT, pending a board measurement. A slot-1 branch has no decode-time
    * predictor (the slot-1 BTB read was deleted as the design's #1 failing setup cone)
    * and the FTB that replaced its deferral holds ONE branch per 8-byte window, so two
    * control transfers in one window leave one of them predicted by nothing. Deferral
    * costs ONE fetch cycle and is unambiguously right for an UNCONDITIONAL (taken by
    * definition); it is deliberately NOT extended to conditionals, where a not-taken
    * fall-through is already free -- that case has its own knobs.
    *
    * Enable at generation time with `CPU_DEFER_SLOT1_UNCOND=1`; echoed in
    * `SHIPPING_CONFIG`. */
  val deferSlot1Uncond: Boolean =
    sys.env.get("CPU_DEFER_SLOT1_UNCOND").contains("1")
}
