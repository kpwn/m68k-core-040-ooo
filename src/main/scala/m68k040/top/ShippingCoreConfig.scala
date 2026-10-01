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
  * by diverging from these values.
  *
  * ⛔ SECOND RULE, ADDED 2026-09-27 AFTER IT COST A BOARD NUMBER:
  *
  *     A flag that is default-OFF *PENDING MEASUREMENT* must have an env override, or it
  *     cannot be measured -- and the attempt will SILENTLY PRODUCE A BASELINE. A
  *     settled-decision constant does not need one, provided it PRINTS.
  *
  * This is narrower than "every flag needs an override", and the narrowness is the point.
  * `dcacheHitUnderMiss = true` is a settled decision: it prints in `SHIPPING_CONFIG`, and
  * nobody is going to A/B it. `rasBranchRepair` and `dcacheHitUnderMissRead` are the other
  * shape entirely -- each says "OFF pending a board measurement" in its own comment, which
  * is a standing invitation to build one ON.
  *
  * WHAT HAPPENED. `rasBranchRepair` was a hardcoded `false` with that invitation in its
  * comment. A `CPU_RAS_BRANCH_REPAIR=1` build was made, measured at 55,534.6
  * Dhrystones/sec on silicon, and attributed to the RAS repair. The variable was silently
  * ignored: that bitstream was a BASELINE, its own provenance line said
  * `rasBranchRepair=false`, and the number had to be withdrawn. `build_id` could not have
  * caught it -- it reads 0xD01DBDC5 on three different bitstreams -- so the provenance
  * line is the only discriminator there is.
  *
  * This is the FOURTH instance in this core of "the shipping configuration is not the
  * tested configuration", after `icMaintFlush` (wired in one top only), `RobPlugin(
  * lsOooIssue)` (set in no sim harness) and `alignedLoadFallThrough` (true on the board,
  * false in every fuzz DUT).
  *
  * ✅ AND THE CHECK THAT CATCHES IT, which costs one extra generation: after adding an
  * override, GENERATE TWICE -- once with the variable set, once without -- and DIFF THE
  * `SHIPPING_CONFIG` LINE. "The variable is accepted" is not the same claim as "the
  * variable reaches the netlist", and only the second one is worth anything. Both flags
  * below have been round-tripped that way. */
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
    *
    * NO ENV OVERRIDE, DELIBERATELY, and that is the second rule above applied rather than
    * forgotten: this is a SETTLED DECISION, not a knob pending measurement. It prints in
    * `SHIPPING_CONFIG`, which is the whole obligation such a constant has. */
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
    * miss-park handler); only the arm is disabled.
    *
    * ⚠️ HAD NO ENV OVERRIDE until 2026-09-27, and it is the exact shape that had just cost
    * a board number on `rasBranchRepair`: default-OFF *pending measurement*, with no way to
    * build it ON but an RTL edit. Anyone A/B-ing it would have got a plausible number from
    * a bitstream that does not contain the feature. `CPU_DCACHE_HIT_UNDER_MISS_READ=1`
    * enables it; default stays OFF, the value is echoed in `SHIPPING_CONFIG`, and the
    * override has been round-tripped through two generations (see the second rule above).
    *
    * ⚠️ AND WHEN IT IS MEASURED, MEASURE FMAX, NOT ONLY CYCLES. The reason it is off is a
    * ROUTED critical path, so a cycle win on the bench is not the question -- the question
    * is whether the `rdEn` cone still closes at 200 MHz. A build that gains 0.9% cycles
    * and loses the clock is a loss.
    *
    * ⚠️ TWO ENV NAMES, BOTH ACCEPTED (integration merge, 2026-09-28). This flag was given
    * an override independently on two branches under two different names:
    * `CPU_DCACHE_HIT_UNDER_MISS_READ` (`integ/all-gated`) and `CPU_DCACHE_HUM_READ`
    * (`perf/spec-imm` lineage). Picking one would have silently made the other a no-op --
    * which is this file's own failure family -- so BOTH are read, the long name taking
    * precedence when both are set. Prefer the long name in new work. */
  val dcacheHitUnderMissRead: Boolean =
    envFlag("CPU_DCACHE_HIT_UNDER_MISS_READ", envFlag("CPU_DCACHE_HUM_READ", false))

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
  /** ✅ THE p127 GATE ITEM IS RETIRED -- IT DID NOT REPRODUCE, AND THE METRIC THAT
    * RAISED IT CANNOT SUPPORT IT (measured 2026-09-28, slice `D3-BURST` work).
    *
    * ── WHAT WAS RECORDED HERE ──────────────────────────────────────────────────────
    * That `ExecuteLockStepSpec`'s "p127 CONTROL ... D-side zero" reported a widest
    * precise-drain window of 7 cycles with this flag OFF and 16 with it ON, against a
    * calibration band of [1, 12], so the ON arm failed -- attributed to "fill-forward
    * makes the LOAD side two cycles faster, so the ROB head reaches a precise store
    * sooner and parks longer on its own AXI B".
    *
    * ── WHAT IS MEASURED ON THE TRUNK, 2x2 OVER THIS FLAG AND `dcacheSectored` ──────
    * All SIXTEEN cells pass their bands. The `zero` row, which is the one that failed:
    *
    *     ff OFF / sec OFF  8      ff ON / sec OFF  8
    *     ff OFF / sec ON   9      ff ON / sec ON   8      band [1, 12]
    *
    * ── AND THE CONTROL THAT SETTLES IT ─────────────────────────────────────────────
    * `maxBusy` IS NOT REPRODUCIBLE RUN TO RUN. Two runs of the IDENTICAL arm
    * (`CPU_DCACHE_FILL_FORWARD=1`, same commit, same worktree) gave:
    *
    *     run 1   zero 8   dram20 18   stslow 75   stvslow 129
    *     run 2   zero 10  dram20 14   stslow 69   stvslow 129
    *
    * Three of the four rows disagree with themselves, by up to 6 cycles. The reason is
    * structural, not flaky: each run draws a FRESH RANDOM SIM SEED (the logs show
    * different seeds per invocation, and SpinalSim randomises the PRF), and `maxBusy` is
    * a per-run MAXIMUM -- an extreme-value statistic, which amplifies seed jitter rather
    * than averaging it out. `DcacheFillForwardSpec`'s own doc comment already records
    * this hazard class for a sibling measurement ("Accept-to-response was measured first
    * and JITTERED BY ONE CYCLE between otherwise identical runs ... neither of which
    * says anything about the D-cache's timing"); here the same hazard is larger because
    * the statistic is a max.
    *
    * So the recorded "7 OFF / 16 ON" is a ONE-SAMPLE-PER-ARM comparison on a
    * seed-varying extreme-value statistic. It is not evidence of a behavioural change,
    * and neither is any cell difference in the 2x2 above.
    *
    * ── AND THERE WAS NEVER A MECHANISM FOR IT IN THIS TEST ─────────────────────────
    * Independently of the statistics, the CONTROL kernel CANNOT see this lever:
    *   - it runs with `cacr = 0x00008000`, and `cacr(31)` IS the D-cache enable
    *     (`RobPlugin`: `_dcacheEnabled := exc.ss.cacr(31)`; `ExceptionUnit`:
    *     `excCacheMode = Mux(ss.cacr(31), WRITETHROUGH, INHIBITED)`). Bit 31 is CLEAR,
    *     so the D-cache is DISABLED and every D access is INHIBITED;
    *   - the kernel contains NO LOADS at all (two immediates, two `(A7)+` stores, three
    *     register moves, a branch);
    *   - and `fillFwdResp` is pulsed only in REPLAY's final arm, gated on
    *     `missCmode =/= INHIBITED`, off a `doAllocate` that requires the same.
    * There are no loads to make faster and no cacheable miss for the lever to fire on,
    * so the attributed mechanism cannot operate here whatever the numbers say.
    *
    * ── WHAT TO DO WITH THE BAND (still: do NOT widen the ceiling) ──────────────────
    * The original note was right that widening the ceiling to go green would be wrong,
    * and it is also unnecessary -- nothing fails. The real defect is the CEILING ITSELF:
    * the band's own message says it exists to catch the window going BELOW the floor
    * ("the dcfg D-side latency did NOT take effect and every p127 negative result in
    * this Part is vacuous"), and a floor is exactly what a max-statistic CAN support --
    * a max can only be dragged down by a latency that failed to apply. An upper bound
    * on a per-run maximum is not a measurement of anything. Assert the FLOOR only, or
    * take a fixed statistic over N seeds; do not move the ceiling.
    *
    * ── THE ONE SUBSTANTIVE CLAIM THAT SURVIVES ─────────────────────────────────────
    * Stall genuinely does move from load-response into precise-drain wait, and this
    * lever's value genuinely is a function of how much of the store stream is precise
    * (the precise path is ~12.4 cycles per store against ~1.31 fast, and the bench
    * default `copybackDtt = false` makes EVERY store precise). That is what explains
    * memcpy's +4.35% against `dhry-cb-128`'s +0.62-0.85%. It is just not what p127
    * CONTROL measured. */
  val dcacheFillForward: Boolean = envFlag("CPU_DCACHE_FILL_FORWARD", false)

  /** Experimental unsectored cacheable-load refill response directly from the
    * accepted AXI R beat. OFF pending error/collision and routed-timing gates. */
  val dcacheDirectRefillResponse: Boolean = envFlag("CPU_DCACHE_DIRECT_REFILL", false)

  /** D-cache: SECTORED 64-byte L1D lines -- four 16-byte SECTORS per line, each with its
    * own valid AND dirty bit. See `DcachePlugin.sectoredL1d` for the mechanism and
    * `docs/superpowers/specs/2026-09-27-l1d-sectored-quadrants-amendment.md` for the
    * ratified spec. This is slice `D3-BURST` (decision D4 / SS11.Q2 of the MSHR design
    * proposal), never bare "D3" -- `D3-SET` is the unrelated one-fill-per-set invariant.
    *
    * WHY SECTORS AND NOT PLAIN 64-BYTE LINES, in one sentence: the 68040's architectural
    * cache line is 16 bytes, so `CINVL` on address A must discard exactly those 16 bytes;
    * at an unsectored 64-byte line it would discard 64 and silently drop up to 48 bytes
    * of dirty data the programmer never asked to lose. Per-16-byte dirty sectors are what
    * make the widening CORRECT, not merely cheaper. (`CPUSHL` has the mirror problem but
    * is safe -- over-pushing writes back correct data and costs only time.)
    *
    * OFF pending measurement. The KNOWN, OWNER-ACCEPTED cost is a scattered-access
    * capacity regression: one tag now covers 64 bytes, so a workload touching one sector
    * per line gets 2 KB of effective capacity instead of 8 KB. The owner ratified that
    * trade explicitly ("memory bandwidth is valuable"); it is not a gate. */
  val dcacheSectored: Boolean = envFlag("CPU_DCACHE_SECTORED", false)

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
    // DEFAULT ON since 2026-09-30: P1 silicon A/B at 200 MHz, +4.93% Dhrystone (116,559 vs
    // 111,084), +2.1% Speedometer mix, clean boot 2/2. `CPU_RAS_BRANCH_REPAIR=0` still disables it.
    envFlag("CPU_RAS_BRANCH_REPAIR", true)
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
    // DEFAULT ON since 2026-09-30: P1 silicon A/B at 200 MHz, +4.93% Dhrystone (116,559 vs
    // 111,084), +2.1% Speedometer mix, clean boot 2/2. `CPU_COMPUTE_DIRECT_TARGETS=0` still disables it.
    envFlag("CPU_COMPUTE_DIRECT_TARGETS", true)

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
    // DEFAULT ON since 2026-09-30: P1 silicon A/B at 200 MHz, +4.93% Dhrystone (116,559 vs
    // 111,084), +2.1% Speedometer mix, clean boot 2/2. `CPU_DEFER_SLOT1_UNCOND=0` still disables it.
    envFlag("CPU_DEFER_SLOT1_UNCOND", true)

  /** Front-end: DEFER a slot-1 `DBcc` -- the 68k loop-closing branch -- so it becomes
    * slot 0 next cycle, where the BTB (whose `brType=0` is literally "Bcc/DBcc") can
    * predict it. See `FetchAlignPlugin.deferSlot1Dbcc`.
    *
    * OFF BY DEFAULT, pending a measurement. This is the hole `deferSlot1Uncond` leaves:
    * DBcc is line-5, so neither the line-6 unconditional predicate nor the line-6
    * conditional one matches it, and it is not JSR/JMP/RTS/RTR either. A slot-1 DBcc is
    * predicted by NOTHING and falls through -- on a loop that should have been taken.
    * ROM census: DBcc is 3.41% of control-transfer-shaped words STATICALLY, and a
    * loop-closing branch executes once per iteration.
    *
    * DBT is excluded in the predicate: it never loops, so deferring it costs an issue
    * slot for no prediction. Same reason RTD/RTE stay out of `deferSlot1Uncond`.
    *
    * Enable at generation time with `CPU_DEFER_SLOT1_DBCC=1`; echoed in
    * `SHIPPING_CONFIG`. */
  val deferSlot1Dbcc: Boolean =
    // DEFAULT ON since 2026-09-30: P1 silicon A/B at 200 MHz, +4.93% Dhrystone (116,559 vs
    // 111,084), +2.1% Speedometer mix, clean boot 2/2. `CPU_DEFER_SLOT1_DBCC=0` still disables it.
    envFlag("CPU_DEFER_SLOT1_DBCC", true)

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

  /** Store-queue (and its lock-step `pendMem` replay ring) depth. Default 8 = OFF.
    *
    * ROUTING-CONGESTION knob, measured 2026-09-27. `LsEuPlugin_logic_sq` is named in
    * EVERY congested window of both arms of the 200 MHz age-matrix A/B (share 16% ->
    * 32%), while the much larger `IssueQueuePlugin` appears in none. The reason is pin
    * density, not area: the SQ is age-ordered all-to-all disambiguation, so its cost
    * scales with `depth`, and at depth 8 its forward cone alone is 31% of the module's
    * RTL pin-bit load -- 32 x 28-bit line-equality comparators (4 per entry:
    * {slot A, slot B} x {query line, query second-half line}), which is 20% of the
    * module on its own.
    *
    * That matrix cannot be made cheaper at fixed depth without changing WHAT forwards
    * (a hashed/partial tag can over-approximate `stall` safely but never `hit`, and
    * over-approximating `stall` changes cycles; address banking is impossible because
    * ring position IS program order, so an entry cannot be steered to an address bank).
    * `depth` is therefore the only lever that scales the whole structure -- 8 -> 4
    * halves the comparator matrix, the youngest-overlap reduction, the per-entry
    * register array and every 1-of-N drain mux at once.
    *
    * ⚠️ This is a CAPACITY change, not a structural one. A sim A/B can prove it is
    * cycle-neutral on the bench kernels (it is: see the note in
    * `docs/PERF_AREA_LEDGER.md`), but the bench does not model the board's store-burst
    * mix, so cycle-identity here is NOT proof of board neutrality. Anything below the
    * measured peak occupancy of the real workload WILL cost stores a `WAIT_SQ` stall.
    *
    * `SQ_DEPTH` overrides it for an A/B without editing every DUT -- same reason
    * `LS_EARLY_AN` is env-read in `FullCoreSynth`. Must be a power of two (the ring
    * pointer wraps on it) and is bounded at 16 by `detachedStoreEntries`' own range. */
  val storeQueueDepth: Int = sys.env.get("SQ_DEPTH").map(_.toInt).getOrElse(8)

  /** Store queue: present a SPLIT store's slot A on the ordinary aligned-store drain
    * path (`useStrb = false`) and specialise slot B's explicit strobe/merge to the three
    * byte lanes it can ever cover, instead of carrying two full 16-lane dynamic decodes
    * and a 144-bit merge payload out of the module. Default false = OFF.
    *
    * ROUTING-CONGESTION lever, 2026-09-27. See `StoreQueue.narrowDrainMerge` for the
    * equivalence proof (`storeStrbA === storeStrb`; `storeDataA` differs from `storeData`
    * only on lanes the strobe clears; the clamped AXI sub-beat range agrees) and for the
    * permanent sim tripwire that pins slot B's specialised form to
    * `DcacheByteLane.storeStrbB`/`storeDataB` every cycle.
    *
    * Semantics-preserving: it changes the ENCODING of the drain command, never what
    * forwards and never which bytes reach memory.
    *
    * ⚠️ Honest size: this is ~12% of the store queue's RTL pin-bit load and an estimated
    * ~400 LUT -- HALF the ~800 LUT noise floor of this flow, so the area effect is not
    * measurable and the congestion effect may not be either. It is built because it is
    * the only SOUND reduction found in the block (the forward compare matrix, which is
    * 20% of the module, is exactly irreducible at fixed forwarding semantics -- see
    * `docs/PERF_AREA_LEDGER.md`), not because it is expected to move a congestion level
    * on its own.
    *
    * `SQ_NARROW_MERGE=1` turns it on for an A/B without editing every DUT. */
  val sqNarrowDrainMerge: Boolean = envFlag("SQ_NARROW_MERGE", false)

  /** Coalesce four committed, non-precise aligned COPYBACK LONG stores covering one
    * 16-byte line into a single SQ drain. Default OFF. */
  val sqCoalesceLines: Boolean = envFlag("CPU_SQ_COALESCE_LINES", false)
  /** Maximum cycles to hold a candidate SQ head while its line group forms. */
  val sqCoalesceHold: Int = envInt("CPU_SQ_COALESCE_HOLD", 16, 1, 255)

  /** Legacy-cache full-line allocate-without-fill. Non-blocking mode has native no-fill
    * and rejects this separate flag; default OFF. */
  val dcacheFullLineNoFill: Boolean = envFlag("CPU_DCACHE_FULLLINE_NOFILL", false)

  /** D4: a CACHE-INHIBITED access is a FULL MEMORY BARRIER in BOTH directions, enforced
    * entirely in the core. See `LsEuPlugin.inhibitedFullBarrier` for the mechanism and the
    * deadlock argument, and `docs/superpowers/specs/2026-09-29-throughput-architecture.md`
    * §2.4 item 3 for why it exists.
    *
    * WHY: it is the HARD prerequisite of P6 (the memory hot/cold split). Once cacheable
    * refills leave on a hot door and inhibited accesses on the cold one, the fabric keeps
    * NO cross-door order ("The fabric contributes NO cross-path ordering between doors and
    * does not need to" -- the SoC side's `cpu_socket.vh`, branch `feat/p6-dside-hot-door`).
    * Today's single D-side port hides most of the hazard behind the one-FSM D-cache; this
    * flag makes the order an explicit core property instead of a structural accident.
    *
    * WHAT IT ADDS, both directions:
    *   BEFORE -- an inhibited load (P4 or park drain) or inhibited store (SQ precise drain)
    *     launches only when the D-cache reports `busQuiesced` (no refill/eviction in
    *     flight, no store awaiting its B, no store-miss allocate, no maintenance walk), the
    *     table walkers have been fenced for at least one cycle, and neither walker owns a
    *     D-cache port.
    *   AFTER -- while an inhibited LOAD is outstanding (launched, terminal response not
    *     yet consumed) no LS op launches from P4 or the park; while any inhibited access is
    *     pending-at-head or outstanding the walkers get no D-cache grant. (An inhibited
    *     STORE's AFTER half already existed: `olderInhibitedStore` holds every younger load
    *     until the entry pops on its B.)
    *
    * OFF PENDING MEASUREMENT -- env `CPU_INHIBITED_FULL_BARRIER=1`; echoed in
    * `SHIPPING_CONFIG`. The OFF netlist is identical to the pre-D4 tree (every added term
    * elaborates only under the flag; the D-cache's `busQuiesced` register has no reader and
    * is pruned). */
  val inhibitedFullBarrier: Boolean = envFlag("CPU_INHIBITED_FULL_BARRIER", false)

  private def envInt(name: String, dflt: Int, lo: Int, hi: Int): Int =
    sys.env.get(name) match {
      case None => dflt
      case Some(v) =>
        val n = try v.trim.toInt catch { case _: NumberFormatException =>
          throw new IllegalArgumentException(s"$name='$v' is not an integer") }
        require(n >= lo && n <= hi, s"$name=$n is outside [$lo, $hi]")
        n
    }

  /** D-side bandwidth program, stage 2 (`docs/superpowers/specs/
    * 2026-09-30-dside-nonblocking-l1d-and-hot-door.md`): a NON-BLOCKING L1D. Every
    * cacheable miss (load, and COPYBACK store write-allocate) takes one of
    * `dcacheMshrs` MSHRs; same-line loads join it as secondaries, stores to an
    * in-flight line merge into it and ack at once, refill data answers every waiter
    * from the MSHR buffer, and a dirty victim leaves through a writeback buffer.
    * INHIBITED accesses keep the legacy FSM. REQUIRES `inhibitedFullBarrier` (D4).
    * OFF pending measurement; `CPU_DCACHE_NONBLOCKING=1`; echoed in `SHIPPING_CONFIG`. */
  val dcacheNonBlocking: Boolean = envFlag("CPU_DCACHE_NONBLOCKING", false)
  /** Registered response-slot shortcut for an uncontended clean NB refill. */
  val dcacheNbEarlyResponse: Boolean = envFlag("CPU_DCACHE_NB_EARLY_RESPONSE", false)
  /** MSHR count for `dcacheNonBlocking` (2..4: the hot door has 2 ID bits). */
  val dcacheMshrs: Int = envInt("CPU_DCACHE_MSHRS", 4, 2, 4)
  /** LS aligned-load descriptor ring. Keep 4 as the shipping default; 8/16 are
    * experimental concurrency arms and resize DLoadRid plus the source FIFO. */
  val lsLoadRingDepth: Int = envInt("CPU_LS_LOAD_RING_DEPTH", 4, 4, 16)
  /** Experimental P3 ordinary-load admission into the existing aligned ring. */
  val lsP3FastLoad: Boolean = envFlag("CPU_LS_P3_FAST_LOAD", false)
  /** Experimental paired P1 translation/VIPT launch for simple aligned MOVEA.L loads. */
  val lsP1EarlyLoad: Boolean = envFlag("CPU_LS_P1_EARLY_LOAD", false)
  /** Experimental consume of the registered probe-line result before slot capture. */
  val dcacheEarlyProbeLineForward: Boolean = envFlag("CPU_DCACHE_EARLY_PROBE_LINE_FORWARD", false)
  require(!lsP3FastLoad || inhibitedFullBarrier,
    "CPU_LS_P3_FAST_LOAD=1 requires CPU_INHIBITED_FULL_BARRIER=1")
  require((lsLoadRingDepth & (lsLoadRingDepth - 1)) == 0,
    "CPU_LS_LOAD_RING_DEPTH must be 4, 8, or 16")
  /** Route the non-blocking L1D's refills through the P6 hot door `axi_dh` (read-only,
    * 128b, ID = MSHR index) instead of `axi_d`. Requires `dcacheNonBlocking`; the SoC
    * must be built with `L2C_DH_PORT`/`DH_PORT_EN=1` (one Makefile knob, `CPU_AXI_DH=1`). */
  val dcacheHotDoor: Boolean = envFlag("CPU_AXI_DH", false)
  /** Experimental zero-empty-latency AR slice at the hot-door socket boundary.
    * R and every other AXI channel retain their FULL register slices. */
  val axiDhArFallThrough: Boolean = envFlag("CPU_AXI_DH_AR_FALL_THROUGH", false)
  require(!axiDhArFallThrough || dcacheHotDoor,
    "CPU_AXI_DH_AR_FALL_THROUGH=1 requires CPU_AXI_DH=1")
  /** Store-allocated MSHRs hold their AR this many cycles so trailing stores can
    * complete the 16 strobes and cancel the fill (write-path agent's hook). 0 = off. */
  val dcacheStoreAllocArDelay: Int = envInt("CPU_DCACHE_STORE_AR_DELAY", 0, 0, 15)
  /** Experimental one-cycle earlier non-blocking AR selection after allocation.
    * Keeps current WB/WT address gates and registered AXI AR output; OFF by default. */
  val dcacheNbEagerAr: Boolean = envFlag("CPU_DCACHE_NB_EAGER_AR", false)
  require(!dcacheNbEagerAr || dcacheNonBlocking,
    "CPU_DCACHE_NB_EAGER_AR=1 requires CPU_DCACHE_NONBLOCKING=1")
  /** Experimental direct selection of an eligible load allocation into the existing
    * registered AR holding slot. Requires the eager registered-selection arm. */
  val dcacheNbPreselectAr: Boolean = envFlag("CPU_DCACHE_NB_PRESELECT_AR", false)
  require(!dcacheNbPreselectAr || dcacheNbEagerAr,
    "CPU_DCACHE_NB_PRESELECT_AR=1 requires CPU_DCACHE_NB_EAGER_AR=1")
  /** Experimental release of installed MSHRs after registered stale-read stages
    * and waiters drain, instead of the fixed LINGER countdown. Default OFF. */
  val dcacheNbDynamicRelease: Boolean = envFlag("CPU_DCACHE_NB_DYNAMIC_RELEASE", false)
  require(!dcacheNbDynamicRelease || dcacheNonBlocking,
    "CPU_DCACHE_NB_DYNAMIC_RELEASE=1 requires CPU_DCACHE_NONBLOCKING=1")
  /** Experimental reuse of a resolved, clean-victim VIPT probe miss in the existing
    * MSHR allocation stage. Dirty or stale probes use the ordinary S1 path. */
  val dcacheNbProbeMissStage: Boolean = envFlag("CPU_DCACHE_NB_PROBE_MISS_STAGE", false)
  require(!dcacheNbProbeMissStage || (dcacheNonBlocking && !dcacheSectored),
    "CPU_DCACHE_NB_PROBE_MISS_STAGE=1 requires unsectored non-blocking D-cache")
  require(!dcacheHotDoor || dcacheNonBlocking, "CPU_AXI_DH=1 requires CPU_DCACHE_NONBLOCKING=1")
  require(!dcacheNbEarlyResponse || dcacheNonBlocking,
    "CPU_DCACHE_NB_EARLY_RESPONSE=1 requires CPU_DCACHE_NONBLOCKING=1")
  require(!dcacheNonBlocking || inhibitedFullBarrier,
    "CPU_DCACHE_NONBLOCKING=1 requires CPU_INHIBITED_FULL_BARRIER=1 (D4 is the hard prerequisite)")
  require(!(dcacheNonBlocking && dcacheFullLineNoFill),
    "CPU_DCACHE_NONBLOCKING and CPU_DCACHE_FULLLINE_NOFILL are mutually exclusive (noFill is native under NONBLOCKING)")

  /** I-cache next-line PREFETCH, reset value of `IcachePlugin.prefetchEnable`.
    *
    * ✅ ON. Board-measured: demand I-misses HALVE (127.9 -> 61.2 per kinst) for +2.6%
    * IPC, coverage 86.3%. This is a settled decision, not a knob pending measurement.
    *
    * ⛔ BUT IT HAS A BUILD-TIME OFF SWITCH, AND THAT SWITCH WAS INVISIBLE. The plugin
    * reads `DBG_IC_PREFETCH_DISABLE` directly to start the register False for a
    * demand-fetch-only diagnostic bitstream -- a REAL netlist change (the RegInit's
    * reset value) that appeared NOWHERE in the build log. A diagnostic image and a
    * shipping image were therefore indistinguishable by their own provenance, which is
    * the same defect class as the withdrawn `rasBranchRepair` number. Centralised here
    * (integration merge, 2026-09-28) so `SHIPPING_CONFIG` reports it.
    *
    * Note the INVERTED sense of the variable: it is a DISABLE, kept that way so every
    * existing build and testbench that does not set it is bit-identical to before. */
  val icachePrefetch: Boolean = !envFlag("DBG_IC_PREFETCH_DISABLE", false)
}
