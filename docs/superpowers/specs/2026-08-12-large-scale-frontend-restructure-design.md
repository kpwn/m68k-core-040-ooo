# Large-scale frontend restructure: the Unified Fetch Array + Verdict-Terminated Lookup (design)

**Status:** Implemented and merged (`b370d89`, 2026-08-13 — the completed 16-task UFA/VTL
frontend restructure). Post-route FMax 182.749 -> 195.427 MHz (+12.678 MHz) as an
RTL+floorplan unit result at merge time; see the FMax ledger for later campaigns' numbers.
Every number below reflects the pre-implementation design-time state and should be read
as historical. Original text follows, unedited:

No RTL written, no synthesis run, no checkpoint opened for
this document. Every number below is either read directly out of the current source,
read out of an already-archived report on disk, or explicitly labelled as an estimate
or a projection. Awaiting explicit go-ahead before any implementation.

**Branch:** `codex/ipc-dcache-vipt`, worktree
`/home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01`, HEAD `c8ce5ba`.

**Baseline (confirmed, reproduced 6+ times):** `IMPL_STRATEGY=postrouteN`,
`POSTROUTE_ROUNDS=3`, `FLOORPLAN_MODE=decode`, `-2` speed grade →
**WNS −1.472 ns / 182.749 MHz**, TNS −17,499.508 ns, 32,408 failing endpoints,
110,679 CLB LUTs (51.01 %), 50,389 CLB Registers (11.61 %), 26/480 Block RAM
tiles (5.42 %), 9,068 LUTs as distributed RAM.

**Binding goal this serves:** "get this core to 200 MHz with good IPC and
functionally ready for prime time."

**Required reading before implementing this:**
`.superpowers/sdd/progress-ipc-push-2026-08-09.md` §27–§33 (the 8-for-8 record, the
checkpoint-forensics diagnostic, the two floorplan experiments, and §33's
141-commit reframing);
`docs/superpowers/plans/2026-08-10-ipc-frontend-fmax-claude-handoff.md` §13–§27;
`docs/superpowers/specs/2026-08-11-ipc-frontend-genuine-repipeline-design.md`
(the immediately-prior design — **its P1 and P2 are among the eight measured
failures**; this document supersedes it, see §2.4);
`synth/floorplan_frontend.xdc` and `synth/floorplan_decode.xdc` (their header
comments are primary evidence, cited throughout).

---

## Contents

- §0 — What this document is, and the one sentence that justifies it
- §1 — The evidence base, restated without softening
- §2 — Why "large" has to mean *resource-class migration*, not more logic cuts
- §3 — The target, inventoried bit-exactly from the current source
- §4 — Goals / non-goals
- §4.3 — Reference alignment: what NaxRiscv's `FetchCachePlugin` actually does
- §5 — The architecture: Unified Fetch Array + Verdict-Terminated Lookup
- §6 — What this does to the diagnostic's own named worst-path families
- §7 — M5: the floorplan half of the co-design
- §8 — Phase 2 (deferred): the predictor complex
- §9 — Honest projection
- §10 — Acceptance bar
- §11 — Risk assessment, and a staging strategy that is not a point fix
- §12 — Verification plan
- §13 — Scope estimate
- §14 — Open questions and USER DECISION NEEDED callouts

---

## 0. What this document is, and the one sentence that justifies it

Eight independent interventions have been designed, built, verified green, and
gated on real post-route silicon timing this session. **All eight regressed**, by
−8.1 to −21.2 MHz, spanning every category anyone has thought of: four RTL
re-pipelinings, one deliberate IPC-for-FMax strength reduction, two floorplan-only
zero-RTL experiments, and one provably-zero-behaviour-change bundle that
*demonstrably shrank the netlist by 4 %*. The user's decision, relayed verbatim
through the orchestrator, was: **"One more large-scale structural attempt… design
one deliberately large, coherent architectural change (not a bundle of unrelated
small cuts) sized to actually justify entering a new placement basin."**

This is that design. Its single justifying sentence is:

> `synth/floorplan_frontend.xdc` states, as measured fact, that **every one of the
> worst 300 unique failing endpoints on the pinned routed checkpoint is the same
> arc — `FetchAlignPlugin_logic_stalled_reg/C → IcachePlugin_logic_s1PredEntries_{0..3}_reg[*]/CE`** —
> and no attempt so far has *deleted that endpoint*. All eight moved logic around
> it, shrank things near it, or re-boxed it. This design removes
> `s1PredEntries` from the netlist entirely, along with `lineReg`, `missPred`,
> `predAccumLo`, the whole 64 Kib async-read predecode LUTRAM behind them, and the
> deep clock enables of `pfNextPa`/`pfDemandLine`/`pfLimitPa`/`arHoldAddr` — i.e.
> **every register family the checkpoint-forensics diagnostic named as the shared
> landing zone of all four RTL regressions.**

Whether that is *sufficient* is genuinely unknown and §9 says so at length. That it
is *different in kind* from all eight prior attempts is not in doubt, and is
checkable against the source before a single line is written.

---

## 1. The evidence base, restated without softening

### 1.1 The 8-for-8 record

| # | Intervention | Kind | Δ FMax | Round-0 WNS | Plateau gain |
|---|---|---|---:|---:|---:|
| — | **baseline `c8ce5ba`/`6b246de`** | — | **182.749 MHz** | −2.094 | **+0.622** |
| 1 | B3 — I-cache install/prefetch decoupling | RTL re-pipelining | −8.1 | −1.836 (better) | +0.110 |
| 2 | P2 — 2-entry skid replacing `PipeStage` | RTL re-pipelining | −20.5 | −2.352 | +0.187 |
| 3 | P1 — I-cache S1 capture-enable relocation | RTL re-pipelining | −21.6 | −2.324 | +0.119 |
| 4 | RAS `Vec[Reg]`→`Mem` + IQ scoreboard decode | RTL, same-boundary | −8.2 | −1.892 (better) | +0.164 |
| 5 | BTB tag narrowing to 16 bits | IPC-for-FMax trade | −20.1 | −2.410 | +0.263 |
| 6 | Loosen `pb_decode` | floorplan only, zero RTL | −12.65 | −2.349 | +0.470 |
| 7 | **Pin `IcachePlugin` S1/line/prefetch bank** | floorplan only, zero RTL | −8.44 | −2.422 | **+0.685** |
| 8 | Zero-behaviour-change bundle (5 cuts, −4 % LUTs) | area reduction | −21.2 | −3.030 | +0.841 |

Two things in that table matter more than the verdicts.

**(a) Round-0 dominates.** Baseline's round-0 WNS is −2.094 ns. Six of the eight
started *worse* than that, and neither of the two that started better (B3,
RAS/scoreboard) could convert. Variant 8 got a **larger** iteration yield than
baseline (+0.841 vs +0.622) and still finished −21.2 MHz behind, purely because its
round-0 was 0.936 ns worse. **The fresh synth+place+route starting point is the
dominant variable, not the iteration loop and not the logic depth.**

**(b) Variant 7 is the only real positive signal in the entire campaign.**
Explicitly pinning `IcachePlugin`'s S1/line/prefetch capture bank produced
**+0.685 ns of iteration yield — the best ever measured, beating baseline's own
+0.622** — and still lost, again on round-0 (−2.422). The obvious reading, and the
one this design takes: *the bottleneck family's placement really is the lever, but
you cannot fix it by boxing 1,900 sparse flops; a pblock over a sparse flop cloud
degrades round-0 more than it helps iteration.* The way to get the pinning benefit
without the round-0 penalty is to **stop having 1,900 sparse flops there.**

### 1.2 The checkpoint-forensics diagnostic (ledger line ~8217)

- Placement is a **deterministic function of the netlist** (`M0_CONTROL`, an
  independent fresh run at a different commit with RTL-equivalent source,
  reproduces baseline's per-module centroids to two decimal places). Not placer
  noise.
- **Every one of the four regressed RTL variants' new worst path terminates inside
  `IcachePlugin`'s S1/line/prefetch capture-register bank** — `s1PredEntries`,
  `lineReg`, `arHoldAddr`, `pfDemandLine`/`pfNextPa`/`pfLimitPa`, `pfPa`/`pfWay` —
  reached from four *different*, previously-uninvolved startpoints
  (`FetchAlignPlugin_predictTargetReg`, `GsharePlugin_pht_port4`,
  `FtbPlugin_rspPayload_framedOk`, `FtbPlugin_rspPayload_brWordOff`). All four:
  17–19 logic levels, 65–71 % route-dominated.
- That family is already the **tied runner-up in the untouched baseline**:
  `−1.472 FetchAlignPlugin_logic_stalled_reg/C → IcachePlugin_logic_lineReg_reg[418]/D`
  ties the nominal WNS path to three decimals.
- The diagnostic's own implication #2: *"Small point-cuts are structurally
  disadvantaged… This argues for either (a) changes large/holistic enough to
  plausibly justify deriving an entirely new basin on their own terms, or (b)
  pairing any future RTL lever with a simultaneous, deliberate floorplan
  adjustment."* Variant 8 tested a weak reading of (a) — five unrelated logic-cost
  cuts — and failed. **(a) in its strong form, and (b), have never been attempted,
  and have certainly never been attempted together.** This design is both.

### 1.3 §33's reframing

`codex/ipc-dcache-vipt` is a direct descendant of `feat/rob-predictor-mem`, forked
*after* both Lever F and the I-side MSHR chain landed. The 207.17 → 182.749 MHz gap
is **141 commits of deliberate IPC architecture** (LS-EU FSM→pipeline, VIPT
D-cache/DTLB overlap, CPLX/slow-ALU pipelining, fetch-ring turnover), not a missing
lever or a bug. That is why no local cut pays: *there is no local cause to remove.*
A change that could pay has to be at the same scale as the thing that cost the
frontend its timing — i.e. an architectural restructuring, not a cut.

### 1.4 The clock-enable census (prior spec §1)

Of the 4,890 endpoints below −1.000 ns, **64.0 % are clock-enable pins** (3,130 CE /
1,400 D / 239 R). `IcachePlugin` owns 2,427 of the 4,890, of which 1,751 are CE.
`RasPlugin` is 496 endpoints at **100 % CE**; `Ftb` 98 %; `Gshare` 100 %. All 3,130
CE endpoints resolve onto exactly two combinational enable cones: `ic.cmd.fire` and
`feed.fire`.

**Important honesty note, inherited and restated:** the design that acted on this
census (P1, P2) failed both times. The census is a *correct measurement*; the
inference drawn from it ("shorten the CE cone") was tested twice and did not
convert. This document therefore does **not** rest on "shorten the CE cone". It
rests on "**remove the registers whose CE pins those endpoints are**", which is a
strictly stronger and structurally different action.

The reference design corroborates that correction directly (§4.3 N-7):
NaxRiscv's fetch pipeline is **also** clock-enable driven — `spinal.lib.pipeline`'s
`M2S` connection compiles every stageable into `when(downstreamReady){ reg := … }`.
"CE" was never the problem. What NaxRiscv never does is drive a CE from a
translation-fed comparator, or fan one CE to a four-figure flop count. Both are
true of `cmdPort.fire` here.

---

## 2. Why "large" has to mean *resource-class migration*, not more logic cuts

### 2.1 The cut axis is exhausted, with a proof

Variant 8 is the proof. It was verified-by-construction zero-behaviour-change, it
reduced CLB LUTs by 4,454 (−4.02 %) and CLB Registers by 2,123 (−4.2 %), and it
regressed −21.2 MHz. **On this netlist, at this point in its optimisation history,
LUT/FF count and post-route FMax are decoupled.** Any further design whose thesis is
"this is less logic" has already been falsified.

### 2.2 What has *never* been tried

Every one of the eight kept the design's state in the same physical resource class
it was already in. Registers stayed registers (B3, P1, P2 moved *which* register or
*which* enable); LUTs stayed LUTs (variant 8 made fewer of them); the RAS→`Mem`
conversion in variant 4 was the single exception and it was a 512-flop change buried
inside a two-file bundle, gated once, at −8.2 MHz.

**Not tried: taking ~1,900 flops and ~65 Kib of async-read distributed RAM out of
the CLB fabric of the left strip entirely and putting them in Block RAM.**

The resource headroom for that is enormous and is measured, not assumed:
`synth/archive/6b246de_default_postrouteN3_decode/fullcore_route_util.rpt` reports
**26 of 480 Block RAM tiles used — 5.42 %. 474 tiles are free.** The design uses 24
RAMB36E2 and 4 RAMB18E2 in a device with 480 tiles. There is no BRAM pressure of any
kind, and there never has been.

Why this matters physically, and not just as an area statistic:

1. **BRAM columns are placement anchors.** They are fixed sites. Logic that reads and
   writes a BRAM gets pulled toward that column deterministically, which is exactly
   the "compact, left-shifted frontend cluster" that the diagnostic's item-3 centroid
   table identifies as the property baseline's good basin has and every bad basin
   lacks. A pblock *asks* the placer for compactness (variant 7: round-0 −2.422); a
   BRAM *gives* it for free at round 0.
2. **It removes endpoints rather than relocating them.** A flop deleted cannot become
   the next worst path. The diagnostic's finding #4 — "the landing zone is, in all
   four cases, `IcachePlugin`'s capture bank" — has no landing zone left to name if
   the bank is gone.
3. **It attacks the exact density figure the floorplan file blames.**
   `synth/floorplan_frontend.xdc`: *"s1PredEntries_* X10..X26, Y56..Y96 — 930 flops
   at ~2.3 FF/slice — very sparse… A 24–48 CLB Manhattan span at 2.3 FF/slice density
   is what the 66 % route number is made of."* You cannot densify a 930-flop cloud
   whose only consumer is a 4:1 way mux. You can delete it.

### 2.3 The design principle this document commits to

> **P1 — One array.** All per-line instruction state (bytes *and* predecode) lives in
> exactly one synchronous memory, read once per fetch, addressed and enabled only by
> page-invariant virtual bits.
>
> **P2 — One fill file.** All in-flight refill line data (demand *and* speculative)
> lives in exactly one memory indexed by AXI ID. There is no register-resident line
> buffer and no line-to-line copy.
>
> **P3 — Verdict-terminated lookup.** The live translation result never reaches a
> combinational comparator. It is registered at S0; the hit verdict is computed at S1
> from registered inputs only, and terminates in the response stage and in
> registered FSM state — never in the clock enable of a wide bank, never in a
> prefetch or AXI-arbiter enable.
>
> **P4 — Registered-state-only speculation.** Prefetch frontier and AR arbitration
> read *only* registered state. A prefetch decision one cycle later is
> architecturally invisible.
>
> **P5 — The floorplan is part of the design.** The pblock set is sized for the
> netlist this design produces, not inherited from the netlist it replaces.

### 2.4 What this supersedes

`docs/superpowers/specs/2026-08-11-ipc-frontend-genuine-repipeline-design.md` is
superseded. Its P1 (S1 capture-enable relocation) is **subsumed and made moot** —
this design deletes the bank whose enable P1 tried to shallow. Its P2 (`PipeStage`
skid) is **dropped**; it was measured at −20.5 MHz and its structure lives in
`DecodeStage`/`RenameStage`, outside this design's boundary. Its P3 (F1/F2 translate
split) is **replaced** by M3 below, which achieves the same "ITLB out of the compare
cone" objective *without* the +1 fetch-latency cost that P3 required — see §5.3 for
why, and §5.3.4 for why that matters (the `RING`=3 / `BUF_WORDS`=20 H7 hazard).

