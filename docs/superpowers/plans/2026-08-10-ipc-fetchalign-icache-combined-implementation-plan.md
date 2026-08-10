# FetchAlign→I-cache Pipeline + D-cache/IQ Ready Skid: Combined Two-Arc Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land **two** of the three tied critical arcs at once — the FetchAlign→I-cache demand arc (a real F1/F2 pipeline stage plus a free speculative-state decoupling) and the D-cache→IssueQueue scoreboard arc (a registered IQ→LS-EU issue-port ready skid) — and measure every combination of them post-route, because each arc alone is *measured* at +0.000 ns WNS and only a multi-arc landing can move the number.

**Architecture:** Three RTL changes behind two elaboration flags. (1) **B3, free:** `IcachePlugin`'s speculative prefetch/install state (`lineReg`, the fanout-518 install select, the prefetch window seed, the AR arbiter) stops being driven by the live demand verdict and is driven from registered context instead — zero resident-hit cycles, no flag, lands unconditionally. (2) **B2, flagged `icacheVerdictStage`:** the I-cache demand path becomes `F1 (accept + translate) → F2 (verdict + dispatch) → S1 → rsp`; acceptance moves out of the verdict cycle so initiation interval stays 1 and the only cost is +1 cycle of fetch latency; `FetchAlignPlugin`'s outstanding ring goes 3→4 to cover it. (3) **Fix A, flagged `lsIssueReadySkid`:** the IQ→LS-EU issue port gets a registered-ready skid, severing the 22-level combinational `stS2Payload_paddr → … → sbNzvc_busy` accept-last chain at its widest point. Every measurement is a real post-route run under `IMPL_STRATEGY=postrouteN` / `POSTROUTE_ROUNDS=3`.

**Tech Stack:** SpinalHDL (Scala 2.13, `spinal.lib.fsm.StateMachine`, `spinal.lib.Stream`), sbt + ScalaTest (`AnyFunSuite`), Verilator simulation, Vivado 2025.2 batch flow on `xcku5p-ffvb676-2-e` at a 4.000 ns constraint.

---

## Why this plan exists: the tie, and why nothing else in it matters without it

`docs/superpowers/specs/2026-08-10-ipc-fetchalign-icache-pipeline-design.md` §9.1 and handoff §19 step 2 measured, on the same frozen routed checkpoint:

| routed path | slack |
|---|---:|
| #1 `DcachePlugin stS2Payload_paddr[5]` → `IssueQueuePlugin sbNzvc_busy[8]` | **−1.472 ns** |
| #2 `FetchAlignPlugin stalled` → `IcachePlugin lineReg[418]` | **−1.472 ns** |

Tied to three decimals, from unrelated subsystems. Handoff §19 step 2 then measured **Fix A alone = +0.000 ns, Fix B alone = +0.000 ns, both together = +0.000 ns**, and handoff §20 step 2 measured **the whole ITLB hit-way cone alone = +0.000 ns**. Deleting either arc simply exposes the other at the identical slack.

The design spec states the consequence in §9.4 and it is the thesis of this plan:

> **this arc and the D-cache/IQ arc are tied. Neither alone moves WNS. The program only pays if at least two of the three arcs land.**

Therefore: **no task in this plan is judged solo.** A slice that measures +0.000 ns on its own is *expected* to and is not evidence of failure. The only verdict-bearing measurement is the combination matrix in Task 21, and the only kill criterion is the falsifier in Task 23.

---

## Which D-cache/IQ fix this plan builds, and why (a judgment call, made explicitly)

Handoff §16 step 5 shelved two candidates. This plan builds **Fix A (the IQ→LS-EU issue-port ready skid)** and does **not** build Fix B (the `earlyProbeSetWriteVec` retime). The grounds, from the sources rather than from preference:

1. **§19 step 6's refutation of Fix A is explicitly conditional, and this plan removes the condition.** It reads: *"What this section refutes is the ready-chain pipeline stage (Fix A) as a **timing** proposal specifically — its over-cut upper bound is 0.000 ns, which a placement argument cannot rescue, **because the tie is with a different subsystem entirely**."* The tie being with a different subsystem is exactly what Slice 2 of this plan attacks. Fix A's refutation was conditioned on the FetchAlign→I-cache arc standing; here it does not stand.
2. **Fix A is in the category §20 step 6 says is the only one left; Fix B is in the category it declared exhausted.** §20 step 6: *"What is left is not a timing cut… requires reducing average logic depth, which means real architectural pipelining — splitting deep combinational stages across the FetchAlign → I-cache demand/install arc …, **the D-cache store pipe → IQ scoreboard wakeup arc (1,370 / 1,032)**, and the DecodeStage → RAS/ROB arc."* Fix A *is* a stage split of that named arc. Fix B is a retime — the lever whose ceiling §19 step 4 measured at +0.012 ns for every candidate on file applied at once.
3. **Fix B's correctness risk was re-verified against the routed netlist and did not improve.** §19 step 5: `wrEn`/`wrTagEn` are combinational defaults driven in the same cycle the write takes effect, so `earlyProbeSetWriteVec` compares against the **live** write port by construction; a registered stale-invalidate is visible only at N+1 while `loadCmdPort.fire` consumes the snapshot at N. *"The hole is real, it is exactly one cycle wide, and it is precisely the case the live compare exists to catch: a load consuming a pre-store snapshot of a set that a drained (already-retired) store just wrote, which the store queue can no longer forward for."* That is a silent-corruption class. Both §16 step 5 and §19 step 5 conclude Fix B should be justified **on throughput grounds** *if the design becomes IPC-limited by `loadProbe.ready` back-pressure* — a condition that has not been shown to hold.
4. **Fix A carries no comparable invariant risk.** §16 step 5: *"it is the textbook fix for a long combinational ready chain, it is architecturally clean, and it costs a real `IqContext`-width buffer plus a re-proof of the LS ordering/flush contract."* A re-proof obligation is a verification cost, and Task 19 discharges it explicitly, obligation by obligation.
5. **The one honest argument for Fix B is recorded and not dismissed.** §19 step 2 measured Fix B at **2.16 % of TNS / 234 endpoints** against Fix A's over-cut upper bound of **0.72 % / 45 endpoints** — roughly 5× the breadth on the metric this whole program is prioritised by. That is why Fix B is **not deleted**: Task 24 step 3 records a mechanical two-part condition under which it is re-opened, keyed on whether the Task 21 matrix and its recensus still show the D-cache/IQ cone binding after Fix A lands.

---

## Global Constraints

Every task's requirements implicitly include this section. Values are copied verbatim from `docs/superpowers/specs/2026-08-10-ipc-fetchalign-icache-pipeline-design.md`, `docs/superpowers/specs/2026-08-10-icache-parallel-vipt-design.md`, and `docs/superpowers/plans/2026-08-10-ipc-frontend-fmax-claude-handoff.md`.

- **GC1 — Combined measurement is mandatory; no slice is judged solo.** The design spec §9.4: *"this arc and the D-cache/IQ arc are tied. Neither alone moves WNS. The program only pays if at least two of the three arcs land."* Consequently: **a +0.000 ns solo result for Slice 1, Slice 2, or Slice 3 is the expected outcome and is NOT grounds to revert, re-scope, or abandon a slice.** The only verdict-bearing measurement is the **four-cell post-route matrix** of Task 21 (`M0` both flags off, `M1` `icacheVerdictStage` only, `M2` `lsIssueReadySkid` only, `M3` both on). No task before Task 21 may record a go/no-go verdict on FMax.

- **GC2 — The written falsifier, exactly as the design spec states it (§9.4).** *"If the real post-route gate for slices 1+2 measures **ΔWNS < +0.050 ns** and the IPC gate measures **> 1.0 % aggregate loss**, then: slice 2 is a net regression in delivered performance and must be **reverted** (or left disabled behind its elaboration flag); slice 1, being free, stays regardless; and the program should be re-scoped to attack the D-cache/IQ arc first."* Task 23 evaluates this as a literal two-term AND against the Task 21/22 numbers and records the mechanical outcome. It is a **conjunction**: a slice that loses IPC but gains ≥ +0.050 ns is **kept**, and a slice that gains nothing but costs ≤ 1.0 % IPC is **kept**.

- **GC3 — Architecture is binding; amend the spec before the RTL.** Handoff §2. The three amendments A1/A2/A3 in design spec §8 to `docs/superpowers/specs/2026-08-10-icache-parallel-vipt-design.md` **must land as their own documentation commit (Task 2) before any RTL in Slice 2 is written**. This is the discipline handoff §13/§14 used for the two cuts that actually landed.

- **GC4 — `make SBT=~/sbt/bin/sbt test-fast` green at every task boundary.** Recorded baseline: **149/149 tests, 157 suites completed, 0 failed, 0 aborted** (handoff §20 step 7). Task 1 re-verifies this on the actual HEAD in use; do not assume it. A task is not complete with a red gate.

- **GC5 — FMax gates are REAL post-route, never OOC.** `~/sbt/bin/sbt 'runMain m68k040.top.GenFullCoreSynthVerilog'` followed by `vivado -mode batch -nojournal -source synth/impl_FullCore.tcl` with `FLOORPLAN_MODE=decode`, `IMPL_STRATEGY=postrouteN`, `POSTROUTE_ROUNDS=3`. A synthesis-only WNS is an early signal, never a substitute (handoff §9). `IMPL_STRATEGY=default` is only ever used to reproduce a pre-§18 historical row.

- **GC6 — Every post-route report includes the sub-(−1.000 ns) endpoint population.** Design spec §11: the standard report set for this program is **WNS, FMax, TNS, failing endpoints, AND the count of endpoints below −1.000 ns**, plus LUT/FF/BRAM/DSP and top-path family. The population number is the metric §20 step 4b introduced and the metric this design is prioritised by; omitting it makes a run unusable for the Task 21 matrix.

- **GC7 — Machine-contention discipline, checked before *every* Vivado run.** Run `free -g` and `ps aux --sort=-%mem | head -15` first. Require **≥ 20 GB available** and **no other Vivado process of any kind**, including an interactive `-mode tcl` JTAG session from a sibling project — one was live during the writing of this plan (PID 577887, `/tools/Vivado/2025.2/…vivado -mode tcl`), and this project has repeatedly measured 214.3 vs 163.9 MHz for an identical commit under contention. Launch under `setsid` and watch it with the Monitor tool; never block a foreground turn on a multi-hour run. **A contended FMax number is not a measurement and must not be entered into the Task 21 matrix.**

