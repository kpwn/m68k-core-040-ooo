# Large-Scale Frontend Restructure (Unified Fetch Array + Verdict-Terminated Lookup) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete `IcachePlugin`'s `s1PredEntries` / `lineReg` / `missPred` / `predAccumLo` register banks and the `predMem` async-LUTRAM array from the netlist by migrating all per-line instruction state into synchronous memory, register the ITLB verdict so no wide clock enable contains a translation-fed comparator, and ship a co-designed `pb_fetch` floorplan — all gated exactly once, on post-route timing, after the whole stack is correctness-verified.

**Architecture:** Five structural moves inside `IcachePlugin.scala`, staged as thirteen correctness-testable RTL deliverables plus three measurement/floorplan/gate tasks. M1 folds the 256-bit-per-line predecode array into the already-synchronous data array (one 384-bit-per-beat `lineMem` per way). M2 replaces the 512-bit `lineReg` and the 5-entry speculative line store with a single 6-entry AXI-ID-indexed MSHR line file written uniformly from the R channel. M3 registers the ITLB response and the way tags at S0 and computes the hit verdict at S1 from registered inputs only, latency-neutral (cmd→rsp stays 2 cycles, II=1 on hits). M4 re-sources the prefetch frontier and AR arbiter clock enables off a registered S1 disposition. M5 draws a new `pb_fetch` pblock sized from a real post-synthesis probe of the resulting netlist.

**Tech Stack:** SpinalHDL (Scala 2.13, `spinal.lib.misc.plugin.FiberPlugin`, `spinal.lib.fsm.StateMachine`), ScalaTest (`AnyFunSuite`), Verilator (`m68k040.VerilatorTest`-tagged suites), Vivado 2023.x batch (`synth/impl_FullCore.tcl`, `xcku5p-ffvb676-2-e`).

**Source of truth:** `docs/superpowers/specs/2026-08-12-large-scale-frontend-restructure-design.md` (1,358 lines). This plan implements it; where the plan deviates from or extends the spec, the deviation is called out inline as **SPEC GAP** or **PLAN DECISION** and never applied silently.

---

## Global Constraints

Every task's requirements implicitly include everything in this section.

### GC-1 — THE GATING RULE (the single most important constraint in this plan)

**"Stage by correctness, gate once."**

Unlike every prior FMax attempt this whole campaign (8 consecutive real post-route regressions across every technique tried: RTL restructuring, an IPC-trading strength reduction, floorplan-only changes, and a provably-safe area-reducing logic bundle), **no intermediate task/slice in this plan may be individually post-route FMax-gated as a go/no-go checkpoint.** Individual tasks are gated on CORRECTNESS ONLY (lockstep, VerilatorTest suites, test-fast) as they land. The real post-route `IMPL_STRATEGY=postrouteN POSTROUTE_ROUNDS=3` gate happens exactly ONCE, after ALL of M1-M5 are complete and correctness-verified together — per the design spec's own explicit reasoning that gating intermediate slices individually would just be repeating the same failed small-scale-perturbation experiment nine more times. This is a deliberate, hard-won methodological choice from this session — do not water it down or add an "optional intermediate gate" escape hatch.

Concretely, this forbids:

- running `synth/impl_FullCore.tcl` with any `IMPL_STRATEGY` that places or routes, at any point before Task 16;
- quoting a WNS number from Task 6's post-synthesis utilisation check (or from any `synth_design`-only run) as evidence for or against continuing;
- aborting, descoping, or reordering the plan because an intermediate measurement "looks bad";
- adding a "quick sanity gate" between M2 and M3, or after M4, in any form.

Exactly two measurement exemptions exist, both **information-only, neither a disguised gate**:

1. **Task 1** — `report_utilization -hierarchical` / `report_ram_utilization` on the **already-archived** routed DCP (baseline netlist, no new RTL involved). Changes *which variant* of M1 gets built (spec §14 Q2).
2. **Task 6 Step 8** — one `synth_design`-only run (no `place_design`, no `route_design`, ~5 min) on the post-M1 RTL, reading `report_utilization` / `report_ram_utilization` only, to check risk R1 (memory inference). **Its WNS must not be read, recorded, or quoted.** Spec §11.3: variant 8's post-synthesis WNS was −2.817 and its final was −2.189; B3's round-0 beat baseline's while its final was worse.

Task 2's IPC sweep is a simulation measurement, not a synthesis gate, and is exempt for the same reason (it changes what gets built, not whether to continue).

### GC-2 — Worktree isolation

