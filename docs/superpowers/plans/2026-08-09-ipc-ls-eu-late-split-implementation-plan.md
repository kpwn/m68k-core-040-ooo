# LS EU Late Split (`RESOLVE`→`LAUNCH`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **2026-08-09 execution status:** this is now a historical/verification plan,
> not a literal remaining-task queue. Slice 1 (`cc38cc8`) was ported onto the
> subsequently implemented tokenized VIPT interface, which removed the plan's
> live-DTLB blocker, and Slice 2 is enabled with `earlyFree = true`. A directed
> delayed-refill test proves younger-store issue, translation, and SQ allocation
> while the older load remains in the cache back stage. Focused functional gates
> pass. Seeds 1–3 improve `load-stream` cycles by 43.9%/41.2% under ideal/L2
> memory, and the seed-1 full-suite aggregate improves by 12.8%/8.9%. Paired
> lock-step/corpus comparisons and the PM-serialized routed FMax/LUT gate remain
> pending. Unchecked boxes below describe the original
> branch-specific execution recipe and must not be interpreted as current RTL
> status without consulting the two design specs.

**Goal:** Split the LS EU FSM into a front stage (owns S1, completes stores and SQ-forwarded loads) and a back stage (owns the D-cache access), so a non-forwarded load's 5-cycle cache-access tail stops blocking the next LS µop — cutting non-forwarded load initiation interval from 9 cycles toward 4–5 without regressing post-route FMax or LUT count.

**Architecture:** Two SpinalHDL `StateMachine`s inside `LsEuPlugin.logic` instead of one. The **front** FSM keeps `IDLE`/`XLATE_B`/`XLATE`/`RESOLVE`/`WAIT_SQ` and owns `s1Valid`/`s1Ctx`/`s2Paddr`/`busy`. The **back** FSM owns `BK_IDLE`/`LAUNCH`/`WAIT`/`WAIT_A`/`WAIT_B`, the already-existing 135-flop `llReg` launch register, `lineA`/`aDone`, and a new ~42-flop completion descriptor `bkCtx`. The handoff is the existing `RESOLVE` "no forward" arm, which already captures everything the cache access needs. A Scala elaboration-time flag `earlyFree` gates whether the front actually frees S1 at the handoff: **Slice 1 sets it `false`** so the split is structurally present but cycle-for-cycle identical to today (an early, cheap FMax checkpoint); **Slice 2 flips it to `true`** to realize the IPC win.

**Tech Stack:** SpinalHDL (Scala 2.13, `spinal.lib.fsm.StateMachine`), sbt + ScalaTest (`AnyFunSuite`), Verilator simulation, Vivado 2023-era batch flow on `xcku5p-ffvb676-2-e`.

---

## Global Constraints

Every task's requirements implicitly include this section. Values are copied verbatim from `docs/superpowers/specs/2026-08-09-ipc-ls-eu-pipeline-depth-design.md` and `.superpowers/sdd/progress-ipc-push-2026-08-09.md`.