- **GC8 — Worktree isolation for A/B.** All work happens in `/home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01` on branch `codex/ipc-dcache-vipt`. Never `git checkout <sha>` in a shared tree for a before/after comparison — use `git worktree add`. A real collision happened before (ported-tests task #199).

- **GC9 — Do not discard any landed work.** The six landed timing cuts (`3c4e1f8`, `dc16fc1`, `cc22cd0`, `9101c5a`, `28ec738`, `6b246de`) and the §18 implementation-strategy discovery (`IMPL_STRATEGY=postrouteN` default, +18.95 MHz) all stay. Reverting any restores a previously measured limiter (handoff §8). `synth/impl_FullCore.tcl`'s `postrouteN` default must not be changed.

- **GC10 — Never assume a SoC address-decode map in `src/main/scala`.** Standing project rule. Address behaviour used by tests is a simulation-harness property only.

- **GC11 — Scope lock.** The only `src/main/scala` files this plan may modify are:
  `src/main/scala/m68k040/cache/IcachePlugin.scala`,
  `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala`,
  `src/main/scala/m68k040/execute/LsEuPlugin.scala`,
  `src/main/scala/m68k040/top/FullCoreSynth.scala`.
  **`Tlb.scala` and `ItlbPlugin.scala` are read-only** (design spec §1.2 — the class is shared with the DTLB and a registered output there would risk the D-side load path for no I-side benefit). **`DcachePlugin.scala` and `IssueQueuePlugin.scala` are read-only contracts** — Fix A is implemented entirely on the consumer side inside `LsEuPlugin`. The `DecodeStage → RAS/ROB` arc is out of scope entirely (design spec §1.2).

- **GC12 — II = 1 is the binding constraint of Slice 2, and it must be *measured*, not argued.** Design spec §5: *"The mandatory proof obligation is that II = 1 is measured, not argued: `FetchAlignResidentCadenceSpec` … is the gate, and slice 2 does not land if it regresses."* An in-RTL II tripwire (Task 15, oracle 3) backs it up in every simulation.

- **GC13 — The acceptance metric is `delivered = IPC_aggregate × FMax`, not either alone (design spec §10.1).** Baselines to beat: FMax **182.749 MHz**; 11-kernel aggregate IPC **0.6739** (ideal memory) and **0.5465** (`l2:5:70`); delivered **123.15** and **99.87** M-instr/s respectively. Break-even table (design spec §10.1): 0.5 % IPC cost needs 183.67 MHz / +0.027 ns; **1.0 % needs 184.60 MHz / +0.055 ns**; 1.5 % needs 185.53 MHz / +0.081 ns; 2.0 % needs 186.48 MHz / +0.108 ns; 3.0 % needs 188.40 MHz / +0.163 ns.

- **GC14 — Both memory models are mandatory on every IPC gate, and the ideal model gates.** Design spec §10.2: *"the `l2:5:70` model partially hides the extra cycle behind memory latency, so the ideal-memory model is the pessimistic bound and is the one that gates."* Three pinned seeds, both models, every time.

- **GC15 — Area growth is a review point, not an automatic rejection** (handoff §2). Report LUT/FF/BRAM/DSP deltas and their source. The design spec §13 expects **≈ +89 flops** for F1/F2 and a **net-negative LUT** change (the `ringInc` comparator and the live install-veto cone are deleted). Fix A adds one `IqContext`-width register set.

- **GC16 — Do not deepen either TLB, and add no associative structure.** Handoff §2 standing rule and parallel-VIPT §4. Slice 2 adds a *pipeline stage in `IcachePlugin`*, never an ITLB entry, way, bank, or second lookup copy.

- **GC17 — `FetchRsp` stays untagged and in acceptance order.** Design spec §1.2. No response tagging, no hit-under-miss, no cancel path for an accepted fetch. The in-order guarantee is enforced structurally by the F1/F2 stage discipline and proved by the Task 15 ordering oracle.

- **GC18 — Every `set_false_path` what-if must print, and assert on, the number of objects it matched.** Handoff §19 step 1: a glob that matches zero nets is a silent no-op indistinguishable from a cut worth nothing, and this campaign already lost a measurement to it. `synth/probe_dcache_iq3.tcl` errors out on a zero match; every probe added by this plan must do the same.

- **GC19 — Ledger discipline.** `.superpowers/sdd/progress-ipc-push-2026-08-09.md` lives in the **MAIN** repo at `/home/qwertyoruiop/m68k-core-040-ooo/.superpowers/sdd/progress-ipc-push-2026-08-09.md`. It is git-ignored-but-force-tracked: stage it with `git add -f`. Pin the exact HEAD sha and the generated-Verilog MD5 in the ledger **before** every synth run (handoff §9).

- **GC20 — Tests must be non-vacuous.** Handoff §2: *"Throughput tests must count real fires, associations, results, and non-vacuity conditions, not merely valid pulses."* Every directed test in this plan asserts on exact PCs, exact opwords, exact response counts, and exact cycle numbers, and every new mechanism is backed by a mutation that provably trips it.

---

## File Structure

| file | action | responsibility |
|---|---|---|
| `docs/superpowers/specs/2026-08-10-icache-parallel-vipt-design.md` | **Modify** (Task 2) | Land binding amendments A1 (§1 cycle contract 2→3 cycles, II=1), A2 (§2 recovery taken in a bandwidth-preserving form, register lives in `IcachePlugin` not `Tlb`), A3 (§3.2 accept-behind-a-miss → *answer*-behind-a-miss). |
| `synth/probe_combined_arcs.tcl` | **Create** (Task 3) | Model B2 + B3 + Fix A fix points together on the archived routed DCP, with a hard error on any zero-match cut, reporting WNS/TNS/failing-endpoints **and** the sub-(−1.000 ns) endpoint population per cut and per combination. |
| `src/main/scala/m68k040/cache/IcachePlugin.scala` | **Modify** (Tasks 4–6, 8–12, 15, 23) | Slice 1's three decouplings (unconditional); the `icacheVerdictStage` flag; the F1 stage; `lookupTick` relocated to F2 over registered context; the three in-RTL oracles. |
| `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala` | **Modify** (Tasks 8, 11) | `RING` parameterised as `ringDepth`, 3 → 4 when the verdict stage is enabled. Nothing else. |
| `src/main/scala/m68k040/execute/LsEuPlugin.scala` | **Modify** (Tasks 17–19, 23) | The `lsIssueReadySkid` flag and the registered-ready skid on the IQ→LS-EU issue port, with flush/poison handling. |
| `src/main/scala/m68k040/top/FullCoreSynth.scala` | **Modify** (Tasks 8, 17, 23) | Read `ICACHE_VERDICT_STAGE` / `LS_ISSUE_READY_SKID` from the environment in `GenFullCoreSynthVerilog` so all four matrix cells build without a source edit. |
| `src/test/scala/m68k040/cache/IcacheFetchPipelineSpec.scala` | **Create** (Task 13) | The nine directed cases of design spec §12.3 against the real `ItlbPlugin` + real walker + `BehavioralMemAgent`. |
| `src/test/scala/m68k040/cache/IcachePrefetchInstallPrioritySpec.scala` | **Create** (Task 4) | Slice 1a's directed test + mutation M5: a completed install and a demand miss in the same cycle both complete, in the specified order, within the bounded wait. |
| `src/test/scala/m68k040/ls/LsIssueReadySkidSpec.scala` | **Create** (Task 19) | Fix A's ordering/flush re-proof: skid occupancy, flush poisoning, store-queue allocation order, no lost or duplicated µop, plus its mutation proof. |
| `.superpowers/sdd/progress-ipc-push-2026-08-09.md` (MAIN repo) | **Modify** (Tasks 1, 3, 7, 14, 16, 20, 21, 22, 23, 24) | Ledger entries. `git add -f`. |
| `docs/superpowers/plans/2026-08-10-ipc-frontend-fmax-claude-handoff.md` | **Modify** (Task 24) | New section §23 recording the combined-arc result. |

---

## Slice map

| slice | tasks | what it is | flag | judged |
|---|---|---|---|---|
| **0 — ground** | 1–3 | baseline re-verification, the three binding spec amendments, the combined-arc probe | — | reproduction only |
| **1 — B3, free** | 4–7 | decouple the speculative install/prefetch state from the live verdict | none (lands unconditionally) | matrix cell `M0` |
| **2 — B2, the stage** | 8–16 | the F1/F2 split + `RING` 3→4 | `icacheVerdictStage` | matrix cell `M1` |
| **3 — Fix A, arc 2** | 17–20 | the IQ→LS-EU registered-ready skid | `lsIssueReadySkid` | matrix cell `M2` |
| **4 — combine + decide** | 21–24 | the four-cell matrix, the IPC×FMax check, the falsifier, the hand-off | both | matrix cell `M3` — **the verdict** |

---

# Slice 0 — Ground the baseline and the boundary set (no RTL)

## Task 1: Re-verify and pin the baseline

**Why first:** every number in GC13 descends from one frozen checkpoint and one recorded test run. This campaign has twice been saved by reproducing a baseline before trusting a delta (handoff §19 step 0, §21). Nothing here changes RTL.

**Files:**
- Modify: `/home/qwertyoruiop/m68k-core-040-ooo/.superpowers/sdd/progress-ipc-push-2026-08-09.md`

**Interfaces:**
- Consumes: nothing.
- Produces: pinned values later tasks quote — `BASE_SHA`, `BASE_VERILOG_MD5`, `BASE_TESTFAST` (expected `149/149, 157 suites`), `BASE_WNS = -1.472`, `BASE_FMAX = 182.749`, `BASE_TNS = -17499.508`, `BASE_FEP = 32408`, `BASE_POP1000 = 4890`.

- [ ] **Step 1: Confirm the worktree, the branch, and that RTL is unchanged since the head checkpoint**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
git status --short --branch
git rev-parse HEAD
git diff --stat 6b246de..HEAD -- src/
```

Expected: branch `codex/ipc-dcache-vipt`; `git diff` over `src/` prints **nothing** (the RTL is byte-identical to the `6b246de` head checkpoint). Untracked `synth/*.out`, `synth/archive/`, `iter_*_CongestedCLBsAndNets.txt` are expected and must not be committed.

- [ ] **Step 2: Run the mandatory repository gate**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -20
```

Expected: `149` tests passed, `157` suites completed, `0` failed, `0` aborted. If the number differs, **stop and record the new number as the baseline** — every later task compares against whatever this run reports, not against 149 by assumption.

- [ ] **Step 3: Regenerate the synthesis Verilog and pin its MD5**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
~/sbt/bin/sbt 'runMain m68k040.top.GenFullCoreSynthVerilog'
md5sum generated/M68kFullCoreSynth.v
```

Expected: `d80f6218c5c7dcab94a33a52d64244fa` (handoff §19). Handoff §21 proved re-elaboration at HEAD is bit-exact, so a different MD5 here means something in the tree changed and must be explained before proceeding.

- [ ] **Step 4: Reproduce the archived routed baseline read-only (no synthesis)**

Check the machine and the Vivado mutex first (GC7):

```bash
free -g
ps aux --sort=-%mem | head -15
flock -n /var/tmp/m68k-ooo-vivado.lock true && echo LOCK_FREE || echo LOCK_HELD
```

Then, only if `LOCK_FREE` and ≥ 20 GB available:

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
setsid flock /var/tmp/m68k-ooo-vivado.lock \
  env DCP=synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp \
      OUT=synth/probe_baseline_repin \
  vivado -mode batch -nojournal -nolog -source synth/probe_population_after_cuts.tcl \
  > synth/probe_baseline_repin_runner.out 2>&1
```

Watch it with the Monitor tool rather than blocking:

```
Monitor: tail -f /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01/synth/probe_baseline_repin_runner.out | grep -E --line-buffered "PROBE_POPCUT|PROBE_DONE|ERROR|error|Fatal"
```

Expected first line: `PROBE_POPCUT baseline                        sub(-1.000) endpoints: 4890`.

- [ ] **Step 5: Record the pinned baseline in the ledger and commit**

Append a section to `/home/qwertyoruiop/m68k-core-040-ooo/.superpowers/sdd/progress-ipc-push-2026-08-09.md` titled `## Combined two-arc plan — Task 1 baseline pin (<date>)` containing, as a literal table: `BASE_SHA`, `BASE_VERILOG_MD5`, `BASE_TESTFAST`, `BASE_WNS -1.472`, `BASE_FMAX 182.749`, `BASE_TNS -17499.508`, `BASE_FEP 32408`, `BASE_POP1000 4890`, `BASE_IPC_IDEAL 0.6739`, `BASE_IPC_L2 0.5465`.

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add -f .superpowers/sdd/progress-ipc-push-2026-08-09.md
git commit -m "ledger: pin the combined two-arc plan's baseline (test-fast, netlist MD5, routed WNS/TNS/FEP/population)"
```

---

## Task 2: Land the three binding spec amendments (documentation only, BEFORE any RTL)

**Why:** GC3. `docs/superpowers/specs/2026-08-10-icache-parallel-vipt-design.md` is binding and its §1, §2 and §3.2 currently forbid or contradict what Slice 2 builds. Handoff §13/§14 used exactly this discipline for the two cuts that landed.

**Files:**
- Modify: `docs/superpowers/specs/2026-08-10-icache-parallel-vipt-design.md` (§1 lines 11–35, §2 lines 36–58, §3.2 lines 69–77)

**Interfaces:**
- Consumes: design spec §8's A1/A2/A3 text.
- Produces: the amended binding contract that Tasks 9–16 implement against. No code.

- [ ] **Step 1: Amend §1 — the cycle contract (A1)**

Replace the sentence *"The resident L1I hit path is a two-cycle, initiation-interval-one pipeline."* and the three numbered cycles beneath it with an amendment block appended to §1:

```markdown
### 1.1 Amendment A1 (2026-08-10) — three-cycle, II = 1

Superseded by `2026-08-10-ipc-fetchalign-icache-pipeline-design.md` §4 when
`IcachePlugin(icacheVerdictStage = true)`. The resident L1I hit path becomes a
**three-cycle, initiation-interval-one** pipeline:

- Cycle N — **F1**: `cmd.fire` accepts the command into F1 and launches the
  ITLB from the registered PC.
- Cycle N+1 — **F2**: the *registered* translation qualifies the async tags for
  the *registered virtual* set, arms the data BRAM, captures the S1 context,
  and captures the miss context.
- Cycle N+2 — **S1**: registered way/lane control selects the BRAM output.
- Cycle N+3 — `FetchRsp.valid`.

The clean-redirect first-useful-group figure moves from **N+4 to N+5**.
Resident commands and responses remain **II = 1**; the cost is latency, not
throughput. With `icacheVerdictStage = false` the original two-cycle contract
above holds unchanged.
```

- [ ] **Step 2: Amend §2 — the recovery is taken, in a bandwidth-preserving form (A2)**

Append to §2, after the sentence *"Do not restore a translation-to-BRAM-address dependency or delete the response register."*:

```markdown
### 2.1 Amendment A2 (2026-08-10) — the pre-authorised recovery is exercised

The permitted recovery ("pipeline the ITLB's internal hit-way result") is taken,
with two clarifications this section did not specify:

1. The register lives at the **fetch pipe's F1/F2 boundary inside
   `IcachePlugin`**, not inside `Tlb.scala` or `ItlbPlugin.scala`. The `Tlb`
   class is shared with the DTLB; a registered output there would put the D-side
   load path at risk for no I-side benefit. Both files remain untouched.
2. **Acceptance moves into a pipeline stage** so that II = 1 is preserved. A
   naive registered hit-way with a single-cycle accept would force fetch II = 2;
   that shape is explicitly rejected.

Both prohibitions of §2 are retained in full: **no translation-to-BRAM-address
dependency is restored** — the BRAM address remains `f2Pc(11 downto 6)`, purely
virtual and registered — and **the response register is not deleted**.
```

- [ ] **Step 3: Amend §3.2 — accept-behind-a-miss becomes answer-behind-a-miss (A3)**

Append to §3.2, after *"This is intentional: `FetchRsp` is untagged and must remain in acceptance order."*:

```markdown
### 3.2.1 Amendment A3 (2026-08-10) — the rule constrains *answering*, not *accepting*

Under `icacheVerdictStage = true` the sentence above is amended to: **the cache
does not *answer* one extra younger command behind a newly discovered miss.** It
may *hold* one in F2 and one in F1. The untagged in-acceptance-order guarantee is
unchanged and is now enforced **structurally** by the in-order F1/F2 stage
discipline rather than by refusing acceptance: F2 holds while it cannot dispatch,
F1 holds behind it, and `REPLAY` answers the missing command before F2 dispatches
the command behind it.

The wrong-path speculation bound grows by exactly one fetch window, which
`FetchAlignPlugin`'s `RING = 4` covers and which `FtqCapacitySpec` re-measures.
```

- [ ] **Step 4: Commit the amendments alone**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
git add docs/superpowers/specs/2026-08-10-icache-parallel-vipt-design.md
git commit -m "docs(spec): binding amendments A1/A2/A3 to the parallel-VIPT contract for the F1/F2 fetch pipeline"
```

Expected: the commit touches **only** that one file. No RTL may be in this commit (GC3).

---

## Task 3: `synth/probe_combined_arcs.tcl` — model B2 + B3 + Fix A together

**Why:** design spec §11 slice 0. The existing probes model each cut alone; the whole thesis of this plan is that only combinations matter, and no probe on file models B3's install/prefetch fix points at all. This is a **read-only checkpoint open**, not a synthesis run.

**Files:**
- Create: `synth/probe_combined_arcs.tcl`
- Reference (do not modify): `synth/probe_itlb_hitway.tcl` (the `summarise` / `worstpath` / erroring-cut idiom), `synth/probe_population_after_cuts.tcl` (the `pop` proc)

**Interfaces:**
- Consumes: `synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp`.
- Produces: `synth/probe_combined_arcs/whatif_summary.txt` and `.../population.txt`, with one row per scenario in the set `{baseline, B3, B2, B2+B3, fixA, B2+B3+fixA}`, each carrying WNS / TNS / failing endpoints / sub-(−1.000 ns) population.

- [ ] **Step 1: Write the script**

```tcl
# Combined-arc what-if probe (implementation plan 2026-08-10, slice 0).
#
# Models B2 (the ITLB hit-way / F1-F2 boundary), B3 (the speculative
# install/prefetch decoupling) and D-cache/IQ Fix A (the IQ->LS ready skid)
# INDIVIDUALLY and IN COMBINATION on one frozen routed checkpoint.  The point of
# the exercise is the combinations: handoff sections 19-20 already measured each
# cut alone at +0.000 ns, and design spec section 9.4 states that the arcs are
# tied so no single cut can move WNS.
#
# Every cut asserts on its matched-object count.  A zero-match set_false_path is
# a silent no-op indistinguishable from a cut worth nothing (handoff 19 step 1).
#
#   DCP=synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp \
#   OUT=synth/probe_combined_arcs \
#   vivado -mode batch -nojournal -nolog -source synth/probe_combined_arcs.tcl

set dcp "synth/archive/6b246de_default_postrouteN3_decode/fullcore_routed.dcp"
if {[info exists ::env(DCP)]} { set dcp $::env(DCP) }
set out "synth/probe_combined_arcs"
if {[info exists ::env(OUT)]} { set out $::env(OUT) }
file mkdir $out
puts "PROBE_DCP $dcp"

proc worstpath {} {
  return [lindex [get_timing_paths -max_paths 1 -nworst 1 -delay_type max] 0]
}

proc pop {} {
  return [llength [get_timing_paths -quiet -max_paths 300000 -nworst 1 \
            -slack_lesser_than -1.000 -delay_type max]]
}

proc summarise {tag} {
  global out
  set f $out/summary_$tag.rpt
  report_timing_summary -no_detailed_paths -quiet -file $f
  set wns "?" ; set tns "?" ; set fep "?"
  set fh [open $f r]
  while {[gets $fh line] >= 0} {
    if {[regexp {^\s+(-?[0-9]+\.[0-9]+)\s+(-?[0-9]+\.[0-9]+)\s+(\d+)\s+(\d+)\s+(-?[0-9]+\.[0-9]+)} $line -> a b c d e]} {
      set wns $a ; set tns $b ; set fep $c ; break
    }
  }
  close $fh
  set p [worstpath]
  set line [format "%-24s WNS %8s  TNS %14s  FEP %8s  POP1000 %6s   %s -> %s" \
    $tag $wns $tns $fep [pop] \
    [get_property STARTPOINT_PIN $p] [get_property ENDPOINT_PIN $p]]
  puts "PROBE $line"
  return $line
}

# ---- B2: the ITLB hit-way / F1-F2 boundary ---------------------------------
proc cut_b2 {} {
  set n [get_nets -quiet -hierarchical -filter \
    {NAME =~ *ItlbPlugin_logic_tlb_io_hit* || NAME =~ *ItlbPlugin_logic_tlb/hitVec* || NAME =~ *ItlbPlugin_logic_tlb/_zz_hitVec*}]
  puts "PROBE_CUT b2_itlb_hitway nets: [llength $n]"
  if {[llength $n] == 0} { error "PROBE_CUT b2_itlb_hitway matched nothing -- refusing to report a false zero" }
  set_false_path -through $n
}

# ---- B3: the speculative install / prefetch decoupling ----------------------
# Everything B3 takes off the LIVE demand verdict: the install select and its
# fanout-518 net, the lineReg capture cone, the prefetch-window seed registers
# and the AR arbiter's hold.
proc cut_b3 {} {
  set n [get_nets -quiet -hierarchical -filter \
    {NAME =~ *IcachePlugin_logic_demandFillStart* || \
     NAME =~ *IcachePlugin_logic_pfInstallIdx* || \
     NAME =~ *IcachePlugin_logic_pfInstallAny* || \
     NAME =~ *IcachePlugin_logic_pfInstallSel* || \
     NAME =~ *IcachePlugin_logic_heldDemandMiss* || \
     NAME =~ *IcachePlugin_logic_pfWindowUpdate*}]
  set c [get_cells -quiet -hierarchical -filter {NAME =~ *IcachePlugin_logic_lineReg_reg*}]
  puts "PROBE_CUT b3_install nets: [llength $n]  lineReg cells: [llength $c]"
  if {[llength $n] == 0} { error "PROBE_CUT b3_install matched no nets -- refusing to report a false zero" }
  if {[llength $c] == 0} { error "PROBE_CUT b3_install matched no lineReg cells -- refusing to report a false zero" }
  set_false_path -through $n
  set_false_path -to $c
}

# ---- Fix A: the IQ -> LS-EU ready chain -------------------------------------
# Modelled as the same deliberate OVER-CUT handoff section 19 step 1 adopted
# after the `selPorts_3_m2sPipe_ready` glob matched zero nets: every D-cache /
# LS-EU cell false-pathed to every IssueQueue cell.  The real skid removes only
# the ready arc, so the real Fix A is worth no more than this.
proc cut_fixa {} {
  set src [get_cells -quiet -hierarchical -filter \
    {NAME =~ *DcachePlugin_logic_* || NAME =~ *LsEuPlugin_logic_*}]
  set dst [get_cells -quiet -hierarchical -filter {NAME =~ *IssueQueuePlugin_logic_*}]
  puts "PROBE_CUT fixa src cells: [llength $src]  dst cells: [llength $dst]"
  if {[llength $src] == 0 || [llength $dst] == 0} { error "PROBE_CUT fixa matched nothing" }
  set_false_path -from $src -to $dst
}

set log {}
foreach scenario {baseline b3 b2 b2_b3 fixa b2_b3_fixa} {
  open_checkpoint $dcp
  switch $scenario {
    baseline    { }
    b3          { cut_b3 }
    b2          { cut_b2 }
    b2_b3       { cut_b2 ; cut_b3 }
    fixa        { cut_fixa }
    b2_b3_fixa  { cut_b2 ; cut_b3 ; cut_fixa }
  }
  lappend log [summarise $scenario]
  close_design
}

set fh [open $out/whatif_summary.txt w]
puts $fh "scenario                 WNS        TNS             FEP       POP1000   new worst path"
foreach l $log { puts $fh $l }
close $fh
puts "PROBE_DONE $out"
```

- [ ] **Step 2: Run it, guarded by the machine check and the Vivado mutex**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
free -g; ps aux --sort=-%mem | head -15
flock -n /var/tmp/m68k-ooo-vivado.lock true && echo LOCK_FREE || echo LOCK_HELD
setsid flock /var/tmp/m68k-ooo-vivado.lock \
  env OUT=synth/probe_combined_arcs \
  vivado -mode batch -nojournal -nolog -source synth/probe_combined_arcs.tcl \
  > synth/probe_combined_arcs_runner.out 2>&1
```

Monitor with:

```
tail -f synth/probe_combined_arcs_runner.out | grep -E --line-buffered "PROBE |PROBE_CUT |PROBE_DONE|ERROR|error|Fatal|refusing"
```

- [ ] **Step 3: Verify the control before believing any delta**

Expected `baseline` row, byte-comparable with `synth/probe_itlb_hitway/whatif_summary.txt` and `synth/probe_population_after_cuts/population_after_cuts.txt`:

```
baseline                 WNS   -1.472  TNS     -17499.508  FEP    32408  POP1000   4890   DcachePlugin_logic_stS2Payload_paddr_reg[5]/C -> IssueQueuePlugin_logic_sbNzvc_busy_reg[8]/D
```

If the baseline row does not reproduce those four numbers exactly, **stop** — the probe is measuring something else and no scenario row from it may be used.

Expected `b2` row: WNS still `-1.472`, worst path moves to `…stS2Payload_paddr…-> …sbNzvc_busy…` (the tie partner). Expected `fixa` row: WNS still `-1.472`, worst path moves to `FetchAlignPlugin…stalled… -> IcachePlugin_logic_lineReg_reg[418]`. **These "no movement" rows are the expected result and confirm the tie** (GC1). The row that carries information is `b2_b3_fixa`.

- [ ] **Step 4: Commit the probe and record the table**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
git add synth/probe_combined_arcs.tcl
git commit -m "synth: probe the B2+B3+FixA fix points together, with per-cut object-count assertions"
```

Then append the six-row table verbatim to the ledger (`git add -f`, MAIN repo).

---

# Slice 1 — B3: decouple the speculative install/prefetch state from the live verdict (free)

Design spec §3 (B3) and §11 slice 1. This slice lands **unconditionally, with no elaboration flag**, because it costs zero resident-hit cycles under the standing miss/prefetch-path licence (handoff §15 step 9) and removes the largest single endpoint object in the design (`lineReg`: 512 cells, 1,024 of the worst 4,000 endpoints, 7.49 % of TNS).

## Task 4: Slice 1a — invert install-vs-demand priority so `lineReg`'s capture enable is register-driven

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala` (the `pfInstallVec`/`pfInstallAny` block near line 468, `lookupTick`'s `answerable` near line 656, and `IDLE.whenIsActive` near line 721)
- Create: `src/test/scala/m68k040/cache/IcachePrefetchInstallPrioritySpec.scala`

**Interfaces:**
- Consumes: existing `pfInstallVec`, `pfInstallAny`, `pfInstallSel`, `demandFillStart`, `pfLookupSetBusy`, `heldDemandMiss`, `lineReg`, `pfInstallIdx` (all already present in `IcachePlugin.logic`).
- Produces, for Tasks 5–7 and the oracles: `val pfInstallArm: Bool` (a `Reg`, the *only* enable for the `lineReg` capture and the `pfInstallIdx` select), `val installDeferCnt: UInt(3 bits)` (the H9 bounded-wait counter), `val installStarves: Bool`.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/cache/IcachePrefetchInstallPrioritySpec.scala`. Model the DUT construction on `IcachePrefetchSpec` (`src/test/scala/m68k040/cache/IcachePrefetchSpec.scala:33`, `val icache = new IcachePlugin`) and drive memory with `IcacheSim.attachMemoryWithWords`.

```scala
package m68k040.cache

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Slice 1a: a completed speculative install and a fresh demand miss arriving in the
  * same cycle must BOTH complete, install first, and the demand must be admitted
  * within the bounded wait (design spec hazard H9). Before slice 1a the demand won
  * and `lineReg`'s capture enable was a function of the LIVE `cmdPort.fire` verdict. */
class IcachePrefetchInstallPrioritySpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ic   = new IcachePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), new IdentityTranslationPlugin, ic)) }
  }

  test("a completed install and a same-cycle demand miss both complete, install first, within the bounded wait",
       VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)

      // Two DIFFERENT sets so the install is not simply the line the demand wants.
      val streamBase = 0x0001_0000L   // warms the 5-line prefetch window
      val demandPa   = 0x0002_0000L   // a different set, guaranteed cold
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, streamBase,
        Seq.tabulate(2048)(i => 0x4e71))
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, demandPa,
        Seq.tabulate(2048)(i => 0x4e71))

      dut.ic.logic.invalidateAll #= false
      dut.ic.logic.cmdPort.valid #= false
      dut.ic.logic.rspPort.ready #= true
      cd.waitSampling(5)

      // Phase 1: sequential demands at streamBase seed the prefetch window and let
      // speculative lines complete, so `pfInstallAny` is asserted.
      var installSeen = 0
      var maxDefer    = 0
      var demandRspPc = -1L
      val installFork = fork {
        while (true) {
          cd.waitSampling()
          if (dut.ic.logic.predIsPf.toBoolean) installSeen += 1
          val d = dut.ic.logic.installDeferCnt.toInt
          if (d > maxDefer) maxDefer = d
        }
      }
      // ... drive 6 sequential fetches at streamBase, waiting for rsp each time ...

      // Phase 2: the instant a completed install is pending, present a cold demand
      // to a DIFFERENT set and require BOTH to complete.
      // ... assert exactly one response for the demand, with pc == demandPa ...

      assert(installSeen > 0, "no speculative install ever ran - the test is vacuous")
      assert(demandRspPc == demandPa, s"demand miss lost or mis-attributed: $demandRspPc")
      assert(maxDefer <= 4,
        s"H9 bounded wait violated: demand deferred behind an install for $maxDefer cycles")
    }
  }
}
```

> The two `// ...` lines above are the only sequencing the implementer writes themselves; the exact drive loop is the one already used in `IcachePrefetchSpec` (present a `cmdPort` beat, `waitSamplingWhere(cmdPort.ready.toBoolean)`, then `waitSamplingWhere(rspPort.valid.toBoolean)`). Reuse it verbatim rather than inventing a new one.