**PLAN DECISION (documented per the task brief's "your call"):** implementation runs in a **fresh worktree**, not in `agent-01`.

- `agent-01` (`/home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01`, branch `codex/ipc-dcache-vipt`) is this campaign's **shared reference worktree**. It holds the baseline archives under `synth/archive/`, the design spec, and this plan. Spec §12.4 records it was found dirty twice this session from experiments run in it, and the standing task-#199 collision rule applies.
- Therefore: create the implementation worktree off the commit that adds **this plan file** (so the implementer has both the spec and the plan), on a new branch:

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
git worktree add -b feat/unified-fetch-array \
  /home/qwertyoruiop/m68k-core-040-ooo-worktrees/frontend-restructure HEAD
```

- Rationale for not using a fresh worktree off `c8ce5ba`: `c8ce5ba` predates the spec (`6144e50`) and this plan, so an implementer there would have neither document. Branching off the plan commit is strictly better and loses nothing — `c8ce5ba` is still the parent-of-parent and remains the A/B baseline.
- Rationale for not working directly in `agent-01`: it must stay clean so the Task 16 gate has an untouched baseline tree available for a same-machine A/B re-measurement if one is needed, and so the archived baseline DCPs are never invalidated.
- **Every task in this plan runs in `/home/qwertyoruiop/m68k-core-040-ooo-worktrees/frontend-restructure` unless the step explicitly says otherwise.** Tasks 1 and 16 are the exceptions (they read/write `agent-01`'s `synth/archive/` and are called out inline).

### GC-3 — Correctness baselines that must never regress at any task boundary

- `ExecuteLockStepSpec` + `EndToEndLockStepSpec`: **396/396** (394 + 2).
- `make test-fast`: **149/149 across 157 suites.**
- **`make test-fast` silently skips 120 of 157 suites** (it excludes `m68k040.SlowTest`, `m68k040.VerilatorTest`, `m68k040.BoardTest`). This is a known trap in this codebase's harness. **Every task that touches `IcachePlugin.scala` or a cache/frontend spec MUST additionally run the explicit `VerilatorTest`-tagged suite list in GC-4.** A green `make test-fast` alone is NOT evidence of correctness for any task in this plan.
- The 35 known pre-existing `PackUnpkDecodeSpec` / `BitfieldDecodeSpec` failures are discounted **only** after being re-confirmed identical on the parent commit.

### GC-4 — The mandatory explicit VerilatorTest suite list

Run alongside `make test-fast` at every task boundary that touches RTL or cache/frontend tests:

```bash
sbt "testOnly m68k040.cache.IcacheSpec \
              m68k040.cache.IcachePrefetchSpec \
              m68k040.cache.IcacheInvalidateSpec \
              m68k040.cache.IcacheParallelViptSpec \
              m68k040.cache.PerBeatPredecodeEquivSpec \
              m68k040.cache.ChunkPredecodeSpec \
              m68k040.frontend.FetchAlignSpec \
              m68k040.frontend.FetchAlignRingTurnoverSpec \
              m68k040.frontend.FetchAlignResidentCadenceSpec \
              m68k040.frontend.AlignerSpec \
              m68k040.frontend.PredecodeRefSpec \
              m68k040.frontend.FtqCapacitySpec \
              m68k040.frontend.LineBEorPartitionSpec \
              m68k040.decode.FedSpecsPacketPairingSpec"
```

Referred to below as **`<GC4-SUITES>`**. Lock-step is run as:

```bash
sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec m68k040.lockstep.EndToEndLockStepSpec"
```

Referred to below as **`<LOCKSTEP>`**.

### GC-5 — Background synth jobs must be actively polled, never passively awaited

A confirmed operational bug this session: **a subagent that launches its own background Vivado job and passively waits on a notification callback does not reliably get woken** (confirmed 5+ times). Any task or reviewer instruction touching a background synth job — which in this plan means **Task 6 Step 8, Task 15, and Task 16** — must explicitly **poll** for completion:

```bash
# CORRECT: active polling
while kill -0 "$VIVADO_PID" 2>/dev/null; do sleep 60; done; echo "vivado exited"
# then check the log for the expected banner
```

Never `wait` on a notification, never assume a callback will arrive, never end a turn assuming the job will wake you.

### GC-6 — `Mem` idiom reference pattern

M1's `lineMem` and M2's `fillLo`/`fillHi` must follow this codebase's **established** `Mem` style, not an invented one. Reference sites, cited favourably in the NaxRiscv-comparison ledger entry:

- `src/main/scala/m68k040/frontend/Ftb.scala:51` — `val mem = Mem(FtbEntry(tagBits), entries)`; `:70` `mem.readAsync(qIdx)`; `:131` `mem.write(uIdx, uNew, enable = memWr)` — **single write call site**, enable-qualified.
- `src/main/scala/m68k040/frontend/Gshare.scala:82` — `val pht = Mem(UInt(2 bits), phtEntries) init Seq.fill(phtEntries)(U(2, 2 bits))`; `:150` `pht.write(upd.payload.index, uNew, enable = upd.valid)`.
- `src/main/scala/m68k040/cache/IcachePlugin.scala:307` — `Vec(dataMem.map(_.readSync(dataReadAddr, dataReadEn)))` — the existing synchronous-read idiom this plan widens.

**Hard rule inherited from `IcachePlugin.scala:765-767`:** each `Mem` gets **exactly one `.write(...)` call site**. A second call site breaks SpinalHDL's `MultiPortWritesSymplifier` — this is a real, previously-shipped breakage in this file, not a style preference.

### GC-7 — `KeepAttribute` on every new memory output

Spec §5.1 / §5.2 / §4.3 N-3: every synchronous memory read output introduced by this plan (`ufaBeat`, `fillLoQ`, `fillHiQ`) carries `KeepAttribute(...)`. This is load-bearing, not decoration: if Vivado absorbs the memory output register back into fabric, M1/M2 deliver nothing and `lineReg` has been re-created under a different name. Import is `spinal.core.KeepAttribute`.

### GC-8 — Non-goals: hard scope boundary

No change to `FetchAlignPlugin` (in particular `RING = 3` and `ibuf.BUF_WORDS = 20` — changing either re-opens the H7 hazard), `DecodeStage`, `RenameStage`, the `PipeStage` construct, `IssueQueuePlugin`, `RobPlugin`, `DcachePlugin`, or any execute-side plugin. **The Phase-2 predictor-complex redesign (spec §8 / D3) is OUT OF SCOPE for this plan entirely** — `Btb.scala`, `Ftb.scala`, `Gshare.scala`, `Ras.scala` are read-only reference material here (GC-6) and must not be modified by any task. No ISA/architectural behaviour change; the only permitted observable changes are the enumerated cycle counts in spec §5.6.

### GC-9 — Machine budget

≤ 2 concurrent heavy JVMs; **never** run a JVM test suite while Vivado is running. Check `free -g` before starting any test run with ≥3 agents live. Before and after Task 16, verify and record the count of competing `impl_FullCore.tcl` processes:

```bash
pgrep -af "impl_FullCore" | wc -l   # must be 0 for the gate to be trustworthy
free -g
```

### GC-10 — Re-derive, don't inherit

Any "current worst path" quoted during implementation must come from a live `report_timing` on the checkpoint actually being discussed. This error class has occurred at least three times in this campaign. No number in this plan or the spec may be re-quoted as if freshly measured.

### GC-11 — D1 RESOLUTION (the CONDITIONAL band)

**Spec §9.5 / §14 raise D1: if the gate returns neutral (−1.472 ≤ WNS < −1.322, i.e. 182.7–187.5 MHz) with IPC and area both improved, does this land on structural merits, or get discarded like the other eight?**

**PLAN DECISION — D1 is resolved as LAND**, subject to §10's numeric CONDITIONAL conditions being met in full (IPC ≥ 99 % of baseline **and** CLB LUTs ≤ 110,679 **and** CLB Registers ≤ baseline − 1,500 **and** all GC-3/GC-4 correctness gates green **and** BRAM ≤ 80/480 **and** WHS ≥ 0 **and** 0 Vivado errors).

Reasoning, recorded so it can be overridden on its merits rather than re-derived:

1. D2 (build-vs-don't, against a stated 20–30 % hit rate) was resolved by explicit user decision — "implement it now". The spec's own argument for building at 20–30 % is **§9.5's fallback value**: −1,920 CLB flops, one fewer memory array, one fewer FSM state, deletion of the demand/speculative asymmetry, convergence toward the NaxRiscv reference shape. That value is FMax-independent *by construction*.
2. Authorising the build on the strength of an explicitly FMax-independent justification, and then discarding the result on FMax grounds alone, would make the authorisation incoherent. The eight prior discards were all changes whose *only* claimed value was FMax; this one is not, and that difference is the whole reason it was authorised.
3. The REJECT band (WNS < −1.472) is unchanged and remains a real discard: a genuine regression still does not land.

**USER DECISION NEEDED (D1) — still visible, still overridable.** This resolution is the plan author's, not the user's. Task 16 Step 9 requires the D1 decision to be re-confirmed by the user (or their standing delegate) **before** the CONDITIONAL band is acted on. If the user overrides to "discard on neutral", Task 16's decision table changes and nothing else in the plan does.

### GC-12 — D4 RESOLUTION (delete the I-side prefetch engine?)

**Spec §14 Q5 / D4: should the I-side instruction-prefetch engine exist at all?** NaxRiscv's `FetchCachePlugin` has none; this project's is ~330 flops of five-slot MSHR state, a 96-flop frontier, `pfFilled`'s 256 telemetry flops, a dedicated FSM state, and the AR-arbitration priority logic that is the *reason* `heldDemandMiss` (⊃ `isHit`) reaches `arHoldAddr` at all — i.e. the reason M4 exists.

**A cheap pre-check of its real IPC value was dispatched in parallel with the writing of this plan. As of this plan's authoring, its result has NOT landed: `.superpowers/sdd/progress-ipc-push-2026-08-09.md` ends at §35 and contains no §36 entry.** D4 is therefore treated as **genuinely open**, and the following is the binding rule:

> **D4 is an OPEN USER DECISION. It MUST be resolved before Task 8 (M2b — the unified MSHR line file) begins, because Task 8 and Task 12 (M4) are the two tasks whose content changes materially depending on the answer.**
>
> **Resolution procedure:**
> 1. Before starting Task 8, re-read `.superpowers/sdd/progress-ipc-push-2026-08-09.md` for a §36 (or later) entry recording the `prefetchEnable = 0` IPC measurement. If one exists, use its number.
> 2. If no such entry exists, **Task 2 of this plan produces the number itself** — that is Task 2's entire purpose.
> 3. With the number in hand, escalate to the user as an explicit **USER DECISION NEEDED (D4)** callout, quoting the measured aggregate IPC delta for `IPC_PREFETCH=off` vs on.
> 4. **DEFAULT IF NO USER DECISION IS AVAILABLE: KEEP the prefetch engine.** Build Tasks 8, 9 and 12 exactly as specified in this plan. Rationale: deleting a measured IPC feature on an explicitly IPC-focused branch without explicit authorisation is precisely what the spec refuses to do pre-emptively ("Deliberately *not* folded in pre-emptively — deleting a measured IPC feature on an IPC-focused branch needs the measurement first"). Keeping it is the conservative, in-spec path; deleting it is the larger structural cut and needs a real decision.
>
> **If D4 resolves in favour of DELETION**, an **S2b** variant replaces part of Task 8 and shrinks Task 12 to near-nothing (spec §13). That variant is NOT written out in this plan — it is a materially different ~−400-line change, and writing it speculatively alongside the keep-path would produce two mutually-exclusive half-specified task sets. **If D4 resolves to DELETE, stop and re-plan Tasks 8/9/12 before implementing them.** This is an explicit, deliberate plan boundary, not an omission.

### GC-13 — SPEC GAPS carried into implementation

These are places where the design spec is genuinely underspecified. Each is flagged here and resolved inside the task that hits it, with the resolution marked **SPEC GAP** in that task's text so a reviewer sees it was not in the spec:

- **SG-1 (Task 11)** — Under M3 the hit/miss verdict is not known at accept time, but today's `answerable` term during a prefetch fill is `lookupFault || isHit || !pfLookupSetBusy`, i.e. it *uses* the verdict to decide acceptance. The spec's §5.3 does not say what replaces it. Resolved in Task 11 Step 3.
- **SG-2 (Task 11)** — `REPLAY` and `FAULT` arm the response with a known way (`victimWay`) rather than a computed `hitVec`. The spec's S1 sketch only shows the hit path. Resolved in Task 11 Step 5 via an explicit `s1WayOh` mux.
- **SG-3 (Task 9)** — Spec §5.2 says the D3-SET-I guard becomes `fillArrayWrActive && (lookupSet === mshrSet(installIdx))`, which puts a Vec-mux in front of a comparator that sits in the accept cone. Resolved in Task 9 Step 4 with a dedicated `installSet` register.
- **SG-4 (Task 10)** — `validsQ` is captured at S0 while `valids` can be cleared by `invalidateAll` between S0 and S1. Resolved in Task 10 Step 5 with a directed test proving the S0-capture window is *identical* to today's behaviour.

### GC-14 — Commit discipline

Frequent commits, one per task minimum. Commit messages end with:

```
Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
```

Never commit to a default branch; all work is on `feat/unified-fetch-array` in the isolation worktree (GC-2).

### GC-15 — Honesty about what this plan's code blocks are

`IcachePlugin.scala` is 1,068 lines and is effectively rewritten from `:140` to `:1010` by this plan (~1,750 lines of change across ~10 files). The code in each step below is **exact where it is exact** (new declarations, new helper functions, new test code, deletions by name and line range) and **structurally precise but requiring compile-verification where it is a large in-place restructuring**. Where a step says "mechanical", the implementer is expected to compile, read the SpinalHDL error, and fix syntax — not to invent semantics. Every semantic decision is written out. If an implementer finds a semantic question this plan does not answer, that is a **SPEC GAP** to be raised, not resolved silently.

---

## File Structure

| File | Status | Responsibility after this plan |
|---|---|---|
| `src/main/scala/m68k040/cache/IcachePlugin.scala` | **Modify** (effectively rewritten `:140`–`:1010`) | All of M1–M4. Stays one cohesive file — the spec does not call for a split and `IcachePlugin` is already the established large-cohesive-file pattern here. |
| `src/main/scala/m68k040/cache/IcacheTypes.scala` | **Read-only** | `ChunkPredecode` (8 bits), `CacheMode`, `CacheGeometry`. Unchanged — M1 changes *which memory* the bits live in, not the bits. |
| `src/main/scala/m68k040/cache/AxiIds.scala` | **Read-only** | `I_DEMAND` (0), `I_SPEC_BASE`, `I_SPEC_LAST`, `I_SPEC_SLOTS` (5), `ID_W`. M2's `MSHR_N = 1 + pfSlots = 6` derives from these. |
| `src/test/scala/m68k040/cache/IcacheArrayProbe.scala` | **Create** (Task 3) | Shared raw-array accessor helper for `IcacheSpec`/`IcachePrefetchSpec`, so M1/M2's array-shape changes are absorbed in ONE place instead of ~30 call sites. |
| `src/test/scala/m68k040/cache/IcacheOrderOracleSpec.scala` | **Create** (Task 4) | Spec §12.1 oracles 1–3: response-order, one-AR-per-line, MSHR-set-exclusivity. Built against the OLD RTL so it is a genuine regression detector for M2/M3. |
| `src/test/scala/m68k040/cache/IcacheUnifiedArraySpec.scala` | **Create** (Task 5) | Spec §12.1 oracle 4: unified-array predecode equivalence. |
| `src/test/scala/m68k040/cache/IcacheMutationProofSpec.scala` | **Create** (Task 13) | Spec §12.2's five mutation proofs, as documentation-of-coverage + the directed tests each one requires to exist. |
| `src/test/scala/m68k040/cache/IcacheSpec.scala` | **Modify** | Raw-array reads move to `IcacheArrayProbe` (Task 3); array-shape updates (Tasks 6, 8). |
| `src/test/scala/m68k040/cache/IcachePrefetchSpec.scala` | **Modify** | Same; plus MSHR-file state accessor updates (Task 9). |
| `src/test/scala/m68k040/cache/PerBeatPredecodeEquivSpec.scala` | **Modify** (Task 6) | The beat-granular window selector changes from `pc(5:3)` over a line to `pc(4:3)` over a beat. |
| `synth/floorplan_fetch.xdc` | **Create** (Task 15) | `pb_fetch` pblock over the post-M1/M2 fetch cluster, sized from a real post-synth probe of the NEW netlist, anchored on its BRAM columns. |
| `synth/impl_FullCore.tcl` | **Modify** (Task 15) | Add the `fetch` `FLOORPLAN_MODE` token + its stale-report cleanup entry. |
| `synth/probe_floorplan_fetch.tcl` | **Create** (Task 15) | Sizing probe for `pb_fetch` against the NEW netlist, reusing `probe_floorplan_filters.tcl`'s clean capture filter. |
| `.superpowers/sdd/progress-ipc-push-2026-08-09.md` | **Modify** (Tasks 2, 16) | §36 (D4 measurement), §37 (the single gate's result — the twelfth honest row either way). |

---

## Task Map

| # | Task | Move | Deliverable gate |
|---|---|---|---|
| 1 | Pin `predMem`'s real LUTRAM cost | S-pre(a) | A recorded number; Q2 input |
| 2 | IPC baseline + `IPC_PREFETCH=off` sweep | S-pre(b) | A recorded number; **D4 input** |
| 3 | Test-surface refactor: `IcacheArrayProbe` | S0 | `<GC4-SUITES>` green, zero RTL change |
| 4 | Ordering/AR/exclusivity oracles, against OLD RTL | S6 (pulled early) | New spec green on unmodified RTL |
| 5 | M1a: unified array written, `predMem` shadowed, equivalence oracle | M1 | Equivalence oracle green |
| 6 | M1b: reads switch over; delete `predMem`/`s1PredEntries`/`predAccumLo` | M1 | `<GC4-SUITES>` + `<LOCKSTEP>` green |
| 7 | M2a: narrow the INHIBITED/poison bypass; delete `missPred` | M2 | INHIBITED + poison replay tests green |
| 8 | M2b: unified MSHR line file; delete `lineReg`; merge `PF_PRED` | M2 | Oracles + `<GC4-SUITES>` green |
| 9 | M2c: 6-entry uniform MSHR control file | M2 | Oracles + `<GC4-SUITES>` green |
| 10 | M3a: S0 capture + shadow S1 verdict + in-RTL equivalence assert | M3 | Shadow assert holds across all suites |
| 11 | M3b: flip to the S1 verdict; delete the live accept-cone comparator | M3 | Oracles + `<GC4-SUITES>` + `<LOCKSTEP>` |
| 12 | M4: registered-state-only frontier + AR arbiter | M4 | `IcachePrefetchSpec` telemetry sweep |
| 13 | Mutation proofs (spec §12.2, all five) | S6 | Each mutation breaks a *named* test |
| 14 | Full correctness sweep + IPC gate | S6 | 396/396, 149/149, IPC ≥ 99 % both postures |
| 15 | M5: `pb_fetch` floorplan, sized from the NEW netlist | M5 | Probe-derived geometry, 55–65 % LUT-site band |
| 16 | **THE SINGLE GATE** + D1 decision + ledger | S7 | postrouteN3 × {decode} × {decode+fetch} |

**Estimated sessions: 5–6.** (Spec §13 says 3–5 for S0–S6 plus the gate; this plan adds the pulled-forward oracle task and splits M1/M2/M3 into shadow-then-flip pairs, which is more tasks but each is smaller and independently reviewable. Tasks 1–4 are one session; 5–7 one; 8–9 one; 10–12 one; 13–15 one; 16 one.)

---

### Task 1: Pin `predMem`'s real distributed-RAM cost (S-pre a)

Spec §3.2 estimates `predMem` at 1,024–2,048 LUTs from bit count and RAM64X1S capacity, and §11.4/§13 list pinning it exactly as a pre-implementation task. This number is the **input to spec §14 Q2** (fold predecode into `dataMem`, or keep a parallel synchronous `predMem`). It is measured on the **already-archived baseline routed DCP** — no new RTL, no place, no route (GC-1 exemption 1).

**Files:**
- Create: `/home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01/synth/probe_predmem_cost.tcl`
- Read: `/home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01/synth/archive/6b246de_default_postrouteN3_decode/`

**Interfaces:**
- Consumes: nothing.
- Produces: two recorded numbers used by Task 5's variant choice —
  - `PREDMEM_LUTRAM_LUTS` (integer): LUTs whose `REF_NAME` is `RAM*` under `*IcachePlugin_logic_predMem*`.
  - `ICACHE_TOTAL_LUTRAM_LUTS` (integer): the same for all of `IcachePlugin`.

- [ ] **Step 1: Confirm the archived routed DCP exists and no Vivado is running**
```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
ls -la synth/archive/6b246de_default_postrouteN3_decode/
pgrep -af "impl_FullCore|vivado" | wc -l   # expect 0
free -g
```
Expected: a `*.dcp` (routed checkpoint) present; competing-process count 0. If the routed DCP is absent, fall back to `synth/archive/*/fullcore_synth.dcp` (post-synthesis) and **record in the report that the number is post-synthesis, not post-route.**

- [ ] **Step 2: Write the probe script**
```tcl
# synth/probe_predmem_cost.tcl
# Read-only. Pins the REAL distributed-RAM LUT cost of IcachePlugin's predMem array,
# which spec section 3.2 could only estimate (1,024-2,048 LUTs) from bit count and
# RAM64X1S capacity. The answer is the input to spec section 14 Q2 (fold predecode
# into dataMem vs keep a parallel synchronous predMem).
#
# GC-1: information-only. No place, no route, no WNS read.
set dcp "synth/archive/6b246de_default_postrouteN3_decode/fullcore_route.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
open_checkpoint $dcp
puts "PREDMEM_PROBE_DCP $dcp"

proc count_by_ref {cells label} {
  array set tally {}
  foreach c $cells {
    set r [get_property REF_NAME $c]
    if {[info exists tally($r)]} { incr tally($r) } else { set tally($r) 1 }
  }
  set total 0
  foreach r [lsort [array names tally]] {
    puts [format "  %-14s %-24s %6d" $label $r $tally($r)]
    incr total $tally($r)
  }
  puts [format "  %-14s %-24s %6d" $label "TOTAL" $total]
  return $total
}

set predmem_cells [get_cells -hier -filter {NAME =~ *IcachePlugin_logic_predMem* && IS_PRIMITIVE}]
set n_pred [count_by_ref $predmem_cells "PREDMEM"]
puts "PREDMEM_CELLS_TOTAL $n_pred"

set predmem_ram [get_cells -hier -filter {NAME =~ *IcachePlugin_logic_predMem* && IS_PRIMITIVE && REF_NAME =~ RAM*}]
puts "PREDMEM_LUTRAM_LUTS [llength $predmem_ram]"

set ic_ram [get_cells -hier -filter {NAME =~ *IcachePlugin_logic* && IS_PRIMITIVE && REF_NAME =~ RAM* && NAME !~ *DecodeStage* && NAME !~ *RobPlugin* && NAME !~ *IssueQueuePlugin* && NAME !~ *RenameStage* && NAME !~ *DcachePlugin* && NAME !~ *LsEuPlugin*}]
puts "ICACHE_TOTAL_LUTRAM_LUTS [llength $ic_ram]"

set tagmem_ram [get_cells -hier -filter {NAME =~ *IcachePlugin_logic_tagMem* && IS_PRIMITIVE && REF_NAME =~ RAM*}]
puts "TAGMEM_LUTRAM_LUTS [llength $tagmem_ram]"

set s1pred_ff [get_cells -hier -filter {NAME =~ *IcachePlugin_logic_s1PredEntries* && IS_PRIMITIVE && REF_NAME =~ FD*}]
puts "S1PREDENTRIES_FF [llength $s1pred_ff]"
set linereg_ff [get_cells -hier -filter {NAME =~ *IcachePlugin_logic_lineReg* && IS_PRIMITIVE && REF_NAME =~ FD*}]
puts "LINEREG_FF [llength $linereg_ff]"
set misspred_ff [get_cells -hier -filter {NAME =~ *IcachePlugin_logic_missPred* && IS_PRIMITIVE && REF_NAME =~ FD*}]
puts "MISSPRED_FF [llength $misspred_ff]"

report_utilization -hierarchical -hierarchical_depth 4 -file synth/predmem_hier_util.rpt
puts "PREDMEM_PROBE_DONE"
```

- [ ] **Step 3: Run the probe (foreground, ~3–5 min)**
```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
vivado -mode batch -source synth/probe_predmem_cost.tcl 2>&1 | tee synth/predmem_cost.out
```
Expected output includes lines `PREDMEM_LUTRAM_LUTS <n>`, `ICACHE_TOTAL_LUTRAM_LUTS <n>`, `S1PREDENTRIES_FF 1024`, `LINEREG_FF 512`, `MISSPRED_FF 256`, and terminates with `PREDMEM_PROBE_DONE`.

If `S1PREDENTRIES_FF` does not read 1024 / `LINEREG_FF` 512 / `MISSPRED_FF` 256, **the DCP is not the baseline netlist** — stop and find the right one before proceeding.

- [ ] **Step 4: Record the numbers and pick the Q2 variant**
Append the measured numbers to the task's report and apply this decision rule (this is the plan's operationalisation of spec §14 Q2, whose text says "decide from the S1 post-synthesis utilisation check, not from this document"):

- `PREDMEM_LUTRAM_LUTS ≥ 1,000` → **build M1 in its FOLD form** (one 384-bit `lineMem`, spec §5.1). The deletion is large enough to justify changing the width of a proven BRAM mapping.
- `PREDMEM_LUTRAM_LUTS < 1,000` → **still build the FOLD form**, but note in the Task 5 report that the R1 fallback (a parallel `Mem(Bits(128), sets*2)` synchronous `predMem`) is the preferred retreat if Task 6 Step 8's inference check goes wrong.

Either way Task 5 builds the FOLD form; this number decides how readily Task 6 retreats to the fallback. **Both variants delete exactly the same 1,152 flops**, so the plan's flop accounting is unaffected by the choice.

- [ ] **Step 5: Commit**
```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
git add synth/probe_predmem_cost.tcl synth/predmem_cost.out synth/predmem_hier_util.rpt
git commit -m "synth: pin IcachePlugin predMem's real distributed-RAM LUT cost (spec S-pre a)

Read-only probe on the archived baseline routed DCP. Answers spec section 3.2's
1,024-2,048 LUT ESTIMATE with a measured number, and confirms the s1PredEntries /
lineReg / missPred flop populations (1,024 / 512 / 256) this restructure deletes.

Information-only per the plan's GC-1: no place, no route, no WNS read.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: IPC baseline + `IPC_PREFETCH=off` sweep (S-pre b) — the D4 evidence

Spec §14 Q5 calls this "the cheapest large question in this document to answer". `IcachePlugin.scala:110`'s `prefetchEnable` is already a sim-pokeable `RegInit(True)` and `IpcBenchSpec` already reads `IPC_PREFETCH=off` (`src/test/scala/m68k040/bench/IpcBenchSpec.scala:384-390`). **This task produces the number GC-12's D4 procedure needs, and simultaneously establishes the baseline the Task 14 IPC gate is measured against.**

**Files:**
- Modify: `.superpowers/sdd/progress-ipc-push-2026-08-09.md` (new §36)
- Read: `src/test/scala/m68k040/bench/IpcBenchSpec.scala`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `IPC_BASELINE_ON` — aggregate `retired/cycles` with prefetch on, `IPC_MEM=l2:5:70`, seeds 1/2/3. **This is the denominator of Task 14's "≥ 99.0 % of baseline" gate.**
  - `IPC_BASELINE_OFF` — same with `IPC_PREFETCH=off`.
  - `IPC_BASELINE_ON_ZERO` / `IPC_BASELINE_OFF_ZERO` — same pair under the default `IPC_MEM` (zero-latency) model.
  - The D4 delta: `(IPC_BASELINE_ON − IPC_BASELINE_OFF) / IPC_BASELINE_ON`.

- [ ] **Step 1: Create the implementation worktree (GC-2) and confirm it is clean**
```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
git status --porcelain -- src synth docs   # expect no modifications to tracked RTL
git worktree add -b feat/unified-fetch-array \
  /home/qwertyoruiop/m68k-core-040-ooo-worktrees/frontend-restructure HEAD
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/frontend-restructure
git log --oneline -2   # expect the plan commit on top of 6144e50
git status --porcelain # expect empty
```
Expected: a clean worktree at `/home/qwertyoruiop/m68k-core-040-ooo-worktrees/frontend-restructure` on branch `feat/unified-fetch-array`.

- [ ] **Step 2: Confirm `IPC_PREFETCH=off` really disables the engine (do not trust the env var)**
This codebase has a *recorded* trap for exactly this: `IcachePlugin.scala:98-104` documents that the first attempt at a prefetch control silently read as 0 in every full-core testbench, and "the first IPC sweep after adding it came back bit-identical to baseline." **Prove the poke lands before trusting any number.**
```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/frontend-restructure
sed -n '380,400p' src/test/scala/m68k040/bench/IpcBenchSpec.scala
```
Read the block and confirm it pokes `dut.<icache path>.logic.prefetchEnable` via `#=` under `sys.env.get("IPC_PREFETCH").contains("off")`. Then run one kernel both ways and require the cycle counts to **differ**:
```bash
IPC_SEED=1 IPC_MEM=l2:5:70 IPC_ONLY=load sbt "testOnly m68k040.bench.IpcBenchSpec" 2>&1 | tail -20
IPC_PREFETCH=off IPC_SEED=1 IPC_MEM=l2:5:70 IPC_ONLY=load sbt "testOnly m68k040.bench.IpcBenchSpec" 2>&1 | tail -20
```
Expected: the two runs report **different** cycle counts for the `load` kernel. If they are bit-identical, the poke is not landing — **stop, fix the harness, and record the fix**; every number after this depends on it.

- [ ] **Step 3: Run the full sweep, both memory models, three seeds each**
```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/frontend-restructure
for seed in 1 2 3; do
  for mem in "" "l2:5:70"; do
    for pf in on off; do
      echo "=== seed=$seed mem=${mem:-zero} prefetch=$pf ==="
      env IPC_SEED=$seed \
          ${mem:+IPC_MEM=$mem} \
          $( [ "$pf" = off ] && echo IPC_PREFETCH=off ) \
        sbt "testOnly m68k040.bench.IpcBenchSpec" 2>&1 | grep -E "^AGGREGATE|^[a-z_]+ +[0-9]"
    done
  done
done 2>&1 | tee /home/qwertyoruiop/tmp/ipc_d4_sweep.txt
```
Expected: 12 `AGGREGATE` lines (`IpcBenchSpec.scala:979` prints `AGGREGATE <retired> <cycles> <ipc>`).

**GC-9:** this is a long Verilator run. Do not run it concurrently with any other heavy JVM, and never during a Vivado job.

- [ ] **Step 4: Compute the D4 delta and write §36 of the ledger**
```bash
grep AGGREGATE /home/qwertyoruiop/tmp/ipc_d4_sweep.txt
```
Compute `(on − off) / on` per memory model, averaged over the three seeds. Then append to the ledger in the main repo checkout used for the campaign record:

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/frontend-restructure
cat >> .superpowers/sdd/progress-ipc-push-2026-08-09.md <<'LEDGER'

## §36 (2026-08-12) — D4 evidence: the I-side prefetch engine's real aggregate IPC contribution, measured

Spec `2026-08-12-large-scale-frontend-restructure-design.md` §14 Q5 / D4 asked
whether the I-side instruction-prefetch engine should exist at all — NaxRiscv's
`FetchCachePlugin` has none, and this project's costs ~330 flops of five-slot MSHR
state, a 96-flop frontier, `pfFilled`'s 256 telemetry flops, a dedicated FSM state,
and the AR-arbitration priority logic that is the *reason* `heldDemandMiss` (⊃
`isHit`) reaches `arHoldAddr` at all.

Measured with the standing IPC bench, `prefetchEnable` poked off for the whole run
(`IPC_PREFETCH=off`), seeds 1/2/3, both memory models. **The poke was independently
proven to land** (single-kernel A/B differs) before any aggregate number was taken —
`IcachePlugin.scala:98-104` records a prior instance of this exact control silently
reading as 0 in full-core testbenches.

| model | prefetch ON (agg cycles, s1/s2/s3) | prefetch OFF | delta |
|---|---|---|---|
| `IPC_MEM` zero | FILL | FILL | FILL |
| `IPC_MEM=l2:5:70` | FILL | FILL | FILL |

**D4 verdict input:** FILL — engine is worth FILL % aggregate IPC under the realistic
L2/DDR model. Per the implementation plan's GC-12, this number is escalated to the
user as USER DECISION NEEDED (D4) before Task 8; the default with no user decision
is KEEP.
LEDGER
```
Replace every `FILL` with the measured value. **Do not leave a `FILL` in the committed file.**

- [ ] **Step 5: Escalate D4 explicitly**
Surface, in the task report that reaches the user, a callout of exactly this shape:

> **USER DECISION NEEDED (D4).** The I-side instruction-prefetch engine is worth **X.X %** aggregate IPC under `IPC_MEM=l2:5:70` (**Y.Y %** under the zero-latency model). Deleting it removes ~700 flops, a whole FSM state, and the AR-arbitration priority logic that M4 exists to undo. Spec §14 Q5's own threshold is "worth less than ~1 % IPC ⇒ deleting it is a better-evidenced structural change than several of the moves in §5." **Decision needed before Task 8. Default with no decision: KEEP** (build Tasks 8/9/12 as planned). If the answer is DELETE, Tasks 8/9/12 must be re-planned first — this plan does not contain the S2b variant.

- [ ] **Step 6: Commit**
```bash
git add .superpowers/sdd/progress-ipc-push-2026-08-09.md
git commit -m "docs(ledger) §36: D4 evidence -- I-side prefetch engine's measured aggregate IPC value

Answers spec section 14 Q5 with the cheapest large measurement in the campaign:
the standing IPC bench with prefetchEnable poked off, seeds 1/2/3, both memory
models. Poke independently proven to land first (IcachePlugin.scala:98-104 records
a prior silent-zero instance of this exact control).

Also establishes IPC_BASELINE_ON, the denominator of the implementation plan's
Task 14 'IPC >= 99.0%% of baseline' gate.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: Test-surface refactor — `IcacheArrayProbe`, committed against the OLD RTL

Spec §11.4 risk R5: `IcacheSpec` (~970 lines) and `IcachePrefetchSpec` (1,028 lines) read `predMem(w).getBigInt(...)`, `dataMem`, `tagMem` and `valids` raw and assert byte-for-byte non-corruption of unrelated ways. These are **good** tests — they caught the real corruption bug at `IcachePlugin.scala:216-232` — and M1/M2 change every address they use. The mitigation the spec mandates: **rewrite the raw-array accessors as a shared helper in a separate, first commit, against the OLD RTL, so the test change and the RTL change are independently reviewable.**

**Files:**
- Create: `src/test/scala/m68k040/cache/IcacheArrayProbe.scala`
- Modify: `src/test/scala/m68k040/cache/IcacheSpec.scala` (raw-array reads at `:810-813`, `:826-829`, `:934-937`, `:959-962`, and `:327`/`:338` valid reads)
- Modify: `src/test/scala/m68k040/cache/IcachePrefetchSpec.scala` (any raw array reads it carries)

**Interfaces:**
- Consumes: `IcachePlugin.logic.{tagMem, predMem, dataMem, valids}` — the existing `simPublic()` handles at `IcachePlugin.scala:170-171`.
- Produces, for every later task:
  - `IcacheArrayProbe.wayData(ic: IcachePlugin, way: Int, set: Int, beat: Int): BigInt` — the 256-bit instruction-byte content of one beat of one way/set.
  - `IcacheArrayProbe.wayPred(ic: IcachePlugin, way: Int, set: Int, beat: Int): BigInt` — the `PRED_BITS_PER_BEAT`-bit predecode content of that same beat. **Beat-granular from day one**, even though the OLD RTL stores predecode line-granularly — the helper does the slicing, so Task 6's RTL change is invisible to callers.
  - `IcacheArrayProbe.wayTag(ic: IcachePlugin, way: Int, set: Int): BigInt`
  - `IcacheArrayProbe.wayValid(ic: IcachePlugin, way: Int, set: Int): Boolean`
  - `IcacheArrayProbe.snapshotWay(ic: IcachePlugin, way: Int, set: Int): WaySnapshot` — a case class bundling all four, for the "unrelated way must be byte-for-byte unchanged" assertions. `WaySnapshot` has fields `data: Seq[BigInt]` (2 beats), `pred: Seq[BigInt]` (2 beats), `tag: BigInt`, `valid: Boolean`, and a `describe: String`.

- [ ] **Step 1: Write the failing test — a self-test of the helper itself**
Create `src/test/scala/m68k040/cache/IcacheArrayProbe.scala` containing **only** the `WaySnapshot` case class and object stub with `???` bodies, plus append this self-test to `IcacheSpec.scala` (it will fail to compile / fail at run, which is the point):

```scala
// ---- appended to src/test/scala/m68k040/cache/IcacheSpec.scala ----
  // Task 3 (plan R5 mitigation): pins IcacheArrayProbe against the OLD RTL, so the
  // helper is proven equivalent to the raw reads it replaces BEFORE M1/M2 change the
  // array shapes underneath it. If this test and the raw reads ever disagree, the
  // helper is wrong and every corruption assertion built on it is worthless.
  test("IcacheArrayProbe agrees with raw array reads on a freshly filled line", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut()).doSim("array-probe-selftest") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x1000)
      dut.clockDomain.waitSampling(5)

      // Fetch address 0x1000 -> set 0, both beats installed by the refill.
      fetchAndWait(dut, 0x1000L)
      dut.clockDomain.waitSampling(5)

      val way = (0 until 4).find(w => dut.icache.logic.valids(w)(0).toBoolean)
        .getOrElse(fail("no way became valid after a demand fill of set 0"))

      // Raw reads, exactly as the pre-Task-3 tests did them.
      val rawData0 = dut.icache.logic.dataMem(way).getBigInt(0)
      val rawData1 = dut.icache.logic.dataMem(way).getBigInt(1)
      val rawTag   = dut.icache.logic.tagMem(way).getBigInt(0)
      val rawPred  = dut.icache.logic.predMem(way).getBigInt(0)
      val predBits = dut.icache.logic.PRED_BITS_PER_BEAT

      assert(IcacheArrayProbe.wayData(dut.icache, way, 0, 0) == rawData0,
        s"wayData(beat 0) disagrees with dataMem($way).getBigInt(0)")
      assert(IcacheArrayProbe.wayData(dut.icache, way, 0, 1) == rawData1,
        s"wayData(beat 1) disagrees with dataMem($way).getBigInt(1)")
      assert(IcacheArrayProbe.wayTag(dut.icache, way, 0) == rawTag,
        s"wayTag disagrees with tagMem($way).getBigInt(0)")
      assert(IcacheArrayProbe.wayValid(dut.icache, way, 0),
        "wayValid disagrees with valids")

      val mask = (BigInt(1) << predBits) - 1
      assert(IcacheArrayProbe.wayPred(dut.icache, way, 0, 0) == (rawPred & mask),
        "wayPred(beat 0) is not the low half of the line-granular predMem entry")
      assert(IcacheArrayProbe.wayPred(dut.icache, way, 0, 1) == ((rawPred >> predBits) & mask),
        "wayPred(beat 1) is not the high half of the line-granular predMem entry")

      val snap = IcacheArrayProbe.snapshotWay(dut.icache, way, 0)
      assert(snap.data == Seq(rawData0, rawData1), "snapshotWay.data disagrees")
      assert(snap.tag == rawTag, "snapshotWay.tag disagrees")
      assert(snap.valid, "snapshotWay.valid disagrees")
    }
  }
```

> **Note for the implementer:** `fetchAndWait` is the existing `IcacheSpec` helper for driving one fetch through `FetchProbePlugin`'s `cmdIn`/`rspOut` and waiting for the response. Read `IcacheSpec.scala`'s existing sim helpers (the block starting at `// ---- sim helpers`, ~line 40) and use whatever it is actually called there. If no such single-fetch helper exists, lift the 6–10 lines that every existing test in that file uses into one, and note the lift in the commit message.

- [ ] **Step 2: Run it and confirm it fails**
```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/frontend-restructure
sbt "testOnly m68k040.cache.IcacheSpec -- -z array-probe" 2>&1 | tail -30
```
Expected: FAIL — either a compile error (`IcacheArrayProbe` members are `???`) or `scala.NotImplementedError: an implementation is missing`.

- [ ] **Step 3: Implement the helper**
```scala
package m68k040.cache

import spinal.core.sim._

/** Raw cache-array read helpers, shared by IcacheSpec / IcachePrefetchSpec.
  *
  * WHY THIS EXISTS (implementation plan Task 3, spec risk R5). The corruption tests
  * in those two files read `dataMem`/`predMem`/`tagMem`/`valids` raw and assert an
  * UNRELATED way's content is byte-for-byte unchanged. They are the tests that caught
  * the real `victim`-pointer corruption bug (IcachePlugin.scala:216-232), so they must
  * survive the Unified Fetch Array restructure intact -- but that restructure changes
  * every array address they use. Routing all raw reads through here means the RTL
  * change touches ONE file's worth of accessors instead of ~30 call sites, and the
  * test change lands in a SEPARATE, independently reviewable commit against the OLD
  * RTL (which is what this file is).
  *
  * DELIBERATELY BEAT-GRANULAR. `wayPred` takes a `beat` even though today's `predMem`
  * is line-granular; the helper does the slicing. After M1 folds predecode into the
  * data array the address really is beat-granular and only this file changes.
  */
object IcacheArrayProbe {

  /** All four raw arrays' content for one (way, set), for before/after comparison. */
  case class WaySnapshot(data: Seq[BigInt], pred: Seq[BigInt], tag: BigInt, valid: Boolean) {
    def describe: String =
      s"data=[${data.map(_.toString(16)).mkString(",")}] " +
      s"pred=[${pred.map(_.toString(16)).mkString(",")}] " +
      s"tag=0x${tag.toString(16)} valid=$valid"
  }

  /** 256-bit instruction-byte content of one beat of one (way, set). */
  def wayData(ic: IcachePlugin, way: Int, set: Int, beat: Int): BigInt = {
    require(beat == 0 || beat == 1, s"beat must be 0 or 1, got $beat")
    ic.logic.dataMem(way).getBigInt(set * 2 + beat)
  }

  /** PRED_BITS_PER_BEAT-bit predecode content of one beat of one (way, set). */
  def wayPred(ic: IcachePlugin, way: Int, set: Int, beat: Int): BigInt = {
    require(beat == 0 || beat == 1, s"beat must be 0 or 1, got $beat")
    val bits = ic.logic.PRED_BITS_PER_BEAT
    val line = ic.logic.predMem(way).getBigInt(set)
    (line >> (bits * beat)) & ((BigInt(1) << bits) - 1)
  }

  def wayTag(ic: IcachePlugin, way: Int, set: Int): BigInt =
    ic.logic.tagMem(way).getBigInt(set)

  def wayValid(ic: IcachePlugin, way: Int, set: Int): Boolean =
    ic.logic.valids(way)(set).toBoolean

  def snapshotWay(ic: IcachePlugin, way: Int, set: Int): WaySnapshot =
    WaySnapshot(
      data  = Seq(0, 1).map(b => wayData(ic, way, set, b)),
      pred  = Seq(0, 1).map(b => wayPred(ic, way, set, b)),
      tag   = wayTag(ic, way, set),
      valid = wayValid(ic, way, set))

  /** Assert an unrelated way's content is byte-for-byte unchanged. The message names
    * the exact field that moved, because "something changed" is useless in a
    * corruption test. */
  def assertUnchanged(before: WaySnapshot, after: WaySnapshot, what: String): Unit = {
    assert(before.data == after.data,
      s"$what: dataMem CORRUPTED -- before ${before.describe} after ${after.describe}")
    assert(before.pred == after.pred,
      s"$what: predecode CORRUPTED -- before ${before.describe} after ${after.describe}")
    assert(before.tag == after.tag,
      s"$what: tag changed -- before 0x${before.tag.toString(16)} after 0x${after.tag.toString(16)}")
    assert(before.valid == after.valid,
      s"$what: valid changed -- before ${before.valid} after ${after.valid}")
  }
}
```

- [ ] **Step 4: Run the self-test to verify it passes**
```bash
sbt "testOnly m68k040.cache.IcacheSpec -- -z array-probe" 2>&1 | tail -20
```
Expected: PASS.

- [ ] **Step 5: Migrate every raw read in `IcacheSpec.scala` to the helper**
Replace the four snapshot blocks (`IcacheSpec.scala:810-813`, `:826-829`, `:934-937`, `:959-962`) with:
```scala
      val way0Before = IcacheArrayProbe.snapshotWay(dut.icache, 0, 0)
      // ... the stimulus that must not corrupt way 0 ...
      val way0After  = IcacheArrayProbe.snapshotWay(dut.icache, 0, 0)
      IcacheArrayProbe.assertUnchanged(way0Before, way0After,
        "way 0 after an unrelated INHIBITED miss")
```
(second site: `"way 0 after a mid-burst (beat-0-OK, beat-1-error) refill"`).

Replace the `valids` reads at `:327` and `:338` with `IcacheArrayProbe.wayValid(dut.icache, way, set)` / `(0 until 4).exists(w => IcacheArrayProbe.wayValid(dut.icache, w, set))`.

Then do the same for any raw `dataMem`/`predMem`/`tagMem`/`valids` reads in `IcachePrefetchSpec.scala`:
```bash
grep -n "dataMem\|predMem\|tagMem\|\.valids" src/test/scala/m68k040/cache/IcachePrefetchSpec.scala
```
Every hit must become an `IcacheArrayProbe` call. **`grep` must return zero raw-array hits in both files when this step is done** (other than inside `IcacheArrayProbe.scala` itself).

- [ ] **Step 6: Verify zero RTL change and full green**
```bash
git diff --stat -- src/main/    # expect EMPTY -- this task changes tests only
grep -rn "dataMem\|predMem\|tagMem" src/test/scala/m68k040/cache/IcacheSpec.scala \
                                    src/test/scala/m68k040/cache/IcachePrefetchSpec.scala
# expect: no matches
make test-fast
sbt "testOnly m68k040.cache.IcacheSpec m68k040.cache.IcachePrefetchSpec"
```
Expected: `git diff --stat -- src/main/` empty; grep no matches; `make test-fast` 149/149; both suites green.

- [ ] **Step 7: Commit**
```bash
git add src/test/scala/m68k040/cache/IcacheArrayProbe.scala \
        src/test/scala/m68k040/cache/IcacheSpec.scala \
        src/test/scala/m68k040/cache/IcachePrefetchSpec.scala
git commit -m "test(icache): shared IcacheArrayProbe raw-array accessors, against the OLD RTL

Spec risk R5's mandated mitigation: IcacheSpec and IcachePrefetchSpec read
dataMem/predMem/tagMem/valids raw and assert byte-for-byte non-corruption of
unrelated ways -- the tests that caught the real victim-pointer corruption bug.
The Unified Fetch Array restructure changes every address they use, so the
accessors are hoisted into one helper FIRST, in a separate commit, against
unmodified RTL, so the test change and the RTL change are independently
reviewable.

wayPred is deliberately beat-granular already (it slices today's line-granular
predMem entry), so M1's array-shape change is invisible to every caller.

A self-test pins the helper against the raw reads it replaces. Zero RTL change:
git diff --stat -- src/main/ is empty.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: The ordering / one-AR / MSHR-exclusivity oracles, built against the OLD RTL

Spec §12.1 lists four in-RTL oracles and §13 puts them in slice S6 — **after** M1–M4. **PLAN DECISION (deviation from spec §13, deliberate):** oracles 1–3 are built **now**, against the unmodified RTL, and proven green on it.

Reasoning: spec risk R3 says M3's failure mode is *silent* — mis-paired instruction bytes, not a crash — and calls it "the most expensive failure mode in the file". An oracle written after M3 lands cannot tell you whether M3 broke the invariant or whether the invariant was never what you thought. An oracle proven green on the pre-M3 RTL and then run at every subsequent task boundary is a genuine regression detector. Oracle 4 (unified-array equivalence) necessarily waits for Task 5, because the array it checks does not exist yet.

**Files:**
- Create: `src/test/scala/m68k040/cache/IcacheOrderOracleSpec.scala`
- Read-only: `src/main/scala/m68k040/cache/IcachePlugin.scala`, `src/test/scala/m68k040/cache/FetchProbePlugin.scala`

**Interfaces:**
- Consumes: `IcacheArrayProbe` (Task 3); `IcachePlugin.logic.{cmdPort, rspPort, axi, pfValid, pfSet, prefetchEnable}`; `IcacheSim.attachMemory`.
- Produces, for Tasks 8/9/11/12/13:
  - `IcacheOrderOracleSpec` — a `VerilatorTest`-tagged suite with three tests, named exactly:
    - `"oracle 1: every response is the next accepted command, in order"`
    - `"oracle 2: N consecutive fetches to one line produce exactly one AR"`
    - `"oracle 3: no two live MSHR entries own the same set"`
  - `IcacheOrderOracle.runOrderedStream(dut, addrs: Seq[Long]): Unit` — a reusable driver that issues a back-to-back command stream and checks the ordering invariant, used again by Task 13's mutation proofs.

- [ ] **Step 1: Write the oracle spec (all three tests, failing-first by construction — the file does not exist)**
```scala
package m68k040.cache

import m68k040.{M68kParams, VerilatorTest}
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.services.TranslationService
import m68k040.core.ParamPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer

/** Spec section 12.1 oracles 1-3, built against the PRE-restructure RTL on purpose.
  *
  * WHY THESE EXIST AND WHY THEY EXIST NOW. Spec risk R3: M3 restructures the
  * accept/verdict boundary that guarantees the in-order response contract, and a
  * violation of that contract is SILENT -- FetchRsp carries no tag and
  * FetchAlignPlugin attributes every response to its outstanding ring's HEAD
  * (FetchAlignPlugin.scala:263,283), so a reordering shows up as mis-paired
  * instruction bytes, not as a crash. An oracle written AFTER M3 cannot distinguish
  * "M3 broke it" from "it was never true". Proven green on unmodified RTL first, then
  * run at every subsequent task boundary, it is a real regression detector.
  *
  * These are simulation-only. Nothing here adds a synthesised register.
  */
object IcacheOrderOracle {

  /** Issue `addrs` back-to-back on cmdPort and check that responses come back in
    * exactly that order, one per command, none dropped, none duplicated.
    *
    * The stamp is maintained in the TESTBENCH, not in RTL: the invariant is
    * "the k-th response corresponds to the k-th accepted command", and the
    * testbench knows the accept order because it is the one driving cmdPort. */
  def runOrderedStream(
      cmdValid: Bool, cmdReady: Bool, cmdPc: UInt,
      rspValid: Bool, rspPc: UInt,
      cd: ClockDomain,
      addrs: Seq[Long],
      timeoutCycles: Int = 20000): Unit = {

    val accepted = ArrayBuffer[Long]()
    val observed = ArrayBuffer[Long]()
    var issueIdx = 0
    var cycles   = 0

    cmdValid #= false
    cd.waitSampling()

    val driver = fork {
      while (issueIdx < addrs.length) {
        cmdPc    #= addrs(issueIdx)
        cmdValid #= true
        cd.waitSampling()
        if (cmdReady.toBoolean) {
          accepted += addrs(issueIdx)
          issueIdx += 1
        }
      }
      cmdValid #= false
    }

    val monitor = fork {
      while (observed.length < addrs.length && cycles < timeoutCycles) {
        cd.waitSampling()
        cycles += 1
        if (rspValid.toBoolean) {
          val pc = rspPc.toLong
          val k  = observed.length
          assert(k < accepted.length,
            s"ORACLE 1 VIOLATED: response #$k (pc=0x${pc.toHexString}) arrived before " +
            s"any ${k + 1}-th command had been accepted -- a response was manufactured")
          assert(pc == accepted(k),
            s"ORACLE 1 VIOLATED: response #$k has pc=0x${pc.toHexString} but the #$k " +
            s"ACCEPTED command was pc=0x${accepted(k).toHexString}. Responses must leave " +
            s"the cache in accept order -- FetchRsp carries no tag and FetchAlignPlugin " +
            s"attributes by ring head, so this is silent instruction-byte mis-pairing.")
          observed += pc
        }
      }
    }

    driver.join()
    monitor.join()
    assert(observed.length == addrs.length,
      s"ORACLE 1 VIOLATED: ${addrs.length} commands accepted but only ${observed.length} " +
      s"responses observed within $timeoutCycles cycles -- a dropped response wedges " +
      s"FetchAlignPlugin's ring permanently (ringCount never decrements).")
  }
}

class IcacheOrderOracleSpec extends AnyFunSuite {

  class Dut(xlateFactory: => FiberPlugin with TranslationService = new IdentityTranslationPlugin)
      extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = xlateFactory
    val icache = new IcachePlugin
    val probe  = new FetchProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, icache, probe)) }
  }

  private def compiled = SimConfig.withVerilator.compile(new Dut())

  // ---- Oracle 1 ------------------------------------------------------------
  test("oracle 1: every response is the next accepted command, in order", VerilatorTest) {
    compiled.doSim("oracle1-order") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x4000)
      dut.clockDomain.waitSampling(5)

      // A deliberately hostile mix: same-line hits, sequential-line misses, a
      // backwards jump that re-hits, and a same-SET conflict that forces eviction.
      // 0x1000 and 0x2000 differ in tag but share set 0 (set = pc(11:6), 64 sets,
      // 64B lines -> 0x1000 and 0x2000 are both set 0).
      val addrs = Seq(
        0x1000L, 0x1008L, 0x1010L,          // one miss then two hits in the same line
        0x1040L, 0x1080L, 0x10c0L,          // sequential misses (also exercises prefetch)
        0x2000L,                            // same-set conflict, forces an eviction
        0x1000L,                            // may or may not still be resident
        0x1008L, 0x2000L, 0x2008L, 0x1040L) // thrash

      IcacheOrderOracle.runOrderedStream(
        cmdValid = dut.probe.cmdIn.valid, cmdReady = dut.probe.cmdIn.ready,
        cmdPc    = dut.probe.cmdIn.payload.pc,
        rspValid = dut.probe.rspOut.valid, rspPc = dut.probe.rspOut.payload.pc,
        cd = dut.clockDomain, addrs = addrs)
    }
  }

  // ---- Oracle 2 ------------------------------------------------------------
  test("oracle 2: N consecutive fetches to one line produce exactly one AR", VerilatorTest) {
    compiled.doSim("oracle2-one-ar") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x4000)
      // Prefetch off: this oracle is about the DEMAND path's duplicate-miss
      // suppression (IcachePlugin.scala:625-632), which M3 re-implements via
      // s1Unresolved. Speculative ARs are a separate, legitimate source of ARs and
      // would make the count untestable.
      dut.icache.logic.prefetchEnable #= false
      dut.clockDomain.waitSampling(5)

      val demandArs = ArrayBuffer[Long]()
      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.icache.logic.axi.ar.valid.toBoolean && dut.icache.logic.axi.ar.ready.toBoolean) {
            demandArs += dut.icache.logic.axi.ar.payload.addr.toLong
          }
        }
      }

      // Eight distinct offsets inside ONE 64-byte line.
      val addrs = (0 until 8).map(i => 0x1000L + i * 8)
      IcacheOrderOracle.runOrderedStream(
        cmdValid = dut.probe.cmdIn.valid, cmdReady = dut.probe.cmdIn.ready,
        cmdPc    = dut.probe.cmdIn.payload.pc,
        rspValid = dut.probe.rspOut.valid, rspPc = dut.probe.rspOut.payload.pc,
        cd = dut.clockDomain, addrs = addrs)
      dut.clockDomain.waitSampling(20)

      val toLine = demandArs.filter(a => (a & ~63L) == 0x1000L)
      assert(toLine.length == 1,
        s"ORACLE 2 VIOLATED: ${addrs.length} fetches into line 0x1000 produced " +
        s"${toLine.length} ARs (${toLine.map(a => f"0x$a%x").mkString(",")}), expected " +
        s"exactly 1. Duplicate-miss suppression is backpressure-based " +
        s"(IcachePlugin.scala:625-632); under M3 it is s1Unresolved's job.")
    }
  }

  // ---- Oracle 3 ------------------------------------------------------------
  test("oracle 3: no two live MSHR entries own the same set", VerilatorTest) {
    compiled.doSim("oracle3-mshr-exclusivity") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x8000)
      dut.clockDomain.waitSampling(5)

      var violations = 0
      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          // Speculative slots only, pre-M2: the demand context is not yet an indexed
          // MSHR entry. Task 9 EXTENDS this check to entry 0 (see its Step 6).
          val live = (0 until AxiIds.I_SPEC_SLOTS).filter(i => dut.icache.logic.pfValid(i).toBoolean)
          val sets = live.map(i => dut.icache.logic.pfSet(i).toInt)
          if (sets.distinct.length != sets.length) {
            violations += 1
            simFailure(
              s"ORACLE 3 VIOLATED: live speculative MSHR entries $live own sets $sets -- " +
              s"two fill owners for one set. IcachePlugin.scala:840-865 enforces one " +
              s"fill owner per set; M2 makes demand and speculative share one file, " +
              s"which is exactly when this can silently break.")
          }
        }
      }

      // Long sequential run: keeps the 5-slot prefetch window saturated and cycles
      // the same sets repeatedly through allocate/install/free.
      val addrs = (0 until 128).map(i => 0x1000L + i * 64)
      IcacheOrderOracle.runOrderedStream(
        cmdValid = dut.probe.cmdIn.valid, cmdReady = dut.probe.cmdIn.ready,
        cmdPc    = dut.probe.cmdIn.payload.pc,
        rspValid = dut.probe.rspOut.valid, rspPc = dut.probe.rspOut.payload.pc,
        cd = dut.clockDomain, addrs = addrs, timeoutCycles = 60000)
      dut.clockDomain.waitSampling(200)
      assert(violations == 0, s"$violations MSHR set-exclusivity violations")
    }
  }
}
```

> **Implementer note on port names:** `dut.probe.cmdIn` / `dut.probe.rspOut` are `FetchProbePlugin`'s top-level IO (`IcacheSpec.scala:31` calls them "cmdIn/rspOut top-level IO"). Read `src/test/scala/m68k040/cache/FetchProbePlugin.scala` and use its actual field names and directions. `AxiIds.I_SPEC_SLOTS` is `5`.

- [ ] **Step 2: Run and confirm all three tests pass on UNMODIFIED RTL**
```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/frontend-restructure
git diff --stat -- src/main/    # expect EMPTY
sbt "testOnly m68k040.cache.IcacheOrderOracleSpec" 2>&1 | tail -30
```
Expected: **3 tests PASS** against unmodified RTL.

**This is the critical acceptance condition of this task.** If any oracle fails here, one of two things is true and both must be resolved before continuing:
- the oracle is wrong (most likely: a stimulus that is not actually legal at the `FetchService` boundary, or a `FetchProbePlugin` port misread) — fix the oracle; or
- the invariant genuinely does not hold on the current RTL — **stop and report it**, because the entire safety argument for M3 rests on it holding today.

- [ ] **Step 3: Prove the oracles can actually fail (an oracle that cannot fail is decoration)**
Temporarily break oracle 1 by feeding `runOrderedStream` a deliberately wrong expectation, and confirm it reports:
```bash
# Temporarily change, in oracle 1's test only:
#   cmdPc = dut.probe.cmdIn.payload.pc  ->  rspPc = dut.probe.rspOut.payload.pc replaced with
#   a constant, e.g. pass `rspPc = dut.probe.cmdIn.payload.pc`
sbt "testOnly m68k040.cache.IcacheOrderOracleSpec -- -z 'oracle 1'" 2>&1 | tail -20
```
Expected: FAIL with `ORACLE 1 VIOLATED: response #... has pc=...`. **Revert the temporary change immediately** and re-run to confirm PASS.

Do the same for oracle 2 by asserting `toLine.length == 0` temporarily; expect `ORACLE 2 VIOLATED: 8 fetches into line 0x1000 produced 1 ARs`. Revert.

- [ ] **Step 4: Verify full green and zero RTL change**
```bash
git diff --stat -- src/main/    # expect EMPTY
make test-fast
sbt "testOnly m68k040.cache.IcacheOrderOracleSpec m68k040.cache.IcacheSpec m68k040.cache.IcachePrefetchSpec"
```
Expected: no `src/main/` diff; 149/149; all three suites green.

- [ ] **Step 5: Commit**
```bash
git add src/test/scala/m68k040/cache/IcacheOrderOracleSpec.scala
git commit -m "test(icache): spec section 12.1 oracles 1-3, proven green on the OLD RTL

DELIBERATE DEVIATION from spec section 13, which schedules the oracles in slice S6
(after M1-M4). Risk R3 says M3's failure mode is SILENT -- mis-paired instruction
bytes, because FetchRsp carries no tag and FetchAlignPlugin attributes by ring head.
An oracle written after M3 cannot distinguish 'M3 broke it' from 'it was never true'.
Proven green on unmodified RTL first, then run at every task boundary, it is a real
regression detector.

  oracle 1  every response is the next accepted command, in order
  oracle 2  N consecutive fetches to one line produce exactly one AR
  oracle 3  no two live MSHR entries own the same set (speculative slots; Task 9
            extends it to demand entry 0 once that becomes an indexed MSHR entry)

Each oracle was also proven CAPABLE OF FAILING via a temporary mutation, then
reverted. Zero RTL change.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 5: M1a — the Unified Fetch Array is written, `predMem` retained as a shadow, equivalence proven

**PLAN DECISION (staging, not in the spec):** M1 lands in two commits — write side first with `predMem` kept alive as a shadow and an in-RTL equivalence oracle proving the two agree bit-for-bit, then read side (Task 6) switches over and deletes the shadow. Reasoning: the equivalence oracle spec §12.1 item 4 asks for is *strictly stronger* if it can compare the new array against the array it replaces, on the real fill path, over every test in the suite — rather than against a testbench re-implementation of `PredecodeWord.classify`. The transient area cost is irrelevant because GC-1 forbids any intermediate synthesis gate.

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala` (`:155` `dataMem` declaration; `:296-307` read port; `:967-1053` predecode dwell)
- Create: `src/test/scala/m68k040/cache/IcacheUnifiedArraySpec.scala`

**Interfaces:**
- Consumes: `PRED_BITS_PER_BEAT` (128, `IcachePlugin.scala:152`), `classifyBeat` (`:397`), `IcacheArrayProbe` (Task 3).
- Produces, for Task 6:
  - `UFA_W: Int = 256 + PRED_BITS_PER_BEAT` (384) — the unified entry width.
  - `lineMem: Seq[Mem[Bits]]` — `Seq.fill(ways)(Mem(Bits(UFA_W bits), sets * beatsPerLine))`, replacing `dataMem`. **The name `dataMem` is retired in Task 5**; every reference becomes `lineMem`.
  - `ufaBeat: Vec[Bits]` — `Vec(lineMem.map(_.readSync(ufaReadAddr, ufaReadEn)))`, `KeepAttribute`-marked.
  - `def ufaData(w: Int): Bits = ufaBeat(w)(255 downto 0)`
  - `def ufaPred(w: Int): Bits = ufaBeat(w)(UFA_W - 1 downto 256)`
  - `dbgUfaPredMatch: Bool` (`simPublic`) — the shadow-equivalence signal Task 5's oracle reads; **deleted in Task 6.**

- [ ] **Step 1: Write the failing test — the unified-array equivalence oracle (spec §12.1 oracle 4)**
Create `src/test/scala/m68k040/cache/IcacheUnifiedArraySpec.scala`:
```scala
package m68k040.cache

import m68k040.{M68kParams, VerilatorTest}
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.services.TranslationService
import m68k040.core.ParamPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 12.1 oracle 4: the Unified Fetch Array's inline predecode field is
  * bit-identical to what the (still-present, shadow) predMem array holds for the same
  * (way, set, beat).
  *
  * This is the STRONG form of the oracle: rather than re-implementing
  * PredecodeWord.classify in the testbench and comparing against that, it compares the
  * new array against the array it replaces, on the real fill path. Any disagreement is
  * an M1 bug by construction, because predMem's content is the pre-restructure
  * behaviour the whole suite already pins.
  *
  * Task 6 deletes predMem and the dbgUfaPredMatch signal; the test then reduces to the
  * beat-slicing check in its second half, which is why that half is written against
  * IcacheArrayProbe (which survives) rather than against predMem directly.
  */
class IcacheUnifiedArraySpec extends AnyFunSuite {

  class Dut(xlateFactory: => FiberPlugin with TranslationService = new IdentityTranslationPlugin)
      extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = xlateFactory
    val icache = new IcachePlugin
    val probe  = new FetchProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, icache, probe)) }
  }

  private def compiled = SimConfig.withVerilator.compile(new Dut())

  test("oracle 4: the unified array's inline predecode matches predMem for every filled beat",
       VerilatorTest) {
    compiled.doSim("oracle4-ufa-equiv") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x8000)
      dut.clockDomain.waitSampling(5)

      // Continuously monitor the in-RTL shadow-equivalence signal. It is asserted
      // combinationally on every array-commit cycle and must never go low.
      var mismatches = 0
      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.icache.logic.predActive.toBoolean && !dut.icache.logic.dbgUfaPredMatch.toBoolean) {
            mismatches += 1
            simFailure("ORACLE 4 VIOLATED (in-RTL): the unified array's inline predecode " +
                       "field disagrees with the shadow predMem write on a commit cycle")
          }
        }
      }

      // Fill 64 distinct lines across all 64 sets, cycling every way.
      val lines = (0 until 256).map(i => 0x1000L + i * 64)
      IcacheOrderOracle.runOrderedStream(
        cmdValid = dut.probe.cmdIn.valid, cmdReady = dut.probe.cmdIn.ready,
        cmdPc    = dut.probe.cmdIn.payload.pc,
        rspValid = dut.probe.rspOut.valid, rspPc = dut.probe.rspOut.payload.pc,
        cd = dut.clockDomain, addrs = lines, timeoutCycles = 120000)
      dut.clockDomain.waitSampling(200)
      assert(mismatches == 0, s"$mismatches in-RTL unified-array predecode mismatches")

      // Post-hoc array sweep: for every VALID (way, set), the unified array's inline
      // predecode field must equal the shadow predMem's corresponding beat slice.
      // Read through IcacheArrayProbe so this half survives Task 6's predMem deletion.
      val predBits = dut.icache.logic.PRED_BITS_PER_BEAT
      var checked = 0
      for (w <- 0 until 4; s <- 0 until 64 if IcacheArrayProbe.wayValid(dut.icache, w, s)) {
        for (b <- 0 until 2) {
          val fromUnified = IcacheArrayProbe.wayPred(dut.icache, w, s, b)
          val fromShadow  = (dut.icache.logic.predMem(w).getBigInt(s) >> (predBits * b)) &
                            ((BigInt(1) << predBits) - 1)
          assert(fromUnified == fromShadow,
            f"ORACLE 4 VIOLATED (array sweep): way=$w set=$s beat=$b unified=0x" +
            f"${fromUnified.toString(16)} shadow=0x${fromShadow.toString(16)}")
          checked += 1
        }
      }
      assert(checked >= 64,
        s"only $checked (way,set,beat) triples were valid -- the stimulus did not " +
        s"actually populate the cache, so this oracle proved nothing")
    }
  }
}
```

- [ ] **Step 2: Run it and confirm it fails**
```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/frontend-restructure
sbt "testOnly m68k040.cache.IcacheUnifiedArraySpec" 2>&1 | tail -30
```
Expected: FAIL to compile — `value dbgUfaPredMatch is not a member of ...` (and `IcacheArrayProbe.wayPred` still reads `predMem`, so the sweep half would trivially pass; the in-RTL half is what fails).

- [ ] **Step 3: Widen the array — declaration and read port**
In `IcachePlugin.scala`, replace the `dataMem` declaration (`:154-155`):
```scala
    // ---- M1: the Unified Fetch Array ----------------------------------------
    // ONE synchronous array per way carries a beat's 256 instruction bits AND that
    // beat's 128 bits of per-word predecode. Replaces the old {dataMem (sync BRAM),
    // predMem (async LUTRAM, whole-line, 2 read ports)} pair.
    //
    // WHY (design spec section 5.1). predMem's line-granular async read forced all four
    // ways' whole 256-bit entries to be captured into `s1PredEntries` -- 1,024 flops,
    // whose clock enable was `cmdPort.fire` (ITLB mux -> async LUTRAM read -> 20-bit
    // compare -> answerable). synth/floorplan_frontend.xdc records that arc as EVERY
    // ONE of the worst 300 unique failing endpoints on the pinned routed checkpoint.
    // Beat-granular storage makes the predecode address ALREADY the address the data
    // array uses, so the capture bank has no reason to exist.
    val UFA_W   = 256 + PRED_BITS_PER_BEAT                                  // 384
    val lineMem = Seq.fill(ways)(Mem(Bits(UFA_W bits), sets * beatsPerLine))
```
Keep `predMem` (`:153`) exactly as it is for this task — it is the shadow.

Replace the read port (`:296-307`):
```scala
    // ---- shared unified-array read port (synchronous; BRAM) ----
    // Address+enable are driven by the FSM (IDLE hit accept, or REPLAY), from
    // PAGE-INVARIANT virtual bits only. The result `ufaBeat` is the registered memory
    // output, valid the NEXT cycle.
    val ufaReadAddr = UInt((setBits + 1) bits)
    val ufaReadEn   = Bool()
    ufaReadEn.simPublic()
    val xlateReadyDbg = Bool()
    xlateReadyDbg := xlate.rsp.ready
    xlateReadyDbg.simPublic()
    ufaReadAddr := U(0, (setBits + 1) bits)
    ufaReadEn   := False
    val ufaBeat = Vec(lineMem.map(_.readSync(ufaReadAddr, ufaReadEn)))
    // Spec section 4.3 N-3 / GC-7: the RAM output register IS the pipeline register.
    // KeepAttribute stops synthesis replicating it or absorbing it back into fabric --
    // if it does, M1 delivers nothing and lineReg/s1PredEntries have been re-created
    // under different names.
    ufaBeat.foreach(b => KeepAttribute(b))
    def ufaData(w: Int): Bits = ufaBeat(w)(255 downto 0)
    def ufaPred(w: Int): Bits = ufaBeat(w)(UFA_W - 1 downto 256)
```
Add the import at the top if not already present: `KeepAttribute` comes from `spinal.core._`, which is already imported at `:5`.

Then mechanically rename the two remaining old identifiers throughout the file:
```bash
grep -n "dataReadAddr\|dataReadEn\|dataBeat\|dataMem" src/main/scala/m68k040/cache/IcachePlugin.scala
```
- `dataReadAddr` → `ufaReadAddr` (sites: `:651`, `:797`)
- `dataReadEn` → `ufaReadEn` (sites: `:652`, `:798`)
- `dataBeat(s1Way)` → `ufaData(...)`. **At this task's stage `s1Way` is still a registered `UInt`**, so write `ufaData` as an indexed helper too:
```scala
    // Task 5 transitional: s1Way is still a registered UInt here (M3/Task 11 replaces
    // it with a one-hot). Vec-index the per-way data slices so the existing S1 mux is
    // unchanged in shape.
    val ufaDataVec = Vec((0 until ways).map(w => ufaData(w)))
    val ufaPredVec = Vec((0 until ways).map(w => ufaPred(w)))
```
and at `:430` `val s1Beat = Mux(s1FromMiss, missDataBeat, ufaDataVec(s1Way))`.
- `dataMem(w).simPublic()` at `:170` → `lineMem(w).simPublic()`.
- The `dataMem(w).write(...)` call site is handled in Step 4.

- [ ] **Step 4: Write predecode inline, per beat, at the single existing write call site**
Replace the predecode dwell's write block (`IcachePlugin.scala:1041-1048`) with:
```scala
      when(doAllocate) {
        for (w <- 0 until ways) {
          when(victimWay === U(w, wayBits bits)) {
            // M1: ONE write, ONE call site (IcachePlugin.scala:765-767 -- a second
            // Mem.write call site breaks SpinalHDL's MultiPortWritesSymplifier, a real
            // previously-shipped breakage in this file). The entry is
            // {beat predecode, beat data}, at exactly the address and on exactly the
            // cycle the old data-only write used.
            lineMem(w).write((missSet ## commitBeat).asUInt,
              beatPred ## Mux(isLoBeat, lineReg(255 downto 0), lineReg(511 downto 256)))
          }
        }
      }
```
`beatPred` is already computed live for this cycle's beat at `:984` — the whole point of M1's write side is that it is the *same cycle*, the *same address*, and the *same single call site*.

Keep the existing shadow `predMem(w).write(missSet, packedPred)` at `:1015` untouched for this task, and keep `predAccumLo`/`packedPred` alive to feed it.

- [ ] **Step 5: Add the in-RTL shadow-equivalence signal**
Immediately after the `packedPred` assembly (`IcachePlugin.scala:987-989`), add:
```scala
    // ---- Task 5 (M1a) TRANSITIONAL: unified-array vs shadow-predMem equivalence ----
    // Spec section 12.1 oracle 4, in its STRONG form: rather than re-deriving
    // PredecodeWord.classify in a testbench, assert that the predecode field this
    // cycle writes into the unified array is bit-identical to the corresponding beat
    // slice of the whole-line value the shadow predMem receives. Deleted together with
    // predMem in Task 6 (M1b). Combinational; drives only a simPublic wire.
    val dbgUfaPredMatch = Bool()
    dbgUfaPredMatch := Mux(isLoBeat,
      beatPred === packedPred(PRED_BITS_PER_BEAT - 1 downto 0),
      beatPred === packedPred(PRED_BITS_PER_LINE - 1 downto PRED_BITS_PER_BEAT))
    dbgUfaPredMatch.simPublic()
```
> Note: on the `isLoBeat` cycle `packedPred`'s low half is `predAccumLo`, which holds the *previous* fill's low beat — so the low-beat arm would compare against stale data. Write it instead against the value being latched this cycle, which is `beatPred` itself, making the low arm a tautology. **Do not do that** — a tautological oracle is decoration. Use this form instead, which compares the shadow's *stored* low half one cycle later:
```scala
    val dbgUfaPredMatch = Bool()
    // On the HIGH-beat cycle both halves of packedPred are final: the low half is
    // predAccumLo (this fill's low beat, latched last cycle) and the high half is this
    // cycle's live beatPred. So check BOTH halves here, against the unified array's two
    // beat entries -- the low one having been written last cycle.
    dbgUfaPredMatch := !predActive || isLoBeat ||
      ((packedPred(PRED_BITS_PER_LINE - 1 downto PRED_BITS_PER_BEAT) === beatPred) &&
       (packedPred(PRED_BITS_PER_BEAT - 1 downto 0) === predAccumLo))
    dbgUfaPredMatch.simPublic()
```
The high-beat arm is the real check; the low-beat cycle is exempt because the low half is not yet final. The oracle's **array sweep half** (Step 1's second block) covers the low beat post-hoc against the actual written array content, which is the stronger check anyway.