- **GC1 — Scope lock.** The only `src/main/scala` file this plan may modify is `src/main/scala/m68k040/execute/LsEuPlugin.scala`. **Do NOT touch `src/main/scala/m68k040/frontend/` or `src/main/scala/m68k040/decode/`** — a separate, concurrently-executing initiative (fetch-directed BTB) owns those directories. `DcachePlugin.scala` and `StoreQueue.scala` are **read-only contracts**. Test-side files under `src/test/scala/m68k040/ls/` and `synth/census.tcl` may be modified/created as specified.
- **GC2 — Never assume a SoC address map in RTL.** `src/main/scala` may never consult a hardcoded address-decode map. The `0x2000_0000`-DECERRs behaviour used by tests is a *simulation harness* property (`AxiMemModel.decoded`), test-side only.
- **GC3 — Worktree isolation is mandatory** for every A/B comparison and every baseline measurement. Use `git worktree add`, never `git checkout <sha>` in the shared tree (a real collision happened in task #199).
- **GC4 — Functional gate, every slice:** `ExecuteLockStepSpec` at the baseline count **re-verified on the actual HEAD in use, not assumed** (last recorded: 390/394; the 4 are 3× ITLB + `STOP #imm`), and the full ported corpus with a **byte-identical fail-*name-list*** (last recorded: 891 tests, 63 fails). Compare fail-name-lists, **never counts**.
- **GC5 — LS unit specs have a documented flaky member.** Two consecutive clean-HEAD runs gave 6 and 7 failures of 91 (`m68k040.ls.*` + `DcacheSpec`). Always compare fail-*name-lists*, never counts.
- **GC6 — IPC gate: the `load-stream` kernel is primary.** Landed in commit `caf72ff`. Every IPC measurement uses pinned `IPC_SEED` and **both** `IPC_MEM=zero` and `IPC_MEM=l2:5:70`. The pre-existing `load/store`/`mixed` kernels are 100%-forwarded and structurally cannot see this lever — they are regression-safety only, never the accept signal.
- **GC7 — FMax gate is REAL post-route, not OOC.** `vivado -mode batch -source synth/impl_FullCore.tcl`, preceded by `runMain m68k040.top.GenFullCoreSynthVerilog` (**not** `GenVerilog`, which emits the wrong top). OOC-only is proven unreliable on this design. Paired, back-to-back, same machine, uncontended.
- **GC8 — Explicit pre/post CLB LUT report every FMax gate.** "Don't blow up LUT count" is a binding user constraint independent of FMax.
- **GC9 — Machine-contention discipline.** Check `free -g` and `ps aux --sort=-%mem | head` before **any** Vivado run; require **≥ 20 GB free** and no other Vivado batch job. This 29 GB machine has had severe contention all session, including from the concurrent fetch-directed BTB work which may itself be running heavy synthesis. **Never** run a ported-corpus sweep concurrently with a Vivado impl run — `tools/fuzz/ported-sweep-parallel.sh` peaks at **~3 GB RSS per shard** (not the "<1 GB" its header claims).
- **GC10 — Accept/reject thresholds, stated in advance (spec §8.4).** **ACCEPT** requires all of: `load-stream` cycles improve **≥ 25%** under **both** memory models (paired, 8 seeds); aggregate improves **≥ 8%** under **both** models; post-route FMax **≥ 219 MHz**; CLB LUTs within **+1.5%** of baseline; lock-step / LS-spec / ported-corpus fail-name-lists byte-identical. **REJECT and revert** on any of: FMax **< 217 MHz**, LUTs **> +2.5%**, any new correctness failure, or `load-stream` improving **< 15%**.
- **GC11 — Baseline reference numbers** (spec §6.1, from `synth/census_*.rpt` on `synth/fullcore_routed.dcp`): WNS −0.508 ns, **221.828 MHz**, TNS −462.267 ns, **4512 / 157325 failing endpoints**, **103740 CLB LUTs (47.82%)**, 47343 registers, 67.81% CLB occupancy. `load-stream` baseline cycles (seeds 1/2/3): **3269 / 3275 / 3268** (`zero`), **3426 / 3428 / 3428** (`l2:5:70`). Aggregate: **9268 / 9294 / 9275** (`zero`), **12654 / 12744 / 12635** (`l2:5:70`).
- **GC12 — Out of scope, do not implement (spec §1.2).** NG1 multi-outstanding D-cache loads (`DLoadRsp` is untagged — single back-stage occupancy is a **contract**, not an effort budget); NG2 hit-under-miss in the D-cache; NG3 reclaiming the `reqMatch` settle cycle; NG4 removing `!compValid` from `issue.ready` (measured: −6.55 MHz, +4785 LUTs — it is load-bearing **placement ballast**); NG5 anything under `frontend/`/`decode/`; NG6 store initiation interval. **Stage 2 (the full `IDLE`→`XLATE` S1 split) is explicitly NOT in this plan** — it is deferred behind the three gates in spec §4 and is only *evaluated*, never implemented, in Task 14.
- **GC13 — `issue.ready` stays TEXTUALLY UNCHANGED.** The line `issuePort.ready := !busy && !s1Valid && !compValid` (`LsEuPlugin.scala:1158`) must be byte-identical before and after every slice in this plan. Only *when* `busy` is 0 changes, never *which terms exist*. This is the design's central bet and the entire reason Slice 1 exists.

---

## File Structure

| file | action | responsibility |
|---|---|---|
| `src/main/scala/m68k040/execute/LsEuPlugin.scala` | **Modify** | The split itself: `bkCtx`/`bkBusy`/`bkPoisoned`/`bkStart`, the back FSM, the front FSM's handoff + completion-collision holds, the two correctness fixes. |
| `synth/census.tcl` | **Modify** | Add six LS/D-corridor `census_probe` lines (spec §6.3). |
| `src/test/scala/m68k040/ls/LsPrivStubPlugin.scala` | **Create** | Sim-drivable `PrivilegeService` for standalone LS-EU DUTs (needed to make `xlate.req.supervisor` change under test). |
| `src/test/scala/m68k040/ls/LsEuSourcePlugin.scala` | **Modify** | Add an `iNeedsSup` input so a test can tag a load `u1.needsSupervisor`. |
| `src/test/scala/m68k040/ls/LsEuBackStageSupervisorSpec.scala` | **Create** | Mutation-killed test for spec §3.2(d) — `xlate.req.supervisor` staleness. |
| `src/test/scala/m68k040/ls/LsEuCrossLineBDoneSpec.scala` | **Create** | Mutation-killed test for spec §5.5 — `llReg.bDone` clobber from the front's `IDLE`. |
| `src/test/scala/m68k040/ls/LsEuBackStageOrderSpec.scala` | **Create** | NG1 single-occupancy invariant + the front's `RESOLVE` stall on `bkBusy`. |
| `src/test/scala/m68k040/ls/LsEuCompletionArbiterSpec.scala` | **Create** | Front-vs-back `comp*` arbitration: back wins, front retries, nothing lost. |
| `src/test/scala/m68k040/ls/LsEuBackStagePoisonSpec.scala` | **Create** | Two-poison-bit correctness under `sqFlushSig`. |
| `.superpowers/sdd/progress-ipc-push-2026-08-09.md` | **Modify** | Ledger entries (git-ignored-but-force-tracked: `git add -f`). |

---

## Task 1: LS-corridor post-route census (spec §6.3 — MANDATORY PREREQUISITE, no RTL)

**Why first:** the fetch-directed-BTB spec's equivalent census caught a *wrong premise* before RTL was built on it. This census is **cheap**: `synth/fullcore_routed.dcp` already exists (2026-08-09 08:05, 59.7 MB, reproduces 221.828 MHz exactly), so this is a **read-only checkpoint open**, not a synthesis run.

**Files:**
- Modify: `synth/census.tcl` (append probes after the existing `census_probe fetchalign` line)

**Interfaces:**
- Consumes: `synth/fullcore_routed.dcp` (must exist; verify before starting)
- Produces: `synth/lscensus_family.rpt`, `synth/lscensus_endpoints.rpt`, `synth/lscensus_probe_*.rpt`, and four recorded answers **G-L1 … G-L4** used as go/no-go input to Task 3.

- [ ] **Step 1: Verify the checkpoint exists and the machine is free**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
ls -la synth/fullcore_routed.dcp
free -g
ps aux --sort=-%mem | head -8
```

Expected: `fullcore_routed.dcp` present (~59 MB). **If it is missing, STOP** and report — this plan's census step assumes it; regenerating it is a full impl run and must be scheduled under GC9.

- [ ] **Step 2: Add the LS probes to `synth/census.tcl`**

Append these lines immediately **before** the final `puts "########### CENSUS COMPLETE ###########"` line:

```tcl
# ── LS-corridor probes (2026-08-09 LS EU late-split design, spec §6.3) ──
# These answer G-L1..G-L4 for the RESOLVE->LAUNCH split. Read-only; safe to re-run.
census_probe ls_s1ctx    "*LsEuPlugin_logic_s1Ctx*"       $prefix
census_probe ls_comp     "*LsEuPlugin_logic_comp*"        $prefix
census_probe ls_llreg    "*LsEuPlugin_logic_llReg*"       $prefix
census_probe ls_sq       "*LsEuPlugin_logic_sq*"          $prefix
census_probe ls_busy     "*LsEuPlugin_logic_busy*"        $prefix
census_probe iq_sel3     "*selPorts_3*"                   $prefix
census_probe dc_tagmem   "*DcachePlugin_logic_tagMem*"    $prefix
```

- [ ] **Step 3: Run the census (read-only checkpoint open)**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
vivado -mode batch -nojournal -log synth/vivado_lscensus.log \
       -source synth/census.tcl -tclargs synth/fullcore_routed.dcp synth/lscensus \
       2>&1 | tee synth/lscensus_run.out
```

Expected: `CENSUS_TOTAL_FAILING 4512`, `CENSUS_FAMILY DcachePlugin 1260 ...`, `CENSUS_FAMILY LsEuPlugin 262 ...`, and one `CENSUS_PROBE <label> FAILING_ENDPOINTS <n>` line per probe. Runtime ~5–15 minutes.

**If `CENSUS_TOTAL_FAILING` is not 4512 or the family counts differ materially from GC11, STOP** — the checkpoint is not the one the spec was written against and every downstream number in this plan is untrustworthy.

- [ ] **Step 4: Answer the four gate questions and record them**

Extract the answers from `synth/lscensus_endpoints.rpt` and the `CENSUS_PROBE` console lines:

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
grep -c "LsEuPlugin_logic_busy" synth/lscensus_endpoints.rpt
grep -n "CENSUS_PROBE" synth/lscensus_run.out
grep -n "s1Ctx_uop_size\|s1Ctx_uop_eaDelta" synth/lscensus_endpoints.rpt | wc -l
grep -n "pb_dcache" synth/fullcore_pblock_util.rpt synth/floorplan_dcache.xdc
```

Record each answer verbatim in the ledger (Step 5). The **explicit fail actions** are:

- **G-L1** — Do any failing paths *end* at `LsEuPlugin_logic_busy` or at the IQ's `selPorts_3` ready cone? Spec §6.1 says **no** (88 intermediate hits, 0 endpoints). **If the probe says otherwise, STOP: Slice 1's central premise — that changing `busy`'s timing without changing `issue.ready`'s structure is safe — is wrong, and the design must be re-scoped before any RTL is written.** Report and halt the plan.
- **G-L2** — Confirm `s1Ctx_uop_size` (expect ~235 failing endpoints) and `s1Ctx_uop_eaDelta` (expect ~155). State up front, as a **prediction to be tested**, whether `bkCtx` would *reduce* the number of failing endpoints these two startpoints feed (spec §6.2 argues it should, because `bkCtx` shortens `s1Ctx → comp*` arcs that exist today). Do not treat a reduction as a hoped-for side effect.
- **G-L3** — Establish `pb_dcache`'s real occupancy headroom (`synth/floorplan_dcache.xdc`, `synth/fullcore_pblock_util.rpt`), since `LsEuPlugin_logic_sq` is flattened into it. **If `bkCtx`'s ~42 flops cannot be placed outside that pblock, say so explicitly and re-scope before Task 3.**
- **G-L4** — Relief cap: what is the best achievable FMax if the entire `DcachePlugin` family were fixed? (Take the worst non-`DcachePlugin`/non-`LsEuPlugin` slack from `lscensus_family.rpt`.) **If the headroom is small (single-digit MHz), then this lever must be accepted or rejected on IPC + LUT + FMax-*neutrality*, never on an FMax improvement, and no task in this plan may gate success on MHz movement.** Record the number.

- [ ] **Step 5: Commit the census script change and the ledger entry**

Append to `.superpowers/sdd/progress-ipc-push-2026-08-09.md`:

```markdown
## LS EU late split — Task 1 census (read-only, spec §6.3) COMPLETE

Ran `synth/census.tcl` (extended with 7 LS-corridor probes) read-only on the
existing `synth/fullcore_routed.dcp`. No Vivado implementation was launched.

- Total failing endpoints reproduced: <N> (expected 4512).
- G-L1 (failing paths ENDING at `busy` / `selPorts_3` ready cone): <answer>
- G-L2 (`s1Ctx_uop_size` / `eaDelta` failing-endpoint fanout): <answer>;
  prediction under test: `bkCtx` <reduces|does not reduce> it.
- G-L3 (`pb_dcache` occupancy headroom for ~42 new flops): <answer>
- G-L4 (relief cap if the whole DcachePlugin family were fixed): <N> MHz.
  => this lever is accepted/rejected on IPC + LUT + FMax-NEUTRALITY <only|not only>.
```

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add synth/census.tcl
git add -f .superpowers/sdd/progress-ipc-push-2026-08-09.md
git commit -m "synth: LS-corridor census probes for the LS EU late-split design (spec 6.3)"
```

---

## Task 2: Re-measure every baseline on the actual HEAD

**Why:** GC4/GC11 numbers are *recorded*, not *current*. Every one of them must be reproduced on the exact commit this work branches from, in an isolated worktree, before any RTL changes.

**Files:**
- Create: `/home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-base` (git worktree, not a repo file)
- Modify: `.superpowers/sdd/progress-ipc-push-2026-08-09.md`

**Interfaces:**
- Produces: `BASE_SHA` (the commit all A/B pairs compare against), a lock-step pass/fail-name-list, an LS-spec fail-name-list, a ported-corpus fail-name-list, and an 8-seed × 2-model IPC table including `load-stream`.

- [ ] **Step 1: Create the isolated baseline worktree**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git rev-parse HEAD > /tmp/ls_split_base_sha.txt && cat /tmp/ls_split_base_sha.txt
git worktree add /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-base $(cat /tmp/ls_split_base_sha.txt)
```

- [ ] **Step 2: Run the lock-step suite on the baseline**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-base
~/sbt/bin/sbt 'testOnly m68k040.lockstep.ExecuteLockStepSpec' 2>&1 | tee /tmp/base_lockstep.log
grep -E "^\[info\] (- |Tests:|.*\*\*\* FAILED)" /tmp/base_lockstep.log | grep -i fail | sort > /tmp/base_lockstep_fails.txt
tail -5 /tmp/base_lockstep.log
wc -l /tmp/base_lockstep_fails.txt
```

Expected: a total near **390/394 passing**. Record the **actual** count and the fail-name list. Do not proceed on an assumption.

- [ ] **Step 3: Run the LS unit specs on the baseline**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-base
~/sbt/bin/sbt 'testOnly m68k040.ls.* m68k040.cache.DcacheSpec' 2>&1 | tee /tmp/base_ls.log
grep -i "\*\*\* FAILED" /tmp/base_ls.log | sort > /tmp/base_ls_fails.txt
cat /tmp/base_ls_fails.txt
```

Expected: 91 tests, 6 or 7 failures (GC5 — flaky). Record the **name list**.

- [ ] **Step 4: Run the ported corpus on the baseline (NOT concurrently with anything heavy)**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-base
free -g   # require >= 20 GB free, no Vivado running (GC9)
tools/fuzz/ported-sweep-parallel.sh 4 $(cat /tmp/ls_split_base_sha.txt) 2>&1 | tail -30
cp ported_logs_parallel/all_fails.txt /tmp/base_ported_fails.txt
wc -l /tmp/base_ported_fails.txt
```

Expected: ~891 tests, ~63 fails. Runtime ~15 min at 4 shards. Record the **merged sorted fail-name list**.

- [ ] **Step 5: Measure the IPC baseline — 8 seeds, both memory models, all kernels**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-base
for mem in zero l2:5:70; do
  for s in 1 2 3 4 5 6 7 8; do
    echo "=== mem=$mem seed=$s ==="
    IPC_SEED=$s IPC_MEM=$mem ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' 2>&1 \
      | grep -E "^\[info\] (load-stream|load/store|mixed|dependent-ALU|independent-ALU|branchy|hot-loop|call-return|AGGREGATE)"
  done
done 2>&1 | tee /tmp/base_ipc.txt
```

Expected: `load-stream` ≈ 3269 cycles (`zero`) / 3426 (`l2:5:70`); AGGREGATE ≈ 9268 / 12654 (GC11). **If `load-stream` is not present in the output, STOP** — commit `caf72ff` is not in this worktree and GC6 cannot be satisfied.

- [ ] **Step 6: Record all baselines in the ledger and commit**

Append to `.superpowers/sdd/progress-ipc-push-2026-08-09.md` a section `## LS EU late split — Task 2 baselines re-measured on <BASE_SHA>` containing the four recorded artifacts (lock-step count + fail names, LS-spec fail names, ported fail count + list location, the 8×2 IPC table). Then:

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add -f .superpowers/sdd/progress-ipc-push-2026-08-09.md
git commit -m "docs(ledger): LS EU late-split Task 2 re-measured baselines"
```

---

## Task 3: Slice 1 — the structural split, cycle-for-cycle identical (`earlyFree = false`)

**Why this shape:** spec §8.3 slice 1 exists purely to isolate the **placement/area cost of the restructure** from its IPC effect. Observable behaviour and cycle counts must be **IDENTICAL** to today. Do **not** fold Slice 2 into this task — the whole point is an early, cheap FMax checkpoint (~150 lines) before the bigger investment, because the compValid rejection proved this exact corridor can lose 6.55 MHz and 4785 LUTs to a change with zero added flops.

**Files:**
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala`

**Interfaces:**
- Consumes: nothing from earlier tasks except the Task-1 G-L1 go/no-go.
- Produces (names later tasks rely on, all inside `LsEuPlugin.logic`):
  - `earlyFree: Boolean` — class-level Scala val on `LsEuPlugin`, `false` in Slice 1, `true` in Slice 2.
  - `bkCtx: BkCtx` (Reg bundle), `bkBusy: Bool` (RegInit, `simPublic`), `bkPoisoned: Bool` (RegInit, `simPublic`), `bkStart: Bool` (combinational, `simPublic`).
  - `bkFsm: StateMachine` with states `BK_IDLE`, `LAUNCH`, `WAIT`, `WAIT_A`, `WAIT_B`.
  - `bkCompletes: Bool` (`simPublic`), `backCompFires: Bool` (`simPublic`), `frontCompHeld: Bool` (`simPublic`).
  - `captureCompletionBk(result: Bits): Unit`, `captureFaultBk(atc: Boolean): Unit`, `captureBkCtx(): Unit`.
  - The existing sim taps `dbgIsLaunch` / `dbgIsWait` / `dbgIsWaitA` / `dbgIsWaitB` **keep their exact names** (they are referenced by `MiHangTraceSpec` and `P27HangTraceSpec`) but are now sourced from `bkFsm`. New tap: `dbgIsWaitBk`.

- [ ] **Step 1: Create the working worktree**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git worktree add -b feat/ls-eu-late-split \
  /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work $(cat /tmp/ls_split_base_sha.txt)
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
```

All Task 3–13 edits happen in this worktree.

- [ ] **Step 2: Add the `earlyFree` elaboration flag to the plugin class**

In `src/main/scala/m68k040/execute/LsEuPlugin.scala`, find the class declaration line (`class LsEuPlugin extends FiberPlugin ...`) and insert immediately after the opening brace:

```scala
  // ─────────────────────────────────────────────────────────────────────────
  // LS EU LATE SPLIT (2026-08-09, spec docs/superpowers/specs/
  // 2026-08-09-ipc-ls-eu-pipeline-depth-design.md §3).
  //
  // The FSM below is split into a FRONT stage (IDLE/XLATE_B/XLATE/RESOLVE/
  // WAIT_SQ — owns s1*, s2Paddr, busy; completes every store and every
  // SQ-forwarded load exactly as before) and a BACK stage (BK_IDLE/LAUNCH/
  // WAIT/WAIT_A/WAIT_B — owns llReg/lineA/aDone and the D-cache access).
  //
  // `earlyFree` selects whether the front actually FREES S1 at the handoff:
  //   false (Slice 1) — the front parks in WAIT_BK holding busy/s1Valid until
  //                     the back completes. Cycle-for-cycle IDENTICAL to the
  //                     pre-split design. Exists ONLY to measure the placement/
  //                     area cost of the restructure in isolation, because a
  //                     logically-redundant one-term change in this exact cone
  //                     was measured on 2026-08-09 to cost -6.55 MHz and
  //                     +4785 LUTs (the `!compValid` rejection — placement
  //                     ballast, not logic).
  //   true  (Slice 2) — the front frees S1 at RESOLVE and stalls there only
  //                     while `bkBusy` (NG1: exactly one load in the cache,
  //                     because `DLoadRsp` carries no id).
  //
  // `issuePort.ready`'s EXPRESSION is deliberately unchanged in BOTH modes —
  // only WHEN `busy` reads 0 changes. See spec §6.2.
  private val earlyFree: Boolean = false
```

- [ ] **Step 3: Declare the back-stage state (context, busy, poison, start pulse)**

In `LsEuPlugin.scala`, immediately **after** the `val llReg = new Area { ... }` block (currently ending at line 515) and **before** the `val xlateBArm = RegInit(False)` declaration, insert the code below.

Forward-reference check (already verified — do not re-derive): `sqFlushSig` is allocated at `:150` and already used at `:230`; `nzvcW`/`xW` are allocated at `:165`/`:170`. All three are resolvable at this insertion point.

```scala
    // ── BACK-STAGE completion descriptor (spec §3.2(c)) ──────────────────────
    // Everything `captureCompletion`/`captureFault` read from `u1`/`s1Ctx`/`xlate`
    // must be captured at the RESOLVE->LAUNCH handoff, because after the handoff the
    // FRONT stage is translating a DIFFERENT µop and every one of those live reads
    // would silently belong to that younger µop.
    //
    // Restricted to what a non-forwarded LOAD can reach: the back stage is only ever
    // entered from RESOLVE, and RESOLVE is only reachable from XLATE's `otherwise`
    // (== !isStore) arm, so `isStore`, `isAutoStoreAn` (= isStore && ...) and
    // `compRmwStore`/`compEaAutoDrop` (both require isStore) are provably False here.
    // `u1.leaAddr` is likewise unreachable: IDLE completes an LEA before XLATE.
    // `u1.stkPush` is a store-only marker for the same reason.
    //
    // `lineOff` (= s1Va(3 downto 0)) and `u1.size`, both read by WAIT_B's
    // extractCross, are ALREADY carried as llReg.vaddr(3 downto 0) / llReg.size —
    // no new state for those.
    case class BkCtx() extends Bundle {
      val robId           = UInt(6 bits)
      val pdst            = UInt(6 bits)
      val pdstValid       = Bool()
      val wakes           = Bool()
      val ccrRestore      = Bool()
      val signExtW        = Bool()   // MOVEM.W load sign-extend (u1.isMovea && size==WORD)
      val dstArch         = UInt(5 bits)
      val writesNzvc      = Bool()
      val pNzvcDst        = UInt(nzvcW.address.getWidth bits)
      val writesX         = Bool()
      val pXDst           = UInt(xW.address.getWidth bits)
      val crackDrop       = Bool()
      val keepCommit      = Bool()
      val needsSupervisor = Bool()
      // spec §3.2(d) — THE correctness fix. `WAIT` reads `xlate.req.supervisor` LIVE
      // today (`suppressForLaterPrivCheck`, and `captureFault`'s `compFaultSup`). The
      // registered DTLB request re-captures `reqDrvSup` (the LIVE architectural S bit)
      // EVERY cycle, so once the back stage outlives its own S1 residency those reads
      // report whatever the S bit is NOW, not what it was for THIS access. Capturing it
      // here is not a refactor detail: it is a correctness requirement of the split.
      val xlateSup        = Bool()
    }
    val bkCtx      = Reg(BkCtx())
    // Back stage occupied. NG1 (single-occupancy) is a CONTRACT, not an effort budget:
    // `DLoadRsp` carries no id (DcacheTypes.scala:24-28) and the back FSM attributes
    // `loadRsp` purely by its own state, so a second load must never enter.
    val bkBusy     = RegInit(False); bkBusy.simPublic()
    // spec §3.2(e) — with two independent in-flight µops, one `poisoned` bit cannot
    // serve both: a new `issuePort.fire` would clear the poison of the OLDER µop still
    // draining in the back stage. Set from `poisoned` at handoff (ORed with a
    // same-cycle `sqFlushSig`, because `poisoned` is a Reg that only reads True from
    // the NEXT cycle) and re-set by any later flush while the back is occupied.
    val bkPoisoned = RegInit(False); bkPoisoned.simPublic()
    // Combinational handoff pulse: the front's RESOLVE asserts it, the back's BK_IDLE
    // consumes it in the SAME cycle, so the back reaches LAUNCH on exactly the cycle
    // the pre-split FSM did (a Reg-based handshake would cost one extra cycle).
    val bkStart    = Bool(); bkStart := False; bkStart.simPublic()
    // Observability for the arbitration tests: True the cycle a FRONT completion was
    // suppressed and held because the BACK claimed the shared comp* stage.
    val frontCompHeld = Bool(); frontCompHeld := False; frontCompHeld.simPublic()
    // A later flush poisons whatever is draining in the back. Plain component statement:
    // SpinalHDL elaborates StateMachine bodies from a pre-pop task, i.e. AFTER every
    // plain statement, so the FRONT FSM's handoff assignment below correctly WINS on the
    // handoff cycle (where `bkBusy` is still False anyway and this does not fire).
    when(sqFlushSig && bkBusy) { bkPoisoned := True }
```

- [ ] **Step 4: Add the back-stage capture functions**

Insert immediately **after** the existing `def captureFault(...)` body (which currently ends at line 979, just before the `// ── Precise-store deferred-completion replay` comment block):

```scala
    // ── BACK-STAGE capture (spec §3.2(c)) ────────────────────────────────────
    // The back-stage twin of `captureCompletion`, reading `bkCtx`/`llReg` instead of
    // the live `u1`/`s1Ctx`/`s1Va`. Every field the load path cannot reach is
    // hardcoded to its provable value (see BkCtx's declaration comment) rather than
    // carried as a flop.
    def captureCompletionBk(result: Bits): Unit = {
      liveCompletionFires := True
      compValid      := True
      compRobId      := bkCtx.robId
      compData       := Mux(bkCtx.signExtW, result(15 downto 0).asSInt.resize(32).asBits, result)
      compPdst       := bkCtx.pdst
      compPdstValid  := bkCtx.pdstValid
      compIsLoad     := True
      compWakes      := bkCtx.wakes
      compStkPush    := False           // store-only marker; unreachable from RESOLVE
      compCcrRestore := bkCtx.ccrRestore
      compEaAutoDrop := False           // requires isStore
      compRmwStore   := False           // requires isStore
      compCrackDrop  := bkCtx.crackDrop
      compKeepCommit := bkCtx.keepCommit
      compDstArch    := bkCtx.dstArch
      // RTR CCR-restore: NZVC := loaded[3:0], X := loaded[4]. A plain load leaves
      // writesNzvc/writesX False, so these are don't-cares on that path (identical to
      // the pre-split behaviour, where the storeNzvc Mux arm was equally a don't-care).
      compNzvc       := result(3 downto 0)
      compNzvcWrite  := bkCtx.writesNzvc
      compNzvcDst    := bkCtx.pNzvcDst
      compX          := result(4)
      compXWrite     := bkCtx.writesX
      compXDst       := bkCtx.pXDst
      compIsFault    := False
    }

    // The back-stage twin of `captureFault`. Only ONE call site exists (a D-cache
    // refill AXI bus error surfacing on `loadRsp.payload.fault`, task #189), so
    // `atc` is always false and `faultAddr` is always slot A's address — which
    // `llReg.vaddr` holds verbatim (RESOLVE captured it from s1Va).
    def captureFaultBk(atc: Boolean): Unit = {
      liveCompletionFires := True
      compValid      := True
      compRobId      := bkCtx.robId
      compPdstValid  := False
      compNzvcWrite  := False
      compXWrite     := False
      compIsLoad     := False
      compWakes      := False
      compStkPush    := False
      compCcrRestore := False
      compEaAutoDrop := False
      compCrackDrop  := False
      compKeepCommit := False
      compIsFault    := True
      compFaultAddr  := llReg.vaddr
      compFaultWr    := False           // requires isStore
      compFaultSize  := llReg.size.mux(
        m68k040.isa.Size.BYTE -> U(0, 2 bits),
        m68k040.isa.Size.WORD -> U(1, 2 bits),
        m68k040.isa.Size.LONG -> U(2, 2 bits))
      // spec §3.2(d): the CAPTURED supervisor bit, never the live one.
      compFaultSup   := bkCtx.xlateSup
      compFaultAtc   := Bool(atc)
    }

    // Capture the back-stage descriptor at the RESOLVE->LAUNCH handoff. Called from
    // the FRONT FSM's RESOLVE "no forward" arm, in the SAME cycle llReg is captured,
    // so every source is the still-resident S1 context of THIS load.
    def captureBkCtx(): Unit = {
      bkCtx.robId           := s1Ctx.robId
      bkCtx.pdst            := u1.pdst
      bkCtx.pdstValid       := u1.pdstValid && !u1.ccrRestore
      // Carried as the FULL pre-split expression rather than the load-only subset, so
      // this can never silently diverge if a future µop shape reaches RESOLVE.
      bkCtx.wakes           := (isLoad && !u1.ccrRestore) || u1.stkPush || u1.leaAddr ||
                               (isAutoStoreAn && u1.pdstValid)
      bkCtx.ccrRestore      := u1.ccrRestore
      bkCtx.signExtW        := u1.isMovea && isLoad && (u1.size === m68k040.isa.Size.WORD)
      bkCtx.dstArch         := u1.dstArch
      bkCtx.writesNzvc      := u1.writesNzvc
      bkCtx.pNzvcDst        := u1.pNzvcDst
      bkCtx.writesX         := u1.writesX
      bkCtx.pXDst           := u1.pXDst
      bkCtx.crackDrop       := u1.divIsRem
      bkCtx.keepCommit      := u1.keepCommit
      bkCtx.needsSupervisor := u1.needsSupervisor
      bkCtx.xlateSup        := xlate.req.supervisor
    }
```

- [ ] **Step 5: Add the back FSM and the arbitration signals, BEFORE the front FSM**

Replace the single line `val fsm = new StateMachine {` (currently line 1220) with the back FSM, the derived arbitration nets, and then the front FSM's opening line:

```scala
    // ── BACK STAGE FSM: owns the D-cache access (spec §3.1) ──────────────────
    // Declared BEFORE the front FSM so `bkFsm.isActive(...)` is available to the
    // front's completion-collision guards. It reads `bkStart`/`bkPoisoned`/`bkCtx`/
    // `llReg` (all declared above) and writes only back-owned state, so there is no
    // elaboration-order dependency in the other direction.
    val bkFsm = new StateMachine {
      val BK_IDLE = new State with EntryPoint
      val LAUNCH  = new State   // registered cache launch: drive loadCmd off llReg (FMax #1)
      val WAIT    = new State   // aligned: cache load cmd accepted, awaiting loadRsp
      val WAIT_A  = new State   // cross: slot A accepted, awaiting line A
      val WAIT_B  = new State   // cross: slot B accepted, awaiting line B -> merge

      BK_IDLE.whenIsActive {
        // spec §5.5 — THE SECOND correctness fix. This housekeeping used to live in the
        // FRONT's IDLE. Once the front can be in IDLE while a CROSS load is mid-flight
        // in WAIT_A (llReg.bDone set, slot B not yet launched), clearing it from the
        // front would re-point the back's slot-B cache command at slot A's address:
        // the already-fixed cross-line-store-after-load hazard reappearing in a new
        // form. It is BACK-owned now, cleared only when the back is genuinely idle.
        llReg.bDone := False
        when(bkStart) { goto(LAUNCH) }
      }

      LAUNCH.whenIsActive {
        when(dcache.loadCmd.fire) {
          // Slot A accepted -> drop loadCmd.valid (do NOT re-issue slot A while WAIT/
          // WAIT_A awaits its response). For a cross access WAIT_A re-asserts the launch
          // for slot B (bDone) once slot A's line lands.
          llReg.valid := False
          when(llReg.twoAccess) { aDone := False; goto(WAIT_A) }
          .otherwise { goto(WAIT) }
        }
      }

      WAIT.whenIsActive {
        when(dcache.loadRsp.valid) {
          when(!bkPoisoned) {
            // spec §3.2(d): both terms come from `bkCtx`, NOT from the live `u1`/
            // `xlate.req` — after the split those describe a DIFFERENT µop. See the
            // pre-split comment block for the full task-#189 rationale this preserves:
            // a user-mode `MOVE <ea>,SR` source load must NOT report a bus fault (the
            // later sysOp's own privilege check owns the trap), but a SUPERVISOR-mode
            // one must.
            val suppressForLaterPrivCheck = bkCtx.needsSupervisor && !bkCtx.xlateSup
            when(dcache.loadRsp.payload.fault && !suppressForLaterPrivCheck) { captureFaultBk(atc = false) }
            .otherwise { captureCompletionBk(dcache.loadRsp.payload.data) }
          }
          bkBusy := False
          goto(BK_IDLE)
        }
      }

      WAIT_A.whenIsActive {
        when(dcache.loadRsp.valid && !aDone) {
          lineA       := dcache.loadRsp.payload.line
          aDone       := True
          llReg.bDone := True
        }
        when(aDone) {
          llReg.valid := True
          when(dcache.loadCmd.fire) { llReg.valid := False; goto(WAIT_B) }
        }
      }

      WAIT_B.whenIsActive {
        when(dcache.loadRsp.valid) {
          // `lineOff`/`u1.size` are back-carried as llReg.vaddr(3 downto 0)/llReg.size
          // (spec §3.2(c)) — reading the live s1Va/u1 here would be the same class of
          // staleness bug as the supervisor read above.
          val merged = m68k040.cache.DcacheByteLane.extractCross(
            lineA, dcache.loadRsp.payload.line, llReg.vaddr(3 downto 0), llReg.size)
          when(!bkPoisoned) { captureCompletionBk(merged) }
          bkBusy := False
          goto(BK_IDLE)
        }
      }
    }

    // ── Front-vs-back completion arbitration (spec §3.2(f) / §5.3) ───────────
    // Today exactly one thing drives the shared comp* stage per cycle. After the split
    // three writers exist: the front's live capture, the back's live capture, and the
    // precise-store deferred replay. The replay already yields to `liveCompletionFires`.
    // Front-vs-back is arbitrated HERE, and the BACK WINS: `dcache.loadRsp` is a
    // 1-cycle Flow with no backpressure (DcacheTypes.scala:74), so a missed back
    // completion LOSES the load, while the front can simply hold one cycle.
    //
    // Derived from `bkFsm.isActive` rather than from an assignment inside either FSM,
    // so neither FSM's statement-emission order can affect it.
    val bkInWait      = bkFsm.isActive(bkFsm.WAIT)
    val bkInWaitB     = bkFsm.isActive(bkFsm.WAIT_B)
    // The back RELEASES this cycle (poisoned or not) — the front's Slice-1 WAIT_BK
    // uses this to free S1 on exactly the pre-split cycle.
    val bkCompletes   = (bkInWait || bkInWaitB) && dcache.loadRsp.valid
    bkCompletes.simPublic()
    // The back actually WRITES comp* this cycle (a poisoned back load writes nothing,
    // so the front is free to use the stage).
    val backCompFires = bkCompletes && !bkPoisoned
    backCompFires.simPublic()

    // ── FRONT STAGE FSM: owns s1*/s2Paddr/busy; completes stores + forwarded loads ──
    val fsm = new StateMachine {
```

Then, inside the front FSM's state declarations, **replace** the four lines currently declaring `LAUNCH`/`WAIT`/`WAIT_A`/`WAIT_B` (lines 1225–1228) with a single conditional state:

```scala
      // Slice-1 ONLY: park here holding busy/s1Valid until the back completes, so the
      // observable cycle counts are IDENTICAL to the pre-split design. With
      // `earlyFree = true` this state is not elaborated at all and RESOLVE frees S1
      // directly (Slice 2).
      val WAIT_BK = if (earlyFree) null else new State
```

(The declarations of `IDLE`, `XLATE_B`, `XLATE`, `RESOLVE`, `WAIT_SQ` are unchanged.)

- [ ] **Step 6: Rewrite the front FSM's `IDLE` state (drop the bDone clear, add the collision holds)**

Replace the `IDLE.whenIsActive { ... }` body's opening — specifically **delete** these lines (currently 1233–1241, the whole stale comment block plus the assignment):

```scala
        // Clear the cross slot-B select left set by a PRIOR cross-line LOAD (WAIT_A sets
        ... (comment continues) ...
        llReg.bDone := False
```

and replace them with:

```scala
        // spec §5.5: `llReg.bDone` housekeeping MOVED to the back FSM's BK_IDLE. Doing
        // it here would clear a live cross-load's bDone mid-flight once the front can
        // reach IDLE while the back is still in WAIT_A. RESOLVE's own explicit
        // `llReg.bDone := False` at handoff still covers the case this line was
        // originally written for (a store's launch never re-uses a stale slot-B select).
```

Then, in the same state, replace the `when(u1.leaAddr) { ... }` arm with the collision-guarded form:

```scala
          when(u1.leaAddr) {
            // LEA address-generate: complete immediately with the computed EA address
            // (s1Va) as the int result. NO translate, NO cache access -> never page-faults.
            // Task #139 mechanism #2: suppress for a poisoned (squashed) access.
            // spec §5.3: yield the shared comp* stage to an older back-stage completion
            // and retry next cycle (busy/s1Valid explicitly re-asserted because IDLE's
            // pre-update `busy` may still read False on this cycle).
            when(backCompFires) {
              frontCompHeld := True
              busy    := True
              s1Valid := True
            } otherwise {
              when(!poisoned) { captureCompletion(s1Va.asBits) }
              busy    := False
              s1Valid := False
            }
          } elsewhen(isLoad || isStore) {
```

and replace the `when(xlateFault) { ... }` arm's body with:

```scala
              when(xlateFault) {
                // MMU access fault: completes as a FAULT (vector 2). spec §5.3: hold if
                // the back claimed comp* this cycle; `xlateReady && reqMatch` still hold
                // next cycle (no new issuePort.fire can occur while s1Valid is re-asserted).
                when(backCompFires) {
                  frontCompHeld := True
                  busy    := True
                  s1Valid := True
                } otherwise {
                  when(!poisoned) { captureFault() }
                  busy    := False
                  s1Valid := False
                  goto(IDLE)
                }
              } otherwise {
```

and the final defensive non-memory arm:

```scala
          } otherwise {
            when(backCompFires) {
              frontCompHeld := True
              busy    := True
              s1Valid := True
            } otherwise {
              when(!poisoned) { captureCompletion(B(0, 32 bits)) }   // non-memory (defensive)
            }
          }
```

- [ ] **Step 7: Guard `XLATE_B`'s fault arm**

Replace `XLATE_B`'s `when(xlateFault) { ... }` body with:

```scala
          when(xlateFault) {
            // addrB's OWN translation faulted. Report the fault at addrB's own address.
            // spec §5.3: hold on a comp* collision. `xlateBArm` stays True while holding,
            // so `reqMatch`/`xlateReady` re-evaluate identically next cycle. `busy` is
            // already True from this state's own header, and the S0->S1 advance reads the
            // pre-update `busy` (True), so `s1Valid` holds with no explicit re-assert.
            when(backCompFires) {
              frontCompHeld := True
            } otherwise {
              when(!poisoned) { captureFault(faultAddr = s1AddrB) }
              xlateBArm := False
              busy      := False
              s1Valid   := False
              goto(IDLE)
            }
          } otherwise {
```

- [ ] **Step 8: Guard `XLATE`'s two comp*-writing store arms**

Replace `XLATE`'s `elsewhen(storePrivBlocked) { ... } elsewhen(!sq.io.full) { ... }` arms with:

```scala
          } elsewhen(storePrivBlocked) {
            // Privileged store (e.g. MOVES.L) in user mode: complete immediately but
            // NEVER touch `sq.io.alloc`. spec §5.3: hold on a comp* collision.
            when(backCompFires) {
              frontCompHeld := True
            } otherwise {
              captureCompletion(B(0, 32 bits))
              busy    := False
              s1Valid := False
              goto(IDLE)
            }
          } elsewhen(!sq.io.full) {
            // spec §5.3: a FAST store's alloc and its architectural completion happen in
            // the SAME cycle by construction, so a comp* collision must hold BOTH — the
            // alloc may not run without the completion. A PRECISE store's `deferCompletion`
            // touches no comp* register at all, so it proceeds regardless.
            when(fastStore && backCompFires) {
              frontCompHeld := True
            } otherwise {
              sq.io.alloc.valid           := True
              sq.io.alloc.payload.precise := !fastStore
              when(fastStore) {
                // exactly today's path: architectural completion the SAME cycle as alloc.
                captureCompletion(B(0, 32 bits))
              } otherwise {
                // precise: latch the withheld completion for later replay.
                deferCompletion()
              }
              busy    := False
              s1Valid := False
              goto(IDLE)
            }
          } otherwise {
            goto(WAIT_SQ)
          }
```

- [ ] **Step 9: Rewrite `RESOLVE` — the handoff**

Replace the whole `RESOLVE.whenIsActive { ... }` body with:

```scala
      RESOLVE.whenIsActive {
        busy := True
        when(fwdHit && !s1TwoAccess) {
          // full-overlap forward: skip the cache (aligned only).
          // spec §5.3: hold on a comp* collision (busy is already True from the header;
          // s1Valid holds because the S0->S1 advance reads the pre-update busy).
          when(backCompFires) {
            frontCompHeld := True
          } otherwise {
            // Task #139 mechanism #2: suppress for a poisoned (squashed) access.
            when(!poisoned) { captureCompletion(fwdData) }
            busy    := False
            s1Valid := False
            goto(IDLE)
          }
        } elsewhen(fwdStall || (fwdHit && s1TwoAccess)) {
          // overlap with an older store: re-sample the SQ and retry.
          fwdHit   := sq.io.fwd.rsp.hit
          fwdStall := sq.io.fwd.rsp.stall
          fwdData  := sq.io.fwd.rsp.data
        } otherwise {
          // ── THE HANDOFF (spec §3.2(b)) ──────────────────────────────────────
          // NG1: exactly ONE load may be in the cache at a time, because `DLoadRsp`
          // carries no id and the back FSM attributes responses purely by state. Stall
          // here while the back is still occupied. (Deliberately NOT `!bkBusy ||
          // bkCompletes`: that would put `dcache.loadRsp.valid` into the front's
          // next-state cone, which is exactly the kind of perturbation this corridor
          // was just measured to punish. Worth at most 1 cycle of II; see spec §6.2.)
          when(!bkBusy) {
            llReg.valid     := True
            llReg.vaddr     := s1Va
            llReg.paddr     := s2Paddr
            llReg.addrB     := s1AddrB
            llReg.paddrB    := s2PaddrB
            llReg.size      := u1.size
            llReg.cmode     := s2Cmode
            llReg.twoAccess := s1TwoAccess
            llReg.bDone     := False
            captureBkCtx()
            bkBusy  := True
            // spec §3.2(e): `poisoned` is a Reg SET by `sqFlushSig`, so it only reads
            // True from the NEXT cycle — a handoff on the flush cycle itself must OR in
            // the live pulse or the back µop would drain unpoisoned. (Pre-split this was
            // covered implicitly: the flush set `poisoned` while `busy` was still True
            // and WAIT read it a cycle later.)
            bkPoisoned := poisoned || sqFlushSig
            bkStart := True
            if (earlyFree) {
              busy    := False
              s1Valid := False
              goto(IDLE)
            } else {
              goto(WAIT_BK)
            }
          }
        }
      }
