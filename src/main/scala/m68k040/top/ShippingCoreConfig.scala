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

  /** RAS: on a mispredict redirect, re-apply the flushing branch's OWN return-address-stack
    * effect after the checkpoint restore. See `RasPlugin.branchRepair` for the mechanism
    * and for why a retire-time architectural shadow stack cannot do this job here.
    *
    * ⛔ OFF pending a board measurement, exactly like the two frontend prediction flags.
    * The defect it fixes is measured (`br-ind`: 1024 of 2050 retired mispredicts are the
    * callee returns of mispredicted calls, i.e. EVERY mispredicted call costs two) and the
    * fix is a pure predictor change -- the branch EU verifies every direction and target,
    * so a wrong guess is a perf loss and never a wrong result. But "a perf loss only" is a
    * claim; `mispredicts/kinst` on silicon is the measurement, and returns are 34.5% of
    * the board's mispredicts against 3.9% for conditional direction.
    *
    * ⚠️ HAD NO ENV OVERRIDE (fixed 2026-09-27, integration of the gated levers). This was
    * a hardcoded `false`, which meant "the build turns it on" was not a thing a build
    * could do -- flipping it needed an RTL edit, and an RTL edit is not a provenance a
    * build log can carry. `CPU_RAS_BRANCH_REPAIR=1` now enables it exactly like the two
    * frontend flags below, and the value is echoed in `SHIPPING_CONFIG`. */
  val rasBranchRepair: Boolean =
    sys.env.get("CPU_RAS_BRANCH_REPAIR").contains("1")
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

  // MEASURED, `BranchPredictIpcSpec`, IPC_SEED=1, throughput-v2 frontend with BOTH
  // slot-1 conditional knobs (the shipped one). RETIRED mispredicts per probe:
  //
  //   probe        OFF    compute   defer    both      cycles OFF -> both
  //   br-cap      1615      1250     1615       7      22239 -> 9025   (-59.4%)
  //   br-cap-fit   561       545       66       2      10340 -> 5854   (-43.4%)
  //   br-patt      601       600      305     304      13206 -> 10799  (-18.2%)
  //   br-ras-fit    18         4       18       2      72990 -> 72806
  //   br-ras      1066      1019     1066    1026      86147 -> 85740
  //   br-ind      2050      2050     2049    2049      51894 -> 51894
  //   br-ind-2     678       678      678     678      30494 -> 30494
  //   aggregate   6589      6146     5797    4068 (149.0 -> 92.0 MPKI, -38.3%)
  //
  // NEITHER FLAG WORKS ALONE, and the `br-cap` pair is why:
  //  - `br-cap-fit` FITS the 128-entry BTB and still mispredicted 27% of its branches.
  //    Deferral alone takes that 561 -> 66. So ~88% of it was never capacity at all --
  //    it was branches landing in SLOT 1, where nothing predicts them.
  //  - `br-cap` EXCEEDS the BTB. Deferral alone changes NOTHING (1615 -> 1615):
  //    handing a slot-1 branch to slot0 is useless when the table has no entry for it.
  //    Computation alone gets -23%. Together: -99.6%.
  // Deferral gives the branch a predictor; computation gives it a target.
  //
  // ⚠️ ZERO effect on `br-ind`/`br-ind-2` (indirect targets) and ~none on `br-ras`
  // (returns) -- correctly, since neither flag touches those. Those are the two buckets
  // that remain, and after this change they are 55% (returns) and 37% (indirect
  // targets) of what is left; conditional direction is 8%.
  //
  // ⚠️ NO REGRESSION on any of the 11 kernels, including the four workload-shaped
  // references -- but those references also show NO GAIN (`dhrystone-x0-cb` is
  // 0.21 MPKI and 3 mispredicts total), which is exactly the recorded bench-stall-mix
  // blind spot. A sim cycle win on a probe is NOT a board win. `mispredicts/kinst` on
  // silicon (~0.95% noise floor) is the only thing that can rank these.
}