- [ ] **Step 6: Run the equivalence oracle**
```bash
sbt "testOnly m68k040.cache.IcacheUnifiedArraySpec" 2>&1 | tail -30
```
Expected: PASS, with the array sweep reporting `checked >= 64`.

If the array sweep fails with `unified=0x0`, the write address is wrong — check that `lineMem`'s address is `(missSet ## commitBeat).asUInt` (beat-granular, `sets * beatsPerLine` deep), not `missSet` (line-granular).

- [ ] **Step 7: Full correctness sweep**
```bash
make test-fast
sbt "testOnly m68k040.cache.IcacheOrderOracleSpec m68k040.cache.IcacheUnifiedArraySpec"
<GC4-SUITES>
<LOCKSTEP>
```
Expected: 149/149; oracles green; all GC-4 suites green; 396/396.

**`IcacheArrayProbe.wayData` must be updated in this step** — it now reads a 384-bit entry whose low 256 bits are the data:
```scala
  def wayData(ic: IcachePlugin, way: Int, set: Int, beat: Int): BigInt = {
    require(beat == 0 || beat == 1, s"beat must be 0 or 1, got $beat")
    val entry = ic.logic.lineMem(way).getBigInt(set * 2 + beat)
    entry & ((BigInt(1) << 256) - 1)
  }
```
`wayPred` still reads the shadow `predMem` in this task; Task 6 switches it.

- [ ] **Step 8: Commit**
```bash
git add src/main/scala/m68k040/cache/IcachePlugin.scala \
        src/test/scala/m68k040/cache/IcacheUnifiedArraySpec.scala \
        src/test/scala/m68k040/cache/IcacheArrayProbe.scala
git commit -m "icache(M1a): Unified Fetch Array written inline, predMem kept as a shadow

dataMem widened 256 -> 384 bits/beat and renamed lineMem: one synchronous array per
way now carries a beat's instruction bytes AND that beat's 128 bits of per-word
predecode, written at the SAME address, on the SAME cycle, through the SAME single
Mem.write call site (IcachePlugin.scala:765-767 -- a second call site breaks
SpinalHDL's MultiPortWritesSymplifier, a real previously-shipped breakage here).

predMem is deliberately RETAINED this commit as a shadow, so spec section 12.1's
oracle 4 can be stated in its strong form: the new array's inline predecode is
compared against the array it replaces, on the real fill path, over the whole suite --
not against a testbench re-implementation of PredecodeWord.classify. M1b (next
commit) switches the reads over and deletes the shadow.

ufaBeat carries KeepAttribute per spec section 4.3 N-3: the RAM output register IS the
pipeline register, and if synthesis absorbs it back into fabric M1 delivers nothing.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 6: M1b — reads switch to the unified array; delete `predMem`, `s1PredEntries`, `predAccumLo`

This is the task that actually removes 1,152 flops and the 65,536-bit async LUTRAM array. **`s1PredEntries` is the documented owner of the worst-300 endpoint arc** (`synth/floorplan_frontend.xdc`); this task deletes it.

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala` (`:153`, `:237`, `:328`, `:413-417`, `:432`, `:467`, `:684`, `:693`, `:758`, `:795`, `:823`, `:987-1015`)
- Modify: `src/test/scala/m68k040/cache/IcacheArrayProbe.scala`
- Modify: `src/test/scala/m68k040/cache/IcacheUnifiedArraySpec.scala`
- Modify: `src/test/scala/m68k040/cache/PerBeatPredecodeEquivSpec.scala`

**Interfaces:**
- Consumes: `ufaPred(w)`, `UFA_W`, `PRED_BITS_PER_BEAT` (Task 5).
- Produces, for Tasks 7/11:
  - `def windowPredBeat(entry: Bits, pc: UInt): Vec[ChunkPredecode]` — beat-granular window select, `entry` is `PRED_BITS_PER_BEAT` wide, selector is `pc(4 downto 3)`.
  - `def windowPredLine(entry: Bits, pc: UInt): Vec[ChunkPredecode]` — the OLD line-granular form, `pc(5 downto 3)`, **retained only for `missPred`** until Task 7 deletes `missPred`; deleted with it.
  - The S1 predecode value is now `s1PredW`, built from `ufaPredVec(s1Way)` with an explicit fault mask.

- [ ] **Step 1: Write the failing test — predecode must survive the read-path switch AND be fault-masked**
Append to `src/test/scala/m68k040/cache/IcacheUnifiedArraySpec.scala`:
```scala
  test("M1b: a hit's response predecode comes from the unified array and matches the reference",
       VerilatorTest) {
    compiled.doSim("m1b-read-path") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x2000)
      dut.clockDomain.waitSampling(5)

      // Fill line 0x1000, then RE-fetch every 8-byte window in it as a HIT and check
      // the response's 4 predecode chunks against the array content for that window.
      val base = 0x1000L
      fetchAndWait(dut, base)
      dut.clockDomain.waitSampling(10)

      val way = (0 until 4).find(w => IcacheArrayProbe.wayValid(dut.icache, w, 0))
        .getOrElse(fail("line 0x1000 did not become resident"))
      val chunkBits = dut.icache.logic.PRED_BITS_PER_WORD

      for (win <- 0 until 8) {
        val pc   = base + win * 8
        val beat = win / 4          // pc(5): windows 0-3 in beat 0, 4-7 in beat 1
        val lane = win % 4          // pc(4:3)
        val rsp  = fetchAndWait(dut, pc)   // returns the observed rsp payload

        val beatPred = IcacheArrayProbe.wayPred(dut.icache, way, 0, beat)
        for (k <- 0 until 4) {
          val expect = (beatPred >> (chunkBits * (lane * 4 + k))) & ((BigInt(1) << chunkBits) - 1)
          val got    = rsp.pred(k)
          assert(got == expect,
            f"pc=0x$pc%x chunk $k: rsp predecode 0x${got.toString(16)} != unified array " +
            f"0x${expect.toString(16)} (way=$way beat=$beat lane=$lane)")
        }
      }
    }
  }

  test("M1b: a translation fault delivers ZEROED predecode (the old s1PredEntries placeholder)",
       VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut(new ICacheModeTranslationPlugin))
      .doSim("m1b-fault-mask") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x2000)
      dut.clockDomain.waitSampling(5)

      // Make line 0x1000 RESIDENT with real (non-zero) predecode first...
      fetchAndWait(dut, 0x1000L)
      dut.clockDomain.waitSampling(10)
      val way = (0 until 4).find(w => IcacheArrayProbe.wayValid(dut.icache, w, 0))
        .getOrElse(fail("line 0x1000 did not become resident"))
      assert(IcacheArrayProbe.wayPred(dut.icache, way, 0, 0) != 0,
        "the resident line's predecode is all-zero, so this test cannot distinguish " +
        "'fault masked it' from 'it was already zero'")

      // ...then fault the SAME address, and require zeroed predecode anyway. Before
      // M1b this came from `s1PredEntries := 0`; now it must come from the explicit
      // S1 fault mask (spec section 5.1, 'Fault placeholder').
      dut.xlate.forceFault #= true
      val rsp = fetchAndWait(dut, 0x1000L)
      dut.xlate.forceFault #= false
      assert(rsp.fault, "the fetch did not fault")
      for (k <- 0 until 4)
        assert(rsp.pred(k) == 0,
          s"faulting fetch delivered NON-ZERO predecode chunk $k = 0x${rsp.pred(k).toString(16)}; " +
          s"the zeroed-predecode placeholder was lost when s1PredEntries was deleted")
    }
  }
```
> **Implementer note:** `fetchAndWait` must return the observed response payload (pc / data / fault / atc / pred as a `Seq[BigInt]`). If `IcacheSpec`'s existing helper does not, extend it — and put the extended helper in `IcacheArrayProbe.scala` (or a small `IcacheFetchDriver` object beside it) so both spec files share it. `ICacheModeTranslationPlugin` is the existing sim-pokeable translation stub (`src/test/scala/m68k040/cache/ICacheModeTranslationPlugin.scala`); read it and use its actual fault-forcing field name rather than the invented `forceFault` if it differs.