---

## 3. The target, inventoried bit-exactly from the current source

`ChunkPredecode()` (`src/main/scala/m68k040/cache/IcacheTypes.scala:18`) =
`simple`(1) + `lenWords`(4) + `ambiguousLine`(1) + `size`(`isa.Size`, 3 elements → 2)
= **8 bits**. Therefore `PRED_BITS_PER_WORD` = 8, `PRED_BITS_PER_LINE` = 256,
`PRED_BITS_PER_BEAT` = 128. Geometry: 64 sets × 4 ways × 64 B lines, 2 × 256-bit
beats per line.

### 3.1 `IcachePlugin`'s register population (source-derived, exact)

| Register | Width | Flops | Clock enable | Fate |
|---|---:|---:|---|---|
| `s1PredEntries` (`:328`) | 4 × 256 | **1,024** | `cmdPort.fire` (ITLB + 4-way async tag compare + `answerable`) | **DELETED** (M1) |
| `lineReg` (`:202`) | 512 | **512** | `axi.r.fire && demandRspMatch` per half; `pfInstallAny && !demandFillStart` in IDLE | **DELETED** (M2) |
| `missPred` (`:233`) | 256 | 256 | `predActive && !isLoBeat` | **DELETED** → 32-bit window (M2) |
| `predAccumLo` (`:237`) | 128 | 128 | `predActive && isLoBeat` | **DELETED** (M1) |
| `valids` (`:157`) | 4 × 64 | 256 | per-bit decoded WE | kept |
| `pfFilled` (`:164`) | 4 × 64 | 256 | per-bit decoded WE | kept (telemetry; see §14 Q3) |
| `victim` (`:159`) | 64 × 2 | 128 | `doAllocate && set match` | kept |
| pf slot state (`:269-281`) | 5 slots | ~330 | mixed | **folded into MSHR file** (M2) |
| `pfDemandLine`/`pfNextPa`/`pfLimitPa` (`:288-290`) | 3 × 32 | 96 | `cmdPort.fire`-derived `seedPfWindow`, and an allocator `when` containing `heldDemandMiss` (⊃ `isHit`) | **enable re-sourced** (M4) |
| miss context (`:191-215`) | — | ~97 | `cmdPort.fire && !fault && !hit` | **enable re-sourced** (M3) |
| `arHoldValid/Id/Addr` (`:569-571`) | 1+4+32 | 37 | contains `heldDemandMiss` ⊃ `isHit` | **enable re-sourced** (M4) |
| s1 control (`:312-334`) | — | 40 | `cmdPort.fire` | becomes S0 context (M3) |
| rsp stage (`:338-343`) | — | 131 | free-running | kept |
| **Total** | | **≈ 3,290** | | **−1,920 flops (−58 %)** |

The `IcachePlugin` sub-threshold endpoint count from the census is **2,427**, of
which 1,751 are CE. A plugin with ~3,290 flops contributing 2,427 near-critical
endpoints means roughly three quarters of its registers are near-critical. Deleting
1,920 of them is not a trim; it is a change of shape.

### 3.2 `IcachePlugin`'s distributed-RAM population

`predMem` (`:153`) = `Seq.fill(4)(Mem(Bits(256), 64))` = **65,536 bits**, with **two
async read ports** (`lookupPredEntry` at `:467`, `replayPredEntry` at `:795`). Async
read forces distributed RAM; UltraScale+ RAM64X1S holds 64 bits per LUT, so this is
**≥ 1,024 LUTs for one read port and, if Vivado replicates for the second (it
normally does for a second async read address), ~2,048 LUTs.** The whole design uses
**9,068 LUTs as distributed RAM**, so `predMem` alone is plausibly **11–23 % of every
distributed-RAM LUT in the core** — sitting in the left strip, feeding a 4 × 256-bit
capture bank.

`tagMem` (`:142`) = 4 × 64 × 20 = 5,120 bits, two async read addresses (`lookupSet`,
`pfCandSet`) ⇒ ~160–320 LUTs. Small; not a target for deletion, but M3 changes how it
is read.

*(Exactness caveat: the 1,024/2,048 LUT figures are derived from the bit count and
the primitive's capacity, not read out of a hierarchical utilisation report. A
`report_utilization -hierarchical` on the archived routed DCP would pin them exactly
and is listed as a pre-implementation task in §11.4.)*

### 3.3 The two arcs that bound the baseline

```
−1.472  DcachePlugin_logic_stS2Payload_paddr_reg[5]/C → IssueQueuePlugin_logic_sbNzvc_busy_reg[8]/D
−1.472  FetchAlignPlugin_logic_stalled_reg/C          → IcachePlugin_logic_lineReg_reg[418]/D      ← TIED
−1.470  FetchAlignPlugin_logic_stalled_reg/C          → IcachePlugin_logic_lineReg_reg[462]/D
```

plus, from `synth/floorplan_frontend.xdc`, the **worst 300 unique failing endpoints**
are all `FetchAlignPlugin_logic_stalled_reg/C → IcachePlugin_logic_s1PredEntries_{0..3}_reg[*]/CE`
at 5.990 ns (2.019 logic / 3.971 route, 20 levels, **0 pblock boundaries crossed**).

This design deletes the destination of both frontend arcs. It does **not** touch the
D-cache/IssueQueue arc. §9 makes the consequence of that explicit and unflattering.

---

## 4. Goals / non-goals

### 4.1 Goals

- **G1.** Delete `s1PredEntries`, `lineReg`, `missPred`, `predAccumLo` and the
  `predMem` distributed-RAM array from the netlist. Target −1,900 ± 100 CLB flops
  and −1,000…−2,000 distributed-RAM LUTs in `IcachePlugin`.
- **G2.** Keep the **hit path cycle-for-cycle identical**: `cmd` accepted at cycle 0,
  `rspPort.valid` at cycle 2, sustained II = 1 on hits. No change to
  `FetchAlignPlugin`'s `RING = 3` or `ibuf.BUF_WORDS = 20`, therefore no H7 hazard.
- **G3.** Make the live ITLB result terminate in registers, so that no wide register
  bank's clock enable and no AXI/prefetch arbiter enable contains a translation-fed
  comparator.
- **G4.** Ship a floorplan sized for the resulting netlist, as part of the same
  change, gated in the same run.
- **G5.** Aggregate IPC within 1 % of baseline, measured on the standing IPC bench,
  not asserted.

### 4.2 Non-goals

- **N1. 200 MHz is not claimed.** §9 states plainly that on a static reading this
  design delivers +0.000 ns, because the D-cache/IssueQueue arc also sits at −1.472.
- **N2.** No change to `FetchAlignPlugin`, `DecodeStage`, `RenameStage`, the
  `PipeStage` construct, `IssueQueuePlugin`, `RobPlugin`, `DcachePlugin`, or any
  execute-side plugin. This design's boundary is `IcachePlugin` + the XDC, plus
  (Phase 2, deferred) the predictor plugins.
- **N3.** No architectural/ISA behaviour change. Every observable at the
  `FetchService` boundary is preserved except the specific, enumerated cycle-count
  changes in §5.6.
- **N4.** The predictor complex (M6/Phase 2, §8) is designed here but explicitly
  **not** in this change's scope or its gate.

### 4.3 Reference alignment: what NaxRiscv's `FetchCachePlugin` actually does

Read fresh for this document from
`/home/qwertyoruiop/m68k-ooo-v2/thirdparty/NaxRiscv/src/main/scala/naxriscv/`
(`fetch/FetchCachePlugin.scala`, `fetch/FetchPlugin.scala`,
`fetch/AlignerPlugin.scala`, `prediction/{BtbPlugin,GSharePlugin,
DecoderPredictionPlugin,HistoryPlugin,BranchContextPlugin}.scala`, plus the pinned
`spinal.lib.pipeline` framework at SpinalHDL `c362657`). This is the FPGA-proven
reference this project already uses as its cleanliness benchmark; the findings below
are load-bearing for §5 and are cited there rather than left as background.