- [ ] **Step 2: Run it to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
~/sbt/bin/sbt 'testOnly m68k040.cache.IcachePrefetchInstallPrioritySpec'
```

Expected: **FAIL to compile** with `value installDeferCnt is not a member of …` — that signal does not exist yet. That compile failure is the "red" state.

- [ ] **Step 3: Implement the priority inversion**

In `src/main/scala/m68k040/cache/IcachePlugin.scala`, immediately after the existing `pfInstallSel` definition (currently around line 470), insert:

```scala
    // ── Slice 1a (design spec section 3 boundary B3): the install decision must not
    // be a function of the LIVE demand verdict. Today `lineReg`'s 512-bit capture
    // enable and the fanout-518 `pfInstallIdx` select are reached from
    // `cmdPort.fire` through `demandFillStart`, which is what puts the whole
    // `applyNow -> ITLB -> tag -> accept -> install` chain into one cycle.
    //
    // The inversion: arm the install from a REGISTER, and make the demand-miss
    // capture yield to it instead. Nothing architectural depends on an install
    // happening in any particular cycle - a completed speculative line is
    // speculative by construction (handoff section 15 step 9's standing licence).
    //
    // H9 anti-starvation: a visible held demand miss may be deferred behind at most
    // `installDeferMax` cycles of install activity; past that, arming stops until the
    // demand is admitted. Without this a back-to-back chain of five completed
    // speculative slots could defer a demand miss for ~15 cycles.
    val installDeferMax = 4
    val installDeferCnt = Reg(UInt(3 bits)) init 0
    installDeferCnt.simPublic()
    val installStarves  = installDeferCnt >= U(installDeferMax, 3 bits)
    val pfInstallArm    = RegNext(pfInstallAny && !installStarves) init False
    pfInstallArm.simPublic()
```

Add the counter update **after** `heldDemandMiss` is defined (it is defined at line 471, so place this immediately below it):

```scala
    when(heldDemandMiss) {
      when(installDeferCnt =/= U(7, 3 bits)) { installDeferCnt := installDeferCnt + 1 }
    } otherwise {
      installDeferCnt := 0
    }
```

In `lookupTick`, change `answerable` so a demand miss yields to an armed install:

```scala
        val answerable = if (canStartFill)
          (lookupFault || isHit || (!pfInstallArm && !pfLookupSetBusy))
        else
          (lookupFault || isHit)
```

In `IDLE.whenIsActive`, replace the live gate with the registered one:

```scala
      IDLE.whenIsActive {
        lookupTick(canStartFill = true)
        // Slice 1a: `pfInstallArm` is a register, so `lineReg`'s capture enable and
        // `pfInstallIdx`'s 518-fanout select are reached from a flop. `answerable`
        // above guarantees no demand miss can be captured in the same cycle, so the
        // old `&& !demandFillStart` live veto is not merely redundant - it is gone.
        when(pfInstallArm) {
          pfInstallIdx := pfInstallSel
          lineReg := pfLineHi.readAsync(pfInstallSel) ## pfLineLo.readAsync(pfInstallSel)
          missPC        := pfPa(pfInstallSel)
          missPA        := pfPa(pfInstallSel)
          missSet       := pfSet(pfInstallSel)
          missTag       := pfTag(pfInstallSel)
          missCacheable := True
          victimWay     := pfWay(pfInstallSel)
          missPoison    := pfPoison(pfInstallSel) || anyInvalidate
          commitBeat    := U(0, 1 bits)
          goto(PF_PRED)
        }
      }
```

- [ ] **Step 4: Add the H9 bounded-wait in-RTL oracle (design spec §12.1 item 4)**

Immediately after the counter update, add:

```scala
    // Design spec section 12.1 oracle 4: a demand miss held by an armed install must
    // be admitted within the bounded wait. Simulation-only; `GenerationFlags.simulation`
    // is the house pattern for keeping an assert out of the synthesised netlist.
    GenerationFlags.simulation {
      assert(installDeferCnt <= U(installDeferMax + 1, 3 bits),
        "H9 bounded-wait violated: a demand miss was deferred behind installs for too long")
    }
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
~/sbt/bin/sbt 'testOnly m68k040.cache.IcachePrefetchInstallPrioritySpec'
```

Expected: PASS, with `installSeen > 0` (non-vacuity, GC20).

- [ ] **Step 6: Mutation proof M5 (design spec §12.2)**

Temporarily restore the old live gate — change `when(pfInstallArm)` back to `when(pfInstallAny && !demandFillStart)` and revert `answerable` — then re-run the new spec.

Expected: **FAIL**, because the demand wins and `installSeen`/the demand ordering assertion no longer holds. Record the exact failure message in the commit body, then restore the slice-1a code and confirm PASS again. A mutation that does *not* trip means the test is vacuous and must be strengthened before proceeding.

- [ ] **Step 7: Full focused gate**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
~/sbt/bin/sbt 'testOnly m68k040.cache.IcacheSpec m68k040.cache.IcachePrefetchSpec m68k040.cache.IcacheInvalidateSpec m68k040.cache.IcacheParallelViptSpec m68k040.cache.IcachePrefetchInstallPrioritySpec m68k040.frontend.FetchAlignResidentCadenceSpec'
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -10
```

Expected: all focused suites green; `test-fast` at the Task 1 baseline (149/149, 157 suites).

- [ ] **Step 8: Commit**

```bash
git add src/main/scala/m68k040/cache/IcachePlugin.scala \
        src/test/scala/m68k040/cache/IcachePrefetchInstallPrioritySpec.scala
git commit -m "icache(B3 1a): arm the speculative install from a register, not the live demand verdict

Takes lineReg's 512-bit capture enable and the fanout-518 pfInstallIdx select off
the applyNow -> ITLB -> tag -> accept cone. A demand miss now yields to an armed
install, bounded by installDeferMax=4 (hazard H9) with an in-RTL oracle and a
directed mutation-proven test."
```

---

## Task 5: Slice 1b — seed the prefetch window from a registered accepted-demand context