- [ ] **Step 2: Run and confirm failure**
```bash
sbt "testOnly m68k040.cache.IcacheUnifiedArraySpec -- -z M1b" 2>&1 | tail -30
```
Expected: FAIL. The first test fails because `IcacheArrayProbe.wayPred` still reads the shadow (so it passes trivially only if the arrays agree — which they do; the *real* failure is the second test, where the fault mask does not exist yet and the resident line's predecode leaks through only after Step 3 removes `s1PredEntries := 0`). If the second test passes at this point, that is expected — it is currently satisfied by `s1PredEntries := 0`. **Re-run it after Step 3 and require it to still pass**; that is the regression this test exists to catch.

- [ ] **Step 3: Switch the read paths and add the fault mask**

(a) Replace the two `windowPred` helpers (`IcachePlugin.scala:413-417`):
```scala
    /** M1: beat-granular window select. A "window" = 4 words (8 bytes, the FetchRsp
      * granularity). A 32-byte BEAT holds 4 windows, selected by pc(4:3) -- one bit
      * narrower than the old whole-line pc(5:3), because the beat that reaches S1 was
      * already selected by pc(5) when the array was addressed. */
    def windowPredBeat(entry: Bits, pc: UInt): Vec[ChunkPredecode] = {
      val win  = entry.subdivideIn(4 * PRED_BITS_PER_WORD bits)(pc(4 downto 3))
      val nibs = win.subdivideIn(PRED_BITS_PER_WORD bits)
      Vec(nibs.map(b => b.as(ChunkPredecode())))
    }

    /** LEGACY line-granular form, retained ONLY for the `missPred` INHIBITED/poison
      * bypass register, which is still whole-line at this commit. Deleted together
      * with `missPred` in M2a. */
    def windowPredLine(entry: Bits, pc: UInt): Vec[ChunkPredecode] = {
      val win  = entry.subdivideIn(4 * PRED_BITS_PER_WORD bits)(pc(5 downto 3))
      val nibs = win.subdivideIn(PRED_BITS_PER_WORD bits)
      Vec(nibs.map(b => b.as(ChunkPredecode())))
    }
```

(b) Replace the S1→rsp predecode mux (`:432`):
```scala
    // M1: predecode now rides out of the SAME synchronous array as the data, selected
    // by the same registered way and a one-bit-narrower window index. The old path
    // (4 x 256-bit async LUTRAM reads captured into 1,024 flops) is gone.
    //
    // Fault placeholder (spec section 5.1): the deleted s1PredEntries bank used to be
    // written all-zero on a translation fault. With no such register, S1 masks
    // explicitly -- a 32-bit AND on the S1->rsp path, one LUT level, preserving the
    // exact zeroed-predecode behaviour Aligner/DecodeStage already rely on.
    val s1PredRaw = Mux(s1FromMiss, windowPredLine(missPred, s1Pc),
                                    windowPredBeat(ufaPredVec(s1Way), s1Pc))
    val s1PredW   = Vec(s1PredRaw.map { p =>
      val masked = ChunkPredecode()
      masked.assignFromBits(p.asBits & B(s1Fault ? B(0, p.getBitsWidth bits) |
                                         B((BigInt(1) << p.getBitsWidth) - 1, p.getBitsWidth bits)))
      masked
    })
```
> Simpler and preferred if it elaborates cleanly — write it as a whole-vector mask instead of per-element:
> ```scala
> val s1PredBits = Mux(s1FromMiss, windowPredLine(missPred, s1Pc).asBits,
>                                  windowPredBeat(ufaPredVec(s1Way), s1Pc).asBits)
> val s1PredW    = Vec(Mux(s1Fault, B(0, s1PredBits.getWidth bits), s1PredBits)
>                        .subdivideIn(PRED_BITS_PER_WORD bits)
>                        .map(b => b.as(ChunkPredecode())))
> ```
> Use whichever compiles; they are semantically identical. **Verify the chunk ORDER is preserved** — `subdivideIn` returns index 0 = lowest bits, matching `windowPredBeat`'s own `nibs` ordering.

(c) Delete these declarations and every assignment to them:
- `s1PredEntries` (`:321-328` including its comment block, and the assignments at `:684`, `:693`, `:758`, `:823`)
- `predAccumLo` (`:234-237`, and `predAccumLo := beatPred` at `:1001`)
- `predMem` (`:153`), `lookupPredEntry` (`:467`), `replayPredEntry` (`:795`), `predMem(w).simPublic()` (`:170`), `predMem(w).write(missSet, packedPred)` (`:1015`)
- `packedPred` (`:987-989`) and `dbgUfaPredMatch` (Task 5 Step 5)

(d) `missPred` stays for now, but its **source** must change, because `packedPred` is gone. Replace `missPred := packedPred` (`:1010`) with a two-cycle assembly off `beatPred` directly:
```scala
      when(isLoBeat) {
        missPred(PRED_BITS_PER_BEAT - 1 downto 0) := beatPred
      } otherwise {
        missPred(PRED_BITS_PER_LINE - 1 downto PRED_BITS_PER_BEAT) := beatPred
        ...
      }
```
This is a strict simplification: `predAccumLo`'s 128 flops existed only to hold the low beat for one cycle so `packedPred` could be assembled combinationally; writing directly into `missPred`'s two halves does the same job with zero extra state. **`missPred` itself dies in Task 7** — this is a one-task bridge.

- [ ] **Step 4: Update the test-side accessor**
In `IcacheArrayProbe.scala`:
```scala
  /** PRED_BITS_PER_BEAT-bit predecode content of one beat of one (way, set).
    * M1b: reads the Unified Fetch Array's inline predecode field. The signature is
    * unchanged from the pre-M1 line-granular form -- that was the whole point of
    * making this helper beat-granular in Task 3. */
  def wayPred(ic: IcachePlugin, way: Int, set: Int, beat: Int): BigInt = {
    require(beat == 0 || beat == 1, s"beat must be 0 or 1, got $beat")
    val entry = ic.logic.lineMem(way).getBigInt(set * 2 + beat)
    entry >> 256
  }
```
In `IcacheUnifiedArraySpec.scala`, delete the in-RTL `dbgUfaPredMatch` monitor and the shadow-comparison half of oracle 4, replacing the shadow with `PerBeatPredecodeEquivSpec`'s reference classifier if one is exposed, or else keeping only the M1b read-path tests. **Do not leave a test that compares the array against itself** — record in the commit message that oracle 4's shadow form was consumed by Task 5 and cannot outlive `predMem`.

- [ ] **Step 5: Update `PerBeatPredecodeEquivSpec`**
```bash
grep -n "predMem\|windowPred\|5 downto 3\|PRED_BITS_PER_LINE" src/test/scala/m68k040/cache/PerBeatPredecodeEquivSpec.scala
```
Every whole-line assumption becomes beat-granular. This spec's *purpose* — proving the 16-instance `classifyBeat` presents `classify` the same three lookahead words and validity flags the whole-line scheme did — is unchanged by M1; only the storage it reads changes.

- [ ] **Step 6: Run the new tests**
```bash
sbt "testOnly m68k040.cache.IcacheUnifiedArraySpec" 2>&1 | tail -30
```
Expected: PASS, including the fault-mask test (which now exercises the explicit S1 mask, not `s1PredEntries := 0`).

- [ ] **Step 7: Full correctness sweep**
```bash
grep -n "s1PredEntries\|predAccumLo\|predMem\|packedPred\|dbgUfaPredMatch" src/main/scala/m68k040/cache/IcachePlugin.scala
# expect: NO MATCHES
make test-fast
sbt "testOnly m68k040.cache.IcacheOrderOracleSpec m68k040.cache.IcacheUnifiedArraySpec"
<GC4-SUITES>
<LOCKSTEP>
```
Expected: zero grep matches (the 1,152 flops and the LUTRAM array are gone by name); 149/149; oracles green; GC-4 green; 396/396.

- [ ] **Step 8: Memory-inference check (GC-1 exemption 2 — INFORMATION ONLY)**
Risk R1: a 384-bit × 128-deep array may map to a wide BRAM cascade with a large output mux, or to distributed RAM. **None of the eight prior attempts touched memory inference at all — this is a new failure axis.**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/frontend-restructure
make verilog
pgrep -af "impl_FullCore|vivado" | wc -l   # GC-9: must be 0
cat > /tmp/synth_only_check.tcl <<'TCL'
read_verilog generated/M68kFullCoreSynth.v
read_xdc synth/clk.xdc
synth_design -top M68kFullCoreSynth -part xcku5p-ffvb676-2-e -mode out_of_context
report_utilization -file synth/m1_synth_util.rpt
report_utilization -hierarchical -hierarchical_depth 4 -file synth/m1_synth_hier_util.rpt
report_ram_utilization -file synth/m1_ram_util.rpt
puts "M1_BRAM_TILES [llength [get_cells -hier -filter {REF_NAME =~ RAMB*}]]"
puts "M1_LUTRAM [llength [get_cells -hier -filter {REF_NAME =~ RAM* && REF_NAME !~ RAMB*}]]"
puts "M1_ICACHE_FF [llength [get_cells -hier -filter {NAME =~ *IcachePlugin_logic* && REF_NAME =~ FD*}]]"
puts "M1_SYNTH_ONLY_DONE"
TCL
nohup vivado -mode batch -source /tmp/synth_only_check.tcl > synth/m1_synth_only.out 2>&1 &
VIVADO_PID=$!
# GC-5: ACTIVE POLLING. Do not passively wait on a notification.
while kill -0 "$VIVADO_PID" 2>/dev/null; do sleep 30; done
grep -E "M1_BRAM_TILES|M1_LUTRAM|M1_ICACHE_FF|M1_SYNTH_ONLY_DONE" synth/m1_synth_only.out
```
Expected: `M1_SYNTH_ONLY_DONE` present; `M1_ICACHE_FF` down ~1,152 from baseline; `M1_LUTRAM` down by Task 1's `PREDMEM_LUTRAM_LUTS`; `M1_BRAM_TILES` ≤ 80.

**GC-1: DO NOT read, record, or quote the WNS from this run.** Do not run `place_design` or `route_design`.

**Decision rule (this run's ONLY permitted use — spec §14 Q2 / risk R1):**
- `M1_BRAM_TILES ≤ 80` and `M1_LUTRAM` down as expected → **keep the fold form. Proceed.**
- `M1_BRAM_TILES > 80`, or the array inferred as distributed RAM, or `M1_ICACHE_FF` did **not** drop ~1,152 (which would mean `KeepAttribute` failed and the output register became fabric flops) → **retreat to the R1 fallback**: revert the width change to `lineMem`, restore `dataMem` at 256 bits, and add `predMem = Mux/Mem(Bits(PRED_BITS_PER_BEAT), sets * beatsPerLine)` as a **separate synchronous** array read with the same address and enable. This deletes exactly the same 1,152 flops with no width change to a proven BRAM mapping. Everything downstream in this plan is unaffected: `ufaPred(w)` simply becomes that array's `readSync` output instead of a slice of `ufaBeat`.

- [ ] **Step 9: Commit**
```bash
git add src/main/scala/m68k040/cache/IcachePlugin.scala \
        src/test/scala/m68k040/cache/IcacheArrayProbe.scala \
        src/test/scala/m68k040/cache/IcacheUnifiedArraySpec.scala \
        src/test/scala/m68k040/cache/PerBeatPredecodeEquivSpec.scala \
        synth/m1_synth_util.rpt synth/m1_synth_hier_util.rpt synth/m1_ram_util.rpt
git commit -m "icache(M1b): delete s1PredEntries, predAccumLo and the predMem LUTRAM array

-1,152 CLB flops and -65,536 bits of async distributed RAM (both read ports).
s1PredEntries is the documented owner of the worst-300 endpoint arc on the pinned
routed checkpoint (synth/floorplan_frontend.xdc: EVERY one of the worst 300 unique
failing endpoints is FetchAlignPlugin_stalled_reg/C ->
IcachePlugin_s1PredEntries_{0..3}_reg[*]/CE, 5.990 ns, 20 levels, 66%% route, 0 pblock
boundaries crossed). This commit deletes the register, not the path around it.

Predecode now rides out of the Unified Fetch Array with the data, selected by
windowPredBeat (pc(4:3) over a 32-byte beat) instead of windowPred (pc(5:3) over a
64-byte line) -- one selector bit narrower, because pc(5) already chose the beat when
the array was addressed. The deleted bank's all-zero-on-fault placeholder becomes an
explicit S1 mask (one LUT level), pinned by a directed test that first proves the
resident line's predecode is NON-zero so the test cannot pass vacuously.

predAccumLo existed only to hold the low beat for one cycle so a whole-line packedPred
could be assembled; missPred's two halves are now written directly per beat. missPred
itself dies in M2a.

Memory inference checked post-synthesis (information-only per the plan's GC-1; no
place, no route, WNS deliberately not read): BRAM tiles and LUTRAM/FF deltas recorded
in synth/m1_synth_util.rpt.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 7: M2a — narrow the INHIBITED/poisoned bypass; delete `missPred`

Spec §5.2: `lineReg` (512 FF) and `missPred` (256 FF) exist at full line width **only** because `REPLAY` for a non-allocated line delivers from them via `s1FromMiss`. But only a **64-bit data window plus its four 8-bit predecode chunks** are ever consumed. 96 flops replace 768. This task takes the predecode half (256 → 32); Task 8 takes the data half together with `lineReg`'s deletion.

**PLAN DECISION:** split from Task 8 because `missPred`'s narrowing is independently reviewable, independently testable (the INHIBITED-replay corruption test at `IcacheSpec.scala:808-836` and the poisoned-fill test are its exact gates), and touches none of the FSM restructuring Task 8 does. Doing both at once would make a 256-flop deletion indistinguishable from an FSM rewrite in review.

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala` (`:233`, `:432`, `:413-417`, `:1010`)
- Modify: `src/test/scala/m68k040/cache/IcacheSpec.scala` (INHIBITED-replay test assertions)

**Interfaces:**
- Consumes: `windowPredBeat` (Task 6), `beatPred` (`:984`), `missPC`, `commitBeat`.
- Produces, for Tasks 8/11:
  - `bypPred: Bits` — `Reg(Bits(4 * PRED_BITS_PER_WORD bits))` (32 bits). Captured during the predecode dwell on the beat that contains `missPC(5)`, at window `missPC(4:3)`.
  - The S1 predecode mux becomes `Mux(s1FromMiss, bypPred, windowPredBeat(ufaPredVec(s1Way), s1Pc).asBits)` — **both arms are now 32 bits**, so `windowPredLine` is deleted here.

- [ ] **Step 1: Write the failing test — the bypass must deliver the RIGHT window, not window 0**
The existing INHIBITED test at `IcacheSpec.scala:808-836` fetches at a fixed address. A narrowed bypass that always captured window 0 would pass it. Append a test that fetches a **non-zero window** of an INHIBITED line:
```scala
  // Task 7 (M2a): the INHIBITED/poisoned bypass is narrowed from a whole 256-bit line
  // predecode register (missPred, 256 flops) to a single 32-bit window (bypPred).
  // A narrowing that captured the WRONG window would still pass the pre-existing
  // INHIBITED test, which only ever fetches offset 0 of the line. This one sweeps
  // every window of both beats.
  test("INHIBITED replay delivers the correct predecode WINDOW, not window 0", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut(new ICacheModeTranslationPlugin))
      .doSim("inhibited-window-sweep") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x2000)
      dut.clockDomain.waitSampling(5)

      // First: fetch the line CACHEABLE so it becomes resident and we can read the
      // array's per-window predecode as the reference.
      fetchAndWait(dut, 0x1000L)
      dut.clockDomain.waitSampling(10)
      val way = (0 until 4).find(w => IcacheArrayProbe.wayValid(dut.icache, w, 0))
        .getOrElse(fail("line 0x1000 did not become resident"))
      val chunkBits = dut.icache.logic.PRED_BITS_PER_WORD
      val reference = (0 until 8).map { win =>
        val beat = win / 4
        val lane = win % 4
        val bp   = IcacheArrayProbe.wayPred(dut.icache, way, 0, beat)
        (0 until 4).map(k => (bp >> (chunkBits * (lane * 4 + k))) & ((BigInt(1) << chunkBits) - 1))
      }
      assert(reference.flatten.exists(_ != 0),
        "the reference predecode is all-zero; this test cannot distinguish a correct " +
        "window from a wrong one")
      assert(reference.map(_.mkString(",")).distinct.length > 1,
        "every window's predecode is identical, so a window-select bug would be " +
        "invisible -- pick a different line or a different memory pattern")

      // Now invalidate, switch the page to INHIBITED, and re-fetch each window. Each
      // response must carry that window's predecode, delivered through the bypass.
      pulseInvalidate(dut)
      dut.clockDomain.waitSampling(5)
      dut.xlate.forceInhibited #= true
      for (win <- 0 until 8) {
        val pc  = 0x1000L + win * 8
        val rsp = fetchAndWait(dut, pc)
        for (k <- 0 until 4)
          assert(rsp.pred(k) == reference(win)(k),
            f"INHIBITED replay at pc=0x$pc%x (window $win) delivered predecode chunk $k = " +
            f"0x${rsp.pred(k).toString(16)}, expected 0x${reference(win)(k).toString(16)}. " +
            f"The narrowed bypPred captured the wrong window.")
      }
      dut.xlate.forceInhibited #= false
    }
  }
```
> **Implementer note:** `pulseInvalidate` is `IcacheSpec`'s existing helper (`// ---- sim helpers`, "Pulse invalidateAll high for one cycle, then low"). `forceInhibited` — read `ICacheModeTranslationPlugin.scala` for the real field name that selects `CacheMode.INHIBITED`.

- [ ] **Step 2: Run and confirm it passes on the PRE-narrowing RTL**
```bash
sbt "testOnly m68k040.cache.IcacheSpec -- -z 'correct predecode WINDOW'" 2>&1 | tail -20
```
Expected: **PASS** — `missPred` is whole-line, so every window is available. This test is a *regression detector for the narrowing*, so it must be green before the narrowing and green after. If it fails here, the test is wrong (most likely `forceInhibited`'s name, or the reference read); fix it before proceeding.

- [ ] **Step 3: Narrow the register**
Replace `missPred`'s declaration (`IcachePlugin.scala:216-233` — keep the historical comment block, it documents the real shipped bug this register exists for, but retitle the register):
```scala
    // ... (existing comment block about the icache-corruption-fix, unchanged) ...
    //
    // M2a: NARROWED. The bypass used to be a whole 256-bit line predecode register
    // (`missPred`) because REPLAY was written to reuse the same whole-line
    // windowPred selector the array path used. Only ONE 32-bit window is ever
    // consumed -- REPLAY answers exactly one fetch, at missPC. 32 flops replace 256.
    val bypPred = Reg(Bits(4 * PRED_BITS_PER_WORD bits))
```

- [ ] **Step 4: Capture the right window on the right beat**
In the predecode dwell, replace the `missPred` half-writes added by Task 6 Step 3(d) with a single window capture:
```scala
      // M2a: capture ONLY the window this refill's own fetch will consume. missPC(5)
      // picks the beat, missPC(4:3) picks the window inside it. Written on whichever
      // dwell cycle classifies that beat, so it is stable by the time REPLAY runs.
      when(commitBeat === missPC(5).asUInt) {
        bypPred := beatPred.subdivideIn(4 * PRED_BITS_PER_WORD bits)(missPC(4 downto 3))
      }
```
Place this **inside `when(predActive)`**, alongside the existing `commitBeat` bookkeeping, and **outside** the `when(doAllocate)` gate — the whole point of the bypass is that it carries the response for lines that are *not* allocated.

> **Correctness note the implementer must verify:** `commitBeat` counts 0 then 1 across the dwell, and `beatPred` is the classification of the beat selected by `isLoBeat = commitBeat === 0`. So `commitBeat === missPC(5).asUInt` fires on exactly the cycle whose `beatPred` covers `missPC`. Confirm against `IcachePlugin.scala:981-984` (`beatSrc = Mux(isLoBeat, lineReg(255:0), lineReg(511:256))`).

- [ ] **Step 5: Rewire the S1 mux and delete `windowPredLine`**
```scala
    // M2a: both arms are now a 32-bit window, so the whole-line selector is gone.
    val s1PredBits = Mux(s1FromMiss, bypPred,
                                     windowPredBeat(ufaPredVec(s1Way), s1Pc).asBits)
    val s1PredW    = Vec(Mux(s1Fault, B(0, s1PredBits.getWidth bits), s1PredBits)
                           .subdivideIn(PRED_BITS_PER_WORD bits)
                           .map(b => b.as(ChunkPredecode())))
```
Delete `windowPredLine` (added in Task 6 Step 3(a)) entirely — its only caller is gone.

- [ ] **Step 6: Run the directed tests**
```bash
sbt "testOnly m68k040.cache.IcacheSpec -- -z 'correct predecode WINDOW'"
sbt "testOnly m68k040.cache.IcacheSpec -- -z INHIBITED"
sbt "testOnly m68k040.cache.IcacheInvalidateSpec"
```
Expected: all PASS. The window sweep is the one that would catch a wrong-beat or wrong-lane capture; `IcacheInvalidateSpec` covers the `missPoison` arm of `s1FromMiss` (an `invalidateAll` mid-fill also routes through the bypass — see `IcachePlugin.scala:818-824`).

- [ ] **Step 7: Full correctness sweep**
```bash
grep -n "missPred\|windowPredLine\|PRED_BITS_PER_LINE" src/main/scala/m68k040/cache/IcachePlugin.scala
# expect: PRED_BITS_PER_LINE may survive as a derived constant; missPred/windowPredLine NO MATCHES
make test-fast
sbt "testOnly m68k040.cache.IcacheOrderOracleSpec m68k040.cache.IcacheUnifiedArraySpec"
<GC4-SUITES>
<LOCKSTEP>
```
Expected: 149/149; oracles green; GC-4 green; 396/396.

- [ ] **Step 8: Commit**
```bash
git add src/main/scala/m68k040/cache/IcachePlugin.scala src/test/scala/m68k040/cache/IcacheSpec.scala
git commit -m "icache(M2a): narrow the INHIBITED/poisoned predecode bypass, 256 -> 32 flops

missPred was a whole-256-bit-line register because REPLAY reused the array path's
whole-line window selector. REPLAY answers exactly ONE fetch, at missPC, so exactly
ONE 32-bit window is ever consumed. bypPred captures that window on whichever dwell
cycle classifies missPC(5)'s beat.

Pinned by a new directed test that sweeps ALL EIGHT windows of an INHIBITED line and
compares each against the same line's array content read while it was cacheable --
the pre-existing INHIBITED test only ever fetched offset 0, so a bypass that always
captured window 0 would have passed it. The test also asserts up front that the
reference is non-zero AND not window-invariant, so it cannot pass vacuously.

windowPredLine (the whole-line pc(5:3) selector, introduced one commit ago purely to
keep missPred working through M1b) is deleted with its only caller.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 8: M2b — the Unified MSHR line file; delete `lineReg`; merge `PF_PRED` into `PREDECODE`

**BLOCKING PREREQUISITE — GC-12/D4 must be resolved before this task starts.** Re-read `.superpowers/sdd/progress-ipc-push-2026-08-09.md` for a §36+ entry, and confirm the user's D4 decision. **If D4 = DELETE the prefetch engine, STOP: Tasks 8/9/12 must be re-planned (the S2b variant is not written in this plan).** If D4 = KEEP (including by GC-12's no-decision default), proceed exactly as written.

Spec §5.2. Today there are two entirely separate line-data mechanisms: `lineReg` (a 512-flop register whose D-input is a 512-bit mux between an AXI beat and a **512-bit async read of a 5-entry LUTRAM**, and whose IDLE clock enable is `pfInstallAny && !demandFillStart`) and `pfLineLo`/`pfLineHi` (a 5-entry memory pair). Per the checkpoint-forensics diagnostic, `lineReg` is the literal endpoint family that variants B3 and P2 each regressed onto, **and** the baseline's tied-runner-up worst path (`FetchAlignPlugin_stalled_reg/C → IcachePlugin_lineReg_reg[418]/D`, −1.472, tying nominal WNS to three decimals).

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala` (`:202`, `:279-281`, `:574-588`, `:719-736`, `:768-789`, `:920-965`, `:967-1053`)

**Interfaces:**
- Consumes: `AxiIds.{I_DEMAND, I_SPEC_BASE, I_SPEC_SLOTS, ID_W}`, `bypPred` (Task 7).
- Produces, for Task 9:
  - `MSHR_N: Int = 1 + pfSlots` (6). **Entry 0 is the demand MSHR; entries 1..5 are speculative, matching `AxiIds.I_DEMAND == 0` and `I_SPEC_BASE == 1`.**
  - `fillLo: Mem[Bits]`, `fillHi: Mem[Bits]` — `Mem(Bits(256 bits), MSHR_N)`, each with **exactly one** `.write` call site (GC-6).
  - `installIdx: UInt` — `Reg(UInt(log2Up(MSHR_N) bits))`, loaded in the new `INSTALL_ARM` state.
  - `installSet: UInt` — `Reg(UInt(setBits bits))` (SG-3; see Task 9 Step 4).
  - `fillLoQ`, `fillHiQ: Bits` — `fillLo.readSync(installIdx)` / `fillHi.readSync(installIdx)`, `KeepAttribute`-marked.
  - `bypWindow: Bits` — `Reg(Bits(64 bits))`, the data half of the narrowed bypass.
  - FSM states after this task: `IDLE`, `REFILL`, `INSTALL_ARM`, `PREDECODE`, `REPLAY`, `FAULT`. **`PF_PRED` is deleted.**

- [ ] **Step 1: Write the failing test — a prefetched line and a demand-filled line must be byte-identical**
Append to `src/test/scala/m68k040/cache/IcachePrefetchSpec.scala`:
```scala
  // Task 8 (M2b): demand and speculative fills now share ONE MSHR line file, written
  // uniformly from the AXI R channel by RID. The old asymmetry -- demand into a
  // 512-flop lineReg, speculative into a 5-entry memory then COPIED into lineReg in
  // IDLE -- is deleted. This test pins the property that copy path existed to provide:
  // a line installed speculatively must be bit-identical to the same line installed
  // by demand, in data AND predecode.
  test("a speculatively installed line is byte-identical to a demand-installed one",
       VerilatorTest) {
    // Pass A: prefetch ON. A long sequential run installs most lines speculatively.
    val speculative = scala.collection.mutable.Map[(Int, Int), (BigInt, BigInt)]()
    compiled.doSim("mshr-spec-install") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x4000)
      dut.icache.logic.prefetchEnable #= true
      dut.clockDomain.waitSampling(5)
      // Touch only every 4th line, so the 3 lines between each are installed ONLY by
      // the prefetch engine.
      for (i <- 0 until 16) fetchAndWait(dut, 0x1000L + i * 256)
      dut.clockDomain.waitSampling(500)
      for (w <- 0 until 4; s <- 0 until 64 if IcacheArrayProbe.wayValid(dut.icache, w, s);
           b <- 0 until 2) {
        speculative((s, b)) = (IcacheArrayProbe.wayData(dut.icache, w, s, b),
                               IcacheArrayProbe.wayPred(dut.icache, w, s, b))
      }
    }
    assert(speculative.nonEmpty, "no lines became resident in the prefetch pass")

    // Pass B: prefetch OFF. Every line is installed by demand.
    compiled.doSim("mshr-demand-install") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x4000)
      dut.icache.logic.prefetchEnable #= false
      dut.clockDomain.waitSampling(5)
      for (i <- 0 until 64) fetchAndWait(dut, 0x1000L + i * 64)
      dut.clockDomain.waitSampling(200)
      var compared = 0
      for (w <- 0 until 4; s <- 0 until 64 if IcacheArrayProbe.wayValid(dut.icache, w, s);
           b <- 0 until 2) {
        val demandPair = (IcacheArrayProbe.wayData(dut.icache, w, s, b),
                          IcacheArrayProbe.wayPred(dut.icache, w, s, b))
        speculative.get((s, b)).foreach { specPair =>
          assert(specPair == demandPair,
            f"set=$s beat=$b: speculatively installed content differs from demand-installed. " +
            f"spec data=0x${specPair._1.toString(16)} pred=0x${specPair._2.toString(16)}; " +
            f"demand data=0x${demandPair._1.toString(16)} pred=0x${demandPair._2.toString(16)}")
          compared += 1
        }
      }
      assert(compared >= 16,
        s"only $compared (set,beat) pairs overlapped between the two passes -- the " +
        s"comparison is too thin to prove anything")
    }
  }
```

- [ ] **Step 2: Run it and confirm it passes on the PRE-M2b RTL**
```bash
sbt "testOnly m68k040.cache.IcachePrefetchSpec -- -z 'byte-identical'" 2>&1 | tail -20
```
Expected: **PASS**. This is a regression detector for the restructure, so it must be green before and after. A failure here means the *existing* copy path already differs — report it as a pre-existing bug before touching anything.

- [ ] **Step 3: Declare the MSHR line file and delete the two old mechanisms**
Replace `lineReg`'s declaration (`IcachePlugin.scala:202`) and `pfLineLo`/`pfLineHi` (`:279-280`) with:
```scala
    // ---- M2: the Unified MSHR line file --------------------------------------
    // ONE line-data structure for demand AND speculative fills, indexed by AXI ID.
    // Entry 0 is the demand MSHR (AxiIds.I_DEMAND == 0); entries 1..pfSlots are the
    // speculative slots (AxiIds.I_SPEC_BASE == 1), so the AXI RID IS the index -- no
    // decode, no asymmetry, no copy.
    //
    // WHY (design spec section 5.2). `lineReg` was a 512-flop bank whose D-input was a
    // 512-bit mux between an AXI beat and a 512-bit ASYNC READ of a 5-entry LUTRAM
    // (a 512 x 5:1 mux), and whose IDLE clock enable was
    // `pfInstallAny && !demandFillStart` -- a five-way reduction over four slot-state
    // vectors, AND-ed with a function of cmdPort.fire. Exactly the shape design
    // principle P2/P3 forbids. It is also the checkpoint-forensics diagnostic's named
    // landing zone for BOTH the B3 and P2 regressions, and the baseline's tied
    // runner-up worst path (FetchAlignPlugin_stalled_reg/C -> lineReg_reg[418]/D,
    // -1.472 ns, tying nominal WNS to three decimals).
    val MSHR_N = 1 + pfSlots                              // 6
    val mshrIdxBits = log2Up(MSHR_N)
    val fillLo = Mem(Bits(256 bits), MSHR_N)              // beat 0
    val fillHi = Mem(Bits(256 bits), MSHR_N)              // beat 1
```

Add, near the FSM's other install state (after `pfInstallIdx`'s declaration at `:281`):
```scala
    // The install target, registered one cycle AHEAD of the dwell so the file read is
    // synchronous. Set in INSTALL_ARM; stable for the whole PREDECODE dwell.
    val installIdx = Reg(UInt(mshrIdxBits bits)) init U(0, mshrIdxBits bits)
    // SG-3 (plan): a dedicated register rather than mshrSet(installIdx). The D3-SET-I
    // guard `fillArrayWrActive && (lookupSet === installSet)` sits in the ACCEPT cone;
    // sourcing it from a Vec index would put a 6:1 mux in front of that comparator, in
    // exactly the cone this whole design exists to shorten. One register instead.
    val installSet = Reg(UInt(setBits bits))
    // The synchronous read of the line being installed. KeepAttribute per GC-7: if
    // these become fabric flops, lineReg has been re-created under a different name.
    val fillLoQ = fillLo.readSync(installIdx)
    val fillHiQ = fillHi.readSync(installIdx)
    KeepAttribute(fillLoQ)
    KeepAttribute(fillHiQ)
    // M2: the DATA half of the narrowed bypass (the predecode half is Task 7's
    // bypPred). 64 flops replace lineReg's 512 for the INHIBITED/poisoned replay.
    val bypWindow = Reg(Bits(64 bits))
```

- [ ] **Step 4: Make the R-channel write uniform (one call site per memory, GC-6)**
Replace `IcachePlugin.scala:935-937` and the `lineReg` half-writes at `:945-949`:
```scala
    // ---- M2: uniform R-channel write. NO demand/speculative asymmetry. ----
    // The AXI RID IS the MSHR index. Exactly one .write call site per memory
    // (IcachePlugin.scala:765-767: a second breaks MultiPortWritesSymplifier).
    val rIdx      = axi.r.payload.id.resize(mshrIdxBits)
    val rOwned    = demandRspMatch || pfRspMatch
    val rFire     = axi.r.fire && rOwned
    // Which beat this entry is expecting. Demand uses beatCnt (entry 0), speculative
    // uses pfBeat (entries 1..N). Task 9 unifies these into mshrBeat(rIdx).
    val rBeatIsHi = Mux(demandRspMatch, beatCnt.asBool, pfBeat(pfRspIdx))
    fillLo.write(rIdx, axi.r.payload.data, enable = rFire && !rBeatIsHi)
    fillHi.write(rIdx, axi.r.payload.data, enable = rFire &&  rBeatIsHi)
```
Delete the `lineReg(255 downto 0) := ...` / `lineReg(511 downto 256) := ...` assignments inside `when(demandRspMatch)` (`:945-949`), keeping the `beatCnt`, `missBusFault`, `refillDone`/`refillErr` bookkeeping and the two-beat assertions (`:942`, `:956`) unchanged.

- [ ] **Step 5: Add `INSTALL_ARM` and merge `PF_PRED` into `PREDECODE`**

(a) Replace the FSM state declarations (`:574-588`): delete `PF_PRED`, add `INSTALL_ARM` with this comment:
```scala
      // M2: the arm cycle. installIdx/installSet are registered here so the MSHR line
      // file read that feeds the dwell is SYNCHRONOUS. Costs the demand refill +1
      // cycle on a ~78-cycle DDR-bound event (1.3%%) and SAVES the speculative install
      // path a cycle (it no longer needs the IDLE 512-bit copy). Spec section 5.6.
      val INSTALL_ARM = new State
```

(b) Replace `IDLE`'s prefetch-install arm (`:723-735`) with:
```scala
        // M2: no 512-bit LUTRAM->register copy. Just name the entry and arm the file
        // read; PREDECODE reads it out synchronously next-next cycle.
        when(pfInstallAny && !demandFillStart) {
          installIdx    := (pfInstallSel + U(AxiIds.I_SPEC_BASE, mshrIdxBits bits)).resized
          pfInstallIdx  := pfInstallSel
          installSet    := pfSet(pfInstallSel)
          missPC        := pfPa(pfInstallSel)
          missPA        := pfPa(pfInstallSel)
          missSet       := pfSet(pfInstallSel)
          missTag       := pfTag(pfInstallSel)
          missCacheable := True
          victimWay     := pfWay(pfInstallSel)
          missPoison    := pfPoison(pfInstallSel) || anyInvalidate
          commitBeat    := U(0, 1 bits)
          predIsPfReg   := True
          goto(INSTALL_ARM)
        }
```
`predIsPfReg` is a new `RegInit(False)` replacing the old combinational `predIsPf` (which was set by `PF_PRED.whenIsActive`); it must be set `False` on the demand path (Step 5(c)) and is read by the dwell's `pfFilled(w)(missSet) := predIsPfReg && !anyInvalidate` (`:1033`). Keep `predIsPf` as a wire aliasing it so the existing `simPublic` and any test reading it are unaffected:
```scala
    val predIsPfReg = RegInit(False)
    val predIsPf = Bool(); predIsPf := predIsPfReg; predIsPf.simPublic()
```

(c) `REFILL` now routes to `INSTALL_ARM` instead of `PREDECODE` (`:740-742`):
```scala
      REFILL.whenIsActive {
        refillActive := True
        when(refillDone) {
          when(refillErr) { goto(FAULT) } otherwise {
            installIdx  := U(AxiIds.I_DEMAND, mshrIdxBits bits)
            installSet  := missSet
            predIsPfReg := False
            goto(INSTALL_ARM)
          }
        }
      }
```

(d) Add the new state's body, right after `REFILL`:
```scala
      // M2: one cycle. installIdx/installSet were registered by whoever entered here;
      // fillLoQ/fillHiQ present the line on the FIRST PREDECODE cycle. Nothing else
      // happens -- deliberately: this state exists to make the file read synchronous,
      // and giving it any other job would put logic back on the path M2 is clearing.
      INSTALL_ARM.whenIsActive { goto(PREDECODE) }
```