| # | NaxRiscv | This project today | Bearing on this design |
|---|---|---|---|
| **N-1** | **Tags read *asynchronously* in F0 and captured by the ordinary F0→F1 stage register** — `tagsReadAsync = withDistributedRam`, `getStage(readAt + (!tagsReadAsync).toInt)(WAYS_TAGS)(id) := rsp`. On FPGA the tag LUTRAM adds no latency and the compare gets a full cycle in F1. | Tags read async in the accept cycle and compared **in that same cycle**. | **Direct precedent for M3**, and specifically for §14 Q1's recommended form (registered `readAsync`, not `readSync`). |
| **N-2** | **Way-hit and data mux are split across a register.** `WAYS_HITS` and `BANKS_MUXES` (the per-way, PC-selected slice) are both produced in F1 and consumed in F2 as `Stageable`s, so the F2 mux is `OhMux.or` of a *registered* one-hot against *registered* per-way words. | Way index registered (`s1Way`), then a 4:1 mux — the compare is a cycle earlier, in the accept cone. | Validates M3's direction. Our pipeline is one stage shallower, so §5.3 keeps compare and one-hot mux in S1; **the per-way lane pre-select (N-2's `BANKS_MUXES`) is adopted** — see §5.3.2. |
| **N-3** | **The RAM output register *is* the pipeline register**: `mem.readSync(cmd.payload, cmd.valid)` with `cmd.valid := !isStuck`, and `KeepAttribute(rsp)` on every SRAM output so synthesis cannot replicate or absorb it. | `dataMem.readSync(dataReadAddr, dataReadEn)` — same idiom — but `predMem` is async and is captured into 1,024 *separate* flops. | **This is precisely M1's thesis, already shipped in the reference.** `KeepAttribute` is adopted as a hard requirement (§5.1, §5.2). |
| **N-4** | **`hitsWithTranslationWays`**: the cache tag is compared **in parallel against every TLB way's physical address**, AND-ed with the TLB way one-hot (`tpk.WAYS_PHYSICAL(i)`, `tpk.WAYS_OH(i)`), instead of waiting for the TLB's own output mux. Removes the TLB mux from the tag-compare cone with no added latency. | The ITLB's muxed `xlate.rsp.ppn` feeds `lookupTag` directly. | A genuine, shipped alternative to registering the translation. Recorded as **variant M3b** (§5.3.5). |
| **N-5** | **A miss is a self-inflicted redirect**: `redoJump` reloads the fetch PC with `FETCH_PC`, `flushIt()` kills the word, F0 is halted for the refill, and the request **replays through the normal pipeline** and hits. One outstanding refill, no MSHR, no write-and-bypass. | Miss is captured into a dedicated `miss*` context and answered from `REPLAY` + a `lineReg`/`missPred` bypass, because `FetchRsp` carries no tag and `FetchAlignPlugin` attributes by ring head. | Explains why NaxRiscv needs no ordering machinery at all. **We cannot adopt it** (it would require `FetchAlignPlugin` ring changes, out of scope per N2) — but it is why §5.3.1's `s1Unresolved` gate exists and why §12.1's ordering oracle is blocking. |
| **N-6** | **There is no I-side prefetcher.** `grep -ni prefetch` over `naxriscv/fetch/` and `naxriscv/prediction/` returns zero hits; the only prefetcher in the tree is `lsu/PrefetchPredictor.scala`. Fetch lookahead comes entirely from the BTB redirecting F0 early. | A 5-slot speculative prefetch engine: ~330 flops of slot state, a 96-flop frontier, a `PF_PRED` install state, AR arbitration priority logic, and `pfFilled` telemetry (256 flops). | **~700 flops and most of the file's control complexity have no reference precedent.** Raised as §14 Q5 — a legitimate, cheaply-measurable IPC-for-FMax structural trade, since `prefetchEnable` is already a runtime switch. |
| **N-7** | **Backpressure is also clock-enable based.** `Connection.M2S.on` compiles to `when(r){ s.valid := m.valid }` / `when(r){ payload := … }` where `r` is the downstream `ready` — every stageable crossing a boundary is a CE'd flop. But `ready` is a *shallow, locally-computed* signal, and halts target **stage 0 only** (F1/F2 drain rather than freeze). | `s1PredEntries`' CE is `cmdPort.fire` ⊃ ITLB mux ⊃ async LUTRAM read ⊃ 20-bit compare ⊃ `answerable`. | **Important nuance that partly rehabilitates and partly corrects the prior spec's CE thesis.** The reference is CE-driven too — so "CE is bad" was the wrong inference (and P1/P2 failing is consistent with that). What the reference never does is put a *translation-fed verdict* into a CE, or fan one CE to 1,024 flops. §2.3's P3 is written accordingly. |
| **N-8** | **GShare stores a `Vec` of counters per fetch word** — `Mem.fill(words)(Vec.fill(SLICE_COUNT)(UInt(counterWidth bits)))`, **one read port**, giving every slice's prediction from a single access, plus an explicit F0→F1 write-port bypass for the read-during-write hole. | `Gshare.pht = Mem(UInt(2), phtEntries)` with **seven** `readAsync` sites (2 scalar + 4 window + 1 update) ⇒ sevenfold LUTRAM replication. | Concrete Phase-2 fix, §8. |
| **N-9** | **BTB is a single `Mem` with a partial-tag hash and no valid array** (`BtbEntry = {hash(16), slice, pcTarget, isBranch}`, `readSync(addr, stage.isReady)`); aliasing is expected and caught downstream by the aligner's `predictionSanity`. | `Btb`/`Ftb` each carry `valids = Vec.fill(entries)(RegInit(False))` register arrays plus multiple `readAsync` sites, and `Btb` does a **16-wide** speculative window read (`spec2Taken/Target/Hit/Type`, 16 independent async reads of the same array). | Concrete Phase-2 fix, §8; also the real explanation for variant 5's "11× replication multiplier" framing. |
| **N-10** | **RAS = `Mem.fill(rasDepth)(PC)` + `writePort` + `readAsync(ptr.pop)`**, pointers healed from the ROB on reschedule; contents not restored. | `Ras.ras = Vec.fill(16)(RegInit(U(0,32)))` — 512 flops with 16 individually-decoded write enables, each re-deriving `feed.fire && !faultHold && s0IsCall`. | Already known (ledger §30 finding #1) and **already measured at −8.2 MHz in variant 4**. Phase 2, §8, with that prior stated. |

Two honest caveats on using this reference. First, NaxRiscv's fetch is **three
stages** (F0/F1/F2 with `M2S` links, plus an `S2M` skid into the aligner) where ours
is two — several of its splits buy their timing with latency we have decided not to
spend (§4.1 G2). Second, N-5 means its whole miss path is structurally simpler than
ours for a reason we cannot copy without entering `FetchAlignPlugin`. The reference
is used below as *evidence that a construct is FPGA-sound*, never as evidence that
adopting it will move this design's WNS — the campaign has already shown (variant 4)
that a correctly-identified NaxRiscv divergence can be fixed and still regress.

---

## 5. The architecture: Unified Fetch Array + Verdict-Terminated Lookup

Five moves, one thesis. M1 and M2 are storage migrations, M3 is the pipeline
restructuring they enable, M4 is the consequence, M5 is the physical half.

### 5.1 M1 — the Unified Fetch Array: predecode moves into the data array

**Today** (`IcachePlugin.scala:142-155, 328, 456-467, 426-438, 890-1010`):

```scala
val tagMem  = Seq.fill(ways)(Mem(UInt(tagBits bits), sets))                 // async LUTRAM
val predMem = Seq.fill(ways)(Mem(Bits(PRED_BITS_PER_LINE bits), sets))      // async LUTRAM, 256b/line
val dataMem = Seq.fill(ways)(Mem(Bits(256 bits), sets * beatsPerLine))      // sync BRAM, 256b/beat
...
val lookupPredEntry = Vec(predMem.map(_.readAsync(lookupSet)))              // 4 × 256 b, live
val s1PredEntries   = Reg(Vec(Bits(PRED_BITS_PER_LINE bits), ways))         // 1,024 flops
...  s1PredEntries := lookupPredEntry                                        // CE = cmdPort.fire
val s1PredW = Mux(s1FromMiss, windowPred(missPred, s1Pc),
                              windowPred(s1PredEntries(s1Way), s1Pc))
```

The predecode array is read **asynchronously, all four ways, whole line (256 bits per
way)** in the accept cycle, and **all 1,024 bits are captured into flops** so that the
way-mux and the `pc(5:3)` window select can be deferred to S1. That deferral was a
deliberate, documented FMax fix (`:322-327`) and it worked — at the price of the
single largest register bank in the frontend.

**Proposed.** Widen the existing synchronous data array to carry the beat's
predecode inline, and delete `predMem` outright:

```scala
// One array. 256 b of instruction bytes + 128 b of per-word predecode, per beat.
val UFA_W   = 256 + PRED_BITS_PER_BEAT               // 384
val lineMem = Seq.fill(ways)(Mem(Bits(UFA_W bits), sets * beatsPerLine))   // sync, 128 × 384/way
...
// S0: exactly the existing arm — page-invariant address, shallow enable, unchanged.
val ufaReadAddr = (lookupSet ## lookupBeatSel).asUInt       // == today's lookupReadAddr
val ufaReadEn   = cmdPort.valid && !setBlocked              // == today's dataReadEn
val ufaBeat     = Vec(lineMem.map(_.readSync(ufaReadAddr, ufaReadEn)))
ufaBeat.foreach(KeepAttribute(_))          // §4.3 N-3: the RAM output register IS the
                                            // pipeline register; do not let synthesis
                                            // replicate or absorb it back into fabric.
// S1: split, then way-select (see M3 for how the way is chosen)
def ufaData(w: Int) = ufaBeat(w)(255 downto 0)
def ufaPred(w: Int) = ufaBeat(w)(UFA_W - 1 downto 256)
```

The `KeepAttribute` is not decoration. §4.3 N-3 records that NaxRiscv puts it on
**every** SRAM output in the fetch path for exactly this reason, and the entire
premise of M1 is that these 1,152 bits stop being CLB flops. If Vivado absorbs the
memory output register back into fabric the change delivers nothing and the
post-synthesis utilisation check in §11.3 is what must catch it.

and the window decode narrows from `pc(5:3)` over a 32-word line to `pc(4:3)` over a
16-word beat — the same selector `windowPred` already builds, one bit shorter,
against a beat-granular entry whose address is *already* the address `dataMem` uses.

**Write side is unchanged in cadence.** The refill dwell (`predActive`,
`:995-1010`) already runs exactly two cycles and already writes `dataMem` once per
beat at `(missSet ## commitBeat)`. It becomes one write of `beatPred ## beatData`,
at the same address, on the same cycle, through the **same single call site** —
which is load-bearing: `:765-767` documents that a second `Mem.write` call site
breaks SpinalHDL's `MultiPortWritesSymplifier`. One array with one write site is
strictly safer here than two arrays with two.

`predAccumLo` (`:237`, 128 flops) exists **only** because the old `predMem` was
line-granular and therefore could not be written until both beats had been
classified. With a beat-granular array it has no reason to exist and is deleted; the
low beat's predecode is written on `commitBeat==0` and the high beat's on
`commitBeat==1`, mirroring the data writes byte for byte.

**Deleted by M1:** `s1PredEntries` (1,024 FF), `predAccumLo` (128 FF), the whole
`predMem` array (65,536 bits of async distributed RAM, both read ports), the
`lookupPredEntry` 4 × 256-bit live read, and the `replayPredEntry` second async read
port at `:795`.

**Added by M1:** 128 bits per entry × 128 entries × 4 ways = 65,536 bits of
synchronous memory folded into an array that is already synchronous. Whole-device
BRAM headroom is 474 free tiles (§2.2); even a pessimistic width-padded mapping
(384 bits ⇒ 11 × RAMB18E2 at 512×36 per way, 44 tiles) stays under 15 % device BRAM.

**Correctness invariants preserved, and why:**

- *Cross-beat lookahead.* `classifyBeat` needs words 16/17/18 while classifying the
  low beat (`beatNext3 = lineReg(303 downto 256)`). This is a property of the *fill*
  path, not the lookup path, and M2 preserves it by keeping the fill file split into
  `Lo`/`Hi` banks so both halves are readable in one cycle. Unchanged semantics.
- *`ambiguousLine`.* Unaffected: the bit is computed at fill time from line content
  and stored per word, exactly as now. `Aligner`'s live re-classification path
  (`Aligner.scala:63-65`) reads it from the response payload and is untouched.
- *Lever B `size`.* `ChunkPredecode.size` continues to be produced by
  `PredecodeWord.classify` at fill time and consumed by `DecodeStage`; the
  `FedSpecsPacketPairingSpec` plumbing invariant is unchanged in kind, only in which
  memory the bits came out of.
- *Fault placeholder.* Today a translation fault writes `s1PredEntries := 0`. With
  no such register the S1 stage instead masks: `s1PredW := s1Fault ? B(0) :
  windowPred(...)` — a 32-bit AND on the S1→rsp path, one LUT level, preserving the
  exact "zeroed predecode" placeholder behaviour.
- *INHIBITED / poisoned replay.* Handled by M2's window bypass, below.

### 5.2 M2 — the Unified MSHR file: one line buffer, no copy, no `lineReg`

**Today** there are two entirely separate line-data mechanisms:

| | demand | speculative (prefetch) |
|---|---|---|
| storage | `lineReg = Reg(Bits(512))` (`:202`) | `pfLineLo`/`pfLineHi = Mem(Bits(256), 5)` (`:279-280`) |
| written by | `axi.r.fire && demandRspMatch` per half (`:930-936`) | `pfLineLo.write(pfRspIdx, …)` (`:920-921`) |
| consumed by | `classifyBeat` / `dataMem` write / `missDataBeat` bypass | — |
| bridged by | — | **a 512-bit async LUTRAM read copied into `lineReg` in IDLE** (`:725`) |
| FSM state | `PREDECODE` | `PF_PRED` (a second, near-duplicate install state) |

`lineReg`'s D-input is therefore a 512-bit-wide mux between an AXI beat and a
**512-bit async read of a 5-entry LUTRAM** (a 512 × 5:1 mux), and its clock enable in
IDLE is `pfInstallAny && !demandFillStart` — where `pfInstallVec` is a five-way
reduction over `pfValid/pfComplete/pfErr/pfPoison` and `demandFillStart` is a
function of `cmdPort.fire`. That is precisely the shape the design principle
forbids: **a 512-flop bank taking a deep verdict as its enable and a wide LUTRAM
mux as its data.** It is also, per the diagnostic's item-1 table, the literal
endpoint family that B3 and P2 each regressed onto.

**Proposed.** One MSHR line file, indexed by AXI ID, demand and speculative alike:

```scala
val MSHR_N = 1 + pfSlots                       // 6: id 0 = demand, 1..5 = speculative
val fillLo = Mem(Bits(256 bits), MSHR_N)       // beat 0
val fillHi = Mem(Bits(256 bits), MSHR_N)       // beat 1
// Uniform R-channel write: no demand/prefetch asymmetry at all.
val rIdx  = axi.r.payload.id.resize(log2Up(MSHR_N))       // AxiIds: I_DEMAND == 0
val rFire = axi.r.fire && rOwnerValid(rIdx)
fillLo.write(rIdx, axi.r.payload.data, enable = rFire && !mshrBeat(rIdx))
fillHi.write(rIdx, axi.r.payload.data, enable = rFire &&  mshrBeat(rIdx))
```

and the per-slot control state generalised from 5 to 6 entries
(`mshrValid`, `mshrArSent`, `mshrComplete`, `mshrBeat`, `mshrErr`, `mshrPoison`,
`mshrPa`, `mshrSet`, `mshrTag`, `mshrWay`), with entry 0's semantics carrying what
`beatCnt`/`arSent`/`missBusFault`/`missPoison`/`missPA`/`missSet`/`missTag`/`victimWay`
carry today. **`PF_PRED` merges into `PREDECODE`**, parameterised by an install index
register; the shared-installer copy at `:723-737` is deleted outright.

The dwell reads the line it is installing out of the file, with the address
(`installIdx`) registered a cycle ahead so the read is synchronous:

```
INSTALL_ARM : installIdx := chosen ; arm fillLo/fillHi readSync
PREDECODE-0 : classify(fillLoQ, fillHiQ(47 downto 0), lo=true)  → write lineMem(set##0)
PREDECODE-1 : classify(fillHiQ, 0,                    lo=false) → write lineMem(set##1)
              tag/valid/victim commit, exactly as today
```

(`fillLoQ`/`fillHiQ` likewise carry `KeepAttribute`, per §4.3 N-3 and for the same
reason as M1: if these become fabric flops again, `lineReg` has been re-created under
a different name.)

— i.e. **the demand dwell grows by one cycle** (an arm cycle) and the prefetch
install path *shrinks* by one (it no longer needs an IDLE copy cycle). A demand
refill is ~78 cycles under DDR; +1 is 1.3 %.

**The INHIBITED / poisoned bypass, narrowed.** Today, `REPLAY` for a non-allocated
line delivers from `lineReg`/`missPred` via `s1FromMiss` — which is *why* those two
768 flops must exist at all. Only a **64-bit data window plus its four 8-bit
predecode chunks** are ever consumed:

```scala
val bypWindow = Reg(Bits(64 bits))    // captured during PREDECODE-{0,1} at missPC(5:3)
val bypPred   = Reg(Bits(32 bits))
```

96 flops replace 768. `s1FromMiss` routes to these exactly as today.

**Deleted by M2:** `lineReg` (512 FF), `missPred` (256 FF), the `pfLineLo`/`pfLineHi`
→ `lineReg` 512-bit copy path, the `PF_PRED` state, and the demand/speculative
asymmetry in the R-channel handler. **Added:** 96 FF of bypass window, ~66 FF of
sixth-MSHR control state, and one 6 × 256-bit-wide memory pair replacing a 5 × 256
pair (LUTRAM, unchanged in kind).

**Correctness invariants preserved:**

- *In-order response contract (§6.3 of the design doc, `:596-616`).* Untouched: the
  demand path still produces exactly one response per accepted command, still in
  order; the speculative path still produces none. Making demand MSHR id 0 an
  ordinary member of the file changes storage, not ordering.
- *Two-beat assertions* (`:928`, `:941`) generalise per-entry against `mshrBeat(i)`.
- *`missPoison` / invalidate-vs-allocate race* (`:998-1016`) is unchanged in
  structure: the `when(!anyInvalidate)` guard on the `valids` write and the
  `doAllocate = missCacheable && !missPoison` gate both survive verbatim, now
  indexed through the MSHR entry. **This is the single most safety-critical piece of
  the file** — `:1004-1013` documents a real, previously-shipped bug there — and §12
  requires the existing directed race test (`dbgAllocCommitCycle`/
  `dbgAllocCommitPending`) to be kept working, not rewritten.
- *D3-SET-I write-first hazard* (`:640-646`, `:774-780`): `fillArrayWrActive &&
  (lookupSet === missSet)` still blocks a same-set lookup on the array-write cycle.
  Unchanged, now referencing `mshrSet(installIdx)`.

### 5.3 M3 — Verdict-Terminated Lookup: register the translation, compare at S1

This is the pipeline restructuring, and the highest-risk move in the document.

**Today** (`:184-188`, `:456-463`, `:648-716`), in **one** cycle:

```
xlate.rsp.ppn ──▶ lookupPaddr ──▶ lookupTag ──┐
                                              ├─▶ hitVec(w) = valids(w)(set) && tagMem(w).readAsync(set)===tag
valids(w)(lookupSet) ─────────────────────────┘        │
                                                       ├─▶ isHit ──▶ answerable ──▶ cmdPort.ready  (to FetchAlign)
                                                       ├─▶ hitWayIdx ──▶ s1Way
                                                       ├─▶ cmdPort.fire ──▶ CE of s1PredEntries[1024] / s1* / miss context
                                                       ├─▶ heldDemandMiss ──▶ CE of pfNextPa / arHoldAddr / AR arbiter
                                                       └─▶ demandFillStart ──▶ CE of lineReg (via IDLE install gate)
```

A live ITLB way-mux output, an async distributed-RAM read, a 20-bit comparator, a
4-way OR, and a one-hot encoder — all upstream of a Stream `ready` that leaves the
plugin, and of every wide clock enable in it. This is the `ic.cmd.fire` cone the
census priced at 1,751 CE endpoints, and it is the *cause* of the arc
`FetchAlignPlugin_stalled_reg → s1PredEntries[*]/CE` being 20 levels and 66 % route.

**Proposed.** Split into S0 (arm + capture) and S1 (verdict + deliver), **without
adding a pipeline stage to the hit path**:

```
S0  (the cycle a command is accepted — same cycle as today)
    ufaReadEn   := cmdPort.valid && !setBlocked                 // page-invariant, unchanged
    ufaReadAddr := (lookupSet ## lookupBeatSel)                 // page-invariant, unchanged
    tagReadEn   := same                                          // tagMem becomes readSync
    cmdPort.ready := xlate.rsp.ready && !setBlocked && s0Accepting   // registered state + §5.3.1
    on fire:  s0Valid  := True
              s0Pc     := lookupPc                               // virtual
              s0Set/s0Beat/s0Lane := lookupPc bits               // virtual
              s0Ppn    := xlate.rsp.ppn                          // ◀── the ITLB result STOPS HERE
              s0Fault  := xlate.rsp.fault
              s0Cmode  := xlate.rsp.cacheMode

S1  (the next cycle — the cycle that already exists as "S1" today)
    hitVec(w) := s0Cacheable && validsQ(w) && (tagQ(w) === s0Ppn)   // registered vs registered
    isHit     := hitVec.orR
    rspDataReg := laneMux(oneHotMux(hitVec, ufaData), s0Lane)       // one-hot AND-OR, not OHToUInt+4:1
    rspPredReg := s0Fault ? 0 : windowPred(oneHotMux(hitVec, ufaPred), s0Lane)
    on miss:  launch the fill from the S0 context (already registered — M3 removes the
              separate `miss*` capture entirely; the MSHR entry is written from s0*)

S2  rspPort.valid  ── unchanged, cycle 2 after accept, exactly as today
```

#### 5.3.1 Why no skid buffer is needed, and why `ready` stays fast

`cmdPort.ready` is gated by `s0Accepting = fsmIdleish && !s1Unresolved`, where
`s1Unresolved` is combinational **from registered inputs only**: a 20-bit equality
against a registered PPN, a 4-way OR, and an AND with `s0Valid`. That is ~3 LUT
levels off flop outputs, versus today's ITLB-mux → LUTRAM-read → compare → OR →
`answerable` chain.

Timeline on a miss: command A is accepted at cycle 0. At cycle 1, S1 computes
`isHit(A) = false`, so `s1Unresolved` is high and `cmdPort.ready` is low **in that
same cycle 1** — so no younger command B is ever accepted behind an unresolved miss.
No holding slot, no captured-translation staleness across a refill, no new hazard
class. On a hit, `s1Unresolved` is low and `ready` stays high: **sustained II = 1**.

This is the key difference from the prior spec's P3 (F1/F2 translate split), which
achieved the same "ITLB out of the compare" goal by adding a real stage and paying
+1 fetch latency — which would force `RING` 3 → 4, which the ledger's own H7 finding
shows is *not free* (`cnt + (ringCount+1)*4 <= BUF_WORDS = 20` makes the fourth slot
reachable only at `cnt == 0`). **M3 costs zero fetch latency and requires no
`FetchAlignPlugin` change at all.**

#### 5.3.2 The honest cost of M3, and the two reference devices that reduce it

The S1 cone gets *deeper*, because the way select moves from "registered `s1Way`
drives a 4:1 mux" to "computed `hitVec` drives a one-hot AND-OR". The trade is:
**~10 levels removed from the accept cone (which crosses a plugin boundary and drives
1,751 CE pins), ~2 levels added to the S1→rsp cone (entirely local, ending in a
free-running 131-flop response register).** That trade is favourable on structure. It
is *not* measured, and §9 does not treat it as evidence.

Two devices from §4.3 shrink the added S1 depth, both of them shipped in the
reference rather than invented here:

1. **Per-way lane pre-select before the way mux** (§4.3 N-2, NaxRiscv's
   `BANKS_MUXES` at `bankMuxesAt` feeding `OhMux.or` at `bankMuxAt`). The lane
   select uses `s0Lane`, which is *registered and page-invariant*, so it does not
   wait on the tag compare and runs **in parallel** with it:
   ```scala
   // parallel with the compare — s0Lane is registered, s0Beat bits are virtual
   val wayWindow = Vec((0 until ways).map(w => ufaData(w).subdivideIn(64 bits)(s0Lane)))
   val wayPred   = Vec((0 until ways).map(w => windowPred(ufaPred(w), s0Lane)))
   // then a one-hot AND-OR over 64 + 32 bits, not over 256 + 128
   rspDataReg := OhMux.or(hitVec, wayWindow)
   rspPredReg := s0Fault ? B(0) | OhMux.or(hitVec, wayPred)
   ```
   The critical arc becomes `tagQ → 20-bit compare → hitVec → 64-bit AND-OR` ≈ 4
   levels, with the 256-bit-to-64-bit narrowing already retired off the critical
   path. Without this the AND-OR is over 256 + 128 bits and costs an extra level of
   OR tree plus four times the LUT count.
2. **`OhMux.or` rather than `OHToUInt` + a binary mux** (§4.3, used throughout the
   reference). The one-hot vector is already available; encoding it and re-decoding
   it is two avoidable levels.

Mitigation if S1 still proves limiting: keep `tagQ` as a **registered async read**
(arm `tagMem.readAsync(lookupSet)` into a 4 × 20-bit register at S0, same shallow
enable) rather than converting `tagMem` to `readSync`. Same result, 80 flops, no
BRAM-latency question on the tag path — and this is **exactly** what NaxRiscv does
on FPGA targets (`tagsReadAsync = withDistributedRam`, §4.3 N-1). **This is the
recommended default** — see §14 Q1.

#### 5.3.5 Variant M3b — `hitsWithTranslationWays` instead of registering the PPN

NaxRiscv offers a second, orthogonal way to get the TLB out of the tag-compare cone
that costs **no register and no cycle** (§4.3 N-4): compare the cache tag against
*every ITLB way's* physical tag in parallel and AND with the ITLB way one-hot.

```scala
// M3b sketch — requires TranslationService to expose per-way PPN + way one-hot
hitVec(w) := tagQ(w).loaded && (0 until itlbWays).map(t =>
               (tagQ(w) === xlate.rsp.waysPpn(t)(tagRange)) && xlate.rsp.waysOh(t)).orR
```

This converts "TLB output mux → comparator" into "wide parallel comparators →
AND-OR tree", which is LUT-friendlier and removes a serial mux level.

**Not adopted as the baseline**, for two reasons, both stated rather than hidden:
(i) it requires widening `TranslationService`'s response to expose per-way PPNs and
the way one-hot, which crosses the plugin boundary N2 declared out of scope and
touches the DTLB consumer as well; (ii) ledger §20 already ground the "ITLB hit-way
cone" as a target and priced it at ~0.00 ns on this netlist. Recorded because it is
the reference's own answer to this exact problem and because, if M3 proves too
invasive during S3, M3b is a genuinely smaller alternative that reaches part of the
same goal. **It is not a substitute for M3's real payload** — M3 is what removes
`cmdPort.fire` from every wide clock enable; M3b only shortens one comparator.

#### 5.3.3 What M3 deletes

The entire separate miss-context capture (`missPC`, `missPA`, `missSet`, `missTag`,
`missCacheable`, `victimWay` — ~93 flops, enable `cmdPort.fire && !fault && !hit`)
disappears: the MSHR entry is written at S1 directly from the already-registered
`s0*` context, with a shallow enable (`s1Valid && s1Miss`). The `s1*` register set
(`s1Valid/s1Way/s1Pc/s1Fault/s1Atc/s1Lane/s1FromMiss`, 40 flops) is *renamed* into
the `s0*` set and loses `s1Way` (the way is now computed at S1, not carried).

#### 5.3.4 The one thing M3 must not break

`FetchAlignPlugin` attributes every `FetchRsp` to its outstanding ring's **head**
(`FetchAlignPlugin.scala:263,283`); `FetchRsp` carries no tag. Responses must leave
this cache in accept order. M3 preserves that structurally — one S0 slot, one S1
slot, in-order, no skid, no reordering, no dropped response — but it is the
invariant whose violation is *silent* (mis-paired instruction bytes, not a crash).
§12 makes an explicit in-RTL ordering oracle a blocking deliverable.

### 5.4 M4 — registered-state-only prefetch frontier and AR arbiter

**Today:**

```scala
val heldDemandMiss = lookupActive && cmdPort.valid && xlate.rsp.ready && !lookupFault && !isHit   // :471
...
when(pfWindowHasCandidate && !anyInvalidate && !demandFillStart &&
     !pfWindowUpdate && !heldDemandMiss) { ... pfNextPa := pfNextPa + 64 ... }                    // :840-865
...
val pfChosenArSel = Mux(heldDemandMiss, pfBlockingArSel, pfArSel)                                  // :893
when(!arHoldValid) { ... arHoldAddr := pfPa(pfChosenArSel) & ~63 ... }                             // :894-905
```

`isHit` — the live translation-fed comparator — is in the clock enable of the 96-flop
prefetch frontier **and** of the 37-flop AR hold, **and** in the AR *payload* select
mux. `seedPfWindow()` (`:546-565`) is additionally enabled by `cmdPort.fire` and
computes a 32-bit add, a page-boundary clamp and a comparison chain inside it.

**Proposed.** Both consumers move behind the S1 verdict register:

```scala
// S1 produces a 4-bit registered disposition, and nothing else crosses the boundary.
val s1Disp = Reg(new Bundle { val valid, hit, fault, cacheable = Bool() })
val s1Line = Reg(UInt(32 bits))     // s0Ppn ## s0Pc(11:6) ## 0, registered

// Frontier: enabled by s1Disp only. `seedPfWindow` runs off s1Line, one cycle later.
when(s1Disp.valid && !s1Disp.fault && s1Disp.cacheable) { seedPfWindow(s1Line) }
// Allocator: `heldDemandMiss` becomes the registered `s1Disp.valid && !s1Disp.hit && !s1Disp.fault`.
// AR arbiter: same substitution; the AR payload mux reads registered MSHR state only.
```

A prefetch frontier that advances one cycle later, and an AR that is presented one
cycle later on the specific cycle a demand miss is *also* arbitrating, are
**architecturally invisible**: prefetch is a pure performance hint (rules P1–P4,
`:516-520`), and the demand AR itself is unaffected (it wins an empty arbiter from
`refillActive && !arSent`, which is already registered FSM state).

**IPC exposure, stated rather than dismissed:** the measurable effect is that a
prefetch launched immediately behind a demand miss issues its AR one cycle later.
Prefetch usefulness is measured by the existing `pfHitUseful` telemetry
(`:509`) and `IcachePrefetchSpec`; §12 requires that sweep to be re-run, not
reasoned about.

### 5.5 M5 — the floorplan half (see §7 for the full treatment)

A new `pb_fetch` pblock sized for the *post-M1/M2* netlist, replacing the
speculative `frontend` box that was drawn for the current one. §7.

### 5.6 The complete cycle-cost ledger

| Path | Today | After | Δ |
|---|---|---|---|
| I-cache **hit** (cmd accept → `rsp.valid`) | 2 cycles, II = 1 | 2 cycles, II = 1 | **0** |
| Translation fault response | 2 cycles | 2 cycles | 0 |
| Demand refill (accept → replayed response) | ~78 (DDR-bound) | ~79 | **+1** (M2 install-arm) |
| Prefetch install (complete → resident) | 2 (IDLE copy + PF_PRED×2) | 3 (arm + PREDECODE×2), no IDLE copy | ~0 |
| Prefetch frontier advance | cycle N | cycle N+1 | **+1**, hint-only |
| INHIBITED / poisoned replay | 2 | 2 | 0 |
| `FetchAlignPlugin` `RING`, `BUF_WORDS` | 3, 20 | 3, 20 | **unchanged** |

Projected aggregate IPC impact: **< 0.3 %**, dominated by the +1 refill cycle on a
~78-cycle event. **This is a projection, not a measurement**, and §10 makes an IPC
re-measurement a blocking gate rather than a footnote.

---

## 6. What this does to the diagnostic's own named worst-path families

The checkpoint-forensics diagnostic's item-1 table is the most useful artefact this
campaign produced. Reproduced, with this design's disposition appended:

| Regressed variant | Its new worst path's endpoint | Disposition under this design |
|---|---|---|
| B3 | `IcachePlugin_s1PredEntries_0[122]/CE` | **register does not exist** (M1) |
| P2 | `IcachePlugin_lineReg[108]/D` | **register does not exist** (M2) |
| P1 | `IcachePlugin_arHoldAddr[30]/D` (+ `lineReg`/`pfPa`/`pfWay` siblings) | `arHoldAddr` enable + payload from registered state (M4); `lineReg` gone (M2) |
| RAS/scoreboard | `IcachePlugin_lineReg[149]/CE` (+ `pfDemandLine`/`pfNextPa`/`pfLimitPa` siblings) | `lineReg` gone (M2); the three frontier regs' CE is registered state (M4) |
| **baseline tied #2** | `IcachePlugin_lineReg[418]/D`, `[462]/D` | **register does not exist** (M2) |
| **worst-300 arc** (`floorplan_frontend.xdc`) | `IcachePlugin_s1PredEntries_{0..3}[*]/CE` | **register does not exist** (M1) |
| baseline nominal WNS | `IssueQueuePlugin_sbNzvc_busy[8]/D` | **untouched — see §9** |
| variant 8's new worst | `IssueQueuePlugin_sbInt_busy[19]/D` | **untouched — see §9** |

Six of eight named frontend endpoint families are deleted or de-deepened by
construction. The two that are not are both in the D-cache/IssueQueue cluster, which
this design does not enter — and which is exactly why §9 cannot project 200 MHz.

**The falsifiable claim this design makes:** if it is gated and the new worst path is
*again* inside `IcachePlugin`'s capture bank, then either an implementation error left
one of these banks alive, or the diagnostic's finding #4 is about the *region* rather
than the *registers* — a genuinely new and important piece of information either way.
If instead the new worst path is in the D-cache/IssueQueue cluster (the expected
outcome), the frontend is closed as an FMax target for good and §33's option list is
the honest next step.

---

## 7. M5: the floorplan half of the co-design

The diagnostic's implication #2(b) — *"pairing any future RTL lever with a
simultaneous, deliberate floorplan adjustment sized for the CHANGED netlist rather
than assuming the existing `pb_decode` box (tuned for baseline's exact netlist) will
keep fitting"* — has never been tried. Variant 7 tried a floorplan sized for the
*current* netlist and got the campaign's best iteration yield (+0.685 ns) while
losing on round-0 (−2.422 vs −2.094). That is the exact failure mode a co-design
should fix.

### 7.1 What changes physically

`synth/floorplan_frontend.xdc`'s sizing block records the current fetch/predict
capture as **25,229 cells = 14,570 LUT + 4,296 FF + 113 CARRY + 855 MUXF + 5,395
other**, in `SLICE_X0Y20:SLICE_X35Y135` (4,176 slices = 33,408 LUT sites), i.e.
44.0 % LUT-site and **6.4 % FF-site** occupancy — the sparseness the file itself
blames for the 66 % route share.

After M1 + M2 the same capture loses **≈1,920 FF (−45 % of its flops)** and
**≈1,000–2,000 distributed-RAM LUTs**, and gains BRAM sites, which are outside the
slice grid entirely. The box that was right for 4,296 FF at 2.3 FF/slice is not
right for ~2,400 FF anchored on BRAM columns.

### 7.2 The proposed pblock set

Add `synth/floorplan_fetch.xdc`, a new `FLOORPLAN_MODE` token `fetch`, and gate the
matrix `{decode} × {decode + fetch}`:

1. **`pb_fetch`** over `IcachePlugin` + `FetchAlignPlugin` + `FtbPlugin` +
   `GsharePlugin` + `RasPlugin` + `BtbPlugin`, using
   `synth/probe_floorplan_filters.tcl`'s existing **clean** capture filter (the file
   documents the instance-path trap that makes a bare `*XPlugin_logic*` match drag in
   7,033 foreign ROB cells — reuse the filter, do not re-derive it).
2. **Sized from a real post-synthesis measurement of the NEW netlist, not from this
   document.** Procedure: run `synth_design` on the new RTL, run the existing filter
   probe, and size the box to land in the 55–65 % LUT-site band that
   `floorplan_decode_fe.xdc`'s own sizing note treats as the working range — never
   the 96.78 %-occupancy trap `floorplan_decode.xdc` records, and never by annexing
   occupied territory (the X87→X103 lesson, −2.447 → −2.558 ns).
3. **Anchored to the BRAM columns the Unified Fetch Array now sits on.** Include the
   relevant `RAMB18E2`/`RAMB36E2` sites in the pblock rather than boxing only slices,
   so the placer is not forced to stretch between a boxed logic region and an
   unboxed memory column.
4. **`pb_decode` left at `SLICE_X36Y0:SLICE_X87Y104`, unchanged.** Two independent
   experiments (the X87→X103 widening, and variant 6's loosening at −12.65 MHz) say
   this box is at a local optimum for the decode capture. Change one thing.

### 7.3 Why this is not just "variant 7 again"

Variant 7 pinned 1,900 sparse flops with a pblock and paid for it at round 0. This
design **deletes those flops and then draws the box around what is left**, which is
denser, smaller, and anchored on fixed BRAM sites. The two moves are not
independent — the RTL change is what makes the floorplan cheap, and the floorplan is
what converts the RTL change into the compact left-shifted cluster the diagnostic's
item-3 centroid table associates with the good basin. Gating them separately would
be exactly the mistake §11.3 is written to prevent.

---

## 8. Phase 2 (deferred, designed but explicitly out of scope): the predictor complex

Recorded here because the brief asked for it and because the evidence is real, but
**not part of this change and not part of its gate.**

**What is there today.** Three separately-evolved modules with four PC-indexed
lookup structures between them:

- `Btb.scala`: `valids = Vec.fill(entries)(RegInit(False))` + `mem = Mem(BtbEntry)`,
  with `mem.readAsync` at three sites — and a **16-wide** speculative window lookup
  (`spec2Taken/spec2Target/spec2Hit/spec2Type`, `:178-181`) that is 16 independent
  async reads of the same array. That replication is what made "BTB tag narrowing"
  look like an 11× multiplier lever (variant 5, −20.1 MHz).
- `Ftb.scala`: `valids` register Vec + `mem = Mem(FtbEntry)` with three more
  `readAsync` sites; `FtbEntry` **already carries a 2-bit `counter`**.
- `Gshare.scala`: `pht = Mem(UInt(2), phtEntries)` with **seven** `readAsync` sites
  (2 scalar + 4 window + 1 update), i.e. sevenfold LUTRAM replication, plus `ghr` and
  a registered `winPayload`.
- `Ras.scala`: `ras = Vec.fill(16)(RegInit(U(0,32)))` — **512 flops with 16
  individually-decoded write enables**, each re-deriving
  `FetchAlignPlugin`'s `feed.fire && !faultHold && s0IsCall`. This is the measured
  100 %-CE, 496-endpoint family.

**The Phase-2 design**, with each element now anchored to a shipped reference
construct rather than to first principles (§4.3 N-8/N-9/N-10):

1. **One `PredictArray`.** A single synchronously-read, window-indexed table holding
   `{hash, brWordOff, brLen, brType, target, counter}` per fetch window — i.e.
   `FtbEntry` — with `BtbPlugin`'s per-PC table folded into it. They are two
   generations of the same structure. NaxRiscv carries exactly one such table
   (`Mem.fill(entries)(BtbEntry())`, `readSync(addr, stage.isReady)`) for the whole
   frontend.
2. **Drop the `valids` register arrays** from `Btb`/`Ftb` in favour of a partial-tag
   hash, per N-9: NaxRiscv's `BtbEntry` has *no valid bit at all* and tolerates
   aliasing because the aligner's `predictionSanity` catches it downstream —
   structurally the same guarantee this project already relies on
   (`BranchEuPlugin` cross-checks predicted direction *and* target at resolve; see
   `IcachePlugin.scala:66-88`'s own analysis of which predictors need invalidation
   and which do not). **Caveat, and it is a real one:** that same analysis concludes
   the **BTB *does* need invalidation on CINV/CPUSH-IC**, because a stale BTB hit can
   redirect fetch on a non-branch and nothing verifies it. Any move to a
   hash-only, valid-less table must preserve a working `invalidateAll`, which a
   `Mem` cannot do in one cycle. **This is an open design problem, not a solved
   one** — probably a small generation counter folded into the hash.
3. **Collapse `Gshare`'s seven `readAsync` sites to one**, per N-8: store a `Vec` of
   counters per fetch window (`Mem.fill(words)(Vec.fill(4)(UInt(2 bits)))`) so the
   4-wide window lookup is a single access instead of four replicated LUTRAMs, and
   add the F0→F1 write-port bypass NaxRiscv uses to cover the read-during-write hole.
   This is the largest single LUTRAM reduction available anywhere in the frontend.
4. **RAS → `Mem.fill(16)(UInt(32))` + `writePort` + `readAsync(sp-1)`**, per N-10.
   **Stated prior: this exact conversion has already been measured at −8.2 MHz**
   (variant 4, bundled with the IQ scoreboard change). It is included because the
   divergence is real and because a 512-flop array with 16 decoded write enables is
   indefensible on its own terms, not because the measurement is disputed.

**Why it is deferred, and this is not hedging:** it changes *prediction accuracy*,
therefore IPC, therefore the aggregate figure of merit — and it needs its own
accuracy gate (misprediction rate per benchmark, not just "tests pass"). Folding an
IPC-risky predictor redesign into an IPC-neutral cache restructuring would make the
single gate uninterpretable. Also, the RAS `Vec[Reg]`→`Mem` conversion **has already
been measured, in variant 4, at −8.2 MHz** — that is a real prior against doing it
casually.

---

## 9. Honest projection

### 9.1 What the static reading says, stated first and without softening

**On a static reading this design delivers +0.000 ns.**

Baseline WNS is a three-decimal tie: `DcachePlugin → IssueQueuePlugin sbNzvc` at
−1.472 and `FetchAlignPlugin → IcachePlugin lineReg` at −1.472/−1.470. If every
frontend family named in §6 retires perfectly and nothing else changes, WNS is set by
the D-cache/IssueQueue arc, which this design does not touch, and the number is
**−1.472 ns / 182.749 MHz**. This is the same conclusion the prior spec reached in
its §7.2 and it has not changed.

Every gain this design could produce is therefore **placement-mediated**: it must
come from the freed left strip, the BRAM anchoring, and the reduced endpoint
population changing the *global* solve well enough that the D-cache/IssueQueue arc
also improves. That mechanism is real (it is the diagnostic's central finding) and it
is **not modellable by any static probe**. The prior spec's caveat is inherited
verbatim and strengthened:

> No `set_false_path` what-if probe models this class of change. A probe models path
> *removal*, after which the next path is promoted unchanged — it cannot model 1,900
> deleted registers reshaping a placement basin. The measured error of static
> prediction on this design runs both ways and is large: `28ec738` predicted +0.124 ns
> and delivered +0.947 ns; B3 predicted a TNS/population gain and delivered −0.254 ns;
> variant 8 was provably zero-behaviour-change and net LUT-reducing and delivered
> −21.2 MHz. **Static evidence for this design is silent, not supportive.**

### 9.2 The empirical prior, stated second

Eleven measured perturbations of this netlist (8 this session + 3 tool-axis in the
diagnostic's item-5 table) have produced **zero** results at or above baseline. The
mean is roughly −14 MHz; the best non-baseline result on the standard recipe is the
`-3` speed grade at −1.620 ns (a *part* change, not an RTL change). **The honest
prior for any perturbation, including this one, is negative.**

### 9.3 What this design has that the eleven did not

Three things, all checkable before implementation:

1. It is the only candidate that **deletes** the documented owner of the worst-300
   endpoint arc, rather than moving, shrinking, or re-boxing it. (§6.)
2. It is the only candidate that migrates state across **resource classes** into a
   resource at 5.42 % utilisation, giving the placer fixed anchors instead of a
   sparse flop cloud. (§2.2.)
3. It is the only candidate that ships **RTL and a floorplan sized for the resulting
   netlist in the same gate** — the diagnostic's own implication 2(b), never
   attempted. (§7.)

None of these is evidence that it will work. They are the reasons it is not the
twelfth instance of the same experiment.

### 9.4 The range

| | WNS | FMax | Reasoning |
|---|---:|---:|---|
| **Floor** | −2.30 ns | **≈157 MHz** | Worse than any single prior attempt is genuinely available: this is a larger diff than any of the eight, it changes memory inference (a synthesis-level lever none of them touched), and variant 8 showed a *smaller* change degrading round-0 by 0.936 ns on its own. If the widened array maps to a wide BRAM cascade with a large output mux, round-0 could start worse than variant 8's −3.030. |
| **P10–P90** | −2.05 … −1.25 ns | **165 – 191 MHz** | The observed spread of eleven measured perturbations, shifted by the §9.3 differentiators. |
| **Central** | −1.50 … −1.40 ns | **178 – 186 MHz** | The frontend families retire; the D-cache/IssueQueue arc is unchanged at −1.472; global placement moves by an amount that is as likely to hurt as help. |
| **Ceiling** | −1.10 ns | **≈192.9 MHz** | Requires *all* of: frontend families retire; round-0 lands at or better than baseline's −2.094 (plausible — the netlist is smaller and better anchored); iteration yield holds at variant 7's measured maximum of +0.685 ns; and the freed left strip lets the global solve improve the D-cache/IssueQueue arc by ~0.35 ns. Every conjunct is individually plausible; their conjunction is not the expected case. |
| **200 MHz** | −0.00 ns | **not projected** | Would additionally require the `DcachePlugin stS2Payload_paddr → IssueQueuePlugin sbNzvc/sbInt` arc to be addressed, which ledger §25 already showed cannot be reached by cutting. |

**Subjective probability that this meets the §10 acceptance bar (≥187.5 MHz): 20–30 %.**
That is a number offered so the reader can price the ~1,500-line implementation
against it, not a number with a derivation. It is deliberately below "even odds",
because eleven for eleven is eleven for eleven.

### 9.5 The fallback value, which is why this is still worth building

This is the first candidate in the campaign whose value **does not depend on the FMax
verdict**. Independent of timing, it delivers:

- −1,920 CLB flops and −1,000…−2,000 distributed-RAM LUTs in the frontend;
- deletion of an entire redundant memory array (`predMem`) and an entire redundant
  line-copy datapath (`pfLine*` → `lineReg`);
- one fewer FSM state (`PF_PRED` merged into `PREDECODE`) and the removal of the
  demand/speculative asymmetry in the R-channel handler — a genuine simplification of
  the file the ledger has repeatedly found hardest to reason about;
- convergence toward the NaxRiscv `FetchCachePlugin` shape (translation registered
  before the compare; line state in memory, not flops), i.e. toward the reference
  design this project already treats as its FPGA-friendliness benchmark.

**USER DECISION NEEDED (D1):** if the gate comes back *neutral* (−1.472 ≤ WNS <
−1.322, i.e. 182.7–187.5 MHz) with IPC and area both improved, should this land on
its structural merits, or be discarded like the other eight? §10's
"conditional-continue" band assumes *land*; that assumption is the author's, not the
user's.

---

## 10. Acceptance bar

Measured on the standard recipe — `IMPL_STRATEGY=postrouteN`, `POSTROUTE_ROUNDS=3`,
fresh synthesis (`SOURCE_MD5 == NETLIST_MD5`, no `REUSE_SYNTH_DCP`), verified
uncontended machine, run in an isolated `git worktree` — over the floorplan matrix
`{FLOORPLAN_MODE=decode}` and `{FLOORPLAN_MODE=decode+fetch}`, taking the better:

| Gate | Threshold | Action |
|---|---|---|
| **ACCEPT** | WNS ≥ **−1.322 ns** (**≥ 187.5 MHz**, i.e. ≥ +0.150 ns / +4.75 MHz) | Land. Continue the frontend track. |
| **CONDITIONAL** | −1.472 ≤ WNS < −1.322 | Land **only if** IPC ≥ 99 % of baseline **and** CLB LUTs ≤ baseline **and** CLB Registers ≤ baseline − 1,500, on the strength of §9.5. Close the frontend as an FMax target. *(Subject to D1.)* |
| **REJECT** | WNS < **−1.472 ns** | Do not land. Record as the ninth negative result and move to §33's option 1 or 3. |

Blocking secondary gates, all of which must pass for either ACCEPT or CONDITIONAL:

- `ExecuteLockStepSpec` + `EndToEndLockStepSpec`: **396/396**.
- `make test-fast`: **149/149, 157 suites**.
- All `VerilatorTest` frontend/cache suites: `IcacheSpec`, `IcachePrefetchSpec`,
  `FetchAlignSpec`, `FetchAlignRingTurnoverSpec`, `FetchAlignResidentCadenceSpec`,
  `AlignerSpec`, `PredecodeRefSpec`, `ChunkPredecodeSpec`, `FedSpecsPacketPairingSpec`,
  `FtqCapacitySpec`, `LineBEorPartitionSpec` — green, with the 35 known pre-existing
  `PackUnpkDecodeSpec`/`BitfieldDecodeSpec` failures re-confirmed identical on the
  parent commit before being discounted.
- **IPC: ≥ 99.0 % of baseline aggregate** on the standing IPC bench, both
  `prefetchEnable = 1` and `= 0`, measured not asserted.
- Area: CLB LUTs ≤ baseline (110,679); **Block RAM tiles ≤ 80/480** (baseline 26).
- Hold met (WHS ≥ 0), 0 Vivado errors.

**Trajectory reporting (diagnostic, not a gate):** report round-0 and every round's
WNS. Per §11.3 this must **not** be used to abort implementation mid-flight — it is
recorded so the eleven-variant table gains a twelfth honest row either way. A round-0
better than −2.094 would be the first ever measured and is the single most
informative sub-result this change can produce.

---

## 11. Risk assessment, and a staging strategy that is not a point fix

### 11.1 What could make this fail *bigger* than the eight

| # | Risk | Why it is larger here than for a point fix | Mitigation |
|---|---|---|---|
| R1 | **Memory inference goes wrong.** A 384-bit-wide × 128-deep array may map to a wide BRAM cascade with a large output mux, or to distributed RAM, adding LUTs and depth instead of removing them. | None of the eight touched memory inference at all. This is a new failure axis. | Post-`synth_design` `report_utilization` + `report_ram_utilization` **before** placing. Named fallback: keep a separate `predMem = Mem(Bits(128), sets*2)` `readSync` array (M1 minus the fold) — same flop deletion, no width change to `dataMem`. See §14 Q2. |
| R2 | **Round-0 degrades anyway.** The dominant variable (§1.1a) is one nobody has yet been able to steer. | A bigger diff has more ways to land badly. | Nothing structural. This is the honest core of the 20–30 % figure. |
| R3 | **The in-order response contract breaks silently.** M3 restructures the accept/verdict boundary that guarantees it. | A cache correctness bug of this class produces *mis-paired instruction bytes*, not a crash — the most expensive failure mode in the file. | Blocking in-RTL ordering oracle (§12.1); mutation proofs (§12.2); full lock-step. |
| R4 | **The invalidate-vs-allocate race regresses.** `:1004-1013` documents a real shipped bug there; M2 rewrites the surrounding code. | The dwell's cadence and the install index both change. | Keep `dbgAllocCommitCycle`/`dbgAllocCommitPending` and their directed test working *unmodified in intent*; treat any need to weaken that test as a design defect. |
| R5 | **Test-surface breakage masquerading as a design problem.** `IcacheSpec` (≈970 lines) and `IcachePrefetchSpec` (1,028 lines) read `predMem(w).getBigInt(...)`, `dataMem`, `tagMem` and `valids` raw, and assert byte-for-byte non-corruption of unrelated ways. | These are *good* tests — they caught the corruption bug — and M1/M2 change every address they use. | Rewrite the raw-array accessors as a small shared helper in the spec files, in a **separate, first commit**, against the *old* RTL, so the test change and the RTL change are independently reviewable. |
| R6 | **S1 becomes the limiter.** M3 adds ~2 levels to the S1→rsp cone. | If S1 binds, the design has traded one frontend limiter for another. | §5.3.2's registered-async-tag variant; and the response register is free-running, so `phys_opt` has retiming room here that a CE cone does not. |
| R7 | **IPC regresses more than projected.** Prefetch decisions move one cycle later; the refill dwell grows. | Prefetch effectiveness is a tuned, measured property (`pfHitUseful`), not an obvious one. | IPC is a blocking gate (§10), swept with prefetch on and off. |
| R8 | **The floorplan half fights the RTL half.** A box sized from this document rather than from the new netlist repeats variant 7's round-0 penalty. | Two coupled changes, one gate. | §7.2 item 2: size the box from a real post-synthesis probe of the new netlist, and gate `{decode}` as well as `{decode+fetch}` so the RTL is never judged only through a possibly-bad box. |

### 11.2 What this design does *not* risk

- No `FetchAlignPlugin`, `RING`, `BUF_WORDS` or `ftqDepth` change ⇒ **no H7 hazard**.
- No fetch-latency change ⇒ no redirect-penalty regression.
- No ISA/architectural behaviour change; no MMU/ATC interaction change (the ITLB
  request wiring at `:130-133` is untouched; only *when its response is registered*
  changes).
- No predictor change (Phase 2 deferred) ⇒ prediction accuracy is bit-identical.

### 11.3 The staging strategy — and why it deliberately refuses the eight-times-failed pattern

The eight failures share one procedural feature: **each was gated in isolation, and
each was judged against baseline's specific basin.** Doing that again with M1, then
M2, then M3 would be the ninth, tenth and eleventh instances of the same experiment,
and the diagnostic's implication #2 predicts they would each fail for the same
reason.

Therefore:

> **Stage by correctness. Gate once.**

- **Implementation is staged** into reviewable slices (§13), each with full
  functional verification (`fastTest` + targeted Verilator suites + lock-step) and
  its own commit. This keeps the change debuggable.
- **Only the complete stack is gated** on `postrouteN3`, in the `{decode}` ×
  `{decode+fetch}` matrix. **No intermediate slice is post-route gated, and no
  intermediate result may trigger an abort.**
- **Two pre-implementation measurements are exempt** because they change *what gets
  built*, not *whether to continue*: the hierarchical utilisation read and the
  `prefetchEnable = 0` IPC sweep (slice **S-pre**, §13). Neither involves the new
  RTL, so neither can be a disguised early gate on it.
- **One exception during implementation, information-only:** a single
  `synth_design`-only run (no place, no route — ~5 minutes) after M1+M2 to check R1 (memory inference and utilisation).
  Its output may change *which variant* of M1 is built (§14 Q2). It may **not** be
  used as a go/no-go on the design, and its WNS must not be quoted as a predictor —
  variant 8's post-synthesis WNS was −2.817 and its final was −2.189, and B3's
  round-0 was better than baseline's while its final was worse.
- **The cheap placement pre-screen the diagnostic proposed** (`pb_decode` spillover
  LUT count + per-module centroid diff) is run on the final `place_design`
  checkpoint **as a recorded observation only**, not as a gate. It has now been run
  once (variant 8) and produced a *fourth* distinct signature that still regressed;
  it is not yet a validated predictor.

This is a genuine tension and it is named rather than hidden: gating once means
spending the full implementation cost before learning anything about FMax. That is
the price of not running the failed experiment a ninth time, and the §9.5 fallback
value is what makes it a defensible price.

---

## 12. Verification plan

### 12.1 Required in-RTL oracles (simulation-only)

1. **Response-order oracle (blocking, R3).** A monotone sequence counter stamped on
   each accepted `cmdPort` command and checked at `rspPort.valid`: assert every
   response's stamp is exactly `prevStamp + 1`. This is the invariant `FetchRsp`
   cannot carry in hardware and that M3 must not break.
2. **One-AR-per-line oracle.** Assert that N consecutive fetches to the same line
   produce exactly one AR — the observable requirement `:625-632` states for the
   backpressure-based duplicate-miss suppression, which M3 re-implements via
   `s1Unresolved`.
3. **MSHR-file exclusivity oracle.** Assert no two live MSHR entries own the same
   set (the "one fill owner per set" invariant `:840-865` enforces), now that demand
   and speculative entries share one file.
4. **Unified-array equivalence oracle.** During PREDECODE, assert
   `lineMem(w)(set##beat)(383:256)` equals `PredecodeWord.classify`'s result for
   every word of that beat, computed independently in the testbench.

### 12.2 Required mutation proofs

Each of these must make a *named* test fail; if one does not, the test suite does not
cover the invariant and a test must be added before the change lands:

- Force `s1Unresolved := False` ⇒ must break oracle 1 or 2.
- Drop the `when(!anyInvalidate)` guard on the `valids` write ⇒ must break
  `IcacheSpec`'s invalidate-race test.
- Write the high beat's predecode at the low beat's address ⇒ must break oracle 4
  and `FedSpecsPacketPairingSpec`.
- Remove the `fillArrayWrActive && (lookupSet === missSet)` block ⇒ must break the
  D3-SET-I same-set test.
- Route `s1FromMiss` to the array instead of the bypass window ⇒ must break the
  INHIBITED-replay corruption test (`IcacheSpec:808-836`).

### 12.3 Suites

Full `make test-fast` (149/149), `ExecuteLockStepSpec` + `EndToEndLockStepSpec`
(396/396), and every suite listed in §10. Plus the `IcachePrefetchSpec` telemetry
sweep with `prefetchEnable` in {on, off} (design doc §8.3), since M4 changes prefetch
timing.

### 12.4 Execution discipline (standing project rules, restated because they have been
violated this session)

- **Mandatory isolated `git worktree`** for implementation and for the gate. `agent-01`
  was found dirty twice this session from experiments run in the shared reference
  worktree; the standing task-#199 collision rule applies to "zero-RTL" work too.
- **Re-derive, don't inherit.** Any "current worst path" quoted during implementation
  must come from a live `report_timing` on the checkpoint actually being discussed.
  This error class has now occurred at least three times in this campaign.
- **No nested background synth jobs inside a subagent.** Confirmed 5+ times this
  session that completion notifications do not reliably wake a subagent from a
  nested `run_in_background` job. The gate must be launched by whoever is waiting
  for it.
- Machine budget: ≤ 2 concurrent heavy JVMs, never during Vivado; verify 0 competing
  `impl_FullCore.tcl` before and after the gate and record it.

---

## 13. Scope estimate

**This is not a single-session change. It needs its own multi-slice implementation
plan** (`writing-plans`), and this document should not be implemented directly from.

| Slice | Content | Files | Est. lines |
|---|---|---|---:|
| **S-pre** | Two cheap measurements that can change the design before a line is written: (a) `report_utilization -hierarchical` / `report_ram_utilization` on the archived routed DCP, to pin `predMem`'s real LUTRAM cost (§3.2's 1,024–2,048 estimate); (b) the standing IPC bench with `prefetchEnable = 0`, to answer §14 Q5 / D4. Neither needs RTL, neither needs a place-and-route. | — | 0 |
| **S0** | Test-surface refactor: shared raw-array accessor helpers in `IcacheSpec`/`IcachePrefetchSpec`, committed **against the old RTL** so it is independently reviewable. | 2 test files | ~150 |
| **S1** | M1 — Unified Fetch Array. `lineMem` replaces `dataMem`+`predMem`; delete `s1PredEntries`, `predAccumLo`; narrow `windowPred` to `pc(4:3)`; fault masking. | `IcachePlugin.scala`, 3–4 test files | ~350 |
| **S2** | M2 — Unified MSHR file. `fillLo`/`fillHi`, 6-entry control state, merge `PF_PRED` into `PREDECODE`, install-arm cycle, bypass window, delete `lineReg`/`missPred`. | `IcachePlugin.scala`, 2 test files | ~450 |
| **S3** | M3 — Verdict-Terminated Lookup. S0 capture, registered tag read, S1 compare/one-hot select, `s1Unresolved` ready gate, MSHR write from `s0*`, delete `miss*` context. | `IcachePlugin.scala`, oracles | ~350 |
| **S4** | M4 — registered-state prefetch frontier + AR arbiter; `s1Disp`. | `IcachePlugin.scala` | ~120 |
| **S5** | M5 — `synth/floorplan_fetch.xdc` + `impl_FullCore.tcl` token; size from a real post-synth probe of the S1–S4 netlist. | 2 synth files | ~80 |
| **S6** | Oracles (§12.1), mutation proofs (§12.2), IPC sweep. | test files | ~250 |
| **S7** | The single gate: matrix `{decode}` × `{decode+fetch}`, archive, ledger. | — | — |

If §14 Q5 / D4 resolves in favour of deleting the I-side prefetch engine, an
**S2b** slice (~−400 lines net, mostly deletions in `IcachePlugin.scala` and
`IcachePrefetchSpec`) replaces part of S2 and shrinks S4 to near-nothing, since M4
exists largely to de-deepen the prefetch allocator and AR arbiter that would no
longer be there.

**Total: ~1,750 lines across ~10 files**, of which `IcachePlugin.scala` (currently
1,068 lines) is effectively rewritten from `:140` to `:1010`. Comparable in size to
the largest prior attempt (B3, +370/−23) roughly **four times over**, and unlike B3 it
spans storage encoding, memory inference, pipeline boundaries, and the floorplan.

Wall-clock estimate: **3–5 focused sessions** for S0–S6, plus ~2 × 35 min for S7's
matrix, plus contingency for R1's fallback variant.

---

## 14. Open questions and USER DECISION NEEDED callouts

**Q1 — `tagMem`: `readSync` or registered `readAsync`?** §5.3.2 recommends keeping
`tagMem` as distributed RAM and registering its async read output into a 4 × 20-bit
S0 register (80 flops), rather than converting it to a synchronous memory. It gets
the same "compare registered vs registered" property with no BRAM-latency question
and a trivially reviewable diff. Resolve during S3 with a `report_utilization`
comparison, not by argument.

**Q2 — Fold predecode into `dataMem`, or keep a parallel synchronous `predMem`?**
The fold (one 384-bit array) is architecturally cleaner and removes a whole memory;
the parallel form (`Mem(Bits(128), sets*2)`, `readSync`, same address and enable)
deletes exactly the same 1,152 flops with a smaller blast radius and no width change
to a proven BRAM mapping. **Decide from the S1 post-synthesis utilisation check
(§11.3), not from this document.** The design's claims hold under either.

**Q3 — `pfFilled` (256 flops, telemetry-only).** It exists to derive design-doc
§8.3's "prefetch useful/wasted" metric and to gate `pfHitUseful`. It is a
per-(way,set) register array with decoded write enables, i.e. exactly the pattern
this design is removing elsewhere. It could fold into the MSHR file or become a
single per-set bit. Left alone in this design to keep the prefetch measurement
methodology intact; flagged because it is 256 flops in the target region.

**Q5 — should the I-side prefetch engine exist at all?** §4.3 N-6 records that
NaxRiscv's `FetchCachePlugin` has **no instruction prefetcher whatsoever** — zero
`prefetch` hits across `naxriscv/fetch/` and `naxriscv/prediction/`; its only fetch
lookahead is the BTB redirecting F0 early. This project's I-side engine is ~330 flops
of five-slot MSHR state, a 96-flop frontier, `pfFilled`'s 256 telemetry flops, a
dedicated FSM state, and the AR-arbitration priority logic that is the *reason*
`heldDemandMiss` (⊃ `isHit`) reaches `arHoldAddr` at all (M4 exists to undo that).
Deleting it would remove ~700 flops and most of the remaining control complexity in
the file — a larger structural cut than M2, on top of M2.

**It is also the cheapest large question in this document to answer**, because
`prefetchEnable` is already a runtime-pokeable register (`IcachePlugin.scala:110`)
and `pfHitUseful` telemetry already exists: **run the standing IPC bench with
`prefetchEnable = 0` and read the aggregate delta.** If the engine is worth less than
~1 % IPC, deleting it is a better-evidenced structural change than several of the
moves in §5, and it should be folded in as **M2b** before implementation starts.
Deliberately *not* folded in pre-emptively — deleting a measured IPC feature on an
IPC-focused branch needs the measurement first, and that measurement has never been
recorded in this campaign. **USER DECISION NEEDED (D4)** if the measurement comes
back favourable.

**Q4 — `valids` (256 flops).** Cannot become a memory: it needs a single-cycle
all-clear for `invalidateAll`, which is why it is registers (`:157`, `:175-178`). A
per-set generation-counter scheme could replace it, at the cost of a comparator in
the hit path — the wrong direction for this design. Explicitly **not** proposed.

**USER DECISION NEEDED (D1) — the CONDITIONAL band.** §10 assumes a neutral FMax
result with confirmed IPC/area wins should **land** on structural merits. Given that
eight prior changes were discarded on FMax grounds alone, that assumption needs a
real decision.

**USER DECISION NEEDED (D2) — build cost vs. a 20–30 % hit rate.** §9.4's honest
probability of clearing the acceptance bar is 20–30 %, against ~1,750 lines and 3–5
sessions. §33 offers two alternatives that cost less: accept 182.749 MHz as this
branch's honest number (option 1), or pivot to the FPU/SoC-readiness legs of the
standing goal (option 3). This document is written so that decision can be made on
real numbers rather than on optimism; it does not argue that building it is
obviously correct.

**USER DECISION NEEDED (D3) — Phase 2.** Whether the predictor-complex redesign (§8)
is authorised at all, given that it trades prediction accuracy (IPC) for structure
and that one of its components has already measured −8.2 MHz in isolation.

---

## Appendix A — source anchor index

Every claim about current behaviour above is anchored to one of these:

| Claim | Anchor |
|---|---|
| `ChunkPredecode` = 8 bits | `src/main/scala/m68k040/cache/IcacheTypes.scala:18-52` |
| `s1PredEntries` = 4 × 256 flops, CE `cmdPort.fire` | `IcachePlugin.scala:328`, armed at `:686-704` |
| `lineReg` = 512 flops, wide LUTRAM D-mux in IDLE | `IcachePlugin.scala:202`, `:723-737`, `:930-936` |
| `predMem` async, 65,536 bits, 2 read ports | `IcachePlugin.scala:153`, `:467`, `:795` |
| `dataMem` synchronous BRAM, beat-addressed | `IcachePlugin.scala:155`, `:299-307`, `:1005-1009` |
| `tagMem` async LUTRAM, read at `lookupSet` and `pfCandSet` | `IcachePlugin.scala:142`, `:461`, `:524` |
| Hit verdict and `cmdPort.ready` in the accept cycle | `IcachePlugin.scala:456-463`, `:648-662` |
| `heldDemandMiss` ⊃ `isHit`, gates frontier + AR | `IcachePlugin.scala:471`, `:840-843`, `:893-905` |
| In-order response contract, no response tag | `IcachePlugin.scala:596-616`; `FetchAlignPlugin.scala:263,283` |
| D3-SET-I write-first hazard | `IcachePlugin.scala:640-646`, `:774-780` |
| Invalidate-vs-allocate race (real shipped bug) | `IcachePlugin.scala:998-1016` |
| `RING = 3`, `BUF_WORDS = 20`, the H7 bound | `FetchAlignPlugin.scala:230`, `:520-535` |
| `Ras` = 16 × 32 flops, decoded WEs | `Ras.scala:43`, `:64-72` |
| `Btb` 16-wide speculative window read | `Btb.scala:178-181`, `:99` |
| `Gshare` PHT, 7 async read sites | `Gshare.scala:82`, `:99-100`, `:116-120`, `:146` |
| `Ftb` valids-Vec + async `mem` reads | `Ftb.scala:50-51`, `:70`, `:108`, `:139` |
| Baseline utilisation, 26/480 BRAM tiles | `synth/archive/6b246de_default_postrouteN3_decode/fullcore_route_util.rpt` |
| Worst-300 arc, s1PredEntries placement, FF density | `synth/floorplan_frontend.xdc` header |
| `pb_decode` at a local optimum; X87→X103 regression | `synth/floorplan_decode.xdc` header |
| 8-for-8 record, diagnostic, floorplan experiments, §33 | `.superpowers/sdd/progress-ipc-push-2026-08-09.md` §27–§33 |

## Appendix B — NaxRiscv reference anchors (§4.3)

Root `/home/qwertyoruiop/m68k-ooo-v2/thirdparty/NaxRiscv/src/main/scala/naxriscv/`,
read-only. Framework quotes are from the pinned SpinalHDL commit `c362657`
(`lib/src/main/scala/spinal/lib/pipeline/{Stage,Pipeline,Connection}.scala`).

| Finding | Anchor |
|---|---|
| N-1 tags read async in F0, captured by the stage register | `fetch/FetchCachePlugin.scala:356-365`, `:231` (`tagsReadAsync`), `Gen.scala:123` |
| N-2 way-hit and data mux split across a register | `fetch/FetchCachePlugin.scala:488` (`BANKS_MUXES`), `:510-523` (`WAYS_HITS`/`WAYS_HIT`), `:491-494` (`OhMux.or` in F2) |
| N-3 RAM output register is the pipeline register; `KeepAttribute` | `fetch/FetchCachePlugin.scala:337-346`, `:344,363,414,440`; `prediction/BtbPlugin.scala:88`; `prediction/GSharePlugin.scala:102` |
| N-4 `hitsWithTranslationWays` | `fetch/FetchCachePlugin.scala:512-518`; `interfaces/Service.scala:391-392`; `misc/MmuPlugin.scala:337-338` |
| N-5 miss = `redoJump` + `flushIt()` + halt F0 + replay; one refill, no MSHR | `fetch/FetchCachePlugin.scala:541-568`, `:404-479` |
| N-6 no I-side prefetcher | absence across `fetch/`, `prediction/`; the only one is `lsu/PrefetchPredictor.scala` |
| N-7 `M2S` compiles to CE'd flops; halts target stage 0 | SpinalHDL `pipeline/Connection.scala` `M2S.on`; `pipeline/Pipeline.scala` arbitration; `fetch/FetchCachePlugin.scala:389,476,552,583` |
| N-8 GShare `Vec` of counters per word, one read port, write bypass | `prediction/GSharePlugin.scala:41-43,70-76,89-103` |
| N-9 BTB single `Mem`, partial-tag hash, no valid array | `prediction/BtbPlugin.scala:52-61,79-96` |
| N-10 RAS `Mem` + `writePort` + `readAsync`, pointers healed from ROB | `prediction/DecoderPredictionPlugin.scala:96-125,117-124` |
