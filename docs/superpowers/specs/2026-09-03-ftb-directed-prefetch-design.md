# FTB-directed L1I prefetch — design scope

**Date:** 2026-09-03
**Repo / branch:** `cpu040`, `feat/soc-fabric-concurrency`, HEAD `802d07a7`
**Status:** SCOPE. Not binding. No RTL is authorised by this document; it authorises a measurement sequence (T0–T3) whose outcome decides whether T4+ is built at all.
**Parent measurement:** `docs/icache_prefetch_sweep_2026-09-03.md` (§3 residual, §6 C6)
**Prior art that constrains this:** `2026-05-31-icache-slice-design.md` §8.3 · `2026-08-09-frontend-throughput-audit.md` §2.1 · `2026-08-09-ipc-fetch-directed-btb-token-pipeline-amendment.md` §2.1.1/§2.2 · `2026-08-10-icache-five-id-stream-prefetch-design.md` rules P1–P4 · `2026-08-12-large-scale-frontend-restructure-design.md` §1.4

---

## 1. The measurement, and an honest statement of what it can and cannot buy

The prefetch on/off sweep run for the first time today (`docs/icache_prefetch_sweep_2026-09-03.md`, core `057a41b1`, `IPC_SEED=1`, `IPC_MEM=l2` → 5-cyc L2 hit / 70-cyc DDR):

| | prefetch ON | prefetch OFF |
|---|---|---|
| aggregate IPC | 0.542 | 0.427 (**+26.9 %**) |
| window cycles | 9,943 | 12,616 |

The next-line prefetcher is already load-bearing. The question this document scopes is the **residual it does not cover**: comparing L2-faithful against ideal memory with prefetch ON in both isolates **1,887 cycles, ~19 % of runtime**. Backing out the four D-side-bound kernels (~1,126 cyc) leaves **≤760 cycles of I-side residual, ~7.7 % of runtime**, concentrated as:

| kernel | L2+pf | ideal+pf | residual | prefetch speedup |
|---|---|---|---|---|
| call-return | 2,970 | 2,446 | **524** | 1.03× |
| mixed | 786 | 615 | **171** | 1.57× |
| independent-ALU | 280 | 214 | 66 | 4.67× |
| branchy | 443 | 443 | **0** | 1.18× |

**What this is not.** `branchy` has *zero* residual stall. Its loop stays resident, so no prefetcher of any kind can help it. This is therefore **not** "make branches faster" and not "branchy code is slow". The addressable phenomenon is narrower: **control flow that leaves the resident working set**, where the prefetcher keeps walking the fall-through while the machine has already committed to a target somewhere else.