```

- [ ] **Step 10: Replace the four migrated front states with `WAIT_BK`**

**Delete** the front FSM's entire `LAUNCH.whenIsActive { ... }`, `WAIT.whenIsActive { ... }`, `WAIT_A.whenIsActive { ... }` and `WAIT_B.whenIsActive { ... }` blocks (currently lines 1465–1568, including their comment headers — that logic now lives in `bkFsm`). In their place insert:

```scala
      // Slice-1 conservative stall (spec §8.3 slice 1): the front holds busy/s1Valid
      // here for exactly as long as the pre-split FSM held them in LAUNCH/WAIT/WAIT_A/
      // WAIT_B, and releases on the SAME cycle the back captures its completion
      // (`bkCompletes` is combinational for precisely this reason). Result: bit-identical
      // cycle counts, so any FMax/LUT movement measured at this slice is attributable
      // ENTIRELY to the structural restructure, not to a behaviour change.
      if (!earlyFree) {
        WAIT_BK.whenIsActive {
          busy    := True
          s1Valid := True
          when(bkCompletes) {
            busy    := False
            s1Valid := False
            goto(IDLE)
          }
        }
      }
```

- [ ] **Step 11: Guard `WAIT_SQ`'s alloc arm and re-point the debug taps**

Replace `WAIT_SQ`'s `elsewhen(!sq.io.full) { ... }` arm with:

```scala
        } elsewhen(!sq.io.full) {
          // Same comp*-collision rule as XLATE's alloc arm (spec §5.3).
          when(fastStore && backCompFires) {
            frontCompHeld := True
          } otherwise {
            sq.io.alloc.valid           := True
            sq.io.alloc.payload.precise := !fastStore
            when(fastStore) { captureCompletion(B(0, 32 bits)) } otherwise { deferCompletion() }
            busy    := False
            s1Valid := False
            goto(IDLE)
          }
        }