(e) `PREDECODE` absorbs `PF_PRED`'s two extra behaviours — holding the lookup port open with `canStartFill = false` (only when installing a speculative line; a demand install must keep the port CLOSED, per the ordering analysis at `:595-620`), and freeing the speculative slot on completion:
```scala
      PREDECODE.whenIsActive {
        predActive := True
        // M2: PF_PRED merged in. A SPECULATIVE install produces no response, so the
        // fetch port may stay open and serve hits (IcachePlugin.scala:611-620). A
        // DEMAND install must keep it closed -- its own REPLAY response is still owed
        // and FetchRsp carries no tag, so a younger hit answered here would be
        // attributed to the older ring entry (silent instruction-byte mis-pairing).
        when(predIsPfReg) {
          // Slice I1 / D3-SET-I: the array-write cycle is the one cycle a concurrent
          // lookup into the SAME set must not be answered (write-first async tagMem vs
          // a register `valids` array).
          fillArrayWrActive := True
          lookupTick(canStartFill = false)
        }
        when(commitBeat === U(1, 1 bits)) {
          when(predIsPfReg) {
            pfValid(pfInstallIdx)    := False
            pfArSent(pfInstallIdx)   := False
            pfComplete(pfInstallIdx) := False
            goto(IDLE)
          } otherwise {
            goto(REPLAY)
          }
        }
      }
```
Delete the whole `PF_PRED.whenIsActive { ... }` block (`:773-789`).

- [ ] **Step 6: Point the dwell at the MSHR file and capture the data bypass**
Replace `IcachePlugin.scala:981-984`:
```scala
    // M2: the dwell classifies out of the MSHR line file, not out of lineReg. The
    // file is split Lo/Hi precisely so classifyBeat's cross-beat lookahead (the low
    // beat's last 3 words need words 16/17/18) is still a single-cycle read.
    val isLoBeat  = commitBeat === U(0, 1 bits)
    val beatSrc   = Mux(isLoBeat, fillLoQ, fillHiQ)
    val beatNext3 = Mux(isLoBeat, fillHiQ(47 downto 0), B(0, 48 bits))
    val beatPred  = classifyBeat(beatSrc, beatNext3, isLoBeat)
```
Replace the array write's data source (`:1044-1045`, as rewritten by Task 5 Step 4):
```scala
            lineMem(w).write((missSet ## commitBeat).asUInt, beatPred ## beatSrc)
```
And add the data-half bypass capture alongside Task 7's `bypPred` capture:
```scala
      // M2: the DATA half of the narrowed bypass. Same beat/window selection as
      // bypPred -- 64 flops replace lineReg's 512 for the INHIBITED/poisoned replay.
      when(commitBeat === missPC(5).asUInt) {
        bypWindow := beatSrc.subdivideIn(64 bits)(missPC(4 downto 3))
        bypPred   := beatPred.subdivideIn(4 * PRED_BITS_PER_WORD bits)(missPC(4 downto 3))
      }
```
Replace the S1 data mux (`:429-431`):
```scala
    // M2: `missDataBeat`'s 512-bit half-select is gone with lineReg; the bypass is
    // already the exact 64-bit window REPLAY will deliver.
    val s1Window = Mux(s1FromMiss, bypWindow, ufaDataVec(s1Way).subdivideIn(64 bits)(s1Lane))
```
and delete `missDataBeat` and `s1Beat`.

- [ ] **Step 7: Run the directed tests**
```bash
sbt "testOnly m68k040.cache.IcachePrefetchSpec -- -z 'byte-identical'"
sbt "testOnly m68k040.cache.IcacheSpec -- -z 'correct predecode WINDOW'"
sbt "testOnly m68k040.cache.IcacheOrderOracleSpec"
```
Expected: all PASS. Oracle 1 is the critical one here — merging `PF_PRED` into `PREDECODE` changes when the fetch port is open, which is exactly the ordering contract's boundary.

- [ ] **Step 8: Full correctness sweep**
```bash
grep -n "lineReg\|pfLineLo\|pfLineHi\|PF_PRED\|missDataBeat" src/main/scala/m68k040/cache/IcachePlugin.scala
# expect: NO MATCHES
make test-fast
sbt "testOnly m68k040.cache.IcacheOrderOracleSpec m68k040.cache.IcacheUnifiedArraySpec"
<GC4-SUITES>
<LOCKSTEP>
```
Expected: zero grep matches; 149/149; oracles green; GC-4 green; 396/396.

- [ ] **Step 9: Commit**
```bash
git add src/main/scala/m68k040/cache/IcachePlugin.scala src/test/scala/m68k040/cache/IcachePrefetchSpec.scala
git commit -m "icache(M2b): unified MSHR line file; delete lineReg and the PF_PRED install state

-512 CLB flops (lineReg) and -448 more from the narrowed bypass (bypWindow 64 +
bypPred 32 replace lineReg's 512 + missPred's 256, the latter already done in M2a).

lineReg's D-input was a 512-bit mux between an AXI beat and a 512-bit ASYNC READ of a
5-entry LUTRAM, with an IDLE clock enable of pfInstallAny && !demandFillStart -- a
five-way reduction over four slot-state vectors AND-ed with a function of
cmdPort.fire. It is the checkpoint-forensics diagnostic's named landing zone for BOTH
the B3 and P2 regressions AND the baseline's tied runner-up worst path
(FetchAlignPlugin_stalled_reg/C -> lineReg_reg[418]/D, -1.472 ns, tying nominal WNS
to three decimals). This commit deletes it.

Demand and speculative fills now share one AXI-ID-indexed line file written uniformly
from the R channel -- the RID IS the index (I_DEMAND == 0, I_SPEC_BASE == 1), so
there is no decode and no copy. PF_PRED merges into PREDECODE, parameterised by a
registered predIsPfReg. A new one-cycle INSTALL_ARM state registers installIdx so the
file read is synchronous: +1 cycle on a ~78-cycle DDR-bound demand refill (1.3%%),
-1 cycle on the speculative install path, which no longer needs the IDLE copy.

SG-3: installSet is a dedicated register rather than mshrSet(installIdx), because the
D3-SET-I guard it feeds sits in the ACCEPT cone and a 6:1 Vec mux there would work
against the whole point of this design.

Pinned by a new two-pass test proving a speculatively installed line is byte-identical
to a demand-installed one in both data and predecode -- the exact property the deleted
copy path existed to provide.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 9: M2c — the 6-entry uniform MSHR control file

Spec §5.2: "the per-slot control state generalised from 5 to 6 entries (`mshrValid`, `mshrArSent`, `mshrComplete`, `mshrBeat`, `mshrErr`, `mshrPoison`, `mshrPa`, `mshrSet`, `mshrTag`, `mshrWay`), with entry 0's semantics carrying what `beatCnt`/`arSent`/`missBusFault`/`missPoison`/`missPA`/`missSet`/`missTag`/`victimWay` carry today."

**Honest accounting, stated rather than buried:** this task is roughly **flop-neutral to slightly flop-positive** (spec: "Added: … ~66 FF of sixth-MSHR control state"). Its value is in §9.5's fallback column — deleting the demand/speculative asymmetry in the file the ledger has repeatedly found hardest to reason about — not in the flop count. It is included because the spec specifies it; a reviewer who wants to reject it on FMax grounds has no basis to, because GC-1 forbids measuring FMax here.

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala` (`:190-215`, `:262`, `:269-281`, `:866-873`, `:878-918`, `:922-965`, `:837-863`)
- Modify: `src/test/scala/m68k040/cache/IcachePrefetchSpec.scala` (`pfValid`/`pfArSent`/`pfComplete` accessors)
- Modify: `src/test/scala/m68k040/cache/IcacheOrderOracleSpec.scala` (oracle 3 extends to entry 0)

**Interfaces:**
- Consumes: `MSHR_N`, `mshrIdxBits`, `installIdx`, `installSet` (Task 8).
- Produces, for Tasks 11/12:
  - `mshrValid, mshrArSent, mshrComplete, mshrBeat, mshrErr, mshrPoison: Vec[Bool]` — each `Vec.fill(MSHR_N)(RegInit(False))`.
  - `mshrPa: Vec[UInt]` (32 bits), `mshrSet: Vec[UInt]` (`setBits`), `mshrTag: Vec[UInt]` (`tagBits`), `mshrWay: Vec[UInt]` (`wayBits`) — each `Vec.fill(MSHR_N)`.
  - `DEMAND_IDX: Int = AxiIds.I_DEMAND` (0). **Every former `miss*`/`beatCnt`/`arSent` reference becomes `mshr*(DEMAND_IDX)`.**
  - Test-side compatibility shims in `IcacheArrayProbe`: `pfSlotValid(ic, i)`, `pfSlotArSent(ic, i)`, `pfSlotComplete(ic, i)`, `pfSlotSet(ic, i)` — mapping speculative slot `i` to MSHR entry `i + AxiIds.I_SPEC_BASE`, so `IcachePrefetchSpec`'s existing assertions survive one rename in one place.

- [ ] **Step 1: Write the failing test — extend oracle 3 to cover the demand entry**
Modify oracle 3 in `IcacheOrderOracleSpec.scala`:
```scala
          // Task 9 (M2c): demand is now MSHR entry 0, an ordinary member of the file.
          // The "one fill owner per set" invariant (IcachePlugin.scala:840-865) must
          // hold across ALL SIX entries, not just the five speculative ones -- and
          // making demand a peer is exactly when it can silently break.
          val live = (0 until MSHR_N_TEST).filter(i =>
            IcacheArrayProbe.mshrValid(dut.icache, i))
          val sets = live.map(i => IcacheArrayProbe.mshrSet(dut.icache, i))
          if (sets.distinct.length != sets.length) {
            violations += 1
            simFailure(
              s"ORACLE 3 VIOLATED: live MSHR entries $live own sets $sets -- two fill " +
              s"owners for one set. Entry 0 is the DEMAND MSHR; a duplicate with a " +
              s"speculative entry means the same line can be installed into two ways " +
              s"of one set.")
          }
```
with `private val MSHR_N_TEST = 1 + AxiIds.I_SPEC_SLOTS` at the top of the suite, and add to `IcacheArrayProbe`:
```scala
  /** MSHR control-file accessors (Task 9). Entry 0 is the demand MSHR
    * (AxiIds.I_DEMAND); entries 1..I_SPEC_SLOTS are the speculative slots. */
  def mshrValid(ic: IcachePlugin, idx: Int): Boolean = ic.logic.mshrValid(idx).toBoolean
  def mshrSet(ic: IcachePlugin, idx: Int): Int       = ic.logic.mshrSet(idx).toInt
  def mshrArSent(ic: IcachePlugin, idx: Int): Boolean   = ic.logic.mshrArSent(idx).toBoolean
  def mshrComplete(ic: IcachePlugin, idx: Int): Boolean = ic.logic.mshrComplete(idx).toBoolean

  /** Speculative-slot views, so IcachePrefetchSpec's existing per-slot assertions
    * survive the demand/speculative unification with one rename in one place. */
  def pfSlotValid(ic: IcachePlugin, i: Int): Boolean    = mshrValid(ic, i + AxiIds.I_SPEC_BASE)
  def pfSlotArSent(ic: IcachePlugin, i: Int): Boolean   = mshrArSent(ic, i + AxiIds.I_SPEC_BASE)
  def pfSlotComplete(ic: IcachePlugin, i: Int): Boolean = mshrComplete(ic, i + AxiIds.I_SPEC_BASE)
  def pfSlotSet(ic: IcachePlugin, i: Int): Int          = mshrSet(ic, i + AxiIds.I_SPEC_BASE)
```

- [ ] **Step 2: Run and confirm failure**
```bash
sbt "testOnly m68k040.cache.IcacheOrderOracleSpec -- -z 'oracle 3'" 2>&1 | tail -20
```
Expected: FAIL to compile — `value mshrValid is not a member of ...`.

- [ ] **Step 3: Declare the unified control file**
Replace the speculative slot vectors (`IcachePlugin.scala:269-278`) with:
```scala
    // ---- M2c: the unified MSHR control file ---------------------------------
    // Entry 0 = the demand MSHR (AxiIds.I_DEMAND). Entries 1..pfSlots = the
    // speculative slots (AxiIds.I_SPEC_BASE..I_SPEC_LAST). Every field that used to
    // exist twice -- once as a scalar `miss*`/`beatCnt`/`arSent` for demand and once
    // as a pfSlots-wide Vec for speculation -- is one Vec now, and the AXI RID is
    // the index into it.
    //
    // Honest accounting (spec section 5.2): this is roughly flop-NEUTRAL (+~66 FF for
    // the sixth entry). Its value is in spec section 9.5's fallback column -- deleting
    // the demand/speculative asymmetry from the file the ledger has repeatedly found
    // hardest to reason about -- not in a flop count.
    val DEMAND_IDX  = AxiIds.I_DEMAND      // 0
    val mshrValid    = Vec.fill(MSHR_N)(RegInit(False))
    val mshrArSent   = Vec.fill(MSHR_N)(RegInit(False))
    val mshrComplete = Vec.fill(MSHR_N)(RegInit(False))
    val mshrBeat     = Vec.fill(MSHR_N)(RegInit(False))
    val mshrErr      = Vec.fill(MSHR_N)(RegInit(False))
    val mshrPoison   = Vec.fill(MSHR_N)(RegInit(False))
    val mshrPa       = Vec.fill(MSHR_N)(Reg(UInt(32 bits)))
    val mshrSet      = Vec.fill(MSHR_N)(Reg(UInt(setBits bits)))
    val mshrTag      = Vec.fill(MSHR_N)(Reg(UInt(tagBits bits)))
    val mshrWay      = Vec.fill(MSHR_N)(Reg(UInt(wayBits bits)))
    mshrValid.simPublic(); mshrArSent.simPublic(); mshrComplete.simPublic()
    mshrSet.simPublic()
```

- [ ] **Step 4: Mechanical substitution, with the two SG-3 exceptions**
Perform these renames throughout the file:

| Old | New | Notes |
|---|---|---|
| `pfValid(i)` | `mshrValid(i + AxiIds.I_SPEC_BASE)` | in the allocator/free/AR loops, which iterate `0 until pfSlots` |
| `pfArSent(i)` / `pfComplete(i)` / `pfBeat(i)` / `pfErr(i)` / `pfPoison(i)` | `mshrArSent(...)` etc. | same offset |
| `pfPa(i)` / `pfSet(i)` / `pfTag(i)` / `pfWay(i)` | `mshrPa(...)` etc. | same offset |
| `beatCnt` | `mshrBeat(DEMAND_IDX)` | a `Bool` now, not `UInt(1 bits)`; `beatCnt + 1` becomes `:= !mshrBeat(DEMAND_IDX)`, `beatCnt === U(0,1 bits)` becomes `!mshrBeat(DEMAND_IDX)`, `beatCnt.asBool` becomes `mshrBeat(DEMAND_IDX)` |
| `arSent` | `mshrArSent(DEMAND_IDX)` | |
| `missBusFault` | `mshrErr(DEMAND_IDX)` | |
| `missPoison` | `mshrPoison(DEMAND_IDX)` | |
| `missPA` | `mshrPa(DEMAND_IDX)` | |
| `missTag` | `mshrTag(DEMAND_IDX)` | |
| `victimWay` | `mshrWay(DEMAND_IDX)` | **but see below** |

**SG-3 exceptions — two comparators stay on dedicated registers, NOT on Vec reads:**
1. `installSet` (Task 8) already covers `fillArrayWrActive && (lookupSet === installSet)` — leave it.
2. **`missSet` becomes `installSet` at the dwell's array-write addresses too**, not `mshrSet(DEMAND_IDX)`: the dwell writes `lineMem(w).write((installSet ## commitBeat).asUInt, ...)`, `tagMem(w).write(installSet, ...)`, `valids(w)(installSet) := True`, `pfFilled(w)(installSet) := ...`, `victim(installSet) := victim(installSet) + 1`. This is strictly correct — `installSet` was loaded from exactly the entry being installed — and it keeps a 6:1 mux out of five array-write address paths. **`missSet` is deleted.**
3. Likewise **`victimWay` stays a dedicated register** rather than becoming `mshrWay(installIdx)`: it feeds a 4-way decoded `when(victimWay === U(w, ...))` on every array-write path. Rename it `installWay` and load it in the same places `installSet` is loaded. `mshrWay` still exists (the allocator records the victim at allocate time) and `installWay := mshrWay(chosen)` happens once, at `INSTALL_ARM` entry.
4. `missPC` and `missCacheable` stay as dedicated demand-only registers (they have no speculative counterpart — a speculative fill has no PC to replay and is cacheable by construction, rule P1). **Document this in a comment** rather than inventing per-entry fields nothing reads.

Record all four exceptions in the commit message as deliberate deviations from spec §5.2's literal text.

- [ ] **Step 5: Unify the R-channel handler**
Replace `IcachePlugin.scala:922-965` with a single index-driven body:
```scala
    // ---- M2c: one R-channel handler. No demand/speculative branch. ----
    val rIdx   = axi.r.payload.id.resize(mshrIdxBits)
    val rIsDem = axi.r.payload.id === U(AxiIds.I_DEMAND, AxiIds.ID_W bits)
    val rIsPf  = (axi.r.payload.id >= U(AxiIds.I_SPEC_BASE, AxiIds.ID_W bits)) &&
                 (axi.r.payload.id <= U(AxiIds.I_SPEC_LAST, AxiIds.ID_W bits))
    // Entry 0's "live" condition is the FSM's refillActive/arSent, not mshrValid --
    // the demand path's liveness is owned by the FSM and must stay that way (an
    // mshrValid(0) would be a second, redundant source of truth for it).
    val demandRspMatch = rIsDem && refillActive && mshrArSent(DEMAND_IDX)
    val pfRspMatch     = rIsPf && mshrValid(rIdx) && mshrArSent(rIdx) && !mshrComplete(rIdx)
    val rOwned         = demandRspMatch || pfRspMatch
    axi.r.ready := rOwned

    when(axi.r.valid) { assert(rOwned, "I-cache R beat has no live MSHR owner") }

    val rFire = axi.r.fire && rOwned
    fillLo.write(rIdx, axi.r.payload.data, enable = rFire && !mshrBeat(rIdx))
    fillHi.write(rIdx, axi.r.payload.data, enable = rFire &&  mshrBeat(rIdx))

    when(rFire) {
      val respErr = axi.r.payload.resp =/= Axi4.resp.OKAY
      // Generalised from the two per-path assertions at the old :942 and :956.
      assert(axi.r.payload.last === mshrBeat(rIdx),
        "I-cache refill must be exactly two beats")
      when(respErr) { mshrErr(rIdx) := True }
      mshrBeat(rIdx) := !mshrBeat(rIdx)
      when(axi.r.payload.last) {
        when(rIsDem) {
          refillDone := True
          refillErr  := mshrErr(DEMAND_IDX) || respErr
        } otherwise {
          mshrComplete(rIdx) := True
        }
      }
    }
```

