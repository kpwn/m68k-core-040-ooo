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
  val sqNarrowDrainMerge: Boolean = sys.env.get("SQ_NARROW_MERGE").contains("1")
}