**Why:** design spec §11 slice 1b and hazard H10. `seedPfWindow()` is called inside `when(cmdPort.fire)` and writes `pfNextPa` / `pfDemandLine` / `pfLimitPa` / `pfSeqValid` from `lookupPaddr`, which **is** the live ITLB PPN. That puts the ITLB output on four more register cones. The window is a speculative hint with no same-cycle architectural requirement, so it may be one cycle late; the page-crossing restart rule inside `seedPfWindow` is **order**-sensitive, not timing-sensitive, and is preserved exactly by feeding it from the registered context.

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala` (`seedPfWindow` at ~line 546, its call site inside `lookupTick` at ~line 664)

**Interfaces:**
- Consumes: `pfInstallArm`, `installDeferCnt` (Task 4); existing `lookupPaddr`, `lookupFault`, `lookupCacheable`, `pfNextPa`, `pfDemandLine`, `pfLimitPa`, `pfSeqValid`, `pfWindowUpdate`.
- Produces, for Task 6 and the oracles: `val seedValidReg: Bool`, `val seedPaddrReg: UInt(32 bits)`, `val seedCacheableReg: Bool` — the registered accepted-demand context that drives the window.

- [ ] **Step 1: Add the registered accepted-demand context**

Insert immediately above `def seedPfWindow(): Unit = {` (currently ~line 546):

```scala
    // ── Slice 1b (design spec section 3 boundary B3, hazard H10): the prefetch
    // window is seeded from a REGISTERED accepted-demand context, not from the live
    // `lookupPaddr` (which is literally `xlate.rsp.ppn ## pc(11:0)`). The window is a
    // speculative hint: nothing architectural reads it in the cycle the demand is
    // accepted, so one cycle of prefetch lateness is free (handoff section 15 step 9).
    //
    // The page-crossing restart rule inside seedPfWindow is ORDER-sensitive, not
    // TIMING-sensitive: it compares this line against the previously recorded
    // `pfDemandLine`. Feeding it a one-cycle-late but correctly ORDERED stream of
    // accepted demands preserves it exactly.
    val seedValidReg     = RegInit(False)
    val seedPaddrReg     = Reg(UInt(32 bits))
    val seedCacheableReg = RegInit(False)
    seedValidReg.simPublic(); seedCacheableReg.simPublic()
```

- [ ] **Step 2: Re-express `seedPfWindow` over the registered context**

Replace the body's two live reads. The function currently opens with `val line = lookupPaddr & ~U(63, 32 bits)` and `val pageEnd = (lookupPaddr(31 downto 12) ## U(0xfff, 12 bits)).asUInt & ~U(63, 32 bits)`. Change both to `seedPaddrReg`, and remove the call from inside `when(cmdPort.fire)`:

```scala
    def seedPfWindow(): Unit = {
      val line = seedPaddrReg & ~U(63, 32 bits)
      val pageEnd = (seedPaddrReg(31 downto 12) ## U(0xfff, 12 bits)).asUInt & ~U(63, 32 bits)
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

- [ ] **Step 3: Drive the registered context from the accept, and run the window one cycle later**

Inside `lookupTick`'s `when(cmdPort.fire) { ... }`, replace the whole leading block

```scala
          when(!lookupFault && lookupCacheable) {
            val line = lookupPaddr & ~U(63, 32 bits)
            val sequential = ...
            pfWindowUpdate := ...
            seedPfWindow()
          } otherwise {
            pfWindowUpdate := True
            pfSeqValid := False
          }
```

with a pure capture:

```scala
          // Slice 1b: capture only. The window itself is evaluated one cycle later,
          // outside the accept cone, from `seedValidReg`/`seedPaddrReg`.
          seedValidReg     := True
          seedPaddrReg     := lookupPaddr
          seedCacheableReg := !lookupFault && lookupCacheable
```

and add, immediately after the FSM (next to the other hoisted datapath blocks, so it is evaluated unconditionally each cycle):

```scala
    // Slice 1b: the deferred window update. `seedValidReg` is a one-shot; the
    // decision rules below are byte-for-byte the ones that used to run inside
    // `when(cmdPort.fire)`, only their operands are registered.
    seedValidReg := False   // default: one-shot, overridden by the capture above
    when(seedValidReg) {
      when(seedCacheableReg) {
        val line = seedPaddrReg & ~U(63, 32 bits)
        val sequential = pfSeqValid &&
                         (line(31 downto 12) === pfDemandLine(31 downto 12)) &&
                         (line === (pfDemandLine + U(64, 32 bits)))
        pfWindowUpdate := !pfSeqValid || ((line =/= pfDemandLine) && !sequential)
        seedPfWindow()
      } otherwise {
        pfWindowUpdate := True
        pfSeqValid := False
      }
    }
```

> **Ordering note the implementer must honour:** SpinalHDL last-assignment-wins means the `seedValidReg := False` default must appear **before** the `when(cmdPort.fire)` capture in elaboration order, or the one-shot never fires. `IcachePlugin` already uses this exact default-then-override idiom for `dataReadEn`, `demandFillStart` and `pfWindowUpdate`; follow it.

- [ ] **Step 4: Verify the prefetch stream is unchanged, one cycle later**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
~/sbt/bin/sbt 'testOnly m68k040.cache.IcachePrefetchSpec m68k040.cache.IcacheSpec m68k040.cache.IcacheInvalidateSpec m68k040.cache.IcacheParallelViptSpec'
```

Expected: all green. `IcachePrefetchSpec` already asserts on real AR counts, IDs and addresses, so a broken window shows up as a wrong AR sequence, not as a timing wobble. If a test asserts on an exact *cycle* of a prefetch AR, relax that one assertion by exactly one cycle and say so in the commit message — do **not** relax an assertion on which line, which ID, or how many.

- [ ] **Step 5: Mutation proof — the page-crossing restart must still be order-sensitive**

Temporarily change `seedPaddrReg := lookupPaddr` to `seedPaddrReg := lookupPaddr & ~U(0xfff, 32 bits)` (destroy the intra-page offset). Re-run `IcachePrefetchSpec`.

Expected: **FAIL** on the prefetch frontier address sequence. Restore and confirm green. If it passes, the prefetch suite is not actually checking the frontier and must be strengthened before this task is accepted.

- [ ] **Step 6: Full gate and commit**

```bash
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -10
git add src/main/scala/m68k040/cache/IcachePlugin.scala
git commit -m "icache(B3 1b): seed the prefetch window from a registered accepted-demand context

Takes the live ITLB PPN off pfNextPa/pfDemandLine/pfLimitPa/pfSeqValid (hazard
H10). The order-sensitive page-crossing restart rule is preserved exactly; the
window is one cycle later, which is free for a speculative hint."
```

---

## Task 6: Slice 1c — register `heldDemandMiss` and `pfWindowUpdate` for the allocator and the AR arbiter

**Why:** design spec §11 slice 1c. `heldDemandMiss` is `lookupActive && cmdPort.valid && xlate.rsp.ready && !lookupFault && !isHit` — the live verdict — and it currently gates both the slot allocator (`when(pfWindowHasCandidate && … && !heldDemandMiss)`) and the AR arbiter (`pfChosenArSel`, `pfBlockingArAny`). Those are the last two consumers of the live verdict on the speculative side.

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala` (the allocator at ~line 840, the AR-hold arbiter at ~line 878)

**Interfaces:**
- Consumes: `pfInstallArm` (Task 4), `seedValidReg` (Task 5), existing `heldDemandMiss`, `pfWindowUpdate`, `demandFillStart`, `pfBlockingArWant`, `pfArSel`.
- Produces: `val heldDemandMissReg: Bool`, `val pfWindowUpdateReg: Bool`, `val demandFillStartReg: Bool` — registered mirrors consumed by the allocator and the AR arbiter.

- [ ] **Step 1: Add the registered mirrors**

Immediately after `heldDemandMiss`'s definition (~line 471, after the Task-4 counter block):

```scala
    // ── Slice 1c (design spec section 3 boundary B3): the speculative allocator and
    // the AR arbiter are the last two consumers of the LIVE demand verdict. Both are
    // pure speculation control - a slot allocated or an AR launched one cycle late
    // costs at most one cycle of prefetch earliness against a ~70-78 cycle line
    // service, and neither can change an architectural outcome (the demand refill's
    // own AR is launched from `refillActive && !arSent`, which is registered FSM
    // state and is NOT touched here).
    val heldDemandMissReg  = RegNext(heldDemandMiss)  init False
    val pfWindowUpdateReg  = RegNext(pfWindowUpdate)  init False
    val demandFillStartReg = RegNext(demandFillStart) init False
    heldDemandMissReg.simPublic()
```

- [ ] **Step 2: Re-point the allocator**

Replace the allocator's guard (currently at ~line 840):

```scala
    when(pfWindowHasCandidate && !anyInvalidate && !demandFillStartReg &&
         !pfWindowUpdateReg && !heldDemandMissReg) {
```

Everything inside the `when` body is unchanged.

- [ ] **Step 3: Re-point the AR arbiter**

Replace the two live uses in the AR-hold block (~lines 878–902):

```scala
    val pfBlockingArWant = Vec((0 until pfSlots).map(i =>
      pfArWant(i) && (pfSet(i) === missSet)))
```

> `lookupSet` was the live virtual set of whatever command happened to be at the boundary. The **registered** equivalent for a held architectural miss is `missSet`, which is latched at miss-capture time and is exactly the set the held demand needs unblocked. This is a strict improvement in precision as well as in timing, and it is why the AR arbiter can move to registered state without a new register.

and

```scala
    val pfChosenArSel = Mux(heldDemandMissReg, pfBlockingArSel, pfArSel)
    when(!arHoldValid) {
      when(refillActive && !arSent) {
        arHoldValid := True
        arHoldId    := U(AxiIds.I_DEMAND, AxiIds.ID_W bits)
        arHoldAddr  := missPA & ~U(63, 32 bits)
      } elsewhen(pfAnyArWant && !demandFillStartReg &&
                 (!heldDemandMissReg || pfBlockingArAny)) {
        arHoldValid := True
        arHoldId    := (pfChosenArSel.resize(AxiIds.ID_W) +
                        U(AxiIds.I_SPEC_BASE, AxiIds.ID_W bits)).resized
        arHoldAddr  := pfPa(pfChosenArSel) & ~U(63, 32 bits)
      }
    }
```

- [ ] **Step 4: Verify — the five-ID contract and the one-owner-per-set rule are the assertions that matter**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
~/sbt/bin/sbt 'testOnly m68k040.cache.IcachePrefetchSpec m68k040.cache.IcacheSpec m68k040.cache.IcacheInvalidateSpec m68k040.cache.IcacheParallelViptSpec m68k040.cache.IcachePrefetchInstallPrioritySpec m68k040.frontend.FetchAlignResidentCadenceSpec m68k040.frontend.FetchAlignRingTurnoverSpec'
```

Expected: all green. `IcachePrefetchSpec` checks that at most five AXI IDs are live, that no two live slots own the same set, and that a demand miss's AR is never starved behind speculation — those three are the invariants this task could break.

- [ ] **Step 5: Mutation proof — the arbiter's demand priority must be load-bearing**

Temporarily change `pfChosenArSel` to the unconditional `pfArSel` (delete the `Mux`). Re-run `IcachePrefetchSpec`.

Expected: **FAIL** on a demand-miss AR being deferred behind speculation. Restore and confirm green.

- [ ] **Step 6: Full gate and commit**

```bash
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -10
git add src/main/scala/m68k040/cache/IcachePlugin.scala
git commit -m "icache(B3 1c): drive the prefetch allocator and the AR arbiter from registered demand state

heldDemandMiss/pfWindowUpdate/demandFillStart get registered mirrors, and the AR
blocking-set compare moves from the live lookupSet to the latched missSet. This
is the last live-verdict consumer on the speculative side; the demand refill's
own AR launch is untouched."
```

---

## Task 7: Slice 1 gate — functional, IPC, and post-route matrix cell `M0`

**Why:** Slice 1 is free and lands regardless of what happens later (design spec §9.4, GC1). But it must be *proved* free, and it establishes the `M0` reference against which `M1`/`M2`/`M3` are read. Note GC1: whatever WNS this run reports, it is **not** a verdict.

**Files:**
- Modify: `/home/qwertyoruiop/m68k-core-040-ooo/.superpowers/sdd/progress-ipc-push-2026-08-09.md`

**Interfaces:**
- Consumes: Tasks 4–6 landed.
- Produces: `M0_WNS`, `M0_FMAX`, `M0_TNS`, `M0_FEP`, `M0_POP1000`, `M0_LUT`, `M0_FF`, `M0_IPC_IDEAL`, `M0_IPC_L2` — the matrix row later tasks compare against.

- [ ] **Step 1: Full functional gate**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -10
~/sbt/bin/sbt 'testOnly m68k040.lockstep.ExecuteLockStepSpec'
```

Expected: `test-fast` at the Task 1 baseline; `ExecuteLockStepSpec` at its own recorded baseline (re-verify on this HEAD, do not assume a count).

- [ ] **Step 2: IPC gate — three pinned seeds, BOTH memory models (GC14)**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
for S in 1 2 3; do
  for M in zero l2:5:70; do
    IPC_SEED=$S IPC_MEM=$M JAVA_OPTS=-Xmx6g \
      ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' \
      > /tmp/ipc_M0_s${S}_$(echo $M | tr ':' '_').log 2>&1
  done
done
grep -h "AGGREGATE\|aggregate" /tmp/ipc_M0_s*_*.log
```

Expected: aggregate IPC within noise of `0.6739` (ideal) and `0.5465` (`l2:5:70`). **Slice 1 costs zero resident-hit cycles by construction**, so anything worse than −0.3 % on the ideal model is a bug in Tasks 4–6, not a design cost — investigate before proceeding.

- [ ] **Step 3: Regenerate the synthesis Verilog with both flags OFF**

Slice 2 and Slice 3 are not built yet, so `M0` is simply Slice 1's RTL:

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
~/sbt/bin/sbt 'runMain m68k040.top.GenFullCoreSynthVerilog'
md5sum generated/M68kFullCoreSynth.v
```

Record the MD5 in the ledger **before** launching Vivado (GC19).

- [ ] **Step 4: Post-route run**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
free -g; ps aux --sort=-%mem | head -15
flock -n /var/tmp/m68k-ooo-vivado.lock true && echo LOCK_FREE || echo LOCK_HELD
setsid flock /var/tmp/m68k-ooo-vivado.lock \
  env FLOORPLAN_MODE=decode IMPL_STRATEGY=postrouteN POSTROUTE_ROUNDS=3 \
  vivado -mode batch -nojournal -log synth/vivado_M0_slice1.log \
    -source synth/impl_FullCore.tcl \
  > synth/M0_slice1_runner.out 2>&1
```

Monitor:

```
tail -f synth/M0_slice1_runner.out | grep -E --line-buffered "POSTROUTE_ROUND|POSTROUTE_FULLCORE_WNS_NS|POSTROUTE_FULLCORE_RESULT|NETLIST_MD5|ERROR|CRITICAL WARNING|Fatal"
```

Expect ~25–45 minutes. `REUSE_SYNTH_DCP` must **not** be set — the RTL changed, so the synthesis DCP must be rebuilt.

- [ ] **Step 5: Extract the full report set including the population (GC6)**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
grep -E "POSTROUTE_FULLCORE_WNS_NS|POSTROUTE_FULLCORE_RESULT|NETLIST_MD5" synth/M0_slice1_runner.out
head -40 synth/fullcore_route_timing.rpt
grep -E "CLB LUTs|CLB Registers|Block RAM Tile|DSPs" synth/fullcore_route_util.rpt
```

Then measure the sub-(−1.000 ns) population on the freshly routed checkpoint:

```bash
setsid flock /var/tmp/m68k-ooo-vivado.lock \
  env DCP=synth/fullcore_routed.dcp OUT=synth/probe_M0_population \
  vivado -mode batch -nojournal -nolog -source synth/probe_combined_arcs.tcl \
  > synth/probe_M0_population_runner.out 2>&1
```

The `baseline` row of that probe is `M0`'s own population.

- [ ] **Step 6: Archive the run**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
D=synth/archive/M0_slice1_b3_postrouteN3_decode
mkdir -p $D
cp generated/M68kFullCoreSynth.v synth/fullcore_routed.dcp \
   synth/fullcore_route_timing.rpt synth/fullcore_route_util.rpt \
   synth/fullcore_slack_matrix.rpt synth/fullcore_path_analysis.rpt \
   synth/vivado_M0_slice1.log $D/
```

- [ ] **Step 7: Record `M0` in the ledger and commit**

Append the `M0` row (WNS / FMax / TNS / FEP / POP1000 / LUT / FF / IPC ideal / IPC l2 / delivered) plus the top-path family. **State explicitly in the entry that per GC1 this row is not a verdict.**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add -f .superpowers/sdd/progress-ipc-push-2026-08-09.md
git commit -m "ledger: matrix cell M0 (slice 1 / B3 only) post-route + IPC"
```

---

# Slice 2 — B2: the F1/F2 split (elaboration-flag gated)

Design spec §4, §5, §8, §11 slice 2. This slice charges a real cycle of fetch latency and is therefore **flagged and reversible** (design spec §11: *"Behind an elaboration flag (`icacheVerdictStage: Boolean = true`), following the `enableFetchDirected` precedent, so the arc can be A/B'd against the D-cache/IQ arc without a revert."*). Task 2's amendments must already be committed (GC3).

## Task 8: Elaboration-flag plumbing, with a bit-exactness proof

**Why:** the flag must exist and be provably inert before any behaviour hangs off it. `enableFetchDirected` (`FetchAlignPlugin.scala:34`) is the sole precedent in `src/main/scala` and is the pattern to copy. All existing `new IcachePlugin` (no parentheses) call sites keep compiling, exactly as `new FetchAlignPlugin` does today at `FetchAlignResidentCadenceSpec.scala:51`.

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala:20`
- Modify: `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala:34-39`, `:230`, `:356`
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala:403-440`

**Interfaces:**
- Produces: `IcachePlugin(icacheVerdictStage: Boolean = false)`; `FetchAlignPlugin(enableFetchDirected: Boolean = false, ftqDepth: Int = 32, ringDepth: Int = 3)`; environment variables `ICACHE_VERDICT_STAGE` and `LS_ISSUE_READY_SKID` read by `GenFullCoreSynthVerilog` (`"1"` ⇒ true, anything else ⇒ false, default `"0"`).

- [ ] **Step 1: Add the `IcachePlugin` flag**

```scala
/** @param icacheVerdictStage when true, the demand path is the F1/F2/S1/rsp pipeline of
  *   `2026-08-10-ipc-fetchalign-icache-pipeline-design.md` section 4 (accept and translate
  *   at F1, verdict and dispatch at F2): three-cycle resident hit, II = 1 preserved.
  *   When false, the original two-cycle single-accept-cycle contract of
  *   `2026-08-10-icache-parallel-vipt-design.md` section 1 holds unchanged. */
class IcachePlugin(val icacheVerdictStage: Boolean = false) extends FiberPlugin with FetchService {
```

- [ ] **Step 2: Parameterise `FetchAlignPlugin`'s ring depth**

```scala
class FetchAlignPlugin(enableFetchDirected: Boolean = false, ftqDepth: Int = 32,
                       ringDepth: Int = 3)
    extends FiberPlugin with DecodeFeedService {

  require(ftqDepth > 0 && (ftqDepth & (ftqDepth - 1)) == 0,
    "FTQ depth must be a positive power of two")
  require(ringDepth == 3 || ringDepth == 4,
    "ringDepth is 3 for the two-cycle I-cache contract and 4 for the F1/F2 verdict stage")
```

At line 230, replace `val RING = 3` with:

```scala
    // 3 = the two-cycle resident-hit contract. 4 = the F1/F2 verdict stage's
    // three-cycle latency, which needs one more outstanding record to sustain II = 1
    // (design spec section 4.6). `ringInc` below folds to a plain +1 wrap for a
    // power-of-two RING, so RING=4 actually LOSES a comparator and a mux.
    val RING = ringDepth
```

At line 356, `resultSlotLegal` must not construct an out-of-range literal when `RING == 4` and `resultSlot` is 2 bits wide:

```scala
    // For RING=4 with a 2-bit slot index every value is legal, so this is
    // tautological and dead-code-eliminates; the assertions that consume it stay as
    // documentation. `U(4, 2 bits)` is not a legal literal, hence the branch.
    val resultSlotLegal = if (RING == (1 << resultSlot.getWidth)) True
                          else resultSlot < U(RING, resultSlot.getWidth bits)
```

The existing `require(ftqDepth >= RING + ibuf.BUF_WORDS + 1)` at line 260 needs **no edit**: it becomes 4 + 20 + 1 = 25 ≤ 32.

- [ ] **Step 3: Make both flags environment-selectable for synthesis**

In `src/main/scala/m68k040/top/FullCoreSynth.scala`, inside `GenFullCoreSynthVerilog.main`, above `val p = M68kParams()`:

```scala
    // Combined two-arc measurement matrix (implementation plan 2026-08-10): the four
    // cells M0/M1/M2/M3 must be buildable without a source edit, so both arcs' flags
    // come from the environment. Default OFF on both, so an unset environment
    // reproduces the pre-plan netlist bit-for-bit.
    val icacheVerdictStage = sys.env.getOrElse("ICACHE_VERDICT_STAGE", "0") == "1"
    val lsIssueReadySkid   = sys.env.getOrElse("LS_ISSUE_READY_SKID", "0") == "1"
    println(s"GEN ICACHE_VERDICT_STAGE=$icacheVerdictStage LS_ISSUE_READY_SKID=$lsIssueReadySkid")
```

and thread them (leave `lsIssueReadySkid` unused until Task 17 — Scala will warn, not error; suppress it by passing it to `LsEuPlugin` only once that constructor exists):

```scala
        val lsEu = new LsEuPlugin
```
```scala
          new IcachePlugin(icacheVerdictStage = icacheVerdictStage),
```
```scala
          new FetchAlignPlugin(enableFetchDirected = true,
                               ringDepth = if (icacheVerdictStage) 4 else 3),
```

- [ ] **Step 4: Prove the flag is inert — bit-exact generated Verilog**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
~/sbt/bin/sbt 'runMain m68k040.top.GenFullCoreSynthVerilog'
md5sum generated/M68kFullCoreSynth.v
```

Expected: **exactly** the MD5 recorded as `M0`'s in Task 7 step 3. Handoff §21 proved elaboration at a fixed HEAD is bit-exact, so any difference here means the flag is not inert and Step 1–3 must be corrected before proceeding. This is the whole point of the task.

Then confirm the ON build elaborates at all (it will be functionally identical for now, since nothing consumes the flag yet):

```bash
ICACHE_VERDICT_STAGE=1 ~/sbt/bin/sbt 'runMain m68k040.top.GenFullCoreSynthVerilog'
md5sum generated/M68kFullCoreSynth.v
```

Expected at this point: **also identical** (the flag only changes `ringDepth`, and `RING=4` does change the netlist — so expect a *different* MD5 here, and confirm the run completes with no elaboration error and no `require` failure). Record both MD5s.

- [ ] **Step 5: Gate and commit**

```bash
~/sbt/bin/sbt 'runMain m68k040.top.GenFullCoreSynthVerilog'   # restore the OFF netlist
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -10
git add src/main/scala/m68k040/cache/IcachePlugin.scala \
        src/main/scala/m68k040/frontend/FetchAlignPlugin.scala \
        src/main/scala/m68k040/top/FullCoreSynth.scala
git commit -m "frontend: add the icacheVerdictStage / ringDepth elaboration flags, provably inert when off

Generated Verilog is bit-identical with ICACHE_VERDICT_STAGE unset. Both arcs'
flags are environment-selectable so the four-cell measurement matrix builds
without a source edit."
```

---

## Task 9: The F1 stage — accept and translate

**Why:** design spec §4.1/§4.2. F1 is the entire new mechanism: acceptance is conditioned only on the translation *resolving*, not on the cache verdict, which is what deletes the II = 2 premise (§5).

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala` (the `xlate` block at ~lines 113–136, and the default `cmdPort.ready := False` at ~line 546)

**Interfaces:**
- Produces, for Task 10 and the oracles: an `Area` named `fp` with members `f1Valid: Bool`, `f1Pc: UInt(32 bits)`, `f1Advance: Bool`, `f1Ready: Bool`, `f2Valid: Bool`, `f2Pc: UInt(32 bits)`, `f2Ppn: UInt(20 bits)`, `f2Cmode` (same type as `xlate.rsp.cacheMode`), `f2Fault: Bool`, `f2Dispatch: Bool`, `f2Ready: Bool`. When `icacheVerdictStage` is false, `fp` is `null` and nothing may dereference it.

- [ ] **Step 1: Declare the stage**

Insert immediately after `lookupActive`'s declaration (~line 131), replacing the three `xlate.req.*` assignments:

```scala
    // ══ F1 / F2 fetch pipeline (design spec 2026-08-10 section 4) ═══════════════
    // F1 - accept + translate.  F2 - verdict + dispatch.  S1 and rsp are unchanged.
    // The advance discipline is the house pattern (DcachePlugin load S0/S1/S2,
    // LsEuPlugin P1-P4): a forward register with a valid bit, a single combinational
    // ready, producer holds and retries.
    val fp = if (icacheVerdictStage) new Area {
      val f1Valid = RegInit(False)
      val f1Pc    = Reg(UInt(32 bits))

      val f2Valid = RegInit(False)
      val f2Pc    = Reg(UInt(32 bits))
      val f2Ppn   = Reg(UInt(20 bits))
      val f2Cmode = Reg(cloneOf(xlate.rsp.cacheMode))
      val f2Fault = RegInit(False)

      // Driven by lookupTick at F2 (task 10). Declared here so the ready chain below
      // can close; `False` is the safe default for a cycle in which no FSM state
      // runs a lookup (REFILL / PREDECODE / REPLAY / FAULT).
      val f2Dispatch = Bool(); f2Dispatch := False

      val f2Ready   = !f2Valid || f2Dispatch
      // Acceptance depends on the translation RESOLVING (a hit or a fault), never on
      // the cache verdict. That is the single change that keeps II = 1 (section 5).
      val f1Advance = f1Valid && xlate.rsp.ready && f2Ready
      val f1Ready   = !f1Valid || f1Advance

      f1Valid.simPublic(); f2Valid.simPublic(); f1Advance.simPublic(); f2Dispatch.simPublic()

      when(f1Advance) {
        f2Valid := True
        f2Pc    := f1Pc
        f2Ppn   := xlate.rsp.ppn
        f2Cmode := xlate.rsp.cacheMode
        f2Fault := xlate.rsp.fault
      } elsewhen(f2Dispatch) {
        f2Valid := False
      }

      when(cmdPort.fire) {
        f1Valid := True
        f1Pc    := cmdPort.payload.pc
      } elsewhen(f1Advance) {
        f1Valid := False
      }
    } else null
```

> **Why `elsewhen` and not two independent `when`s:** on a cycle where F1 advances *and* a new command fires, F1 must end the cycle occupied by the new command — the `when(cmdPort.fire)` arm wins, which is correct because `cmdPort.ready = f1Ready` only permits a fire when F1 is empty or advancing. The same reasoning applies at F2.

- [ ] **Step 2: Re-point the translation request and the command handshake**

```scala
    // A translation is demanded only while the cache is actively looking at a real
    // offered fetch (parallel-VIPT section 4). With the verdict stage, the request
    // follows a genuinely ACCEPTED fetch, which satisfies that rule by construction
    // and is strictly stronger than the live `lookupActive && cmdPort.valid` form.
    xlate.req.valid      := (if (icacheVerdictStage) fp.f1Valid else lookupActive && cmdPort.valid)
    xlate.req.vpn        := (if (icacheVerdictStage) fp.f1Pc(31 downto 12) else cmdPort.payload.pc(31 downto 12))
    xlate.req.supervisor := privCtrl.map(_.supervisor).getOrElse(False)
    xlate.req.write      := False
```

And at the default-assignment block (~line 546), replace `cmdPort.ready := False` with:

```scala
    // With the verdict stage, acceptance is a property of F1 alone and does NOT
    // depend on the FSM state: a cold ITLB miss holds F1 with `_req` asserted and a
    // stable VPN, exactly as it holds the command today, and the existing registered
    // `missReqReg` walk trigger (ItlbPlugin.scala:150-171) is unchanged. Hazard H11.
    cmdPort.ready := (if (icacheVerdictStage) fp.f1Ready else False)
```

- [ ] **Step 3: Compile and check the pre-existing suites still pass with the flag OFF**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -10
```

Expected: baseline green. The flag defaults to false, so `fp` is `null` and every `if (icacheVerdictStage)` takes the original branch. **Do not** run an ON-flag test yet — F2 does not exist, so the ON build would accept commands and never answer them.

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/m68k040/cache/IcachePlugin.scala
git commit -m "icache(B2): add the F1 accept+translate stage behind icacheVerdictStage

Acceptance is conditioned on the translation resolving, never on the cache
verdict - the change that deletes the II=2 premise of design spec section 5.
Flag off is unchanged; flag on is not yet functional until F2 lands."
```

---

## Task 10: The F2 verdict — relocate `lookupTick` onto the registered context

**Why:** design spec §4.4 — *"`lookupTick(canStartFill)` moves wholesale from 'live command + live translation' to 'registered F2 context', with **no change to any decision rule**."* The trick that makes the diff small is that every downstream name (`lookupSet`, `lookupTag`, `hitVec`, `isHit`, `lookupPredEntry`, `lookupReadAddr`) is already expressed in terms of `lookupPc` / `lookupPaddr` / `lookupFault` / `lookupCmode`, so only those four definitions move.

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala` (the live-lookup context at ~lines 179–184, `heldDemandMiss` at ~line 471, `lookupTick` at ~lines 642–716)

**Interfaces:**
- Consumes: `fp` (Task 9); `pfInstallArm`, `seedValidReg`, `heldDemandMissReg` (Tasks 4–6).
- Produces: `val acceptFire: Bool` — the single signal that replaces `cmdPort.fire` at all six of its consumers inside `lookupTick` (design spec §2.1 rows 7b–7g). Row 7a stays on `ic.cmd.fire` in `FetchAlignPlugin` and is not touched here.

- [ ] **Step 1: Move the four context definitions onto F2**

Replace the `lookupPc` / `lookupPaddr` / `lookupFault` / `lookupCmode` block:

```scala
    // ---- live parallel-VIPT lookup context (binding amendment 2026-08-10, A1/A2) ----
    // Virtual set/beat arms the synchronous BRAM in the same cycle as the tag compare.
    // Translation only qualifies the physical tag and the small S1 control context; it
    // is deliberately NOT on the BRAM address/enable or the wide data mux.
    //
    // With the verdict stage the operands are the REGISTERED F2 context. `lookupPaddr`
    // stays a concatenation, never a register (design spec section 4.3): keeping it
    // combinational at F2 is what preserves the amendment's "PPN supplies the tag"
    // invariant without a 32-bit register, and `lookupSet = lookupPc(11 downto 6)`
    // stays purely virtual and page-invariant either way.
    val lookupPc    = if (icacheVerdictStage) fp.f2Pc else cmdPort.payload.pc
    val lookupPaddr = if (icacheVerdictStage) (fp.f2Ppn ## fp.f2Pc(11 downto 0)).asUInt
                      else (xlate.rsp.ppn ## lookupPc(11 downto 0)).asUInt
    val lookupFault = if (icacheVerdictStage) fp.f2Fault else xlate.rsp.fault
    val lookupCmode = if (icacheVerdictStage) fp.f2Cmode else xlate.rsp.cacheMode
    val lookupCacheable = lookupCmode =/= CacheMode.INHIBITED
```

Everything from `lookupSet` through `lookupPredEntry` (~lines 456–470) is **unchanged, byte for byte**.

- [ ] **Step 2: Re-express `heldDemandMiss` over F2**

```scala
    // An architectural miss can remain visibly held while a speculative owner or the
    // shared installer drains. With the verdict stage the miss is a property of the
    // F2 context, and `xlate.rsp.ready` is no longer part of it: a command only
    // reaches F2 after its translation resolved.
    val heldDemandMiss = if (icacheVerdictStage)
      (lookupActive && fp.f2Valid && !lookupFault && !isHit)
    else
      (lookupActive && cmdPort.valid && xlate.rsp.ready && !lookupFault && !isHit)
```

- [ ] **Step 3: Rewrite `lookupTick` over the registered context**

```scala
      def lookupTick(canStartFill: Boolean): Unit = {
        lookupActive := True

        // Arm the BRAM solely from virtual page-offset bits. Never gate this enable
        // with hitVec/isHit: that would put the tag comparison back on ENARDEN. With
        // the verdict stage the address is `f2Pc(11 downto 6)` - registered AND
        // virtual - so amendment A2's prohibition (the address/enable must not be a
        // function of the translation RESULT) is satisfied to the letter.
        val setBlocked = if (canStartFill) False
                         else (fillArrayWrActive && (lookupSet === missSet))
        val stageOccupied = if (icacheVerdictStage) fp.f2Valid else cmdPort.valid
        dataReadAddr := lookupReadAddr
        dataReadEn   := stageOccupied && !setBlocked

        val answerable = if (canStartFill)
          (lookupFault || isHit || (!pfInstallArm && !pfLookupSetBusy))
        else
          (lookupFault || isHit)

        // The accept event. Without the verdict stage this is the Stream fire, as
        // before. With it, `cmdPort.ready` belongs to F1 (task 9) and F2 dispatches
        // its own held context - which is exactly amendment A3: the cache may HOLD a
        // younger command, it just may not ANSWER one out of order.
        val fire = if (icacheVerdictStage) {
          fp.f2Dispatch := fp.f2Valid && !setBlocked && answerable
          fp.f2Dispatch
        } else {
          cmdPort.ready := xlate.rsp.ready && !setBlocked && answerable
          cmdPort.fire
        }

        when(fire) {
          // ... the ENTIRE existing body, unchanged, from
          //     `seedValidReg := True` (task 5's capture)
          //     through the `when(lookupFault) / elsewhen(isHit) / otherwise` arms
          //     and the miss capture that sets demandFillStart and goto(REFILL).
          // Not one decision rule changes. Only the operands are registered.
        }
      }
```

> The implementer copies the existing `when(cmdPort.fire) { … }` body verbatim into `when(fire) { … }`. Do not retype it — a transcription error inside the miss-capture arm is a silent-corruption class. Diff the two bodies before committing and confirm the only textual difference is the `when` head.

- [ ] **Step 4: Confirm the FSM permission rule (design spec §4.2)**

`f2Dispatch` is assigned only inside `lookupTick`, and `lookupTick` is called only from `IDLE.whenIsActive` and `PF_PRED.whenIsActive`. In every other state the `Bool(); f2Dispatch := False` default from Task 9 holds and F2 holds its context. Verify by inspection that no other site assigns `fp.f2Dispatch`:

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
grep -n "f2Dispatch" src/main/scala/m68k040/cache/IcachePlugin.scala
```

Expected: exactly three lines — the declaration/default, the `lookupTick` assignment, and the `f2Ready` consumer.

- [ ] **Step 5: Audit for any other consumer of the two-cycle response latency (design spec §14 item 4)**

This is a **prerequisite, not an assumption** — the design spec says so explicitly.

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
grep -rn "N+2\|two-cycle\|2 cycle\|latency 2\|RING\|ringCount" src/main/scala/m68k040/frontend/ src/main/scala/m68k040/cache/IcachePlugin.scala | grep -v "^Binary"
grep -rn "ringSlot" src/main/scala/m68k040/
```

Expected findings, matching design spec §4.6: only `RING` and the FTQ run-ahead bound depend on the latency. `token.ringSlot` is already `UInt(2 bits)` (`src/main/scala/m68k040/services/Services.scala:81`) and `Ftb.scala:57` / `Gshare.scala:112` override it with `U(0, 2 bits)` — **no token-pipeline change is required**. Record any *additional* consumer found as a blocking finding and fix it in this task.

- [ ] **Step 6: First ON-flag smoke test**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
~/sbt/bin/sbt 'testOnly m68k040.cache.IcacheElaborateSpec'
```

Then temporarily add, at the top of `IcacheSpec`'s DUT, `new IcachePlugin(icacheVerdictStage = true)` in a scratch copy and run it, to confirm the ON path answers at all. Revert the scratch change; the permanent ON-path coverage arrives in Tasks 13 and 15.

- [ ] **Step 7: Flag-off gate and commit**

```bash
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -10
git add src/main/scala/m68k040/cache/IcachePlugin.scala
git commit -m "icache(B2): relocate the lookup verdict to F2 over the registered translation context

lookupPc/lookupPaddr/lookupFault/lookupCmode become the registered F2 context;
every downstream name (lookupSet, lookupTag, hitVec, isHit, lookupPredEntry) is
unchanged. No decision rule changes - only when the verdict is computed.
Amendment A2's no-translation-on-BRAM-address prohibition is preserved: the BRAM
address is f2Pc(11 downto 6), registered and virtual."
```

---

## Task 11: `RING` 3 → 4 at every instantiation site

**Why:** design spec §4.6. Response latency after acceptance moves from 2 to 3 cycles; sustaining II = 1 needs one more outstanding record.

**Files:**
- Modify: `src/test/scala/m68k040/bench/IpcBenchSpec.scala:282`
- Modify: `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala:374`
- Modify: `src/test/scala/m68k040/fuzz/FuzzDut.scala:268`
- Modify: `src/test/scala/m68k040/frontend/FetchDirectedFtbSpec.scala:116`
- Modify: `src/test/scala/m68k040/frontend/FtqCapacitySpec.scala:125`
- (`src/main/scala/m68k040/top/FullCoreSynth.scala:425` was already done in Task 8 step 3.)

**Interfaces:**
- Consumes: `FetchAlignPlugin(ringDepth: Int)` and `IcachePlugin(icacheVerdictStage: Boolean)` from Task 8.
- Produces: every full-core DUT pairs its `IcachePlugin` flag with the matching `ringDepth`, via one shared `val` per DUT so the two can never disagree.

- [ ] **Step 1: Introduce a single shared switch per full-core DUT**

In each of the five test DUTs, add above the plugin declarations and use it for **both** plugins:

```scala
    // The I-cache verdict stage and the fetch ring depth are one decision, never two.
    // ICACHE_VERDICT_STAGE lets a bench/lockstep run be A/B'd without a source edit,
    // matching GenFullCoreSynthVerilog.
    val icacheVerdictStage = sys.env.getOrElse("ICACHE_VERDICT_STAGE", "0") == "1"
```
```scala
    val icache = new IcachePlugin(icacheVerdictStage = icacheVerdictStage)
```
```scala
    val fa     = new FetchAlignPlugin(enableFetchDirected = true,
                                      ringDepth = if (icacheVerdictStage) 4 else 3)
```

`FtqCapacitySpec:125` additionally keeps its `ftqDepth = ftqDepth` argument.

- [ ] **Step 2: Verify the pairing cannot be broken**

Add, inside `IcachePlugin.logic`, a structural cross-check that a mismatched configuration fails at elaboration rather than at simulation:

```scala
    // The FetchAlign ring must be able to hold one more outstanding record when the
    // verdict stage is on (design spec section 4.6). FetchAlignPlugin is a sibling
    // plugin, so this is checked from the frontend side; see the `require` there.
```

and, in `FetchAlignPlugin.logic` immediately after `val ic = host[FetchService]`:

```scala
    // A three-cycle cache with a depth-3 ring cannot sustain II = 1 (design spec
    // section 4.6 and mutation proof M4). Reject the mismatch at elaboration rather
    // than shipping a silent throughput regression.
    host.get[IcachePlugin].foreach { icp =>
      require(!icp.icacheVerdictStage || ringDepth == 4,
        "IcachePlugin(icacheVerdictStage = true) requires FetchAlignPlugin(ringDepth = 4)")
    }
```

> If `host.get[IcachePlugin]` is not available at that point in the Fiber build order, move the `require` into a `during build` block *after* `host[FetchService]` resolves — the constraint is that it must run at elaboration, not that it must run at a particular line.

- [ ] **Step 3: Verify both configurations elaborate**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -10
ICACHE_VERDICT_STAGE=1 ~/sbt/bin/sbt 'testOnly m68k040.frontend.FtqCapacitySpec'
```

Expected: OFF build at the Task 1 baseline; ON build of `FtqCapacitySpec` completes and reports a **re-measured** peak FTQ occupancy (hazard H12 — the bound is now `RING + BUF_WORDS` = 24 ≤ 32). Record the new peak; the old recorded peak was 17 of 32.

- [ ] **Step 4: Commit**

```bash
git add src/test/scala/m68k040/bench/IpcBenchSpec.scala \
        src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala \
        src/test/scala/m68k040/fuzz/FuzzDut.scala \
        src/test/scala/m68k040/frontend/FetchDirectedFtbSpec.scala \
        src/test/scala/m68k040/frontend/FtqCapacitySpec.scala \
        src/main/scala/m68k040/frontend/FetchAlignPlugin.scala
git commit -m "frontend: pair the I-cache verdict stage with RING=4 in every full-core DUT

One env-selectable switch per DUT drives both plugins, and an elaboration
require rejects the mismatched combination outright."
```

---

## Task 12: The three in-RTL differential oracles

**Why:** design spec §12.1. The house standard on this branch (§14's framing retime is the model) is a live differential oracle running in *every* simulation, not only in a dedicated test.

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala` (inside the `fp` Area from Task 9)

**Interfaces:**
- Consumes: `fp.*`, `xlate.rsp.*`, `lookupActive`.
- Produces: `fp.seqF1`, `fp.seqF2`, `fp.seqS1`, `fp.seqRsp` (simulation-only `UInt(8 bits)` sequence numbers) — read by Task 13's spec.

- [ ] **Step 1: Oracle 1 — the translation-capture oracle**

Append inside the `fp` Area:

```scala
      // ── Oracle 1 (design spec section 12.1): the F1->F2 boundary is a PROVEN retime
      // of the translation, not an assumed one. Whenever F2 holds a context and the
      // ITLB is quiescent for that VPN, a live re-translation must agree with what was
      // captured. Direct analogue of the section-14 framing oracle.
      GenerationFlags.simulation {
        val quiet = xlate.rsp.ready && (xlate.req.vpn === f2Pc(31 downto 12))
        when(f2Valid && quiet) {
          assert(f2Ppn   === xlate.rsp.ppn,       "F2 captured a stale PPN")
          assert(f2Cmode === xlate.rsp.cacheMode, "F2 captured a stale cache mode")
          assert(f2Fault === xlate.rsp.fault,     "F2 captured a stale fault")
        }
      }
```

- [ ] **Step 2: Oracle 2 — the ordering oracle**

```scala
      // ── Oracle 2: the untagged in-acceptance-order contract, proved STRUCTURALLY
      // for every existing test, forever. A monotonically increasing acceptance
      // sequence number rides F1 -> F2 -> S1 -> rsp; every emitted FetchRsp must carry
      // exactly the previous one plus one. This is what makes amendment A3 safe.
      val seqCounter = Reg(UInt(8 bits)) init 0
      val seqF1  = Reg(UInt(8 bits)) init 0
      val seqF2  = Reg(UInt(8 bits)) init 0
      val seqS1  = Reg(UInt(8 bits)) init 0
      val seqRsp = Reg(UInt(8 bits)) init 0
      val seqPrevRsp = Reg(UInt(8 bits)) init 0
      when(cmdPort.fire) { seqF1 := seqCounter; seqCounter := seqCounter + 1 }
      when(f1Advance)    { seqF2 := seqF1 }
      GenerationFlags.simulation {
        when(rspValidReg) {
          assert(seqRsp === (seqPrevRsp + 1),
            "FetchRsp emitted out of acceptance order")
        }
      }
```

> `seqS1` / `seqRsp` / `seqPrevRsp` are advanced at the same two sites that already advance `s1Valid` and `rspValidReg`; add `seqS1 := seqF2` beside every `s1Valid := True` armed from a dispatch, `seqS1 := seqF2` in `REPLAY` (the miss being answered is the one F2 captured), and `seqRsp := seqS1` / `seqPrevRsp := seqRsp` beside `rspValidReg`'s update. All are inside `GenerationFlags.simulation` blocks or are 8-bit registers that dead-code-eliminate in synthesis because nothing outside an assertion reads them.

- [ ] **Step 3: Oracle 3 — the II tripwire**

```scala
      // ── Oracle 3 (GC12): a live tripwire for a regression to II = 2. In a resident
      // hit stream with a command offered, no set collision, no prefetch-set busy and
      // a resolved translation, F1 MUST advance every cycle. If this ever fails, the
      // frontend has silently halved its bandwidth.
      GenerationFlags.simulation {
        val noStall = f1Valid && xlate.rsp.ready && f2Ready
        when(noStall) {
          assert(f1Advance, "F1 failed to advance with no stall source asserted (II regressed to 2)")
        }
      }
```

- [ ] **Step 4: Verify the oracles are live, not vacuous (GC20)**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
ICACHE_VERDICT_STAGE=1 ~/sbt/bin/sbt 'testOnly m68k040.cache.IcacheSpec m68k040.cache.IcacheParallelViptSpec m68k040.frontend.FetchAlignResidentCadenceSpec'
```

Expected: green, and the ordering oracle must have *fired at least once* — confirm by temporarily changing `assert(seqRsp === (seqPrevRsp + 1), …)` to `assert(seqRsp === (seqPrevRsp + 2), …)` and observing a **failure**. If it does not fail, the sequence numbers are not being advanced and the oracle is decoration. Restore.

- [ ] **Step 5: Confirm zero synthesis cost**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
~/sbt/bin/sbt 'runMain m68k040.top.GenFullCoreSynthVerilog'
grep -c "seqCounter\|seqPrevRsp" generated/M68kFullCoreSynth.v
```

Expected: `0`. If non-zero, the oracle registers are reaching synthesised logic and must be moved fully inside `GenerationFlags.simulation`.

- [ ] **Step 6: Commit**

```bash
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -10
git add src/main/scala/m68k040/cache/IcachePlugin.scala
git commit -m "icache(B2): three in-RTL differential oracles for the F1/F2 boundary

Translation-capture (the boundary is a proven retime), acceptance-order (the
untagged FetchRsp contract, proved structurally in every simulation), and an
II=1 tripwire. Simulation-only; the generated Verilog contains none of them."
```

---

## Task 13: `IcacheFetchPipelineSpec` — the nine directed cases

**Why:** design spec §12.3. Every case runs against the **real** `ItlbPlugin` + real walker + `BehavioralMemAgent`, following the `FetchAlignResidentCadenceSpec` fixture pattern (`src/test/scala/m68k040/frontend/FetchAlignResidentCadenceSpec.scala:45-57`, `:107-126`).

**Files:**
- Create: `src/test/scala/m68k040/cache/IcacheFetchPipelineSpec.scala`

**Interfaces:**
- Consumes: `fp.f1Valid`, `fp.f2Valid`, `fp.f1Advance`, `fp.f2Dispatch` (all `simPublic` from Tasks 9/12); `IcacheSim.attachMemoryWithWords`; `BehavioralMemAgent` for the walker.
- Produces: nothing consumed by later tasks; it is a gate.

- [ ] **Step 1: Write the fixture and case 1 (sustained II = 1) first, and watch it fail**

```scala
package m68k040.cache

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.ls.BehavioralMemAgent
import m68k040.mmu.{ItlbPlugin, MmuControlPlugin}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Directed proof of the F1/F2 fetch pipeline (design spec 2026-08-10 section 12.3).
  * Every case runs the REAL ITLB with a REAL three-level walk and a real refill; the
  * point is to prove II = 1 and in-order answering, not to exercise a stub. */
class IcacheFetchPipelineSpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin
    val itlb = new ItlbPlugin
    val ic   = new IcachePlugin(icacheVerdictStage = true)
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), ctrl, itlb, ic)) }
    def walkerAxi = itlb.walkerAxi
  }

  private val PhysPage = 0x0020_0000L
  private val VirtBase = 0x0080_0000L

  test("resident hit stream sustains II = 1 over 32 commands with exact PCs and one response each",
       VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val walkerMem = new BehavioralMemAgent(dut.walkerAxi, cd)
      // installMapping: copy the three-level page-table poke helper verbatim from
      // FetchAlignResidentCadenceSpec.installMapping (that file, lines 75+), mapping
      // VirtBase -> PhysPage.
      installMapping(walkerMem)
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, PhysPage,
        Seq.tabulate(1024)(i => Seq(0x4e71, 0x4e71)).flatten)

      dut.ic.logic.invalidateAll #= false
      dut.ic.logic.rspPort.ready #= true
      dut.ic.logic.cmdPort.valid #= false
      cd.waitSampling(5)

      // Warm the ITLB and the 32 lines we are about to stream, via real misses.
      for (i <- 0 until 32) driveOneAndWaitRsp(dut, cd, VirtBase + i * 8)

      // Measured arm: present 32 back-to-back commands and require an accept EVERY
      // cycle and a response EVERY cycle, with exact PCs and no duplicate or gap.
      val accepted = scala.collection.mutable.ArrayBuffer[Long]()
      val answered = scala.collection.mutable.ArrayBuffer[Long]()
      var acceptGaps = 0
      var streaming  = true
      fork {
        while (streaming) {
          cd.waitSampling()
          if (dut.ic.logic.rspPort.valid.toBoolean)
            answered += dut.ic.logic.rspPort.payload.pc.toLong
        }
      }
      dut.ic.logic.cmdPort.valid #= true
      var pc = VirtBase
      var issuedCount = 0
      while (issuedCount < 32) {
        dut.ic.logic.cmdPort.payload.pc #= pc
        cd.waitSampling()
        if (dut.ic.logic.cmdPort.ready.toBoolean) {
          accepted += pc; pc += 8; issuedCount += 1
        } else acceptGaps += 1
      }
      dut.ic.logic.cmdPort.valid #= false
      cd.waitSampling(20)
      streaming = false

      assert(acceptGaps == 0,
        s"II regressed: $acceptGaps cycles with a resident-hit command offered and ready low")
      assert(answered.size == 32, s"expected 32 responses, saw ${answered.size}")
      assert(answered == accepted.toSeq,
        s"responses out of acceptance order or mis-attributed:\n  acc=$accepted\n  rsp=$answered")
    }
  }
}
```

- [ ] **Step 2: Run it**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
~/sbt/bin/sbt 'testOnly m68k040.cache.IcacheFetchPipelineSpec'
```