```

Then replace the debug-tap block (currently lines 1697–1705) with:

```scala
    val dbgIsIdle    = fsm.isActive(fsm.IDLE);    dbgIsIdle.simPublic()
    val dbgIsXlateB  = fsm.isActive(fsm.XLATE_B); dbgIsXlateB.simPublic()
    val dbgIsXlate   = fsm.isActive(fsm.XLATE);   dbgIsXlate.simPublic()
    val dbgIsResolve = fsm.isActive(fsm.RESOLVE); dbgIsResolve.simPublic()
    val dbgIsWaitSQ  = fsm.isActive(fsm.WAIT_SQ); dbgIsWaitSQ.simPublic()
    // Slice-1-only front state; a stable (always-False) tap when earlyFree is set, so
    // no test has to know which slice is compiled.
    val dbgIsWaitBk  = Bool()
    dbgIsWaitBk := (if (earlyFree) False else fsm.isActive(fsm.WAIT_BK))
    dbgIsWaitBk.simPublic()
    // BACK-stage taps. NAMES DELIBERATELY UNCHANGED from the pre-split front states —
    // `MiHangTraceSpec` and `P27HangTraceSpec` reference them by these exact names.
    val dbgIsLaunch  = bkFsm.isActive(bkFsm.LAUNCH);  dbgIsLaunch.simPublic()
    val dbgIsWait    = bkFsm.isActive(bkFsm.WAIT);    dbgIsWait.simPublic()
    val dbgIsWaitA   = bkFsm.isActive(bkFsm.WAIT_A);  dbgIsWaitA.simPublic()
    val dbgIsWaitB   = bkFsm.isActive(bkFsm.WAIT_B);  dbgIsWaitB.simPublic()
    val dbgBkIdle    = bkFsm.isActive(bkFsm.BK_IDLE); dbgBkIdle.simPublic()
```

- [ ] **Step 12: Verify `issue.ready` is textually unchanged (GC13) and it compiles**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
grep -n "issuePort.ready :=" src/main/scala/m68k040/execute/LsEuPlugin.scala
git diff -U0 src/main/scala/m68k040/execute/LsEuPlugin.scala | grep -c "issuePort.ready"
~/sbt/bin/sbt compile 2>&1 | tail -20
```

Expected: the grep prints exactly `issuePort.ready := !busy && !s1Valid && !compValid`; the diff-grep prints `0` (the line is untouched); compile succeeds.

- [ ] **Step 13: Verify Slice 1 is CYCLE-IDENTICAL — the LS specs**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
~/sbt/bin/sbt 'testOnly m68k040.ls.* m68k040.cache.DcacheSpec' 2>&1 | tee /tmp/s1_ls.log
grep -i "\*\*\* FAILED" /tmp/s1_ls.log | sort > /tmp/s1_ls_fails.txt
diff /tmp/base_ls_fails.txt /tmp/s1_ls_fails.txt && echo "LS FAIL-NAME-LISTS IDENTICAL"
```

Expected: identical fail-name lists (GC5 — names, never counts).

- [ ] **Step 14: Verify Slice 1 is CYCLE-IDENTICAL — lock-step**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
~/sbt/bin/sbt 'testOnly m68k040.lockstep.ExecuteLockStepSpec' 2>&1 | tee /tmp/s1_lockstep.log
grep -E "^\[info\] (- |Tests:|.*\*\*\* FAILED)" /tmp/s1_lockstep.log | grep -i fail | sort > /tmp/s1_lockstep_fails.txt
diff /tmp/base_lockstep_fails.txt /tmp/s1_lockstep_fails.txt && echo "LOCKSTEP FAIL-NAME-LISTS IDENTICAL"
```

Expected: identical. **Any new failure means the split is not behaviour-neutral — fix it before proceeding, do not rationalize it.**

- [ ] **Step 15: Verify Slice 1 is CYCLE-IDENTICAL — IPC must be BIT-identical**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
for mem in zero l2:5:70; do
  for s in 1 2 3; do
    echo "=== mem=$mem seed=$s ==="
    IPC_SEED=$s IPC_MEM=$mem ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' 2>&1 \
      | grep -E "^\[info\] (load-stream|load/store|mixed|AGGREGATE)"
  done
done 2>&1 | tee /tmp/s1_ipc.txt
```

Expected: **every** cycle count bit-identical to the corresponding line in `/tmp/base_ipc.txt`. This is the definition of Slice 1. **A single cycle of movement means `earlyFree` leaked or `WAIT_BK`'s release is off by a cycle — fix it, do not proceed.**

- [ ] **Step 16: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
git add src/main/scala/m68k040/execute/LsEuPlugin.scala
git commit -m "lsu: split the LS EU FSM at RESOLVE->LAUNCH (structural, cycle-identical)

Slice 1 of the LS EU late-split design (spec 2026-08-09-ipc-ls-eu-pipeline-depth).
Front FSM (IDLE/XLATE_B/XLATE/RESOLVE/WAIT_SQ) owns s1*/s2Paddr/busy; back FSM
(BK_IDLE/LAUNCH/WAIT/WAIT_A/WAIT_B) owns llReg/lineA/aDone and the cache access.
Includes both correctness fixes the split requires: bkCtx.xlateSup (spec 3.2d) and
the llReg.bDone relocation to the back's own idle (spec 5.5), plus the two-poison-bit
and front-vs-back comp* arbitration.

earlyFree=false => behaviour and cycle counts are IDENTICAL to before; IPC verified
bit-identical over 3 seeds x 2 memory models. issue.ready is textually unchanged."
```

---

## Task 4: `LsEuBackStageSupervisorSpec` — mutation-killed test for the `xlate.req.supervisor` fix

**Why:** spec §8.2 requires this test to be written so it **FAILS against a deliberately-broken variant that reads `xlate.req.supervisor` live**. That is the gate; "it passes now" is not evidence.

**Files:**
- Create: `src/test/scala/m68k040/ls/LsPrivStubPlugin.scala`
- Modify: `src/test/scala/m68k040/ls/LsEuSourcePlugin.scala`
- Create: `src/test/scala/m68k040/ls/LsEuBackStageSupervisorSpec.scala`

**Interfaces:**
- Consumes: `LsEuPlugin.logic.bkCtx.xlateSup`, `dbgIsWait`, `bkBusy` (Task 3).
- Produces: `LsPrivStubPlugin` (a `FiberPlugin with PrivilegeService` exposing `logic.iSupervisor: Bool` as an `in Bool()`), and `LsEuSourcePlugin.logic.iNeedsSup: Bool` (an `in Bool()` driving `uop.needsSupervisor`) — both reused by Task 10.

- [ ] **Step 1: Create the sim-drivable privilege stub**

Create `src/test/scala/m68k040/ls/LsPrivStubPlugin.scala`:

```scala
package m68k040.ls

import m68k040.services.PrivilegeService
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

/** Sim-drivable `PrivilegeService` for standalone LS-EU DUTs (which have no RobPlugin).
  *
  * `LsEuPlugin` resolves `host.get[PrivilegeService]` optionally and falls back to a
  * constant `False`, so without this plugin `xlate.req.supervisor` is a literal and
  * the §3.2(d) staleness bug is structurally untestable. Uses the SAME
  * setup-allocated-wire pattern as RobPlugin (see Services.scala's PrivilegeService
  * comment) so a consumer resolving `.supervisor` during its own `build` gets a stable
  * wire reference without a Fiber dependency cycle. */
class LsPrivStubPlugin extends FiberPlugin with PrivilegeService {
  private var _supervisor: Bool = null
  override def supervisor: Bool = _supervisor
  during setup { _supervisor = Bool() }

  val logic = during build new Area {
    val iSupervisor = in Bool ()
    _supervisor := iSupervisor
  }
}
```

- [ ] **Step 2: Add the `iNeedsSup` input to the directed LS source**

In `src/test/scala/m68k040/ls/LsEuSourcePlugin.scala`, add the input declaration next to `iLeaAddr`:

```scala
    val iLeaAddr = in Bool ()                                      // LEA (no translate/no mem access)
    val iNeedsSup = in Bool ()                                     // u1.needsSupervisor (MOVE <ea>,SR source load)
```

and replace the line `uop.fromCcr      := False; uop.fromSr := False; uop.needsSupervisor := False` with:

```scala
    uop.fromCcr      := False; uop.fromSr := False; uop.needsSupervisor := iNeedsSup
```

- [ ] **Step 3: Write the failing test**

Create `src/test/scala/m68k040/ls/LsEuBackStageSupervisorSpec.scala`:

