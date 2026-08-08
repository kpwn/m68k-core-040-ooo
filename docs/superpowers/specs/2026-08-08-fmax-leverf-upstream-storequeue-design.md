# FMax closure, "Lever F": sever the D-cache store-S2 tag compare from the write-allocate drain ACK (design)

**Date:** 2026-08-08
**Repo:** `/home/qwertyoruiop/m68k-core-040-ooo`, branch `feat/rob-predictor-mem`, HEAD `4590ca4`
**Target:** the **upstream 67.9 %** of the `DcachePlugin tagMem -> RobPlugin fault*Store`
critical-path family — the segment that lives in `DcachePlugin` + `StoreQueue` +
`LsEuPlugin`, *not* in `RobPlugin`.

**STATUS — READ THIS FIRST.**
This spec is **RTL-grounded, NOT netlist-confirmed.** The machine was saturated for the
whole of this pass (`free -g`: 1 GB free / 37 GB swap, three concurrent Vivado
processes, one from a sibling project) and the dispatch brief forbade launching any
new Vivado invocation — including a read-only `open_checkpoint` — below ~10 GB free.
**No checkpoint was opened by this pass. No number below was measured by this pass.**

Every delay figure quoted is **carried over verbatim** from the round's existing
cell-by-cell trace
(`…/374c5f2c-…/scratchpad/fmax-robplugin-dominant-grounding-report.md` §4, checkpoints
`scratchpad/wt-lce/fp/A40C_routed.dcp` / `B52C_routed.dcp`). What *this* pass adds is
the **RTL mechanism** behind that trace: which source line each netlist node is, why
the arc exists, and — the actual finding — **that the arc is a false dependency that
can be deleted without touching latency, IPC, or any correctness property.**