**Ceiling, stated conservatively.** 760 cycles / 7.7 % is an **upper bound**, not a measured I-side figure — the sweep says so itself (§3 caveat: call-return's 524 includes bsr/rts stack traffic, which is D-side). Two independent investigations argued, from the ~32-byte size of the `call-return` kernel image, that its 524 cycles **cannot** be I-side at all. If they are right, the real addressable pool is closer to `mixed` (171) + `independent-ALU` (66) ≈ **240 cycles, ~2.4 % of runtime**, and even that is an upper bound because part of it is fill latency no prefetcher placed earlier could hide. **The I-side/D-side attribution is the single largest unknown in this document and is task T1.**

**What is genuinely unmeasured, and must not be invented:** the I-side/D-side split of the residual; the number of predicted targets that are non-resident when predicted; the distribution of available lead time; the coverage of same-page vs cross-page targets; and the current FMax of HEAD.

---

## 2. Evidence status — read before quoting any number

### 2.1 FMax: the brief's ~197 MHz is corroborated, not stale

| artefact | netlist md5 | date | result at a real 5.000 ns |
|---|---|---|---|
| `synth/fullcore_signoff_timing.rpt` / `impl_FullCore.tcl:56` | `872c3dc5…` | 25 Aug | WNS **+0.042**, 0 failing of 168,756 → **MET_200**, 201.694 MHz |
| `synth/fullcore_route_timing.rpt` (`fullcore_synth.md5` on disk) | `29e2887a…` | 26 Aug | WNS **−0.084**, **311 failing** of 167,572 → **196.70 MHz** |

These are **different netlists**. The most recent routed artefact on disk *fails*. Both predate HEAD `802d07a7` and two netlist-changing commits — `22b16f44` (full AXI boundary register slice on `axi_i`/`axi_d`, which lands directly on the path the I-cache MSHR/AR families terminate in) and `057a41b1`. **No FMax number in this document, or in any of the four investigations behind it, may be quoted as current.** T0 exists to fix that.

### 2.2 The `pfNextPa` timing family: real, but not "the binding constraint"

`synth/census_ooc_family.rpt:3` ranks `FetchAlignPlugin_logic_resultExpectedSlot -> IcachePlugin_logic_pfNextPa` first — **by endpoint count** (29 endpoints, −0.082 ns), which is what that file ranks by. By *slack* the same file shows `LsEuPlugin…p4Ctx_xlate_front_robId -> IssueQueuePlugin…triggers` at −0.555 ns, ~0.47 ns worse and nowhere near the fetch path. Across three reports the FetchAlign→`pfNextPa` arc appears with **three different startpoints and two different signs** (`stalled_reg` −0.066 in `fullcore_slack_matrix.rpt:32`; `quiesce_reg` **+0.083/+0.085** at ranks 56–68 in `fullcore_signoff_slack_matrix.rpt:56-67`; `resultExpectedSlot` −0.082 in the OOC census). That is one cone with several critical terms, not one arc — `ic.cmd.fire` is an ~11-way conjunction spanning both plugins (`FetchAlignPlugin.scala:600-601` ∧ `IcachePlugin.scala:1600`), so which term is named depends on the run.

**Correct characterisation:** a marginal (~0.08 ns) family with wide fanout (29 endpoints) in a band-limited netlist, whose sign is run-dependent — not the design's limiter.

### 2.3 Cost of one added logic level

`fullcore_route_timing.rpt:674-684` — 5.059 ns over 12 levels — is **76.6 % route**. Logic per level is **0.099 ns**; on the design's worst path (`:204-212`) it is 0.093 ns. The "0.28–0.42 ns/level" figure circulating in the investigation is an average presented as a marginal and overstates it by ~3–4×.

**Honest marginal:** ~0.10 ns of LUT delay plus one unknown net hop, against a margin that is +0.042 ns on the last passing netlist and negative on the newest one. Still likely fatal if unbudgeted; **~2–3× the margin, not 7–10×.**

---

## 3. The seam, as verified in the Scala

Independently re-read at HEAD (not taken from prose):

- **FTB response is registered and token-matched.** `rspValid = RegNext(cmd.valid)`, payload a `Reg` written `when(cmd.valid)` (`Ftb.scala:87-98`). Lookup fires on `planLookupFire = ic.cmd.fire && !issueBornStale` (`FetchAlignPlugin.scala:386`), so the FTB verdict for a command fired at C lands at **C+1**, proven by `resultTokenProof` (`:399-405`) and hard-asserted (`:484-489`) — a mismatch is an assertion failure, not a silent drop.
- **`applyNow` selects the target into the same-cycle command PC.** `directTargetPc = ftbRsp.payload.target(31 downto 3) ## 0` (`:583`) → `cmdWindowPc` (`:585-587`) → `ic.cmd.payload.pc` (`:602`).
- **But `applyNow` does not imply `ic.cmd.fire`.** `when(applyNow) { … when(!ic.cmd.fire) { targetHoldValid := True; targetHoldPc := directTargetPc } }` (`:631-635`), registers declared `:288-290`, cleared at `:637-638` and on `ftqFlush` `:1375`, and **already `simPublic` at `:339`**.
- **The I-cache refuses commands for the whole demand fill.** `cmdPort.ready := False` is the FSM default (`IcachePlugin.scala:1118`); the only assignment is inside `lookupTick` (`:1600`), and `lookupTick` is called from exactly two states — `IDLE` (`:1666`) and `PREDECODE` (`:1811`). **During `REFILL` (`:1722`) — the whole ~70-cycle line fill — `ic.cmd.ready` is low.**
- **The prefetch allocator keeps running during that fill.** On a miss the demand MSHR is allocated immediately in `IDLE` (`:1912-1935`); the `s0Valid := True` HOLD arm is taken only when the FSM is not in `IDLE`. So `heldDemandMissQ = s1Disp.valid && !hit && !fault` (`:1341`) — and hence `demandStuckQ` (`:1394`), the allocator freeze at `:2147` — is short (bounded by the ≤3-cycle install dwell), **not** the fill duration.
- **The frontier is physical and same-page by construction.** `s1Line := (s0Ppn ## s0Set ## U(0,6 bits))` (`:845`); `seedPfWindow` (`:1416-1433`) clamps `pfLimitPa` to `pageEnd`; `pfWindowHasCandidate` requires `pfNextPa(31:12) === pfDemandLine(31:12)` (`:1294-1295`). One call site (`:1463`), inside `when(s1Disp.valid)`.
- **The prefetcher has no translation of its own.** One combinational `xlate.req` hard-wired to `cmdPort.payload.pc` (`:151-155`); an ITLB miss sets `needWalk` and starts a real table walk (`ItlbPlugin.scala:161`). Rule P1: *speculation never starts an ITLB walk.*
- **A virtual value in the frontier is a silent wrong-bytes hazard, not wasted bandwidth.** `mshrPa(pfFreeMshr) := pfNextPa` (`:2165`) → AR address; `mshrTag(pfFreeMshr) := pfCandTag = pfNextPa(31:12)` (`:1273`, `:2167`) → `tagMem(w).write(installSet, mshrTag(installIdx))` (`:2537`). Nothing between catches a VA. A coincidental tag match is a false hit delivering another page's instruction bytes (risk R3).
- **`mshrValid` / `mshrPa` / `mshrSet` are already `simPublic`** (`:588`, `:609`, `:591`), as are `s0Pc` (`:760`), `s1Disp.*`/`s1Line` (`:846-847`), `prefetchEnable` (`:126`) and `pfHitUseful` (`:1221-1223`, sim-only, and `IpcBenchSpec` compiles via `M68kSim()` = `SimConfig.withConfig(M68kSpinalConfig().includeSimulation)`).

### 3.1 The exploitable window — and the three claims I am overriding

Three of the four investigations concluded, in different words, that this feature has no headroom:

> *"The fetch-directed redirect already applies the FTB target to `ic.cmd.pc` in the very cycle the target becomes available, so a reactive target-seed can never beat the demand fetch to the target line."* (prefetch-engine) — *"an FTB-sourced seed is structurally incapable of covering the target LINE."* (timing-and-fmax, **upheld** by its verifier) — *"`pfTargetSeeds/ftbApplies` can read ~1.0 while the feature saves zero cycles."* (verification)

**All three rest on the same assumption: that `applyNow` implies the target command is issued.** The prefetch-engine verifier refuted it via `targetHold`, and I reproduced that grep myself (write set `:632-633`, `:637-638`, `:1375`; nothing else). I then went one step further than any of the four: **`cmdPort.ready` is the FSM default `False` for the entirety of `REFILL`.**

So the real cycle contract is:

```
C     branch window W's ic.cmd.fire; FTB lookup launched
C+1   FTB verdict for W; applyNow; directTargetPc drives ic.cmd.payload.pc
      IF ic.cmd.ready  -> target fetched now. Lead time ≈ 0. (steady state)
      IF NOT           -> targetHoldPc := directTargetPc, and it sits there
C+1 .. C+1+k           REFILL of some earlier demand miss: cmd.ready == False,
                       target parked in a register, k up to a full fill (~70 cyc DDR),
                       while the prefetch allocator IS free and IS spending its
                       four speculative slots on pfDemandLine+64 … +320, i.e. the
                       fall-through the machine has already decided not to take.
```

**I am explicitly overriding the timing-and-fmax verifier's upholding of the value ceiling.** That verifier did not consider `targetHold` and did not check `cmdPort.ready`'s default; the prefetch-engine verifier's refutation is direct, structural, and I re-derived it independently. The ceiling survives *only in the unstalled regime* (~1–2 cycles, worthless). In the stalled regime — **which is by definition the regime that produces the residual stall** — the lead time is the remainder of an in-flight fill.

That is the entire thesis of this document, and it is falsifiable: **T3 measures the distribution of `targetHoldValid`-rise → target `ic.cmd.fire` latency.** If that distribution is concentrated at 0–2 cycles, this design is dead and the residual belongs to a different lever (§8).

Two further findings I am overriding, each in favour of the verifier:

- **`declineBlocked` is not the exploit** (ftb-plan-pipeline claimed it was "the strongest"). Its verifier proved `ftbDeclineBlocked` can never be caused by `targetHoldValid` — the set and hold paths both require `!ic.cmd.fire(X-1)` while `ftbCandidate(X)` requires `ic.cmd.fire(X-1)`; mutually exclusive. Once the rising-edge argument is applied to `stalled`/`faultHold`/`quiesce` too, the reachable steady-state causes reduce to `redirect.valid` (whose `commonRedirect` ends in `ringStale.foreach(_ := True)` — a wrong-path target) and a held `ftbSuppress` (a plan the frontend just proved malformed). **The declined populations are the worst hints available.** The synthesis: the right source is not a decline at all, it is the *applied-and-held* target, `targetHoldPc`.
- **The same-page-restricted variant is not "near-vacuous"** (claimed by verification's risks). A 4 KiB page is **64 lines**; the sequential window covers **5** (`seedPfWindow`'s `line + 5*64`). Every backward target and every forward target more than 5 lines away is outside it.

---

## 4. Decisions

### F1 — Scope is "re-aim the existing frontier", not "prefetch more". **DECIDED.**
The feature re-points the four existing speculative slots from the fall-through to the predicted target's line and its successors. It adds **no new MSHR entry, no new AXI ID, no new frontier, and no second `tagMem` async read port.** Steady-state AR count on a correct prediction should be flat or *lower* (the discarded fall-through fills stop being issued).
*Rationale:* every expensive variant costed by the investigations (second frontier: +97 flops + a third `tagMem(w).readAsync`, which the file records duplicates the LUTRAM at `:1127-1131`; a 4th `Ftb` read port; an ITLB port) buys lookahead this design does not claim. Re-aiming is the minimum change that can capture the window in §3.1.

### F2 — Measurement gates RTL. **DECIDED.**
T0–T3 run before any line of RTL. T4 is authorised only if **all** of: (a) T1 shows a non-trivial I-side share of the residual; (b) T3's lead-time distribution has meaningful mass beyond ~5 cycles; (c) T2's new kernel is proven insensitive to the parent prefetcher.
*Rationale:* this repo has a documented case (`frontend-throughput-audit` §2.1) of a fetch-directed feature that could pass tests while doing nothing, and two documented cases of a measurement knob that silently did nothing (`IcachePlugin.scala:96-127`). Both were found *after* the RTL existed. Also, three of four investigations concluded the feature was worthless; the counter-argument in §3.1 is structural but unmeasured.

### F3 — The hint crosses the seam as a **separate registered `Flow`**, not on `FetchCmd`. **DECIDED.**
`FetchCmd` stays `{ pc: UInt(32) }` (`IcacheTypes.scala:89-92`).
*Rationale, three independent reasons:* (1) `FetchCmd.pc` feeds the ITLB request, the VIPT set index and the tag compare **in the accept cycle** — the exact cone M3b/M4 spent two tasks emptying; a 32-bit companion field adds fanout there for nothing. (2) A hint on `FetchCmd` is delivered only on cycles a command fires — precisely the cycles the prefetcher least needs it, and structurally *never* during `REFILL`, which is the whole window this design targets. (3) "Seed from the NEXT command" is the same defect plus a stale-pairing hazard.
**Rejected alternative — seed-from-next-command:** rejected outright.

### F4 — The hint's source register is `targetHoldPc`, not the one-cycle `ftbRsp.payload.target`. **DECIDED.**
*Rationale:* `Ftb`'s `rspPayload` is sticky between lookups while `rspValid` is a one-cycle pulse (`Ftb.scala:88-98`) — keying off it re-presents stale targets. `targetHoldPc`/`targetHoldValid` are stable registers, already `simPublic` (`:339`), and are **high exactly when the target could not be fetched** — i.e. they are a hardware-maintained filter for "the frontend wants this line and cannot have it yet". No new matching structure, no new hold, and it dodges the S1-hold clobber the prefetch-engine verifier found in the `ftbRsp`-muxed proposal.

### F5 — The hint's *qualifier* must be registered, and must not import `ftbBlocked`. **DECIDED.**
Hint valid = `targetHoldValid` (a register) ∧ a locally registered enable. It must **not** be derived from `applyNow` or `ftbBlocked`.
*Rationale:* `ftbBlocked` (`:463-465`) contains `redirect.valid` and `resume.valid`, which are **slave `Flow` inputs** (`:72-73`) driven from ROB flush/exception. Routing them into the I-cache prefetch write path re-creates the family the file records deleting — *"the measured 24-level ROB-flush/exception → ITLB/I-cache → target-hold path"* (`:449-452`) — and re-opens the amendment's binding prohibition that fetch-plan state *"must not feed target selection, I-cache readiness, or prefetch state"* (`2026-08-09-…-amendment.md:248-252`). `targetHoldValid` is the registered *consequence* of that cone, one cycle later, which is exactly what design rule P4 asks for (`IcachePlugin.scala:829-831`: *"the prefetch frontier and AR arbitration read ONLY registered state; a prefetch decision one cycle later is architecturally invisible"*).

### F6 — Phase A is **same-page targets only**, with the page predicate compared VPN-to-VPN. **DECIDED.**
```
hintSamePage = hintValid && pfSeqValid && (hintPc(31 downto 12) === pfDemandVpn)
hintLine     = pfDemandLine(31 downto 12) ## hintPc(11 downto 6) ## U(0, 6 bits)
```
*Rationale:* this is the only formulation that preserves every existing invariant with zero translation. The PPN, the cacheability verdict and the page containment are all **inherited unchanged** from the demand line's already-resolved ITLB verdict, so rule P1 holds, `pfWindowHasCandidate`'s page gate holds, `pfCandSet`/`pfCandSetBusy`/`demandSetOwned`/the one-fill-owner-per-set assertion (`:2198`) all hold verbatim (bits 11:6 are untranslated page-offset bits under `geo.requireViptSafe(4096, "L1I")`), and no VA-derived value can ever reach `mshrTag` → `tagMem`.
**Correction adopted:** the naive predicate `hintPc(31:12) === pfDemandLine(31:12)` compares a **VPN to a PPN** and is wrong — `pfDemandLine` is physical (`:845`). F7 supplies the missing register.

### F7 — Add `pfDemandVpn`, a 20-bit register written only inside `seedPfWindow`'s guarded block. **DECIDED.**
*Rationale:* the paired virtual page must be *structurally* paired with `pfDemandLine`, not merely correlated. Using `s0Pc` directly is wrong: `s0Pc` is written on `cmdPort.fire` (T) while `pfDemandLine` is written from `s1Disp` (T+1), so a command accepted at T+1 leaves them describing different pages at T+2 — and the splice would then join the **old page's PPN** to the **new page's offset**, producing exactly the cross-page speculative fetch F6 exists to prevent. One writer, same enable, +20 flops, self-pairing by construction.

### F8 — Injection point: the **existing single `seedPfWindow` call site**, with a muxed `line` argument and a widened enable. **DECIDED.**
```
val hintFire = hintSamePage && !s1Disp.valid && prefetchEnable && ftbHintEnable
when(s1Disp.valid || hintFire) {
  … pfWindowUpdate := <as today, or True for the hint arm> ; seedPfWindow(Mux(s1Disp.valid, s1Line, hintLine))
}
```
*Rationale, and the three hazards this shape closes:*
1. **No new arm on `pfNextPa`'s D pin.** The seed body (32-bit add, 20-bit page compare, clamp mux, second add, equality against `pfDemandLine + 64`) is **shared**, not duplicated. The added logic is one registered 32-bit 2:1 mux ahead of an existing cone — one LUT level (§2.3), not a third mux arm, not a duplicated adder.
2. **The single-writer discipline survives.** `seedPfWindow` keeps exactly one call site. The file's load-bearing ordering argument (`:1440-1450`) — seed textually before the allocator, before `when(anyInvalidate){pfSeqValid := False}` — is preserved verbatim.
3. **`!s1Disp.valid` makes the two seed sources structurally mutually exclusive**, which kills the invariant break the prefetch-engine verifier found: a target seed taken *during* the S1 hold is overwritten on the next hold cycle, because `pfDemandLine` now holds the target so `line =/= pfDemandLine` is true and `seedPfWindow` re-seeds straight back to the demand line. Excluding `s1Disp.valid` cycles removes the hold entirely from the hint's reachable set.
**Mandatory:** the hint arm **must** assert `pfWindowUpdate` on any cycle it changes the window. Otherwise the allocator's `+64` advance (gated on `!pfWindowUpdate`, `:2147`) silently overwrites the new `pfNextPa` while `pfDemandLine`/`pfLimitPa` take the new window — a frontier pointing outside its own limit. This is enforced by **one term, not by structure**, and it fails *silently* (a dead prefetcher costs the measured +26.9 %, and no correctness test would notice).

### F9 — Wrong prefetches are dropped, never reclaimed, never queued, never acknowledged. **DECIDED.**
The hint is a single-entry, best-effort, droppable request. It is dropped whenever `s1Disp.valid`, whenever `pfSeqValid` is false, whenever the page predicate fails, on `anyInvalidate`, and whenever the allocator is otherwise busy. There is **no** hint queue, **no** delivery guarantee, **no** ordering requirement against the demand stream, **no** cancel-on-misprediction, and **no** fault or response path.
*Rationale — this is the correctness asymmetry, stated precisely.* A redirect path must be exact: a wrong PC executes wrong instructions. A prefetch is different in four specific ways, each of which removes a mechanism this design would otherwise need:
- *No target-line correctness.* Under F6/F7 every candidate is a legal, cacheable, correctly-translated address in the demand's own page **whatever the prediction says**. A mispredicted hint fetches a real line the program may not execute — bandwidth, one of four slots for a fill duration, one RR victim-way eviction (`:2562`). Nothing more.
- *No cancellation.* The existing design already has none (frees only at `:1818-1820` and `:2214-2221`; `anyInvalidate` merely poisons at `:2239` because *"there is no transaction-cancel in AXI"*, `:415-419`). A redirect path would have to add one. This does not.
- *No delivery guarantee.* A dropped hint costs a missed opportunity, so the consumer can be gated by any convenient registered term without a liveness proof — which is what lets F5 use `targetHoldValid` one cycle late and F8 exclude `s1Disp.valid` cycles outright.
- *No confidence machinery.* Neither `FtbEntry.counter` (dead on the read side: written `Ftb.scala:117-128`, absent from `FtbLookupRsp`) nor a decline-class split (whose discriminator `resultAfterDrop` re-materialises a cone measured at six levels / 1.536 ns and retimed away by amendment §2.1.1) is needed. **Not adding either is a decision, not an omission.**

### F10 — The feature gets its own `RegInit` enable, copying `prefetchEnable`'s exact pattern. **DECIDED.**
`ftbHintEnable`, self-assigned `RegInit`, build-time env override, `simPublic`, **and** subordinate to `prefetchEnable`.
*Rationale:* two documented traps, both instances of a measurement knob that quietly did nothing. (1) A top-level `in Bool()` port silently read 0 in every full-core testbench — *"the first IPC sweep after adding it came back bit-identical to baseline"* (`IcachePlugin.scala:99-127`). (2) A single sim poke is overwritten by the reset value while `forkStimulus` still holds reset, hence `IpcBenchSpec`'s 8-cycle re-poke fork (`:442-448`). Subordination to `prefetchEnable` is mandatory: **nine** test files poke `prefetchEnable := false` precisely to assert zero speculative ARs (`IcacheSpec`, `FetchAlignResidentCadenceSpec:165`, `IcacheInvalidateSpec`, `IcacheVerdictShadowSpec`, `IcacheParallelViptSpec`, `IcacheOrderOracleSpec`, `IcacheIdReuseWhiteboxSpec`, `IcachePrefetchSpec`, `IpcBenchSpec`). An ungated hint would start firing inside all of them.

### F11 — C6 should land first, but only the **accept decision** is gated on it. **DECIDED.**
Contract clause C6 (`docs/fabric_concurrency_contract.md`, SoC repo): a 64 B line fill costs a measured **4.97 cyc against a 4.0 floor**, because L2C serves each 256-bit fetch beat as **two serialized 128-bit quadrant lookups** held apart by `pipe_id_haz_c`.
*Rationale, stated plainly:*
- **This design does not obviously push harder on that door.** Under F1, a *correct* prediction re-aims existing slots — the fall-through fills it replaces were being issued and discarded anyway. Net line count should be flat or down.
- **A wrong prediction is strictly additive**, and following control flow makes the miss stream less spatially regular, which is the traffic pattern a serialized two-quadrant lookup handles worst.
- **The measurement is confounded either way.** A ~24 % tax on every line fill uniformly suppresses any prefetch benefit; an accept/reject decision taken under it will under-value the feature and may reject something that works.
- Per the sweep's own §6, *"C6 raises the ceiling for the prefetcher that already exists"* — it is a win independent of this work.
**Therefore:** T0–T3 (measurement, zero RTL) and T4–T7 (RTL, correctness) proceed regardless. **T9, the accept/reject measurement, must be run post-C6, or rejected results must be labelled as taken under a known-narrowed door.** C6 is not a hard prerequisite for building; it is a prerequisite for *concluding*.

### F12 — Cross-page target prefetch is **out of scope**. **NON-DECISION, recorded.**
Not decided here, and deliberately not designed. It requires either a second ITLB lookup port — `Tlb.scala:72-82` exposes exactly one, over `Vec(2 banks)(Vec(4 ways)(Vec(4 sets)(Reg)))`, and replicating it duplicates the bank/set decode plus 4×17-bit compares in a named fetch critical-path head — or a non-walking hit-only probe, which is a new structure with a new fault surface. Both violate the spirit of rule P1 (*speculation never starts an ITLB walk*: a speculative walk sets descriptor U bits and issues real AXI reads against page tables) and both engage the standing **never assume a static SoC address decode map** rule. Phase A's coverage on the real kernels (T1/T2) decides whether this is ever worth costing.

### F13 — Decoupled run-ahead / FTB lookahead is **out of scope**. **NON-DECISION, recorded.**
The only variant that could prefetch a target line *before* the fetch stream asks for it needs targets for windows fetch has not issued: a 4th `Ftb` `readAsync` port (three exist: `Ftb.scala:70`, `:108`, `:139`) over 128×64 bits — growing exactly the array the 08-12 census measured at **98 % clock-enable endpoints** — plus a new *virtual* walk pointer, its own ITLB access, and a select level on the FTB address decode. The current FTQ is not this: it is pushed at `ftqPush := applyNow` (`:539`) as a post-hoc framing-verification queue, so its entries name lines the machine is already fetching. Explicitly not proposed.

### F14 — A fetch-time return predictor is **a different spec**. **NON-DECISION, recorded.**
Returns are excluded from BTB/FTB training by construction (`BranchEuPlugin.scala:267-269`), so every `rts` takes the RAS decode-time path, whose action is a full restart — `ibuf.io.flush := True`, `ringStale.foreach(_ := True)`, `feed.valid` held low (`FetchAlignPlugin.scala:1025-1026`, `:1234-1252`) — against the FTB call path's zero-flush `ftqConfirmFire` (`:1196-1200`, which *"does not flush the IBuf or stale the ring"*). If T1 attributes `call-return`'s 524 cycles to that asymmetry rather than to I-fetch, **that is the lever**, and this document is the wrong one. Flagged, not designed.

### F15 — Elaboration-time inertness must be provable. **DECIDED.**
`FullCoreSynth` elaborates with an un-simulation-flagged `M68kSpinalConfig()`, so every counter added under `GenerationFlags.simulation` is absent from the netlist — zero area, and equally **unreadable on hardware/ILA**. Every counter in §5 is sim-only by that mechanism; the RTL added by F6–F8 is not. The synth gate (T8) must report the LUT/FF delta and confirm no distributed-RAM growth.

### F16 — OPEN: the exact new-kernel addressing. **OPEN, owned by T2.**
Constraints are known and tight (§5.3); the concrete address formula is not settled, and I will not invent one that has not been assembled and run.

---

## 5. Anti-vacuity plan

The `frontend-throughput-audit` §2.1 failure mode is *a fetch-directed feature that passes its tests while doing nothing*. Three of this design's ingredients are individually capable of reproducing it: an enable that reads 0 in the full-core testbench (F10), a `pfWindowUpdate` omission that leaves the hint silently overwritten (F8), and a predicate that is false every cycle. The plan below is designed so that each of those failures produces a **counter that reads exactly zero on a kernel where it must not**.

### 5.1 The discriminator must be provably zero on parent RTL — and "a prefetch AR fired" is not

Counting speculative ARs is vacuous: the sequential frontier already emits them. Two further traps, both found by adversarial review:

- **Do not observe at `axi.ar.fire`.** Allocation (`:2165`, gated by `:1294-1295`) is decoupled from AR issue by the MSHR *and* the AR hold register (`arHoldValid`/`arHoldAddr` declared `:1473-1475`, loaded `:2306-2309`, released on fire `:2320`). `pfArWant` (`:2246-2249`) is `mshrValid && !mshrArSent && !mshrComplete` — it re-checks **no** window or page state, and speculative ARs are held off entirely while `demandStuckQ` (`:2304`). A stale speculative AR for page X can therefore fire after the demand stream has moved to page Y, giving a **false "the feature ran" signal on parent RTL** — most likely under a page-hopping kernel. *(Correction adopted from the verification verifier; the proposed "update the tracked line after the AR check" mitigation rests on a wrong mechanism — `arHoldAddr` comes from an entry allocated an arbitrary number of cycles earlier.)*
- **Observe at allocation time instead**, with zero RTL change: pair a rising `mshrValid(i)` for `i ∈ [I_SPEC_BASE, I_SPEC_LAST]` with that cycle's `mshrPa(i)` and `mshrSet(i)` — all `simPublic` (`:588`, `:609`, `:591`). *That* is the quantity `pfWindowHasCandidate` makes zero.
- **The cross-page discriminator is void under F6.** With same-page-only seeding, cross-page speculative allocations remain zero by design, so a cross-page counter proves nothing.

### 5.2 The discriminator: **backward** and **beyond-limit** speculative allocations

On parent RTL, `pfNextPa` is seeded at `demandLine + 64`, only ever increments by 64 (`:2149`, `:2155`, `:2169`), is bounded above by `pfLimitPa = demandLine + 5*64` (clamped to `pageEnd`) and bounded to the demand's page. Therefore, for every speculative allocation, the allocated line satisfies

```
demandLine + 64  <=  line  <=  demandLine + 5*64      and  samePage(line, demandLine)
```

Two counters, both **structurally zero on parent RTL** and both collectable with no RTL change:

| counter | definition at allocation | parent value | meaning if non-zero |
|---|---|---|---|
| `pfBackwardAlloc` | `line < demandMshrLine` (same page) | **0** | the frontier followed a **backward** target — a loop back-edge or a local call/return. Impossible today. |
| `pfBeyondLimitAlloc` | `line > demandMshrLine + 5*64` (same page) | **0** | the frontier followed a **forward** target past the sequential window. Impossible today. |

`demandMshrLine` is `mshrPa(0) & ~63`, `simPublic`, and is a stable register for the whole fill — no reconstruction of `pfDemandLine` is needed, which sidesteps the reconstruction plan's own incompleteness (it must otherwise mirror the fault/uncacheable window kill at `:1465-1466` **and** the invalidate path at `:2239`, neither of which the original plan handled).

A hint whose target lands inside `+64..+320` is not attributable and is not counted; that is correct, because the parent already covers it.

**Assertion form:** on the T2 kernel, `pfBackwardAlloc + pfBeyondLimitAlloc` must be `0` with `ftbHintEnable=off` and **≥ a stated floor** with it on. A bare `> 0` is not acceptable — see 5.4.

### 5.3 A new kernel is mandatory

The existing kernels cannot exercise this. Assembled with the real toolchain (`ProgramAssembler.scala:20-24` shells out to `m68k-linux-gnu-as -m68040` / `ld -Ttext 0x40800000` / `objcopy -O binary`): **call-return 32 bytes** (one 64 B line), **branchy 22 bytes**, **mixed 812 bytes** (13 lines, one page, straight-line — the 5-line frontier already covers it). Against a 16 KiB / 4-way / 64-set / 64 B L1I (`Config.scala:18`), none can produce a non-resident predicted target.

Constraints on the new kernel, in force order:

1. **Targets must be same-page** (F6) — so, unlike the cross-page chain sketched during investigation, the control flow lives *inside* pages and only crosses them to create capacity pressure.
2. **Targets must be backward, or >5 lines forward**, or the counters in 5.2 stay zero by construction.
3. **Working set must exceed 16 KiB**, i.e. ≥5 pages actively toured, or nothing is ever evicted and there is nothing to prefetch (the `branchy` lesson).
4. **FTB aliasing must be avoided**: 128 entries indexed on `pc[9:3]` (`Ftb.scala:29`, `:43-47`), direct-mapped, tag `pc[31:10]`. Placing every branch at a page base aliases all of them to index 0 and measures nothing — the §2.1 trap in a new guise. Do **not** raise the entry count for the bench: 128 is the shipped default in both `IpcBenchSpec.scala:329` and `FullCoreSynth.scala:653`.
5. **Framing must pass**: `qFramed` requires `brWordOff + brLen <= 4` (`Ftb.scala:81-84`). Use `bra.w`/`bcc.w` (2 words) at word offset ≤ 2. **Prefer these over `jmp (abs).l`** — that form leaves open whether the BTB/FTB trains on it and what `brType` it carries, and PC-relative word-displacement branches keep every target same-page trivially.
6. **Trip count vs. the 500,000-cycle bench cap** (`IpcBenchSpec.scala:634`) at 70-cycle DRAM.
7. **Image-size cost:** `attachProgramIFetch` writes byte-by-byte into `SparseMemory` (`AxiMemModel.scala:776-789`). A multi-hundred-KiB image is a large number of map writes per `doSim`; measure the wall-clock delta and gate the kernel behind `IPC_ONLY` (the filter already exists, `IpcBenchSpec.scala:1033-1034`) if it dominates.

**The kernel's own anti-vacuity gate, runnable on parent RTL before any RTL change exists:** with the parent core, `IPC_PREFETCH=on` must ≈ `IPC_PREFETCH=off`, demand-AR count must be high, and `pfHitUseful` must be ≈ 0. **If the sequential prefetcher helps this kernel, the kernel is wrong** and is measuring next-line behaviour, not control flow.

### 5.4 Coverage metric — and the denominator that must *not* be used

**Do not use `pfTargetSeeds / ftbApplies`.** `applyNow` selects `directTargetPc` into that same cycle's `ic.cmd.payload.pc` (`:583-587`, `:602`), so most applies are targets the demand stream fetches immediately. That ratio can read ~1.0 while the feature saves zero cycles — the §2.1 trap reproduced by the metric intended to prevent it.

**Use instead**, all sim-only, all reported per kernel:

| metric | source | why |
|---|---|---|
| `hintLeadCycles` histogram | `targetHoldValid` rise → that target's `ic.cmd.fire` (both `simPublic`) | the **go/no-go** number; it *is* the exploitable window of §3.1 |
| `pfBackwardAlloc`, `pfBeyondLimitAlloc` | allocation-time `mshrValid`/`mshrPa` | structurally-zero discriminators (5.2) |
| `pfSpecArIssued` | `axi.ar.fire` with `id ∈ 1..4` | bandwidth cost; must not rise materially on a correct prediction (F1) |
| `pfHitUseful` | `IcachePlugin:1221-1223`, `simPublic` | one pulse per speculatively-installed `(way,set)` on its **first** demand hit. Counts the held-demand case. Does **not** count issues, installs-never-hit, or evictions; per-`(way,set)` granularity double-counts an evict-then-refetch and single-counts an N-times-hit line. Report the caveat inline, never as a bare ratio. |
| `pfWasted = pfSpecArIssued − pfHitUseful` | derived | sound but not exact: prefetches still in flight at end-of-run are a residual bounded by 4 slots + the 5-line window. **Print the bound, do not absorb it.** |
| I-side vs D-side demand ARs | `icache.logic.axi` vs `dcache.logic.axi` | settles T1, the largest unknown in §1 |

**Dependency to record beside the counters:** `pfHitUseful` exists only because `IpcBenchSpec` compiles via `M68kSim()` (`:1039`) = `SimConfig.withConfig(M68kSpinalConfig().includeSimulation)` (`M68kSim.scala:20-21`). Had the bench used `M68kSpinalConfig()` directly, the signal would not exist at all.

### 5.5 Mutation proof

Following `IcacheMutationProofSpec`'s established pattern (`:198-222`, `requireTest(...)` name pins): deleting the F8 hint arm, or forcing `hintSamePage := False`, or dropping the `pfWindowUpdate` assertion in the hint arm, must each make a **named** test fail with a **named** message — e.g. `"FTB HINT INERT: 0 backward/beyond-limit speculative allocations on page-local-control"`. Note that the name pins are load-bearing in both directions: renaming an existing test silently retires a mutation proof (it fails loudly in the fast gate, which is intended, but must be planned for).

### 5.6 Regression surface that is *not* prefetch-specific

Two always-on simulation assertions guard the invariant a second seed source could break, and they run under the lock-step suites, the ported corpus and the fuzz campaign — so a break shows up as a broad, noisy failure, not a directed one:

- producer end: `assert(!(mshrValid(i) && mshrValid(j) && mshrSet(i) === mshrSet(j)))` — **`IcachePlugin.scala:2198`** (not `:2226-2231`, which is comment prose about the `anyInvalidate` poison loop — a mis-citation corrected here);
- consumer end: `CountOne(s1HitVec) <= 1` (`:875-877`).

Tests requiring rescoping, by name: `IcachePrefetchSpec` *"P1/P4: no prefetch is issued when the next line would cross a page boundary"* (`:813`) and *"P1: no prefetch is issued off an INHIBITED-page fetch"* (`:838`) — both remain **true** under F6 and should be re-stated as scoped to *both* seed sources rather than deleted; `IcacheOrderOracleSpec` oracle 3 and variants (`:227`, `:324`, `:523`); `IcacheVerdictShadowSpec`; `IcacheIdReuseWhiteboxSpec` (more distinct in-flight addresses raise pressure on the per-slot generation / stale-drain deadlock fix). Also note the self-modifying-code corpus: `smc_dcache_to_icache.s` documents FAIL sentinel `0xDEAD0002` as *"wrong-path speculative garbage"*; re-aiming (rather than widening) the frontier should be neutral there, but the corpus must be re-run.

---

## 6. Task breakdown

Each task is independently buildable, has a named gate, and states what it decides. **T0–T3 authorise T4.** The standing rule (every slice ends in a full-core OOC synth gate) applies at T8; T0 establishes the baseline that gate is measured against.

| # | Task | Gate command | Decides |
|---|---|---|---|
| **T0** | **FMax rebaseline on HEAD.** No RTL. Run the sign-off recipe on `802d07a7`; archive `SIGNOFF_200MHZ_RESULT`, WNS, failing-endpoint count, `fullcore_signoff_slack_matrix.rpt`, per-plugin TNS shares, and the netlist md5. | `POSTROUTE_ROUNDS=9 vivado -mode batch -source synth/impl_FullCore.tcl` (verdict re-derived at a real 5.000 ns, `impl_FullCore.tcl:446-447`) | Every before/after FMax claim in T8. Without it, §2.1 says no such claim is admissible. |
| **T1** | **Residual attribution.** Zero RTL. Add per-kernel I-side vs D-side demand-AR counters + `pfHitUseful` + spec-AR issued to the existing `cd.onSamplings` block (`IpcBenchSpec.scala:506`), fields in `IpcResult` (`:396-410`), print beside `:1067-1070`. Pattern: `IcachePrefetchSpec.scala:41-56`. | `IPC_SEED=1 IPC_MEM=l2 sbt "testOnly m68k040.bench.IpcBenchSpec"` | **Is `call-return`'s 524 cycles I-side at all?** If not, §1's pool shrinks to ~240 cyc and F14 becomes the real lever. |
| **T2** | **New kernel `page-local-control`** per §5.3, plus its parent-RTL inertness gate (prefetch ON ≈ OFF, `pfHitUseful` ≈ 0). Settles F16. | `IPC_SEED=1 IPC_MEM=l2 IPC_ONLY=page-local-control sbt "testOnly m68k040.bench.IpcBenchSpec"`, run twice with `IPC_PREFETCH=on/off` | Whether a measurable target population exists at all. |
| **T3** | **Lead-time histogram.** Zero RTL. `targetHoldValid` rise → that target's `ic.cmd.fire`, per kernel, plus the fraction of `targetHoldValid` cycles spent with the FSM in `REFILL`. | as T1 | **GO/NO-GO.** Mass concentrated at 0–2 cycles ⇒ §3.1 is wrong and this design is abandoned here, at zero RTL cost. |
| **T4** | **RTL: hint producer.** Registered `Flow(UInt(32 bits))` in `FetchAlignPlugin`, payload `targetHoldPc`, valid `targetHoldValid` ∧ local registered enable (F4, F5). No change to `cmdWindowPc`, `ic.cmd.valid`, `FetchCmd`, or any assertion in the token protocol. | `make test-fast`; `sbt "testOnly m68k040.frontend.FetchDirectedFtbSpec m68k040.frontend.FtqCapacitySpec"`; new `FtbPrefetchHintSpec` | Producer side in isolation. |
| **T5** | **RTL: hint consumer.** `pfDemandVpn` (F7), `hintSamePage`/`hintLine` (F6), muxed single call site + `pfWindowUpdate` (F8), `ftbHintEnable` (F10). Rescope the two `IcachePrefetchSpec` P1/P4 tests. | `sbt "testOnly m68k040.cache.*"` — must include `IcachePrefetchSpec`, `IcacheOrderOracleSpec`, `IcacheVerdictShadowSpec`, `IcacheIdReuseWhiteboxSpec`, `IcacheInvalidateSpec`, `IcacheParallelViptSpec` | Consumer side; the two repo-wide assertions (§5.6) hold. |
| **T6** | **Anti-vacuity counters + mutation proof** (§5.2, §5.5), with `IcacheMutationProofSpec` name pins. | `sbt "testOnly m68k040.cache.IcacheMutationProofSpec"` + the new spec | That the feature demonstrably *does something*, and that removing it demonstrably breaks a named test. |
| **T7** | **Full regression.** Includes the ported corpus and a fuzz sweep — both are, per the standing record, **9–28 days stale** and predate the FPU, frontend-restructure and FMax campaigns. | `make test`; ported-corpus runner; fuzz sweep | No correctness regression, especially in the SMC/coherency corpus. |
| **T8** | **Full-core OOC + impl synth gate** against T0. Standing rule. | `synth/ooc_M68kFullCoreSynth.tcl`, then `POSTROUTE_ROUNDS=9 … synth/impl_FullCore.tcl` | Acceptance bar §7. |
| **T9** | **Accept/reject measurement.** Post-C6 (F11), or explicitly labelled as taken under a known-narrowed L2C door. | `IPC_SEED=1 IPC_MEM=l2 sbt "testOnly m68k040.bench.IpcBenchSpec"`, feature on vs off, plus `IPC_ONLY=page-local-control` | Ship or revert. |

---

## 7. Pre-registered acceptance bar

Registered **before** the runs, so a marginal result cannot be re-narrated afterwards.

**Timing (T8), all against T0's HEAD baseline, same recipe, uncontended machine:**
1. `SIGNOFF_200MHZ_RESULT MET_200` with WNS ≥ 0 re-derived at a real 5.000 ns — **or**, if T0's baseline itself fails, WNS no worse than T0's and failing-endpoint count no higher.
2. Failing-endpoint count ≤ baseline.
3. In `fullcore_signoff_slack_matrix.rpt` (the sign-off-aligned matrix, per `impl_FullCore.tcl:436-438` — *"the probe-period top-100 is not the same set of paths that actually limit the 200 MHz verdict"*), `IcachePlugin_logic_pfNextPa_reg[*]/D` and `/CE` no worse than baseline, and **no new `FetchAlignPlugin_* -> IcachePlugin_logic_pf*` startpoint/endpoint pairs**.
4. `FetchAlignPlugin`'s TNS share not above its current 11.1 %.
5. LUT/FF delta reported from `report_utilization`; **distributed-RAM LUT delta must be 0** (no new `readAsync` port on `tagMem`, `Ftb` or `Btb` — F1).
6. **Declared up-front:** this design adds one registered 32-bit 2:1 mux ahead of `seedPfWindow`'s existing add/compare/clamp cone, plus 20 flops (`pfDemandVpn`) and one hint register pair in `FetchAlignPlugin`. Honest marginal ≈ 0.10 ns of LUT delay plus one net hop (§2.3). It adds **no** new arm to `pfNextPa`'s D-input mux and **no** new MSHR write source.

**Function (T6, T9):**
7. On `page-local-control`: `pfBackwardAlloc + pfBeyondLimitAlloc` = 0 with the feature off, ≥ a stated floor with it on.
8. `IPC_MEM=l2`, pinned `IPC_SEED`, feature on vs off, in this order: **(a)** I-side demand-AR count must **drop**; **(b)** total spec-AR count rise must be bounded and stated; **(c)** `pfHitUseful` must rise; **(d)** only then is an IPC delta interpretable. IPC alone is not an accept signal — the bench has ~1 % run-to-run jitter unpinned (`:433-436`) and IPC moves for backend reasons.
9. Aggregate IPC on the existing kernel set must not regress against 0.542.
10. A run under the zero-latency default model is **disqualifying** for any I-side claim (sweep §0: 0.6 % vs 26.9 % on the same comparison).

---

## 8. Open questions

**Gating (must be answered before T4):**
- **O1.** What is the I-side vs D-side split of the 1,887-cycle residual, measured? Given `call-return`'s 32-byte image, the ≤760 cyc I-side figure is an upper bound that two investigations argue is badly loose. → T1.
- **O2.** What is the distribution of `targetHoldValid`-rise → target-`ic.cmd.fire` lead time? Mass at 0–2 cycles kills the design. → T3.
- **O3.** What fraction of applied/held predictions have **same-page** targets that are also **outside** `+64..+320`? If small, F6's Phase A ceiling is small and F12 need not ever be costed. → T2/T3.

**Non-gating but load-bearing:**
- **O4.** How much of the I-side residual is `pfLookupSetBusy`'s **set**-granularity refusal (`:1308-1310`, `:1599-1600`) — a same-set/different-line demand stalling for a whole speculative install, over only 64 sets — rather than genuine fill latency? Different lever, cheap to distinguish.
- **O5.** Which memory model did `2026-08-09-frontend-throughput-audit.md` use for its *"the dominant measured frontend loss is correctly predicted taken control flow"* claim? Unrecorded, and inconsistent with the L2-faithful numbers unless it hid I-side fill cost (sweep §0). Do not treat that claim and this sweep as jointly true until settled.
- **O6.** `branchy` confirms only **178 of 298** applies (60 %) against ~97–99 % everywhere else (sweep §4). Unexplained, and it is a frontend number worth chasing on its own.
- **O7.** Independent of this proposal: `demandSetOwned`'s live lane `cmdPort.fire && (lookupSet === pfCandSet)` (`:2041`) is the last non-registered term in the frontier's write path and is the measured startpoint of the `−0.066` arc. M4 stopped one lane short. Is there a registered replacement, worth margin whether or not this feature is ever built?
- **O8.** Does the FTB train on `jmp (abs).l`, and what `brType` does `BtbUpdate` carry for it? Avoided in F16/§5.3 by specifying `bra.w`/`bcc.w`, but still unknown and relevant to any future kernel.

---

## 9. Overrides and corrections, recorded

Where an adversarial verifier refuted an investigation finding, the verifier wins unless stated otherwise. Explicitly:

1. **Overrode an investigator (verifier wins).** *"A reactive target-seed can never beat the demand fetch to the target line"* — refuted via `targetHold` (`FetchAlignPlugin.scala:288-290`, `:631-638`, `:1375`), which I re-derived by grep. §3.1.
2. **Overrode a verifier (stated explicitly, per the ground rules).** The timing-and-fmax verifier **upheld** the value-ceiling finding. I override that upholding: it did not consider `targetHold`, and I independently established the stronger fact that `cmdPort.ready` is the FSM default `False` for the whole of `REFILL` (`IcachePlugin.scala:1118`, `lookupTick` reached only from `IDLE:1666` and `PREDECODE:1811`). The ceiling survives only in the unstalled regime. **This is the one place where an investigator's structural evidence was clearly stronger than a verifier's conclusion, and it is the load-bearing claim of the document — hence T3 exists to falsify it.**
3. **Overrode an investigator (verifier wins).** *"`declineBlocked` is the strongest exploit"* — `ftbDeclineBlocked` can never be caused by `targetHoldValid` (mutually exclusive by construction); the reachable steady-state causes are `redirect.valid` (wrong-path) and held `ftbSuppress` (malformed plan). It is the **weakest** class. Synthesis: the right source is the applied-and-held target, not any decline. F4.
4. **Overrode an investigator (verifier wins).** *"`pfNextPa` is the single worst failing endpoint family / the binding constraint"* — true only by endpoint **count**; by slack it is marginal, and the same arc appears with three startpoints and two signs across three reports. §2.2.
5. **Overrode an investigator (verifier wins).** *"One added logic level costs 0.28–0.42 ns, measured, 7–10× the margin"* — average-as-marginal; the measured logic term is 0.093–0.099 ns/level. §2.3.
6. **Overrode an investigator (verifier wins).** *"Current FMax is 201.694 MHz, the brief's ~197 is stale"* — the newest routed netlist on disk fails at −0.084 (196.70 MHz); the brief is corroborated. §2.1.
7. **Overrode an investigator (verifier wins).** *"A cross-page speculative AR is structurally zero"* observed at `ar.fire` — decoupled from allocation by the MSHR and AR hold. Moved to allocation time, and replaced entirely (the cross-page discriminator is void under F6). §5.1–5.2.
8. **Overrode an investigator (verifier wins).** *"A same-page-restricted variant is near-vacuous"* — 64 lines per page vs a 5-line window. §3.1, F6.
9. **Overrode an investigator (verifier wins).** *"The `declineFraming` split is free to make"* — its discriminator `resultAfterDrop` re-materialises a cone measured at six levels / 1.536 ns and retimed into the provider by amendment §2.1.1. Not needed; F9 declines to use any decline class.
10. **Correction not raised by either side:** the recommended injection point must be excluded from `s1Disp.valid` cycles, or the S1-hold re-seed destroys the hint one cycle later. F8.
11. **Citation fixes carried forward:** the one-fill-owner-per-set assertion is `IcachePlugin.scala:2198` (not `:2226-2231`); `geo.requireViptSafe` is `:24`; `directTargetPc` is `FetchAlignPlugin.scala:583`; `ic.cmd.valid :=` is `:600-601`; `arHoldValid/arHoldAddr` are declared `:1473-1475`; `Ftb`'s framing test is `:81-84`; the bench is `src/test/scala/m68k040/bench/IpcBenchSpec.scala`.
12. **Upheld and not re-litigated** (verified by at least one verifier and, for the load-bearing ones, by me at HEAD): the VA-tag silent-wrong-bytes chain; rule P1 and the single combinational ITLB port; the pinned same-page containment and its mutation proofs; the absence of any misprediction reclamation; `FtbEntry.counter`'s deadness on the read path; the M4 deletion note (`:1649-1655`), rule P4 (`:829-831`), the amendment's binding prohibition (`:230-252`), and the 08-12 census's *"shorten the CE cone was tested twice and did not convert"* honesty note (`:165-175`) — which is why F1 does not rest on shortening a cone.