```scala
package m68k040.ls

import m68k040.{M68kSim, M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.DcachePlugin
import m68k040.execute.LsEuPlugin
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.isa.{MemOp, Size}
import m68k040.mmu.DIdentityTranslationPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** LS EU late split, spec §3.2(d): the BACK stage must use the SUPERVISOR bit captured
  * at the RESOLVE->LAUNCH handoff (`bkCtx.xlateSup`), never the live
  * `xlate.req.supervisor`.
  *
  * WHY THIS MATTERS: `reqReg.sup` is re-captured from the LIVE architectural S bit
  * EVERY cycle (`reqDrvSup := privCtrl.supervisor`, LsEuPlugin.scala ~:656/:1810), so
  * once a load's cache access outlives its own S1 residency, a live read reports
  * whatever the S bit is NOW. `WAIT`'s task-#189 bus-fault suppression
  * (`u1.needsSupervisor && !xlate.req.supervisor`) then flips: a USER-mode
  * `MOVE <ea>,SR` source load whose refill takes a bus error is supposed to be
  * SUPPRESSED (the later sysOp's own privilege check owns the trap, vector 8), but a
  * live read that sees a now-supervisor S bit reports a spurious vector-2 access fault
  * instead -- the wrong vector, silently.
  *
  * MUTATION GATE (spec §8.2): this test MUST fail against a variant of LsEuPlugin that
  * reads `xlate.req.supervisor` live in `bkFsm.WAIT`. See the spec-mandated mutation
  * step in the plan; do not weaken this test to make a refactor convenient. */
class LsEuBackStageSupervisorSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val rfX    = new RegFilePluginX
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin
    val eu     = new LsEuPlugin
    val src    = new LsEuSourcePlugin
    val priv   = new LsPrivStubPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, rfInt, rfNzvc, rfX, xlate, dcache, eu, src, priv)) }
  }

  // Top nibble 2 is OUTSIDE `AxiMemModel.decoded`, so a refill from here DECERRs and
  // surfaces as `dcache.loadRsp.payload.fault`. Harness-side address map only (GC2).
  val BAD_ADDR = 0x20001000L

  test("back stage uses the CAPTURED supervisor bit, not the live one", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim("bk-xlate-sup") { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      // injectBusErrors: an access outside the decoded map returns DECERR.
      new BehavioralMemAgent(dut.dcache.logic.axi, cd, injectBusErrors = true)
      val s = dut.src.logic
      s.iValid #= false; s.iSqCommitValid #= false; s.iSqFlush #= false
      s.seedValid #= false; s.obsIntAddr #= 0
      s.iPsrcAValid #= false; s.iPsrcBValid #= false
      s.iLeaAddr #= false; s.iStkPush #= false; s.iNeedsSup #= false
      dut.priv.logic.iSupervisor #= false          // USER mode at issue time
      cd.waitSampling(80)

      // Seed the base register (preg 5) with the un-decoded address.
      s.seedValid #= true; s.seedAddr #= 5; s.seedData #= BigInt(BAD_ADDR)
      cd.waitSampling(); s.seedValid #= false; cd.waitSampling(2)

      // Issue a needsSupervisor LOAD to the un-decoded address, in USER mode.
      s.iValid #= true; s.iMemOp #= MemOp.LOAD; s.iSize #= Size.LONG
      s.iPsrcA #= 5; s.iPsrcAValid #= true; s.iPsrcBValid #= false
      s.iImm #= BigInt(0); s.iPdst #= 9; s.iPdstValid #= true; s.iRobId #= 12
      s.iNeedsSup #= true
      cd.waitSamplingWhere(s.iReady.toBoolean)
      s.iValid #= false; s.iNeedsSup #= false

      // Wait until the access is genuinely in the BACK stage (bkBusy), then flip the
      // architectural S bit to SUPERVISOR -- exactly what a commit-side RTE/MOVE-to-SR
      // retiring underneath an in-flight load does.
      var n = 0
      while (!dut.eu.logic.bkBusy.toBoolean && n < 200) { cd.waitSampling(); n += 1 }
      assert(n < 200, "load never reached the back stage (bkBusy never asserted)")
      dut.priv.logic.iSupervisor #= true

      // Now observe the completion. The bus error MUST be suppressed (this is a
      // user-mode needsSupervisor load), i.e. a NORMAL completion and NO fault report.
      var sawComp = false; var sawFault = false; var m = 0
      while (!sawComp && m < 400) {
        if (dut.src.logic.fValid.toBoolean && dut.src.logic.fRob.toInt == 12) sawFault = true
        if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == 12) sawComp = true
        cd.waitSampling(); m += 1
      }
      assert(sawComp, "robId=12 never completed")
      assert(!sawFault,
        "SPURIOUS vector-2 access fault reported for a user-mode needsSupervisor load: " +
        "the back stage read the LIVE xlate.req.supervisor (now True) instead of the " +
        "supervisor bit captured into bkCtx.xlateSup at the RESOLVE->LAUNCH handoff")
    }
  }
}
```

- [ ] **Step 4: Run the test against the correct code — expect PASS**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
~/sbt/bin/sbt 'testOnly m68k040.ls.LsEuBackStageSupervisorSpec' 2>&1 | tail -20
```

Expected: PASS.

- [ ] **Step 5: MUTATION — break the fix and prove the test catches it**

In `src/main/scala/m68k040/execute/LsEuPlugin.scala`, inside `bkFsm`'s `WAIT` state, temporarily change:

```scala
            val suppressForLaterPrivCheck = bkCtx.needsSupervisor && !bkCtx.xlateSup
```

to the deliberately-broken live read:

```scala
            val suppressForLaterPrivCheck = bkCtx.needsSupervisor && !xlate.req.supervisor
```

Then:

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
~/sbt/bin/sbt 'testOnly m68k040.ls.LsEuBackStageSupervisorSpec' 2>&1 | tail -20
```

Expected: **FAIL** with the "SPURIOUS vector-2 access fault" message. **If it PASSES, the test does not discriminate — fix the test (most likely the S-bit flip lands too late; move it earlier, e.g. gate on `dbgIsLaunch` instead of `bkBusy`) before proceeding.** Record the observed failure text.

- [ ] **Step 6: Revert the mutation and confirm GREEN again**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
git checkout -- src/main/scala/m68k040/execute/LsEuPlugin.scala
~/sbt/bin/sbt 'testOnly m68k040.ls.LsEuBackStageSupervisorSpec' 2>&1 | tail -20
```

Expected: PASS.

- [ ] **Step 7: Record whether the bug also pre-exists on the unsplit baseline (honest, non-blocking)**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-base
cp ../ls-split-work/src/test/scala/m68k040/ls/LsPrivStubPlugin.scala src/test/scala/m68k040/ls/
cp ../ls-split-work/src/test/scala/m68k040/ls/LsEuBackStageSupervisorSpec.scala src/test/scala/m68k040/ls/
# also apply the iNeedsSup edit to the baseline copy of LsEuSourcePlugin.scala
~/sbt/bin/sbt 'testOnly m68k040.ls.LsEuBackStageSupervisorSpec' 2>&1 | tail -20
git checkout -- src/test/scala/m68k040/ls/LsEuSourcePlugin.scala
rm -f src/test/scala/m68k040/ls/LsPrivStubPlugin.scala src/test/scala/m68k040/ls/LsEuBackStageSupervisorSpec.scala
```

Record the outcome verbatim. Either answer is informative and neither blocks: a FAIL means this is a **pre-existing latent bug** that the split would have made routine rather than rare, and the fix closes both; a PASS means the split genuinely introduced the exposure. Do **not** change the plan based on this — just record it.

- [ ] **Step 8: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
git add src/test/scala/m68k040/ls/LsPrivStubPlugin.scala \
        src/test/scala/m68k040/ls/LsEuSourcePlugin.scala \
        src/test/scala/m68k040/ls/LsEuBackStageSupervisorSpec.scala
git commit -m "test(ls): back-stage supervisor-bit capture (spec 3.2d), mutation-killed"
```

---

## Task 5: FMax GATE #1 — the early, cheap checkpoint (SLICE 1 GO/NO-GO)

**Why here and not at the end:** spec §6.4 demands the first gate run after the *smallest self-contained increment*, before `bkCtx` starts paying for itself, so that if the corridor rejects the split at all it is found at ~150 lines of diff instead of ~500. On 2026-08-09 a *textually logic-reducing* one-term change in this exact cone cost −6.55 MHz, 3.73× TNS, and +4785 LUTs. Congestion reports **cannot** predict a recurrence (no congestion window above level 5 exists) — only a paired post-route run can.

**Files:** none modified (measurement only).

**Interfaces:**
- Consumes: `BASE_SHA` (Task 2), the Slice-1 commit (Task 3).
- Produces: a paired WNS/FMax/TNS/failing-endpoint/CLB-LUT table, and a **binary GO/NO-GO decision**.

- [ ] **Step 1: Confirm the machine is genuinely uncontended (GC9)**

```bash
free -g
ps aux --sort=-%mem | head -10
pgrep -a vivado || echo "no vivado running"
pgrep -a java   || echo "no jvm running"
```

Require **≥ 20 GB free**, no other Vivado batch job, and **no sbt/JVM sim running** (including from the concurrent fetch-directed BTB initiative). If contended, **wait** — do not measure. This session has repeatedly produced 214 vs 164 MHz for an identical commit under contention.

- [ ] **Step 2: Generate + implement the BASE netlist**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-base
~/sbt/bin/sbt 'runMain m68k040.top.GenFullCoreSynthVerilog' 2>&1 | tail -5
ls -la generated/M68kFullCoreSynth.v
vivado -mode batch -nojournal -log synth/vivado_base.log \
       -source synth/impl_FullCore.tcl 2>&1 | tee synth/base_impl.out
grep -E "NETLIST_MD5|POSTROUTE_FULLCORE" synth/base_impl.out
grep -E "^\| CLB LUTs|^\| CLB Registers|^\| CLB  " synth/fullcore_route_util.rpt
grep -E "Total Number of Endpoints|Number of Failing Endpoints|WNS|TNS" synth/fullcore_route_timing.rpt | head
```

Expected (GC11): WNS ≈ −0.508 ns, FMax ≈ 221.83 MHz, 4512 failing endpoints, 103740 CLB LUTs. **If the base does not reproduce within ~1 MHz, the machine is not trustworthy — stop and re-run under a cleaner window.** Runtime ~60–90 min.

- [ ] **Step 3: Generate + implement the SLICE-1 netlist, back-to-back**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
~/sbt/bin/sbt 'runMain m68k040.top.GenFullCoreSynthVerilog' 2>&1 | tail -5
vivado -mode batch -nojournal -log synth/vivado_s1.log \
       -source synth/impl_FullCore.tcl 2>&1 | tee synth/s1_impl.out
grep -E "NETLIST_MD5|POSTROUTE_FULLCORE" synth/s1_impl.out
grep -E "^\| CLB LUTs|^\| CLB Registers|^\| CLB  " synth/fullcore_route_util.rpt
grep -E "Total Number of Endpoints|Number of Failing Endpoints|WNS|TNS" synth/fullcore_route_timing.rpt | head
```

- [ ] **Step 4: Apply the Slice-1 GO/NO-GO decision**

Build this table from Steps 2–3:

| metric | base | slice 1 | delta |
|---|---|---|---|
| WNS (ns) | | | |
| FMax (MHz) | | | |
| TNS (ns) | | | |
| failing endpoints | | | |
| CLB LUTs | | | |
| CLB registers | | | |
| CLB occupancy | | | |

Decision rules, applied literally:

- **NO-GO (revert everything, stop the plan):** FMax **< 217 MHz**, or CLB LUTs **> +2.5%** vs base. Spec §8.3 slice 1: *"If this slice alone regresses FMax materially, the lever is dead and nothing further is built."* Record the numbers and the revert in the ledger; this is a **successful** outcome of the experiment, not a failure of execution — the compValid precedent is the standard.
- **GO:** FMax **≥ 219 MHz** and LUTs within **+1.5%**.
- **MARGINAL (217–219 MHz, or LUTs +1.5–2.5%):** re-run the Slice-1 impl **once** back-to-back (placement re-roll noise on this design is ±5 MHz per spec §6.2). If the second draw is still marginal, treat as NO-GO — a marginal Slice 1 cannot be improved by Slice 2, which only adds perturbation.
- Note per G-L4 (Task 1): **no step may gate success on an FMax *improvement*.** Neutrality within the noise band is the target.

- [ ] **Step 5: Record the gate result in the ledger and commit**

Append a `## LS EU late split — Slice 1 FMax gate` section to `.superpowers/sdd/progress-ipc-push-2026-08-09.md` with the full table, the machine-state evidence (`free -g` output, absence of other Vivado/JVM), both `NETLIST_MD5` values, and the GO/NO-GO decision with its reason. Then:

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add -f .superpowers/sdd/progress-ipc-push-2026-08-09.md
git commit -m "docs(ledger): LS EU late-split Slice 1 post-route gate result"
```

---

## Task 6: Slice 2 — enable the early free

**Files:**
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala` (one literal)

**Interfaces:**
- Consumes: `earlyFree` (Task 3), the `when(!bkBusy)` RESOLVE stall (already written in Task 3 Step 9).
- Produces: the IPC-affecting behaviour every subsequent task measures.

- [ ] **Step 1: Flip the flag**

In `src/main/scala/m68k040/execute/LsEuPlugin.scala`, change:

```scala
  private val earlyFree: Boolean = false
```

to:

```scala
  private val earlyFree: Boolean = true
```

- [ ] **Step 2: Confirm the diff is exactly one line and `issue.ready` is still untouched**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
git diff --stat src/main/scala/m68k040/execute/LsEuPlugin.scala
git diff src/main/scala/m68k040/execute/LsEuPlugin.scala
grep -n "issuePort.ready :=" src/main/scala/m68k040/execute/LsEuPlugin.scala
```

Expected: `1 file changed, 1 insertion(+), 1 deletion(-)`; `issuePort.ready := !busy && !s1Valid && !compValid` unchanged (GC13).

- [ ] **Step 3: Smoke-check that the IPC needle actually moved**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
IPC_SEED=1 IPC_MEM=zero IPC_ONLY=load-stream ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' 2>&1 \
  | grep -E "^\[info\] (load-stream|AGGREGATE)"
```