- [ ] **Step 6: Update the test-side accessors**
```bash
grep -n "pfValid\|pfArSent\|pfComplete\|pfSet" src/test/scala/m68k040/cache/IcachePrefetchSpec.scala
```
Every hit becomes `IcacheArrayProbe.pfSlot*(dut.icache, i)`. Then update oracle 3 (Step 1's code) to iterate all `MSHR_N_TEST` entries.

- [ ] **Step 7: Run the tests**
```bash
sbt "testOnly m68k040.cache.IcacheOrderOracleSpec"
sbt "testOnly m68k040.cache.IcachePrefetchSpec"
```
Expected: all PASS, including the extended oracle 3.

If oracle 3 now fails with entry 0 duplicating a speculative entry's set, that is a **real bug the unification exposed**, not a test problem: the allocator's `demandSetOwned` guard (`:837-839`) must cover the demand entry's set. Check that it still reads the demand path's set correctly after the `missSet` → `installSet` rename — during `REFILL` the demand set lives in `mshrSet(DEMAND_IDX)`, and `installSet` is only loaded at `INSTALL_ARM`. **`demandSetOwned` must read `mshrSet(DEMAND_IDX)`, not `installSet`.**

- [ ] **Step 8: Full correctness sweep**
```bash
grep -n "pfValid\|pfArSent\|pfComplete\|pfBeat\|pfErr\|pfPoison\|pfPa\|pfSet\|pfTag\|pfWay\|beatCnt\|missBusFault\|missPoison\|missPA\|missTag\|missSet\|victimWay" src/main/scala/m68k040/cache/IcachePlugin.scala
# expect: NO MATCHES (missPC and missCacheable survive, deliberately)
make test-fast
sbt "testOnly m68k040.cache.IcacheOrderOracleSpec m68k040.cache.IcacheUnifiedArraySpec"
<GC4-SUITES>
<LOCKSTEP>
```
Expected: only `missPC`/`missCacheable` remain of the old names; 149/149; oracles green; GC-4 green; 396/396.

- [ ] **Step 9: Commit**
```bash
git add src/main/scala/m68k040/cache/IcachePlugin.scala \
        src/test/scala/m68k040/cache/IcacheArrayProbe.scala \
        src/test/scala/m68k040/cache/IcacheOrderOracleSpec.scala \
        src/test/scala/m68k040/cache/IcachePrefetchSpec.scala
git commit -m "icache(M2c): 6-entry uniform MSHR control file, demand is entry 0

Every field that existed twice -- once as a scalar miss*/beatCnt/arSent for demand,
once as a 5-wide Vec for speculation -- is one MSHR_N-wide Vec now, and the AXI RID is
the index into it (I_DEMAND == 0, I_SPEC_BASE == 1). The R-channel handler loses its
demand/speculative branch entirely: one .write per memory, one beat-count update, one
two-beat assertion.

HONEST ACCOUNTING: this is roughly flop-NEUTRAL (+~66 FF for the sixth entry). Its
value is spec section 9.5's fallback column -- deleting the demand/speculative
asymmetry from the file the ledger has repeatedly found hardest to reason about --
not a flop count. GC-1 forbids measuring its FMax effect here, deliberately.

FOUR DELIBERATE DEVIATIONS from spec section 5.2's literal text, all in service of
its own design principle P3 (keep deep verdicts out of accept-cone comparators):
  - installSet (a dedicated register) rather than mshrSet(installIdx) for the
    D3-SET-I guard AND for all five array-write address paths -- a 6:1 Vec mux in
    front of a comparator in the accept cone is exactly what this design removes;
  - installWay likewise, rather than mshrWay(installIdx), because it feeds a 4-way
    decoded when() on every array-write path;
  - demandSetOwned reads mshrSet(DEMAND_IDX), not installSet (installSet is only
    loaded at INSTALL_ARM, whereas the guard must hold from REFILL onward);
  - missPC and missCacheable stay demand-only scalars -- a speculative fill has no PC
    to replay and is cacheable by construction (rule P1), so per-entry fields for them
    would be state nothing reads.

Oracle 3 extended to all six entries: making demand a peer of the speculative slots is
exactly when the 'one fill owner per set' invariant can silently break.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 10: M3a — S0 context capture + shadow S1 verdict, with an in-RTL equivalence assertion

Spec §5.3, "the pipeline restructuring, and the highest-risk move in the document."

**PLAN DECISION (staging):** M3 lands in two commits — build the registered verdict path **in parallel with** the live one and assert bit-equivalence in RTL across the whole suite (this task), then flip the consumers over and delete the live path (Task 11). Reasoning: risk R3's failure mode is silent, and the shadow assertion turns "did I get the registered verdict right?" into a question the existing 396-test lock-step suite answers directly, before any consumer depends on it.

**This task is behaviour-neutral by construction.** `cmdPort.ready`, the response path, the MSHR writes, and the prefetch/AR paths all still read the live `isHit`. Only new registers and a new assertion are added.

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala` (`:180-188`, `:309-334`, `:452-472`, `:642-717`)

**Interfaces:**
- Consumes: `lookupPc`, `lookupSet`, `lookupPaddr`, `lookupFault`, `lookupCmode`, `tagMem`, `valids`.
- Produces, for Tasks 11/12 — **these exact names are used unchanged there:**
  - `s0Valid: Bool` — `RegInit(False)`, set on `cmdPort.fire`, cleared otherwise.
  - `s0Pc: UInt(32 bits)`, `s0Set: UInt(setBits bits)`, `s0Beat: Bool`, `s0Lane: UInt(2 bits)` — page-invariant virtual context.
  - `s0Ppn: UInt(20 bits)` — **the ITLB result STOPS HERE.**
  - `s0Fault: Bool`, `s0Cacheable: Bool` — the rest of the registered translation verdict.
  - `tagQ: Vec[UInt]` — `Reg(Vec(UInt(tagBits bits), ways))`, the registered `tagMem.readAsync(lookupSet)` (spec §14 Q1's **recommended default**: registered async read, not `readSync`; matches NaxRiscv's `tagsReadAsync = withDistributedRam`, §4.3 N-1).
  - `validsQ: Vec[Bool]` — `Reg(Vec(Bool(), ways))`, the registered per-way valid bit for `lookupSet`.
  - `s1HitVec: Vec[Bool]` — `s0Cacheable && validsQ(w) && (tagQ(w) === s0Ppn)`, computed at S1 from registered inputs only.
  - `s1Hit: Bool` — `s1HitVec.orR`.
  - `s1Unresolved: Bool` — `s0Valid && !s0Fault && !s1Hit`.
  - `dbgVerdictMatch: Bool` (`simPublic`) — the shadow-equivalence signal; **deleted in Task 11.**

- [ ] **Step 1: Write the failing test — the shadow verdict must equal the live verdict, always**
Create `src/test/scala/m68k040/cache/IcacheVerdictShadowSpec.scala`:
```scala
package m68k040.cache

import m68k040.{M68kParams, VerilatorTest}
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.services.TranslationService
import m68k040.core.ParamPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** M3a shadow check: the S1 verdict computed from REGISTERED inputs (tagQ, validsQ,
  * s0Ppn, s0Cacheable) must equal the live accept-cycle verdict (isHit), delayed one
  * cycle, on every accepted command.
  *
  * WHY A SHADOW. Risk R3: M3 restructures the accept/verdict boundary that guarantees
  * the in-order response contract, and a violation is SILENT. Building the registered
  * path in parallel and asserting equivalence before any consumer depends on it turns
  * "did I get the registered verdict right?" into a question the existing suites
  * answer, instead of a question a post-flip debugging session answers.
  *
  * Deleted in Task 11 together with dbgVerdictMatch and the live comparator.
  */
class IcacheVerdictShadowSpec extends AnyFunSuite {

  class Dut(xlateFactory: => FiberPlugin with TranslationService = new IdentityTranslationPlugin)
      extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = xlateFactory
    val icache = new IcachePlugin
    val probe  = new FetchProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, icache, probe)) }
  }

  private def compiled = SimConfig.withVerilator.compile(new Dut())

  /** Drive `addrs` and require dbgVerdictMatch to be high on every cycle s0Valid is. */
  private def sweep(name: String, addrs: Seq[Long], prefetch: Boolean): Unit = {
    compiled.doSim(name) { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x8000)
      dut.icache.logic.prefetchEnable #= prefetch
      dut.clockDomain.waitSampling(5)

      var checked = 0
      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.icache.logic.s0Valid.toBoolean) {
            checked += 1
            if (!dut.icache.logic.dbgVerdictMatch.toBoolean)
              simFailure(
                "M3a SHADOW VIOLATED: the S1 verdict computed from registered inputs " +
                "(tagQ/validsQ/s0Ppn/s0Cacheable) disagrees with the live accept-cycle " +
                "isHit, delayed one cycle. M3 cannot flip until these are identical.")
          }
        }
      }

      IcacheOrderOracle.runOrderedStream(
        cmdValid = dut.probe.cmdIn.valid, cmdReady = dut.probe.cmdIn.ready,
        cmdPc    = dut.probe.cmdIn.payload.pc,
        rspValid = dut.probe.rspOut.valid, rspPc = dut.probe.rspOut.payload.pc,
        cd = dut.clockDomain, addrs = addrs, timeoutCycles = 120000)
      dut.clockDomain.waitSampling(200)
      assert(checked >= addrs.length,
        s"only $checked cycles had s0Valid high for ${addrs.length} commands -- the " +
        s"shadow was barely exercised, so it proved nothing")
    }
  }

  test("M3a shadow: sequential misses and hits, prefetch on", VerilatorTest) {
    sweep("shadow-seq-pf-on", (0 until 128).map(i => 0x1000L + i * 64), prefetch = true)
  }

  test("M3a shadow: same-set thrash forcing evictions, prefetch off", VerilatorTest) {
    // 0x1000, 0x2000, 0x3000, 0x4000, 0x5000 all map to set 0 (set = pc(11:6)).
    // Five distinct tags into a 4-way set guarantees continuous eviction, so validsQ
    // and tagQ are churning under the shadow the whole time.
    val thrash = (0 until 40).flatMap(i =>
      Seq(0x1000L, 0x2000L, 0x3000L, 0x4000L, 0x5000L).map(_ + (i % 8) * 8))
    sweep("shadow-thrash-pf-off", thrash, prefetch = false)
  }

  test("M3a shadow: repeated hits in one line (the II=1 case)", VerilatorTest) {
    sweep("shadow-hits", (0 until 200).map(i => 0x1000L + (i % 8) * 8), prefetch = false)
  }
}
```

- [ ] **Step 2: Run and confirm failure**
```bash
sbt "testOnly m68k040.cache.IcacheVerdictShadowSpec" 2>&1 | tail -20
```
Expected: FAIL to compile — `value s0Valid is not a member of ...`.

- [ ] **Step 3: Add the S0 context registers**
Replace the S1 register declarations (`IcachePlugin.scala:309-334`) with the S0 set. **The old `s1*` names are RENAMED, not duplicated** — spec §5.3.3: "the `s1*` register set is *renamed* into the `s0*` set and loses `s1Way`". At this task `s1Way` survives (Task 11 removes it), so:
```scala
    // ---- S0: the accept-cycle capture stage (M3) ------------------------------
    // The ITLB result STOPS HERE. Nothing downstream of this register reads
    // xlate.rsp.* -- that is the whole content of design principle P3, and the reason
    // the census's 1,751 clock-enable endpoints in this plugin can stop being fed by a
    // translation-fed comparator.
    val s0Valid = RegInit(False)
    val s0Pc    = Reg(UInt(32 bits))
    val s0Set   = Reg(UInt(setBits bits))       // virtual, page-invariant
    val s0Beat  = Reg(Bool())                   // pc(5), virtual
    val s0Lane  = Reg(UInt(2 bits))             // pc(4:3), virtual
    val s0Ppn   = Reg(UInt(tagBits bits))       // <-- the translation terminates here
    val s0Fault = Reg(Bool())
    val s0Atc   = Reg(Bool())
    val s0Cacheable = Reg(Bool())
    val s0FromMiss  = Reg(Bool())
    // Spec section 14 Q1's RECOMMENDED DEFAULT, and NaxRiscv's own FPGA choice
    // (tagsReadAsync = withDistributedRam, section 4.3 N-1): keep tagMem as
    // distributed RAM and REGISTER its async read, rather than converting it to a
    // synchronous memory. Same "compare registered vs registered" property, 80 flops,
    // no BRAM-latency question on the tag path, trivially reviewable diff.
    val tagQ    = Reg(Vec(UInt(tagBits bits), ways))
    val validsQ = Reg(Vec(Bool(), ways))
    s0Valid := False   // default each cycle; armed on cmdPort.fire / REPLAY / FAULT
    s0Valid.simPublic()

    // Transitional (deleted in Task 11): the registered way, still driving the S1 mux.
    val s1Way = Reg(UInt(wayBits bits))
```
Then mechanically rename every `s1Valid` → `s0Valid`, `s1Pc` → `s0Pc`, `s1Fault` → `s0Fault`, `s1Atc` → `s0Atc`, `s1Lane` → `s0Lane`, `s1FromMiss` → `s0FromMiss` at their existing assignment and read sites (`:433-438`, `:678-694`, `:752-759`, `:799-824`).

> **Naming note, load-bearing:** the register set is named `s0*` because it is written **in** the accept cycle and read **in** the next cycle. It is the same physical register the old `s1*` names described from the consumer's point of view. Renaming it now, before Task 11 adds a genuinely new S1 combinational stage, prevents the plan's worst naming hazard: two different things both called "s1".

- [ ] **Step 4: Capture the translation, tags and valids at S0**
Inside `lookupTick`, in the `when(cmdPort.fire)` block, add — **before** the existing fault/hit/miss `when` chain, so it runs unconditionally on every accepted command:
```scala
        when(cmdPort.fire) {
          // ---- M3 S0 capture: unconditional, one shallow enable, page-invariant
          //      address bits plus the ONE cycle the translation is allowed to be live.
          s0Pc        := lookupPc
          s0Set       := lookupSet
          s0Beat      := lookupPc(5)
          s0Lane      := lookupLaneIdx
          s0Ppn       := lookupTag
          s0Cacheable := lookupCacheable
          for (w <- 0 until ways) {
            tagQ(w)    := tagMem(w).readAsync(lookupSet)
            validsQ(w) := valids(w)(lookupSet)
          }
          // ... existing fault / isHit / miss chain, unchanged this task ...
        }
```

- [ ] **Step 5: Compute the shadow verdict and assert equivalence**
Add, immediately after the `hitVec`/`isHit` block (`IcachePlugin.scala:458-463`):
```scala
    // ---- M3 S1: the verdict, from REGISTERED inputs only ----------------------
    // 20-bit equality against a registered PPN, a 4-way OR, and an AND with s0Valid.
    // ~3 LUT levels off flop outputs -- versus the live path's ITLB mux -> async
    // LUTRAM read -> compare -> OR -> answerable, which is the 20-level, 66%-route arc
    // that synth/floorplan_frontend.xdc names as all 300 worst failing endpoints.
    val s1HitVec = Vec((0 until ways).map(w =>
      s0Cacheable && validsQ(w) && (tagQ(w) === s0Ppn)))
    val s1Hit    = s1HitVec.orR
    // Held high while an accepted command's miss has not yet been dispatched to a
    // fill. Task 11 makes this gate cmdPort.ready; this task only computes it.
    val s1Unresolved = s0Valid && !s0Fault && !s1Hit

    // ---- Task 10 (M3a) TRANSITIONAL shadow check -------------------------------
    // The registered verdict must equal the live one, delayed a cycle, on every
    // accepted command. Deleted in Task 11 with the live comparator.
    val dbgLiveHitQ    = RegNextWhen(isHit, cmdPort.fire) init False
    val dbgLiveFaultQ  = RegNextWhen(lookupFault, cmdPort.fire) init False
    val dbgVerdictMatch = Bool()
    dbgVerdictMatch := !s0Valid || dbgLiveFaultQ || (s1Hit === dbgLiveHitQ)
    dbgVerdictMatch.simPublic()
```
> The `dbgLiveFaultQ` exemption is deliberate and must be understood, not copied blindly: on a **translation fault** the live path never evaluates `isHit` meaningfully (it takes the `when(lookupFault)` arm first, and `s1PredEntries`/way were forced to a placeholder). The registered path's `s1Hit` may legitimately be either value there because `tagQ`/`validsQ` were captured anyway. Task 11's consumers all gate on `!s0Fault` for the same reason. **If the implementer finds a case where a fault and a hit must be distinguished downstream, that is a SPEC GAP to raise, not to paper over.**

- [ ] **Step 6: Prove the S0-capture invalidate window is unchanged (SG-4)**
`validsQ` is captured at S0 while `invalidateAll` can clear `valids` between S0 and S1. Add this directed test to `IcacheVerdictShadowSpec`:
```scala
  test("SG-4: an invalidateAll landing between S0 and S1 leaves the accepted fetch's " +
       "verdict identical to pre-M3 behaviour", VerilatorTest) {
    compiled.doSim("sg4-invalidate-window") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x2000)
      dut.clockDomain.waitSampling(5)
      fetchAndWait(dut, 0x1000L)          // make it resident
      dut.clockDomain.waitSampling(10)

      // Accept a fetch to the resident line, then pulse invalidateAll on the very next
      // cycle -- i.e. between S0 and S1.
      //
      // EXPECTED, and this is the whole point: the verdict is UNCHANGED from today.
      // `valids` is a register array, so a clear issued at cycle N takes effect for
      // cycle N+1. Today's live path reads valids at cycle N (the accept cycle) and
      // sees the OLD value; M3's validsQ is captured at cycle N and holds the same OLD
      // value. The observable window is identical -- M3 neither opens nor closes one.
      // CINV/CPUSH is architecturally a software synchronisation point
      // (IcachePlugin.scala:239-261), so a fetch already accepted when the invalidate
      // lands is entitled to its data.
      dut.probe.cmdIn.payload.pc #= 0x1000L
      dut.probe.cmdIn.valid #= true
      dut.clockDomain.waitSamplingWhere(dut.probe.cmdIn.ready.toBoolean)
      dut.probe.cmdIn.valid #= false
      dut.icache.logic.invalidateAll #= true
      dut.clockDomain.waitSampling()
      dut.icache.logic.invalidateAll #= false

      assert(dut.icache.logic.dbgVerdictMatch.toBoolean,
        "SG-4: the registered verdict diverged from the live one across an " +
        "invalidateAll landing between S0 and S1")
      // And the response must still arrive -- a dropped response wedges FetchAlign's
      // ring permanently (IcachePlugin.scala:248-261).
      var sawRsp = false
      for (_ <- 0 until 200 if !sawRsp) {
        dut.clockDomain.waitSampling()
        if (dut.probe.rspOut.valid.toBoolean) sawRsp = true
      }
      assert(sawRsp, "the accepted fetch never got a response after a mid-flight invalidateAll")
    }
  }
```

- [ ] **Step 7: Run the shadow suite**
```bash
sbt "testOnly m68k040.cache.IcacheVerdictShadowSpec" 2>&1 | tail -30
```
Expected: all 4 tests PASS.

**If the shadow diverges, DO NOT proceed to Task 11.** The two most likely causes, in order:
1. `tagQ`/`validsQ` captured under a different enable than `cmdPort.fire` (e.g. inside a conditional arm) — they must be captured on **every** accepted command;
2. `s0Ppn := lookupTag` vs `lookupPaddr(31 downto 12)` mismatch — `lookupTag` is `lookupPaddr(31 downto 12)` (`:457`), i.e. exactly `tagBits` wide; confirm the widths agree.

- [ ] **Step 8: Full correctness sweep**
```bash
git diff -- src/main/scala/m68k040/cache/IcachePlugin.scala | grep -E "^\-" | grep -v "^---" | head -40
# Sanity: this task should DELETE almost nothing (it is additive + a rename)
make test-fast
sbt "testOnly m68k040.cache.IcacheOrderOracleSpec m68k040.cache.IcacheUnifiedArraySpec m68k040.cache.IcacheVerdictShadowSpec"
<GC4-SUITES>
<LOCKSTEP>
```
Expected: 149/149; all oracles and the shadow suite green; GC-4 green; 396/396.

- [ ] **Step 9: Commit**
```bash
git add src/main/scala/m68k040/cache/IcachePlugin.scala \
        src/test/scala/m68k040/cache/IcacheVerdictShadowSpec.scala
git commit -m "icache(M3a): S0 translation/tag/valid capture + shadow S1 verdict, equivalence proven

Behaviour-neutral by construction: cmdPort.ready, the response path, the MSHR writes
and the prefetch/AR paths all still read the LIVE isHit. This commit only adds the
registered path and asserts, in RTL, that it agrees with the live one on every
accepted command, across sequential misses, same-set thrash forcing continuous
eviction, and the repeated-hit II=1 case.

Risk R3's failure mode is SILENT -- mis-paired instruction bytes, not a crash -- so
the registered verdict is proven correct BEFORE any consumer depends on it, using the
suites that already exist rather than a post-flip debugging session.

tagMem stays distributed RAM with its async read REGISTERED (tagQ, 80 flops), which is
spec section 14 Q1's recommended default and NaxRiscv's own FPGA choice
(tagsReadAsync = withDistributedRam, section 4.3 N-1) -- same compare-registered-vs-
registered property, no BRAM-latency question on the tag path.

The s1* register set is RENAMED to s0*, not duplicated (spec section 5.3.3). It is
written IN the accept cycle and read the NEXT cycle; renaming now, before Task 11 adds
a genuinely new S1 combinational stage, avoids two different things both called 's1'.

SG-4 (a plan-identified spec gap): validsQ is captured at S0 while invalidateAll can
clear valids between S0 and S1. A directed test proves the observable window is
IDENTICAL to today's -- valids is a register array, so a clear at cycle N takes effect
for N+1, and today's live path also reads the pre-clear value at cycle N. M3 neither
opens nor closes a window here, and the accepted fetch still gets its response (a
dropped one wedges FetchAlign's ring permanently).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 11: M3b — flip to the S1 verdict; delete the live accept-cone comparator

This is the task that removes `cmdPort.fire`'s translation-fed comparator from the plugin. Spec §5.3, §5.3.1, §5.3.2.

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala` (`:419-438`, `:452-472`, `:642-717`, `:791-827`, `:837-863`)

**Interfaces:**
- Consumes: everything Task 10 produced.
- Produces, for Task 12:
  - `s1WayOh: Vec[Bool]` — the one-hot way select feeding the S1 output mux. `Mux(s0FromMiss || s0Replay, UIntToOh(installWay), s1HitVec)`.
  - `s1Miss: Bool` — `s0Valid && !s0Fault && !s1Hit`, i.e. `s1Unresolved` under a name that reads as a *disposition* rather than a *gate*. (**Same signal; one name.** Use `s1Unresolved` everywhere and do not introduce `s1Miss` as a second alias — noted here because the spec text uses both.)
  - `s0Accepting: Bool` — `fsmIdleish && !s1Unresolved && pfAcceptOk`.
  - `pfAcceptOk: Bool` — SG-1's page-invariant prefetch-fill accept gate.
- Deletes: `hitVec`, `isHit`, `hitWayIdx`, `lookupPredEntry`(already gone), `answerable`, `heldDemandMiss`'s live form, `missPC`/`missCacheable` capture at accept time, `s1Way`, `dbgVerdictMatch`, `dbgLiveHitQ`, `dbgLiveFaultQ`.

- [ ] **Step 1: Write the failing test — II=1 on hits must survive, and a miss must not admit a younger command**
Append to `src/test/scala/m68k040/cache/IcacheVerdictShadowSpec.scala`:
```scala
  // M3b: cmdPort.ready is now gated by s1Unresolved instead of by a live translation-
  // fed comparator. Two properties must survive that, and they pull in opposite
  // directions -- which is why both are tested.
  test("M3b: sustained II=1 on hits (ready never drops in a hit stream)", VerilatorTest) {
    compiled.doSim("m3b-ii1") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x2000)
      dut.icache.logic.prefetchEnable #= false
      dut.clockDomain.waitSampling(5)
      fetchAndWait(dut, 0x1000L)                 // resident
      dut.clockDomain.waitSampling(20)

      // Hold cmd.valid high on a resident line for 100 cycles and count accepts.
      dut.probe.cmdIn.payload.pc #= 0x1000L
      dut.probe.cmdIn.valid #= true
      var accepts = 0
      for (_ <- 0 until 100) {
        dut.clockDomain.waitSampling()
        if (dut.probe.cmdIn.ready.toBoolean) accepts += 1
      }
      dut.probe.cmdIn.valid #= false
      assert(accepts >= 95,
        s"only $accepts of 100 cycles accepted a command on a RESIDENT line. M3's " +
        s"s1Unresolved gate must be low on hits -- sustained II=1 is spec goal G2 and " +
        s"a regression here is a direct IPC loss on every straight-line fetch.")
    }
  }

  test("M3b: no younger command is accepted behind an unresolved miss", VerilatorTest) {
    compiled.doSim("m3b-no-overtake") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x8000)
      dut.icache.logic.prefetchEnable #= false
      dut.clockDomain.waitSampling(5)

      // Command A misses (cold line). Immediately offer command B to a DIFFERENT,
      // ALREADY-RESIDENT line. B must not be accepted until A's fill has resolved,
      // or its response would be attributed to A's ring entry.
      fetchAndWait(dut, 0x2000L)                 // make 0x2000 resident
      dut.clockDomain.waitSampling(20)

      dut.probe.cmdIn.payload.pc #= 0x5000L      // cold
      dut.probe.cmdIn.valid #= true
      dut.clockDomain.waitSamplingWhere(dut.probe.cmdIn.ready.toBoolean)  // A accepted
      dut.probe.cmdIn.payload.pc #= 0x2000L      // resident -- would HIT
      // A's miss is discovered at S1, i.e. the very next cycle. From that cycle until
      // the fill completes, ready must be low.
      var acceptedDuringMiss = 0
      var sawRsp = false
      for (_ <- 0 until 300 if !sawRsp) {
        dut.clockDomain.waitSampling()
        if (dut.probe.rspOut.valid.toBoolean) sawRsp = true
        else if (dut.probe.cmdIn.ready.toBoolean) acceptedDuringMiss += 1
      }
      dut.probe.cmdIn.valid #= false
      assert(sawRsp, "command A never got a response")
      assert(acceptedDuringMiss == 0,
        s"$acceptedDuringMiss younger commands were accepted while an older miss was " +
        s"unresolved. FetchRsp carries no tag and FetchAlignPlugin attributes by ring " +
        s"head (FetchAlignPlugin.scala:263,283), so this is silent instruction-byte " +
        s"mis-pairing -- risk R3, the most expensive failure mode in this file.")
    }
  }
```

- [ ] **Step 2: Run and confirm the no-overtake test's status**
```bash
sbt "testOnly m68k040.cache.IcacheVerdictShadowSpec -- -z M3b" 2>&1 | tail -20
```
Expected: both **PASS** on the pre-flip RTL (today's `answerable` already refuses a miss-then-younger accept, and today's hit path is already II=1). These are regression detectors for the flip. If either fails now, fix the test before changing RTL.

- [ ] **Step 3: SPEC GAP SG-1 — the prefetch-fill accept gate**

> **SG-1, stated in full because the spec does not answer it.** Today, during a prefetch install, `lookupTick(canStartFill = false)` computes
> `answerable = lookupFault || isHit || !pfLookupSetBusy`
> — i.e. it **uses the hit/fault verdict** to decide acceptance. Under M3 that verdict does not exist until S1, one cycle after acceptance. Accepting a command that turns out to be a miss while a speculative fill owns its set could allocate a second way for a line the prefetch is about to install — two valid ways with the same tag in one set, which makes `s1HitVec` 2-hot.
>
> **Resolution (plan decision, conservative and page-invariant):** during a fill install, gate acceptance on `!pfLookupSetBusy` **alone**, dropping the `lookupFault || isHit` disjuncts. `pfLookupSetBusy` is computed from `lookupSet` (virtual, page-invariant) against registered MSHR state (`IcachePlugin.scala:538-539`) — it needs no translation and no tag compare, so it is exactly the kind of term design principle P3 permits in the accept cone.
>
> **Cost:** strictly more conservative than today. A *hit* to a set that a speculative slot happens to own is now held for a few cycles instead of answered. That is an IPC cost, it is bounded by the install dwell (3 cycles), and Task 14's IPC gate measures it rather than assuming it.
>
> **Companion requirement:** the pf allocator's `demandSetOwned` guard (`:837-839`) must additionally cover an S1-unresolved miss's set, so a speculative slot cannot be allocated to a set an accepted-but-unresolved demand miss is about to claim.

Implement:
```scala
    // SG-1 (plan resolution; spec section 5.3 does not cover this). During a fill
    // install the fetch port may stay open (a speculative install produces no
    // response), but under M3 the hit/fault verdict is not available at accept time.
    // Gate on the PAGE-INVARIANT, REGISTERED-STATE term alone. Strictly more
    // conservative than today's `lookupFault || isHit || !pfLookupSetBusy`: a hit to a
    // speculatively-owned set is held for the install dwell instead of answered.
    // Bounded by 3 cycles; measured, not assumed, by the Task 14 IPC gate.
    val pfAcceptOk = Bool()
    pfAcceptOk := True     // default: IDLE imposes no such restriction
```
and inside `lookupTick`:
```scala
      def lookupTick(canStartFill: Boolean): Unit = {
        lookupActive := True
        val setBlocked = if (canStartFill) False
                         else (fillArrayWrActive && (lookupSet === installSet))
        ufaReadAddr := lookupReadAddr
        ufaReadEn   := cmdPort.valid && !setBlocked
        if (!canStartFill) pfAcceptOk := !pfLookupSetBusy

        // ---- M3: cmdPort.ready from REGISTERED state only. -------------------
        // No ITLB mux, no async LUTRAM read, no 20-bit comparator, no 4-way OR
        // upstream of a Stream ready that leaves the plugin. s1Unresolved is a 20-bit
        // equality against a registered PPN, a 4-way OR, and an AND -- ~3 LUT levels
        // off flop outputs. `xlate.rsp.ready` remains because a cold ITLB walk must
        // still hold the command (that is a translation-SERVICE handshake, not a
        // translation-fed verdict).
        cmdPort.ready := xlate.rsp.ready && !setBlocked && !s1Unresolved && pfAcceptOk
```

**Extend `demandSetOwned` (`:837-839`):**
```scala
    // M3/SG-1: an accepted-but-unresolved demand miss owns its set from S1 onward,
    // before the FSM has entered REFILL. Without this, a speculative slot could be
    // allocated to that set in the intervening cycle and both would fill it.
    val demandSetOwned = ((fsm.isActive(fsm.REFILL) || fsm.isActive(fsm.INSTALL_ARM) ||
                           fsm.isActive(fsm.PREDECODE) || fsm.isActive(fsm.REPLAY) ||
                           fsm.isActive(fsm.FAULT)) &&
                          (mshrSet(DEMAND_IDX) === pfCandSet)) ||
                         (s1Unresolved && (s0Set === pfCandSet))
```

- [ ] **Step 4: Move the miss dispatch from S0 to S1**
Delete the `otherwise` (miss) arm inside `when(cmdPort.fire)` (`:701-715`) entirely, and delete the `missPC`/`missCacheable`/`mshr*(DEMAND_IDX)` captures it performed. Replace with an S1-side dispatch, placed **outside** the FSM, next to the other hoisted datapath:
```scala
    // ---- M3: the miss dispatch, from S1, out of the ALREADY-REGISTERED s0 context.
    // Spec section 5.3.3: the separate miss-context capture (missPC/missPA/missSet/
    // missTag/missCacheable/victimWay, ~93 flops, enable cmdPort.fire && !fault &&
    // !hit) disappears -- the s0* registers already hold every one of those values,
    // and the enable is now the shallow `s1Unresolved && fsmIdle`.
    when(s1Unresolved && fsm.isActive(fsm.IDLE) && !fillArrayWrActive) {
      missPC                 := s0Pc
      missCacheable          := s0Cacheable
      mshrPa(DEMAND_IDX)     := (s0Ppn ## s0Pc(11 downto 0)).asUInt
      mshrSet(DEMAND_IDX)    := s0Set
      mshrTag(DEMAND_IDX)    := s0Ppn
      mshrWay(DEMAND_IDX)    := victim(s0Set)
      mshrBeat(DEMAND_IDX)   := False
      mshrArSent(DEMAND_IDX) := False
      mshrErr(DEMAND_IDX)    := False
      mshrPoison(DEMAND_IDX) := False
      demandFillStart        := True
      fsm.forceGoto(fsm.REFILL)
    }
```
> **Implementer note:** SpinalHDL's `StateMachine` transition from outside the FSM body — use whichever of `fsm.forceGoto(...)` / a `Bool` request wire consumed by `IDLE.whenIsActive { when(startDemandFill) { goto(REFILL) } }` actually elaborates in this SpinalHDL version. **The request-wire form is preferred** because it keeps every `goto` inside the FSM, which is this file's existing convention:
> ```scala
> val startDemandFill = Bool(); startDemandFill := False   // declared before the FSM
> // inside IDLE.whenIsActive, after lookupTick:
>   when(startDemandFill) { goto(REFILL) }
> // and the block above sets `startDemandFill := True` instead of calling forceGoto.
> ```

- [ ] **Step 5: SPEC GAP SG-2 — the S1 output mux for REPLAY and FAULT**

> **SG-2.** Spec §5.3's S1 sketch shows only the hit path (`oneHotMux(hitVec, ufaData)`). But `REPLAY` delivers a just-filled line from a **known** way (`installWay`), and `FAULT` delivers a placeholder with no way at all. The spec does not say how those reach the one-hot mux.
>
> **Resolution:** an explicit `s1WayOh` mux selecting `UIntToOh(installWay)` when the response is a replay, `s1HitVec` otherwise. This is one 4-bit 2:1 mux off registered state — it does not re-enter the accept cone.

Replace the S1→rsp stage (`:419-438`) with:
```scala
    // ---- S1 -> rsp output register (M3) ---------------------------------------
    // Per-way LANE PRE-SELECT runs IN PARALLEL with the tag compare (spec section
    // 5.3.2 device 1, NaxRiscv's BANKS_MUXES at section 4.3 N-2): s0Lane is registered
    // and page-invariant, so it does not wait on the verdict. The one-hot AND-OR is
    // then over 64 + 32 bits instead of 256 + 128 -- one fewer OR-tree level and a
    // quarter of the LUTs.
    val wayWindow = Vec((0 until ways).map(w => ufaData(w).subdivideIn(64 bits)(s0Lane)))
    // windowPredBeat takes a full PC and selects on pc(4:3); s0Lane IS s0Pc(4 downto 3)
    // (both are captured from lookupLaneIdx at S0), so these two lines select the same
    // window. Passing s0Pc here rather than s0Lane keeps windowPredBeat's one signature
    // shared with the bypass path -- do not "unify" them by changing the helper.
    val wayPred   = Vec((0 until ways).map(w => windowPredBeat(ufaPred(w), s0Pc).asBits))

    // SG-2 (plan resolution; spec section 5.3 sketches only the hit path). REPLAY and
    // FAULT deliver from a KNOWN way, not from a computed hitVec. One 4-bit 2:1 mux
    // off registered state -- it does not re-enter the accept cone.
    val s1WayOh = Vec((0 until ways).map(w =>
      Mux(s0Replay, installWay === U(w, wayBits bits), s1HitVec(w))))

    // OhMux.or rather than OHToUInt + a binary mux (spec section 5.3.2 device 2): the
    // one-hot vector is already available; encoding and re-decoding it is two
    // avoidable levels.
    def ohOr(sel: Vec[Bool], data: Vec[Bits]): Bits =
      data.zip(sel).map { case (d, s) => d & B(ways bits, default -> s).resize(d.getWidth) }
          .reduceBalancedTree(_ | _)

    val s1Window   = Mux(s0FromMiss, bypWindow, ohOr(s1WayOh, wayWindow))
    val s1PredBits = Mux(s0FromMiss, bypPred,   ohOr(s1WayOh, wayPred))
    val s1PredW    = Vec(Mux(s0Fault, B(0, s1PredBits.getWidth bits), s1PredBits)
                           .subdivideIn(PRED_BITS_PER_WORD bits)
                           .map(b => b.as(ChunkPredecode())))

    rspValidReg := s0Valid
    rspPcReg    := s0Pc
    rspDataReg  := s1Window
    rspFaultReg := s0Fault
    rspAtcReg   := s0Atc
    rspPredReg  := s1PredW
```
Add `val s0Replay = Reg(Bool())` to the S0 register set (Task 10 Step 3), set `True` in `REPLAY`/`FAULT`'s `s0Valid` arming and `False` in `lookupTick`'s.

> **Implementer note on `ohOr`:** the sketch above is illustrative of the *shape* (mask each way's data by its select bit, then a balanced OR-reduce). Write whichever SpinalHDL formulation elaborates correctly — e.g. `MuxOH(s1WayOh, wayWindow)` if this SpinalHDL version's `MuxOH` emits an AND-OR rather than an encoder. **Verify by reading the generated Verilog** that no `OHToUInt`/binary-mux appears in this cone; that verification is the point of the step, not the exact Scala.

- [ ] **Step 6: Delete the live comparator and the shadow scaffolding**
Delete from `IcachePlugin.scala`:
- `hitVec`, `isHit`, `hitWayIdx` (`:458-463`)
- `heldDemandMiss` (`:471-472`) — **Task 12 replaces its consumers; for this task, substitute `s1Unresolved` at its three use sites (`:840-841`, `:889`, `:896`) so the file compiles and behaviour is preserved.**
- `answerable` (`:657-660`)
- `s1Way` (Task 10's transitional register) and `ufaDataVec`/`ufaPredVec` (Task 5's transitional Vec-index helpers) — the per-way pre-select in Step 5 replaces both
- `dbgVerdictMatch`, `dbgLiveHitQ`, `dbgLiveFaultQ`
- The `when(pfFilled(hitWayIdx)(lookupSet))` telemetry arm (`:697-700`) → moves to S1: `when(s1Hit && pfFilledQ) { ... }` where `pfFilledQ` is captured at S0 alongside `validsQ`:
```scala
    val pfFilledQ = Reg(Vec(Bool(), ways))
    // in the S0 capture: pfFilledQ(w) := pfFilled(w)(lookupSet)
    // at S1:
    when(s0Valid && s1Hit) {
      for (w <- 0 until ways) when(s1HitVec(w) && pfFilledQ(w)) {
        pfFilled(w)(s0Set) := False
        pfHitUseful := True
      }
    }
```

Then delete the shadow tests from `IcacheVerdictShadowSpec` that referenced `dbgVerdictMatch` (the three `sweep` tests and the SG-4 test's `dbgVerdictMatch` assertion — **keep the SG-4 test**, re-pointing its assertion at "the response still arrives and carries the pre-invalidate data").

- [ ] **Step 7: Run the oracles and the M3b tests**
```bash
sbt "testOnly m68k040.cache.IcacheOrderOracleSpec"
sbt "testOnly m68k040.cache.IcacheVerdictShadowSpec"
```
Expected: all PASS. **Oracle 1 is the single most important check in this entire plan** — it is the in-order response contract that M3 restructures and whose violation is silent.

- [ ] **Step 8: Full correctness sweep**
```bash
grep -n "isHit\|hitWayIdx\|answerable\|heldDemandMiss\|s1Way\b\|dbgVerdictMatch" src/main/scala/m68k040/cache/IcachePlugin.scala
# expect: NO MATCHES
grep -n "xlate.rsp" src/main/scala/m68k040/cache/IcachePlugin.scala
# expect: ONLY the request wiring (:133-136), lookupPaddr/lookupFault/lookupCmode
# (:185-188), and cmdPort.ready's xlate.rsp.ready handshake. NOTHING downstream.
make test-fast
sbt "testOnly m68k040.cache.IcacheOrderOracleSpec m68k040.cache.IcacheUnifiedArraySpec m68k040.cache.IcacheVerdictShadowSpec"
<GC4-SUITES>
<LOCKSTEP>
```
Expected: zero matches for the deleted names; the `xlate.rsp` grep shows the translation reaching only the S0 capture; 149/149; all oracles green; GC-4 green; 396/396.

- [ ] **Step 9: Commit**
```bash
git add src/main/scala/m68k040/cache/IcachePlugin.scala \
        src/test/scala/m68k040/cache/IcacheVerdictShadowSpec.scala
git commit -m "icache(M3b): Verdict-Terminated Lookup -- cmdPort.ready off registered state only

The live ITLB way-mux output, async distributed-RAM read, 20-bit comparator, 4-way OR
and one-hot encoder are no longer upstream of a Stream ready that leaves the plugin,
nor of any clock enable in it. cmdPort.ready is now
xlate.rsp.ready && !setBlocked && !s1Unresolved && pfAcceptOk -- a 20-bit equality
against a REGISTERED ppn, a 4-way OR and an AND, ~3 LUT levels off flop outputs. This
is the ic.cmd.fire cone the census priced at 1,751 CE endpoints.

LATENCY-NEUTRAL, and that is the point: cmd accept -> rsp.valid stays 2 cycles and
hits stay II=1 (pinned by a directed 100-cycle resident-line test requiring >=95
accepts). The prior spec's P3 reached the same 'ITLB out of the compare' goal by
adding a real stage, which would force RING 3 -> 4 and re-open the H7 hazard. M3 costs
zero fetch latency and requires no FetchAlignPlugin change.

The separate miss context (~93 flops, enable cmdPort.fire && !fault && !hit) is gone:
the MSHR entry is written at S1 straight from the already-registered s0* context.

S1 uses the two reference devices from spec section 5.3.2: per-way LANE PRE-SELECT in
parallel with the compare (s0Lane is registered and page-invariant), so the one-hot
AND-OR is over 64+32 bits rather than 256+128; and OhMux.or rather than OHToUInt plus
a binary mux.

TWO PLAN-RESOLVED SPEC GAPS, both flagged rather than silently applied:
  SG-1  During a fill install, today's accept gate USES the verdict
        (lookupFault || isHit || !pfLookupSetBusy), which M3 cannot supply at accept
        time. Resolved by gating on the page-invariant, registered-state term ALONE --
        strictly more conservative than today (a hit to a speculatively-owned set is
        held for the 3-cycle install dwell instead of answered), with the cost measured
        by the Task 14 IPC gate rather than assumed. demandSetOwned is extended to
        cover an accepted-but-unresolved miss's set for the same reason.
  SG-2  REPLAY and FAULT deliver from a KNOWN way, which the spec's hit-path-only S1
        sketch does not cover. Resolved with an explicit s1WayOh 2:1 mux off registered
        state.

No younger command is ever accepted behind an unresolved miss (directed test): FetchRsp
carries no tag and FetchAlignPlugin attributes by ring head, so a violation would be
silent instruction-byte mis-pairing -- risk R3.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 12: M4 — registered-state-only prefetch frontier and AR arbiter

Spec §5.4. After Task 11, `heldDemandMiss`'s consumers already read `s1Unresolved` (a registered-input combinational term). M4 completes the move: the frontier and AR arbiter read a **fully registered** disposition, and `seedPfWindow` stops running off the live `lookupPaddr`.

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala` (`:546-565`, `:642-717`, `:837-863`, `:878-918`)

**Interfaces:**
- Consumes: `s0Valid`, `s0Fault`, `s0Cacheable`, `s0Ppn`, `s0Set`, `s1Hit`, `s1Unresolved` (Tasks 10/11).
- Produces, for Task 14/15:
  - `s1Disp: Bundle { valid, hit, fault, cacheable: Bool }` — a `Reg`, the 4-bit registered disposition. **Nothing else crosses the S1→prefetch boundary.**
  - `s1Line: UInt(32 bits)` — `Reg`, the registered line base (`s0Ppn ## s0Set ## 0`).
  - `seedPfWindow(line: UInt): Unit` — **signature change**: takes the line base as a parameter instead of computing it from the live `lookupPaddr`.

- [ ] **Step 1: Write the failing test — prefetch must still work, one cycle later**
Append to `src/test/scala/m68k040/cache/IcachePrefetchSpec.scala`:
```scala
  // Task 12 (M4): the prefetch frontier advances one cycle later and the AR is
  // presented one cycle later on the specific cycle a demand miss is also arbitrating.
  // Both are architecturally invisible (prefetch is a pure performance hint, rules
  // P1-P4), but the ENGINE MUST STILL WORK -- a frontier that never advances is also
  // "architecturally invisible" and would silently delete the feature.
  test("M4: the frontier still advances and speculative fills still land", VerilatorTest) {
    compiled.doSim("m4-frontier-alive") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x8000)
      dut.icache.logic.prefetchEnable #= true
      dut.clockDomain.waitSampling(5)

      // Touch ONLY line 0x1000. The frontier should pull in up to five lines ahead.
      fetchAndWait(dut, 0x1000L)
      dut.clockDomain.waitSampling(2000)

      val ahead = (1 to 5).count { i =>
        val set = ((0x1000 + i * 64) >> 6) & 0x3f
        (0 until 4).exists(w => IcacheArrayProbe.wayValid(dut.icache, w, set) &&
          IcacheArrayProbe.wayTag(dut.icache, w, set) == BigInt((0x1000 + i * 64) >> 12))
      }
      assert(ahead >= 3,
        s"only $ahead of the 5 lines ahead of a single demand fetch became resident. " +
        s"M4 moved the frontier behind a registered disposition; a frontier that never " +
        s"advances is 'architecturally invisible' in exactly the way that silently " +
        s"deletes the feature.")
    }
  }

  test("M4: pfHitUseful telemetry still fires on a prefetched line's first demand hit",
       VerilatorTest) {
    compiled.doSim("m4-pfhituseful") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x8000)
      dut.icache.logic.prefetchEnable #= true
      dut.clockDomain.waitSampling(5)

      var useful = 0
      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.icache.logic.pfHitUseful.toBoolean) useful += 1
        }
      }
      fetchAndWait(dut, 0x1000L)
      dut.clockDomain.waitSampling(2000)
      for (i <- 1 to 5) fetchAndWait(dut, 0x1000L + i * 64)
      dut.clockDomain.waitSampling(50)
      assert(useful >= 3,
        s"pfHitUseful fired only $useful times across 5 demand hits into prefetched " +
        s"lines. M4 moved the pfFilled clear to S1 (Task 11 Step 6); the telemetry " +
        s"design doc section 8.3 depends on is measured, not asserted.")
    }
  }
```

- [ ] **Step 2: Run and confirm both pass pre-M4**
```bash
sbt "testOnly m68k040.cache.IcachePrefetchSpec -- -z M4" 2>&1 | tail -20
```
Expected: both PASS. Regression detectors again.

- [ ] **Step 3: Add the registered disposition**
```scala
    // ---- M4: the registered S1 disposition ------------------------------------
    // FOUR BITS and a line base are the entire interface between the lookup pipeline
    // and the speculation machinery. Nothing else crosses (design principle P4:
    // "prefetch frontier and AR arbitration read ONLY registered state; a prefetch
    // decision one cycle later is architecturally invisible").
    val s1Disp = new Bundle {
      val valid     = RegInit(False)
      val hit       = Reg(Bool())
      val fault     = Reg(Bool())
      val cacheable = Reg(Bool())
    }
    val s1Line = Reg(UInt(32 bits))
    s1Disp.valid := s0Valid && !s0Replay
    s1Disp.hit       := s1Hit
    s1Disp.fault     := s0Fault
    s1Disp.cacheable := s0Cacheable
    s1Line           := (s0Ppn ## s0Set ## U(0, 6 bits)).asUInt
```

- [ ] **Step 4: Re-source `seedPfWindow` and the frontier**
Change the signature (`:546`) and delete its live `lookupPaddr` reads:
```scala
    // M4: takes the line base as a PARAMETER, registered, instead of computing it
    // from the live lookupPaddr. The 32-bit add, the page-boundary clamp and the
    // comparison chain all now run off flop outputs, one cycle later.
    def seedPfWindow(line: UInt): Unit = {
      val pageEnd = (line(31 downto 12) ## U(0xfff, 12 bits)).asUInt & ~U(63, 32 bits)
      val wantedLimit = line + U(5 * 64, 32 bits)
      val clampedLimit = Mux(wantedLimit(31 downto 12) === line(31 downto 12),
                             wantedLimit, pageEnd)
      when(!pfSeqValid || (line =/= pfDemandLine)) {
        val sequential = pfSeqValid &&
                         (line(31 downto 12) === pfDemandLine(31 downto 12)) &&
                         (line === (pfDemandLine + U(64, 32 bits)))
        when(!sequential) { pfNextPa := line + U(64, 32 bits) }
        pfDemandLine := line
        pfLimitPa    := clampedLimit
      }
      pfSeqValid := True
    }
```
Delete the `seedPfWindow()` / `pfWindowUpdate` block from inside `when(cmdPort.fire)` (`:664-675`) and replace it with an S1-side equivalent, placed with the other hoisted datapath:
```scala
    // M4: the frontier is seeded from the REGISTERED disposition, one cycle after
    // accept. Enabled by s1Disp only -- no cmdPort.fire, no live translation.
    when(s1Disp.valid) {
      when(!s1Disp.fault && s1Disp.cacheable) {
        val sequential = pfSeqValid &&
                         (s1Line(31 downto 12) === pfDemandLine(31 downto 12)) &&
                         (s1Line === (pfDemandLine + U(64, 32 bits)))
        pfWindowUpdate := !pfSeqValid || ((s1Line =/= pfDemandLine) && !sequential)
        seedPfWindow(s1Line)
      } otherwise {
        pfWindowUpdate := True
        pfSeqValid     := False
      }
    }
```

- [ ] **Step 5: Re-source the allocator and AR arbiter**
Replace every remaining `s1Unresolved` use in the speculation machinery (Task 11 Step 6's temporary substitutions) with the registered form:
```scala
    // M4: the allocator's and the AR arbiter's "an architectural miss is visibly held"
    // term is now a REGISTERED disposition, not a combinational verdict.
    val heldDemandMissQ = s1Disp.valid && !s1Disp.hit && !s1Disp.fault
```
- allocator guard (`:840-841`): `... && !heldDemandMissQ`
- `pfChosenArSel` (`:889`): `Mux(heldDemandMissQ, pfBlockingArSel, pfArSel)`
- AR arbiter guard (`:896`): `(!heldDemandMissQ || pfBlockingArAny)`

And `pfBlockingArWant` (`:885-886`) must compare against the **registered** set, not `lookupSet`:
```scala
    // M4: registered set, not the live lookupSet -- this term feeds the AR PAYLOAD
    // select mux, which spec section 5.4 names explicitly as a place isHit must not
    // reach.
    val pfBlockingArWant = Vec((0 until pfSlots).map(i =>
      pfArWant(i) && (mshrSet(i + AxiIds.I_SPEC_BASE) === s0Set)))
```

- [ ] **Step 6: Run the M4 tests and the prefetch telemetry sweep**
```bash
sbt "testOnly m68k040.cache.IcachePrefetchSpec"
sbt "testOnly m68k040.cache.IcacheOrderOracleSpec"
```
Expected: all PASS, including the two new M4 tests and the pre-existing prefetch telemetry assertions.

- [ ] **Step 7: Full correctness sweep**
```bash
grep -n "lookupPaddr\|lookupTag\|lookupCacheable\|lookupFault" src/main/scala/m68k040/cache/IcachePlugin.scala
# expect: ONLY the declarations (:185-188) and the S0 capture. NOT in the allocator,
# NOT in the AR arbiter, NOT in seedPfWindow.
make test-fast
sbt "testOnly m68k040.cache.IcacheOrderOracleSpec m68k040.cache.IcacheUnifiedArraySpec m68k040.cache.IcacheVerdictShadowSpec"
<GC4-SUITES>
<LOCKSTEP>
```
Expected: the live-translation signals appear only at their declaration and the S0 capture; 149/149; all oracles green; GC-4 green; 396/396.

- [ ] **Step 8: Commit**
```bash
git add src/main/scala/m68k040/cache/IcachePlugin.scala src/test/scala/m68k040/cache/IcachePrefetchSpec.scala
git commit -m "icache(M4): registered-state-only prefetch frontier and AR arbiter

Four bits and a line base (s1Disp + s1Line) are now the entire interface between the
lookup pipeline and the speculation machinery. The 96-flop frontier's clock enable,
the 37-flop AR hold's clock enable, AND the AR payload select mux all read registered
state -- isHit reached all three before (IcachePlugin.scala:471, :840-843, :893-905),
and P1's measured new worst path terminated at arHoldAddr[30]/D.

seedPfWindow takes the line base as a PARAMETER instead of computing it from the live
lookupPaddr, so its 32-bit add, page-boundary clamp and comparison chain run off flop
outputs. pfBlockingArWant compares against the registered s0Set, not lookupSet.

A frontier that advances one cycle later and an AR presented one cycle later on the
one cycle a demand miss is also arbitrating are architecturally invisible (prefetch is
a pure performance hint, rules P1-P4). But a frontier that never advances is ALSO
'architecturally invisible' in exactly the way that silently deletes the feature, so
two directed tests pin that the frontier still pulls >=3 of 5 lines ahead of a single
demand fetch and that pfHitUseful still fires -- measured, not reasoned about.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 13: Mutation proofs (spec §12.2, all five)

Spec §12.2: "Each of these must make a *named* test fail; if one does not, the test suite does not cover the invariant and a test must be added before the change lands."

**PLAN DECISION:** the mutations are applied **temporarily, one at a time, and reverted**, and the *result* is recorded in a spec file that documents which named test covers which invariant. A mutation left in the tree is a shipped bug; a mutation never run is an unproven claim. The record is what survives.

**Files:**
- Create: `src/test/scala/m68k040/cache/IcacheMutationProofSpec.scala`
- Temporarily modify (and revert): `src/main/scala/m68k040/cache/IcachePlugin.scala`

**Interfaces:**
- Consumes: all oracles and directed tests from Tasks 4–12.
- Produces: `IcacheMutationProofSpec` — a documentation-of-coverage suite, one `test` per mutation, each **asserting the covering test exists and is tagged**, plus a scaladoc block recording the observed failure. Not a mutation *runner* (SpinalHDL RTL mutation cannot be driven from ScalaTest); the running is manual and its result is the deliverable.

- [ ] **Step 1: Run mutation 1 — force `s1Unresolved := False`**
```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/frontend-restructure
# In IcachePlugin.scala, immediately after s1Unresolved's assignment, add:
#   s1Unresolved := False   // MUTATION 1 -- REVERT
sbt "testOnly m68k040.cache.IcacheOrderOracleSpec" 2>&1 | tail -30
```
**Required result:** FAIL, in `oracle 1: every response is the next accepted command, in order` **or** `oracle 2: N consecutive fetches to one line produce exactly one AR`. Record which, and the exact assertion message.

If **neither** fails: the suite does not cover the invariant. **Add a test that does before proceeding** — this is the spec's own stated rule, not a suggestion.

```bash
git checkout -- src/main/scala/m68k040/cache/IcachePlugin.scala   # REVERT
sbt "testOnly m68k040.cache.IcacheOrderOracleSpec" 2>&1 | tail -10   # confirm green again
```

- [ ] **Step 2: Run mutation 2 — drop the `when(!anyInvalidate)` guard on the `valids` write**
```bash
# In the predecode dwell, change:
#   when(!anyInvalidate) { valids(w)(installSet) := True }
# to:
#   valids(w)(installSet) := True     // MUTATION 2 -- REVERT
sbt "testOnly m68k040.cache.IcacheSpec -- -z invalidate" 2>&1 | tail -30
sbt "testOnly m68k040.cache.IcacheInvalidateSpec" 2>&1 | tail -30
```
**Required result:** FAIL in `IcacheSpec`'s invalidate-race test (the one built on `dbgAllocCommitCycle`/`dbgAllocCommitPending`, `IcacheSpec.scala:249-338`). This is risk R4's exact guard — `IcachePlugin.scala:1017-1029` documents the real shipped bug it prevents, where the refill's `True` silently won over the invalidate under SpinalHDL's last-assignment-wins.

**Additionally required (risk R4's own wording):** the test must still work *unmodified in intent*. If M2's install-index/cadence changes forced any weakening of `dbgAllocCommitCycle`/`dbgAllocCommitPending` or of the test's timing, **treat that as a design defect and fix the RTL**, not the test.

```bash
git checkout -- src/main/scala/m68k040/cache/IcachePlugin.scala   # REVERT
```

- [ ] **Step 3: Run mutation 3 — write the high beat's predecode at the low beat's address**
```bash
# In the dwell's lineMem write, change:
#   lineMem(w).write((installSet ## commitBeat).asUInt, beatPred ## beatSrc)
# to:
#   lineMem(w).write((installSet ## U(0, 1 bits)).asUInt, beatPred ## beatSrc)  // MUTATION 3
sbt "testOnly m68k040.cache.IcacheUnifiedArraySpec" 2>&1 | tail -30
sbt "testOnly m68k040.decode.FedSpecsPacketPairingSpec" 2>&1 | tail -30
```
**Required result:** FAIL in `IcacheUnifiedArraySpec`'s M1b read-path test **and/or** `FedSpecsPacketPairingSpec` (the Lever-B `size`-plumbing invariant, which reads `pred.size` end-to-end).

```bash
git checkout -- src/main/scala/m68k040/cache/IcachePlugin.scala   # REVERT
```

- [ ] **Step 4: Run mutation 4 — remove the `fillArrayWrActive && (lookupSet === installSet)` block**
```bash
# In lookupTick, change:
#   val setBlocked = if (canStartFill) False
#                    else (fillArrayWrActive && (lookupSet === installSet))
# to:
#   val setBlocked = False    // MUTATION 4 -- REVERT
sbt "testOnly m68k040.cache.IcacheSpec" 2>&1 | tail -40
sbt "testOnly m68k040.cache.IcachePrefetchSpec" 2>&1 | tail -40
```
**Required result:** FAIL in the D3-SET-I same-set test. `IcachePlugin.scala:636-641` describes the hazard exactly: `tagMem` is a write-first async-read LUTRAM while `valids` is a register array, so on the commit cycle a same-set lookup reads the NEW tag against the OLD valid bit and can report a spurious HIT on a line whose data beat is being written that same cycle.

**Note:** this hazard is now *harder* to hit than pre-M3, because `tagQ`/`validsQ` are both captured at S0 in the same cycle. If no test fails, **do not conclude the guard is unnecessary** — construct a directed test that drives a lookup to `installSet` on exactly the `fillArrayWrActive` cycle (use the existing `dbgAllocCommitPending` hook, which exists to line a testbench poke up with that precise cycle). Only after a test fails may this mutation be recorded as covered.

```bash
git checkout -- src/main/scala/m68k040/cache/IcachePlugin.scala   # REVERT
```

- [ ] **Step 5: Run mutation 5 — route `s0FromMiss` to the array instead of the bypass**
```bash
# In the S1 output mux, change:
#   val s1Window   = Mux(s0FromMiss, bypWindow, ohOr(s1WayOh, wayWindow))
#   val s1PredBits = Mux(s0FromMiss, bypPred,   ohOr(s1WayOh, wayPred))
# to:
#   val s1Window   = ohOr(s1WayOh, wayWindow)   // MUTATION 5 -- REVERT
#   val s1PredBits = ohOr(s1WayOh, wayPred)     // MUTATION 5 -- REVERT
sbt "testOnly m68k040.cache.IcacheSpec -- -z INHIBITED" 2>&1 | tail -30
```
**Required result:** FAIL in the INHIBITED-replay corruption test (`IcacheSpec.scala:808-836`) and/or Task 7's window-sweep test. The arrays were never written for a non-allocated line, so the response would carry stale, unrelated content from a prior allocation to the same way/set.

```bash
git checkout -- src/main/scala/m68k040/cache/IcachePlugin.scala   # REVERT
git diff --stat -- src/main/    # MUST be EMPTY -- no mutation may survive
```

- [ ] **Step 6: Record the coverage map**
Create `src/test/scala/m68k040/cache/IcacheMutationProofSpec.scala`:
```scala
package m68k040.cache

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 12.2's five mutation proofs, RUN and RECORDED.
  *
  * Each mutation below was applied temporarily to IcachePlugin.scala, the named test
  * was observed to FAIL, and the mutation was reverted. This file is the record; it
  * does not re-run the mutations (SpinalHDL RTL mutation cannot be driven from
  * ScalaTest), it pins that the covering tests still EXIST and are reachable, so a
  * later refactor cannot quietly delete the coverage the mutations proved.
  *
  * The rule this implements is spec section 12.2's own: "Each of these must make a
  * NAMED test fail; if one does not, the test suite does not cover the invariant and a
  * test must be added before the change lands."
  *
  *  M1  Force s1Unresolved := False
  *      -> FAILS: <record the exact test name and assertion message here>
  *  M2  Drop the when(!anyInvalidate) guard on the valids write
  *      -> FAILS: <record>
  *      (risk R4: IcachePlugin.scala:1017-1029 documents the real shipped bug)
  *  M3  Write the high beat's predecode at the low beat's address
  *      -> FAILS: <record>
  *  M4  Remove the fillArrayWrActive && (lookupSet === installSet) block
  *      -> FAILS: <record>
  *      (D3-SET-I: write-first async tagMem vs register valids, IcachePlugin.scala:636-641)
  *  M5  Route s0FromMiss to the array instead of the bypass
  *      -> FAILS: <record>
  */
class IcacheMutationProofSpec extends AnyFunSuite {

  /** Pins that each covering suite still declares the test the mutation proof relies
    * on. A renamed or deleted test breaks THIS, loudly, instead of silently retiring
    * a proven invariant. */
  private def suiteDeclaresTest(suiteClass: Class[_], substring: String): Boolean = {
    val suite = suiteClass.getDeclaredConstructor().newInstance()
      .asInstanceOf[org.scalatest.Suite]
    suite.testNames.exists(_.toLowerCase.contains(substring.toLowerCase))
  }

  test("mutation 1's covering tests exist: response order and one-AR-per-line") {
    assert(suiteDeclaresTest(classOf[IcacheOrderOracleSpec], "in order"))
    assert(suiteDeclaresTest(classOf[IcacheOrderOracleSpec], "exactly one AR"))
  }

  test("mutation 2's covering test exists: the invalidate-vs-allocate race") {
    assert(suiteDeclaresTest(classOf[IcacheSpec], "invalidate"))
  }

  test("mutation 3's covering tests exist: unified-array read path and packet pairing") {
    assert(suiteDeclaresTest(classOf[IcacheUnifiedArraySpec], "unified array"))
  }

  test("mutation 4's covering test exists: the D3-SET-I same-set block") {
    assert(suiteDeclaresTest(classOf[IcacheSpec], "same-set") ||
           suiteDeclaresTest(classOf[IcachePrefetchSpec], "same-set"))
  }

  test("mutation 5's covering test exists: INHIBITED replay") {
    assert(suiteDeclaresTest(classOf[IcacheSpec], "INHIBITED"))
  }
}
```
Fill every `<record>` with the actual observed test name and assertion message from Steps 1–5. **Do not commit a `<record>` placeholder.** Adjust each `suiteDeclaresTest` substring to match the real test names in the tree.

- [ ] **Step 7: Verify and commit**
```bash
git diff --stat -- src/main/    # MUST be EMPTY
make test-fast
sbt "testOnly m68k040.cache.IcacheMutationProofSpec"
git add src/test/scala/m68k040/cache/IcacheMutationProofSpec.scala
git commit -m "test(icache): spec section 12.2's five mutation proofs, run and recorded

Each mutation was applied temporarily, the named covering test was observed to fail,
and the mutation was reverted (git diff --stat -- src/main/ is empty). This file
records which named test covers which invariant and pins that those tests still exist,
so a later refactor cannot quietly retire coverage the mutations proved.

  M1 s1Unresolved := False                       -> response-order / one-AR oracles
  M2 drop when(!anyInvalidate) on the valids write -> the invalidate-vs-allocate race
     test (risk R4; IcachePlugin.scala:1017-1029 documents the real shipped bug)
  M3 high beat's predecode at the low beat's address -> unified-array read path
  M4 remove the fillArrayWrActive same-set block  -> the D3-SET-I test
  M5 route s0FromMiss to the array, not the bypass -> the INHIBITED-replay test

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 14: Full correctness sweep + the IPC gate

Everything in spec §10's "blocking secondary gates" list, run once, together, on the complete M1–M4 stack — **before** any floorplan work or synthesis.

**Files:** none modified (this task produces a report and a ledger note).

**Interfaces:**
- Consumes: `IPC_BASELINE_ON` / `IPC_BASELINE_OFF` (Task 2).
- Produces: a pass/fail record for every blocking secondary gate, which Task 16 quotes rather than re-derives.

- [ ] **Step 1: Lock-step, 396/396**
```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/frontend-restructure
sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec m68k040.lockstep.EndToEndLockStepSpec" 2>&1 | tail -20
```
Expected: `Tests: succeeded 396, failed 0`.

- [ ] **Step 2: `make test-fast`, 149/149 across 157 suites**
```bash
make test-fast 2>&1 | tail -20
```
Expected: 149 succeeded, 0 failed. **Remember GC-3: this silently skips 120 of 157 suites.** Step 3 is not optional.

- [ ] **Step 3: The explicit VerilatorTest list, plus the new suites**
```bash
<GC4-SUITES>
sbt "testOnly m68k040.cache.IcacheOrderOracleSpec \
              m68k040.cache.IcacheUnifiedArraySpec \
              m68k040.cache.IcacheVerdictShadowSpec \
              m68k040.cache.IcacheMutationProofSpec"
```
Expected: all green.

- [ ] **Step 4: Re-confirm the 35 known pre-existing failures on the parent commit**
```bash
sbt "testOnly m68k040.decode.PackUnpkDecodeSpec m68k040.decode.BitfieldDecodeSpec" 2>&1 | tail -20
git stash list  # ensure nothing is stashed
git worktree add /home/qwertyoruiop/tmp/parent-check 6144e50
cd /home/qwertyoruiop/tmp/parent-check
sbt "testOnly m68k040.decode.PackUnpkDecodeSpec m68k040.decode.BitfieldDecodeSpec" 2>&1 | tail -20
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/frontend-restructure
git worktree remove /home/qwertyoruiop/tmp/parent-check
```
Expected: **identical** failure counts and identical failing test names on both. Only then may they be discounted. **The suite names above are the ones spec §10 cites; confirm they are the real ones with `grep -rn "class PackUnpkDecodeSpec\|class BitfieldDecodeSpec" src/test`** and use whatever is actually there.

- [ ] **Step 5: The IPC gate — ≥ 99.0 % of baseline, both prefetch postures**
```bash
for seed in 1 2 3; do
  for mem in "" "l2:5:70"; do
    for pf in on off; do
      echo "=== seed=$seed mem=${mem:-zero} prefetch=$pf ==="
      env IPC_SEED=$seed ${mem:+IPC_MEM=$mem} \
          $( [ "$pf" = off ] && echo IPC_PREFETCH=off ) \
        sbt "testOnly m68k040.bench.IpcBenchSpec" 2>&1 | grep -E "^AGGREGATE"
    done
  done
done 2>&1 | tee /home/qwertyoruiop/tmp/ipc_after_m4.txt
```
Compare each `AGGREGATE` IPC against Task 2's corresponding baseline.

**BLOCKING GATE:** every configuration must be **≥ 99.0 %** of its Task 2 baseline.

If any configuration falls below 99.0 %, the two most likely causes, in order, are:
1. **SG-1's conservative accept gate** (Task 11 Step 3) — a hit to a speculatively-owned set is now held for the install dwell. Diagnose by re-running with `IPC_PREFETCH=off`: if the `off` posture is at parity and only `on` regressed, this is the cause. Mitigation: refine `pfAcceptOk` to `!pfLookupSetBusy || !s1Unresolved`-style forms **only** if they stay page-invariant and registered-state-only; otherwise report the trade honestly.
2. **The +1 install-arm cycle** (Task 8) — should be ~0.3 % on a ~78-cycle event and would show equally in both postures.

**Record the diagnosis, do not paper over the number.** Spec §10 makes IPC blocking precisely so it is measured and not asserted.

- [ ] **Step 6: Record the flop/LUT delta claim, provisionally**
```bash
grep -c "Reg(" src/main/scala/m68k040/cache/IcachePlugin.scala
git diff --stat 6144e50..HEAD
```
Record the diff size. The **real** flop/LUT numbers come from Task 16's synthesis; do not claim them here.

- [ ] **Step 7: Commit the correctness record**
```bash
cat >> .superpowers/sdd/progress-ipc-push-2026-08-09.md <<'LEDGER'

### §36.1 — M1-M4 correctness gate (pre-synthesis), all blocking secondary gates

| gate | threshold | result |
|---|---|---|
| ExecuteLockStepSpec + EndToEndLockStepSpec | 396/396 | FILL |
| make test-fast | 149/149, 157 suites | FILL |
| explicit VerilatorTest frontend/cache list | all green | FILL |
| new oracles (order / one-AR / MSHR-exclusivity / unified-array) | all green | FILL |
| mutation proofs (5) | each breaks a NAMED test | FILL |
| PackUnpk/Bitfield 35 known failures | identical on parent 6144e50 | FILL |
| IPC, prefetch ON, IPC_MEM=l2:5:70 | >= 99.0 %% of §36 baseline | FILL |
| IPC, prefetch OFF, IPC_MEM=l2:5:70 | >= 99.0 %% of §36 baseline | FILL |
| IPC, prefetch ON, IPC_MEM=zero | >= 99.0 %% of §36 baseline | FILL |
| IPC, prefetch OFF, IPC_MEM=zero | >= 99.0 %% of §36 baseline | FILL |

No synthesis has been run on this stack. Per the plan's GC-1, the single post-route
gate is Task 16 and nothing before it may abort the change.
LEDGER
```
Replace every `FILL` with the measured result (`PASS` / `FAIL` / the actual percentage). **Do not leave a `FILL` in the committed file.**

```bash
git add .superpowers/sdd/progress-ipc-push-2026-08-09.md
git commit -m "docs(ledger) §36.1: M1-M4 pre-synthesis correctness gate, all blocking secondary gates

Every gate in spec section 10's blocking-secondary list, run once on the complete
M1-M4 stack: 396/396 lock-step, 149/149 fastTest, the explicit VerilatorTest
frontend/cache list (GC-3: fastTest silently skips 120 of 157 suites), the four new
oracles, the five mutation proofs, and the IPC sweep at >= 99.0%% of the section-36
baseline with prefetch both ON and OFF under both memory models.

No synthesis run. Per GC-1 the single post-route gate is the next-but-one task and
nothing before it may abort the change.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 15: M5 — `synth/floorplan_fetch.xdc`, sized from a real post-synthesis probe of the NEW netlist

Spec §7. Variant 7 pinned 1,900 sparse flops with a pblock and got the campaign's best iteration yield ever measured (+0.685 ns, beating baseline's own +0.622) while still losing on round-0 (−2.422 vs −2.094). **This design deletes those flops and then draws the box around what is left** — denser, smaller, anchored on fixed BRAM sites. The two moves are not independent, and §7.3 says gating them separately would be exactly the mistake §11.3 exists to prevent.

**The box MUST be sized from a measurement of the new netlist, not from this document or from `floorplan_frontend.xdc`'s baseline numbers.** That is spec §7.2 item 2, and it is the one thing that distinguishes M5 from variant 7.

**Files:**
- Create: `synth/probe_floorplan_fetch.tcl`
- Create: `synth/floorplan_fetch.xdc`
- Modify: `synth/impl_FullCore.tcl` (`:84-115` token table, `:122-127` stale-report cleanup)

**Interfaces:**
- Consumes: the M1–M4 netlist.
- Produces: `FLOORPLAN_MODE` token `fetch` → `synth/floorplan_fetch.xdc`, creating pblock `pb_fetch`.

- [ ] **Step 1: Generate the new netlist and run a synthesis-only pass to get a real DCP**
```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/frontend-restructure
make verilog
md5sum generated/M68kFullCoreSynth.v
pgrep -af "impl_FullCore|vivado" | wc -l   # GC-9: must be 0
free -g
cat > synth/synth_only_m5.tcl <<'TCL'
read_verilog generated/M68kFullCoreSynth.v
read_xdc synth/clk.xdc
synth_design -top M68kFullCoreSynth -part xcku5p-ffvb676-2-e -mode out_of_context
opt_design
write_checkpoint -force synth/m5_synth.dcp
report_utilization -file synth/m5_synth_util.rpt
puts "M5_SYNTH_DONE"
TCL
nohup vivado -mode batch -source synth/synth_only_m5.tcl > synth/m5_synth.out 2>&1 &
VIVADO_PID=$!
# GC-5: ACTIVE POLLING.
while kill -0 "$VIVADO_PID" 2>/dev/null; do sleep 30; done
grep -c "M5_SYNTH_DONE" synth/m5_synth.out   # expect 1
```
**GC-1: do not read the WNS from this run.** It exists solely to produce a netlist the sizing probe can measure.

- [ ] **Step 2: Write and run the sizing probe**
Create `synth/probe_floorplan_fetch.tcl`, reusing `probe_floorplan_filters.tcl`'s **clean** capture filter — the file documents the instance-path trap that makes a bare `*XPlugin_logic*` match drag in 7,033 foreign ROB cells; **reuse the filter, do not re-derive it**:
```tcl
# Sizing probe for pb_fetch, against the POST-M1-M4 netlist.
#
# Spec section 7.2 item 2: the box is sized from a REAL post-synthesis measurement of
# the NEW netlist, never from the design document and never from
# floorplan_frontend.xdc's baseline numbers. That is the one thing distinguishing M5
# from variant 7 (which sized a box for the netlist this design replaces, got the
# campaign's best iteration yield at +0.685 ns, and still lost on round-0 at -2.422).
#
# The capture filter is COPIED VERBATIM from floorplan_frontend.xdc, including the
# foreign-plugin exclusions and the IS_PRIMITIVE qualifier. Without IS_PRIMITIVE,
# get_cells -hier also returns the hierarchical instances themselves (e.g.
# FetchAlignPlugin_logic_ibuf), and adding one of those re-constrains every leaf under
# it regardless of that leaf's name -- which is how the exclusions get silently undone.
set dcp "synth/m5_synth.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
open_checkpoint $dcp
puts "FETCHPROBE_DCP $dcp"

set filt {(NAME =~ *IcachePlugin_logic* || NAME =~ *FetchAlignPlugin_logic* || NAME =~ *FtbPlugin_logic* || NAME =~ *GsharePlugin_logic* || NAME =~ *RasPlugin_logic* || NAME =~ *BtbPlugin_logic*) && NAME !~ *DecodeStage* && NAME !~ *RobPlugin* && NAME !~ *IssueQueuePlugin* && NAME !~ *RenameStage* && NAME !~ *DcachePlugin* && NAME !~ *LsEuPlugin* && IS_PRIMITIVE}
set cells [get_cells -hier -filter $filt]

set nl 0; set nf 0; set nc 0; set nm 0; set nram 0; set nbram 0; set no 0
foreach c $cells {
  set r [get_property REF_NAME $c]
  if {[string match "RAMB*" $r]}      { incr nbram } \
  elseif {[string match "RAM*" $r]}   { incr nram } \
  elseif {[string match "LUT*" $r]}   { incr nl } \
  elseif {[string match "FD*" $r]}    { incr nf } \
  elseif {[string match "CARRY*" $r]} { incr nc } \
  elseif {[string match "MUXF*" $r]}  { incr nm } \
  else { incr no }
}
puts "FETCH_CAPTURE cells=[llength $cells] LUT=$nl FF=$nf CARRY=$nc MUXF=$nm LUTRAM=$nram BRAM=$nbram other=$no"

# Minimum slice demand, and the grid that lands in the 55-65%% LUT-site working band
# that floorplan_decode_fe.xdc's own sizing note treats as correct -- never the
# 96.78%%-occupancy trap floorplan_decode.xdc records.
set lut_demand [expr {$nl + $nc + $nram}]
foreach target {0.55 0.60 0.65} {
  set need_lutsites [expr {int(ceil($lut_demand / $target))}]
  set need_slices   [expr {int(ceil($need_lutsites / 8.0))}]
  puts [format "FETCH_SIZING target_lutocc=%.2f lut_demand=%d need_lutsites=%d need_slices=%d" \
        $target $lut_demand $need_lutsites $need_slices]
}
# FF-site occupancy at each candidate, so the box is not FF-starved either.
puts "FETCH_FF_DEMAND $nf"

# Where do the IcachePlugin BRAMs actually sit? pb_fetch must INCLUDE those sites
# (spec section 7.2 item 3), or the placer is forced to stretch between a boxed logic
# region and an unboxed memory column.
foreach c [get_cells -hier -filter {NAME =~ *IcachePlugin_logic* && REF_NAME =~ RAMB*}] {
  puts "FETCH_BRAM [get_property NAME $c] [get_property LOC $c]"
}
puts "FETCHPROBE_DONE"
```
```bash
nohup vivado -mode batch -source synth/probe_floorplan_fetch.tcl > synth/m5_probe.out 2>&1 &
VIVADO_PID=$!
while kill -0 "$VIVADO_PID" 2>/dev/null; do sleep 20; done   # GC-5
grep -E "FETCH_CAPTURE|FETCH_SIZING|FETCH_FF_DEMAND|FETCH_BRAM|FETCHPROBE_DONE" synth/m5_probe.out
```
Expected: `FETCH_CAPTURE` reporting **materially fewer FF than `floorplan_frontend.xdc`'s recorded 4,296** (target ≈ 2,400 after the ~1,900-flop deletion) and materially fewer `LUTRAM`; the three `FETCH_SIZING` rows; a `FETCH_BRAM` list (post-synthesis `LOC` may be empty — if so, note it and pick BRAM column ranges from the device's fixed geometry instead).

- [ ] **Step 3: Write the pblock, from the probe's numbers**
Create `synth/floorplan_fetch.xdc`:
```tcl
# pb_fetch -- the floorplan half of the Unified Fetch Array co-design (spec section 7).
#
# WHY THIS IS NOT VARIANT 7 AGAIN. Variant 7 pinned 1,900 SPARSE flops with a pblock:
# it produced +0.685 ns of iteration yield -- the best ever measured in this campaign,
# beating baseline's own +0.622 -- and still lost, on round-0 (-2.422 vs -2.094). A
# pblock ASKS the placer for compactness; it cannot manufacture density in a
# 2.3-FF/slice cloud. This design DELETED those flops (M1/M2: -1,920 CLB flops,
# -1,000...-2,000 distributed-RAM LUTs) and then draws the box around what is left,
# which is denser, smaller, and anchored on fixed BRAM sites.
#
# SIZING -- measured, not assumed. From synth/probe_floorplan_fetch.tcl against THIS
# netlist's own post-synthesis checkpoint (synth/m5_synth.dcp), not against the
# 6b246de baseline that floorplan_frontend.xdc was drawn for:
#   captured (clean)  <FILL: cells> = <FILL> LUT + <FILL> FF + <FILL> CARRY +
#                     <FILL> MUXF + <FILL> LUTRAM + <FILL> BRAM + <FILL> other
#   grid  <FILL: SLICE_XaYb:SLICE_XcYd>  = <FILL> slices = <FILL> LUT sites
#   -> <FILL>%% LUT-site, <FILL>%% FF-site occupancy
# Target band 55-65%% LUT-site, which is the working range floorplan_decode_fe.xdc's
# own sizing note treats as correct. NEVER the 96.78%%-occupancy trap
# floorplan_decode.xdc records, and NEVER by annexing occupied territory (the
# X87->X103 lesson, -2.447 -> -2.558 ns).
#
# BRAM SITES ARE INCLUDED (spec section 7.2 item 3), unlike every prior pblock in this
# tree, which constrained SLICE ranges only. The Unified Fetch Array is why: its
# columns are fixed placement anchors, and boxing the logic without them would force
# the placer to stretch between a boxed region and an unboxed memory column -- the
# exact stretch the 66%%-route number is made of.
#
# pb_decode IS DELIBERATELY LEFT UNCHANGED at SLICE_X36Y0:SLICE_X87Y104. Two
# independent experiments (the X87->X103 widening, and variant 6's loosening at
# -12.65 MHz) say that box is at a local optimum. Change one thing.
#
# CAPTURE FILTER copied verbatim from floorplan_frontend.xdc: explicit foreign-plugin
# exclusions AND the IS_PRIMITIVE qualifier. Without IS_PRIMITIVE, get_cells -hier
# also returns hierarchical instances (e.g. FetchAlignPlugin_logic_ibuf), and adding
# one re-constrains every leaf under it regardless of name -- which is how the
# exclusions get silently undone, and is the exact defect that made the legacy
# pb_dcache harmful.
create_pblock pb_fetch
resize_pblock pb_fetch -add {<FILL: SLICE_XaYb:SLICE_XcYd>}
resize_pblock pb_fetch -add {<FILL: RAMB18_XaYb:RAMB18_XcYd>}
resize_pblock pb_fetch -add {<FILL: RAMB36_XaYb:RAMB36_XcYd>}
add_cells_to_pblock pb_fetch [get_cells -hier -filter {(NAME =~ *IcachePlugin_logic* || NAME =~ *FetchAlignPlugin_logic* || NAME =~ *FtbPlugin_logic* || NAME =~ *GsharePlugin_logic* || NAME =~ *RasPlugin_logic* || NAME =~ *BtbPlugin_logic*) && NAME !~ *DecodeStage* && NAME !~ *RobPlugin* && NAME !~ *IssueQueuePlugin* && NAME !~ *RenameStage* && NAME !~ *DcachePlugin* && NAME !~ *LsEuPlugin* && IS_PRIMITIVE}]
```
Fill every `<FILL>` from Step 2's output. **Do not commit a `<FILL>`.**

Geometry guidance, in priority order:
1. Start from the `FETCH_SIZING target_lutocc=0.60` slice count.
2. Keep the box in the **left strip** the cluster already occupies (`floorplan_frontend.xdc`'s measured bboxes: Icache X1..X50 Y1..Y136, FetchAlign X10..X65 Y20..Y105, Ftb X1..X34 Y57..Y135, Gshare X10..X32 Y75..Y103, Ras X10..X59 Y51..Y82).
3. **Do not cross X36** — that is `pb_decode`'s left edge, and annexing occupied territory is the recorded X87→X103 failure mode.
4. Include the BRAM columns inside or immediately adjacent to the chosen X range.

- [ ] **Step 4: Add the `fetch` token to `impl_FullCore.tcl`**
```tcl
# in the FLOORPLAN_MODE comment block (:87-91), add:
#   fetch     synth/floorplan_fetch.xdc      Unified-Fetch-Array cluster box + BRAM sites

# in the `array set floorplan_xdc { ... }` block (:94-100), add:
  fetch     synth/floorplan_fetch.xdc

# in the token-validation error message (:103), add `fetch` to the accepted list:
    error "FLOORPLAN_MODE tokens must be from: none decode decode_fe dcache backend frontend fetch (got '$tok')"

# add a mutual-exclusion check next to the existing decode/decode_fe and dcache/backend ones:
if {[lsearch -exact $floorplan_tokens "frontend"] >= 0 && [lsearch -exact $floorplan_tokens "fetch"] >= 0} {
  error "FLOORPLAN_MODE: frontend and fetch box the same cluster; pick one"
}

# in the stale-report cleanup list (:122):
foreach stale {pb_decode pb_dcache pb_backend pb_frontend pb_fetch} {
```

- [ ] **Step 5: Verify the XDC parses and captures the right cells**
```bash
cat > /tmp/check_fetch_xdc.tcl <<'TCL'
open_checkpoint synth/m5_synth.dcp
read_xdc synth/floorplan_fetch.xdc
foreach pb [get_pblocks -quiet] {
  puts "FLOORPLAN [get_property NAME $pb] grid=[get_property GRID_RANGES $pb] cells=[llength [get_cells -quiet -of_objects $pb]]"
}
# Contamination check: no foreign-plugin cell may be captured.
set bad [get_cells -quiet -of_objects [get_pblocks pb_fetch] -filter {NAME =~ *RobPlugin* || NAME =~ *IssueQueuePlugin* || NAME =~ *DcachePlugin* || NAME =~ *LsEuPlugin* || NAME =~ *DecodeStage* || NAME =~ *RenameStage*}]
puts "FETCH_CONTAMINATION [llength $bad]"
puts "FETCH_XDC_CHECK_DONE"
TCL
nohup vivado -mode batch -source /tmp/check_fetch_xdc.tcl > synth/m5_xdc_check.out 2>&1 &
VIVADO_PID=$!; while kill -0 "$VIVADO_PID" 2>/dev/null; do sleep 15; done   # GC-5
grep -E "FLOORPLAN|FETCH_CONTAMINATION|FETCH_XDC_CHECK_DONE" synth/m5_xdc_check.out
```
Expected: `pb_fetch` created with a cell count matching Step 2's `FETCH_CAPTURE`, and **`FETCH_CONTAMINATION 0`**. A non-zero contamination count means the filter was mis-copied — fix it before the gate.

- [ ] **Step 6: Commit**
```bash
git add synth/probe_floorplan_fetch.tcl synth/floorplan_fetch.xdc synth/impl_FullCore.tcl \
        synth/m5_probe.out synth/m5_xdc_check.out synth/m5_synth_util.rpt synth/synth_only_m5.tcl
git commit -m "synth(M5): pb_fetch, sized from a real post-synthesis probe of the NEW netlist

Spec section 7's floorplan half of the co-design, and the one thing that makes it not
variant 7 again. Variant 7 pinned 1,900 SPARSE flops with a pblock: best iteration
yield ever measured in this campaign (+0.685 ns, beating baseline's own +0.622) and it
still lost, on round-0 (-2.422 vs -2.094), because a pblock ASKS for compactness and
cannot manufacture density in a 2.3-FF/slice cloud. M1/M2 DELETED those flops; this
box is drawn around what is left.

Sized from synth/probe_floorplan_fetch.tcl against THIS netlist's own post-synthesis
checkpoint -- not from the design document, and not from floorplan_frontend.xdc's
baseline numbers, which describe the netlist this change replaces. Target 55-65%%
LUT-site occupancy, the working band floorplan_decode_fe.xdc's own sizing note treats
as correct; never the 96.78%% trap floorplan_decode.xdc records; never annexing
occupied territory (the X87->X103 lesson).

BRAM sites are INCLUDED in the pblock, unlike every prior box in this tree, which
constrained SLICE ranges only. The Unified Fetch Array is why: BRAM columns are fixed
placement anchors, and boxing the logic without them forces the placer to stretch
between a boxed region and an unboxed memory column.

pb_decode is left UNCHANGED. Two independent experiments say it is at a local optimum.
Change one thing.

Capture filter copied verbatim, IS_PRIMITIVE and all; contamination verified 0.
No place, no route, no WNS read -- per the plan's GC-1 the single gate is next.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 16: THE SINGLE GATE — postrouteN3 × {decode} × {decode+fetch}, and the D1 decision

**This is the only post-route measurement in the entire plan.** Spec §11.3, GC-1.

**Files:**
- Modify: `.superpowers/sdd/progress-ipc-push-2026-08-09.md` (new §37)
- Create: `synth/archive/<sha>_ufa_postrouteN3_decode/`, `synth/archive/<sha>_ufa_postrouteN3_decode_fetch/`

**Interfaces:**
- Consumes: everything.
- Produces: the twelfth honest row of the campaign's variant table, either way.

- [ ] **Step 1: Verify the machine is uncontended, before AND after (GC-9, spec §12.4)**
```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/frontend-restructure
pgrep -af "impl_FullCore" | wc -l   # MUST be 0
pgrep -af vivado | wc -l            # MUST be 0
free -g                             # record available memory
```
Record both numbers. The campaign has a confirmed history of FMax measurements being meaningless under contention (214.3 vs 163.9 MHz for an identical commit). **If either count is non-zero, wait. Do not gate.**

- [ ] **Step 2: Verify a FRESH synthesis will happen (no reused DCP)**
```bash
make verilog
md5sum generated/M68kFullCoreSynth.v
cat synth/fullcore_synth.md5 2>/dev/null || echo "(no sidecar)"
echo "REUSE_SYNTH_DCP=${REUSE_SYNTH_DCP:-unset}"   # MUST be unset or 0
rm -f synth/fullcore_synth.dcp synth/fullcore_synth.md5
```
Spec §10: the gate requires fresh synthesis (`SOURCE_MD5 == NETLIST_MD5`, no `REUSE_SYNTH_DCP`). Deleting the checkpoint makes reuse impossible by construction.

- [ ] **Step 3: Run arm A — `FLOORPLAN_MODE=decode` (RTL judged WITHOUT the new box)**
Spec §11.4 R8: gate `{decode}` as well as `{decode+fetch}` **so the RTL is never judged only through a possibly-bad box.**
```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/frontend-restructure
nohup env IMPL_STRATEGY=postrouteN POSTROUTE_ROUNDS=3 FLOORPLAN_MODE=decode \
  vivado -mode batch -source synth/impl_FullCore.tcl > synth/gate_decode.out 2>&1 &
VIVADO_PID=$!
echo "arm A pid=$VIVADO_PID"
# GC-5: ACTIVE POLLING. A subagent that launches a background Vivado job and passively
# waits on a notification callback DOES NOT RELIABLY GET WOKEN -- confirmed 5+ times
# this session. Poll.
while kill -0 "$VIVADO_PID" 2>/dev/null; do sleep 120; done
echo "arm A exited"
grep -E "IMPL_STRATEGY|FLOORPLAN|TARGET_PART|POSTSYNTH_FULLCORE_WNS_NS|WNS|MHz|ERROR" synth/gate_decode.out | tail -40
```
Expected: ~35 min. Record round-0 WNS and every round's WNS (spec §10 "trajectory reporting"), final WNS, FMax, TNS, failing endpoints, CLB LUTs, CLB Registers, BRAM tiles, LUTs-as-distributed-RAM, WHS, and the Vivado error count.

Archive:
```bash
SHA=$(git rev-parse --short HEAD)
mkdir -p synth/archive/${SHA}_ufa_postrouteN3_decode
cp synth/fullcore_*.rpt synth/fullcore_*.dcp synth/gate_decode.out \
   synth/archive/${SHA}_ufa_postrouteN3_decode/ 2>/dev/null
```

- [ ] **Step 4: Run arm B — `FLOORPLAN_MODE=decode+fetch`**
```bash
rm -f synth/fullcore_synth.dcp synth/fullcore_synth.md5
nohup env IMPL_STRATEGY=postrouteN POSTROUTE_ROUNDS=3 FLOORPLAN_MODE=decode+fetch \
  vivado -mode batch -source synth/impl_FullCore.tcl > synth/gate_decode_fetch.out 2>&1 &
VIVADO_PID=$!
while kill -0 "$VIVADO_PID" 2>/dev/null; do sleep 120; done   # GC-5
grep -E "IMPL_STRATEGY|FLOORPLAN|POSTSYNTH_FULLCORE_WNS_NS|WNS|MHz|ERROR" synth/gate_decode_fetch.out | tail -40
SHA=$(git rev-parse --short HEAD)
mkdir -p synth/archive/${SHA}_ufa_postrouteN3_decode_fetch
cp synth/fullcore_*.rpt synth/fullcore_*.dcp synth/gate_decode_fetch.out \
   synth/archive/${SHA}_ufa_postrouteN3_decode_fetch/ 2>/dev/null
```
Verify from the log that `FLOORPLAN pb_fetch grid=... cells=...` appears with a plausible cell count — a token that silently failed to apply would make this arm a duplicate of arm A.

- [ ] **Step 5: Re-verify the machine was uncontended for the whole run**
```bash
pgrep -af "impl_FullCore" | wc -l   # MUST still be 0
free -g
```
Record. **If contention appeared mid-run, the numbers are not trustworthy — re-run.**

- [ ] **Step 6: Extract the new worst path (GC-10 — re-derive, do not inherit)**
```bash
for arm in decode decode_fetch; do
  SHA=$(git rev-parse --short HEAD)
  d=synth/archive/${SHA}_ufa_postrouteN3_${arm}
  echo "=== $arm ==="
  grep -A 30 "Max Delay Paths" $d/fullcore_route_timing.rpt 2>/dev/null | head -40
done
```
Record the **exact startpoint and endpoint** of the new WNS path for each arm.

**Spec §6's falsifiable claim, evaluated here and nowhere else:**
- New worst path **again inside `IcachePlugin`'s capture bank** → either an implementation error left one of the deleted banks alive (check `synth/fullcore_route_util.rpt` for `s1PredEntries`/`lineReg`/`missPred`/`predAccumLo` by name — they must be **absent**), or the diagnostic's finding #4 is about the **region** rather than the **registers**. Either is genuinely new and important information; report it as such.
- New worst path in the **D-cache/IssueQueue cluster** (the expected outcome) → the frontend is closed as an FMax target for good, and §33's option list is the honest next step.

- [ ] **Step 7: Apply the acceptance bar (spec §10), taking the better arm**

| Gate | Threshold | Action |
|---|---|---|
| **ACCEPT** | WNS ≥ **−1.322 ns** (≥ 187.5 MHz) | Land. Continue the frontend track. |
| **CONDITIONAL** | −1.472 ≤ WNS < −1.322 | Land **only if** IPC ≥ 99 % (Task 14) **and** CLB LUTs ≤ 110,679 **and** CLB Registers ≤ baseline − 1,500. Close the frontend as an FMax target. **Per GC-11, D1 is resolved as LAND — subject to Step 9's re-confirmation.** |
| **REJECT** | WNS < **−1.472 ns** | Do not land. Record as the ninth negative result and move to §33's option 1 or 3. |

Blocking secondary gates, **all** of which must pass for ACCEPT or CONDITIONAL — quote Task 14's recorded results rather than re-deriving them, and add from this task:
- Area: CLB LUTs ≤ 110,679; **Block RAM tiles ≤ 80/480** (baseline 26).
- Hold met (WHS ≥ 0), **0 Vivado errors**.

- [ ] **Step 8: Write §37 of the ledger — the twelfth honest row, either way**
```bash
cat >> .superpowers/sdd/progress-ipc-push-2026-08-09.md <<'LEDGER'

## §37 (2026-08-12) — THE SINGLE GATE: Unified Fetch Array + Verdict-Terminated Lookup, measured

Implemented per `docs/superpowers/plans/2026-08-12-large-scale-frontend-restructure-implementation-plan.md`
(M1-M5, ~FILL lines across FILL files, FILL commits), gated exactly ONCE on
`IMPL_STRATEGY=postrouteN POSTROUTE_ROUNDS=3`, fresh synthesis, uncontended machine
(0 competing `impl_FullCore.tcl` before AND after, FILL GB available), over the
floorplan matrix `{decode}` x `{decode+fetch}`.

**Staging rule honoured: no intermediate slice was post-route gated.** The two
information-only exemptions (a hierarchical utilisation read on the ARCHIVED baseline
DCP, and one `synth_design`-only memory-inference check after M1) were taken and
neither WNS was read.

| arm | round-0 WNS | r1 | r2 | r3 (final) | FMax | TNS | failing eps | CLB LUT | CLB FF | BRAM | LUTRAM |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline `6b246de` | −2.094 | −1.623 | −1.552 | **−1.472** | **182.749** | −17,499.508 | 32,408 | 110,679 | 50,389 | 26 | 9,068 |
| `decode` | FILL | FILL | FILL | FILL | FILL | FILL | FILL | FILL | FILL | FILL | FILL |
| `decode+fetch` | FILL | FILL | FILL | FILL | FILL | FILL | FILL | FILL | FILL | FILL | FILL |

**New worst path, re-derived live from each routed checkpoint (not inherited):**
- `decode`: FILL → FILL
- `decode+fetch`: FILL → FILL

**Spec §6's falsifiable claim:** FILL (either "the frontend families retired and the
limiter moved to the D-cache/IssueQueue cluster as expected", or "the limiter is again
inside IcachePlugin, which means either a bank survived — checked by name in the
utilisation report — or the diagnostic's finding #4 is about the REGION, not the
REGISTERS").

**Round-0 note:** baseline's round-0 is −2.094 and §1.1(a) established that round-0,
not the iteration loop, is the dominant variable across all eleven measured
perturbations. This design's round-0 is FILL. A round-0 better than −2.094 would be
the first ever measured in this campaign and is the single most informative
sub-result available here.

**Verdict: FILL (ACCEPT / CONDITIONAL / REJECT).**

**Fallback value, which stands regardless of the FMax verdict (spec §9.5):**
FILL CLB flops and FILL distributed-RAM LUTs removed from the frontend; `predMem` and
the `pfLine*`→`lineReg` copy datapath deleted outright; one fewer FSM state
(`PF_PRED` merged into `PREDECODE`); the demand/speculative asymmetry in the
R-channel handler gone; convergence toward the NaxRiscv `FetchCachePlugin` shape.

**Correctness: FILL** (396/396 lock-step, 149/149 fastTest, all explicit
`VerilatorTest` frontend/cache suites, four new oracles, five mutation proofs, IPC
≥ 99 % of the §36 baseline with prefetch both on and off — full table in §36.1).
LEDGER
```
Fill every `FILL`. **Do not commit a `FILL`.**

- [ ] **Step 9: Apply D1 (GC-11) and report**

If the verdict is **ACCEPT** or **REJECT**, D1 does not arise — apply the table.

If the verdict is **CONDITIONAL**, surface this to the user before landing:

> **USER DECISION NEEDED (D1) — the CONDITIONAL band, now real.** The gate returned **WNS X.XXX ns / YYY.Y MHz**, inside spec §10's CONDITIONAL band (182.7–187.5 MHz). The numeric conditions are: IPC **Z.Z %** of baseline (need ≥ 99 %), CLB LUTs **N** (need ≤ 110,679), CLB Registers **M** (need ≤ baseline − 1,500 = 48,889). **All met / not all met.**
>
> The implementation plan's GC-11 resolved D1 as **LAND**, on this reasoning: D2 authorised the build at a stated 20–30 % hit rate, and the spec's own justification for building at those odds was §9.5's explicitly **FMax-independent** fallback value. Authorising on an FMax-independent justification and then discarding on FMax alone would make the authorisation incoherent. The eight prior discards were changes whose *only* claimed value was FMax; this one is not.
>
> **This is the plan author's resolution, not yours. Confirm or override before landing.**

- [ ] **Step 10: Commit**
```bash
SHA=$(git rev-parse --short HEAD)
git add .superpowers/sdd/progress-ipc-push-2026-08-09.md \
        synth/archive/${SHA}_ufa_postrouteN3_decode \
        synth/archive/${SHA}_ufa_postrouteN3_decode_fetch
git commit -m "docs(ledger) §37: the single gate -- Unified Fetch Array measured, verdict FILL

M1-M5 gated exactly ONCE on postrouteN/POSTROUTE_ROUNDS=3, fresh synthesis,
uncontended machine (0 competing impl_FullCore.tcl before AND after), matrix
{decode} x {decode+fetch}, taking the better.

Staging rule honoured throughout: NO intermediate slice was post-route gated and no
intermediate result was allowed to abort the change. Gating M1, then M2, then M3
separately would have been the ninth, tenth and eleventh instances of the experiment
that has already failed eight times.

The new worst path was re-derived live from each routed checkpoint, not inherited --
an error class that has occurred at least three times in this campaign.

Twelfth honest row of the variant table, whichever way it went.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Appendix A — Signal-name consistency table

Every signal introduced by this plan, the task that creates it, and the tasks that consume it. **A name used in a later task must appear here with the same spelling as the task that defined it.**

| Name | Type | Defined | Consumed by | Deleted |
|---|---|---|---|---|
| `UFA_W` | `Int` = 384 | T5 | T5, T6 | — |
| `lineMem` | `Seq[Mem[Bits]]` | T5 (replaces `dataMem`) | T5–T16 | — |
| `ufaReadAddr` / `ufaReadEn` | `UInt` / `Bool` | T5 | T5–T12 | — |
| `ufaBeat` | `Vec[Bits]` | T5 | T5, T6, T11 | — |
| `ufaData(w)` / `ufaPred(w)` | `Bits` | T5 | T6, T11 | — |
| `ufaDataVec` / `ufaPredVec` | `Vec[Bits]` | T5 (transitional) | T6–T10 | **T11** |
| `dbgUfaPredMatch` | `Bool` | T5 | T5 | **T6** |
| `windowPredBeat` | `def` | T6 | T6–T12 | — |
| `windowPredLine` | `def` | T6 (transitional) | T6 | **T7** |
| `bypPred` | `Reg(Bits(32))` | T7 (replaces `missPred`) | T7–T12 | — |
| `MSHR_N` / `mshrIdxBits` | `Int` | T8 | T8, T9 | — |
| `fillLo` / `fillHi` | `Mem(Bits(256), MSHR_N)` | T8 | T8, T9 | — |
| `fillLoQ` / `fillHiQ` | `Bits` (readSync + KeepAttribute) | T8 | T8, T9 | — |
| `installIdx` | `Reg(UInt)` | T8 | T8, T9 | — |
| `installSet` | `Reg(UInt(setBits))` | T8 (SG-3) | T8–T13 | — |
| `installWay` | `Reg(UInt(wayBits))` | T9 (renames `victimWay`) | T9–T13 | — |
| `bypWindow` | `Reg(Bits(64))` | T8 | T8–T12 | — |
| `predIsPfReg` | `RegInit(False)` | T8 | T8–T12 | — |
| `INSTALL_ARM` | FSM `State` | T8 | T8–T12 | — |
| `DEMAND_IDX` | `Int` = 0 | T9 | T9–T12 | — |
| `mshrValid/ArSent/Complete/Beat/Err/Poison` | `Vec[Bool]` | T9 | T9–T12 | — |
| `mshrPa/Set/Tag/Way` | `Vec[UInt]` | T9 | T9–T12 | — |
| `s0Valid/Pc/Set/Beat/Lane/Ppn/Fault/Atc/Cacheable/FromMiss` | `Reg` | T10 (renames `s1*`) | T10–T12 | — |
| `s1Way` | `Reg(UInt(wayBits))` | pre-existing (`:313`), kept transitional | T5–T10 | **T11** (replaced by `s1WayOh`) |
| `s0Replay` | `Reg(Bool)` | T11 | T11 | — |
| `tagQ` / `validsQ` / `pfFilledQ` | `Reg(Vec(...))` | T10 (`pfFilledQ` T11) | T10–T12 | — |
| `s1HitVec` / `s1Hit` | `Vec[Bool]` / `Bool` | T10 | T10–T12 | — |
| `s1Unresolved` | `Bool` | T10 | T10–T12 | — |
| `dbgVerdictMatch` / `dbgLiveHitQ` / `dbgLiveFaultQ` | `Bool` / `Reg` | T10 | T10 | **T11** |
| `pfAcceptOk` | `Bool` (SG-1) | T11 | T11, T12 | — |
| `s1WayOh` | `Vec[Bool]` (SG-2) | T11 | T11, T12 | — |
| `startDemandFill` | `Bool` | T11 | T11 | — |
| `s1Disp` / `s1Line` | `Reg` bundle / `Reg(UInt(32))` | T12 | T12 | — |
| `heldDemandMissQ` | `Bool` | T12 (replaces `heldDemandMiss`) | T12 | — |
| `seedPfWindow(line: UInt)` | `def` | T12 (signature change) | T12 | — |

Test-side, all in `IcacheArrayProbe`: `wayData` / `wayPred` / `wayTag` / `wayValid` / `snapshotWay` / `assertUnchanged` (T3), `mshrValid` / `mshrSet` / `mshrArSent` / `mshrComplete` / `pfSlotValid` / `pfSlotArSent` / `pfSlotComplete` / `pfSlotSet` (T9). Reusable driver: `IcacheOrderOracle.runOrderedStream` (T4).

## Appendix B — Spec coverage map

| Spec item | Implemented by |
|---|---|
| M1 — Unified Fetch Array (§5.1) | **T5** (write side + shadow) + **T6** (read side + deletions) |
| M2 — Unified MSHR line file (§5.2) | **T7** (bypass predecode) + **T8** (line file, `lineReg`, `PF_PRED`) + **T9** (control file) |
| M3 — Verdict-Terminated Lookup (§5.3) | **T10** (S0 capture + shadow verdict) + **T11** (flip + deletions) |
| M4 — registered-state prefetch/AR (§5.4) | **T12** |
| M5 — `pb_fetch` floorplan (§5.5, §7) | **T15** |
| §12.1 oracle 1 (response order) | **T4** |
| §12.1 oracle 2 (one AR per line) | **T4** |
| §12.1 oracle 3 (MSHR set exclusivity) | **T4**, extended to entry 0 in **T9** |
| §12.1 oracle 4 (unified-array equivalence) | **T5** |
| §12.2 mutation proofs ×5 | **T13** |
| §12.3 suites + prefetch sweep | **T14** |
| §13 S-pre (a) utilisation read | **T1** |
| §13 S-pre (b) `prefetchEnable=0` IPC | **T2** |
| §13 S0 test-surface refactor (risk R5) | **T3** |
| §10 acceptance bar + trajectory reporting | **T16** |
| §11.3 "stage by correctness, gate once" | **GC-1**, enforced in every task |
| §14 Q1 (`tagMem` readSync vs registered readAsync) | **T10** — resolved as registered `readAsync` (spec's own recommended default) |
| §14 Q2 (fold vs parallel `predMem`) | **T1** (input) + **T6 Step 8** (decision + fallback) |
| §14 Q3 (`pfFilled`, 256 telemetry flops) | **Deliberately not addressed.** Spec leaves it alone to keep the prefetch measurement methodology intact; this plan does the same. Revisit only if D4 resolves to DELETE. |
| §14 Q4 (`valids`) | **Explicitly not proposed** by the spec; not addressed. |
| §14 Q5 / **D4** | **GC-12** + **T2** (measurement) — **open user decision**, default KEEP |
| **D1** (CONDITIONAL band) | **GC-11** — resolved as LAND, re-confirmed at **T16 Step 9** |
| **D2** (build or not) | Already resolved by the user: "implement it now." |
| **D3** (Phase 2 predictor complex) | **Out of scope** per **GC-8**. `Btb`/`Ftb`/`Gshare`/`Ras` are read-only reference material in this plan. |