Per this project's own standing lesson
(`2026-08-07-fmax-slice3-frontend-dsteashift-collapse-design.md` "POST-IMPLEMENTATION
CORRECTIONS", and the `moveLineSize` episode): **an RTL story is not a netlist result.**
§9 states exactly which single Vivado query must confirm this before implementation,
and §10 what must be re-measured after.

---

## 0. What the family actually is, in source terms

The round's existing trace names the worst endpoint's cells. Reconciled here against
live source (all line numbers re-verified against HEAD `4590ca4`, they had **not**
drifted):

| # | netlist node (from the existing A40C trace) | source |
|---|---|---|
| — | `DcachePlugin_logic_tagMem_0_reg/CLKARDCLK` (launch) | `DcachePlugin.scala:117` — `val rdTag = Vec(tagMem.map(_.readSync(rdSet, rdEn)))` |
| 1-3 | `cbHitAckReg_i_71` → CARRY8 → `cbHitAckReg_i_6`, net **`stS2HitVec_0`** | `DcachePlugin.scala:485-487` — `stS2HitVec(w) := valids(w)(stS2Set) && (rdTag(w) === stS2Tag)`. The CARRY8 is the **21-bit tag equality**. (LUT names say `cbHitAckReg` only because `cbHitAckReg` at `:1516` is another load of the same shared cone — the *timed* path continues below.) |
| 4-5 | `axi_r_ready_INST_0_i_3` → `axi_r_ready_INST_0_i_1`, net **`when_DcachePlugin_l991`** | `DcachePlugin.scala:588-598` — **`refillWriteHold`**, whose 3rd term is `stS2ArrayWrite && stS2HitVec(victimWay)` (`:498`, `:598`). Vivado merged the two identical consumers `axi.r.ready := !refillWriteHold` (`:922`) and `when(!refillWriteHold)` (`:991`) into one LUT. |
| 6-7 | `pendReady[2]_i_7` → `pendReady[2]_i_6`, net `RobPlugin_logic_exc_dcStoreAck` | `DcachePlugin.scala:991-1006` — REPLAY's write-allocate merge arm drives **`storeAllocAckReg := True`** (`:1005`), which is a **plain combinational `Bool`, not a register** (`:202`), and feeds `storeAckReg := storeBAck \|\| cbHitAckReg \|\| storeAllocAckReg` (`:1574`) → `DcacheService.storeAck` (`:1692`). |
| 8 | `pendReady[2]_i_1`, net **`sq_io_sqCompletion_valid`** | `LsEuPlugin.scala:232` `sq.io.drainAck := dcache.storeAck` → `StoreQueue.scala:405-425` `when(io.drainAck && drainBusy) { … when(precises(head)) { io.sqCompletion.valid := True } }` |
| 9 | `faultSupStore_0_i_4`, net **`LsEuPlugin_logic_applyFast`** | `LsEuPlugin.scala:1660` — `applyFast = (pendApply === pendReady) && sq.io.sqCompletion.valid`; `:1663-1690` drives `sqCompletionPort` / `sqFaultCompletionPort` |
| 10-14 | `sqFaultSel_0_i_5` → `sqFaultSel_42_i_1` → `faultAddrStore_42[31]_i_8/_i_2` | `RobPlugin.scala:777-785` (`sqFaultOh`/`sqFaultSel`, LS/ROB Lever C's kept per-entry select) and `:806-817` (the fault register-file write) |

**One combinational cycle crosses four modules:** `DcachePlugin` → `StoreQueue` →
`LsEuPlugin` → `RobPlugin`. Carried-over budget (A40C, slack −1.378 ns, 5.312 ns data path):

| seg | span | what | Δ ns | % |
|---|---|---|---:|---:|
| S1 | 0.076→0.905 | tag BRAM CLK→DOUT | 0.829 | 15.6 |
| S2 | 0.905→2.175 | BRAM-out route + **21-bit store-S2 tag compare** | 1.270 | 23.9 |
| S3 | 2.175→2.955 | `refillWriteHold` → REPLAY gate → `storeAllocAck` → `storeAck` | 0.780 | 14.7 |
| S4 | 2.955→3.683 | SQ `drainAck` → `sqCompletion.valid` → `applyFast` → one-hot | 0.728 | 13.7 |
| **S1-S4 upstream** | | | **3.607** | **67.9** |
| S5-S7 | 3.683→5.388 | ROB one-hot broadcast + per-entry select + data mux | 1.705 | 32.1 |

**S1+S2 = 2.099 ns = 39.5 % of the whole path is the tag BRAM read plus the tag
compare.** Everything this spec proposes is aimed at exactly those two segments.

---

## 1. Mechanism class — determined, not guessed

The brief offered three classes. The answer is **none of the three cleanly**, and
saying so precisely matters, because this project has been burned by picking the wrong
one:

* **Not placement.** The already-run controlled experiment (same netlist, `pb_decode`
  X75 vs X87) moved this segment 3.607 → 3.352 ns: **+0.255 ns, 7 %.** Real, already
  banked (`7afc26b`), and nowhere near enough. The segment survives the best placement
  the design has.
* **Not genuine logic depth.** A BRAM read + a 21-bit compare + a 3-input FSM gate is
  not padded. There is no cone to re-associate and no pipeline register that would help
  *without* the finding below (see §7 for why the obvious pipeline registers are wrong
  here).
* **It is a FALSE DEPENDENCY — an unreachable-context recompute.** The tag compare is
  pulled into the ack path by exactly one term, and **that term is provably constant
  `False` in the FSM state that consumes it.** This is the same *class* as the round's
  three zero-cost wins (Frontend Lever A, Lever U1, the IQ-scoreboard `!slowFire` gate):
  a live combinational term that is architecturally dead where it is read. It differs
  only in *why* it is dead — not "already computed elsewhere", but "the guarded
  condition cannot occur in this state".

### 1.1 The proof

`DcachePlugin.scala:991` gates REPLAY's write-allocate merge — and therefore the drain
ACK, and therefore the whole downstream cone — on `!refillWriteHold`, where
(`:588-598`):

```scala
val refillWriteHold = (stS1Valid && (stS1Set === missSet)) ||
                      (stS2Valid && (stS2Set === missSet)) ||
                      (stS2ArrayWrite && stS2HitVec(victimWay))
```

and `stS2ArrayWrite = stS2Valid && !stS2Inhibited && stS2HitAny` (`:498`).

**Every term requires `stS1Valid` or `stS2Valid`.** So the claim reduces to: *no store
is anywhere in the D-cache store pipe while the FSM is in `REPLAY` with
`refillReqIsStore = True`.*

`storePort` has exactly **two** producers (verified by exhaustive grep over
`src/main/scala`):

1. **`LsEuPlugin.scala:231` — `dcache.store << sq.io.drain`.**
   `StoreQueue.scala:215` `drainIssue = headReady && !drainBusy`; `:404` sets
   `drainBusy` on issue; `:405` clears it **only** on `io.drainAck`, and
   `LsEuPlugin.scala:232` wires `io.drainAck := dcache.storeAck`. So the SQ presents
   **at most one store at a time** and cannot present the next until the previous is
   acked.
   Now trace the drain-miss store itself: at its S2 (`DcachePlugin.scala:1458-1466`,
   the COPYBACK-miss arm) it sets `stAwDone := True; stWDone := True` — **no AXI beat
   is ever issued**, so `storeBAck` (`:1572`) can never fire for it — and it is a miss,
   so `cbHitAckReg` (`:1516`) is `False`. **Its only possible ack is the very
   `storeAllocAckReg` this gate produces.** Therefore `drainBusy` is *still set* for the
   whole EVICT_WR/REFILL/REPLAY excursion, `drainIssue` is `False`, and no further store
   can be presented. ∎
2. **`LsEuPlugin.scala:1784-1787` — the ExceptionUnit's frame writer**
   (`when(excActive && excStoreValid)`, overriding the SQ mux).
   `ExceptionUnit.scala:866-867` `E_DRAIN.whenIsActive { when(sqDrained) { … } }` gates
   every store-issuing state (`E_STORE`/`E_STWAIT`, `:909`/`:911`) behind `sqDrained`;
   `FullCoreSynth.scala:341` wires `exc.sqDrained := lsEu.sqEmptySig`;
   `LsEuPlugin.scala:234` `sqEmptySig := sq.io.empty`; `StoreQueue.scala:537`
   `io.empty := !valids.reduce(_ \|\| _) && !drainBusy`. With `drainBusy` held set by (1),
   `sqDrained` is `False` for the entire window. The RTE path (`R_DRAIN`, `:1001-1002`)
   and the sysOp path (`S_DRAIN`, `:859-860`, which additionally waits on `dcQuiesced`)
   carry the same gate. ∎

**Both producers are structurally blocked by the same register (`drainBusy`) that the
gate's own output is the sole clearer of.** The dependency is not merely improbable —
it is closed by a self-referential interlock.

### 1.2 A second, independent corroboration: the arc is functionally dead end-to-end

`StoreQueue.scala:425` fires `io.sqCompletion.valid` only `when(precises(head))`.
`LsEuPlugin.scala:588` sets `sq.io.alloc.payload.precise := !fastStore`, and `:460-461`:

```scala
val fastStore = mmuCtrl2.map(_.mmuEnable).getOrElse(False) &&
                (s2Cmode =/= CacheMode.INHIBITED)
```

A `COPYBACK` `s2Cmode` implies MMU-on (it comes from a page attribute) and
`=/= INHIBITED`, so **every COPYBACK store is `fastStore` ⇒ `!precise`.** This is the
same invariant `DcachePlugin.scala:963-964` already states in prose ("never precise —
a COPYBACK drain is always fast-path by construction"). And the ExceptionUnit's stores
never enter the SQ at all, so `drainBusy` is `False` and `StoreQueue.scala:405`'s guard
excludes them.

⇒ **`storeAllocAckReg` can never cause `sq.io.sqCompletion.valid`.** The timed path
from node 7 onward is a *functionally unreachable* arc that the tools must nevertheless
close. This is corroboration, not the fix — the fix in §2 does not rely on it, and §8
explains why encoding it as a timing exception would be wrong.

---

## 2. The design

### 2.1 The change

`DcachePlugin.scala`, immediately after `refillWriteHold` (`:588-598`), add a
**second, register-only** interlock, and use it at the REPLAY write-allocate site only:

```scala
    /** FMax Lever F: REPLAY's OWN write-allocate hold — deliberately NOT
      * `refillWriteHold`.
      *
      * `refillWriteHold`'s three terms are all live functions of the store-S2 tag
      * compare (`stS2HitVec`, :487), i.e. of the tag BRAM read output. REFILL's
      * `axi.r.ready` (:922) genuinely needs that precision: a LOAD-miss refill runs
      * fully concurrently with an unrelated store stream, so it must hold ONLY on a
      * real same-set/same-way collision or it would stall every refill against every
      * store.
      *
      * REPLAY's write-allocate merge below (:991) is the opposite case. It only ever
      * runs with `refillReqIsStore`, i.e. servicing a COPYBACK drain miss — and that
      * drain's ONLY ack source is the very `storeAllocAckReg` this gate produces
      * (its S2 arm at :1458-1466 issues no AXI beat, so `storeBAck` cannot fire, and
      * it missed, so `cbHitAckReg` cannot fire). So `StoreQueue.drainBusy` is still
      * set, `drainIssue` (StoreQueue.scala:215) is False, and the ExceptionUnit's
      * own store path is gated behind `sqDrained` == `sq.io.empty`, which is False
      * for the same reason. NO store can be in S0/S1/S2 here.
      *
      * This predicate is therefore a STRICT SUPERSET of `refillWriteHold` restricted
      * to this site (it holds on every cycle `refillWriteHold` would, and more), so
      * it CANNOT weaken the P4.4 array-write-port interlock — the "delay, never drop"
      * property is preserved unconditionally, WITHOUT relying on the single-
      * outstanding producer contract for CORRECTNESS. The contract is relied on only
      * for the (unmeasurable, because unreachable) claim that this never actually
      * holds, and `storeDrainHoldFired` below makes even that claim falsifiable.
      *
      * FMax purpose: both terms are plain registers (:461, :469), so this gate — and
      * with it `storeAllocAckReg`/`storeAck`/`sqCompletion`/the ROB fault write —
      * launches from a flop instead of from the tag BRAM through a 21-bit compare. */
    val storeDrainRefillHold = stS1Valid || stS2Valid
```

and at `:991`:

```scala
-          when(!refillWriteHold) {
+          when(!storeDrainRefillHold) {
```

`REFILL`'s `axi.r.ready := !refillWriteHold` (`:922`) is **unchanged** — see §8.

### 2.2 Falsifiability hook (mirrors this file's own `diagFaultKind*Fires` precedent)

```scala
    // Proves §1.1's producer-contract argument instead of assuming it. Same shape as
    // this file's diagFaultKind0Fires/Kind1Fires collision detectors, and the same
    // poke-able opt-out as `diagFaultExpected` (:1680) so the deliberate
    // contract-violating positive control in §10.2 can exercise the hold on purpose.
    val storeDrainHoldFired = Bool(); storeDrainHoldFired := False
    storeDrainHoldFired.simPublic()
    val storeDrainHoldExpected = RegInit(False); storeDrainHoldExpected.simPublic()
```

driven `True` inside REPLAY's `refillReqIsStore` arm when `storeDrainRefillHold` is
asserted, with

```scala
    GenerationFlags.simulation {
      assert(!(storeDrainHoldFired && !storeDrainHoldExpected),
        "DcachePlugin: a store was in S1/S2 while a COPYBACK drain-miss write-allocate " +
        "merge was held in REPLAY -- the single-outstanding store-producer contract " +
        "(StoreQueue.drainBusy + ExceptionUnit's sqDrained gate) that Lever F's timing " +
        "argument rests on was violated. The merge was correctly DELAYED (not dropped), " +
        "so this is not corruption -- but the contract must be re-derived before trusting " +
        "the FMax claim.",
        FAILURE)
    }
```

Note the severity choice is deliberate and matches the file: a fire is *not* a
correctness bug (the hold still protects), it is an **invalidated premise**, which is
exactly what a loud FAILURE should surface.

### 2.3 Expected effect

`storeAllocAckReg` becomes a function of registers only:
`REPLAY_active && refillReqIsStore && !missFault && (missCmode =/= INHIBITED) &&
!(stS1Valid || stS2Valid)` — 5 register inputs, one LUT6.

* **S1 (0.829 ns) and S2 (1.270 ns) leave the path entirely: −2.099 ns.**
* On the A40C arm: 5.312 → ~3.2 ns ⇒ slack ≈ **+0.8 ns** against the 4.000 ns
  constraint (was −1.378).
* On the current shipped floorplan (the `B52C` geometry, family worst −1.096 /
  5.030 ns with a 3.352 ns upstream): 5.030 → ~3.0 ns ⇒ slack ≈ **+1.0 ns**.
* Either way the family is **retired, not improved** — the same outcome shape Lever B
  produced for the frontend dst-EA family. That matters because Lever B's own probe
  reported "the probe's new worst-8 are all the LS/ROB `tagMem -> faultAddrStore`
  family", i.e. **this family is the current binding one.**

**These are arithmetic on a carried-over trace, not a measurement.** A re-place will
redistribute route; the ROB tail (S5-S7, 1.705 ns, 89 % route) is untouched and stays
the residual. §10 is binding.

### 2.4 Cost

* **Latency: zero.** The new predicate is `False` on every reachable cycle at this site
  (§1.1), so the merge fires on exactly the cycle it fires today.
* **IPC: zero**, for the same reason. No new stall, no new state, no reordering.
* **Area: +1-2 LUTs.** Today `axi.r.ready` and the `:991` gate share one LUT; they
  stop sharing. `refillWriteHold` itself is unchanged and still needed at `:922`.
* **No new register, no new pipeline stage, no protocol change.**

---

## 3. Correctness properties that MUST be preserved (binding checklist)

This is a store-completion / D-cache-refill / fault-delivery path. The properties below
are the ones this subsystem's own history says get broken. A reviewer must tick each.

| # | property | why it survives |
|---|---|---|
| C1 | **P4.4 same-SET array-write interlock** (`DcachePlugin.scala:524-598`): a refill/write-allocate array write must never land inside a store drain's S1→S2 window, or the store's registered tag-read goes stale (WT: line corruption; CB: lost store). | The new predicate holds on a **superset** of those cycles (`stS1Valid \|\| stS2Valid` ⊇ `stS1Valid && sameSet`, `stS2Valid && sameSet`). Strictly stronger. |
| C2 | **P4.4 same-WAY/different-SET write-port collision** (`:541-566`, `DcacheDrainRefillRaceSpec`'s second sweep): `wrEn/wrSet/wrData` is one port *per way*, shared across sets; the FSM elaborates last and silently wins. | Also a superset: term 3 required `stS2Valid`, which the new predicate covers unconditionally. |
| C3 | **Retry-don't-drop.** The hold must delay the merge, never discard it. | The `otherwise` behaviour at `:1008` (stay in REPLAY, retry) is unchanged. And a *stronger* hold cannot livelock here: a store in S1 always advances (`:1397-1406` — S1 holds only on `fsm.loadUsesPort`, which the REPLAY store arm never sets, or `maintUsesPort`, which cannot be active because `dcIdleForMaint` (`:1151`) requires `!busy` and `busy` is True throughout REPLAY), and S2 completes in one cycle. Bounded at ≤2 cycles even under a contract violation. |
| C4 | **One-ack-per-store** (`:1596-1601` assert): exactly one of `storeBAck` / `cbHitAckReg` / `storeAllocAckReg` per store. | `storeAllocAckReg`'s *firing cycle* is unchanged; only its *gate expression* changes. |
| C5 | **Precise-drain fault delivery** — `sqFaultCompletion` must reach `RobPlugin.faultAddrStore` in the cycle `LsEuPlugin.scala:1663` expects. | Untouched. Not one line of `StoreQueue`, `LsEuPlugin` or `RobPlugin` changes. |
| C6 | **The `applyFast` same-cycle calibration** (`LsEuPlugin.scala:1646-1656`): the `irq-nmi` test needs `sqCompletionPort` to fire on the *same* cycle as `sq.io.sqCompletion`; a documented earlier attempt to register it "added a SECOND stage and mis-timed the injection by a cycle". | This lever adds **no register anywhere on that arc.** This is the single strongest reason to prefer it over every pipeline-register alternative (§7). |
| C7 | **`refillReqIsStore = False` (LOAD-miss) refills keep full-precision holding.** | `:922` is untouched; only the `refillReqIsStore` arm's gate changes. |
| C8 | **The five ROB fault write ports' priority order** (`RobPlugin.scala:773-775`: `alloc1 > alloc0 > eu > sq > ls`, textual order load-bearing). | No ROB edit. |
| C9 | **The interlock must not become contract-dependent for correctness.** (Explicitly why the "just delete the gate" variant is rejected — §7.1.) | The hold is retained, only made coarser. A contract violation degrades to a ≤2-cycle delay, never to a dropped array write. |

---

## 4. Blast radius

Files touched: **one** — `src/main/scala/m68k040/cache/DcachePlugin.scala`.
Lines: ~1 changed + ~25 added (mostly the rationale comment and the sim hook).
No interface, no port, no plugin service, no XDC, no test-harness change required for
the change itself (§10.2 adds one new directed test).

---

## 5. Why this and not the ROB side

Already settled by the round's own evidence and restated here only so it is not
re-litigated: the ROB tail is 32 % of the path, 89 % route, and its two dominant hops
are placement-distance, not fanout (a 52× fanout reduction made a hop **16 % slower**).
Task #127 (fold the fault `Reg` arrays into `Mem`) is bounded at that 32 % and is not a
bounded slice (5 write ports with documented load-bearing priority). A dedicated
`RobPlugin` pblock was refuted on measurement (span does not predict failure; the two
widest ROB structures have zero failing endpoints). **The upstream 68 % is where the
lever is, and §1.1 is the lever.**

---

## 6. What this does NOT fix

* **S3+S4 (1.508 ns)** — `storeAllocAck` → `storeAck` → SQ `drainAck` →
  `sqCompletion.valid` → `applyFast`, still one combinational cycle across three
  modules. Retained deliberately (C6).
* **S5-S7 (1.705 ns)** — the ROB one-hot broadcast + per-entry select + data mux.
  Unchanged; this is the residual after Lever F and it is a placement problem.
* Predicted residual ≈ 3.0-3.2 ns ⇒ this family stops blocking 200 MHz **and** clears
  250 MHz. If §10.1's measurement disagrees, §7.2 is the contingency.

---

## 7. Alternatives evaluated and rejected

### 7.1 Delete the hold at `:991` entirely — **REJECTED**
§1.1 proves the predicate is `False`, so deleting it is *behaviourally* identical to
this design. But deletion makes the P4.4 interlock **depend on the single-outstanding
producer contract for CORRECTNESS**, and that contract is enforced in a different file
(`StoreQueue.drainBusy`) and is *not* enforced at all in the standalone probe DUTs
(`DcacheProbePlugin` drives `storeIn` directly — see `DcacheDrainRefillRaceSpec`).
`DcachePlugin.scala:568-587` already records this project's judgement on exactly this
question, in this exact file, about this exact signal ("THIS MAKES THE HOLD'S LIVENESS
A CONTRACT the store side must preserve, not a structural guarantee"). Keeping a
strictly-stronger register-only hold gets 100 % of the timing win at zero correctness
exposure. **The timing win is identical; only the risk differs.**

### 7.2 Register `storeAllocAckReg` (+1 cycle on the write-allocate ack) — **CONTINGENCY ONLY**
Would additionally sever S3+S4. The latency *is* genuinely hidden: the merge is a
post-commit event entirely off the retire timeline (`:993-994` — "the fast-path store
retired long ago at SQ-alloc"), it only delays the SQ pop of an *already-retired* fast
store by one cycle, and — per §1.2 — it cannot delay any precise completion, because
`storeAllocAckReg` can never be a precise store's ack. So it satisfies both of the
coordinator's conditions for an acceptable latency trade. **But it is unnecessary if
§10.1 confirms Lever F alone retires the family, and it is strictly more invasive
(new register, new one-cycle window against `dcIdleForMaint`/`maintQuiesced` at `:1151`
which would need re-derivation).** Do not implement speculatively; implement only if
measurement demands it.

### 7.3 Register `sq.io.sqCompletion` → `sqCompletionPort` — **REJECTED, known-broken**
`LsEuPlugin.scala:1646-1656` documents that the always-registered form
"added a SECOND stage on top and mis-timed the injection by a cycle" against the
`irq-nmi` test's calibration. This is a recorded, reproduced failure, not a risk
estimate. (This is also the blocker the prior grounding pass flagged as "F2, not a
session-scale lever" — correctly.)

### 7.4 `set_false_path` on the (functionally dead, §1.2) `storeAllocAck` → ROB arc — **REJECTED**
The deadness derives from an RTL invariant (`COPYBACK ⇒ fastStore ⇒ !precise`,
`LsEuPlugin.scala:460`) that no XDC can enforce. A future change to the fast/precise
classification would silently un-dead the arc with no timing signal. Encoding an RTL
invariant in a timing exception is exactly the class of trap this project's standing
rules exist to avoid.

### 7.5 Apply the coarse register-only hold to `:922` as well — **REJECTED**
That site serves LOAD-miss refills, which *do* run concurrently with an unrelated store
stream. A set-agnostic hold there would stall every refill against every store
anywhere in the cache — a real IPC cost for zero FMax benefit (that endpoint is
`axi.r.ready`, not a ROB endpoint).

---

## 8. Non-goals

* No change to `refillWriteHold` itself, to `axi.r.ready`, or to any AXI ordering
  interlock (`storeWantsAxi` / `evictAxiPairOpen` / `maintAxiPairOpen` — P4.3
  revision 3, proven necessary by two failed attempts).
* No change to `RobPlugin`, `StoreQueue`, `LsEuPlugin`, or any XDC.
* No revisiting of task #127, the ROB pblock, or fanout factoring of the fault
  write-select (all three already refuted on measurement this round).

---

## 9. **REQUIRED before implementation** — the one netlist query this pass could not run

Machine contention blocked all Vivado use. Before writing any RTL, run **one**
read-only query against the freshest routed checkpoint (do **not** regenerate one;
reuse `scratchpad/wt-lce/fp/*_routed.dcp` or whatever the concurrent Lever-B /
combined gate produced), and confirm all three:

```tcl
open_checkpoint <freshest>_routed.dcp
# Q1: does the family still launch from the tag BRAM and land in the ROB fault file?
report_timing -from [get_pins DcachePlugin_logic_tagMem_*_reg/CLKARDCLK] \
              -to   [get_cells -hier -filter {NAME =~ *RobPlugin_logic_fault*Store_*_reg*}] \
              -max_paths 5 -nworst 1 -path_type full_clock_expanded
# Q2: does the worst such path still traverse the when_DcachePlugin_l991 net?
report_timing -through [get_nets -hier *when_DcachePlugin_l991*] -max_paths 3
# Q3: how many endpoints < -1.0 does this family still own, post-Lever-B?
get_timing_paths -max_paths 40000 -nworst 1 -slack_lesser_than -1.0
```

**Accept the design if** Q1's worst path still passes through `when_DcachePlugin_l991`
(i.e. §0's node-5 reconciliation still holds on the current netlist) **and** Q3 shows
this family still owning a material blocking population. **Reject / re-ground if**
Lever B's re-place moved the family's launch or its dominant sub-path elsewhere — the
carried-over A40C trace predates Lever B, and Lever B's own probe explicitly reshuffled
the worst-8.

---

## 10. Verification requirements (binding)

### 10.1 Timing
1. Full-core **OOC A/B** in an isolated `git worktree` (mandatory — `git checkout <sha>`
   in the shared tree caused a real collision in task #199), same flow both arms,
   recording synth wall time explicitly.
2. Confirm on the post-A netlist that **no** `DcachePlugin_logic_tagMem_*_reg` pin
   reaches **any** `RobPlugin_logic_fault*Store_*` or `RobPlugin_logic_completes_*`
   endpoint (`report_timing -from … -to …` must return "no timing paths" or only
   slack-positive ones). *This, not the WNS number, is the acceptance criterion* — the
   claim is "the family is severed", and a WNS improvement could come from placement
   noise.
3. Full **post-route double-run** gate on an **uncontended** machine. FMax on this box
   is documented-unreliable under contention (214.3 vs 163.9 MHz for an identical
   commit).

### 10.2 Correctness
1. `DcacheSpec` (incl. "COPYBACK miss drains via write-allocate", `:520`; the
   `pendingStoreMiss` racing-load test, `:952`; the `diagFault` kind=1 test, `:1075`;
   the maintenance-vs-pending-drain-miss test, `:1630`) — full pass.
2. `DcacheDrainRefillRaceSpec` — **full pass, including the `collisionHit` coincidence
   taps** (`wtCollisionHitAny` / `cbCollisionHitAny`). These prove the sweeps still
   *reach* the race; a vacuous pass here is a failed gate. Note these sweeps exercise
   `:922` (LOAD-miss refill), which this lever does not touch — so any change in their
   behaviour is itself a red flag.
3. **NEW directed test (required deliverable): the positive control for the hold.**
   Using the `DcacheProbePlugin` DUT (which, unlike the real core, *can* violate the
   producer contract), poke `storeDrainHoldExpected := True`, drive a COPYBACK drain
   miss into REPLAY, and present a second store so it sits in S1/S2 across the merge
   cycle. Assert: (a) `storeDrainHoldFired` pulses — proving the hold is live and the
   test is not vacuous; (b) the write-allocate merge is **delayed, not dropped** — the
   allocated line's final contents are the correct merge; (c) the second store's own
   array write also lands. This is the test that makes C1/C2/C3 falsifiable rather
   than argued.
4. `storeDrainHoldFired` must stay **`False` for the entire run** of the full lock-step
   ×2 and the ported-tests corpus — that is the live proof of §1.1's contract argument
   across ~700 real programs. Wire it as a sim-side monitor in the lock-step harness.
5. `fastTest` full pass; lock-step ×2; the LSU/copyback clusters of the ported-tests
   corpus at minimum, ideally the full sweep via
   `tools/fuzz/ported-sweep-parallel.sh`.

### 10.3 IPC
Zero change is *claimed*, not assumed: run the existing IPC bench and require
bit-identical cycle counts on at least one store-heavy workload. A non-zero delta means
§1.1 is wrong somewhere and the lever must be withdrawn, not tuned.

---

## 11. Honest status summary

| claim | basis |
|---|---|
| the family's upstream 67.9 % is `tagMem → stS2HitVec → refillWriteHold → :991 → storeAllocAck → storeAck → sqCompletion → applyFast` | **netlist-traced** (carried over from this round's A40C/B52C trace), **RTL-reconciled line-by-line by this pass** |
| the `:991` gate's predicate is unreachable-`False` | **RTL proof, this pass** (§1.1) — structural, two independent producer paths, closed by `drainBusy` |
| `storeAllocAckReg` can never produce `sqCompletion.valid` | **RTL proof, this pass** (§1.2) — corroboration only |
| the fix removes S1+S2 = 2.099 ns | **arithmetic on a carried-over trace — NOT measured by this pass** |
| the family is retired at ~3.0-3.2 ns | **projection — NOT measured.** §9 must confirm the premise; §10.1 must confirm the result |
| zero latency / zero IPC | **RTL argument** (§2.4), to be confirmed empirically by §10.3 |

**Do not implement before §9.** The whole spec rests on the carried-over trace still
describing the post-Lever-B netlist, and that is one read-only query away from being
either confirmed or refuted.