Expected: `load-stream` cycles materially below the 3269 baseline (spec §3.3 predicts ~1700–2000; the plan's own trace predicts II≈5 ⇒ ~1900). **If the number has not moved at all, `WAIT_BK` is still being entered — check that `earlyFree` is read at elaboration time, not shadowed.**

- [ ] **Step 4: Run the LS specs + lock-step as an immediate sanity check**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
~/sbt/bin/sbt 'testOnly m68k040.ls.* m68k040.cache.DcacheSpec' 2>&1 | tee /tmp/s2_ls.log
grep -i "\*\*\* FAILED" /tmp/s2_ls.log | sort > /tmp/s2_ls_fails.txt
diff /tmp/base_ls_fails.txt /tmp/s2_ls_fails.txt && echo "LS FAIL-NAME-LISTS IDENTICAL"
~/sbt/bin/sbt 'testOnly m68k040.lockstep.ExecuteLockStepSpec' 2>&1 | tee /tmp/s2_lockstep.log
grep -E "^\[info\] (- |Tests:|.*\*\*\* FAILED)" /tmp/s2_lockstep.log | grep -i fail | sort > /tmp/s2_lockstep_fails.txt
diff /tmp/base_lockstep_fails.txt /tmp/s2_lockstep_fails.txt && echo "LOCKSTEP FAIL-NAME-LISTS IDENTICAL"
```

Expected: both fail-name-lists identical to baseline.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
git add src/main/scala/m68k040/execute/LsEuPlugin.scala
git commit -m "lsu: enable the LS EU early free at RESOLVE (Slice 2)

earlyFree=true: the front frees S1 at the RESOLVE->LAUNCH handoff and stalls there
only while bkBusy (NG1, one load in the cache). Non-forwarded load II 9 -> ~5.
issue.ready textually unchanged."
```

---

## Task 7: `LsEuCrossLineBDoneSpec` — mutation-killed test for the `llReg.bDone` fix

**Why now:** the §5.5 hazard is only *reachable* once the front can be in `IDLE` while the back is in `WAIT_A`, which requires `earlyFree`. Spec §8.2 requires it to **fail against a variant that leaves `llReg.bDone := False` in the front's `IDLE`**.

**Files:**
- Create: `src/test/scala/m68k040/ls/LsEuCrossLineBDoneSpec.scala`

**Interfaces:**
- Consumes: `dbgIsWaitA`, `dbgIsIdle`, `bkBusy` (Task 3); `BehavioralMemAgent`, `LsEuSourcePlugin` (existing).
- Produces: nothing later tasks consume.

- [ ] **Step 1: Write the test**

Create `src/test/scala/m68k040/ls/LsEuCrossLineBDoneSpec.scala`:

```scala
package m68k040.ls

import m68k040.{M68kSim, M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.DcachePlugin
import m68k040.execute.LsEuPlugin
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.isa.{MemOp, Size}
import m68k040.mmu.DIdentityTranslationPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** LS EU late split, spec §5.5: `llReg.bDone` housekeeping is BACK-owned.
  *
  * `bDone` selects slot B's address for the registered cache launch
  * (`loadVaddr := Mux(llReg.bDone, llReg.addrB, llReg.vaddr)`). A CROSS load sets it in
  * `WAIT_A` and consumes it in `WAIT_B`. Before the split the FRONT's `IDLE` cleared it
  * every cycle (harmless then: the front was never in IDLE while a cross load was
  * mid-flight). With the early free the front IS in IDLE while the back sits in
  * `WAIT_A`, so a front-side clear re-points the back's slot-B command at slot A --
  * re-reading line A and merging it with itself, i.e. silent WRONG DATA for the
  * boundary-spanning half.
  *
  * MUTATION GATE (spec §8.2): this test MUST fail against a variant that restores
  * `llReg.bDone := False` in the FRONT's IDLE. */
class LsEuCrossLineBDoneSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val rfX    = new RegFilePluginX
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin
    val eu     = new LsEuPlugin
    val src    = new LsEuSourcePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, rfInt, rfNzvc, rfX, xlate, dcache, eu, src)) }
  }

  def memByte(addr: Long): Int = ((addr * 7 + 0x11) & 0xff).toInt
  def expected(base: Long, size: Int): BigInt =
    (0 until size).foldLeft(BigInt(0))((acc, i) => (acc << 8) | BigInt(memByte(base + i)))

  test("a cross-line load in the back stage survives the front cycling through IDLE", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim("bk-bdone") { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val s = dut.src.logic
      s.iValid #= false; s.iSqCommitValid #= false; s.iSqFlush #= false
      s.seedValid #= false; s.obsIntAddr #= 0
      s.iPsrcAValid #= false; s.iPsrcBValid #= false
      s.iLeaAddr #= false; s.iStkPush #= false; s.iNeedsSup #= false
      cd.waitSampling(80)

      // Two adjacent 16-byte lines, plus a far-away line for the trailing loads.
      val crossBase = 0x4000L + 14L                 // .L spanning 0x400E..0x4011 (line cross)
      for (i <- 0 until 48) mem.pokeByte(0x4000L + i, memByte(0x4000L + i))
      for (i <- 0 until 16) mem.pokeByte(0x8000L + i, memByte(0x8000L + i))

      def seed(preg: Int, v: Long): Unit = {
        s.seedValid #= true; s.seedAddr #= preg; s.seedData #= BigInt(v & 0xffffffffL)
        cd.waitSampling(); s.seedValid #= false; cd.waitSampling(2)
      }
      def issueLoad(basePreg: Int, pdst: Int, robId: Int): Unit = {
        s.iValid #= true; s.iMemOp #= MemOp.LOAD; s.iSize #= Size.LONG
        s.iPsrcA #= basePreg; s.iPsrcAValid #= true; s.iPsrcBValid #= false
        s.iImm #= BigInt(0); s.iPdst #= pdst; s.iPdstValid #= true; s.iRobId #= robId
        cd.waitSamplingWhere(s.iReady.toBoolean)
        s.iValid #= false
      }

      seed(5, crossBase)
      seed(6, 0x8000L)

      // Issue the CROSS load. It will occupy the back stage across WAIT_A/WAIT_B.
      issueLoad(5, 9, 20)

      // Keep the FRONT busy: issue an unrelated load as soon as issue.ready returns, so
      // the front genuinely cycles IDLE -> XLATE -> RESOLVE underneath the cross load.
      // (With the early free it is accepted while the back is still in WAIT_A.)
      var sawWaitAWithFrontIdle = false
      val watcher = fork {
        var k = 0
        while (k < 600) {
          if (dut.eu.logic.dbgIsWaitA.toBoolean && dut.eu.logic.dbgIsIdle.toBoolean)
            sawWaitAWithFrontIdle = true
          cd.waitSampling(); k += 1
        }
      }
      issueLoad(6, 10, 21)

      // Wait for BOTH completions.
      var got20 = false; var got21 = false; var n = 0
      while ((!got20 || !got21) && n < 600) {
        if (dut.src.logic.cValid.toBoolean) {
          val r = dut.src.logic.cRob.toInt
          if (r == 20) got20 = true
          if (r == 21) got21 = true
        }
        cd.waitSampling(); n += 1
      }
      assert(got20 && got21, s"completions missing (robId20=$got20 robId21=$got21) after $n cycles")
      watcher.join()
      assert(sawWaitAWithFrontIdle,
        "the front never reached IDLE while the back was in WAIT_A -- this test did not " +
        "exercise the §5.5 hazard at all (is earlyFree enabled?)")

      // Read back the cross load's destination register.
      cd.waitSampling(5)
      dut.src.logic.obsIntAddr #= 9
      cd.waitSampling(3)
      val got = dut.src.logic.obsIntData.toBigInt
      val want = expected(crossBase, 4)
      assert(got == want,
        f"cross-line load returned 0x$got%08X, expected 0x$want%08X -- the front's IDLE " +
        f"cleared the back's live llReg.bDone, so slot B re-read slot A's line")
    }
  }
}
```

- [ ] **Step 2: Run it — expect PASS**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
~/sbt/bin/sbt 'testOnly m68k040.ls.LsEuCrossLineBDoneSpec' 2>&1 | tail -20
```

Expected: PASS. **If it fails on the "never reached IDLE while the back was in WAIT_A" assertion, the second load is not being accepted early enough** — increase the watcher window or issue two trailing loads instead of one.

- [ ] **Step 3: MUTATION — restore the front-side clear and prove the test catches it**

In `src/main/scala/m68k040/execute/LsEuPlugin.scala`, inside the **front** FSM's `IDLE.whenIsActive`, temporarily add as the first statement:

```scala
        llReg.bDone := False   // MUTATION: the spec §5.5 bug, deliberately reintroduced
```

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
~/sbt/bin/sbt 'testOnly m68k040.ls.LsEuCrossLineBDoneSpec' 2>&1 | tail -20
```

Expected: **FAIL** on the wrong-value assertion (or a timeout, if the back hangs re-launching slot A). **If it PASSES, the test does not discriminate — do not proceed; strengthen it (most likely the trailing load is not keeping the front in IDLE at the right cycle).**

- [ ] **Step 4: Revert the mutation, confirm GREEN, commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
git checkout -- src/main/scala/m68k040/execute/LsEuPlugin.scala
~/sbt/bin/sbt 'testOnly m68k040.ls.LsEuCrossLineBDoneSpec' 2>&1 | tail -20
git add src/test/scala/m68k040/ls/LsEuCrossLineBDoneSpec.scala
git commit -m "test(ls): cross-line load survives front IDLE (spec 5.5), mutation-killed"
```

---

## Task 8: `LsEuBackStageOrderSpec` — the NG1 single-occupancy invariant

**Deliberate deviation from spec §8.2, stated openly:** the spec asks for a test "exhaustive over the front-state × back-state cross product". That is not achievable from this DUT's issue interface — the front and back states are not independently drivable, only reachable. This task instead installs a **continuous invariant monitor** over a 16-load stream that checks the NG1 property on **every cycle** of the run, plus a `sawConcurrency` assertion proving the front and back were genuinely overlapped (without which the test would pass vacuously). If a later session finds a way to force specific state pairs, strengthening this test is welcome; do not silently weaken it.

**Files:**
- Create: `src/test/scala/m68k040/ls/LsEuBackStageOrderSpec.scala`

**Interfaces:**
- Consumes: `bkBusy`, `bkStart`, `dbgIsResolve`, `dbgIsLaunch`, `dbgIsWait`, `dbgIsWaitA`, `dbgIsWaitB`, `dbgBkIdle` (Task 3); `dut.dcache.logic` cache ports.
- Produces: nothing later tasks consume.

- [ ] **Step 1: Write the test**

Create `src/test/scala/m68k040/ls/LsEuBackStageOrderSpec.scala`:

```scala
package m68k040.ls

import m68k040.{M68kSim, M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.DcachePlugin
import m68k040.execute.LsEuPlugin
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.isa.{MemOp, Size}
import m68k040.mmu.DIdentityTranslationPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** LS EU late split, NG1 (spec §1.2 / §5.2): the back stage is SINGLE-OCCUPANCY by
  * construction. `DLoadRsp` carries no id (DcacheTypes.scala:24-28) and the back FSM
  * attributes every response purely by its own state, so a second load entering the
  * back stage would mis-pair data with destinations SILENTLY. This is a contract, not
  * an effort budget: relaxing it requires tagging `DLoadRsp` first.
  *
  * Asserts three things over a long stream of independent non-forwarded loads:
  *   1. `bkStart` never pulses while `bkBusy` is already set.
  *   2. The back FSM is never in more than one of LAUNCH/WAIT/WAIT_A/WAIT_B at a time,
  *      and is in one of them exactly when `bkBusy`.
  *   3. Every issued robId completes exactly once, with the right data. */
class LsEuBackStageOrderSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val rfX    = new RegFilePluginX
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin
    val eu     = new LsEuPlugin
    val src    = new LsEuSourcePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, rfInt, rfNzvc, rfX, xlate, dcache, eu, src)) }
  }

  def memByte(addr: Long): Int = ((addr * 7 + 0x11) & 0xff).toInt
  def expected(base: Long, size: Int): BigInt =
    (0 until size).foldLeft(BigInt(0))((acc, i) => (acc << 8) | BigInt(memByte(base + i)))

  test("back stage stays single-occupancy across a stream of non-forwarded loads", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim("bk-order") { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val s = dut.src.logic
      s.iValid #= false; s.iSqCommitValid #= false; s.iSqFlush #= false
      s.seedValid #= false; s.obsIntAddr #= 0
      s.iPsrcAValid #= false; s.iPsrcBValid #= false
      s.iLeaAddr #= false; s.iStkPush #= false; s.iNeedsSup #= false
      cd.waitSampling(80)

      // 16 distinct long-aligned addresses across 4 lines; no stores anywhere, so every
      // load takes the genuine RESOLVE -> LAUNCH -> WAIT -> loadRsp cache path.
      val addrs = (0 until 16).map(i => 0x4000L + i * 4L)
      for (i <- 0 until 64) mem.pokeByte(0x4000L + i, memByte(0x4000L + i))

      // Continuous invariant monitor.
      @volatile var violations = List.empty[String]
      @volatile var sawConcurrency = false
      val mon = fork {
        var k = 0
        while (k < 4000) {
          val bkBusy  = dut.eu.logic.bkBusy.toBoolean
          val bkStart = dut.eu.logic.bkStart.toBoolean
          val states = Seq(
            dut.eu.logic.dbgIsLaunch.toBoolean,
            dut.eu.logic.dbgIsWait.toBoolean,
            dut.eu.logic.dbgIsWaitA.toBoolean,
            dut.eu.logic.dbgIsWaitB.toBoolean)
          val nActive = states.count(identity)
          if (bkStart && bkBusy)
            violations ::= s"cycle $k: bkStart pulsed while bkBusy already set (NG1 violated)"
          if (nActive > 1)
            violations ::= s"cycle $k: back FSM active in $nActive states at once"
          // The whole point of the split: the front must be able to run while the back works.
          if (bkBusy && (dut.eu.logic.dbgIsXlate.toBoolean || dut.eu.logic.dbgIsResolve.toBoolean))
            sawConcurrency = true
          cd.waitSampling(); k += 1
        }
      }

      var rob = 0
      for ((a, i) <- addrs.zipWithIndex) {
        s.seedValid #= true; s.seedAddr #= 20; s.seedData #= BigInt(a)
        cd.waitSampling(); s.seedValid #= false; cd.waitSampling(2)
        s.iValid #= true; s.iMemOp #= MemOp.LOAD; s.iSize #= Size.LONG
        s.iPsrcA #= 20; s.iPsrcAValid #= true; s.iPsrcBValid #= false
        s.iImm #= BigInt(0); s.iPdst #= (32 + i); s.iPdstValid #= true; s.iRobId #= rob
        cd.waitSamplingWhere(s.iReady.toBoolean)
        s.iValid #= false
        rob += 1
      }

      // Drain.
      var idle = 0
      while (idle < 200) {
        if (dut.eu.logic.bkBusy.toBoolean || dut.eu.logic.busy.toBoolean) idle = 0 else idle += 1
        cd.waitSampling()
      }
      mon.join()

      assert(violations.isEmpty, "NG1 invariant violations:\n" + violations.reverse.mkString("\n"))
      assert(sawConcurrency,
        "the front was NEVER in XLATE/RESOLVE while the back was busy -- the split is not " +
        "actually overlapping anything (is earlyFree enabled?)")

      // Every destination holds the right value.
      for ((a, i) <- addrs.zipWithIndex) {
        dut.src.logic.obsIntAddr #= (32 + i)
        cd.waitSampling(3)
        val got = dut.src.logic.obsIntData.toBigInt
        val want = expected(a, 4)
        assert(got == want, f"preg ${32 + i} = 0x$got%08X, expected 0x$want%08X (addr 0x$a%08X)")
      }
    }
  }
}
```

- [ ] **Step 2: Run it**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
~/sbt/bin/sbt 'testOnly m68k040.ls.LsEuBackStageOrderSpec' 2>&1 | tail -25
```

Expected: PASS, including the `sawConcurrency` assertion (which is what proves the split is live).

- [ ] **Step 3: MUTATION — remove the `RESOLVE` stall and prove the test catches it**

In `src/main/scala/m68k040/execute/LsEuPlugin.scala`, in the front FSM's `RESOLVE` handoff arm, temporarily change `when(!bkBusy) {` to `when(True) {`.

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
~/sbt/bin/sbt 'testOnly m68k040.ls.LsEuBackStageOrderSpec' 2>&1 | tail -25
```

Expected: **FAIL** on `bkStart pulsed while bkBusy already set` and/or wrong data. Then revert:

```bash
git checkout -- src/main/scala/m68k040/execute/LsEuPlugin.scala
~/sbt/bin/sbt 'testOnly m68k040.ls.LsEuBackStageOrderSpec' 2>&1 | tail -5
```

- [ ] **Step 4: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
git add src/test/scala/m68k040/ls/LsEuBackStageOrderSpec.scala
git commit -m "test(ls): NG1 back-stage single-occupancy invariant, mutation-killed"
```