Expected on a correct Task 9/10: PASS. If `acceptGaps > 0` the pipeline has regressed to II = 2 and Tasks 9/10 must be fixed before continuing — **this is the binding constraint (GC12)**.

- [ ] **Step 3: Add cases 2–9**

Each is its own `test(...)` in the same file, all `VerilatorTest`, all against the same fixture:

2. **Latency = 3.** Present exactly one resident-hit command; record the cycle of `cmdPort.fire` and the cycle of `rspPort.valid`. `assert(rspCycle - fireCycle == 3)`.
3. **Cold ITLB walk with F1 occupied.** Invalidate the ITLB, present a command, and assert: `fp.f1Valid` is high while `xlate.rsp.ready` is low; the walker AXI sees a real three-level walk; `fp.f1Advance` fires on the first cycle the translation resolves; the response PC and data are correct.
4. **Walk overlapping a demand refill** (the design spec §10.3 gain). Start a demand refill on page A, then present a command on a *different, untranslated* page B. Assert the walker's first AR is observed **before** the refill's last R beat. Record the cycle numbers in the failure message.
5. **Miss with a younger command held in F2.** Present a missing command, then a second, hitting command. Assert the `REPLAY` response for the miss is emitted **first** and the held command's response **second**, both with correct PCs and data.
6. **Fault with two younger commands in the pipe** (H3). Map one page no-access. Present the faulting PC followed by two resident PCs. Assert **exactly one** response carries `fault`, that `atc` is set, that no AXI AR is issued for it, and that the two younger responses are still delivered in order (the frontend's `faultHold` is a `FetchAlignPlugin` property and is covered separately by `FetchAlignSpec`).
7. **`invalidateAll` in each of the four pipe positions** (H4). Four sub-arms: pulse `invalidateAll` on the cycle the command is in F1; in F2; in S1; in rsp. In every arm assert **no stale line is ever delivered** — compare the returned words against the memory model's current content, not against a snapshot.
8. **Set-collision hold at F2** (H6). Force a `PF_PRED` array-write cycle for set S while a command for the same set S sits in F2. Assert `fp.f2Dispatch` is low for exactly that cycle, that F2 still holds the same PC afterwards, and that the eventual response is correct (not the half-written line).
9. **Redirect with commands in F1 and F2.** Fill both stages, then have the consumer stop accepting and mark both ring entries stale (drive this at the `FetchAlignPlugin` level in a second DUT that includes `FetchAlignPlugin(enableFetchDirected = true, ringDepth = 4)`); assert both responses are consumed and discarded via `ringStale`, `ringCount` returns to 0, and the frontend does not wedge.

Each assertion message must name the case and print the observed sequence. Vacuity guards: cases 3, 4 and 8 must each assert that the *mechanism they exercise actually occurred* (a walk happened; a refill was in flight; `fillArrayWrActive` was high), not merely that nothing broke.

- [ ] **Step 4: Run the whole spec and commit**

```bash
~/sbt/bin/sbt 'testOnly m68k040.cache.IcacheFetchPipelineSpec'
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -10
git add src/test/scala/m68k040/cache/IcacheFetchPipelineSpec.scala
git commit -m "test(icache): IcacheFetchPipelineSpec - nine directed F1/F2 cases against the real ITLB and walker"
```

---

## Task 14: Mutation proofs M1–M4

**Why:** design spec §12.2. A new mechanism needs a mutation that provably trips its oracle. A mutation that does **not** trip means the oracle is decoration and the task is not done.

**Files:**
- Temporarily modify then restore: `src/main/scala/m68k040/cache/IcachePlugin.scala`, `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala`
- Modify: `/home/qwertyoruiop/m68k-core-040-ooo/.superpowers/sdd/progress-ipc-push-2026-08-09.md`

**Interfaces:**
- Consumes: Tasks 9–13.
- Produces: a recorded mutation table (mutation → the exact assertion text that fired → the suite it fired in). No RTL survives this task.

- [ ] **Step 1: M1 — F2 consumes the live PPN**

Change `val lookupPaddr = if (icacheVerdictStage) (fp.f2Ppn ## fp.f2Pc(11 downto 0)).asUInt` to use `xlate.rsp.ppn` instead of `fp.f2Ppn`. Run:

```bash
~/sbt/bin/sbt 'testOnly m68k040.cache.IcacheFetchPipelineSpec m68k040.cache.IcacheParallelViptSpec'
```

Must trip: the **translation-capture oracle** (`"F2 captured a stale PPN"`), on a page-crossing fetch stream. If it does not, add a page-crossing arm to `IcacheFetchPipelineSpec` case 1 (stream across a 4 KiB boundary with two different physical mappings) until it does. Restore.

- [ ] **Step 2: M2 — F1 advances without a resolved translation**

Change `val f1Advance = f1Valid && xlate.rsp.ready && f2Ready` to drop `xlate.rsp.ready`. Run `IcacheSpec` (cold-walk case) and `IcacheFetchPipelineSpec` case 3.

Must trip: a fault/miss misclassification in the cold-walk case **and** the capture oracle. Restore.

- [ ] **Step 3: M3 — F2 dispatches during `REFILL`**

Add `fp.f2Dispatch := fp.f2Valid` unconditionally after the FSM (so it is no longer confined to `IDLE`/`PF_PRED`). Run `IcacheFetchPipelineSpec` case 5.

Must trip: the **ordering oracle** (`"FetchRsp emitted out of acceptance order"`). Restore.

- [ ] **Step 4: M4 — `RING` left at 3**

Change `ringDepth = if (icacheVerdictStage) 4 else 3` to a constant `3` in `IpcBenchSpec`'s DUT and in `FetchAlignRingTurnoverSpec`'s (temporarily relaxing the Task 11 `require`). Run:

```bash
ICACHE_VERDICT_STAGE=1 ~/sbt/bin/sbt 'testOnly m68k040.frontend.FetchAlignRingTurnoverSpec'
```

Must trip: a **measurable II regression** — which proves the ring change is load-bearing rather than cosmetic. If `FetchAlignRingTurnoverSpec` passes with `RING = 3`, the suite is not measuring turnover under sustained pressure and must be strengthened (add an arm that requires an accept on every cycle for ≥ 16 consecutive commands with the IBuf drained). Restore both files and the `require`.

- [ ] **Step 5: Confirm the tree is clean and record the table**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
git diff --stat
```

Expected: **empty**. Every mutation must be fully reverted.

Append the mutation table to the ledger, then:

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add -f .superpowers/sdd/progress-ipc-push-2026-08-09.md
git commit -m "ledger: mutation proofs M1-M4 for the F1/F2 fetch pipeline (all four trip)"
```

---

## Task 15: Turn the verdict stage on by default and run the full functional gate

**Why:** design spec §11 slice 2's gate list. Defaulting the flag on is what makes **every existing suite** exercise the new pipeline — by far the strongest verification available, and the reason the flag's default matters.

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala:20`
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala` (default the env var to `"1"`)
- Modify: the five test DUTs from Task 11 (default their `sys.env.getOrElse(..., "1")`)

**Interfaces:**
- Consumes: Tasks 9–14.
- Produces: `M1`'s RTL configuration. `ICACHE_VERDICT_STAGE=0` still builds the old contract, which is what makes matrix cells `M0` and `M2` reachable.

- [ ] **Step 1: Flip the defaults**

`class IcachePlugin(val icacheVerdictStage: Boolean = true)`, and in all six env reads change `getOrElse("ICACHE_VERDICT_STAGE", "0")` to `getOrElse("ICACHE_VERDICT_STAGE", "1")`.

- [ ] **Step 2: The full gate (design spec §12.4)**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -10
~/sbt/bin/sbt 'testOnly m68k040.cache.IcacheSpec m68k040.cache.IcacheParallelViptSpec m68k040.cache.IcachePrefetchSpec m68k040.cache.IcacheInvalidateSpec m68k040.cache.IcacheFetchPipelineSpec m68k040.cache.IcachePrefetchInstallPrioritySpec'
~/sbt/bin/sbt 'testOnly m68k040.frontend.FetchAlignSpec m68k040.frontend.FetchAlignResidentCadenceSpec m68k040.frontend.FetchAlignRingTurnoverSpec m68k040.frontend.FetchDirectedFtbSpec m68k040.frontend.FtqCapacitySpec m68k040.frontend.FtbSpec m68k040.frontend.GshareSpec m68k040.frontend.RasPluginSpec'
~/sbt/bin/sbt 'testOnly m68k040.decode.PerBeatPredecodeEquivSpec m68k040.mmu.IdentityTranslationSpec'
~/sbt/bin/sbt 'testOnly m68k040.lockstep.ExecuteLockStepSpec'
```

Expected: `test-fast` at the Task 1 baseline count. `FtqCapacitySpec` reports a **re-measured** peak (hazard H12) — record it. Lock-step must be run at **two seeds** (design spec §11 slice 2); use the suite's own seed mechanism and record both.

- [ ] **Step 3: Confirm the OFF build still works (this is what `M0`/`M2` depend on)**

```bash
ICACHE_VERDICT_STAGE=0 make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -10
```

Expected: identical pass count. If the OFF path has rotted, the matrix cannot be built and this must be fixed here.

- [ ] **Step 4: Commit**

```bash
git add -A src/
git commit -m "icache(B2): enable the F1/F2 verdict stage by default

Every existing suite now exercises the three-cycle II=1 pipeline. ICACHE_VERDICT_STAGE=0
still builds the two-cycle contract, which the measurement matrix depends on.
FtqCapacitySpec peak re-measured for RING=4 (hazard H12)."
```

---

## Task 16: Slice 2 gate — IPC and post-route matrix cell `M1`

**Why:** design spec §11 slice 2's measurement obligation. Per GC1 this is **not** a verdict — `M1` is expected to sit at or near `M0`, because the D-cache/IQ arc still stands.

**Files:**
- Modify: `/home/qwertyoruiop/m68k-core-040-ooo/.superpowers/sdd/progress-ipc-push-2026-08-09.md`

**Interfaces:**
- Produces: `M1_WNS`, `M1_FMAX`, `M1_TNS`, `M1_FEP`, `M1_POP1000`, `M1_LUT`, `M1_FF`, `M1_IPC_IDEAL`, `M1_IPC_L2`.

- [ ] **Step 1: IPC — three pinned seeds, both models, with `ICACHE_VERDICT_STAGE=1`**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
for S in 1 2 3; do
  for M in zero l2:5:70; do
    ICACHE_VERDICT_STAGE=1 IPC_SEED=$S IPC_MEM=$M JAVA_OPTS=-Xmx6g \
      ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' \
      > /tmp/ipc_M1_s${S}_$(echo $M | tr ':' '_').log 2>&1
  done
done
```

Record the 11-kernel aggregate for each seed and model, plus the **per-kernel** rows for `branchy`, `hot-loop` and `call-return` (design spec §10.2 predicts these carry the cost) and for `independent-ALU`, `shift-stream`, `store-stream` (predicted ~zero cost). A result where the redirect-heavy kernels lose and the straight-line kernels do not is *confirmation of the model*; a uniform loss means something other than fetch latency changed and must be explained.

**GC14: the ideal-memory model is the pessimistic bound and is the one that gates.**

- [ ] **Step 2: Generate `M1`'s netlist**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
ICACHE_VERDICT_STAGE=1 LS_ISSUE_READY_SKID=0 ~/sbt/bin/sbt 'runMain m68k040.top.GenFullCoreSynthVerilog'
md5sum generated/M68kFullCoreSynth.v
```

Record in the ledger before launching Vivado (GC19).

- [ ] **Step 3: Post-route run (GC5, GC7)**

```bash
free -g; ps aux --sort=-%mem | head -15
flock -n /var/tmp/m68k-ooo-vivado.lock true && echo LOCK_FREE || echo LOCK_HELD
setsid flock /var/tmp/m68k-ooo-vivado.lock \
  env FLOORPLAN_MODE=decode IMPL_STRATEGY=postrouteN POSTROUTE_ROUNDS=3 \
  vivado -mode batch -nojournal -log synth/vivado_M1_verdictstage.log \
    -source synth/impl_FullCore.tcl \
  > synth/M1_verdictstage_runner.out 2>&1
```

Monitor for `POSTROUTE_FULLCORE_WNS_NS`, `POSTROUTE_ROUND`, and any `ERROR`/`Fatal`.

- [ ] **Step 4: Population + area + top-path family (GC6, GC15)**

```bash
grep -E "POSTROUTE_FULLCORE_WNS_NS|POSTROUTE_FULLCORE_RESULT|NETLIST_MD5" synth/M1_verdictstage_runner.out
grep -E "CLB LUTs|CLB Registers|Block RAM Tile|DSPs" synth/fullcore_route_util.rpt
head -30 synth/fullcore_slack_matrix.rpt
setsid flock /var/tmp/m68k-ooo-vivado.lock \
  env DCP=synth/fullcore_routed.dcp OUT=synth/probe_M1_population \
  vivado -mode batch -nojournal -nolog -source synth/probe_combined_arcs.tcl \
  > synth/probe_M1_population_runner.out 2>&1
```

Expected area (design spec §13): ≈ +89 flops, net-negative LUTs. A LUT *increase* is a review point, not a rejection (GC15), but must be explained.

Expected top path: the D-cache/IQ arc (`stS2Payload_paddr → sbNzvc_busy`), because that arc is untouched and was tied. **Seeing it there is confirmation of the tie, not a failure.**

- [ ] **Step 5: Archive, record, commit**

```bash
D=synth/archive/M1_verdictstage_postrouteN3_decode
mkdir -p $D && cp generated/M68kFullCoreSynth.v synth/fullcore_routed.dcp \
  synth/fullcore_route_timing.rpt synth/fullcore_route_util.rpt \
  synth/fullcore_slack_matrix.rpt synth/fullcore_path_analysis.rpt \
  synth/vivado_M1_verdictstage.log $D/
cd /home/qwertyoruiop/m68k-core-040-ooo
git add -f .superpowers/sdd/progress-ipc-push-2026-08-09.md
git commit -m "ledger: matrix cell M1 (slices 1+2, icacheVerdictStage on) post-route + IPC"
```

Also validate, in the ledger entry, the design spec's own §9.3 prediction band (−1.472 to −1.350 ns, 182.7–186.9 MHz) and its §9.2 population prediction (~2,150 endpoints below −1.000 ns). **State whether the population metric predicted the routed outcome** — design spec §14 item 2 says that is a result worth having regardless of the FMax number, because it either validates or retires the metric the whole three-arc program is prioritised by.

---

# Slice 3 — Fix A: the IQ→LS-EU registered-ready skid (the second arc)

Handoff §16 step 5 fix 1, §19 step 2. See "Which D-cache/IQ fix this plan builds, and why" above for the grounded choice of Fix A over Fix B. Per GC11 this is implemented **entirely inside `LsEuPlugin`** — `IssueQueuePlugin.scala` and `DcachePlugin.scala` are read-only contracts.

## Task 17: `lsIssueReadySkid` flag plumbing, with a bit-exactness proof

**Files:**
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala:77`
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala` (`GenFullCoreSynthVerilog`)
- Modify: `src/test/scala/m68k040/bench/IpcBenchSpec.scala:291`, `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala`, `src/test/scala/m68k040/fuzz/FuzzDut.scala:277`

**Interfaces:**
- Produces: `LsEuPlugin(lsIssueReadySkid: Boolean = false)`; the `LS_ISSUE_READY_SKID` environment variable already read by `GenFullCoreSynthVerilog` (Task 8 step 3) becomes live.

- [ ] **Step 1: Add the flag**

```scala
/** @param lsIssueReadySkid when true, the IQ -> LS-EU issue port is consumed through a
  *   two-deep registered-ready skid, so `issuePort.ready` is a register instead of the
  *   head of the 22-level combinational accept-last chain that reaches
  *   `IssueQueuePlugin`'s decoded scoreboard clear. Sustained throughput is unchanged
  *   at one uop per cycle; the IQ frees its slot one cycle earlier, which is the
  *   semantic the re-proof in `LsIssueReadySkidSpec` covers. */
class LsEuPlugin(val lsIssueReadySkid: Boolean = false) extends FiberPlugin with LsEuService {
```

- [ ] **Step 2: Thread it at the four instantiation sites that build a full core**

`FullCoreSynth.scala`: `val lsEu = new LsEuPlugin(lsIssueReadySkid = lsIssueReadySkid)`.
`IpcBenchSpec.scala:291`, `ExecuteLockStepSpec.scala`, `FuzzDut.scala:277`: add, next to each DUT's existing `icacheVerdictStage` switch from Task 11,

```scala
    val lsIssueReadySkid = sys.env.getOrElse("LS_ISSUE_READY_SKID", "0") == "1"
```
```scala
    val lsEu   = new LsEuPlugin(lsIssueReadySkid = lsIssueReadySkid)
```

The many standalone LS DUTs (`LsEuSpec`, `StackOpSpec`, `LsBackendInjectSpec`, `DtlbCrossPageSplitSpec`, `AccessFaultCaptureSpec`) keep `new LsEuPlugin` and therefore keep the default. Do not edit them.

- [ ] **Step 3: Prove the flag is inert**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
ICACHE_VERDICT_STAGE=1 LS_ISSUE_READY_SKID=0 ~/sbt/bin/sbt 'runMain m68k040.top.GenFullCoreSynthVerilog'
md5sum generated/M68kFullCoreSynth.v
```

Expected: **exactly** the MD5 recorded for `M1` in Task 16 step 2. Anything else means the flag is not inert.

- [ ] **Step 4: Gate and commit**

```bash
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -10
git add src/main/scala/m68k040/execute/LsEuPlugin.scala \
        src/main/scala/m68k040/top/FullCoreSynth.scala \
        src/test/scala/m68k040/bench/IpcBenchSpec.scala \
        src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala \
        src/test/scala/m68k040/fuzz/FuzzDut.scala
git commit -m "ls: add the lsIssueReadySkid elaboration flag, provably inert when off"
```

---

## Task 18: The two-deep registered-ready skid

**Why:** handoff §16 step 3 traced the arc end to end; §19 step 5 confirmed the mechanism on the routed netlist. The skid severs it at its widest point — `IssueQueuePlugin.scala:439` (`piped.ready := issuePorts(k).ready`) is what makes `selPorts(3).ready` combinationally the EU's ready, and one `fire` bit fans into a 50-bit decoded scoreboard clear.

**Files:**
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala` (insert the skid above line 282; re-point lines 282, 1780, 1816–1818)

**Interfaces:**
- Consumes: existing `issuePort: Stream[IqContext]`, `sqFlushSig`, `excActive`, `s1Ready`, `s1Valid`, `s1Ctx`.
- Produces: `val issueStage: Stream[IqContext]` — the stream the LS EU actually consumes. Every existing consumer of `issuePort` inside `logic` moves to it. `LsEuService.issue` keeps returning `issuePort` (the external port), so `BackendWiringPlugin`'s `lsEu.issue << iq.issue(3)` (`FullCoreSynth.scala:202`) is **unchanged**.

- [ ] **Step 1: Insert the skid immediately above the `// ---- S0: read operands ----` comment (~line 281)**

```scala
    // ══ D-cache/IQ arc, Fix A: a two-deep registered-ready skid ═════════════════
    // Today `issuePort.ready := s1Ready && !sqFlushSig && !excActive` (below), and
    // s1Ready -> tReady -> tCanLeave -> normalReqArm -> dcache.loadProbe.ready ->
    // earlyProbeSetWriteVec -> the D-cache write-port set compares -> stS2Payload_paddr
    // is one unbroken combinational chain, 22 levels at 70.5% route.  IssueQueuePlugin
    // drives selPorts(3).ready straight from this port (IssueQueuePlugin.scala:439), and
    // `when(selPorts(k).fire)` clears a 50-bit decoded scoreboard, so the chain ends in
    // a very wide cone.  That is routed path #1 at -1.472 ns (handoff section 19 step 2).
    //
    // Two buffer slots, not one: with a single slot the upstream ready would have to be
    // `!full || downstream.ready` to sustain one uop per cycle, which puts the
    // combinational arc straight back.  With two, `issuePort.ready := !d1Valid` is a
    // pure register and throughput is still one per cycle (steady state: d0 occupied,
    // d1 free, accept one and emit one every cycle).
    //
    // SEMANTIC CHANGE, deliberately narrow and re-proved in LsIssueReadySkidSpec: the
    // IQ frees its slot and runs its scoreboard clear when the uop enters the SKID,
    // one cycle before the EU consumes it.  IssueQueuePlugin.scala:900-905 records that
    // LS int/NZVC producers never touch sbInt/sbNzvc at all (they use the dynamic
    // lsBusy/lsNzvcBusy bitmaps, cleared by a real lsWakeup completion), so for those
    // the earlier clear is a confirmed no-op.  Ordering is preserved by construction
    // (a two-entry FIFO), and a flush poisons both slots below.
    val issueStage = Stream(IqContext())
    val lsSkid = if (lsIssueReadySkid) new Area {
      val d0Valid = RegInit(False); val d0Ctx = Reg(IqContext())
      val d1Valid = RegInit(False); val d1Ctx = Reg(IqContext())
      d0Valid.simPublic(); d1Valid.simPublic()

      issuePort.ready    := !d1Valid && !sqFlushSig && !excActive
      issueStage.valid   := d0Valid && !sqFlushSig && !excActive
      issueStage.payload := d0Ctx

      val outFire = issueStage.fire
      val inFire  = issuePort.fire

      when(outFire) {
        d0Valid := d1Valid
        d0Ctx   := d1Ctx
        d1Valid := False
      }
      when(inFire) {
        when(!d0Valid || (outFire && !d1Valid)) {
          d0Valid := True; d0Ctx := issuePort.payload
        } otherwise {
          d1Valid := True; d1Ctx := issuePort.payload
        }
      }
      // A flush poisons every buffered uop.  IssueQueuePlugin flushes its own m2sPipe
      // (IssueQueuePlugin.scala:436) and gates its output with !flushSignal (:437); the
      // skid sits on the far side of that boundary and is this plugin's responsibility.
      // Assigned LAST so it wins over both blocks above (SpinalHDL last-assignment-wins).
      when(sqFlushSig || excActive) { d0Valid := False; d1Valid := False }
    } else new Area {
      issuePort.ready    := issueStage.ready
      issueStage.valid   := issuePort.valid
      issueStage.payload := issuePort.payload
    }
```

- [ ] **Step 2: Re-point the three consumers**

Line 282: `val u0 = issueStage.payload.uop`
(this also takes the PRF read address off the IQ's output mux and onto a local register — a bonus on the S0 arc.)

Line 1780: replace

```scala
    issuePort.ready := s1Ready && !sqFlushSig && !excActive
```

with

```scala
    // The skid (or the pass-through, with the flag off) owns `issuePort.ready`.
    issueStage.ready := s1Ready && !sqFlushSig && !excActive
```

Lines 1816–1818:

```scala
    when(issueStage.fire) {
      s1Valid := True
      s1Ctx   := issueStage.payload
```

- [ ] **Step 3: Confirm there is no remaining consumer of `issuePort` inside `logic`**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
grep -n "issuePort" src/main/scala/m68k040/execute/LsEuPlugin.scala
```

Expected: exactly the declaration (`:85`), the service override (`:103`), the port construction and `simPublic` taps (`:147-148`), and the four lines inside `lsSkid`. **No other line.** Any other hit is a consumer that would silently bypass the skid.

- [ ] **Step 4: Flag-off regression**

```bash
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -10
~/sbt/bin/sbt 'testOnly m68k040.ls.LsEuSpec m68k040.ls.LsBackendInjectSpec m68k040.ls.StackOpSpec m68k040.ls.DtlbCrossPageSplitSpec m68k040.cache.DcacheSpec'
```

Expected: baseline green. Note GC5 from the LS EU late-split plan: LS unit specs have a documented flaky member — **compare fail-*name-lists*, never counts.**

- [ ] **Step 5: Commit (the flag is still off by default)**

```bash
git add src/main/scala/m68k040/execute/LsEuPlugin.scala
git commit -m "ls(FixA): two-deep registered-ready skid on the IQ issue port, behind lsIssueReadySkid

Severs the 22-level stS2Payload_paddr -> ... -> sbNzvc_busy accept-last chain at
its widest point. issuePort.ready becomes a register; throughput is unchanged at
one uop per cycle; both skid slots are poisoned on flush."
```

---

## Task 19: `LsIssueReadySkidSpec` — the four re-proof obligations

**Why:** handoff §16 step 5 priced Fix A as *"a real `IqContext`-width buffer plus a re-proof of the LS ordering/flush contract"*. This task **is** that re-proof, and it is the reason Fix A is safe to build where Fix B is not.

**Files:**
- Create: `src/test/scala/m68k040/ls/LsIssueReadySkidSpec.scala`

**Interfaces:**
- Consumes: `lsSkid.d0Valid`, `lsSkid.d1Valid` (simPublic, Task 18); the existing `LsBackendInjectSpec` DUT pattern (`src/test/scala/m68k040/ls/LsBackendInjectSpec.scala:91-97`), which already wires a real `IssueQueuePlugin` + `LsEuPlugin` + `DcachePlugin`.
- Produces: the recorded answer to obligation **R3** (the `sbX` question), which is a *finding*, not a code change, unless it comes back positive.

- [ ] **Step 1: R1 — ordering. Write the test and run it.**

Build the DUT as `LsBackendInjectSpec` does, but with `new LsEuPlugin(lsIssueReadySkid = true)`. Push 16 LS µops with distinct `robId`s through the IQ under a downstream that stalls pseudo-randomly (drive `dcache.loadProbe.ready` low for random 1–4 cycle bursts via the existing memory-model backpressure knob). Record `s1Ctx.robId` at every `issueStage.fire`.

```scala
      assert(observedRobIds == expectedRobIds,
        s"skid reordered LS uops:\n  expected=$expectedRobIds\n  observed=$observedRobIds")
      assert(observedRobIds.size == 16, s"lost or duplicated a uop: ${observedRobIds.size}")
      assert(maxSkidOccupancy == 2, s"skid never filled - test is vacuous (max=$maxSkidOccupancy)")
```

The third assertion is the non-vacuity guard (GC20): if the skid never holds two entries, the test never exercised the mechanism.

- [ ] **Step 2: R2 — flush. Write the test and run it.**

Fill both skid slots, then pulse `sqFlushSig` (and, in a second arm, `excActive`). Assert:

```scala
      assert(!dut.lsEu.logic.lsSkid.d0Valid.toBoolean && !dut.lsEu.logic.lsSkid.d1Valid.toBoolean,
        "flush did not poison both skid slots")
      assert(postFlushIssueFires == 0,
        s"$postFlushIssueFires wrong-path LS uops executed after the flush")
      assert(preFlushSkidOccupancy == 2, "flush arm is vacuous - the skid was not full")
```

- [ ] **Step 3: R3 — the early scoreboard clear. Answer the `sbX` question in writing.**

`IssueQueuePlugin.scala:900-905` records that LS int and NZVC producers never enter `sbInt`/`sbNzvc` (they use `lsBusy`/`lsNzvcBusy`, cleared by a real completion), which makes the earlier clear a confirmed no-op for those. The residual is `sbX`: line 953 clears `sbX.busy(pXDst)` on **any** port fire, and the push-time block at `:869-871` puts an LS µop that `writesX` into the *static* `sbX`.

Determine, and record in the commit message, whether any µop routed to IQ port 3 can have `writesX` set:

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
grep -rn "writesX" src/main/scala/m68k040/decode/ src/main/scala/m68k040/rename/ | head -40
grep -rn "def isLs\b" src/main/scala/m68k040/execute/iq/IssueQueuePlugin.scala
```

- **If the answer is "no LS µop sets `writesX`":** record it as a grounded finding with the file/line evidence, and add a permanent elaboration guard in `LsEuPlugin` so a future change cannot silently reintroduce the case:

```scala
      GenerationFlags.simulation {
        when(issuePort.fire) {
          assert(!issuePort.payload.uop.writesX,
            "an LS uop writes X via the static sbX scoreboard; the skid's early clear must be re-proved")
        }
      }
```

- **If the answer is "yes":** write a directed arm — an LS µop that writes X followed immediately by a consumer of that X physreg — and assert the consumer cannot issue before the LS µop's X result is architecturally available. If it can, **the skid must additionally hold `sbX`'s clear back**, which requires a change inside `IssueQueuePlugin` and therefore a GC11 scope amendment and a fresh review before proceeding.

- [ ] **Step 4: R4 — capacity and liveness under sustained backpressure**

Run 256 LS µops through with `dcache.loadProbe.ready` held low for long bursts. Assert: the IQ never overflows (its own `count` never exceeds `slotCount`), no µop is lost, the pipeline drains completely when backpressure lifts, and the run terminates (a wedge shows up as a simulation timeout — set an explicit cycle budget and fail with a clear message rather than hanging).

```scala
      assert(cycles < budget, s"LS pipeline wedged under sustained backpressure at cycle $cycles")
      assert(completed == 256, s"lost uops under backpressure: $completed of 256")
```

- [ ] **Step 5: Mutation proof — the flush clear must be load-bearing**

Temporarily delete `when(sqFlushSig || excActive) { d0Valid := False; d1Valid := False }` from Task 18's skid. Re-run `LsIssueReadySkidSpec`.

Expected: **R2 FAILS** with `"flush did not poison both skid slots"` or a non-zero `postFlushIssueFires`. Restore and confirm green. If R2 passes without the flush clear, it is vacuous and must be strengthened before this task is accepted.

- [ ] **Step 6: Full functional gate with the skid ON**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
LS_ISSUE_READY_SKID=1 make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -10
LS_ISSUE_READY_SKID=1 ~/sbt/bin/sbt 'testOnly m68k040.lockstep.ExecuteLockStepSpec'
LS_ISSUE_READY_SKID=1 ~/sbt/bin/sbt 'testOnly m68k040.ls.* m68k040.cache.DcacheSpec'
```

Expected: the same pass set as the flag-off run, compared by **fail-name-list**, not by count.

- [ ] **Step 7: Commit**

```bash
git add src/test/scala/m68k040/ls/LsIssueReadySkidSpec.scala \
        src/main/scala/m68k040/execute/LsEuPlugin.scala
git commit -m "test(ls): re-prove the LS ordering/flush contract under the issue-ready skid

R1 ordering under random backpressure, R2 flush poisoning both slots
(mutation-proven), R3 the sbX early-clear question answered with an elaboration
guard, R4 liveness and capacity over 256 uops under sustained backpressure."
```

---

## Task 20: Fix A gate — IPC and post-route matrix cell `M2`

**Why:** GC1 requires Fix A be measured **independently** as well as in combination. Handoff §19 step 2 measured its over-cut upper bound at +0.000 ns with the FetchAlign arc standing; `M2` is the first *real* (rather than modelled) test of that, and it is expected to reconfirm ~0.000 ns. **That reconfirmation is the point** — it is what makes the `M3` result attributable to the combination rather than to Fix A alone.

**Files:**
- Modify: `/home/qwertyoruiop/m68k-core-040-ooo/.superpowers/sdd/progress-ipc-push-2026-08-09.md`

**Interfaces:**
- Produces: `M2_WNS`, `M2_FMAX`, `M2_TNS`, `M2_FEP`, `M2_POP1000`, `M2_LUT`, `M2_FF`, `M2_IPC_IDEAL`, `M2_IPC_L2`.

- [ ] **Step 1: IPC with the skid ON and the verdict stage OFF**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
for S in 1 2 3; do
  for M in zero l2:5:70; do
    ICACHE_VERDICT_STAGE=0 LS_ISSUE_READY_SKID=1 IPC_SEED=$S IPC_MEM=$M JAVA_OPTS=-Xmx6g \
      ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' \
      > /tmp/ipc_M2_s${S}_$(echo $M | tr ':' '_').log 2>&1
  done
done
```

Record the aggregate and the `load-stream` / `load/store` / `mixed` per-kernel rows. Fix A adds no latency to a µop that is not back-pressured, so the expectation is **IPC-neutral**; a measurable gain would mean `loadProbe.ready` back-pressure was costing IPC (which would, per handoff §16 step 5, also revive Fix B's throughput justification — record it if seen).

- [ ] **Step 2: Generate `M2`'s netlist and run post-route**

```bash
ICACHE_VERDICT_STAGE=0 LS_ISSUE_READY_SKID=1 ~/sbt/bin/sbt 'runMain m68k040.top.GenFullCoreSynthVerilog'
md5sum generated/M68kFullCoreSynth.v
free -g; ps aux --sort=-%mem | head -15
flock -n /var/tmp/m68k-ooo-vivado.lock true && echo LOCK_FREE || echo LOCK_HELD
setsid flock /var/tmp/m68k-ooo-vivado.lock \
  env FLOORPLAN_MODE=decode IMPL_STRATEGY=postrouteN POSTROUTE_ROUNDS=3 \
  vivado -mode batch -nojournal -log synth/vivado_M2_lsskid.log \
    -source synth/impl_FullCore.tcl \
  > synth/M2_lsskid_runner.out 2>&1
```

- [ ] **Step 3: Full report set, population, archive**

Same extraction as Task 16 step 4, with `OUT=synth/probe_M2_population` and archive directory `synth/archive/M2_lsskid_postrouteN3_decode`.

Expected top path after Fix A: `FetchAlignPlugin stalled → IcachePlugin lineReg[418]` — the tie partner, exactly as handoff §19 step 2's what-if predicted. Note, however, that `lineReg`'s cone was already restructured by Slice 1 (which is in **all** four cells), so the family name may differ; record whatever it is, because it is the input to Task 24's recensus.

- [ ] **Step 4: Record `M2` and commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add -f .superpowers/sdd/progress-ipc-push-2026-08-09.md
git commit -m "ledger: matrix cell M2 (slice 1 + Fix A, verdict stage off) post-route + IPC"
```

State in the entry whether `M2 - M0` reconfirms the modelled +0.000 ns, and note per GC1 that this is **not** a verdict on Fix A.

---

# Slice 4 — Combine, measure, decide

## Task 21: Matrix cell `M3` and the assembled four-cell table

**Why:** this is **the** task. GC1: no slice is judged solo, and the design spec §9.4's strategic point is that the program only pays if at least two of the three arcs land. `M3` is the first measurement in this campaign in which two of the three tied arcs are *both* gone.

**Files:**
- Modify: `/home/qwertyoruiop/m68k-core-040-ooo/.superpowers/sdd/progress-ipc-push-2026-08-09.md`

**Interfaces:**
- Consumes: `M0` (Task 7), `M1` (Task 16), `M2` (Task 20).
- Produces: `M3_*`, and the assembled matrix that Tasks 22–24 read.

- [ ] **Step 1: Full functional gate with BOTH flags on**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
ICACHE_VERDICT_STAGE=1 LS_ISSUE_READY_SKID=1 make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -10
ICACHE_VERDICT_STAGE=1 LS_ISSUE_READY_SKID=1 ~/sbt/bin/sbt 'testOnly m68k040.lockstep.ExecuteLockStepSpec'
ICACHE_VERDICT_STAGE=1 LS_ISSUE_READY_SKID=1 ~/sbt/bin/sbt 'testOnly m68k040.cache.IcacheFetchPipelineSpec m68k040.ls.LsIssueReadySkidSpec m68k040.frontend.FetchAlignResidentCadenceSpec m68k040.frontend.FetchAlignRingTurnoverSpec m68k040.frontend.FtqCapacitySpec'
```

Expected: the Task 1 baseline pass count, lock-step at its baseline (two seeds), and **no interaction failure** between the two arcs. They touch disjoint subsystems, so an interaction failure here would be a genuine surprise and must be root-caused, not worked around.

- [ ] **Step 2: IPC with both flags on**

```bash
for S in 1 2 3; do
  for M in zero l2:5:70; do
    ICACHE_VERDICT_STAGE=1 LS_ISSUE_READY_SKID=1 IPC_SEED=$S IPC_MEM=$M JAVA_OPTS=-Xmx6g \
      ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' \
      > /tmp/ipc_M3_s${S}_$(echo $M | tr ':' '_').log 2>&1
  done
done
```

- [ ] **Step 3: Generate `M3`'s netlist and run post-route**

```bash
ICACHE_VERDICT_STAGE=1 LS_ISSUE_READY_SKID=1 ~/sbt/bin/sbt 'runMain m68k040.top.GenFullCoreSynthVerilog'
md5sum generated/M68kFullCoreSynth.v
free -g; ps aux --sort=-%mem | head -15
flock -n /var/tmp/m68k-ooo-vivado.lock true && echo LOCK_FREE || echo LOCK_HELD
setsid flock /var/tmp/m68k-ooo-vivado.lock \
  env FLOORPLAN_MODE=decode IMPL_STRATEGY=postrouteN POSTROUTE_ROUNDS=3 \
  vivado -mode batch -nojournal -log synth/vivado_M3_combined.log \
    -source synth/impl_FullCore.tcl \
  > synth/M3_combined_runner.out 2>&1
```

Extract the full report set, the sub-(−1.000 ns) population (`OUT=synth/probe_M3_population`), and archive to `synth/archive/M3_combined_postrouteN3_decode`.

- [ ] **Step 4: Assemble the matrix**

Write this table into the ledger, filled from the four recorded cells. Every cell must come from a run that satisfied GC7 (uncontended, mutex-held); a contended number is not a measurement and the cell must be re-run.

```markdown
| cell | icacheVerdictStage | lsIssueReadySkid | WNS (ns) | FMax (MHz) | TNS | failing EP | sub(-1.000) EP | LUT | FF | IPC ideal | IPC l2:5:70 | delivered ideal | delivered l2 | top-path family |
|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| **B** (pinned, `6b246de`) | n/a | n/a | -1.472 | 182.749 | -17499.508 | 32408 | 4890 | 110662 | 50389 | 0.6739 | 0.5465 | 123.15 | 99.87 | `stS2Payload_paddr -> sbNzvc_busy` |
| **M0** (slice 1 only) | off | off | | | | | | | | | | | | |
| **M1** (+ B2) | **on** | off | | | | | | | | | | | | |
| **M2** (+ Fix A) | off | **on** | | | | | | | | | | | | |
| **M3** (**both**) | **on** | **on** | | | | | | | | | | | | |
```

- [ ] **Step 5: Compute and state the three deltas that carry the whole thesis**

```
solo_B2   = M1.WNS - M0.WNS
solo_FixA = M2.WNS - M0.WNS
combined  = M3.WNS - M0.WNS
superadditivity = combined - (solo_B2 + solo_FixA)
```

The design spec's prediction is `solo_B2 ≈ 0`, `solo_FixA ≈ 0`, and `combined > 0` — i.e. **strictly positive superadditivity**, which is exactly what "the arcs are tied" means and is a claim this campaign has never before been able to test. State the measured superadditivity explicitly, with its sign, whatever it is.

- [ ] **Step 6: Also state the population result, which is a result in its own right**

Design spec §14 item 2: the sub-(−1.000 ns) population metric *"has never been validated against a real route"*. Compare `M0/M1/M2/M3`'s populations against §9.2's predictions (4,890 → ~2,272 after B2, ~2,150 after B2+B3). Record whether the metric predicted the routed outcome. **Per the design spec this either validates or retires the metric the whole three-arc program is prioritised by, and is worth recording regardless of the FMax numbers.**

- [ ] **Step 7: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add -f .superpowers/sdd/progress-ipc-push-2026-08-09.md
git commit -m "ledger: the four-cell combined-arc matrix (M0/M1/M2/M3) with measured superadditivity"
```

---

## Task 22: The IPC × FMax acceptance criterion, evaluated on all four cells

**Why:** GC13 and design spec §10.1 — *"the acceptance metric is `delivered = IPC_aggregate × FMax`, not IPC and not FMax in isolation."*

**Files:**
- Modify: `/home/qwertyoruiop/m68k-core-040-ooo/.superpowers/sdd/progress-ipc-push-2026-08-09.md`

**Interfaces:**
- Consumes: the Task 21 matrix.
- Produces: `delivered_ideal` and `delivered_l2` for all four cells, plus the break-even verdict for each.

- [ ] **Step 1: Compute delivered performance for every cell, both models**

```
delivered_ideal(X) = IPC_ideal(X) * FMax(X)
delivered_l2(X)    = IPC_l2(X)    * FMax(X)
```

Baseline: `delivered_ideal(B) = 0.6739 * 182.749 = 123.15` and `delivered_l2(B) = 0.5465 * 182.749 = 99.87` M-instr/s.

- [ ] **Step 2: Apply the break-even table (design spec §10.1) to `M1` and `M3`**

| IPC cost vs `M0` | required FMax | required ΔWNS |
|---:|---:|---:|
| 0.5 % | 183.67 MHz | +0.027 ns |
| **1.0 %** | **184.60 MHz** | **+0.055 ns** |
| 1.5 % | 185.53 MHz | +0.081 ns |
| 2.0 % | 186.48 MHz | +0.108 ns |
| 3.0 % | 188.40 MHz | +0.163 ns |

For each of `M1` and `M3`, state: the measured IPC cost against `M0` on the **ideal** model (GC14: the ideal model is the pessimistic bound and is the one that gates), the row of the table that cost lands on, the FMax that row requires, and whether the measured FMax meets it. Do the same on `l2:5:70` as a secondary, non-gating figure.

- [ ] **Step 3: State the headline plainly**

Three sentences, in the ledger, in this order: (a) did delivered performance improve over the pinned baseline `B`, on the ideal model; (b) did it improve over `M0`; (c) is the core at, above, or below the **200 MHz deployment floor** — and if below, by how many ns, stated as a deficit exactly the way handoff §20 step 7 states it (`Distance to the 200 MHz deployment floor: X ns. The goal is NOT met, at Y MHz.`). Do not round in a flattering direction.

- [ ] **Step 4: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add -f .superpowers/sdd/progress-ipc-push-2026-08-09.md
git commit -m "ledger: IPC x FMax delivered-performance evaluation of the four-cell matrix"
```

---

## Task 23: The falsifier — a mechanical gate, not a judgement call

**Why:** GC2. The design spec §9.4 wrote the kill criterion **in advance**, before any of these numbers existed. This task executes it literally.

**Files:**
- Modify: `/home/qwertyoruiop/m68k-core-040-ooo/.superpowers/sdd/progress-ipc-push-2026-08-09.md`
- Possibly modify: `src/main/scala/m68k040/cache/IcachePlugin.scala`, `src/main/scala/m68k040/top/FullCoreSynth.scala`, and the five test DUTs (only in outcome (b) below)

**Interfaces:**
- Consumes: Tasks 21 and 22.
- Produces: the written verdict, and — if the falsifier fires — the flag-default change that disables Slice 2.

- [ ] **Step 1: Evaluate the falsifier as a literal two-term conjunction**

```
falsifier_fires  ==  (dWNS < +0.050 ns)  AND  (dIPC_aggregate_ideal < -1.0 %)
```

Evaluate it **twice**, because the design spec's §9.4 wording is about "slices 1+2" but GC1 forbids judging Slice 2 solo:

| evaluation | ΔWNS | ΔIPC (ideal) | fires? |
|---|---|---|---|
| **solo** — `M1` vs `M0` | | | |
| **combined** — `M3` vs `M0` | | | |

**It is a conjunction.** Write out both terms and their truth values explicitly. Per GC2:
- a cell that **gains ≥ +0.050 ns** but costs IPC is **kept**;
- a cell that **costs ≤ 1.0 % IPC** but gains nothing is **kept**;
- only **both** conditions together fire the falsifier.

- [ ] **Step 2: Apply the outcome**

**(a) The falsifier does NOT fire on the combined evaluation** → Slice 2 **lands enabled**. `icacheVerdictStage` stays defaulted `true` (Task 15), `lsIssueReadySkid` is defaulted `true` as well:

```scala
class LsEuPlugin(val lsIssueReadySkid: Boolean = true) extends FiberPlugin with LsEuService {
```
and change every `getOrElse("LS_ISSUE_READY_SKID", "0")` to `"1"`. Re-run `make SBT=~/sbt/bin/sbt test-fast` and `ExecuteLockStepSpec` at the new defaults before committing.

**(b) The falsifier DOES fire on the combined evaluation** → apply design spec §9.4 exactly, in all four of its parts:

1. Slice 2 is **left disabled behind its elaboration flag** — revert Task 15's default flip: `class IcachePlugin(val icacheVerdictStage: Boolean = false)` and all six env reads back to `getOrElse(..., "0")`. **Do not delete the RTL** — it is measured, tested, and reversible, and the flag is why it was built this way (design spec §11 slice 2).
2. **Slice 1 stays regardless**, because it is free. Nothing in Tasks 4–7 is touched.
3. Fix A's disposition follows its own row: keep it enabled if `M2`/`M3` show it gains anything or costs nothing; otherwise leave it flagged off alongside Slice 2.
4. **Re-scope the program to attack the D-cache/IQ arc first**, and record that as the recommendation — feeding directly into Task 24.

- [ ] **Step 3: Write the verdict into the ledger, in the campaign's own voice**

Use handoff §20 step 7's format: state what changed, what did not, what the distance to 200 MHz now is, and what category of work is next. Include the sentence *"the falsifier stated in the design spec on 2026-08-10 fired / did not fire"* verbatim so it is greppable.

- [ ] **Step 4: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
git add -A src/
git commit -m "frontend/ls: apply the design spec's written falsifier to the combined two-arc result"
cd /home/qwertyoruiop/m68k-core-040-ooo
git add -f .superpowers/sdd/progress-ipc-push-2026-08-09.md
git commit -m "ledger: the falsifier verdict for the combined two-arc attempt"
```

---

## Task 24: Recensus, hand-off to arc 3, and the conditional Fix-B re-open

**Why:** design spec §11 slice 4 — *"Report the recensus: which families and which endpoint population remain, so the D-cache/IQ arc's design starts from a measured state rather than this document's projection."* With two arcs landed, the remaining arc is `DecodeStage → RAS/ROB` (679 sub-threshold startpoints / 830 endpoints, handoff §20 step 4).

**Files:**
- Modify: `docs/superpowers/plans/2026-08-10-ipc-frontend-fmax-claude-handoff.md` (append `## 23.`)
- Modify: `/home/qwertyoruiop/m68k-core-040-ooo/.superpowers/sdd/progress-ipc-push-2026-08-09.md`
- Create: `synth/probe_arc3_recensus.tcl`

**Interfaces:**
- Consumes: `synth/archive/M3_combined_postrouteN3_decode/fullcore_routed.dcp`.
- Produces: the arc-3 starting census, and the recorded condition under which Fix B is re-opened.

- [ ] **Step 1: Run the recensus on `M3`'s routed checkpoint**

Copy `synth/probe_combined_arcs.tcl` to `synth/probe_arc3_recensus.tcl` and replace the scenario list with a **start/end family census plus the population histogram**, reusing `probe_itlb_hitway.tcl:123-130` verbatim for the histogram and `probe_itlb_hitway.tcl:165-188` for a 45-rung deep ladder. Add one new cut modelling the `DecodeStage → RAS/ROB` arc, with the same erroring object-count guard (GC18):

```tcl
proc cut_arc3 {} {
  set src [get_cells -quiet -hierarchical -filter {NAME =~ *DecodeStage_logic_*}]
  set dst [get_cells -quiet -hierarchical -filter \
    {NAME =~ *RasPlugin_logic_* || NAME =~ *RobPlugin_logic_*}]
  puts "PROBE_CUT arc3 src cells: [llength $src]  dst cells: [llength $dst]"
  if {[llength $src] == 0 || [llength $dst] == 0} { error "PROBE_CUT arc3 matched nothing" }
  set_false_path -from $src -to $dst
}
```

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
setsid flock /var/tmp/m68k-ooo-vivado.lock \
  env DCP=synth/archive/M3_combined_postrouteN3_decode/fullcore_routed.dcp \
      OUT=synth/probe_arc3_recensus \
  vivado -mode batch -nojournal -nolog -source synth/probe_arc3_recensus.tcl \
  > synth/probe_arc3_recensus_runner.out 2>&1
```

- [ ] **Step 2: Report the recensus in the shape arc 3's designer needs**

The table handoff §20 step 4 used — sub-(−1.000 ns) endpoints by **start** family and by **end** family, plus the logic-level/net-share histogram — recomputed on `M3`. Plus: how many of the original 4,890 endpoints remain, which families now dominate, and whether the `DecodeStage → RAS/ROB` arc's own what-if now recovers anything (it should recover more than 0.000 ns if and only if the two-arc thesis held).

- [ ] **Step 3: Record the Fix-B re-open condition**

State, as an explicit conditional so a future session can check it mechanically:

> **Fix B (`earlyProbeSetWriteVec` retime) is re-opened if and only if** the `M3` recensus still shows the `earlyProbeSetWriteVec` / `loadProbe.ready` cone among the top three end-family populations **and** an IPC measurement shows `loadProbe.ready` back-pressure costing throughput (the throughput justification handoff §16 step 5 and §19 step 5 both required). Absent **both**, Fix B stays shelved: §19 step 5 re-verified against the routed netlist that its same-cycle write-versus-consume hole is real, exactly one cycle wide, and a silent-corruption class, and that the thing it buys is not FMax.

- [ ] **Step 4: Write handoff section 23**

Append `## 23. The combined two-arc attempt: <headline> (2026-08-…)` to `docs/superpowers/plans/2026-08-10-ipc-frontend-fmax-claude-handoff.md`, in the voice and structure of §19/§20: what was built, the four-cell matrix, the measured superadditivity with its sign, the IPC × FMax verdict, whether the falsifier fired, whether the population metric predicted the route, the distance to 200 MHz, and what category of work is next. Include the archive paths and the netlist MD5 of every cell so the numbers are reproducible.

- [ ] **Step 5: Commit everything**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/agent-01
git add synth/probe_arc3_recensus.tcl \
        docs/superpowers/plans/2026-08-10-ipc-frontend-fmax-claude-handoff.md
git commit -m "docs(handoff 23) + synth: the combined two-arc result and the arc-3 recensus"
cd /home/qwertyoruiop/m68k-core-040-ooo
git add -f .superpowers/sdd/progress-ipc-push-2026-08-09.md
git commit -m "ledger: combined two-arc attempt closed; arc-3 recensus filed; Fix B re-open condition recorded"
```

---

## Deferred by design (do not implement in this plan)

These are the design spec's own conditional slices 5 and 6, plus its explicit non-goals. Each is a **separate, measured decision** and none may be taken on inside this plan.

| item | source | condition under which it becomes live |
|---|---|---|
| Register the four raw L1I tags at F1 (+80 flops, F2 −0.5 ns) | design spec §6.4, §11 slice 5 | only if `M1`/`M3`'s post-route shows the **P+T half** binding, i.e. the worst path enters F1 rather than F2 |
| **B1** — register the fetch-PC before the ITLB (splits 1.647 / 3.834, +1 further cycle) | design spec §3, §6.5, §11 slice 5 | same condition; explicitly deferred because the long half is 96 % of a 4.000 ns period |
| `BUF_WORDS` 20 → 24 | design spec §4.6, §11 slice 6, hazard H13 | only if `FetchAlignRingTurnoverSpec` or the IPC gate show the fourth ring slot is reservation-starved. The IBuf shift mux was a previously measured FMax limiter, so this is measured, never assumed |
| **Arc 3** — `DecodeStage → RAS/ROB` | design spec §1.2, handoff §20 step 6 | its own design cycle, seeded by Task 24's recensus |
| Fix B — the `earlyProbeSetWriteVec` retime | handoff §16 step 5 fix 2, §19 step 5 | only under Task 24 step 3's **two-part** condition |
| Response tagging / hit-under-miss / a `FetchRsp` cancel path | design spec §1.2, §6.3 | never, under this plan. `FetchRsp` stays untagged and in acceptance order (GC17) |
| Any change to `Tlb.scala` / `ItlbPlugin.scala`, TLB depth or associativity | design spec §1.2, GC16 | never, under this plan |
| Floorplan variants, implementation-strategy directives, re-elaboration | handoff §18, §19 step 8, §21 | closed; all measured to exhaustion |

---

## Self-review record

Run against the writing-plans checklist before this plan was considered finished.

**1. Spec coverage.** Design spec §3 boundaries: B3 → Tasks 4–6; B2 → Tasks 8–16; B1 → deferred table (matching §3's own verdict). §4.1–4.5 stage map / advance discipline / boundary contents / `lookupTick` relocation / what does not move → Tasks 9–10. §4.6 `RING` 3→4 and the no-token-change audit → Tasks 8, 10 step 5, 11. §5 II = 1 → GC12, Task 12 oracle 3, Task 13 case 1. §8 A1/A2/A3 → Task 2, sequenced before all Slice-2 RTL (GC3). §9.4 falsifier → GC2 and Task 23. §10.1 break-even table → GC13 and Task 22. §11 slices 0/1/2/3/4 → Tasks 3 / 4–7 / 8–16 / 23 / 24; slices 5–6 → deferred table. §12.1 oracles 1–4 → Task 12 (1–3) and Task 4 step 4 (4). §12.2 mutations M1–M5 → Task 14 (M1–M4) and Task 4 step 6 (M5). §12.3 nine cases → Task 13. §12.4 existing suites → Task 15 step 2. §13 cost estimate → Task 16 step 4 area check. §14 open questions 1/2/3/4 → the plan's structure itself (1), Task 21 step 6 (2), deferred table (3), Task 10 step 5 (4). Handoff §16/§19/§20 Fix A/Fix B → the explicit choice section, Tasks 17–20, Task 24 step 3. **No spec requirement is unassigned.**

**2. Placeholder scan.** No "TBD", "TODO", "implement later", "add appropriate error handling", or "similar to Task N". Two deliberate, bounded exceptions, both flagged inline with the exact existing code to copy rather than invent: Task 4 step 1's drive loop (reuse `IcachePrefetchSpec`'s) and Task 10 step 3's `when(fire)` body (copy the existing `when(cmdPort.fire)` body verbatim and diff it). Task 13 cases 2–9 are specified by exact behaviour, exact signals and exact assertions rather than by full source, because each is a variation on case 1's fixture, which **is** given in full.

**3. Type consistency.** `icacheVerdictStage: Boolean` (Tasks 8, 11, 15, 23) and `lsIssueReadySkid: Boolean` (Tasks 8, 17, 18, 23) are spelled identically everywhere, including in the environment variables `ICACHE_VERDICT_STAGE` / `LS_ISSUE_READY_SKID`. `ringDepth: Int` (Tasks 8, 11) is never called `RING_DEPTH` or `ringSize`. The `fp` Area's members (`f1Valid`, `f1Pc`, `f1Advance`, `f1Ready`, `f2Valid`, `f2Pc`, `f2Ppn`, `f2Cmode`, `f2Fault`, `f2Dispatch`, `f2Ready`) are declared in Task 9 and used with those exact names in Tasks 10, 12 and 13. `pfInstallArm` / `installDeferCnt` / `installStarves` (Task 4) are used with those names in Tasks 5, 6 and 10. `seedValidReg` / `seedPaddrReg` / `seedCacheableReg` (Task 5) and `heldDemandMissReg` / `pfWindowUpdateReg` / `demandFillStartReg` (Task 6) likewise. `issueStage: Stream[IqContext]` and `lsSkid.{d0Valid, d1Valid, d0Ctx, d1Ctx}` (Task 18) are used with those names in Task 19. Matrix cell names `M0`/`M1`/`M2`/`M3` and the baseline `B` are consistent from GC1 through Task 24.