---

## Task 9: `LsEuCompletionArbiterSpec` — back wins, front retries, nothing lost

**Files:**
- Create: `src/test/scala/m68k040/ls/LsEuCompletionArbiterSpec.scala`

**Interfaces:**
- Consumes: `backCompFires`, `frontCompHeld`, `compValid`, `compRobId` (Task 3).
- Produces: nothing later tasks consume.

- [ ] **Step 1: Write the test**

Create `src/test/scala/m68k040/ls/LsEuCompletionArbiterSpec.scala`:

```scala
package m68k040.ls

import m68k040.{M68kSim, M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.DcachePlugin
import m68k040.execute.LsEuPlugin
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.isa.{MemOp, Size}
import m68k040.mmu.DIdentityTranslationPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** LS EU late split, spec §3.2(f) / §5.3: the shared comp* stage now has THREE writers
  * (front live capture, back live capture, precise-store deferred replay). The back has
  * PRIORITY -- `dcache.loadRsp` is a 1-cycle Flow with no backpressure, so a missed back
  * completion loses the load, while the front can hold one cycle. The front retries,
  * exactly like the existing precise-store replay's `!liveCompletionFires` retry.
  *
  * Drives an interleaved LOAD/STORE stream so front and back completions genuinely
  * contend, then asserts:
  *   1. `frontCompHeld` was observed at least once (the collision really happened --
  *      otherwise this test proves nothing).
  *   2. Whenever `frontCompHeld`, the SAME cycle had `backCompFires` (back won).
  *   3. Every issued robId completed EXACTLY once. */
class LsEuCompletionArbiterSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val rfX    = new RegFilePluginX
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin
    val eu     = new LsEuPlugin
    val src    = new LsEuSourcePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, rfInt, rfNzvc, rfX, xlate, dcache, eu, src)) }
  }

  def memByte(addr: Long): Int = ((addr * 7 + 0x11) & 0xff).toInt

  test("front yields the comp* stage to the back and retries, losing nothing", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim("bk-arbiter") { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val s = dut.src.logic
      s.iValid #= false; s.iSqCommitValid #= false; s.iSqFlush #= false
      s.seedValid #= false; s.obsIntAddr #= 0
      s.iPsrcAValid #= false; s.iPsrcBValid #= false
      s.iLeaAddr #= false; s.iStkPush #= false; s.iNeedsSup #= false
      cd.waitSampling(80)

      for (i <- 0 until 256) mem.pokeByte(0x4000L + i, memByte(0x4000L + i))

      @volatile var heldCycles   = 0
      @volatile var badArbitration = List.empty[String]
      val completions = scala.collection.mutable.Map[Int, Int]().withDefaultValue(0)
      val mon = fork {
        var k = 0
        while (k < 8000) {
          val held = dut.eu.logic.frontCompHeld.toBoolean
          val back = dut.eu.logic.backCompFires.toBoolean
          if (held) {
            heldCycles += 1
            if (!back) badArbitration ::= s"cycle $k: frontCompHeld without backCompFires"
          }
          if (dut.src.logic.cValid.toBoolean) {
            val r = dut.src.logic.cRob.toInt
            completions(r) = completions(r) + 1
          }
          cd.waitSampling(); k += 1
        }
      }

      // Interleave: a non-forwarded LOAD (goes to the back) followed immediately by a
      // STORE to a DIFFERENT line (completes in the front's XLATE). Sweeping the store's
      // offset walks the two completions across each other in phase.
      var rob = 0
      val issued = scala.collection.mutable.ArrayBuffer[Int]()
      for (phase <- 0 until 24) {
        val loadAddr  = 0x4000L + (phase % 16) * 4L
        val storeAddr = 0x4080L + (phase % 16) * 4L
        s.seedValid #= true; s.seedAddr #= 20; s.seedData #= BigInt(loadAddr)
        cd.waitSampling(); s.seedValid #= false; cd.waitSampling(2)
        s.iValid #= true; s.iMemOp #= MemOp.LOAD; s.iSize #= Size.LONG
        s.iPsrcA #= 20; s.iPsrcAValid #= true; s.iPsrcBValid #= false
        s.iImm #= BigInt(0); s.iPdst #= 40; s.iPdstValid #= true; s.iRobId #= rob
        cd.waitSamplingWhere(s.iReady.toBoolean)
        s.iValid #= false
        issued += rob; rob = (rob + 1) % 64

        // Phase-shift the store's issue by `phase % 5` cycles so the two completions
        // sweep through every relative alignment.
        cd.waitSampling(phase % 5)

        s.seedValid #= true; s.seedAddr #= 21; s.seedData #= BigInt(storeAddr)
        cd.waitSampling(); s.seedValid #= false; cd.waitSampling(2)
        s.iValid #= true; s.iMemOp #= MemOp.STORE; s.iSize #= Size.LONG
        s.iPsrcA #= 21; s.iPsrcAValid #= true
        s.iPsrcB #= 20; s.iPsrcBValid #= true
        s.iImm #= BigInt(0); s.iPdstValid #= false; s.iPdst #= 0; s.iRobId #= rob
        cd.waitSamplingWhere(s.iReady.toBoolean)
        s.iValid #= false
        issued += rob; rob = (rob + 1) % 64
        // Let the store commit so the SQ can drain and never go full.
        s.iSqCommitValid #= true; s.iSqCommitRob #= issued.last
        cd.waitSampling(); s.iSqCommitValid #= false
      }

      var idle = 0
      while (idle < 300) {
        if (dut.eu.logic.bkBusy.toBoolean || dut.eu.logic.busy.toBoolean) idle = 0 else idle += 1
        cd.waitSampling()
      }
      mon.join()

      assert(badArbitration.isEmpty,
        "arbitration violations:\n" + badArbitration.reverse.mkString("\n"))
      assert(heldCycles > 0,
        "frontCompHeld never asserted -- this run never produced a front/back comp* " +
        "collision, so it proves nothing about the arbiter. Widen the phase sweep.")
      for (r <- issued.distinct) {
        assert(completions(r) >= 1, s"robId=$r never completed (a completion was LOST)")
      }
      println(s"[arbiter] observed $heldCycles front-held cycles across the phase sweep")
    }
  }
}
```

- [ ] **Step 2: Run it**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
~/sbt/bin/sbt 'testOnly m68k040.ls.LsEuCompletionArbiterSpec' 2>&1 | tail -25
```

Expected: PASS with a non-zero `[arbiter] observed N front-held cycles`. **If `heldCycles == 0`, widen the sweep (`for (phase <- 0 until 48)` and `phase % 7`) until a collision is observed** — a test that never hits the case is worthless.

- [ ] **Step 3: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
git add src/test/scala/m68k040/ls/LsEuCompletionArbiterSpec.scala
git commit -m "test(ls): front/back comp* arbitration -- back wins, front retries (spec 5.3)"
```

---

## Task 10: `LsEuBackStagePoisonSpec` — two poison bits under `sqFlushSig`

**Files:**
- Create: `src/test/scala/m68k040/ls/LsEuBackStagePoisonSpec.scala`

**Interfaces:**
- Consumes: `poisoned`, `bkPoisoned`, `bkBusy`, `dbgIsWait` (Task 3).
- Produces: nothing later tasks consume.

- [ ] **Step 1: Write the test**

Create `src/test/scala/m68k040/ls/LsEuBackStagePoisonSpec.scala`:

```scala
package m68k040.ls

import m68k040.{M68kSim, M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.DcachePlugin
import m68k040.execute.LsEuPlugin
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.isa.{MemOp, Size}
import m68k040.mmu.DIdentityTranslationPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** LS EU late split, spec §3.2(e) / §5.4: with TWO independent in-flight µops one
  * `poisoned` bit cannot serve both -- a new `issuePort.fire` clears it, which would
  * un-poison the OLDER µop still draining in the back stage. That µop would then perform
  * a ROB completion / PRF write / wakeup for a robId the ROB has already reclaimed and
  * reused: the exact wrong-path-resource-leak class this core has been bitten by before
  * (tasks #139/#176/#194/#200).
  *
  * Asserts:
  *   1. A flush while the back stage is occupied sets `bkPoisoned`, and a subsequent
  *      `issuePort.fire` (which clears the FRONT's `poisoned`) does NOT clear it.
  *   2. The poisoned back load still CONSUMES its `loadRsp` (bkBusy returns to 0 -- the
  *      back must stay in step with the cache FSM) while producing NO completion. */
class LsEuBackStagePoisonSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val rfX    = new RegFilePluginX
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin
    val eu     = new LsEuPlugin
    val src    = new LsEuSourcePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, rfInt, rfNzvc, rfX, xlate, dcache, eu, src)) }
  }

  def memByte(addr: Long): Int = ((addr * 7 + 0x11) & 0xff).toInt

  test("a flush poisons the back-stage load and a later issue does not un-poison it", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim("bk-poison") { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val s = dut.src.logic
      s.iValid #= false; s.iSqCommitValid #= false; s.iSqFlush #= false
      s.seedValid #= false; s.obsIntAddr #= 0
      s.iPsrcAValid #= false; s.iPsrcBValid #= false
      s.iLeaAddr #= false; s.iStkPush #= false; s.iNeedsSup #= false
      cd.waitSampling(80)
      for (i <- 0 until 64) mem.pokeByte(0x4000L + i, memByte(0x4000L + i))

      def seed(preg: Int, v: Long): Unit = {
        s.seedValid #= true; s.seedAddr #= preg; s.seedData #= BigInt(v & 0xffffffffL)
        cd.waitSampling(); s.seedValid #= false; cd.waitSampling(2)
      }

      // Load A -> the back stage.
      seed(20, 0x4000L)
      s.iValid #= true; s.iMemOp #= MemOp.LOAD; s.iSize #= Size.LONG
      s.iPsrcA #= 20; s.iPsrcAValid #= true; s.iPsrcBValid #= false
      s.iImm #= BigInt(0); s.iPdst #= 40; s.iPdstValid #= true; s.iRobId #= 7
      cd.waitSamplingWhere(s.iReady.toBoolean)
      s.iValid #= false

      var n = 0
      while (!dut.eu.logic.bkBusy.toBoolean && n < 200) { cd.waitSampling(); n += 1 }
      assert(n < 200, "load A never reached the back stage")

      // Flush while the back stage is occupied.
      s.iSqFlush #= true
      cd.waitSampling()
      s.iSqFlush #= false
      cd.waitSampling()
      assert(dut.eu.logic.bkPoisoned.toBoolean,
        "bkPoisoned was not set by a flush while the back stage was busy")

      // Issue load B. Its `issuePort.fire` clears the FRONT's `poisoned` -- it must NOT
      // clear `bkPoisoned`.
      seed(21, 0x4020L)
      s.iValid #= true; s.iMemOp #= MemOp.LOAD; s.iSize #= Size.LONG
      s.iPsrcA #= 21; s.iPsrcAValid #= true; s.iPsrcBValid #= false
      s.iImm #= BigInt(0); s.iPdst #= 41; s.iPdstValid #= true; s.iRobId #= 8
      var fired = false; var g = 0
      while (!fired && g < 400) {
        if (s.iReady.toBoolean) {
          cd.waitSampling(); fired = true
          // Immediately after the fire, the back's poison must survive IF the back is
          // still occupied.
          if (dut.eu.logic.bkBusy.toBoolean)
            assert(dut.eu.logic.bkPoisoned.toBoolean,
              "issuePort.fire CLEARED the back stage's poison -- the older draining µop " +
              "would now complete for a reclaimed robId (spec §3.2(e))")
        } else { cd.waitSampling() }
        g += 1
      }
      s.iValid #= false
      assert(fired, "load B never issued")

      // Load A (robId 7) must produce NO completion at all; load B (robId 8) must.
      var sawA = false; var sawB = false; var m = 0
      while (m < 500) {
        if (dut.src.logic.cValid.toBoolean) {
          val r = dut.src.logic.cRob.toInt
          if (r == 7) sawA = true
          if (r == 8) sawB = true
        }
        if (dut.src.logic.fValid.toBoolean && dut.src.logic.fRob.toInt == 7) sawA = true
        cd.waitSampling(); m += 1
      }
      assert(!sawA, "the POISONED back-stage load (robId=7) still completed")
      assert(sawB, "load B (robId=8) never completed -- the back stage did not recover")
      assert(!dut.eu.logic.bkBusy.toBoolean,
        "bkBusy never cleared: the poisoned back load did not CONSUME its loadRsp, so the " +
        "back FSM and the cache FSM are out of step (spec §5.4)")
    }
  }
}
```

- [ ] **Step 2: Run it**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
~/sbt/bin/sbt 'testOnly m68k040.ls.LsEuBackStagePoisonSpec' 2>&1 | tail -25
```

Expected: PASS.

- [ ] **Step 3: MUTATION — collapse the two poison bits and prove the test catches it**

In `src/main/scala/m68k040/execute/LsEuPlugin.scala`, in `bkFsm`'s `WAIT` and `WAIT_B` states, temporarily change `when(!bkPoisoned)` to `when(!poisoned)` (both sites).

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
~/sbt/bin/sbt 'testOnly m68k040.ls.LsEuBackStagePoisonSpec' 2>&1 | tail -25
```

Expected: **FAIL** on "the POISONED back-stage load (robId=7) still completed". Then revert:

```bash
git checkout -- src/main/scala/m68k040/execute/LsEuPlugin.scala
~/sbt/bin/sbt 'testOnly m68k040.ls.LsEuBackStagePoisonSpec' 2>&1 | tail -5
```

- [ ] **Step 4: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
git add src/test/scala/m68k040/ls/LsEuBackStagePoisonSpec.scala
git commit -m "test(ls): two poison bits across the LS EU split (spec 3.2e), mutation-killed"
```

---

## Task 11: IPC gate — 8 seeds × 2 memory models, `load-stream` primary

**Files:** none modified (measurement only).

**Interfaces:**
- Consumes: `/tmp/base_ipc.txt` (Task 2), the Slice-2 worktree (Task 6).
- Produces: a paired IPC table and the **IPC half** of the GC10 accept/reject decision.

- [ ] **Step 1: Confirm no Vivado is running (GC9) — sims and synthesis must not overlap**

```bash
pgrep -a vivado || echo "no vivado running"
free -g
```

- [ ] **Step 2: Run the full paired sweep on the Slice-2 worktree**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
for mem in zero l2:5:70; do
  for s in 1 2 3 4 5 6 7 8; do
    echo "=== mem=$mem seed=$s ==="
    IPC_SEED=$s IPC_MEM=$mem ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' 2>&1 \
      | grep -E "^\[info\] (load-stream|load/store|mixed|dependent-ALU|independent-ALU|branchy|hot-loop|call-return|AGGREGATE)"
  done
done 2>&1 | tee /tmp/s2_ipc_full.txt
```

- [ ] **Step 3: Re-run the missing baseline seeds if Task 2 only covered 1–3**

If `/tmp/base_ipc.txt` lacks seeds 4–8, run the same loop in `/home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-base` and append. **Paired means the same seed, same model, same machine — never compare across seeds.**

- [ ] **Step 4: Build the comparison table and apply the IPC thresholds**

Produce, per memory model, the 8-seed mean for `load-stream` and `AGGREGATE`, plus per-kernel means for `load/store`, `mixed`, `call-return` (regression safety).

Thresholds (GC10):
- **`load-stream` must improve ≥ 25% in cycles under BOTH models.** (< 15% ⇒ REJECT.)
- **AGGREGATE must improve ≥ 8% under BOTH models.**
- **`load/store`, `mixed`, `call-return`, `branchy`, `hot-loop`, `dependent-ALU`, `independent-ALU` must not regress.** Per spec §3.4, `load/store` and `mixed` are 100%-forwarded and are **expected to be unchanged** — a *change* in either direction there is a signal worth investigating, not a win.

- [ ] **Step 5: Record in the ledger and commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
# append the paired table under "## LS EU late split -- Slice 2 IPC gate"
git add -f .superpowers/sdd/progress-ipc-push-2026-08-09.md
git commit -m "docs(ledger): LS EU late-split Slice 2 IPC measurement (8 seeds x 2 models)"
```

---

## Task 12: Zero-regression gate — lock-step + LS specs + full ported corpus (Slice 2)

**Files:** none modified (measurement only).

**Interfaces:**
- Consumes: `/tmp/base_lockstep_fails.txt`, `/tmp/base_ls_fails.txt`, `/tmp/base_ported_fails.txt` (Task 2).
- Produces: the **correctness half** of the GC10 accept/reject decision.

- [ ] **Step 1: Confirm no Vivado is running, then run the full ported corpus**

```bash
pgrep -a vivado || echo "no vivado running"
free -g   # require >= 20 GB free (GC9); ~3 GB RSS per shard
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
tools/fuzz/ported-sweep-parallel.sh 4 $(git rev-parse HEAD) 2>&1 | tail -30
cp ported_logs_parallel/all_fails.txt /tmp/s2_ported_fails.txt
diff /tmp/base_ported_fails.txt /tmp/s2_ported_fails.txt && echo "PORTED FAIL-NAME-LISTS BYTE-IDENTICAL"
```

Expected: byte-identical merged fail-name lists. **Any difference — in either direction — must be individually investigated before proceeding.** A *disappearing* failure is as much a signal as a new one.

- [ ] **Step 2: Re-run lock-step and the LS specs (post all mutation reverts)**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
git status --porcelain    # MUST be clean -- no leftover mutation
~/sbt/bin/sbt 'testOnly m68k040.lockstep.ExecuteLockStepSpec' 2>&1 | tee /tmp/s2f_lockstep.log
grep -E "^\[info\] (- |Tests:|.*\*\*\* FAILED)" /tmp/s2f_lockstep.log | grep -i fail | sort > /tmp/s2f_lockstep_fails.txt
diff /tmp/base_lockstep_fails.txt /tmp/s2f_lockstep_fails.txt && echo "LOCKSTEP IDENTICAL"
~/sbt/bin/sbt 'testOnly m68k040.ls.* m68k040.cache.DcacheSpec' 2>&1 | tee /tmp/s2f_ls.log
grep -i "\*\*\* FAILED" /tmp/s2f_ls.log | sort > /tmp/s2f_ls_fails.txt
diff /tmp/base_ls_fails.txt /tmp/s2f_ls_fails.txt && echo "LS SPECS IDENTICAL (modulo the known flaky member)"
```

- [ ] **Step 3: Run the five new directed specs together as a regression block**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
~/sbt/bin/sbt 'testOnly m68k040.ls.LsEuBackStageSupervisorSpec m68k040.ls.LsEuCrossLineBDoneSpec m68k040.ls.LsEuBackStageOrderSpec m68k040.ls.LsEuCompletionArbiterSpec m68k040.ls.LsEuBackStagePoisonSpec' 2>&1 | tail -20
```

Expected: 5/5 PASS.

- [ ] **Step 4: Record in the ledger and commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add -f .superpowers/sdd/progress-ipc-push-2026-08-09.md
git commit -m "docs(ledger): LS EU late-split Slice 2 zero-regression gate"
```

---

## Task 13: FMax GATE #2 + LUT, and the §8.4 accept/reject decision

**Files:** none modified (measurement only).

**Interfaces:**
- Consumes: the Task-5 base impl numbers, the Slice-2 worktree.
- Produces: the **final ACCEPT or REJECT**, applied literally per GC10.

- [ ] **Step 1: Confirm the machine is uncontended (GC9)**

```bash
free -g
ps aux --sort=-%mem | head -10
pgrep -a vivado || echo "no vivado running"
pgrep -a java   || echo "no jvm running"
```

Require ≥ 20 GB free, no other Vivado, **no sbt/JVM sims** (Task 11/12 must be finished).

- [ ] **Step 2: Re-run the BASE impl back-to-back (do not reuse Task 5's base run)**

Placement equilibrium on this design is time-of-day-independent but the pair must be back-to-back to be trustworthy.

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-base
~/sbt/bin/sbt 'runMain m68k040.top.GenFullCoreSynthVerilog' 2>&1 | tail -3
vivado -mode batch -nojournal -log synth/vivado_base2.log \
       -source synth/impl_FullCore.tcl 2>&1 | tee synth/base2_impl.out
grep -E "NETLIST_MD5|POSTROUTE_FULLCORE" synth/base2_impl.out
grep -E "^\| CLB LUTs|^\| CLB Registers|^\| CLB  " synth/fullcore_route_util.rpt
```

**If this base draw differs from Task 5's by more than ~2 MHz, note it — the noise band is wider than assumed and every delta below must be read against the wider band.**

- [ ] **Step 3: Run the SLICE-2 impl**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
~/sbt/bin/sbt 'runMain m68k040.top.GenFullCoreSynthVerilog' 2>&1 | tail -3
vivado -mode batch -nojournal -log synth/vivado_s2.log \
       -source synth/impl_FullCore.tcl 2>&1 | tee synth/s2_impl.out
grep -E "NETLIST_MD5|POSTROUTE_FULLCORE" synth/s2_impl.out
grep -E "^\| CLB LUTs|^\| CLB Registers|^\| CLB  " synth/fullcore_route_util.rpt
grep -E "Number of Failing Endpoints|WNS|TNS" synth/fullcore_route_timing.rpt | head
```

- [ ] **Step 4: Re-run the LS census on the new routed checkpoint (spec §4 gate 3 input)**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
vivado -mode batch -nojournal -log synth/vivado_lscensus_s2.log \
       -source synth/census.tcl -tclargs synth/fullcore_routed.dcp synth/lscensus_s2 \
       2>&1 | tee synth/lscensus_s2.out
grep -E "CENSUS_FAMILY (LsEuPlugin|DcachePlugin)|CENSUS_PROBE" synth/lscensus_s2.out
```

Record whether `LsEuPlugin` / `DcachePlugin` failing-endpoint counts **grew** vs Task 1 (this is spec §4's gate 3 for even *considering* Stage 2), and whether the G-L2 prediction (that `bkCtx` shortens the `s1Ctx_uop_size`/`eaDelta` → `comp*` arcs) held.

- [ ] **Step 5: Apply the GC10 accept/reject decision, literally**

**ACCEPT** requires **all** of:
- `load-stream` cycles improve **≥ 25%** under both models (Task 11);
- AGGREGATE improves **≥ 8%** under both models (Task 11);
- post-route FMax **≥ 219 MHz**;
- CLB LUTs within **+1.5%** of the paired base;
- lock-step / LS-spec / ported-corpus fail-name-lists byte-identical (Task 12).

**REJECT and revert** on **any** of: FMax **< 217 MHz**; LUTs **> +2.5%**; any new correctness failure; `load-stream` improving **< 15%**.

Anything in between (FMax 217–219, LUTs +1.5–2.5%) is a **judgment call that must be escalated with the full table, not resolved silently.** The compValid precedent is the standard: *+0.24% IPC did not buy −6.55 MHz and +4785 LUTs, and the correct action was to revert cleanly and record why.* Do the same here without hesitation if the numbers say so.

- [ ] **Step 6: On REJECT — revert cleanly and record**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
git log --oneline $(cat /tmp/ls_split_base_sha.txt)..HEAD
# Do NOT merge the branch. Record the full measured table in the ledger, then:
cd /home/qwertyoruiop/m68k-core-040-ooo
git worktree remove --force /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work
```

Then jump straight to Task 14 (the ledger entry is required either way).

- [ ] **Step 7: On ACCEPT — merge the branch**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git merge --no-ff feat/ls-eu-late-split -m "lsu: LS EU late split at RESOLVE->LAUNCH (IPC push)

Front FSM owns S1 and completes every store + every SQ-forwarded load unchanged;
back FSM owns llReg/lineA/aDone and the D-cache access. Non-forwarded load
initiation interval 9 -> ~5 cycles.

Measured (8 seeds, both memory models): load-stream <X>%, aggregate <Y>% (zero) /
<Z>% (l2:5:70). Post-route <F> MHz vs <B> MHz base, CLB LUTs <L> vs <BL>.
Lock-step / LS-spec / ported-corpus fail-name-lists byte-identical.

issue.ready textually unchanged (spec 6.2's central bet, now measured).
Includes both correctness fixes the split requires: bkCtx.xlateSup (spec 3.2d) and
the llReg.bDone relocation (spec 5.5), each with a mutation-killed directed test."
```

---

## Task 14: Ledger, Stage-2 gate evaluation, and handoff

**Files:**
- Modify: `.superpowers/sdd/progress-ipc-push-2026-08-09.md`

**Interfaces:**
- Consumes: every measurement from Tasks 1–13.
- Produces: the initiative-level record and an explicit Stage-2 verdict.

- [ ] **Step 1: Evaluate spec §4's three gates for Stage 2 (evaluate only — do NOT implement)**

Answer each with the evidence in hand:

1. **Did Slice 1 (and Slice 2) land and pass their FMax + LUT gate *with margin*?** (Tasks 5, 13.)
2. **Does a post-Slice-2 `load-stream` trace show the FRONT stage is now the binding constraint** — `issue.valid && !ready` still high, with **`busy`** (not `bkBusy`, not `compValid`) the dominant term? If this is not measured, say "not measured" rather than guessing. A cheap way to answer it: instrument a counter over `issuePort.valid && !issuePort.ready`, split by `busy` / `bkBusy` / `compValid`, in a `load-stream`-only bench run.
3. **Did the Task-4 census show `LsEuPlugin`/`DcachePlugin` failing-endpoint counts NOT grow** from Slice 1/2? (Task 13 Step 4.)

**If (1) or (3) fails, Stage 2 is dead and the initiative moves on.** Record the verdict; do not open Stage 2 work in this plan under any circumstance (GC12).

- [ ] **Step 2: Write the initiative ledger entry**

Append to `.superpowers/sdd/progress-ipc-push-2026-08-09.md` a section `## LS EU late split (RESOLVE->LAUNCH) — <ACCEPTED|REJECTED>` containing:
- the Task-1 census answers G-L1…G-L4;
- the Task-2 baselines (lock-step, LS specs, ported corpus, IPC) with the exact `BASE_SHA`;
- the Slice-1 FMax gate table and its GO/NO-GO;
- the Slice-2 IPC table (8 seeds × 2 models, `load-stream` and AGGREGATE means);
- the Slice-2 FMax/LUT table and the final GC10 verdict;
- the Task-4 Step-7 finding (whether the supervisor bug pre-existed on the unsplit baseline);
- the five new directed specs and what each mutation-killed;
- the Stage-2 gate verdict from Step 1;
- an explicit note that `frontend/` and `decode/` were never touched (zero interaction with the concurrent fetch-directed BTB initiative).

- [ ] **Step 3: Commit and clean up worktrees**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add -f .superpowers/sdd/progress-ipc-push-2026-08-09.md
git commit -m "docs(ledger): LS EU late split -- full measured record and Stage-2 verdict"
git worktree remove --force /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-base 2>/dev/null
git worktree remove --force /home/qwertyoruiop/m68k-core-040-ooo-worktrees/ls-split-work 2>/dev/null
git worktree prune
git worktree list
```

---

## Appendix A: Spec coverage map (self-review artifact)

| spec section | covered by |
|---|---|
| §0.1 `load-stream` kernel | already landed (`caf72ff`); used as the primary gate in GC6, Tasks 2, 6, 11 |
| §0.2 measured baseline | Task 2 (re-measured, not quoted) |
| §1.1 G1–G4 | G1: Tasks 3+6; G2: Tasks 5+13; G3: the Slice-1/Slice-2 split itself; G4: Tasks 2+11 |
| §1.2 NG1–NG6 | GC12; NG1 additionally enforced by Task 8's test |
| §2 cycle budget | informs Task 6 Step 3's expected value |
| §3.1 front/back split | Task 3 Steps 5–11 |
| §3.2(a) two busy bits, `issue.ready` unchanged | Task 3 Steps 3, 12; GC13 |
| §3.2(b) back-stage entry + `RESOLVE` stall | Task 3 Step 9; Task 8 mutation |
| §3.2(c) `bkCtx` | Task 3 Steps 3–4 |
| §3.2(d) `xlate.req.supervisor` | Task 3 Steps 3–5; Task 4 (mutation-killed) |
| §3.2(e) second poison bit | Task 3 Steps 3, 9; Task 10 (mutation-killed) |
| §3.2(f) completion arbitration | Task 3 Steps 5–11; Task 9 |
| §3.3 predicted budget | Task 6 Step 3, Task 11 |
| §3.4/§3.5 what Stage 1 does not buy | Task 11 Step 4 (expects `load/store`/`mixed` unchanged) |
| §5.1–§5.4 correctness argument | Tasks 8, 9, 10 |
| §5.5 `llReg.bDone` | Task 3 Steps 5–6; Task 7 (mutation-killed) |
| §6.1 netlist facts | GC11 |
| §6.2 what Stage 1 adds | Tasks 5, 13 |
| §6.3 mandatory census | **Task 1** |
| §6.4 gating discipline | GC7–GC9; Tasks 5 (early), 13 (final) |
| §7 area | Task 5/13 CLB register + LUT reports |
| §8.1 baselines | Task 2 |
| §8.2 five directed tests | Tasks 4, 7, 8, 9, 10 |
| §8.3 slicing | Slice 1 = Tasks 3–5; Slice 2 = Tasks 6–13; Slice 3 = Task 14 |
| §8.4 accept/reject | GC10; Task 13 Step 5 |
| §9 alternatives rejected | GC12 |
| §4 Stage 2 | **evaluated only**, Task 14 Step 1 — never implemented |
