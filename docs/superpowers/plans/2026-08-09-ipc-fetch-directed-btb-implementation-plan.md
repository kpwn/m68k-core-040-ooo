# Fetch-Directed BTB (window-indexed FTB + FTQ + confirm-or-flush) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the measured 4-cycle fetch restart on a *correctly predicted* taken
branch by adding a window-indexed Fetch Target Buffer read at fetch time, a Fetch Target
Queue that carries each prediction down to decode, and a confirm-or-flush guard at decode —
targeting ≥ +10 % realistic-memory aggregate IPC (projection ~+20 %) with no FMax or LUT
regression.

**Architecture:** A new `FtbPlugin` holds a 128-entry, 8-byte-window-indexed table
(`{tag, brWordOff, brLen, target, brType, counter}`), read combinationally off the `fetchPc`
register with the result **registered** (never combinational into `fetchPc` — see §8.1).
On an applied prediction, the same `when(ic.cmd.fire)` arm atomically (a) records a
**trailing-word truncation** `ringKeep` on that window's outstanding-ring entry, (b) redirects
`fetchPc` to the learned target, (c) pushes an entry onto a 4-deep FTQ, and (d) shifts the GHR.
The IBuf therefore holds the *predicted dynamic* instruction byte stream. At decode, an
`availEff = min(ibuf.avail, spliceWords)` **genuine-word clamp** derived from the FTQ head
makes every already-load-bearing aligner stall gate into the mis-framing guard: no packet is
ever assembled from a byte at or beyond the splice. On agreement the branch is emitted with
`predTaken`/`predTarget` stamped and `decodePc` jumps straight to the target — no flush, no
refetch. On any disagreement the *existing* redirect mechanism flushes and refetches
sequentially, with a sticky `ftbSuppress` and a single-entry FTB invalidate closing liveness.

**Tech Stack:** SpinalHDL 1.14.1 / Scala 2.13.16, `spinal.lib.misc.plugin.FiberPlugin`
architecture, SpinalSim + Verilator for simulation, ScalaTest (`AnyFunSuite`) for specs,
Vivado 2024.x post-route (`xcku5p-ffvb676-2-e`, 250 MHz constraint) for the FMax/area gates.

## Global Constraints

**Read before starting anything:**
- Design spec, IN FULL: `docs/superpowers/specs/2026-08-09-ipc-fetch-directed-btb-design.md`
- Initiative ledger: `.superpowers/sdd/progress-ipc-push-2026-08-09.md`

**Binding gates — every slice, no exceptions:**
- `ExecuteLockStepSpec` at the **exact count re-measured in Task 2** (ledger says **390/394**
  at `1a627f7`; Task 2 re-measures at the branch's own base and **that** number binds
  everything after it), with a **byte-identical fail-name set**.
  Command: `~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec"`
- Full ported corpus (**870** tests), **zero new regressions**, fail-name list `diff`-empty
  against Task 2's re-measured baseline (ledger says **58 fails of 870**).
  Command: `tools/fuzz/ported-sweep-parallel.sh 4`
- `~/sbt/bin/sbt compile` stays clean throughout (0 errors, 0 new warnings).
- **git-worktree isolation is MANDATORY for every A/B comparison.** `git worktree add`,
  never `git checkout <sha>` in the shared tree — that caused a real collision before
  (task #199).
- **IPC benchmark is the accept/reject gate for the IPC claim** (spec §9.6). `IPC_SEED`
  pinned, **paired** before/after, **both** memory models, worktree-isolated:
  `IPC_SEED=<n> IPC_MEM=zero JAVA_OPTS=-Xmx6g ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec'`
  `IPC_SEED=<n> IPC_MEM=l2:5:70 JAVA_OPTS=-Xmx6g ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec'`
  Every slice that could plausibly move IPC measures it. Never assume it.
- **Real post-route FMax gate** (spec §9.7) — **not OOC-only**; OOC has been proven
  unreliable this session even for targeted-family checks:
  `JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"` then
  `vivado -mode batch -nojournal -log synth/vivado_FullCore.log -source synth/impl_FullCore.tcl`
  Read `POSTROUTE_FULLCORE_WNS_NS` / `POSTROUTE_FULLCORE_RESULT` and
  `synth/fullcore_route_timing.rpt`. Compare failing-endpoint families against **Task 1's
  census**, not just the top line.
- **Machine-contention discipline.** Before ANY Vivado run: `free -g` and
  `ps aux --sort=-%mem | head -20` (and `ps aux | grep -i vivado`). This machine has had
  severe contention all session (a sibling `macqd700-soc` `full_impl` held 6.9 GB). **Wait
  for a real window** — do not run a gate under contention and then reason about the number.
  Never run heavy sbt concurrently with Vivado. Max 2 concurrent heavy JVMs (29 GB total RAM).
- **LUT budget (spec G3).** Explicit pre/post `report_utilization` numbers at every synth
  gate. A material LUT increase is a REJECT independent of FMax and IPC. Budget: this lever
  deletes Lever D's measured **-1302 LUTs / -512 LUTRAM**; the new FTB/FTQ must fit inside it.
- **IPC-monotonicity (spec G4).** Any branch the fetch-time mechanism does not cover MUST
  fall back to *exactly today's* decode-time BTB/RAS/gshare path. The decode-time **slot-0**
  BTB/gshare/RAS read is **RETAINED** (spec §5.2) — this is a deliberate, recorded departure
  from the grounding's sketch. Do not delete it.

**LOCKED rejections — do not re-litigate (spec §8):**
- **§8.1 — a combinational (unregistered) FTB read feeding `fetchPc` is REJECTED.** This
  project measured that exact shape at **-36.29 MHz / -17.5 %** (`349a585` → fixed by
  `8267ff1`). The FTB result is REGISTERED. If a task's natural path drifts here, **STOP and
  escalate** — do not "just try it".
- **§8.4 — splice-at-decode via a larger `effShift` is REJECTED.** Truncation happens on the
  fetch/`rsp → push` side, which has slack; the `decodePc`/`shift` loop does not.
- **§8.6 — task #116 resolve-time selective mispredict recovery is REJECTED, unchanged.**
- **§8.3** — "prove the splice is unreachable" instead of the clamp: REJECTED.
- **§8.5** — combinational target issue in the redirect cycle: REJECTED.
Each of these is a **deviation requiring escalation**, not a shortcut to take quietly.

**Phase-1 scope fences (spec N6):** cross-window branches (`brEndOff ≥ 4`), fetch-time RAS
for `rts`, the opword-class confirm hardening, deleting the decode-time slot-0 read, and
removing the slot-1 defer are all **out of scope**. Each is its own later design pass.

**Fallback is pre-authorised.** If Task 11 (`FtbConfirmGuardSpec`) or Task 12
(`FtbStreamEquivalenceSpec`) cannot be closed with full confidence, **do not patch the guard
incrementally** — pivot to **Approach C** (Task 16). The decision point is explicitly at the
end of Task 12.

---

## Plan-time resolution of the design spec's §10 open questions

The spec deferred eight questions to plan-writing time. Each is resolved here, with reasoning.
Where a task depends on one, the task states the dependency.

**Q1 — re-confirm every line citation against live source. RESOLVED by audit (done during
plan-writing).** Audit performed against HEAD `c949f0f`. Corrections that matter:

| spec citation | reality |
|---|---|
| `frontend/IcachePlugin.scala` | file is **`src/main/scala/m68k040/cache/IcachePlugin.scala`** |
| `ParamPlugin.scala:19` | file is **`src/main/scala/m68k040/core/ParamPlugin.scala`**, line 19 correct |
| `Btb.scala:44` (stale `queryPc` comment) | actually **`Btb.scala:34`** (class scaladoc). Line 44 is `var queryValidPort`. **The comment IS stale/wrong as the spec claims** — fix it at line 34. |
| `Btb.scala:52-63` | `idxLo = 1` is at **:53**, `valids` at **:63**. The header comment at **:52** (`index = pc[1+idxBits downto 2]`) is ALSO stale/wrong — the code is `pc[7:1]`. Fix both comments. |
| `Btb.scala:75-201` (`spec2` block) | real extents: comment **72-81**, declarations **82-87**, rationale **115-177**, `spec2*` Vecs **178-181**, 9-way loop **182-196**, late select **197-201**. Delete **72-87 and 115-201**. |
| `Btb.scala:216-228` (counters) | real span **214-228**; `cInc`/`cDec` at **219-220**, `ctrAlloc` **222-223**. |
| `Services.scala:51-61` | `BtbUpdate` is **51-56**; trait `BtbUpdateService` is **60-62**. |
| `Services.scala:64-70` | comment **64-69**, `GshareUpdate` **70-73**. |
| `BranchEuPlugin.scala:251-262` | `isReturn` **259**, `isBtbBranch` **260-261**. |
| `FullCoreSynth.scala:66-70` | comment is **59-66**, code **67-70**. |
| `FullCoreSynth.scala:82-83` | `val btb` at **82**, invalidate wiring at **83-84**. |
| `Aligner.scala:18-27` | `Result` bundle is **10-28**; `slot1Sel` is **27**. |
| `Gshare.scala:81`, `:39-42`; `Btb.scala:17-22`; `BranchEuPlugin.scala:231`, `:247`; `Aligner.scala:95-110`, `:167`, `:267`; `Config.scala:23`; `Global.scala:22` | **all accurate.** |

`spec2Taken/Target/Hit/Type` have **zero references outside `Btb.scala`**.
`query2BasePc`/`query2Sel`/`query2Valid` and `predTaken2Comb`/`predTarget2Comb`/
`predHit2Comb`/`predType2Comb` are referenced in exactly 5 files: `FullCoreSynth.scala`,
`FuzzDut.scala`, `IpcBenchSpec.scala`, `ExecuteLockStepSpec.scala`,
`BtbLateSelectEquivalenceSpec.scala`. That is the complete Slice-4 blast radius.

**Standing rule for every RTL task below:** re-anchor edits by **content grep**, not by the
line numbers printed here — earlier tasks in this plan shift them.

**Q2 — `FtbPlugin` as a `FiberPlugin` vs an `Area` inside `FetchAlignPlugin`. RESOLVED:
`FiberPlugin`.** It matches the `BtbPlugin`/`RasPlugin`/`GsharePlugin` convention, gets a
standalone unit-test DUT for free (Task 4), and — decisively — lets the guard specs install
deliberately-wrong entries through the *real* training path rather than through test-only
hardware. No Fiber build-order cycle is created: `FetchAlignPlugin` **drives** a plain
directionless `queryPc` wire and **reads** plain combinational outputs, exactly as it already
does for `BtbPlugin`; the wiring is done by the *top-level* wiring plugin (`FullCoreSynth`,
`FuzzDut`, the two test DUTs), never by `FetchAlignPlugin` calling `host[FtbPlugin]`. That is
precisely the discipline `FullCoreSynth.scala:59-66`'s `mispredictRedirect` comment records.

**Q3 — `ftqDelta`'s width and saturation. RESOLVED, and this corrects a real bug in spec
§2.8.** Definitions used throughout this plan:
```
ftqDiff  = (ftq(ftqHead).brPc - decodePc)        // UInt(32 bits), wrapping
ftqDelta = ftqDiff(4 downto 1)                   // UInt(4 bits) — word delta, 0..15
ftqNear  = ftqV && (ftqDiff(31 downto 5) === 0)  // == the spec's (diff>>1)(31 downto 4)===0
ftqPast  = ftqV && ftqDiff(31)                   // NOT gated on ftqNear
```
Spec §2.8 writes `ftqPast = ftqNear && ftqDelta(31)`. That is **identically False**: `ftqNear`
already requires bits 31..5 of the difference to be zero, so bit 31 cannot be set inside it.
As written, `ftqPast` would be dead logic and §9.2's mandated mutation test on it would be
unkillable. The corrected form (`ftqV && ftqDiff(31)`, ungated) is what this plan implements.
`ftqNear`'s 16-word reach vs `HEAD_WORDS = 10` is deliberate and safe: for
`ftqDelta ∈ 10..15` the clamp `min(avail, ftqDelta + brLen)` is never binding (`avail ≤ 10`),
so it degenerates to today's behaviour, exactly as spec §3.5-A9 requires.

**Q4 — where `availEff` is substituted. RESOLVED, pinned exhaustively.** Substitute at
**exactly two sites and no others**:
1. `Aligner.align(decodePc, ibuf.io.head, ibuf.io.headPred, availEff, p0LiveReg)`
   (`FetchAlignPlugin.scala:412`).
2. `p0LiveReg`'s three classify validity flags (`FetchAlignPlugin.scala:402-404`):
   `extWValid = availEff >= 2`, `extW2Valid = availEff >= 3`, `extW3Valid = availEff >= 4`.

**Do NOT substitute at:**
- `ibufRoomForIssue` (`:243-244`) — it uses `ibuf.io.cnt` (physical occupancy), not `avail`,
  and must keep doing so. Truncation only *shrinks* a window, so its 4-words-per-outstanding
  reservation stays a valid strict upper bound.
- `InstructionBuffer.io.avail` itself — the IBuf is untouched by this lever.
- `p0LiveInvalidate`'s `ibuf.io.cnt < 4` term — that is physical occupancy too.
A wrong substitution here is a silent-corruption vector; this list is normative.

**Q5 — should `ftqOvershoot` suppress the emit or allow it. RESOLVED: ALLOW.** Derivation
against the real `effShift`/`decodePcNext` timing (`FetchAlignPlugin.scala:606-618`): the
overshooting packet's own words are all `< splice` (that is what `avail < L0` / `slot1Ok`'s
`avail >= L0L1` guarantee under the clamp), so every emitted byte is genuine. The
second-order case the spec asked about — slot1 also emitted — is covered *for free* because
`decodePcNext = Mux(suppressSlot1, decodePc + 2*L0, decodePc + 2*shiftWords)` already accounts
for **both** slots: `res.shiftWords == L0 + L1` in the `slot1Ok` arm. So redirecting to
`decodePcNext` is correct whether one or two slots fired, and loses nothing. Additional
constraint discovered while resolving this and folded into the code below: `ftqOvershoot` and
`ftqLenBad` must both be gated on `feed.fire` — under backpressure the packet has *not* fired,
and redirecting to `decodePcNext` would skip an unemitted instruction. Holding the clamp
during backpressure is correct and safe (no post-splice byte is used).

**Q6 — `ftbEnable` control shape. RESOLVED.** A **self-assigned `RegInit`** in
`FetchAlignPlugin.logic`, `simPublic`, never an `in Bool()` inside a plugin `Area` (recorded
trap: that makes a top-level port where `default(...)` silently does nothing; cost last time
was one full IPC sweep that came back bit-identical). Written exactly as:
```scala
val ftbEnable = RegInit(False); ftbEnable := ftbEnable; ftbEnable.simPublic()
```
The self-assignment is what keeps it out of the "UNASSIGNED REGISTER" synth check while
staying pokeable. Pokes use the **non-blocking** pattern (a poke immediately after
`forkStimulus` is overwritten by reset):
```scala
fork { for (_ <- 0 until 8) { dut.fa.logic.ftbEnable #= true; cd.waitSampling() } }
```
Default is `False` from Task 6 through Task 13, and is flipped to `True` as an explicit,
separately-gated step in Task 14.
**Load-bearing corollary:** `ftbEnable` gates **only** `applyPrediction`. It does **not** gate
the FTQ, the `availEff` clamp, or the confirm/mismatch logic — if it did, poking it off with
entries in flight would strand a live splice with nothing describing it, which is the exact
F7 silent-corruption shape.

**Q7 — Task 1's census script. RESOLVED: a reusable `synth/census.tcl`**, written in full in
Task 1, so no future pass rediscovers that `-max_paths 10` is useless for family analysis.

**Q8 — does `Config.scala` need `ftbEnable` as an *elaboration* parameter too. RESOLVED: NO
for `ftbEnable`, YES for `ftbEntries` and `ftqDepth`.** Reasoning: an elaboration-time
enable would create two structurally different RTL variants, doubling the verification
surface (both would need the full corpus + lock-step) to buy a LUT A/B we already get, more
faithfully, from the mandated worktree-isolated base-vs-branch synth. `ftbEntries`/`ftqDepth`
*are* threaded as elaboration parameters, following `btbEntries` exactly:
`M68kParams` field → `ParamPlugin` `during setup` `Global.X.set(...)` → `Global.X.get` in the
plugin's `during build`.

**Two further gaps found while resolving the above, resolved here so no task has to invent
an answer:**

**R1 — fetch-time vs decode-time GHR shift collision.** §2.6 mandates a GHR shift at fetch on
an applied conditional prediction and adds `&& !ftqConfirm` to `gsShiftValid`, but does not
say what happens when a fetch-side shift and a decode-side shift want the single-ported GHR in
the **same cycle**. RESOLVED: **fetch wins**, the decode-side shift is dropped. This lands
inside `Gshare.scala:39-42`'s explicit accept-corruption tolerance (the branch EU cross-checks
direction *and* target, `BranchEuPlugin.scala:247-248`), and each conditional still carries its
own `phtIndex` down, so retire trains the exact entry the lookup read regardless. Coded as a
single `Mux` on the existing `gsShiftValid`/`gsShiftDir` ports — no second port.

**R2 — `ftqStarved` needs a dwell counter.** `ftqStarved` fires on
`ftqNear && !res.slot0Valid && availEff < avail`. `p0LiveReg` is a **register** that is forced
`ambiguousLine := True` for one cycle whenever its inputs change — and `availEff` changing is
now one of those inputs (spec §2.8's own `p0LiveInvalidate` addition). So for 1-2 cycles after
any `availEff` change the aligner legitimately reports `!slot0Valid` while nothing is wrong.
Firing immediately would flush constantly and destroy the win. RESOLVED: a 2-bit saturating
`ftqStarveCnt`; `ftqStarved` fires only after the raw condition has held for **3 consecutive
cycles**. This is safe by construction because `ftqStarved` is a **liveness** guard only — the
correctness obligation (no packet assembled from a post-splice byte) is discharged entirely by
the clamp plus the aligner's own stalls (spec §3.4), never by this guard's timing. And a
genuine starve is *permanent* (`availEff` is pinned at `spliceWords`, which cannot grow), so a
3-cycle dwell costs 3 cycles on a rare event and can never mask a real one.

---

## File Structure

| file | responsibility | task |
|---|---|---|
| `synth/census.tcl` | **NEW.** Reusable full failing-endpoint + per-family post-route census on a routed `.dcp`. | 1 |
| `src/main/scala/m68k040/services/Services.scala` | `BtbUpdate` gains `len`. | 3 |
| `src/main/scala/m68k040/execute/BranchEuPlugin.scala` | `BranchCompletion` gains `btbLen`; driven `(nextPc - pc) >> 1`. | 3 |
| `src/main/scala/m68k040/rob/RobPlugin.scala` | `BranchTrainPayload` gains `len`; carried write→read→`btbUpdateFlow`. | 3 |
| `src/main/scala/m68k040/Config.scala` | `ftbEntries = 128`, `ftqDepth = 4`. | 4 |
| `src/main/scala/m68k040/Global.scala` | `FTB_ENTRIES`, `FTQ_DEPTH`. | 4 |
| `src/main/scala/m68k040/core/ParamPlugin.scala` | set both. | 4 |
| `src/main/scala/m68k040/frontend/Ftb.scala` | **NEW.** `FtbEntry`, `FtbPlugin`: window-indexed table, install filter, single-entry clear, combinational lookup. | 4 |
| `src/main/scala/m68k040/frontend/Gshare.scala` | 4 speculative PHT reads off `idxBase ^ k` + `specIdxBase` export. | 5 |
| `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala` | registered `ftbRes`/`ftbResFresh`; `ftbEnable`; `ringKeep`; FTQ; `applyPrediction`; `availEff`; confirm/mismatch; `ftbSuppress`; F1–F12 flush wiring; `slot1WouldPred` re-source. | 6-10, 15 |
| `src/main/scala/m68k040/frontend/Aligner.scala` | (Task 15 only) delete `slot1Sel`. | 15 |
| `src/main/scala/m68k040/frontend/Btb.scala` | consume `upd.payload.len` (no-op); fix 2 stale comments; (Task 15) delete `query2*`/`spec2*`. | 3, 15 |
| `src/main/scala/m68k040/top/FullCoreSynth.scala` | wire `FtbPlugin` (both invalidate sources) + gshare spec port; (Task 15) drop `query2*`. | 6, 15 |
| `src/test/scala/m68k040/fuzz/FuzzDut.scala` | same wiring. | 6, 15 |
| `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` | same wiring. | 6, 15 |
| `src/test/scala/m68k040/bench/IpcBenchSpec.scala` | same wiring. | 6, 15 |
| `src/test/scala/m68k040/frontend/FtbPluginSpec.scala` | **NEW.** Unit test: install filter, allocation/counter semantics, invalidate, single-entry clear. | 4 |
| `src/test/scala/m68k040/frontend/GshareSpecReadSpec.scala` | **NEW.** Fold-linearity equivalence: `indexOf(base\|k) === specIdxBase ^ k`. | 5 |
| `src/test/scala/m68k040/frontend/FtbConfirmGuardSpec.scala` | **NEW.** §9.2 — one directed case per §3.5 row + L1/L2 + positive control, mutation-tested. | 11 |
| `src/test/scala/m68k040/frontend/FtbStreamEquivalenceSpec.scala` | **NEW.** §9.3 — the differential "fetch-direction is architecturally invisible" proof. | 12 |
| `src/test/scala/m68k040/frontend/FtqFlushSpec.scala` | **NEW.** §9.4 — F1–F12 + INV-A/INV-B live monitors. | 13 |
| `src/test/scala/m68k040/frontend/FtbTestDut.scala` | **NEW.** Shared component-level DUT (IcachePlugin + FetchAlign + Ftb + Btb + Gshare + Ras + probe + pokeable update driver) for Tasks 11-13. | 11 |
| `src/test/scala/m68k040/frontend/BtbLateSelectEquivalenceSpec.scala` | **DELETE** in Task 15 (its subject is removed) — a recorded, deliberate coverage retirement. | 15 |

---

### Task 1: MANDATORY PREREQUISITE — fresh post-route failing-endpoint census on HEAD

> **This is a gate, not an assumption. NO RTL FOR THIS LEVER MAY BE WRITTEN BEFORE IT
> COMPLETES.** Spec §5.4. Every FMax number in the design spec is extrapolated from 10
> reported paths, and **no post-route `.dcp` exists for the current 221.828 MHz design** —
> the newest one on the machine (`…/wt-asl-base/synth/fullcore_routed.dcp`, `344c0c5`,
> 190.11 MHz) predates Lever F, per-beat predecode and the whole I-side chain, and on that
> draw the frontend contributes **zero** top-100 endpoints.

**Files:**
- Create: `synth/census.tcl`
- Read: `synth/impl_FullCore.tcl` (it already writes `synth/fullcore_routed.dcp` at step 6)

**Interfaces:**
- Produces: `synth/census_endpoints.rpt`, `synth/census_summary.rpt`,
  `synth/census_family.rpt`, `synth/census_probe_*.rpt`, `synth/fullcore_routed.dcp`,
  and the recorded verdicts **G-T1a / G-T1b / G-T1c** that Tasks 9 and 15 consume.

- [ ] **Step 1: Wait for a genuinely uncontended machine window**

```bash
free -g
ps aux --sort=-%mem | head -20
ps aux | grep -i -E 'vivado|sbt|java' | grep -v grep
```
Expected: no `vivado` process, no heavy `java`/`sbt`, and **≥ 12 GB free**. If a sibling
project's `full_impl` (or MAME, or another agent's sbt) is running, **wait**. Do not proceed
and then caveat the number — this session has repeatedly produced untrustworthy FMax under
contention (214.3 vs 163.9 MHz for an identical commit).

- [ ] **Step 2: Create the reusable census script**

Create `synth/census.tcl`:

```tcl
# synth/census.tcl -- FULL post-route failing-endpoint CENSUS on a routed checkpoint.
#
# WHY THIS EXISTS: synth/impl_FullCore.tcl reports `-max_paths 10`, which is useless for
# per-FAMILY analysis. The 2026-08-09 fetch-directed-BTB design spec (§5.4) needed to know
# which module families own the 4512 failing endpoints and could only extrapolate from 10
# reported paths. This script answers that question and is meant to be re-run by any future
# FMax pass -- do not delete it, and do not re-derive it ad hoc.
#
# usage:
#   vivado -mode batch -nojournal -log synth/vivado_census.log \
#          -source synth/census.tcl -tclargs synth/fullcore_routed.dcp synth/census
#
# (synth/impl_FullCore.tcl already writes synth/fullcore_routed.dcp as its last step.)

set dcp    [lindex $argv 0]
set prefix [lindex $argv 1]
open_checkpoint $dcp

puts "########### CENSUS on $dcp ###########"

# ---- 1) the full failing-endpoint list (one worst path per endpoint) ----
report_timing -setup -max_paths 5000 -nworst 1 -slack_lesser_than 0 \
              -file ${prefix}_endpoints.rpt
report_timing_summary -max_paths 10 -file ${prefix}_summary.rpt
report_utilization -file ${prefix}_util.rpt
catch { report_design_analysis -congestion -file ${prefix}_congestion.rpt }

# ---- 2) per-family census: group every failing endpoint by leaf-module prefix ----
set paths [get_timing_paths -setup -max_paths 5000 -nworst 1 -slack_lesser_than 0]
puts "CENSUS_TOTAL_FAILING [llength $paths]"

array unset famCount
array unset famWorst
array unset famSlacks
foreach p $paths {
  set nm  [get_property NAME [get_property ENDPOINT_PIN $p]]
  # leaf-module family key: text before the first "/", with _logic*/_reg* suffixes stripped
  set key [lindex [split $nm "/"] 0]
  regsub {_logic.*$} $key "" key
  regsub {_reg.*$}   $key "" key
  set s [get_property SLACK $p]
  if {![info exists famCount($key)]} {
    set famCount($key) 0 ; set famWorst($key) 99.0 ; set famSlacks($key) {}
  }
  incr famCount($key)
  if {$s < $famWorst($key)} { set famWorst($key) $s }
  lappend famSlacks($key) $s
}

set rows {}
foreach k [array names famCount] {
  set sorted [lsort -real $famSlacks($k)]
  set med    [lindex $sorted [expr {[llength $sorted] / 2}]]
  lappend rows [list $famCount($k) $k $famWorst($k) $med]
}
set fp [open ${prefix}_family.rpt w]
puts $fp [format "%-56s %8s %10s %10s" "FAMILY" "COUNT" "WORST_NS" "MEDIAN_NS"]
foreach r [lsort -integer -decreasing -index 0 $rows] {
  puts $fp [format "%-56s %8d %10.3f %10.3f" \
            [lindex $r 1] [lindex $r 0] [lindex $r 2] [lindex $r 3]]
  puts     [format "CENSUS_FAMILY %-40s %6d  worst %8.3f  median %8.3f" \
            [lindex $r 1] [lindex $r 0] [lindex $r 2] [lindex $r 3]]
}
close $fp

# ---- 3) -through probes for the three families this lever is about ----
proc census_probe {label pat prefix} {
  set cells [get_cells -quiet -hierarchical -filter "NAME =~ $pat"]
  puts "CENSUS_PROBE $label CELLS [llength $cells]"
  if {[llength $cells] == 0} { return }
  set pp [get_timing_paths -quiet -setup -max_paths 5000 -nworst 1 \
                           -slack_lesser_than 0 -through $cells]
  puts "CENSUS_PROBE $label FAILING_ENDPOINTS [llength $pp]"
  if {[llength $pp] > 0} {
    puts "CENSUS_PROBE $label WORST [get_property SLACK [lindex $pp 0]]"
  }
  catch {
    report_timing -setup -max_paths 20 -nworst 1 -through $cells \
                  -file ${prefix}_probe_${label}.rpt
  }
}
# (a) Lever D's spec2 speculative-read cells -- the design spec's claimed #1 failing cone
census_probe spec2_leverD "*BtbPlugin_logic_mem_reg_r4*" $prefix
# (b) the slot-0 BTB port cells (RETAINED by this lever -- the G4 fallback)
census_probe btb_slot0    "*BtbPlugin_logic_mem_reg_r0*" $prefix
# (c) every BTB RAM cell, as the denominator for (a) and (b)
census_probe btb_all      "*BtbPlugin_logic_mem_reg*"    $prefix
# (d) the Aligner preds(L0) -> slot1Ok -> io_shift family flagged in spec §5.3
census_probe aligner_ibuf "*InstructionBuffer*"          $prefix
census_probe fetchalign   "*FetchAlignPlugin*"           $prefix
puts "########### CENSUS COMPLETE ###########"
```

- [ ] **Step 3: Generate the netlist and run a full post-route implementation at HEAD**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git rev-parse HEAD          # record this SHA in the report -- it is the census's identity
JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
vivado -mode batch -nojournal -log synth/vivado_FullCore.log -source synth/impl_FullCore.tcl
```
Expected: `POSTROUTE_FULLCORE_RESULT FAILED_AT_250 ACHIEVED_FMAX_MHZ ~221.8`, and
`synth/fullcore_routed.dcp` written. Record `NETLIST_MD5` (regen ordering is bistable — the
`3e57fe6` netlist-lottery fix is what makes this reproducible; if the MD5 differs from a
re-run, say so).

- [ ] **Step 4: Run the census on the routed checkpoint**

```bash
vivado -mode batch -nojournal -log synth/vivado_census.log \
       -source synth/census.tcl -tclargs synth/fullcore_routed.dcp synth/census
```
Expected: `CENSUS_TOTAL_FAILING <N>` (the spec's extrapolation says ~4512), a
`CENSUS_FAMILY` line per module family sorted by count, and `CENSUS_PROBE` lines for all
five probes.

- [ ] **Step 5: Evaluate the three gate conditions and RECORD the verdicts**

Read `synth/census_family.rpt` and the `CENSUS_PROBE` lines, then answer each explicitly:

- **G-T1a** — does the `spec2`/slot-1 family (`CENSUS_PROBE spec2_leverD FAILING_ENDPOINTS`)
  own the **plurality of frontend failing endpoints**? Compare against
  `CENSUS_PROBE btb_slot0` and `CENSUS_PROBE fetchalign`.
  **If NO: spec §5.1's entire FMax case is wrong. STOP. Re-scope before any RTL.**
- **G-T1b** — is the `Aligner preds(L0) → slot1Ok → io_shift` family (proxy:
  `CENSUS_PROBE aligner_ibuf`, plus any `InstructionBuffer`/`Aligner` rows in
  `census_family.rpt`) already **at or worse than** the frontend BTB family?
  **If YES: the `availEff` clamp's placement must be re-designed BEFORE Task 9** — the clamp
  lands directly on `slot1Ok`. The redesign direction (do not invent another): clamp only the
  `avail < L0` gate and derive `slot1Ok`'s bound from `spliceWords` on a separate, parallel
  arc. Escalate with the census data rather than proceeding.
- **G-T1c** — re-derive the FMax relief cap from the **real** distribution, not from the 10
  paths §5.3 used. Report: WNS, TNS, total failing endpoints, and what WNS would become if
  every `spec2` endpoint were removed (i.e. the worst slack among non-`spec2` endpoints).
  **If the real cap is materially below ~224.5 MHz, record that the LUT and IPC cases must
  carry this lever on their own** — and do not let any later task gate on a top-line MHz
  number.

- [ ] **Step 6: Commit the census script and the reports**

```bash
git add synth/census.tcl synth/census_family.rpt synth/census_summary.rpt
git commit -m "synth: reusable post-route failing-endpoint census (fetch-directed BTB Task 1)

Adds synth/census.tcl -- full -max_paths 5000 failing-endpoint list, a per-leaf-module
family census (count / worst / median slack), and -through probes for the Lever-D spec2
cone, the BTB slot-0 port and the Aligner/IBuf family. impl_FullCore.tcl reports only
-max_paths 10, which cannot answer 'which family owns the wall'.

Prerequisite gate for docs/superpowers/specs/2026-08-09-ipc-fetch-directed-btb-design.md
(its §5.4). Records G-T1a/G-T1b/G-T1c verdicts at HEAD."
```
(`synth/census_endpoints.rpt` can be large — leave it untracked unless it is under a few MB.)

---

### Task 2: Re-measure every functional and IPC baseline on the implementation branch's base

**Files:** none modified. This task produces the numbers every later gate compares against.

**Interfaces:**
- Consumes: nothing.
- Produces: the binding baseline tuple `(lockstepPass/lockstepTotal, lockstepFailNames,
  portedFailCount, portedFailNames, ipcZero[seed], ipcL2[seed])` recorded in the task report.

> Spec §9.1 is explicit: **re-measure, do not quote.** The ledger's numbers were taken at
> `1a627f7`; HEAD has since absorbed the I-side MSHR merge plus a concurrent session's FPU
> docs and vendored tests. Do not run this concurrently with Task 1's Vivado.

- [ ] **Step 1: Create the isolated implementation worktree**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git worktree add -b feat/ipc-ftb ../wt-ftb HEAD
cd ../wt-ftb && git rev-parse HEAD
```
All subsequent tasks work in `../wt-ftb`. Record the base SHA — it is the A-side of every A/B.

- [ ] **Step 2: Lock-step baseline**

```bash
cd ../wt-ftb
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec" 2>&1 | tail -40
```
Expected: **390/394** (ledger). Record the actual pass/total **and the 4 (or N) failing test
names verbatim** — later gates compare the name set, not just the count. The documented 4 are
3 × ITLB + `STOP #imm → halt → IRQ → handler → RTE → resume`.

- [ ] **Step 3: Ported-corpus baseline**

```bash
cd ../wt-ftb
tools/fuzz/ported-sweep-parallel.sh 4 2>&1 | tail -30
cp ported_logs_parallel/all_fails.txt /tmp/ftb_baseline_fails.txt
wc -l /tmp/ftb_baseline_fails.txt
```
Expected: **58 fails of 870** (ledger). Record the count and keep
`/tmp/ftb_baseline_fails.txt` — every later corpus gate is `diff`-empty against it.

- [ ] **Step 4: IPC baseline, 5 seeds, both memory models**

```bash
cd ../wt-ftb
for s in 1 2 3 4 5; do
  IPC_SEED=$s IPC_MEM=zero   JAVA_OPTS=-Xmx6g ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' 2>&1 | tee /tmp/ipc_base_zero_$s.log | grep -E 'AGGREGATE|cycles|IPC'
  IPC_SEED=$s IPC_MEM=l2:5:70 JAVA_OPTS=-Xmx6g ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' 2>&1 | tee /tmp/ipc_base_l2_$s.log  | grep -E 'AGGREGATE|cycles|IPC'
done
```
Expected order of magnitude (ledger, seeds 1-3): `l2:5:70` aggregate 9228 / 9316 / 9207;
`zero` aggregate 5999 / 6019 / 6007. Record the **per-kernel** cycle counts too — the accept
gate in Task 14 needs `hot-loop`, `branchy`, `call-return` individually, plus `load/store`'s
seed spread (5 seeds span ~46 cyc = 4.4 % under ideal memory; that spread is the band its
regression must be read against, and it is what produced an earlier false "-1.77 % regression"
alarm).

- [ ] **Step 5: Record the baseline in the task report**

No commit (nothing changed). The report must state, as a table: lock-step pass/total + fail
names; ported fail count + the path to the fail list; per-kernel and aggregate IPC for 5 seeds
× 2 memory models; and Task 1's WNS / FMax / LUT / FF / failing-endpoint count.

---

### Task 3 (Slice 1): `BtbUpdate.len` end to end — provably inert

**Files:**
- Modify: `src/main/scala/m68k040/services/Services.scala` (`BtbUpdate`, ~:51-56)
- Modify: `src/main/scala/m68k040/execute/BranchEuPlugin.scala` (`BranchCompletion` ~:15-27,
  drive site ~:277-281)
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala` (`BranchTrainPayload` ~:90-96,
  write site ~:718-726, read/drive site ~:913-919)
- Modify: `src/main/scala/m68k040/frontend/Btb.scala` (fix the two stale comments at ~:34
  and ~:52; `len` is deliberately unread here)
- Test: `src/test/scala/m68k040/frontend/BtbPluginSpec.scala` (extend)

**Interfaces:**
- Produces (later tasks rely on these exact names/types):
  - `m68k040.services.BtbUpdate.len : UInt(4 bits)` — the retiring branch's length in
    **words** (1..5 for anything installable; wider values are legal on the wire and are
    filtered by the FTB, not here).
  - `m68k040.execute.BranchCompletion.btbLen : UInt(4 bits)`
  - `m68k040.rob.RobPlugin.BranchTrainPayload.len : UInt(4 bits)`

> This slice mirrors the I-side chain's V2a.1 "inert slice" discipline: nothing consumes
> `len`, so it is behaviour-neutral by construction and is gated for **zero** change.

- [ ] **Step 1: Write the failing test**

Append to `src/test/scala/m68k040/frontend/BtbPluginSpec.scala`, inside the class (the
existing `BtbUpdateDriverPlugin`/`BtbWirePlugin`/`BtbDut` above it are reused as-is; note
`upd.payload.flatten.foreach(in(_))` already exposes any new field as a top-level input, so
no harness change is needed):

```scala
  test("BtbUpdate carries `len` (inert in slice 1): the BTB still predicts identically", VerilatorTest) {
    SimConfig.withVerilator.compile(new BtbDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.drv.logic.upd.valid #= false
      dut.wire.logic.qValid #= false
      dut.wire.logic.inval  #= false
      cd.waitSampling(3)

      // Install a branch at 0x1000 -> 0x2000, unconditional, with len = 2 words.
      dut.drv.logic.upd.valid          #= true
      dut.drv.logic.upd.payload.pc     #= 0x1000L
      dut.drv.logic.upd.payload.taken  #= true
      dut.drv.logic.upd.payload.target #= 0x2000L
      dut.drv.logic.upd.payload.brType #= 1
      dut.drv.logic.upd.payload.len    #= 2
      cd.waitSampling()
      dut.drv.logic.upd.valid #= false
      cd.waitSampling(2)

      // The BTB's prediction must be UNAFFECTED by the new field: hit, taken, 0x2000.
      dut.wire.logic.qPc    #= 0x1000L
      dut.wire.logic.qValid #= true
      cd.waitSampling()
      sleep(1)
      assert(dut.wire.logic.oPredValid.toBoolean, "expected a BTB hit at 0x1000")
      assert(dut.wire.logic.oPredTarget.toLong == 0x2000L,
        s"target=0x${dut.wire.logic.oPredTarget.toLong.toHexString}")

      // And a DIFFERENT len on the same entry must still not change the prediction.
      dut.wire.logic.qValid #= false
      dut.drv.logic.upd.valid       #= true
      dut.drv.logic.upd.payload.len #= 5
      cd.waitSampling()
      dut.drv.logic.upd.valid #= false
      cd.waitSampling(2)
      dut.wire.logic.qPc    #= 0x1000L
      dut.wire.logic.qValid #= true
      cd.waitSampling()
      sleep(1)
      assert(dut.wire.logic.oPredValid.toBoolean, "len must not affect BTB hit")
      assert(dut.wire.logic.oPredTarget.toLong == 0x2000L, "len must not affect BTB target")
    }
  }
```

- [ ] **Step 2: Run it and verify it fails**

```bash
cd ../wt-ftb && ~/sbt/bin/sbt "testOnly m68k040.frontend.BtbPluginSpec"
```
Expected: **compile error** — `value len is not a member of m68k040.services.BtbUpdate`.
That is the correct failure.

- [ ] **Step 3: Add `len` to `BtbUpdate`**

In `src/main/scala/m68k040/services/Services.scala`, replace the `BtbUpdate` case class body
(currently lines ~51-56):

```scala
case class BtbUpdate() extends Bundle {
  val pc     = UInt(32 bits)   // the retiring branch's PC (index/tag source)
  val taken  = Bool()          // resolved taken (bimodal direction)
  val target = UInt(32 bits)   // resolved taken-target (learned)
  val brType = UInt(2 bits)    // 0=cond, 1=uncond
  // ── fetch-directed BTB (task #126, slice 1): the branch's LENGTH in words ──────
  // = (nextPc - pc) >> 1, computed by BranchEuPlugin from the assembler's own
  // fall-through PC. The word-indexed `BtbPlugin` does NOT read this (a decode-time
  // lookup is already at an instruction boundary and needs no length); the
  // WINDOW-indexed `FtbPlugin` does, because a fetch-time prediction must know where
  // the branch ENDS in order to truncate the fetched window at exactly that point
  // (`ringKeep`). Legal installable range is 1..5 words; the wire is 4 bits so a
  // longer/garbage value is representable and is REJECTED by FtbPlugin's install
  // filter rather than silently truncated here.
  // See docs/superpowers/specs/2026-08-09-ipc-fetch-directed-btb-design.md §2.3.
  val len    = UInt(4 bits)
}
```

- [ ] **Step 4: Add `btbLen` to `BranchCompletion` and drive it**

In `src/main/scala/m68k040/execute/BranchEuPlugin.scala`, add to the `BranchCompletion`
bundle, immediately after `val brType`:

```scala
  val btbLen     = UInt(4 bits)   // the branch's length in WORDS ((nextPc - pc) >> 1)
```

and at the drive site (currently ~:277-281), immediately after
`completionPort.payload.brType := brType`:

```scala
    // ── fetch-directed BTB (task #126): the branch's length in WORDS ──────────────
    // `u1.nextPc` is the MicroOpAssembler-computed FALL-THROUGH PC (pc + encoded
    // length) -- NOT the local `nextPc` val above, which is `Mux(redirect, target,
    // u1.nextPc)` and would give the TARGET's distance on a taken branch. Correct for
    // DBcc too (a 2-word instruction whose fall-through is pc+4).
    completionPort.payload.btbLen := (u1.nextPc - u1.pc)(4 downto 1)
```

**Note the load-bearing subtlety:** use `u1.nextPc`, never the local `nextPc`. Getting this
wrong installs the branch *displacement* as the length and produces a wrong `ringKeep` — a
silent-corruption vector that Task 11's A1/A2 cases would catch, but far more cheaply avoided
here.

- [ ] **Step 5: Carry `len` through the ROB**

In `src/main/scala/m68k040/rob/RobPlugin.scala`:

(a) `BranchTrainPayload` (~:90-96) gains a field:
```scala
  case class BranchTrainPayload() extends Bundle {
    val pc       = UInt(32 bits)
    val taken    = Bool()
    val target   = UInt(32 bits)
    val brType   = UInt(2 bits)
    val phtIndex = UInt(11 bits)
    val len      = UInt(4 bits)   // task #126: branch length in words, for the FTB
  }
```

(b) the single write site (~:718-726), after `btrainWr.phtIndex := ...`:
```scala
      btrainWr.len      := branchCompletion.payload.btbLen
```

(c) the retire-time read/drive site (~:913-919), after
`btbUpdateFlow.payload.brType := branchTrainRd.brType`:
```scala
    btbUpdateFlow.payload.len    := branchTrainRd.len
```

- [ ] **Step 6: Make `BtbPlugin` explicitly ignore `len`, and fix its two stale comments**

In `src/main/scala/m68k040/frontend/Btb.scala`:

(a) Fix the stale interface comment (currently line ~34). Replace:
```scala
  *  - queryPc / queryValid : the fetch PC to look up (the issued fetch window base).
```
with:
```scala
  *  - queryPc / queryValid : the PC to look up. NOTE: despite what this comment said until
  *    2026-08-09, this has NEVER been a fetch WINDOW base. `FullCoreSynth` wires it from
  *    `FetchAlignPlugin.logic.btbQueryPc0`, which is `res.slot0.pc` == the ALIGNER's
  *    per-instruction PC (`decodePc`). This table is a DECODE-time, word-granular
  *    predictor. The window-indexed fetch-time table is `FtbPlugin` (`Ftb.scala`).
```

(b) Fix the stale index comment (currently line ~52). Replace:
```scala
    // Word-granular index: PC bit 0 is always 0 for instructions; index off PC bits
    // above the 2-byte word. index = pc[1+idxBits downto 2], tag = pc above that.
```
with:
```scala
    // Word-granular index: PC bit 0 is always 0 for instructions; index off PC bits
    // above the 2-byte word. With idxLo = 1 the index is pc[idxBits downto 1] --
    // i.e. pc[7:1] for the default 128 entries -- and the tag is pc[31:8]. (The
    // previous "pc[1+idxBits downto 2]" here was stale and did not match the code.)
```

(c) Immediately after the `val upd = host[BtbUpdateService].btbUpdate` line (~:209), add:
```scala
    // `upd.payload.len` is DELIBERATELY UNREAD here (task #126, slice 1). A decode-time
    // lookup is always already at an instruction boundary, so this word-granular table
    // needs no length. It is the WINDOW-indexed FtbPlugin that consumes `len`, to know
    // where a predicted branch ENDS inside its 8-byte fetch window.
```

- [ ] **Step 7: Run the test and verify it passes**

```bash
cd ../wt-ftb && ~/sbt/bin/sbt "testOnly m68k040.frontend.BtbPluginSpec"
```
Expected: all tests PASS, including the new one.

- [ ] **Step 8: Prove inertness — the full functional gates**

```bash
cd ../wt-ftb
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec" 2>&1 | tail -20
tools/fuzz/ported-sweep-parallel.sh 4 2>&1 | tail -20
diff <(sort ported_logs_parallel/all_fails.txt) <(sort /tmp/ftb_baseline_fails.txt)
```
Expected: lock-step at Task 2's exact count with an identical fail-name set; `diff` **empty**.
Because nothing reads `len`, any change here is a real bug — investigate, do not rationalise.

- [ ] **Step 9: Commit**

```bash
cd ../wt-ftb
git add src/main/scala/m68k040/services/Services.scala \
        src/main/scala/m68k040/execute/BranchEuPlugin.scala \
        src/main/scala/m68k040/rob/RobPlugin.scala \
        src/main/scala/m68k040/frontend/Btb.scala \
        src/test/scala/m68k040/frontend/BtbPluginSpec.scala
git commit -m "frontend: BtbUpdate carries branch length in words (fetch-directed BTB, slice 1)

Inert plumbing slice: BranchEuPlugin computes len = (u1.nextPc - u1.pc) >> 1 from the
assembler's own fall-through PC, BranchCompletion carries btbLen, RobPlugin's
BranchTrainPayload carries it through completion -> retire, and BtbUpdate.len reaches
the predictors. NOTHING consumes it yet -- BtbPlugin explicitly ignores it (a decode-time
word-granular lookup needs no length). The window-indexed FtbPlugin (next slice) needs it
to truncate a fetched window at the predicted branch's end.

Also fixes two stale Btb.scala comments confirmed wrong against live source: the queryPc
doc claimed 'the issued fetch window base' (it has always been the aligner's per-
instruction PC) and the index comment claimed pc[1+idxBits:2] (the code is pc[idxBits:1]).

Gated: lock-step at baseline with an identical fail set, ported corpus diff-empty.
Spec: docs/superpowers/specs/2026-08-09-ipc-fetch-directed-btb-design.md §2.3."
```

---

### Task 4 (Slice 2a): the FTB table — `Ftb.scala`, parameters, and its unit test

**Files:**
- Create: `src/main/scala/m68k040/frontend/Ftb.scala`
- Modify: `src/main/scala/m68k040/Config.scala` (add `ftbEntries`, `ftqDepth` after `btbEntries`, ~:23)
- Modify: `src/main/scala/m68k040/Global.scala` (add `FTB_ENTRIES`, `FTQ_DEPTH` after `BTB_ENTRIES`, ~:22)
- Modify: `src/main/scala/m68k040/core/ParamPlugin.scala` (set both, after ~:19)
- Test: `src/test/scala/m68k040/frontend/FtbPluginSpec.scala`

**Interfaces:**
- Consumes: `m68k040.services.BtbUpdate.len` (Task 3).
- Produces (Task 6 wires these by exact name):
  - `FtbPlugin.logic.queryPc : UInt(32 bits)` — directionless input, idle-defaulted 0.
  - `FtbPlugin.logic.invalidateAll : Bool` — directionless input, idle-defaulted False.
  - `FtbPlugin.logic.clrValid : Bool`, `FtbPlugin.logic.clrPc : UInt(32 bits)` — directionless
    inputs, idle-defaulted; single-entry valid clear (spec §3.6 L2).
  - Combinational outputs: `hitComb : Bool`, `brWordOffComb : UInt(2 bits)`,
    `brLenComb : UInt(3 bits)`, `targetComb : UInt(32 bits)`, `brTypeComb : UInt(2 bits)`,
    `counterComb : UInt(2 bits)`.
  - `case class FtbEntry(tagBits: Int)` with fields `tag, brWordOff, brLen, target, brType, counter`.

**Plan-time decisions embedded here (do not re-derive):**
- **Allocation identity includes `brWordOff`.** A window can hold up to 4 branches but the
  table holds one entry, so `uEntryHit` (bump the counter) requires tag **and** `brWordOff` to
  match; a different branch in the same window is a fresh allocation. The spec's §2.3 says
  "counter semantics copied verbatim from `Btb.scala`" — this is the one place window
  granularity forces an addition, and omitting it would let two branches in one window
  train each other's counter.
- **`counter` is stored but NOT read by the Phase-1 fetch direction composition.** That is
  deliberate and mirrors today's decode-time behaviour exactly: `slot0PredTaken =
  Mux(condBtbHit0, gsPhtTaken0, btbPredTaken0)`, and for `brType == uncond`
  `btbPredTaken0` is unconditionally true, while for `brType == cond` the counter is
  overridden by gshare — so the BTB counter is already architecturally dead on the composed
  slot-0 path. The field is kept for allocation-policy parity with `BtbPlugin` and because
  Phase 2 may drop the gshare dependence. `FtbPluginSpec` asserts its semantics directly, so
  it is tested rather than dead-and-unverified. **Do not add it to the fetch-time direction
  term** — that would change accuracy relative to the decode-time fallback and break G4
  monotonicity.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/frontend/FtbPluginSpec.scala`:

```scala
package m68k040.frontend

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.services.{BtbUpdateService, BtbUpdate}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Directed FtbPlugin unit test (fetch-directed BTB, task #126, slice 2).
  *
  * Covers: the Phase-1 INSTALL FILTER (1 <= len <= 5 AND brEndOff <= 3 -- a branch whose
  * body leaves its own 8-byte window is NOT installed, so it degrades to the decode-time
  * BTB fallback exactly as spec G4 requires); window-granular indexing (four PCs in one
  * 8-byte window share one entry); the allocation identity (tag AND brWordOff);
  * counter/allocation semantics; invalidateAll; and the single-entry clear (spec §3.6 L2).
  */
class FtbPluginSpec extends AnyFunSuite {

  /** Test-side BtbUpdate provider: a pokeable Flow exposed via BtbUpdateService.
    * Same shape as BtbPluginSpec's -- `flatten.foreach(in(_))` exposes every payload
    * field (including `len`) as a top-level input, so no harness edit is ever needed
    * when the payload grows. */
  class FtbUpdateDriverPlugin extends FiberPlugin with BtbUpdateService {
    val logic = during build new Area {
      val upd = Flow(BtbUpdate())
      in(upd.valid)
      upd.payload.flatten.foreach(in(_))
    }
    override def btbUpdate: Flow[BtbUpdate] = logic.upd
  }

  /** Hoists FtbPlugin's directionless plain-wire ports to top IO inside a Fiber build. */
  class FtbWirePlugin extends FiberPlugin {
    val logic = during build new Area {
      val ftb = host[FtbPlugin]
      val qPc      = in UInt (32 bits)
      val inval    = in Bool ()
      val clrV     = in Bool ()
      val clrP     = in UInt (32 bits)
      ftb.logic.queryPc       := qPc
      ftb.logic.invalidateAll := inval
      ftb.logic.clrValid      := clrV
      ftb.logic.clrPc         := clrP
      val oHit    = out(Bool());        oHit    := ftb.logic.hitComb
      val oOff    = out(UInt(2 bits));  oOff    := ftb.logic.brWordOffComb
      val oLen    = out(UInt(3 bits));  oLen    := ftb.logic.brLenComb
      val oTarget = out(UInt(32 bits)); oTarget := ftb.logic.targetComb
      val oType   = out(UInt(2 bits));  oType   := ftb.logic.brTypeComb
      val oCtr    = out(UInt(2 bits));  oCtr    := ftb.logic.counterComb
    }
  }

  class FtbDut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val drv  = new FtbUpdateDriverPlugin
    val ftb  = new FtbPlugin
    val wire = new FtbWirePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), drv, ftb, wire)) }
  }

  /** Drive one retire-time train pulse. */
  def train(dut: FtbDut, cd: ClockDomain,
            pc: Long, taken: Boolean, target: Long, brType: Int, len: Int): Unit = {
    dut.drv.logic.upd.valid          #= true
    dut.drv.logic.upd.payload.pc     #= pc
    dut.drv.logic.upd.payload.taken  #= taken
    dut.drv.logic.upd.payload.target #= target
    dut.drv.logic.upd.payload.brType #= brType
    dut.drv.logic.upd.payload.len    #= len
    cd.waitSampling()
    dut.drv.logic.upd.valid #= false
    cd.waitSampling()
  }

  /** Read the combinational lookup for `pc`. Returns (hit, off, len, target, brType, ctr). */
  def look(dut: FtbDut, cd: ClockDomain, pc: Long): (Boolean, Int, Int, Long, Int, Int) = {
    dut.wire.logic.qPc #= pc
    cd.waitSampling()
    sleep(1)
    (dut.wire.logic.oHit.toBoolean, dut.wire.logic.oOff.toInt, dut.wire.logic.oLen.toInt,
     dut.wire.logic.oTarget.toLong, dut.wire.logic.oType.toInt, dut.wire.logic.oCtr.toInt)
  }

  def idle(dut: FtbDut, cd: ClockDomain): Unit = {
    dut.drv.logic.upd.valid #= false
    dut.wire.logic.inval    #= false
    dut.wire.logic.clrV     #= false
    dut.wire.logic.clrP     #= 0
    dut.wire.logic.qPc      #= 0
    cd.waitSampling(3)
  }

  test("FTB: install, window-granular hit, and the brEndOff<=3 / len<=5 filter", VerilatorTest) {
    SimConfig.withVerilator.compile(new FtbDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      idle(dut, cd)
      val fails = scala.collection.mutable.ArrayBuffer[String]()

      // (1) A cold window misses.
      { val (h, _, _, _, _, _) = look(dut, cd, 0x1000L)
        if (h) fails += "cold window 0x1000 must MISS" }

      // (2) Install a 1-word unconditional branch at 0x1002 (word offset 1, endOff 1).
      train(dut, cd, 0x1002L, taken = true, target = 0x3000L, brType = 1, len = 1)
      // The lookup is WINDOW-indexed, so ANY pc in [0x1000,0x1008) hits the same entry.
      for (p <- Seq(0x1000L, 0x1002L, 0x1004L, 0x1006L)) {
        val (h, off, len, tgt, ty, _) = look(dut, cd, p)
        if (!h)            fails += f"window hit expected at 0x$p%x"
        if (off != 1)      fails += f"brWordOff at 0x$p%x = $off, want 1"
        if (len != 1)      fails += f"brLen at 0x$p%x = $len, want 1"
        if (tgt != 0x3000L) fails += f"target at 0x$p%x = 0x$tgt%x, want 0x3000"
        if (ty != 1)       fails += f"brType at 0x$p%x = $ty, want 1"
      }
      // A DIFFERENT window must not hit (tag check).
      { val (h, _, _, _, _, _) = look(dut, cd, 0x1008L)
        if (h) fails += "0x1008 is a different window -- must MISS" }

      // (3) brEndOff > 3 is REJECTED: a 3-word branch at word offset 2 ends at word 4.
      train(dut, cd, 0x2004L, taken = true, target = 0x9000L, brType = 1, len = 3)
      { val (h, _, _, _, _, _) = look(dut, cd, 0x2000L)
        if (h) fails += "brEndOff=4 must NOT install (cross-window, Phase 1)" }
      // But the SAME length at offset 0 fits (endOff 2) and DOES install.
      train(dut, cd, 0x2800L, taken = true, target = 0x9100L, brType = 1, len = 3)
      { val (h, off, len, tgt, _, _) = look(dut, cd, 0x2800L)
        if (!h || off != 0 || len != 3 || tgt != 0x9100L)
          fails += s"offset-0 len-3 must install: hit=$h off=$off len=$len tgt=0x${tgt.toHexString}" }

      // (4) len == 0 and len > 5 are rejected.
      train(dut, cd, 0x3000L, taken = true, target = 0xA000L, brType = 1, len = 0)
      { val (h, _, _, _, _, _) = look(dut, cd, 0x3000L)
        if (h) fails += "len=0 must NOT install" }
      train(dut, cd, 0x3800L, taken = true, target = 0xA100L, brType = 1, len = 6)
      { val (h, _, _, _, _, _) = look(dut, cd, 0x3800L)
        if (h) fails += "len=6 must NOT install" }

      assert(fails.isEmpty, "\n" + fails.mkString("\n"))
    }
  }

  test("FTB: counter/allocation semantics and the (tag, brWordOff) allocation identity", VerilatorTest) {
    SimConfig.withVerilator.compile(new FtbDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      idle(dut, cd)
      val fails = scala.collection.mutable.ArrayBuffer[String]()

      // A CONDITIONAL allocation seeds counter := taken ? 2 : 1 (same rule as BtbPlugin).
      train(dut, cd, 0x4000L, taken = true, target = 0x5000L, brType = 0, len = 1)
      { val (_, _, _, _, _, c) = look(dut, cd, 0x4000L)
        if (c != 2) fails += s"cond alloc taken -> counter $c, want 2" }
      // A same-entry taken update bumps toward 3 and saturates.
      train(dut, cd, 0x4000L, taken = true, target = 0x5000L, brType = 0, len = 1)
      { val (_, _, _, _, _, c) = look(dut, cd, 0x4000L); if (c != 3) fails += s"bump -> $c, want 3" }
      train(dut, cd, 0x4000L, taken = true, target = 0x5000L, brType = 0, len = 1)
      { val (_, _, _, _, _, c) = look(dut, cd, 0x4000L); if (c != 3) fails += s"saturate -> $c, want 3" }
      // Not-taken decrements toward 0.
      train(dut, cd, 0x4000L, taken = false, target = 0x5000L, brType = 0, len = 1)
      { val (_, _, _, _, _, c) = look(dut, cd, 0x4000L); if (c != 2) fails += s"dec -> $c, want 2" }
      // An UNCONDITIONAL allocation pegs the counter at 3.
      train(dut, cd, 0x4800L, taken = true, target = 0x5800L, brType = 1, len = 1)
      { val (_, _, _, _, _, c) = look(dut, cd, 0x4800L)
        if (c != 3) fails += s"uncond alloc -> counter $c, want 3" }

      // ALLOCATION IDENTITY: a DIFFERENT branch in the SAME window (same tag, different
      // brWordOff) must REALLOCATE, not bump the incumbent's counter.
      train(dut, cd, 0x4000L, taken = true, target = 0x5000L, brType = 0, len = 1)  // ctr -> 3
      train(dut, cd, 0x4004L, taken = false, target = 0x6000L, brType = 0, len = 1) // new branch
      { val (h, off, tgtOk, c) = { val (h, o, _, t, _, cc) = look(dut, cd, 0x4000L); (h, o, t, cc) }
        if (!h)          fails += "reallocated entry must still hit"
        if (off != 2)    fails += s"brWordOff after realloc = $off, want 2"
        if (tgtOk != 0x6000L) fails += f"target after realloc = 0x$tgtOk%x, want 0x6000"
        if (c != 1)      fails += s"realloc (not-taken cond) counter = $c, want 1 (fresh alloc, NOT a bump)" }

      assert(fails.isEmpty, "\n" + fails.mkString("\n"))
    }
  }

  test("FTB: invalidateAll clears everything; clrValid clears exactly one entry", VerilatorTest) {
    SimConfig.withVerilator.compile(new FtbDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      idle(dut, cd)
      val fails = scala.collection.mutable.ArrayBuffer[String]()

      train(dut, cd, 0x1000L, taken = true, target = 0x7000L, brType = 1, len = 1)
      train(dut, cd, 0x1800L, taken = true, target = 0x7100L, brType = 1, len = 1)
      if (!look(dut, cd, 0x1000L)._1) fails += "0x1000 should be installed"
      if (!look(dut, cd, 0x1800L)._1) fails += "0x1800 should be installed"

      // Single-entry clear (spec §3.6 L2): only the named window loses its valid bit.
      dut.wire.logic.clrV #= true; dut.wire.logic.clrP #= 0x1000L
      cd.waitSampling()
      dut.wire.logic.clrV #= false
      cd.waitSampling()
      if (look(dut, cd, 0x1000L)._1) fails += "clrValid must clear 0x1000's window"
      if (!look(dut, cd, 0x1800L)._1) fails += "clrValid must NOT clear 0x1800's window"

      // invalidateAll clears the rest.
      dut.wire.logic.inval #= true
      cd.waitSampling()
      dut.wire.logic.inval #= false
      cd.waitSampling()
      if (look(dut, cd, 0x1800L)._1) fails += "invalidateAll must clear 0x1800"

      assert(fails.isEmpty, "\n" + fails.mkString("\n"))
    }
  }
}
```

- [ ] **Step 2: Run it and verify it fails**

```bash
cd ../wt-ftb && ~/sbt/bin/sbt "testOnly m68k040.frontend.FtbPluginSpec"
```
Expected: **compile error** — `not found: type FtbPlugin`.

- [ ] **Step 3: Add the elaboration parameters**

`src/main/scala/m68k040/Config.scala` — insert after `btbEntries: Int = 128,`:
```scala
    ftbEntries:   Int = 128,   // fetch-directed BTB: 8-byte-WINDOW-indexed entries (task #126)
    ftqDepth:     Int = 4,     // fetch target queue depth (predictions in flight)
```
`src/main/scala/m68k040/Global.scala` — insert after `val BTB_ENTRIES = Database.blocking[Int]()`:
```scala
  val FTB_ENTRIES     = Database.blocking[Int]()
  val FTQ_DEPTH       = Database.blocking[Int]()
```
`src/main/scala/m68k040/core/ParamPlugin.scala` — insert after `Global.BTB_ENTRIES.set(p.btbEntries)`:
```scala
    Global.FTB_ENTRIES.set(p.ftbEntries)
    Global.FTQ_DEPTH.set(p.ftqDepth)
```

- [ ] **Step 4: Create `Ftb.scala`**

```scala
package m68k040.frontend

import m68k040.Global
import m68k040.services.BtbUpdateService
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** One FTB entry — the WINDOW-indexed fetch-time target buffer (task #126).
  *
  *  - tag       : PC bits above the window index (pc[31:10] for 128 entries).
  *  - brWordOff : the branch's FIRST word within the 8-byte window == brPc[2:1].
  *  - brLen     : the branch's length in WORDS, 1..5 (from BtbUpdate.len).
  *  - target    : the learned taken-target.
  *  - brType    : 0 = cond (Bcc/DBcc), 1 = uncond (BRA/BSR/JMP/JSR).
  *  - counter   : 2-bit saturating bimodal, identical rule to BtbEntry.
  *
  * Derived: brEndOff = brWordOff + brLen - 1, range 0..6. PHASE-1 INSTALL FILTER: an entry
  * is installed only when brEndOff <= 3, i.e. the whole branch lies inside the indexed
  * window. Cross-window branches simply get no FTB entry and fall back to the DECODE-time
  * BtbPlugin path, exactly as today -- that is what makes this lever IPC-monotone by
  * construction (design spec G4). `brLen` is already 3 bits wide for the Phase-2
  * cross-window case, so no retraining or format change is needed later. */
case class FtbEntry(tagBits: Int) extends Bundle {
  val tag       = UInt(tagBits bits)
  val brWordOff = UInt(2 bits)
  val brLen     = UInt(3 bits)
  val target    = UInt(32 bits)
  val brType    = UInt(2 bits)
  val counter   = UInt(2 bits)
}

/** Window-indexed Fetch Target Buffer (fetch-directed BTB, task #126).
  *
  * Answers, for the 8-byte window `FetchAlignPlugin` is ABOUT TO FETCH: "does this window
  * contain a taken control transfer, and if so at which word offset, with what length, to
  * what target?". `FetchAlignPlugin` REGISTERS the answer -- a combinational read feeding
  * `fetchPc` directly is REJECTED (design spec §8.1: this project measured that exact shape
  * at -36.29 MHz / -17.5% in the I-cache prefetch first cut, `349a585`). Do not rebuild it.
  *
  * This is a NEW table, not a re-indexing of `BtbPlugin`: the index granularity (window vs
  * word), the tag width, the payload (brWordOff/brLen) and the allocation policy (one entry
  * per WINDOW, which may contain up to four branches) are all different, and `BtbPlugin`
  * must survive UNCHANGED to serve the decode-time fallback for every branch this table
  * does not cover.
  *
  * Both tables train from the SAME `BtbUpdate` Flow, so they agree on direction by
  * construction.
  *
  * COHERENCE (mandatory, not optional -- design spec §3.7 H7): like `BtbPlugin`, and unlike
  * the RAS/gshare, this table MUST be wired to BOTH `IcachePlugin.logic.invalidateAll` AND
  * `IcachePlugin.logic.maintInvalidateAll`. A prediction is not gated on predecode agreeing
  * the slot is a branch, so a stale entry surviving self-modifying code + a correct CINV
  * could redirect fetch on a non-branch with nothing downstream to verify it.
  *
  * Ports are directionless plain wires with concrete idle defaults (the BtbPlugin/RasPlugin
  * convention): the top-level wiring layer drives the inputs and reads the outputs, so no
  * Fiber build-order cycle is created.
  *
  * Design: docs/superpowers/specs/2026-08-09-ipc-fetch-directed-btb-design.md §2.3. */
class FtbPlugin extends FiberPlugin {

  val logic = during build new Area {
    val entries = Global.FTB_ENTRIES.get
    require((entries & (entries - 1)) == 0, "ftbEntries must be a power of two")
    val idxBits = log2Up(entries)
    // WINDOW-granular index: the I-cache fetch window is 8 bytes, so the index starts at
    // pc bit 3 (BtbPlugin's starts at bit 1). idx = pc[9:3], tag = pc[31:10] for 128
    // entries -- 1 KiB of code coverage, 4x BtbPlugin's 256 B, deliberately.
    val idxLo   = 3
    val idxHi   = idxLo + idxBits - 1
    val tagLo   = idxHi + 1
    val tagBits = 32 - tagLo

    def idxOf(pc: UInt): UInt = pc(idxHi downto idxLo)
    def tagOf(pc: UInt): UInt = pc(31 downto tagLo)

    // ---- storage: valids in a Reg Vec (one-cycle invalidate AND a free second writer for
    //      the single-entry clear -- no second Mem write port), payload in async LUTRAM ----
    val valids = Vec.fill(entries)(RegInit(False))
    val mem    = Mem(FtbEntry(tagBits), entries)

    // ---- ports (directionless plain wires, concrete idle defaults) ----
    val queryPc = UInt(32 bits)
    queryPc.allowOverride; queryPc := U(0, 32 bits)
    val invalidateAll = Bool()
    invalidateAll.allowOverride; invalidateAll := False
    // Single-entry valid clear (design spec §3.6 L2, MANDATORY not merely recommended):
    // on a confirm mismatch FetchAlign clears the offending window's valid bit. Without
    // it, a branch whose real decode is COMPLEX, or one that permanently straddles a
    // boundary, would mismatch on EVERY occurrence -- a persistent 4-cycle penalty on top
    // of the un-predicted cost, i.e. strictly WORSE than today, violating G4.
    val clrValid = Bool()
    clrValid.allowOverride; clrValid := False
    val clrPc = UInt(32 bits)
    clrPc.allowOverride; clrPc := U(0, 32 bits)

    // ---- combinational lookup (async LUTRAM read + tag compare) ----
    // The RESULT IS REGISTERED BY THE CONSUMER, not here -- see the class comment and
    // design spec §8.1.
    val qIdx  = idxOf(queryPc)
    val qEnt  = mem.readAsync(qIdx)
    val hitComb       = valids(qIdx) && (qEnt.tag === tagOf(queryPc))
    val brWordOffComb = qEnt.brWordOff
    val brLenComb     = qEnt.brLen
    val targetComb    = qEnt.target
    val brTypeComb    = qEnt.brType
    // `counterComb` is EXPOSED and TESTED (FtbPluginSpec) but is DELIBERATELY NOT part of
    // the Phase-1 fetch-time direction composition in FetchAlignPlugin. That mirrors the
    // decode-time path exactly: `slot0PredTaken = Mux(condBtbHit0, gsPhtTaken0,
    // btbPredTaken0)` -- for brType==uncond the BTB is force-taken regardless of the
    // counter, and for brType==cond gshare overrides it -- so the bimodal counter is
    // already architecturally dead on the composed slot-0 path today. Keeping the field
    // preserves allocation-policy parity with BtbPlugin and leaves Phase 2 free to drop
    // the gshare dependence. Adding it to the fetch-time direction term would change
    // accuracy relative to the decode-time fallback and break G4 monotonicity.
    val counterComb   = qEnt.counter
    hitComb.simPublic(); brWordOffComb.simPublic(); brLenComb.simPublic()
    targetComb.simPublic(); brTypeComb.simPublic(); counterComb.simPublic()

    // ---- retire-time install/update (the SAME BtbUpdate Flow BtbPlugin consumes) ----
    val upd     = host[BtbUpdateService].btbUpdate
    val uIdx    = idxOf(upd.payload.pc)
    val uTag    = tagOf(upd.payload.pc)
    val uOff    = upd.payload.pc(2 downto 1)          // the branch's word offset in its window
    val uLen    = upd.payload.len                     // UInt(4 bits), words
    val uOld    = mem.readAsync(uIdx)
    // brEndOff = brWordOff + brLen - 1, computed width-extended so it cannot wrap.
    val uEndOff = (uOff.resize(4) +^ uLen) - U(1, 5 bits)
    // PHASE-1 INSTALL FILTER: a representable length (1..5 words -- 5 is the longest
    // BTB-eligible branch encoding) AND the whole branch inside the indexed window.
    val uLenOk    = (uLen >= U(1, 4 bits)) && (uLen <= U(5, 4 bits))
    val uFitsWin  = uEndOff <= U(3, 5 bits)
    val uInstall  = upd.valid && uLenOk && uFitsWin

    // Allocation identity: tag AND brWordOff. A window holds up to four branches but the
    // table holds ONE entry, so a DIFFERENT branch in the same window is a fresh
    // allocation, not a counter bump on the incumbent. (This is the one place window
    // granularity forces an addition to BtbPlugin's rule; everything else below is copied
    // verbatim from Btb.scala's cInc/cDec/ctrAlloc.)
    val uEntryHit = valids(uIdx) && (uOld.tag === uTag) && (uOld.brWordOff === uOff)

    val cInc = Mux(uOld.counter === U(3, 2 bits), U(3, 2 bits), (uOld.counter + 1).resized)
    val cDec = Mux(uOld.counter === U(0, 2 bits), U(0, 2 bits), (uOld.counter - 1).resized)
    val ctrUpdated = Mux(upd.payload.taken, cInc, cDec)
    val ctrAlloc   = Mux(upd.payload.brType === U(1, 2 bits), U(3, 2 bits),
                     Mux(upd.payload.taken, U(2, 2 bits), U(1, 2 bits)))

    val newEntry = FtbEntry(tagBits)
    newEntry.tag       := uTag
    newEntry.brWordOff := uOff
    newEntry.brLen     := uLen.resize(3)
    newEntry.target    := upd.payload.target
    newEntry.brType    := upd.payload.brType
    newEntry.counter   := Mux(uEntryHit, ctrUpdated, ctrAlloc)

    val memWrEn = Bool(); memWrEn := False
    mem.write(uIdx, newEntry, enable = memWrEn)
    when(uInstall) {
      memWrEn      := True
      valids(uIdx) := True
    }

    // ---- single-entry clear (L2) and invalidateAll, in that priority order ----
    when(clrValid) {
      valids(idxOf(clrPc)) := False
    }
    when(invalidateAll) {
      for (i <- 0 until entries) valids(i) := False
    }
  }
}
```

- [ ] **Step 5: Run the tests and verify they pass**

```bash
cd ../wt-ftb && ~/sbt/bin/sbt "testOnly m68k040.frontend.FtbPluginSpec"
```
Expected: all three tests PASS.

- [ ] **Step 6: Confirm the rest of the design is untouched**

```bash
cd ../wt-ftb
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.frontend.*"
```
Expected: clean compile; all frontend specs pass. `FtbPlugin` is not instantiated by any
full-core DUT yet, so the heavier suites cannot move — but run lock-step anyway, cheaply, to
confirm the `Config`/`Global`/`ParamPlugin` edits did not disturb elaboration:
```bash
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec" 2>&1 | tail -20
```
Expected: Task 2's exact count and fail-name set.

- [ ] **Step 7: Commit**

```bash
cd ../wt-ftb
git add src/main/scala/m68k040/frontend/Ftb.scala \
        src/main/scala/m68k040/Config.scala \
        src/main/scala/m68k040/Global.scala \
        src/main/scala/m68k040/core/ParamPlugin.scala \
        src/test/scala/m68k040/frontend/FtbPluginSpec.scala
git commit -m "frontend: window-indexed Fetch Target Buffer table (FtbPlugin)

New 128-entry table indexed by 8-byte FETCH WINDOW (pc[9:3], tag pc[31:10]) carrying
{brWordOff, brLen, target, brType, counter}, trained from the SAME BtbUpdate flow as
BtbPlugin so the two agree on direction by construction. Phase-1 install filter:
1 <= len <= 5 AND brEndOff <= 3, so a cross-window branch simply gets no entry and falls
back to the unchanged decode-time BtbPlugin path (IPC-monotone by construction, spec G4).

Allocation identity is (tag, brWordOff), not tag alone -- a window can hold four branches
but the table holds one entry, so a different branch in the same window REALLOCATES rather
than bumping the incumbent's counter.

Lookup is combinational here and REGISTERED BY THE CONSUMER; a combinational read feeding
fetchPc is rejected outright (spec §8.1 -- this project measured that shape at -36.29 MHz).

Not instantiated by any DUT yet. Spec §2.3."
```

---

### Task 5 (Slice 2b): gshare 4-way speculative PHT read + the fold-linearity proof

**Files:**
- Modify: `src/main/scala/m68k040/frontend/Gshare.scala` (add ports + reads after the
  existing `phtTaken0/1` block, ~:92-98)
- Test: `src/test/scala/m68k040/frontend/GshareSpecReadSpec.scala` (create)

**Interfaces:**
- Produces (Task 6 wires these by exact name):
  - `GsharePlugin.logic.specQueryPc : UInt(32 bits)` — directionless input, idle-defaulted 0.
    Driven with `fetchPc` (the 8-byte window base).
  - `GsharePlugin.logic.specIdxBase : UInt(idxBits bits)` (11) — `indexOf(window base)`.
  - `GsharePlugin.logic.specTaken : Vec(Bool(), 4)` — `pht[specIdxBase ^ k] >= 2`, k = 0..3.

**Why this shape (spec §2.6):** a serial `FTB RAM → fold/XOR → PHT RAM` chain is exactly the
2-RAM-in-series shape that cost -36.29 MHz in the I-cache prefetch first cut. Instead exploit
the **linearity of the fold**. `fold` XOR-reduces its argument in `idxBits`-wide chunks, so
for `B = {fetchPc[31:3], brWordOff, 1'b0}` the low chunk of `fold(B[31:1])` is
`{fetchPc[11:3], brWordOff}` and every higher chunk depends only on `fetchPc[31:12]`.
Therefore `indexOf(B) == indexOf({fetchPc[31:3], 3'b0}) ^ brWordOff`. All four candidate PHT
reads launch **from registers in parallel** with the FTB RAM read and are late-selected by
`brWordOff` — structurally the same late-select pattern Lever D proved, at 4 ways instead of
9, on a 2-bit-wide RAM instead of a 60-bit one, and **replacing** Lever D's 9 ways.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/frontend/GshareSpecReadSpec.scala`:

```scala
package m68k040.frontend

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Fold-linearity proof for the 4-way speculative PHT read (fetch-directed BTB, task #126).
  *
  * THE PROPERTY UNDER TEST -- this is the entire correctness basis for reading the PHT off
  * the fetch WINDOW base instead of the branch PC, and thus for keeping the FTB RAM and the
  * PHT RAM in PARALLEL rather than in series:
  *
  *     indexOf({base[31:3], k, 1'b0})  ==  indexOf({base[31:3], 3'b0}) ^ k     for k = 0..3
  *
  * i.e. `specIdxBase ^ k` is the EXACT index a per-branch-PC lookup would have used, so
  * `phtIndex` carried down the FTQ trains the exact entry the lookup read (the discipline
  * Services.scala's GshareUpdate comment already mandates).
  *
  * Collect-then-assert, never abort at the first mismatch (standing project rule: that flaw
  * is what makes PredecodeWordSpec's "exhaustive" claim untrustworthy). */
class GshareSpecReadSpec extends AnyFunSuite {

  /** Hoists GsharePlugin's plain-wire ports to top IO inside a Fiber build. */
  class GsWirePlugin extends FiberPlugin {
    val logic = during build new Area {
      val gs = host[GsharePlugin]
      val qPc0     = in UInt (32 bits)
      val specPc   = in UInt (32 bits)
      val shiftV   = in Bool ()
      val shiftD   = in Bool ()
      gs.logic.queryPc0    := qPc0
      gs.logic.queryValid0 := True
      gs.logic.specQueryPc := specPc
      gs.logic.shiftValid  := shiftV
      gs.logic.shiftDir    := shiftD
      val oIdx0    = out(UInt(11 bits)); oIdx0    := gs.logic.phtIndex0
      val oTaken0  = out(Bool());        oTaken0  := gs.logic.phtTaken0
      val oIdxBase = out(UInt(11 bits)); oIdxBase := gs.logic.specIdxBase
      val oSpec    = out(Vec(Bool(), 4))
      for (k <- 0 until 4) oSpec(k) := gs.logic.specTaken(k)
    }
  }

  class GsDut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val gs   = new GsharePlugin
    val wire = new GsWirePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), gs, wire)) }
  }

  test("gshare: indexOf(base|k) === specIdxBase ^ k, and specTaken(k) === phtTaken(base|k)", VerilatorTest) {
    SimConfig.withVerilator.compile(new GsDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.wire.logic.shiftV #= false
      dut.wire.logic.shiftD #= false
      dut.wire.logic.qPc0   #= 0
      dut.wire.logic.specPc #= 0
      cd.waitSampling(3)

      val rnd   = new scala.util.Random(0x126)
      val fails = scala.collection.mutable.ArrayBuffer[String]()
      var checks = 0

      // 512 random window bases x 4 offsets, re-randomizing the GHR every 16 bases so the
      // `fold(ghr)` term genuinely varies (it is address-independent and must cancel).
      for (i <- 0 until 512) {
        if (i % 16 == 0) {
          // Shift 16 random bits into the GHR (ghrBits = 16), one per cycle.
          for (_ <- 0 until 16) {
            dut.wire.logic.shiftV #= true
            dut.wire.logic.shiftD #= rnd.nextBoolean()
            cd.waitSampling()
          }
          dut.wire.logic.shiftV #= false
          cd.waitSampling()
        }
        val base = (rnd.nextLong() & 0xFFFFFFF8L) & 0xFFFFFFFFL
        dut.wire.logic.specPc #= base
        for (k <- 0 until 4) {
          val brPc = base + 2L * k
          dut.wire.logic.qPc0 #= brPc
          cd.waitSampling()
          sleep(1)
          val idxDirect = dut.wire.logic.oIdx0.toInt
          val idxSpec   = dut.wire.logic.oIdxBase.toInt ^ k
          if (idxDirect != idxSpec) {
            fails += f"base=0x$base%08x k=$k: indexOf(brPc)=$idxDirect specIdxBase^k=$idxSpec"
          }
          val takenDirect = dut.wire.logic.oTaken0.toBoolean
          val takenSpec   = dut.wire.logic.oSpec(k).toBoolean
          if (takenDirect != takenSpec) {
            fails += f"base=0x$base%08x k=$k: phtTaken0=$takenDirect specTaken($k)=$takenSpec"
          }
          checks += 2
        }
      }
      info(s"checked $checks assertions over 512 bases x 4 offsets with a re-randomized GHR")
      assert(fails.isEmpty, s"${fails.size} fold-linearity mismatches:\n" + fails.take(30).mkString("\n"))
    }
  }
}
```

- [ ] **Step 2: Run it and verify it fails**

```bash
cd ../wt-ftb && ~/sbt/bin/sbt "testOnly m68k040.frontend.GshareSpecReadSpec"
```
Expected: **compile error** — `value specQueryPc is not a member of ...`.

- [ ] **Step 3: Add the speculative read to `Gshare.scala`**

Insert immediately after the existing `phtTaken0.simPublic(); phtTaken1.simPublic()` line
(~:98), and add `specQueryPc` to the port block above (~:84-90) alongside `queryPc0`:

```scala
    // ── fetch-directed BTB (task #126): 4-way SPECULATIVE window read ─────────────
    // `specQueryPc` is the 8-byte FETCH WINDOW base (FetchAlignPlugin's `fetchPc`), a
    // plain register. The window's four candidate branch PCs are {base|0, base|2, base|4,
    // base|6}, and by the LINEARITY of `fold` (an XOR reduction over idxBits-wide chunks)
    //
    //     indexOf({base[31:3], k, 1'b0}) == indexOf({base[31:3], 3'b0}) ^ k
    //
    // because the low chunk of fold(B[31:1]) is {base[11:3], k} and every higher chunk --
    // and the whole fold(ghr) term -- is independent of k. So all four PHT reads launch
    // FROM REGISTERS IN PARALLEL with the FTB RAM read and are late-selected by the FTB's
    // `brWordOff`. This deliberately avoids a serial `FTB RAM -> fold/XOR -> PHT RAM`
    // chain: that 2-RAM-in-series shape is exactly what cost -36.29 MHz in the I-cache
    // prefetch first cut (`349a585`). It is the same late-select pattern Lever D proved,
    // at 4 ways instead of 9 and on a 2-bit-wide RAM instead of a 60-bit one -- and it
    // REPLACES Lever D's 9 ways, so it is LUTRAM-negative.
    //
    // Proven RTL-against-RTL by `GshareSpecReadSpec`.
    // Spec: docs/superpowers/specs/2026-08-09-ipc-fetch-directed-btb-design.md §2.6.
    val specIdxBase = indexOf(specQueryPc(31 downto 3) @@ U(0, 3 bits))
    val specTaken   = Vec(Bool(), 4)
    for (k <- 0 until 4) {
      specTaken(k) := (pht.readAsync(specIdxBase ^ U(k, idxBits bits)) >= U(2, 2 bits))
    }
    specIdxBase.simPublic(); specTaken.simPublic()
```

and in the port block (~:84-90), after the `queryValid1` line:
```scala
    val specQueryPc = UInt(32 bits); specQueryPc.allowOverride; specQueryPc := U(0, 32 bits)
```

- [ ] **Step 4: Run the test and verify it passes**

```bash
cd ../wt-ftb && ~/sbt/bin/sbt "testOnly m68k040.frontend.GshareSpecReadSpec"
```
Expected: PASS, with the `info` line reporting 4096 checked assertions.

- [ ] **Step 5: Confirm the existing gshare behaviour is untouched**

```bash
cd ../wt-ftb
~/sbt/bin/sbt "testOnly m68k040.frontend.GshareSpec"
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec" 2>&1 | tail -20
```
Expected: `GshareSpec` fully green; lock-step at Task 2's count and fail set. The new reads
are read-only additions with no consumer yet, so any movement is a real bug.

- [ ] **Step 6: Commit**

```bash
cd ../wt-ftb
git add src/main/scala/m68k040/frontend/Gshare.scala \
        src/test/scala/m68k040/frontend/GshareSpecReadSpec.scala
git commit -m "frontend: gshare 4-way speculative window PHT read (fetch-directed BTB)

Adds specQueryPc (the 8-byte fetch window base) plus specIdxBase and specTaken(0..3),
exploiting the linearity of the XOR fold: indexOf(base|k) == indexOf(base) ^ k. All four
candidate PHT reads therefore launch from REGISTERS IN PARALLEL with the FTB RAM read and
are late-selected by the FTB's brWordOff, instead of a serial FTB->fold->PHT chain -- the
2-RAM-in-series shape that cost this design -36.29 MHz in the I-cache prefetch first cut.

Proven RTL-against-RTL by GshareSpecReadSpec (512 random window bases x 4 offsets with a
re-randomized GHR, collect-then-assert). No consumer yet; existing gshare behaviour and
the lock-step baseline are unchanged. Spec §2.6."
```

---

### Task 6 (Slice 2c): registered FTB lookup in `FetchAlignPlugin` + wire into all four DUTs — inert

**Files:**
- Modify: `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala` (new port block after the
  gshare port block ~:129; new registered-result block after the `pendingDrop`/`simPublic`
  line ~:199)
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala` (after the BTB wiring, ~:84-102;
  add `new m68k040.frontend.FtbPlugin()` to the plugin list ~:420)
- Modify: `src/test/scala/m68k040/fuzz/FuzzDut.scala` (~:166-198 wiring, ~:265 instantiation)
- Modify: `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` (~:245-281, ~:369)
- Modify: `src/test/scala/m68k040/bench/IpcBenchSpec.scala` (~:180-216, ~:278)

**Interfaces:**
- Consumes: `FtbPlugin.logic.{queryPc, invalidateAll, clrValid, clrPc, hitComb,
  brWordOffComb, brLenComb, targetComb, brTypeComb}` (Task 4);
  `GsharePlugin.logic.{specQueryPc, specIdxBase, specTaken}` (Task 5).
- Produces (Tasks 7-10 consume these by exact name, all inside `FetchAlignPlugin.logic`):
  - `ftbQueryPc : UInt(32 bits)` — DRIVEN here (`:= fetchPc`).
  - `ftbHitIn, ftbBrWordOffIn, ftbBrLenIn, ftbTargetIn, ftbBrTypeIn` — combinational INPUTS.
  - `gsSpecIdxBaseIn : UInt(11 bits)`, `gsSpecTakenIn : Vec(Bool(), 4)` — combinational INPUTS.
  - `ftbClrValid : Bool`, `ftbClrPc : UInt(32 bits)` — DRIVEN here (default False/0).
  - Registered result: `ftbResHit, ftbResBrWordOff, ftbResBrLen, ftbResTarget, ftbResBrType,
    ftbResPhtIdxBase, ftbResPhtTaken (Vec 4)`, plus `ftbResFresh : Bool`.
  - `ftbEnable : Bool` (a self-assigned `RegInit(False)`, `simPublic`).
  - `ftbTakenSpec : Bool` — the composed fetch-time predict-taken.

> **Inert by construction:** nothing reads `ftbTakenSpec` yet. The gate is therefore
> "bit-identical behaviour", and any movement is a real bug.

- [ ] **Step 1: Add the FTB/gshare-spec port block to `FetchAlignPlugin`**

Insert immediately after the existing gshare port block's last line
(`val gsShiftDir = Bool()`, ~:132):

```scala
    // ── Fetch-directed BTB interface (task #126) ─────────────────────────────────
    // FetchAlign DRIVES `ftbQueryPc` with `fetchPc` (the 8-byte window it is about to
    // fetch) and `gsSpecQueryPc` with the same value; the FtbPlugin/GsharePlugin return
    // COMBINATIONAL results, which are REGISTERED here (see `ftbRes*` below). A
    // combinational read feeding `fetchPc` directly is REJECTED -- design spec §8.1; this
    // project measured that exact shape at -36.29 MHz (`349a585`). Directionless plain
    // wires with concrete idle defaults (the BtbPlugin convention) so FetchAlign
    // elaborates standalone with the FTB inert (hit reads False -> no prediction ever).
    val ftbQueryPc      = UInt(32 bits)
    val ftbHitIn        = Bool();        ftbHitIn.allowOverride;        ftbHitIn        := False
    val ftbBrWordOffIn  = UInt(2 bits);  ftbBrWordOffIn.allowOverride;  ftbBrWordOffIn  := U(0, 2 bits)
    val ftbBrLenIn      = UInt(3 bits);  ftbBrLenIn.allowOverride;      ftbBrLenIn      := U(0, 3 bits)
    val ftbTargetIn     = UInt(32 bits); ftbTargetIn.allowOverride;     ftbTargetIn     := U(0, 32 bits)
    val ftbBrTypeIn     = UInt(2 bits);  ftbBrTypeIn.allowOverride;     ftbBrTypeIn     := U(0, 2 bits)
    val gsSpecIdxBaseIn = UInt(11 bits); gsSpecIdxBaseIn.allowOverride; gsSpecIdxBaseIn := U(0, 11 bits)
    val gsSpecTakenIn   = Vec(Bool(), 4)
    gsSpecTakenIn.foreach { b => b.allowOverride; b := False }
    // Single-entry FTB valid clear, driven by the confirm-mismatch path (spec §3.6 L2).
    // Defaults here; Task 10 overrides them inside the mismatch `when`.
    val ftbClrValid = Bool(); ftbClrValid := False
    val ftbClrPc    = UInt(32 bits); ftbClrPc := U(0, 32 bits)
    ftbQueryPc := fetchPc
```

**Note:** `ftbQueryPc := fetchPc` must come *after* `fetchPc`'s declaration (~:136), so place
this whole block after the state-register declarations rather than with the other port blocks
if the compiler objects — the plan's canonical insertion point is immediately after the
`spinal.core.sim.SimPublic(recValid, ringCount, ringHead, ringTail, pendingDrop)` line (~:199).

- [ ] **Step 2: Add the registered result, freshness, `ftbEnable` and the composed direction**

Insert immediately after the block from Step 1:

```scala
    // ── FTB lookup result: REGISTERED, with VALUE-BASED freshness ────────────────
    // `ftbRes*` is the lookup of `fetchPc` AS OF THE PREVIOUS CYCLE. `fetchPc` is a plain
    // register whose only other input is +8, and fetch runs up to RING(3) windows plus ~5
    // IBuf windows ahead of decode, so a one-cycle-late result costs nothing
    // architecturally.
    //
    // FRESHNESS IS DEFINED BY VALUE, NOT BY EVENT. `ftbResFresh` is a plain 32-bit
    // register-to-register compare (~2 LUT levels), NOT a list of "did any of these
    // events fire". That is deliberate and load-bearing: in a tight loop whose target
    // lands in the SAME window -- the `hot-loop` kernel, whose entire body is one 8-byte
    // window -- `fetchPc` is re-assigned to the value it ALREADY HELD, so the value
    // compare stays true and the loop predicts on EVERY iteration. An event-based
    // freshness bit would go false there and lose the single biggest win.
    // Spec §2.4.
    val fetchPcPrev       = RegNext(fetchPc) init 0
    val ftbResHit         = RegNext(ftbHitIn) init False
    val ftbResBrWordOff   = RegNext(ftbBrWordOffIn) init 0
    val ftbResBrLen       = RegNext(ftbBrLenIn) init 0
    val ftbResTarget      = RegNext(ftbTargetIn) init 0
    val ftbResBrType      = RegNext(ftbBrTypeIn) init 0
    val ftbResPhtIdxBase  = RegNext(gsSpecIdxBaseIn) init 0
    val ftbResPhtTaken    = Vec(Bool(), 4)
    for (k <- 0 until 4) ftbResPhtTaken(k) := RegNext(gsSpecTakenIn(k)) init False
    val ftbResFresh       = fetchPcPrev === fetchPc
    spinal.core.sim.SimPublic(ftbResHit, ftbResFresh, ftbResBrWordOff, ftbResBrLen, ftbResTarget)

    // Runtime enable. MUST be a SELF-ASSIGNED RegInit, never `in Bool()` inside a plugin
    // Area (recorded trap: that makes a top-level port where `default(...)` silently does
    // nothing, and every full-core testbench then reads 0 -- cost last time was one full
    // IPC sweep that came back bit-identical and looked like "the feature does nothing").
    // The self-assignment also keeps it out of the "UNASSIGNED REGISTER" synth check.
    // NOTE it gates ONLY `applyPrediction`. It deliberately does NOT gate the FTQ, the
    // availEff clamp, or the confirm/mismatch logic: poking it off with predictions in
    // flight must never strand a live splice with nothing describing it (spec §4 F7).
    val ftbEnable = RegInit(False); ftbEnable := ftbEnable; ftbEnable.simPublic()

    // Composed fetch-time direction (spec §2.6): unconditionals are force-taken; a
    // CONDITIONAL takes its direction from the speculative PHT read, late-selected by the
    // FTB's brWordOff -- exactly mirroring the decode-time `Mux(condBtbHit0, gsPhtTaken0,
    // btbPredTaken0)` composition. The FTB's own bimodal `counter` is deliberately NOT
    // read here; see Ftb.scala's `counterComb` comment for why (it is architecturally
    // dead on the decode-time composed path too).
    val ftbIsCond    = ftbResBrType === U(0, 2 bits)
    val ftbTakenSpec = ftbResHit && (!ftbIsCond || ftbResPhtTaken(ftbResBrWordOff))
    // The fetch-time PHT index actually READ, carried down the FTQ so retire trains the
    // EXACT entry (Services.scala's GshareUpdate comment mandates this discipline).
    val ftbPhtIdx    = ftbResPhtIdxBase ^ ftbResBrWordOff.resize(11)
```

- [ ] **Step 3: Wire the plugin into `FullCoreSynth`**

In `src/main/scala/m68k040/top/FullCoreSynth.scala`, immediately after
`fa.logic.btbPredTarget1 := btb.logic.predTarget2Comb` (~:102), add:

```scala
    // ── Fetch-directed BTB wiring (task #126) ────────────────────────────────────
    // The FTB is queried with the FETCH WINDOW BASE (`fetchPc`), not an instruction PC.
    // Its invalidate is wired to BOTH sources exactly like the BTB's, and for the SAME
    // reason (IcachePlugin's maintInvalidateAll comment): a fetch-time prediction is not
    // gated on predecode agreeing the slot is a branch, so a stale entry surviving SMC +
    // a correct CINV could redirect fetch on a non-branch. This is MANDATORY, not
    // optional (design spec §3.7 H7).
    val ftb = host[m68k040.frontend.FtbPlugin]
    ftb.logic.invalidateAll := host[IcachePlugin].logic.invalidateAll ||
                               host[IcachePlugin].logic.maintInvalidateAll
    ftb.logic.queryPc  := fa.logic.ftbQueryPc
    ftb.logic.clrValid := fa.logic.ftbClrValid
    ftb.logic.clrPc    := fa.logic.ftbClrPc
    fa.logic.ftbHitIn       := ftb.logic.hitComb
    fa.logic.ftbBrWordOffIn := ftb.logic.brWordOffComb
    fa.logic.ftbBrLenIn     := ftb.logic.brLenComb
    fa.logic.ftbTargetIn    := ftb.logic.targetComb
    fa.logic.ftbBrTypeIn    := ftb.logic.brTypeComb
```

and, after the existing gshare read-port wiring (the `gsh.logic.queryPc1`/`gsBtbType1` group,
~:127-138), add:
```scala
    // 4-way speculative window PHT read for the fetch-time direction (task #126).
    gsh.logic.specQueryPc := fa.logic.ftbQueryPc
    fa.logic.gsSpecIdxBaseIn := gsh.logic.specIdxBase
    for (k <- 0 until 4) fa.logic.gsSpecTakenIn(k) := gsh.logic.specTaken(k)
```

and add `new m68k040.frontend.FtbPlugin(),` to the plugin `Seq` immediately after
`new m68k040.frontend.GsharePlugin(),` (~:420).

- [ ] **Step 4: Replicate the identical wiring in the three test DUTs**

Apply the **same three blocks** (FTB wiring, gshare spec wiring, plugin instantiation) to:
- `src/test/scala/m68k040/fuzz/FuzzDut.scala` — wiring after ~:180, gshare after ~:198,
  instantiation `val ftb = new m68k040.frontend.FtbPlugin` after ~:267 and into the plugin Seq.
- `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` — wiring after ~:259,
  gshare after ~:281, instantiation after ~:371. Use `faBtb` as the FetchAlign handle (that
  is the local name in this file).
- `src/test/scala/m68k040/bench/IpcBenchSpec.scala` — wiring after ~:194, gshare after ~:216,
  instantiation after ~:280. Use `fa` as the handle.

In each file the FTB block reads (adjusting only the FetchAlign handle name):
```scala
    val ftb = host[m68k040.frontend.FtbPlugin]
    ftb.logic.invalidateAll := host[IcachePlugin].logic.invalidateAll ||
                               host[IcachePlugin].logic.maintInvalidateAll
    ftb.logic.queryPc  := faBtb.logic.ftbQueryPc
    ftb.logic.clrValid := faBtb.logic.ftbClrValid
    ftb.logic.clrPc    := faBtb.logic.ftbClrPc
    faBtb.logic.ftbHitIn       := ftb.logic.hitComb
    faBtb.logic.ftbBrWordOffIn := ftb.logic.brWordOffComb
    faBtb.logic.ftbBrLenIn     := ftb.logic.brLenComb
    faBtb.logic.ftbTargetIn    := ftb.logic.targetComb
    faBtb.logic.ftbBrTypeIn    := ftb.logic.brTypeComb
    gsh.logic.specQueryPc := faBtb.logic.ftbQueryPc
    faBtb.logic.gsSpecIdxBaseIn := gsh.logic.specIdxBase
    for (k <- 0 until 4) faBtb.logic.gsSpecTakenIn(k) := gsh.logic.specTaken(k)
```

**Do not skip any of the four.** The FTB must be present in every full-core DUT or the later
IPC/lock-step/fuzz gates measure a different design than the one that synthesises.

- [ ] **Step 5: Verify elaboration and inertness**

```bash
cd ../wt-ftb
~/sbt/bin/sbt compile
JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog" 2>&1 | tail -20
```
Expected: 0 errors, and **no "UNASSIGNED REGISTER" warnings**. Then confirm the FTB is
genuinely present and not const-folded away:
```bash
grep -c "FtbPlugin" generated/M68kFullCoreSynth.v
```
Expected: a non-zero count.

- [ ] **Step 6: Full functional gates — the inertness proof**

```bash
cd ../wt-ftb
~/sbt/bin/sbt "testOnly m68k040.frontend.*"
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec" 2>&1 | tail -20
tools/fuzz/ported-sweep-parallel.sh 4 2>&1 | tail -20
diff <(sort ported_logs_parallel/all_fails.txt) <(sort /tmp/ftb_baseline_fails.txt)
```
Expected: lock-step at Task 2's exact count and fail set; `diff` **empty**.
`ftbEnable` defaults False and nothing reads `ftbTakenSpec`, so the design is behaviourally
identical. Any movement is a wiring bug (most likely a double-drive or a missed
`allowOverride`).

- [ ] **Step 7: IPC sanity check (cheap, 1 seed, both models)**

```bash
cd ../wt-ftb
IPC_SEED=1 IPC_MEM=zero   JAVA_OPTS=-Xmx6g ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' 2>&1 | grep -E 'AGGREGATE'
IPC_SEED=1 IPC_MEM=l2:5:70 JAVA_OPTS=-Xmx6g ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' 2>&1 | grep -E 'AGGREGATE'
```
Expected: **bit-identical** to Task 2's seed-1 numbers. (This is the one slice where
"bit-identical" is the *desired* answer — contrast with the recorded trap where it meant the
feature was silently disabled. Here it is the inertness proof, and it is confirmed by the
`grep -c FtbPlugin` in Step 5 that the hardware really is present.)

- [ ] **Step 8: Post-route + LUT gate**

Check contention (`free -g`, `ps aux | grep -i vivado`) and wait for a window, then:
```bash
cd ../wt-ftb
JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
vivado -mode batch -nojournal -log synth/vivado_FullCore.log -source synth/impl_FullCore.tcl
```
Report WNS / FMax / CLB LUTs / LUT-as-Memory / FFs against Task 1's census. Expected: FMax
within noise of 221.828 MHz; LUT-as-Memory **up** by roughly one 128-entry × 63-bit table plus
4 small PHT read ports; FFs up by ~128 (FTB valids) + ~80 (registered result). This is the
*cost* half of the budget; the *credit* (Lever D's -1302 LUTs / -512 LUTRAM) does not land
until Task 15, so a temporary LUT increase here is expected and acceptable — **record it
precisely** so Task 15's net can be computed.

- [ ] **Step 9: Commit**

```bash
cd ../wt-ftb
git add src/main/scala/m68k040/frontend/FetchAlignPlugin.scala \
        src/main/scala/m68k040/top/FullCoreSynth.scala \
        src/test/scala/m68k040/fuzz/FuzzDut.scala \
        src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala \
        src/test/scala/m68k040/bench/IpcBenchSpec.scala
git commit -m "frontend: registered FTB lookup + gshare spec read in FetchAlign (inert)

Wires FtbPlugin and the gshare 4-way speculative window read into FetchAlignPlugin and all
four full-core DUTs (FullCoreSynth, FuzzDut, ExecuteLockStepSpec, IpcBenchSpec), queried
with fetchPc -- the 8-byte window about to be fetched. The combinational result is
REGISTERED (ftbRes*), with freshness defined BY VALUE (RegNext(fetchPc) === fetchPc) rather
than by event, so a loop whose target lands in the SAME window still predicts every
iteration -- which is exactly the hot-loop kernel.

FTB invalidate is wired to BOTH invalidateAll and maintInvalidateAll, mandatory for the
same reason as the BTB's (spec §3.7 H7).

ftbEnable is a self-assigned RegInit(False) -- never `in Bool()` inside a plugin Area --
and gates ONLY applyPrediction, never the FTQ/clamp/confirm path.

Nothing consumes the composed ftbTakenSpec yet: lock-step, ported corpus and IPC are all
bit-identical, which is the inertness proof (hardware presence confirmed independently by
grep on the generated netlist). Spec §2.4, §2.6."
```

---

### Task 7 (Slice 3a): `ringKeep` — trailing-word truncation on the outstanding ring, inert

**Files:**
- Modify: `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala`
  (`ringDrop` declaration ~:182; the `when(ic.cmd.fire)` block ~:248-264;
  the `nWords` expression ~:299)
- Test: `src/test/scala/m68k040/frontend/FetchAlignSpec.scala` (extend)

**Interfaces:**
- Produces: `FetchAlignPlugin.logic.ringKeep : Vec[UInt(3 bits)]` of length `RING` (3).
  Semantics: **how many words of the fetched window to push**, counted from word 0 of the
  window (NOT from the drop point). Default 4 = keep the whole window. `ringKeep(i) >
  ringDrop(i)` is **INV-A** and must hold for every occupied entry.

> This slice is inert: `ringKeep` is written 4 unconditionally, so
> `nWords = ringKeep - startWord` reduces to today's `4 - startWord` bit-for-bit.
> The edit lives in the `rsp → ibuf.push` cone, which is **not** in any feedback loop.

- [ ] **Step 1: Write the failing test**

Append to `src/test/scala/m68k040/frontend/FetchAlignSpec.scala`, inside the class:

```scala
  test("ringKeep defaults to 4 and reduces nWords to today's 4 - drop", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x9000L
      // 16 x MOVEQ #0,%d0 -- four full 8-byte windows of 1-word simple instructions.
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq.fill(16)(0x7000))
      dut.probe.logic.feedOut.ready #= false
      dut.fa.logic.resume.valid #= false; dut.fa.logic.redirect.valid #= false
      cd.waitSampling(2)
      // Redirect to base+4, i.e. a leading drop of 2 words on the first window.
      redirect(dut, cd, base + 4)
      dut.probe.logic.feedOut.ready #= true

      val fails = scala.collection.mutable.ArrayBuffer[String]()
      // Every occupied ring entry must satisfy INV-A: ringKeep > ringDrop.
      val mon = fork {
        while (true) {
          cd.waitSampling()
          val cnt  = dut.fa.logic.ringCount.toInt
          val head = dut.fa.logic.ringHead.toInt
          for (j <- 0 until cnt) {
            val i = (head + j) % 3
            val k = dut.fa.logic.ringKeep(i).toInt
            val d = dut.fa.logic.ringDrop(i).toInt
            if (k != 4) fails += s"ringKeep($i) = $k, want 4 while inert"
            if (k <= d) fails += s"INV-A violated: ringKeep($i)=$k <= ringDrop($i)=$d"
          }
        }
      }
      // Consume 12 packets; PCs must still be the plain sequential stream from base+4.
      var expect = base + 4
      var got = 0
      var guard = 0
      while (got < 12 && guard < 400) {
        cd.waitSampling(); guard += 1
        if (dut.probe.logic.feedOut.valid.toBoolean) {
          val pc = dut.probe.logic.feedOut.payload(0).pc.toLong
          if (pc != expect) fails += f"slot0 pc 0x$pc%x, want 0x$expect%x"
          expect += 2; got += 1
          if (dut.probe.logic.s1v.toBoolean) { expect += 2; got += 1 }
        }
      }
      mon.terminate()
      if (got < 12) fails += s"only $got packets in $guard cycles"
      assert(fails.isEmpty, "\n" + fails.take(20).mkString("\n"))
    }
  }
```

- [ ] **Step 2: Run it and verify it fails**

```bash
cd ../wt-ftb && ~/sbt/bin/sbt "testOnly m68k040.frontend.FetchAlignSpec"
```
Expected: **compile error** — `value ringKeep is not a member of ...`.

- [ ] **Step 3: Declare `ringKeep` and make it simPublic**

In `FetchAlignPlugin.scala`, immediately after the `ringDrop` declaration (~:182):

```scala
    // ── fetch-directed BTB (task #126): per-entry TRAILING-word truncation ────────
    // The exact mirror of `ringDrop`'s leading-word drop. When fetch applies an FTB
    // prediction to the window it is issuing, it records how many of that window's words
    // are GENUINE (`brWordOff + brLen` == brEndOff + 1); the words after the predicted
    // branch are fall-through, i.e. wrong-path, and are never pushed. Default 4 == keep
    // the whole window, which makes `nWords` below bit-identical to the pre-lever
    // `4 - startWord`.
    //
    // INV-A (asserted in simulation by FtqFlushSpec and FetchAlignSpec): for every
    // OCCUPIED entry, `ringKeep(i) > ringDrop(i)`. A violation means a zero-word or
    // negative-width push. It is guaranteed at issue by refusing to apply a prediction
    // unless `brWordOff >= pendingDrop` (spec §2.5, and §3.5 case A6).
    val ringKeep = Vec.fill(RING)(Reg(UInt(3 bits)) init 4)
```
and extend the existing simPublic line (~:199) to:
```scala
    spinal.core.sim.SimPublic(recValid, ringCount, ringHead, ringTail, pendingDrop)
    ringKeep.foreach(spinal.core.sim.SimPublic(_))
    ringDrop.foreach(spinal.core.sim.SimPublic(_))
```

- [ ] **Step 4: Write `ringKeep` at issue**

In the `when(ic.cmd.fire)` block, immediately after `ringDrop(ringTail) := pendingDrop`:
```scala
      // Default: keep the whole window. Task 8's applyPrediction overrides this.
      ringKeep(ringTail) := U(4, 3 bits)
```

- [ ] **Step 5: Consume `ringKeep` in the response path**

Replace (~:299):
```scala
        val nWords    = U(4, 3 bits) - startWord.resize(3)
```
with:
```scala
        // `ringKeep` is counted from word 0 of the WINDOW, and `startWord` is the leading
        // drop, so the pushed count is keep - drop. While `ringKeep` is 4 this is
        // bit-identical to the previous `U(4) - startWord`. This expression lives in the
        // `rsp -> ibuf.push` cone, which is NOT in any feedback loop -- deliberately the
        // side this lever pays on (spec §8.4 rejects splicing at decode instead).
        val nWords    = ringKeep(ringHead) - startWord.resize(3)
```

**`ibufRoomForIssue` is NOT touched** — it reserves a full 4 words per outstanding window and
truncation only *shrinks* a window, so the no-overflow invariant survives as a strict upper
bound (its own existing comment already says so).

- [ ] **Step 6: Run the test and verify it passes**

```bash
cd ../wt-ftb && ~/sbt/bin/sbt "testOnly m68k040.frontend.FetchAlignSpec"
```
Expected: all tests PASS.

- [ ] **Step 7: Full gates**

```bash
cd ../wt-ftb
~/sbt/bin/sbt "testOnly m68k040.frontend.*"
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec" 2>&1 | tail -20
tools/fuzz/ported-sweep-parallel.sh 4 2>&1 | tail -20
diff <(sort ported_logs_parallel/all_fails.txt) <(sort /tmp/ftb_baseline_fails.txt)
IPC_SEED=1 IPC_MEM=l2:5:70 JAVA_OPTS=-Xmx6g ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' 2>&1 | grep AGGREGATE
```
Expected: everything bit-identical to Task 2's baseline.

- [ ] **Step 8: Commit**

```bash
cd ../wt-ftb
git add src/main/scala/m68k040/frontend/FetchAlignPlugin.scala \
        src/test/scala/m68k040/frontend/FetchAlignSpec.scala
git commit -m "frontend: per-ring-entry ringKeep trailing-word truncation (inert)

Mirror of the existing ringDrop leading-word drop: each outstanding fetch records how many
of its window's words are genuine, and the response path pushes ringKeep - ringDrop words.
Written 4 unconditionally for now, so nWords is bit-identical to the previous 4 - drop.

The edit lives in the rsp -> ibuf.push cone, which is not in any feedback loop -- that is
the deliberate choice over splicing at decode (spec §8.4, rejected). ibufRoomForIssue is
untouched: it reserves a full 4 words per outstanding window and truncation only shrinks
one, so its no-overflow bound still holds.

INV-A (ringKeep > ringDrop for every occupied entry) is now asserted live in FetchAlignSpec.
Gated bit-identical: lock-step, ported corpus, IPC. Spec §2.5."
```

---

### Task 8 (Slice 3b): the Fetch Target Queue + the fetch-side `applyPrediction` action

**Files:**
- Modify: `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala`
  (FTQ state next to the ring state ~:199; `applyPrediction` + the `when(ic.cmd.fire)`
  extension ~:248-264; the GHR shift ~:637; the FTQ pointer update at the **very end** of
  the `logic` Area)
- Test: `src/test/scala/m68k040/frontend/FetchAlignSpec.scala` (extend)

**Interfaces:**
- Consumes: `ftbEnable`, `ftbResHit`, `ftbResFresh`, `ftbResBrWordOff`, `ftbResBrLen`,
  `ftbResTarget`, `ftbTakenSpec`, `ftbIsCond`, `ftbPhtIdx` (Task 6); `ringKeep` (Task 7).
- Produces (Tasks 9-10 consume by exact name):
  - `case class FtqEntry() extends Bundle { brPc: UInt(32), brLen: UInt(3), target: UInt(32),
    phtIdx: UInt(11), isCond: Bool }`
  - `ftq : Vec[FtqEntry]` (depth `Global.FTQ_DEPTH.get`), `ftqHead`, `ftqTail`,
    `ftqCount : UInt`, `ftqInc(UInt): UInt`
  - `applyPrediction : Bool`, `ftqPush : Bool`, `ftqPop : Bool` (forward-declared, default
    False; Task 10 drives `ftqPop`), `ftqFlush : Bool` (forward-declared, default False;
    Task 10 drives it from the redirect blocks), `ftbSuppress : Bool` (Reg).

> Still inert: `ftbEnable` is False, so `applyPrediction` is constant-False and no FTQ entry
> is ever pushed. The test pokes `ftbEnable` on to exercise the fetch side in isolation.

- [ ] **Step 1: Write the failing test**

Append to `src/test/scala/m68k040/frontend/FetchAlignSpec.scala`. This DUT has no
`FtbPlugin`, so drive `fa.logic.ftb*In` directly — they are `allowOverride` plain wires, but
a *sim poke* needs them to be top-level inputs. Add a tiny wiring plugin to this spec file
and a second DUT that uses it:

```scala
  /** Hoists FetchAlign's FTB-result INPUTS to top IO so a test can inject an arbitrary
    * (including deliberately wrong) lookup result without instantiating an FtbPlugin. */
  class FtbInjectPlugin extends FiberPlugin {
    val logic = during build new Area {
      val fa = host[FetchAlignPlugin]
      val iHit  = in Bool ()
      val iOff  = in UInt (2 bits)
      val iLen  = in UInt (3 bits)
      val iTgt  = in UInt (32 bits)
      val iType = in UInt (2 bits)
      fa.logic.ftbHitIn       := iHit
      fa.logic.ftbBrWordOffIn := iOff
      fa.logic.ftbBrLenIn     := iLen
      fa.logic.ftbTargetIn    := iTgt
      fa.logic.ftbBrTypeIn    := iType
      val oQueryPc = out(UInt(32 bits)); oQueryPc := fa.logic.ftbQueryPc
    }
  }

  class InjDut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val ic = new IcachePlugin
    val fa = new FetchAlignPlugin
    val probe = new DecodeFeedProbePlugin
    val inj = new FtbInjectPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), new IdentityTranslationPlugin, ic, fa, probe, inj)) }
  }

  test("applyPrediction truncates the window, redirects fetchPc, and pushes exactly one FTQ entry", VerilatorTest) {
    SimConfig.withVerilator.compile(new InjDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0xB000L
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq.fill(32)(0x7000))
      dut.probe.logic.feedOut.ready #= false
      dut.fa.logic.resume.valid #= false; dut.fa.logic.redirect.valid #= false
      dut.inj.logic.iHit #= false; dut.inj.logic.iOff #= 0; dut.inj.logic.iLen #= 1
      dut.inj.logic.iTgt #= 0; dut.inj.logic.iType #= 1
      cd.waitSampling(2)
      // Non-blocking enable poke (a poke right after forkStimulus is eaten by reset).
      fork { for (_ <- 0 until 8) { dut.fa.logic.ftbEnable #= true; cd.waitSampling() } }
      cd.waitSampling(10)

      val fails = scala.collection.mutable.ArrayBuffer[String]()

      // INV-B monitor: a ring entry gets ringKeep < 4 IFF an FTQ entry was pushed the same
      // cycle. Sampled on ic.cmd.fire.
      var pushes = 0
      var truncs = 0
      val mon = fork {
        var prevCount = 0
        while (true) {
          cd.waitSampling()
          val c = dut.fa.logic.ftqCount.toInt
          if (c > prevCount) pushes += 1
          prevCount = c
        }
      }

      // Claim: window `base` holds a 1-word unconditional branch at word offset 1
      // (pc = base+2) targeting base+0x100.
      dut.inj.logic.iHit  #= true
      dut.inj.logic.iOff  #= 1
      dut.inj.logic.iLen  #= 1
      dut.inj.logic.iTgt  #= base + 0x100
      dut.inj.logic.iType #= 1
      redirect(dut, cd, base)
      cd.waitSampling(20)

      if (dut.fa.logic.ftqCount.toInt == 0) fails += "expected at least one FTQ entry pushed"
      // The FTQ head must describe exactly the claimed branch.
      val hIdx = dut.fa.logic.ftqHead.toInt
      val hPc  = dut.fa.logic.ftq(hIdx).brPc.toLong
      val hLen = dut.fa.logic.ftq(hIdx).brLen.toInt
      val hTgt = dut.fa.logic.ftq(hIdx).target.toLong
      if (hPc != base + 2)         fails += f"ftq.brPc = 0x$hPc%x, want 0x${base + 2}%x"
      if (hLen != 1)               fails += s"ftq.brLen = $hLen, want 1"
      if (hTgt != base + 0x100)    fails += f"ftq.target = 0x$hTgt%x, want 0x${base + 0x100}%x"
      mon.terminate()
      assert(fails.isEmpty, "\n" + fails.mkString("\n"))
    }
  }

  test("applyPrediction is DECLINED when brWordOff < pendingDrop (spec §3.5 A6)", VerilatorTest) {
    SimConfig.withVerilator.compile(new InjDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0xC000L
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq.fill(32)(0x7000))
      dut.probe.logic.feedOut.ready #= false
      dut.fa.logic.resume.valid #= false; dut.fa.logic.redirect.valid #= false
      dut.inj.logic.iHit #= false; dut.inj.logic.iOff #= 0; dut.inj.logic.iLen #= 1
      dut.inj.logic.iTgt #= 0; dut.inj.logic.iType #= 1
      cd.waitSampling(2)
      fork { for (_ <- 0 until 8) { dut.fa.logic.ftbEnable #= true; cd.waitSampling() } }
      cd.waitSampling(10)

      // Claim a branch at word offset 0, but ENTER the window at word offset 2
      // (redirect to base+4 => pendingDrop = 2 > brWordOff = 0).
      dut.inj.logic.iHit  #= true
      dut.inj.logic.iOff  #= 0
      dut.inj.logic.iLen  #= 1
      dut.inj.logic.iTgt  #= base + 0x100
      dut.inj.logic.iType #= 1
      redirect(dut, cd, base + 4)
      cd.waitSampling(6)
      assert(dut.fa.logic.ftqCount.toInt == 0,
        "a prediction whose branch starts BEFORE the window's entry point must be declined " +
        "at issue -- no truncation, no FTQ push (spec §3.5 A6)")
    }
  }
```

Add to this file's imports: `import spinal.lib.misc.plugin.FiberPlugin` is already present via
`{FiberPlugin, PluginHost}`; no new imports are needed.

- [ ] **Step 2: Run and verify it fails**

```bash
cd ../wt-ftb && ~/sbt/bin/sbt "testOnly m68k040.frontend.FetchAlignSpec"
```
Expected: **compile error** — `value ftqCount is not a member of ...`.

- [ ] **Step 3: Declare the FTQ state**

In `FetchAlignPlugin.scala`, immediately after the `ringKeep` declaration from Task 7:

```scala
    // ── Fetch Target Queue (task #126, spec §2.7) ────────────────────────────────
    // One entry per prediction IN FLIGHT between fetch and decode. Distinct from the
    // outstanding ring and never overlapping it: the ring tracks in-flight I-CACHE
    // FETCHES (cmd.fire -> rsp.valid, <= 3) and stores the PHYSICAL shape of one push;
    // the FTQ tracks in-flight PREDICTIONS (cmd.fire -> decode confirm, spanning the IBuf
    // too) and stores the ARCHITECTURAL CLAIM about one branch. The only coupling is one
    // atomic write at issue and a co-located flush -- see `ftqFlush` below.
    //
    // Depth rationale: fetch can be ~8 windows ahead of decode (RING=3 in flight + up to 5
    // windows resident in the 20-word IBuf), and in a 1-window loop every window is
    // predicted, giving ~3 predictions in flight. Depth 4 covers that with margin; the
    // depth is a SWEPT parameter in Task 14's IPC gate ({2,4,6}), not an assertion.
    case class FtqEntry() extends Bundle {
      val brPc   = UInt(32 bits)   // the predicted branch's own PC
      val brLen  = UInt(3 bits)    // its length in words, 1..5
      val target = UInt(32 bits)   // the learned taken-target fetch was redirected to
      val phtIdx = UInt(11 bits)   // the fetch-time gshare index the lookup ACTUALLY read
      val isCond = Bool()          // phtIdx is meaningful
    }
    val FTQ = Global.FTQ_DEPTH.get
    val ftq = Vec.fill(FTQ) {
      // Explicit init: an uninitialised Reg randomises per sim seed in SpinalSim, which
      // has produced flaky lock-step before. These fields are only READ when ftqCount != 0,
      // but determinism is cheap here.
      val e = Reg(FtqEntry())
      e.brPc init 0; e.brLen init 0; e.target init 0; e.phtIdx init 0; e.isCond init False
      e
    }
    val ftqHead  = Reg(UInt(log2Up(FTQ) bits)) init 0
    val ftqTail  = Reg(UInt(log2Up(FTQ) bits)) init 0
    val ftqCount = Reg(UInt(log2Up(FTQ + 1) bits)) init 0
    // Same explicit non-power-of-two wrap helper as `ringInc` (FTQ=4 is a power of two
    // today, but the depth is swept and 6 is a candidate).
    def ftqInc(idx: UInt): UInt =
      if (isPow2(FTQ)) (idx + 1).resized
      else Mux(idx === U(FTQ - 1, idx.getWidth bits), U(0, idx.getWidth bits), (idx + 1).resized)
    spinal.core.sim.SimPublic(ftqHead, ftqTail, ftqCount)
    ftq.foreach { e => e.brPc.simPublic(); e.brLen.simPublic(); e.target.simPublic()
                       e.phtIdx.simPublic(); e.isCond.simPublic() }

    // Forward-declared FTQ control. `ftqPop` is driven by the confirm path (Task 10);
    // `ftqFlush` by EVERY redirect block (Task 10) -- that CO-LOCATION with
    // `ringStale.foreach(_ := True)` is the incoherence guard, and it has no exceptions.
    val ftqPop = Bool(); ftqPop.allowOverride; ftqPop := False
    val ftqFlush = Bool(); ftqFlush.allowOverride; ftqFlush := False
    // L1 (spec §3.6, MANDATORY): a sticky suppress set on a confirm mismatch and cleared on
    // the next feed.fire. Without it a mismatch flush LIVELOCKS -- refetch -> same window
    // -> same FTB hit -> same splice -> same mismatch, forever. While set, the sequential
    // refetch delivers the window UNTRUNCATED, which is exactly today's stream, whose
    // forward progress is already established.
    val ftbSuppress = Reg(Bool()) init False
    ftbSuppress.simPublic()
```

Add `import m68k040.Global` to the file's imports if not already present.

- [ ] **Step 4: Add `applyPrediction` and extend the issue block**

Immediately **before** `when(ic.cmd.fire) {` (~:248), add:

```scala
    // ── fetch-directed BTB: should this issue apply a prediction? (spec §2.5) ─────
    // Every term is load-bearing:
    //  - ftbEnable          : the runtime kill switch (gates ONLY this decision).
    //  - ftbResHit/Fresh    : the registered lookup is for THIS fetchPc value (§2.4).
    //  - ftbTakenSpec       : uncond, or the speculative PHT says taken (§2.6).
    //  - brWordOff >= pendingDrop : the branch's FIRST word must be at or after the
    //      window's entry point. A violation means the window is being entered INSIDE the
    //      claimed branch (an aliased entry or a mid-branch redirect target) -- spec §3.5
    //      case A6 -- and it is also what guarantees INV-A (ringKeep = brEndOff + 1 >
    //      brWordOff >= ringDrop).
    //  - ftqCount != FTQ    : FTQ full => do not predict. Self-consistent, not a hazard:
    //      the un-predicted branch is caught later by the decode-time BTB fallback and
    //      flushes exactly as today (G4).
    //  - !ftbSuppress       : L1 liveness.
    //  - !redirectThisCycle : an architectural redirect this cycle makes THIS issue stale,
    //      so its window is discarded -- pushing an FTQ entry for it would describe a
    //      stream that no longer exists.
    // Fetch must NEVER truncate a window without a matching FTQ entry and NEVER push an
    // FTQ entry without truncating; both are written in the same `when(ic.cmd.fire)` arm
    // under this one common term, so INV-B holds by construction.
    val applyPrediction = ftbEnable && ftbResHit && ftbResFresh && ftbTakenSpec &&
                          (ftbResBrWordOff >= pendingDrop) &&
                          (ftqCount =/= U(FTQ, ftqCount.getWidth bits)) &&
                          !ftbSuppress && !redirectThisCycle
    val ftqPush = ic.cmd.fire && applyPrediction
    spinal.core.sim.SimPublic(applyPrediction)
```

Then, at the **end** of the existing `when(ic.cmd.fire) { ... }` body (after the
`when(!redirectThisCycle) { fetchPc := fetchPc + 8 }` arm, so it overrides it):

```scala
      // ── the atomic fetch-side prediction action (all four, same cycle) ─────────
      when(applyPrediction) {
        // (1) trailing truncation: keep words 0 .. brEndOff of this window.
        //     ringKeep = brEndOff + 1 = brWordOff + brLen.
        ringKeep(ringTail) := (ftbResBrWordOff.resize(3) +^ ftbResBrLen).resize(3)
        // (2) redirect the sequential walker to the learned target, reusing the existing
        //     leading-drop mechanism verbatim.
        fetchPc     := ftbResTarget
        pendingDrop := ftbResTarget(2 downto 1)
        // (3) enqueue the architectural claim.
        ftq(ftqTail).brPc   := ftbQueryPc(31 downto 3) @@ ftbResBrWordOff @@ U(0, 1 bits)
        ftq(ftqTail).brLen  := ftbResBrLen
        ftq(ftqTail).target := ftbResTarget
        ftq(ftqTail).phtIdx := ftbPhtIdx
        ftq(ftqTail).isCond := ftbIsCond
        ftqTail := ftqInc(ftqTail)
      }
```

- [ ] **Step 5: Route the fetch-time GHR shift (resolution R1)**

Replace the existing `gsShiftValid`/`gsShiftDir` drives (~:637-638) with:

```scala
    // ── GHR shift: fetch-time prediction wins over the decode-time one (R1) ──────
    // The GHR is single-ported. A fetch-side shift (an applied CONDITIONAL prediction)
    // and a decode-side shift (an emitted condBtbHit0) can want it in the same cycle;
    // fetch wins and the decode-side bit is dropped. That lands inside gshare's explicit
    // accept-corruption tolerance (Gshare.scala's class comment): the branch EU
    // cross-checks direction AND target, and every conditional still carries its OWN
    // phtIndex down, so retire trains the exact entry the lookup read regardless.
    // `&& !ftqConfirm` (added in Task 10) is the mandatory DOUBLE-shift avoidance: a
    // confirmed branch already shifted at fetch.
    val ftbGhrShift = ic.cmd.fire && applyPrediction && ftbIsCond
    gsShiftValid := ftbGhrShift ||
                    (feed.fire && !faultHold && condBtbHit0 && res.slot0Valid)
    gsShiftDir   := Mux(ftbGhrShift, True, slot0PredTaken)
```
(An applied prediction is taken by definition, hence the constant `True`.)

- [ ] **Step 6: Add the FTQ pointer/occupancy update at the VERY END of the `logic` Area**

Place this as the **last** statements inside `val logic = during build new Area { ... }`,
after the `when(mispredictRedirect.valid) { ... }` block. Position matters: `ftqFlush` must
be the last writer of these three registers so it wins over push/pop, exactly as the
architectural redirects win over `predictFire` today.

```scala
    // ── FTQ occupancy, LAST so ftqFlush wins over push/pop ───────────────────────
    when(ftqPop) { ftqHead := ftqInc(ftqHead) }
    when(ftqPush && !ftqPop) {
      ftqCount := (ftqCount + 1).resized
    } elsewhen(!ftqPush && ftqPop) {
      ftqCount := (ftqCount - 1).resized
    }
    when(ftqFlush) {
      ftqHead := 0; ftqTail := 0; ftqCount := 0
    }
    // L1: ftbSuppress clears on the next emitted packet (forward progress made), and is
    // SET by the mismatch path (Task 10) which is placed later, so the set wins on a
    // same-cycle collision. The architectural redirect blocks clear it again (F1/F2/F3):
    // a fresh path deserves a fresh prediction.
    when(feed.fire) { ftbSuppress := False }
```

**IMPORTANT ordering note for the implementer:** `ftbSuppress := True` (Task 10's mismatch
block) and the `ftbSuppress := False` clears in the redirect blocks are all written *before*
this trailing block in the file. SpinalHDL is last-`when`-wins, so this `when(feed.fire)`
clear would override them. To keep the intended priority
(**redirect-clear > mismatch-set > feed.fire-clear**), move this one line to sit
**immediately before** the `predictFire` block instead, and leave only the FTQ pointer
statements at the end. Task 10 verifies this ordering explicitly.

- [ ] **Step 7: Run the tests and verify they pass**

```bash
cd ../wt-ftb && ~/sbt/bin/sbt "testOnly m68k040.frontend.FetchAlignSpec"
```
Expected: all tests PASS, including both new ones.

- [ ] **Step 8: Full gates (still inert in the real DUTs — `ftbEnable` defaults False)**

```bash
cd ../wt-ftb
~/sbt/bin/sbt "testOnly m68k040.frontend.*"
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec" 2>&1 | tail -20
tools/fuzz/ported-sweep-parallel.sh 4 2>&1 | tail -20
diff <(sort ported_logs_parallel/all_fails.txt) <(sort /tmp/ftb_baseline_fails.txt)
```
Expected: identical to Task 2's baseline.

- [ ] **Step 9: Commit**

```bash
cd ../wt-ftb
git add src/main/scala/m68k040/frontend/FetchAlignPlugin.scala \
        src/test/scala/m68k040/frontend/FetchAlignSpec.scala
git commit -m "frontend: Fetch Target Queue + fetch-side applyPrediction action

Adds a 4-deep FTQ of {brPc, brLen, target, phtIdx, isCond} and the atomic four-part issue
action: ringKeep truncation, fetchPc redirect to the learned target (reusing pendingDrop
verbatim), FTQ push, and the fetch-time GHR shift. All four sit in ONE when(ic.cmd.fire)
arm under one common applyPrediction term, so INV-B (ringKeep < 4 iff an FTQ entry was
pushed the same cycle) holds by construction.

applyPrediction is declined when brWordOff < pendingDrop -- the window would be entered
INSIDE the claimed branch (spec §3.5 A6) -- and that same condition is what guarantees
INV-A. It is also declined when the FTQ is full or a redirect fires this cycle; both
degrade to the unchanged decode-time BTB path (G4).

GHR: a fetch-side shift wins over a same-cycle decode-side one (single-ported GHR); the
lost bit is accept-corruption, and each conditional still carries its own phtIndex down.

Still inert in the real DUTs (ftbEnable defaults False); the new FetchAlignSpec tests poke
it on to exercise the fetch side standalone. Spec §2.5, §2.7."
```

---

### Task 9 (Slice 3c): the `availEff` genuine-word clamp at decode

> **DEPENDENCY: this task is gated on Task 1's G-T1b verdict.** If the census showed the
> `Aligner preds(L0) → slot1Ok → io_shift` family already at or worse than the frontend BTB
> family, the clamp's placement must be re-designed **before** this task is implemented
> (clamp only the `avail < L0` gate; derive `slot1Ok`'s bound from `spliceWords` on a
> separate parallel arc). Escalate with the census data rather than proceeding.

**Files:**
- Modify: `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala`
  (new decode-side FTQ view **before** the `p0LiveReg` block ~:384; the three classify
  validity flags ~:402-404; `p0LiveInvalidate` ~:405-406; the `Aligner.align` call ~:412)
- Test: `src/test/scala/m68k040/frontend/FetchAlignSpec.scala` (extend)

**Interfaces:**
- Consumes: `ftq`, `ftqHead`, `ftqCount` (Task 8).
- Produces (Task 10 consumes by exact name): `ftqV`, `ftqHeadE`, `ftqDiff`, `ftqDelta`,
  `ftqNear`, `ftqAt0`, `spliceWords`, `availEff`.

**This is the correctness mechanism.** Spec §3.3-§3.4: with `availEff` substituted, three
**already-load-bearing** aligner stalls become the mis-framing guard, and a fourth comes free:

| gate | `Aligner.scala` | what it now guarantees |
|---|---|---|
| `when(avail < L0)` | :167 | slot0's own words 0..L0-1 are all genuine (all < splice) |
| `.elsewhen(p0.ambiguousLine)` | :106 | slot0's framing *lookahead* never used a post-splice word |
| `slot1Ok`'s `avail >= L0L1` + `!p1.ambiguousLine` | :267 | slot1's words are all genuine, from a non-ambiguous bake |
| `!p0.simple && (avail < WINDOW)` | :110 | a COMPLEX packet can never be emitted under a binding clamp (`spliceWords <= 4 + 5 = 9 < 10`) |

Consequence: **no instruction is ever framed, emitted, or shifted using a single byte at or
beyond the splice.** Every emitted packet is correct whether or not the FTQ head's claim
holds. That is the whole §3.2 obligation, discharged by construction.

- [ ] **Step 1: Write the failing test**

Append to `src/test/scala/m68k040/frontend/FetchAlignSpec.scala`:

```scala
  test("availEff clamps to the FTQ head's splice and is otherwise ibuf.avail", VerilatorTest) {
    SimConfig.withVerilator.compile(new InjDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0xD000L
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq.fill(32)(0x7000))
      dut.probe.logic.feedOut.ready #= false
      dut.fa.logic.resume.valid #= false; dut.fa.logic.redirect.valid #= false
      dut.inj.logic.iHit #= false; dut.inj.logic.iOff #= 0; dut.inj.logic.iLen #= 1
      dut.inj.logic.iTgt #= 0; dut.inj.logic.iType #= 1
      cd.waitSampling(2)
      val fails = scala.collection.mutable.ArrayBuffer[String]()

      // (a) With NO FTQ entry, availEff must equal ibuf.avail on every cycle.
      redirect(dut, cd, base)
      dut.probe.logic.feedOut.ready #= true
      for (_ <- 0 until 40) {
        cd.waitSampling(); sleep(1)
        val ae = dut.fa.logic.availEff.toInt
        val av = dut.fa.logic.ibufAvailProbe.toInt
        if (ae != av) fails += s"no FTQ entry: availEff=$ae but ibuf.avail=$av"
      }

      // (b) With a live FTQ entry claiming a 1-word branch at word 1 of the decode window,
      //     availEff must be clamped to delta + brLen = 1 + 1 = 2 once ibuf.avail exceeds it.
      dut.probe.logic.feedOut.ready #= false
      fork { for (_ <- 0 until 8) { dut.fa.logic.ftbEnable #= true; cd.waitSampling() } }
      dut.inj.logic.iHit  #= true
      dut.inj.logic.iOff  #= 1
      dut.inj.logic.iLen  #= 1
      dut.inj.logic.iTgt  #= base + 0x200
      dut.inj.logic.iType #= 1
      redirect(dut, cd, base)
      cd.waitSampling(25)
      sleep(1)
      if (dut.fa.logic.ftqCount.toInt == 0) {
        fails += "expected a live FTQ entry"
      } else {
        val ae = dut.fa.logic.availEff.toInt
        val av = dut.fa.logic.ibufAvailProbe.toInt
        val sp = dut.fa.logic.spliceWords.toInt
        val expect = math.min(av, sp)
        if (ae != expect) fails += s"clamped: availEff=$ae, want min(avail=$av, splice=$sp)=$expect"
        if (ae > av)      fails += s"availEff=$ae must never EXCEED ibuf.avail=$av"
      }
      assert(fails.isEmpty, "\n" + fails.take(20).mkString("\n"))
    }
  }
```

- [ ] **Step 2: Run and verify it fails**

```bash
cd ../wt-ftb && ~/sbt/bin/sbt "testOnly m68k040.frontend.FetchAlignSpec"
```
Expected: **compile error** — `value availEff is not a member of ...`.

- [ ] **Step 3: Add the decode-side FTQ view and the clamp**

Insert **immediately before** the `val p0LiveReg = Reg(ChunkPredecode())` line (~:384) —
placement matters, because `p0LiveReg`'s own classify inputs are clamped in Step 4:

```scala
    // ── decode-side view of the FTQ head + the GENUINE-WORD CLAMP (spec §2.8, §3) ──
    // Everything here launches from REGISTERS (`ftq` entries, `decodePc`, `ibuf.io.avail`).
    //
    // ftqDiff/ftqDelta/ftqPast width derivation (design spec §10 Q3, resolved at plan time
    // and DIFFERENT from what §2.8 wrote): `brPc - decodePc` is a plain 32-bit wrapping
    // subtract. `ftqNear` requires bits 31..5 to be zero, i.e. the branch is < 16 words
    // ahead. `ftqPast` is therefore NOT gated on `ftqNear` -- inside `ftqNear`, bit 31 is
    // zero by construction and the spec's `ftqNear && ftqDelta(31)` would be identically
    // False (dead logic, and unkillable by the §9.2 mutation suite).
    //
    // ftqNear's 16-word reach vs HEAD_WORDS = 10 is deliberate: for delta in 10..15 the
    // clamp is never binding (avail <= 10 <= delta <= spliceWords), so it degrades exactly
    // to today's behaviour -- spec §3.5 case A9.
    val ftqV     = ftqCount =/= 0
    val ftqHeadE = ftq(ftqHead)
    val ftqDiff  = ftqHeadE.brPc - decodePc                       // UInt(32 bits), wrapping
    val ftqDelta = ftqDiff(4 downto 1)                            // word delta, 0..15
    val ftqNear  = ftqV && (ftqDiff(31 downto 5) === U(0, 27 bits))
    val ftqAt0   = ftqNear && (ftqDelta === U(0, 4 bits))
    // spliceWords = the number of GENUINE words remaining at decodePc if the head's claim
    // holds: the words before the branch, plus the branch's own.
    val spliceWords = (ftqDelta +^ ftqHeadE.brLen)                 // UInt(5 bits), 0..20
    // THE CLAMP. Every length the aligner consumes is derived only from words strictly
    // before the splice, so by INV-P (Aligner's two length sources are each
    // truth-or-stall -- see PredecodeSimpleLenSpec, which is now LOAD-BEARING for this
    // property) it is either the TRUE architectural length or the aligner stalls.
    val availEff = UInt(4 bits)
    when(ftqNear && (spliceWords < ibuf.io.avail.resize(5))) {
      availEff := spliceWords.resize(4)   // safe: in this arm spliceWords < avail <= 10
    } otherwise {
      availEff := ibuf.io.avail
    }
    spinal.core.sim.SimPublic(availEff, spliceWords, ftqDelta, ftqNear, ftqAt0, ftqV)
    // Probe of the RAW avail, so a testbench can assert availEff == min(avail, splice).
    val ibufAvailProbe = UInt(4 bits); ibufAvailProbe := ibuf.io.avail
    ibufAvailProbe.simPublic()
```

- [ ] **Step 4: Substitute `availEff` at exactly the two pinned sites**

(a) `p0LiveReg`'s classify validity flags (~:402-404) — replace `ibuf.io.avail` with
`availEff` in all three:
```scala
    p0LiveReg := PredecodeWord.classify(ibuf.io.head(0), ibuf.io.head(1), ibuf.io.head(2), ibuf.io.head(3),
      extWValid  = availEff >= U(2, 4 bits),
      extW2Valid = availEff >= U(3, 4 bits),
      extW3Valid = availEff >= U(4, 4 bits))
```

(b) the `Aligner.align` call (~:412):
```scala
    val res = Aligner.align(decodePc, ibuf.io.head, ibuf.io.headPred, availEff, p0LiveReg)
```

**Do NOT substitute anywhere else.** In particular `ibufRoomForIssue` (~:243-244) keeps
`ibuf.io.cnt`, and `p0LiveInvalidate`'s `ibuf.io.cnt < 4` term keeps `cnt`. Both are physical
occupancy, not visibility, and a wrong substitution here is a silent-corruption vector.

- [ ] **Step 5: Extend `p0LiveInvalidate`**

Replace (~:405-406):
```scala
    val p0LiveInvalidate = ibuf.io.flush || (ibuf.io.shift =/= 0) ||
                           (ibuf.io.push.fire && (ibuf.io.cnt < U(4, ibuf.io.cnt.getWidth bits)))
```
with:
```scala
    // task #126: `availEff` is now a classify INPUT, and it can change WITHOUT a
    // push.fire/shift/flush -- a push into an empty FTQ, a pop, or a flush all move the
    // FTQ head and hence the clamp. `p0LiveReg`'s existing invalidation set does not cover
    // that, and a stale p0LiveReg consumed as this instruction's length is a silent
    // mis-frame (exactly the class the task #202 comment block above describes). Adding
    // the value-change term restores the invariant "p0LiveReg is either bit-identical to a
    // same-cycle classify, or explicitly ambiguousLine".
    val p0LiveInvalidate = ibuf.io.flush || (ibuf.io.shift =/= 0) ||
                           (ibuf.io.push.fire && (ibuf.io.cnt < U(4, ibuf.io.cnt.getWidth bits))) ||
                           (RegNext(availEff) =/= availEff)
```

- [ ] **Step 6: Run the test and verify it passes**

```bash
cd ../wt-ftb && ~/sbt/bin/sbt "testOnly m68k040.frontend.FetchAlignSpec"
```
Expected: all tests PASS.

- [ ] **Step 7: Full gates**

```bash
cd ../wt-ftb
~/sbt/bin/sbt "testOnly m68k040.frontend.*"
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec" 2>&1 | tail -20
tools/fuzz/ported-sweep-parallel.sh 4 2>&1 | tail -20
diff <(sort ported_logs_parallel/all_fails.txt) <(sort /tmp/ftb_baseline_fails.txt)
IPC_SEED=1 IPC_MEM=zero JAVA_OPTS=-Xmx6g ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' 2>&1 | grep AGGREGATE
```
Expected: identical to Task 2's baseline. With `ftbEnable` False, `ftqCount` is always 0, so
`ftqNear` is always False and `availEff === ibuf.io.avail` — the clamp is a pure pass-through.
**The one thing that CAN move even so:** `p0LiveInvalidate`'s new `RegNext(availEff) =/=
availEff` term fires whenever `ibuf.io.avail` changes, which is more often than the old set.
That can cost a few extra `ambiguousLine` stall cycles. If IPC moves by more than ~0.2 %,
report it as a measured cost of the clamp rather than treating it as noise.

- [ ] **Step 8: Commit**

```bash
cd ../wt-ftb
git add src/main/scala/m68k040/frontend/FetchAlignPlugin.scala \
        src/test/scala/m68k040/frontend/FetchAlignSpec.scala
git commit -m "frontend: availEff genuine-word clamp from the FTQ head

availEff = min(ibuf.avail, ftqDelta + brLen), substituted at EXACTLY two sites -- the
Aligner.align call and p0LiveReg's three classify validity flags -- and nowhere else
(ibufRoomForIssue and p0LiveInvalidate's cnt<4 term keep raw physical occupancy).

This is the correctness mechanism, not a heuristic: with the clamp, four ALREADY
load-bearing aligner stalls (avail<L0, p0.ambiguousLine, slot1Ok's avail>=L0L1, and
complex's avail>=WINDOW) guarantee that no instruction is ever framed, emitted or shifted
using a single byte at or beyond the splice. Every emitted packet is therefore correct
whether or not the FTQ head's claim holds.

ftqPast is defined as ftqV && ftqDiff(31), NOT the design spec §2.8's
ftqNear && ftqDelta(31), which is identically False (ftqNear already forces bit 31 low) --
that would be dead logic and unkillable by the mandated mutation suite.

p0LiveInvalidate gains a value-change term because availEff is now a classify input that
can move without a push/shift/flush.

Gated at baseline with ftbEnable=False (clamp is a pass-through). Spec §2.8, §3.3, §3.4."
```

---

### Task 10 (Slice 3d): confirm-or-flush — the whole §3 guard, plus F1-F12 and L1/L2

**Files:**
- Modify: `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala` (many sites, listed below)

**Interfaces:**
- Consumes: everything from Tasks 6-9.
- Produces (Tasks 11-13 assert on these by exact name): `ftqConfirm`, `ftqConfirmFire`,
  `ftqLenBad`, `ftqOvershoot`, `ftqStarved`, `ftqStarveRaw`, `ftqStarveCnt`, `ftqPast`,
  `ftqMismatch`, `ftqMismatchPc`.

> This is the biggest single edit in the plan. Work through the steps in order; the
> combinational ordering constraints are stated where they bite.

- [ ] **Step 1: Define `ftqConfirm` — placed BEFORE `slot0Predicted`**

Insert immediately **before** `val slot0Predicted = slot0IsPred || rasPredictSlot0` (~:516).
It must precede that line because `predictedThisEmit` and `suppressSlot1` both consume it.

```scala
    // ── confirm-or-flush: the CONFIRM side (spec §2.8) ───────────────────────────
    // `decodePc` IS the claimed branch, the aligner produced a SIMPLE packet there, and
    // its predecoded length matches the claim. `res.slot0.simple` is belt-and-braces for
    // spec §3.5 case A3 (a COMPLEX instruction at B), which the clamp already makes
    // unreachable -- under a binding clamp spliceWords <= 4 + 5 = 9 < WINDOW(10), so
    // Aligner's complex arm cannot fire.
    //
    // DELIBERATELY NOT gated on `ftbEnable`: an FTQ entry describes a truncation that
    // ALREADY HAPPENED to bytes already in the IBuf. Gating the confirm off would strand a
    // live splice with nothing describing it -- the exact F7 silent-corruption shape.
    val ftqConfirm = ftqAt0 && res.slot0Valid && res.slot0.simple &&
                     (res.slot0.lenWords === ftqHeadE.brLen.resize(4))
    spinal.core.sim.SimPublic(ftqConfirm)
```

- [ ] **Step 2: Fold `ftqConfirm` into the emit-side decisions**

(a) `predictedThisEmit` (~:517) — F6: confirm wins, so a confirmed branch cannot ALSO
`predictFire` (which would flush and lose the entire win):
```scala
    val predictedThisEmit = slot0Predicted && !ftqConfirm
```

(b) `suppressSlot1` (~:606) and the `slot1ValidOut` suppression `when` (~:529) both gain
`ftqConfirm` — Phase 1 keeps the slot-1 defer on a confirmed branch (spec N6/§2.8; removing
it is Phase 4 and adds a mux to the `decodePc` loop):
```scala
    when(slot0Predicted || slot1WouldPred || slot1WouldRasPred || ftqConfirm) {
      slot1ValidOut := False
    }
```
```scala
    val suppressSlot1 = slot0Predicted || slot1WouldPred || slot1WouldRasPred || ftqConfirm
```

(c) the prediction stamp. Insert immediately after the existing
`when(condBtbHit0 && res.slot0Valid) { ... }` block (~:545-548) and before
`feed.valid := ...` (~:551):
```scala
    // A CONFIRMED fetch-directed prediction stamps the slot exactly as the decode-time
    // path does, but carries the FETCH-time gshare index (the one the lookup actually
    // read), so retire trains the exact PHT entry -- the discipline Services.scala's
    // GshareUpdate comment mandates. Placed after the decode-time stamps so it overrides
    // them; the emittingFaultPacket block below still overrides everything, which is the
    // correct priority.
    when(ftqConfirm) {
      feed.payload(0).predTaken  := True
      feed.payload(0).predTarget := ftqHeadE.target
      feed.payload(0).phtValid   := ftqHeadE.isCond
      feed.payload(0).phtIndex   := ftqHeadE.phtIdx
    }
```

- [ ] **Step 3: Define the starved/past guards and re-gate `emittingFaultPacket` (F8)**

Insert immediately **before** `val emittingFaultPacket = ...` (~:562):

```scala
    // ── confirm-or-flush: the STARVED and PAST guards ────────────────────────────
    // ftqStarved is a LIVENESS guard only -- correctness (no packet built from a
    // post-splice byte) is discharged entirely by the clamp plus Aligner's own stalls.
    // It exists because a binding clamp with no emittable packet is a PERMANENT stall:
    // availEff is pinned at spliceWords, which cannot grow.
    //
    // DWELL COUNTER (plan-time resolution R2, not in the design spec): p0LiveReg is a
    // REGISTER forced ambiguousLine for one cycle whenever its inputs change -- and
    // availEff is now one of those inputs -- so for 1-2 cycles after any availEff change
    // the aligner legitimately reports !slot0Valid while nothing is wrong. Firing
    // immediately would flush constantly and destroy the win. A genuine starve is
    // permanent, so requiring the raw condition to hold for 3 consecutive cycles cannot
    // mask a real one and costs 3 cycles on a rare event.
    val ftqStarveRaw = ftqNear && !res.slot0Valid && (availEff < ibuf.io.avail) &&
                       !quiesce && !stalled
    val ftqStarveCnt = Reg(UInt(2 bits)) init 0
    when(!ftqStarveRaw) {
      ftqStarveCnt := 0
    } elsewhen(ftqStarveCnt =/= U(3, 2 bits)) {
      ftqStarveCnt := ftqStarveCnt + 1
    }
    val ftqStarved = ftqStarveRaw && (ftqStarveCnt === U(3, 2 bits))
    // Defensive (spec §3.5 A7): decodePc is already PAST the head's branch. Argued
    // unreachable -- the FTQ is written in fetch order, decodePc advances monotonically
    // between flushes, and ftqOvershoot catches every skip in the cycle it happens -- but
    // if it ever holds, the splice is behind us and undescribed, so flush.
    val ftqPast = ftqV && ftqDiff(31)
    val ftqStarvedOrPast = ftqStarved || ftqPast
    spinal.core.sim.SimPublic(ftqStarved, ftqPast, ftqStarveRaw)
```

Then replace `emittingFaultPacket` (~:562) with:
```scala
    // F8: a confirm mismatch has priority over the synthetic fault packet. Both can
    // trigger on !res.slot0Valid; if the fault packet won, its `pc := decodePc` would
    // report a fault at a PC decode never actually reached. Only the STARVED/PAST classes
    // can coexist with !res.slot0Valid -- ftqLenBad requires res.slot0Valid and
    // ftqOvershoot requires an emit -- so gating on those two is exact, and it also
    // BREAKS what would otherwise be a combinational loop
    // (emittingFaultPacket -> feed.valid -> feed.fire -> ftqOvershoot).
    val emittingFaultPacket = faultHold && !res.slot0Valid && !stalled && !quiesce &&
                              !ftqStarvedOrPast
```

- [ ] **Step 4: Define the remaining guards and the mismatch, AFTER `decodePcNext`**

Insert immediately **after** the `val decodePcNext = Mux(...)` line (~:618) and before the
`when(feed.fire && !emittingFaultPacket)` block:

```scala
    // ── confirm-or-flush: the LENBAD and OVERSHOOT guards ────────────────────────
    // A2 -- the real instruction at B is SHORTER than claimed. The packet at B DOES fire,
    // un-predicted, and that is correct: L0 < brLen <= availEff, so ALL of its words are
    // genuine. The redirect then goes to decodePcNext and flushes the IBuf in the same
    // cycle, so the leftover fall-through words never reach decode.
    // A1/A3/A5 (the real instruction is LONGER, or COMPLEX, or straddles the splice) never
    // reach here at all -- they produce !res.slot0Valid and are caught by ftqStarved.
    //
    // Both LENBAD and OVERSHOOT are gated on feed.fire (plan-time resolution of design
    // spec §10 Q5): under backpressure the packet has NOT fired, and redirecting to
    // decodePcNext would skip an unemitted instruction. Holding the clamp during
    // backpressure is correct and safe -- no post-splice byte is used either way.
    val ftqLenBad = ftqAt0 && res.slot0Valid && !ftqConfirm && feed.fire
    // A4 -- there is no boundary at B: an instruction starting before B ends after it but
    // at or before the splice. Its own words are all genuine so it frames correctly and
    // emits, but decodePcNext SKIPS B. Caught in the SAME cycle as that emit, before the
    // next packet -- the one that would be framed from post-splice bytes at a fall-through
    // PC, i.e. the corrupting one.
    // decodePcNext already accounts for BOTH slots (res.shiftWords == L0 + L1 in the
    // slot1Ok arm), so allowing the emit is correct whether one or two slots fired.
    val ftqOvershoot = ftqNear && !ftqAt0 && feed.fire && !emittingFaultPacket &&
                       (effShift > ftqDelta)
    // F10: gated on !quiesce && !stalled -- firing a flush while fetch is quiesced
    // perturbs state for no benefit, and under `stalled` the head is an already-emitted
    // complex packet whose mismatch, if real, re-evaluates after resume (which flushes
    // anyway, F3).
    ftqMismatch := ftqStarvedOrPast ||
                   ((ftqLenBad || ftqOvershoot) && !quiesce && !stalled)
    val ftqMismatchPc = Mux(ftqLenBad || ftqOvershoot, decodePcNext, decodePc)
    spinal.core.sim.SimPublic(ftqLenBad, ftqOvershoot, ftqMismatch)
```

Note `ftqMismatch` is **assigned** here, not declared — see Step 6.

- [ ] **Step 5: Confirm's architectural effects**

Immediately **after** the existing `when(feed.fire && !emittingFaultPacket) { ... }` block
(~:619-626) — so it overrides the normal `decodePc := decodePcNext` — insert:

```scala
    // ── confirm-or-flush: THE ENTIRE IPC WIN ─────────────────────────────────────
    // decodePc jumps straight to the target with NO ibuf flush, NO ringStale, NO fetchPc
    // change, NO pendingDrop change, NO `started` change -- the target's words are already
    // in the IBuf, contiguous, because fetch put them there. This is a register-to-register
    // mux into the decodePcNext mux, SHALLOWER than today's
    // Mux(rasPredictSlot0, rasPredTarget, btbPredTarget0) whose second operand is a RAM
    // output.
    val ftqConfirmFire = ftqConfirm && feed.fire && !emittingFaultPacket
    when(ftqConfirmFire) {
      decodePc := ftqHeadE.target
    }
    ftqPop := ftqConfirmFire
    spinal.core.sim.SimPublic(ftqConfirmFire)
```

- [ ] **Step 6: Forward-declare `ftqMismatch` and add it to `redirectThisCycle`**

Next to the existing `predictFire` forward declaration (~:219-221), add:
```scala
    // A confirm mismatch redirect fires this cycle (forward-declared; driven after the
    // aligner + FTQ confirm logic). Like every other redirect, a fetch issued the SAME
    // cycle used the pre-redirect fetchPc and MUST be born stale.
    val ftqMismatch = Bool()
    ftqMismatch.allowOverride
    ftqMismatch := False
```
and extend `redirectThisCycle` (~:226):
```scala
    val redirectThisCycle = redirect.valid || (resume.valid && stalled) ||
                            mispredictRedirect.valid || predictFire || ftqMismatch
```

- [ ] **Step 7: The mismatch redirect block, at `predictFire`'s priority**

Insert immediately **after** the `when(predictFire) { ... }` block (~:662-677) and before
`when(redirect.valid)`:

```scala
    // ── confirm-or-flush: the MISMATCH redirect (spec §3.6) ──────────────────────
    // Reuses the EXISTING redirect action verbatim, at exactly predictFire's priority --
    // below the architectural redirects, so F5 (an architectural redirect coincident with
    // a confirm/mismatch) is resolved by SpinalHDL's last-when-wins ordering, identically
    // to today's proven arrangement.
    //
    // Recovery cost is identical to today's predictFire cost (4 cycles) and it is rare.
    // Over-firing this guard is SAFE by construction (it degrades to today's behaviour);
    // under-firing is the only danger, and spec §3.5's enumeration argues it cannot happen.
    when(ftqMismatch) {
      val newPc      = ftqMismatchPc
      decodePc       := newPc
      fetchPc        := newPc(31 downto 3) @@ U(0, 3 bits)
      ibuf.io.flush  := True
      stalled        := False
      started        := True
      pendingDrop    := newPc(2 downto 1)
      ringStale.foreach(_ := True)
      // F9: clear the fault hold. `ic.cmd.valid` is gated on !faultHold, so without this
      // the sequential refetch could never issue -- a deadlock. Clearing is safe and
      // self-correcting: the refetch re-hits the same ITLB/bus condition and re-raises the
      // fault at the correct PC, and L1 bounds it to one extra round trip.
      faultHold      := False
      faultEmitted   := False
      ftqFlush       := True
      // L1: suppress the next prediction so the sequential refetch delivers the window
      // UNTRUNCATED and decode makes forward progress.
      ftbSuppress    := True
      // L2 (MANDATORY): clear this window's FTB valid bit. Without it a branch whose real
      // decode is COMPLEX, or one that permanently straddles a boundary, mismatches on
      // EVERY occurrence -- strictly worse than today, violating G4. `valids` is a Reg Vec
      // so a second writer is free (no second Mem write port). A transient re-trains at
      // the next retire.
      ftbClrValid    := True
      ftbClrPc       := ftqHeadE.brPc
    }
```

- [ ] **Step 8: `ftqFlush` in every redirect block (F1-F4) — no exceptions**

Add `ftqFlush := True` to **all four** of these `when` blocks, alongside their existing
`ringStale.foreach(_ := True)`:
- `when(predictFire)` (~:662) — **F4**. Reachable: an FTQ entry for a LATER branch is
  invalidated when an earlier branch at `p < B` redirects. Without the flush the FTQ head
  would describe a stream that no longer exists.
- `when(redirect.valid)` (~:680) — **F1**, and additionally `ftbSuppress := False`.
- `when(resume.valid && stalled)` (~:706) — **F3**, and additionally `ftbSuppress := False`.
- `when(mispredictRedirect.valid)` (~:730) — **F2** (this port carries **two** distinct
  events: the commit flush AND the µcode `ucComplexResume` path — both need the flush), and
  additionally `ftbSuppress := False`.

Add this comment above the first of them:
```scala
      // F1-F4: EVERY block that sets `ringStale.foreach(_ := True)` also sets ftqFlush.
      // That co-location is the incoherence guard between the ring and the FTQ, and it has
      // NO exceptions. `ftbSuppress := False` on the architectural redirects only (F1/F2/
      // F3): a fresh path deserves a fresh prediction. NOT on predictFire (F4) -- see L1.
```

**F7 (I-fetch fault) and F11 (CINV/CPUSH) are handled by NOT adding a flush:** on `rspFault`
the FTQ is deliberately **kept** (dropping it would leave a truncated window + target words in
the IBuf with nothing describing the splice — silent corruption), and on
`invalidateAll`/`maintInvalidateAll` only the FTB `valids` are cleared while the FTQ, which
describes windows *already fetched*, is left alone. **F12** holds because `ftqFlush` never
touches `ringCount` — the ring is decremented only by `ic.rsp.valid`, with no timeout and no
other path, so no in-flight response can be stranded.

- [ ] **Step 9: Fix the `ftbSuppress` clear ordering (from Task 8 Step 6's note)**

Move `when(feed.fire) { ftbSuppress := False }` from the end of the Area to sit
**immediately before** the `when(predictFire)` block. Final intended priority, last-wins:

| writer | position | wins over |
|---|---|---|
| `when(feed.fire) { ftbSuppress := False }` | before `predictFire` | — |
| `when(ftqMismatch) { ftbSuppress := True }` | after `predictFire` | the feed.fire clear |
| `when(redirect / resume / mispredict) { ftbSuppress := False }` | last | everything |

- [ ] **Step 10: Double-shift avoidance on the GHR**

Extend the decode-side term of `gsShiftValid` (Task 8 Step 5) with `&& !ftqConfirm` — a
confirmed branch already shifted the GHR at fetch:
```scala
    gsShiftValid := ftbGhrShift ||
                    (feed.fire && !faultHold && condBtbHit0 && res.slot0Valid && !ftqConfirm)
```

- [ ] **Step 11: Compile and run the frontend suites**

```bash
cd ../wt-ftb
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.frontend.*"
```
Expected: clean compile with **no combinational-loop error**. If SpinalHDL reports a loop,
the cause is almost certainly `emittingFaultPacket` — re-check that it is gated only on
`ftqStarvedOrPast` and never on `ftqLenBad`/`ftqOvershoot`.

- [ ] **Step 12: Full gates (still `ftbEnable = False` by default)**

```bash
cd ../wt-ftb
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec" 2>&1 | tail -20
tools/fuzz/ported-sweep-parallel.sh 4 2>&1 | tail -20
diff <(sort ported_logs_parallel/all_fails.txt) <(sort /tmp/ftb_baseline_fails.txt)
IPC_SEED=1 IPC_MEM=zero JAVA_OPTS=-Xmx6g ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' 2>&1 | grep AGGREGATE
IPC_SEED=1 IPC_MEM=l2:5:70 JAVA_OPTS=-Xmx6g ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' 2>&1 | grep AGGREGATE
```
Expected: at Task 9's numbers. With no FTQ entries the entire confirm/mismatch block is
constant-False.

- [ ] **Step 13: Commit**

```bash
cd ../wt-ftb
git add src/main/scala/m68k040/frontend/FetchAlignPlugin.scala
git commit -m "frontend: confirm-or-flush guard, F1-F12 flush interactions, L1/L2 liveness

The load-bearing half of the fetch-directed BTB. On confirm (decodePc IS the claimed
branch, the aligner produced a SIMPLE packet, and its predecoded length matches) the branch
is emitted with predTaken/predTarget/phtIndex stamped and decodePc jumps straight to the
target -- no ibuf flush, no ringStale, no refetch. That is the entire IPC win.

On any disagreement the EXISTING redirect action fires verbatim at predictFire's priority:
  ftqLenBad    (A2) -> newPc = decodePcNext, the un-predicted packet at B still emits
                       (all its words are genuine) and the leftovers are flushed
  ftqOvershoot (A4) -> newPc = decodePcNext, caught in the SAME cycle as the skipping emit
  ftqStarved   (A1/A3/A5) -> newPc = decodePc; a 3-cycle dwell counter distinguishes a
                       genuine permanent starve from p0LiveReg's one-cycle
                       ambiguousLine transient after an availEff change
  ftqPast      (A7, defensive) -> newPc = decodePc

L1 (sticky ftbSuppress, cleared on the next feed.fire) and L2 (single-entry FTB invalidate)
are both MANDATORY: without L1 the mismatch flush livelocks; without L2 a permanently
mis-framing entry mismatches on every occurrence, which is strictly worse than today.

F1-F4 add ftqFlush to EVERY block that sets ringStale (the co-location is the incoherence
guard). F7 and F11 deliberately do NOT flush the FTQ -- dropping it would strand a live
splice. F8 gives the mismatch priority over the synthetic fault packet, gated only on the
starved/past classes, which is also what breaks the emittingFaultPacket -> feed.fire ->
ftqOvershoot combinational loop. F9 clears faultHold (ic.cmd.valid is gated on it, so
otherwise the refetch deadlocks). F12 holds because ftqFlush never touches ringCount.

Still gated at baseline with ftbEnable=False. Spec §2.8, §3.5, §3.6, §4."
```

---

### Task 11 (Slice 3e): `FtbConfirmGuardSpec` — the load-bearing directed test (spec §9.2)

**Files:**
- Create: `src/test/scala/m68k040/frontend/FtbTestDut.scala` (shared by Tasks 11, 12, 13)
- Create: `src/test/scala/m68k040/frontend/FtbConfirmGuardSpec.scala`

**Interfaces:**
- Produces: `m68k040.frontend.FtbTestDut` (a `Component`) with public members `ic`, `fa`,
  `ftb`, `gs`, `probe`, `drv`; plus the object `FtbTestDut` with `install`, `redirect`,
  `enable`, `runAndCollect` helpers used by Tasks 12 and 13.

**Construction rationale — read before writing code.** The DUT deliberately **omits**
`BtbPlugin` and `RasPlugin`, so `FetchAlignPlugin`'s `btbPredTaken0/1` and `rasPredValid`
fall back to their `allowOverride` zero defaults and the decode-time fallback is inert. That
isolates the fetch-directed path and makes every observed flush attributable to this lever.
The real `FtbPlugin` is used (not a stub): **wrong entries are constructible through the
genuine training path**, because training does not validate the claim against memory —
poking `{pc = B, len = Lb, target = T}` installs `brWordOff = B[2:1]`, `brLen = Lb` whatever
the bytes at `B` actually are. That is strictly stronger than a stub and it is the only way to
also exercise L1/L2.

**Target convention, used by BOTH Task 11 and Task 12:** always install
`target = B + 2*Lb`, i.e. the **fall-through of the claim**. The architectural instruction
stream is then *identical* whether or not the prediction is applied, so the emitted packet
sequence can be compared directly against the pure-sequential run. A wrong claim still
produces a wrong *fetch-side splice*; the guard's job is to make that invisible downstream.
`brType = 1` (unconditional) throughout, so direction is deterministic and the PHT/GHR play
no part in the differential.

- [ ] **Step 1: Write the shared DUT**

Create `src/test/scala/m68k040/frontend/FtbTestDut.scala`:

```scala
package m68k040.frontend

import m68k040.M68kParams
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.cache.{IcachePlugin, IcacheSim}
import m68k040.services.{BtbUpdateService, BtbUpdate}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database

/** Test-side BtbUpdate provider: a pokeable Flow exposed via BtbUpdateService.
  * `flatten.foreach(in(_))` exposes every payload field (including `len`) as top-level IO,
  * so this never needs editing when the payload grows. */
class FtbUpdateDriverPlugin extends FiberPlugin with BtbUpdateService {
  val logic = during build new Area {
    val upd = Flow(BtbUpdate())
    in(upd.valid)
    upd.payload.flatten.foreach(in(_))
  }
  override def btbUpdate: Flow[BtbUpdate] = logic.upd
}

/** Wires FtbPlugin + GsharePlugin to FetchAlignPlugin inside a Fiber build (doing it in the
  * raw Component body deadlocks the Fiber). Mirrors FullCoreSynth's wiring exactly, so the
  * component-level tests exercise the same connectivity the synthesised core has. */
class FtbWireFaPlugin extends FiberPlugin {
  val logic = during build new Area {
    val fa  = host[FetchAlignPlugin]
    val ftb = host[FtbPlugin]
    val gs  = host[GsharePlugin]
    val ic  = host[IcachePlugin]
    ftb.logic.invalidateAll := ic.logic.invalidateAll || ic.logic.maintInvalidateAll
    ftb.logic.queryPc  := fa.logic.ftbQueryPc
    ftb.logic.clrValid := fa.logic.ftbClrValid
    ftb.logic.clrPc    := fa.logic.ftbClrPc
    fa.logic.ftbHitIn       := ftb.logic.hitComb
    fa.logic.ftbBrWordOffIn := ftb.logic.brWordOffComb
    fa.logic.ftbBrLenIn     := ftb.logic.brLenComb
    fa.logic.ftbTargetIn    := ftb.logic.targetComb
    fa.logic.ftbBrTypeIn    := ftb.logic.brTypeComb
    gs.logic.specQueryPc := fa.logic.ftbQueryPc
    fa.logic.gsSpecIdxBaseIn := gs.logic.specIdxBase
    for (k <- 0 until 4) fa.logic.gsSpecTakenIn(k) := gs.logic.specTaken(k)
    gs.logic.shiftValid := fa.logic.gsShiftValid
    gs.logic.shiftDir   := fa.logic.gsShiftDir
  }
}

/** Component-level DUT for the fetch-directed BTB guard/equivalence/flush specs.
  *
  * DELIBERATELY omits BtbPlugin and RasPlugin: FetchAlign's btbPredTaken0/1 and rasPredValid
  * fall back to their allowOverride zero defaults, so the DECODE-time fallback is inert and
  * every flush observed here is attributable to this lever. */
class FtbTestDut extends Component {
  val db    = new Database
  val host  = db on (new PluginHost)
  val ic    = new IcachePlugin
  val fa    = new FetchAlignPlugin
  val ftb   = new FtbPlugin
  val gs    = new GsharePlugin
  val drv   = new FtbUpdateDriverPlugin
  val probe = new DecodeFeedProbePlugin
  val wire  = new FtbWireFaPlugin
  db.on { host.asHostOf(Seq[FiberPlugin](
    new ParamPlugin(M68kParams()), new IdentityTranslationPlugin,
    ic, fa, ftb, gs, drv, probe, wire)) }
}

/** One emitted packet, as observed on `feed`. The tuple DELIBERATELY excludes
  * predTaken/predTarget: a fetch-directed prediction legitimately changes *which*
  * instructions are fetched next, so the property under test is FRAMING equivalence --
  * exactly the §3 obligation and nothing more. Target-correctness is covered by the branch
  * EU's own verification and by the end-to-end suites. */
case class Emitted(pc: Long, lenWords: Int, words: Seq[Int],
                   simple: Boolean, complex: Boolean, fault: Boolean)

object FtbTestDut {
  /** m68k encodings used to build framing test images. Lengths are the TRUE architectural
    * lengths and are what PredecodeWord.classify returns. */
  val MOVEQ  : Seq[Int] = Seq(0x7000)                  // MOVEQ #0,%d0        -- 1 word
  val ADDIW  : Seq[Int] = Seq(0x0640, 0x0001)          // ADDI.W #1,%d0       -- 2 words
  val ADDIL  : Seq[Int] = Seq(0x0680, 0x0000, 0x0001)  // ADDI.L #1,%d0       -- 3 words
  val CPLX   : Seq[Int] = Seq(0xF000)                  // line-F coprocessor  -- COMPLEX

  /** Install (or overwrite) one FTB entry through the REAL training path. `target` defaults
    * to the claim's own fall-through, so the architectural stream is unchanged and the
    * emitted sequence can be compared against a pure-sequential run. */
  def install(dut: FtbTestDut, cd: ClockDomain, brPc: Long, brLen: Int,
              target: Long = -1L, brType: Int = 1, taken: Boolean = true): Unit = {
    val tgt = if (target >= 0) target else brPc + 2L * brLen
    dut.drv.logic.upd.valid          #= true
    dut.drv.logic.upd.payload.pc     #= brPc
    dut.drv.logic.upd.payload.taken  #= taken
    dut.drv.logic.upd.payload.target #= tgt
    dut.drv.logic.upd.payload.brType #= brType
    dut.drv.logic.upd.payload.len    #= brLen
    cd.waitSampling()
    dut.drv.logic.upd.valid #= false
    cd.waitSampling()
  }

  def redirect(dut: FtbTestDut, cd: ClockDomain, pc: Long): Unit = {
    dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= pc
    cd.waitSampling(); dut.fa.logic.redirect.valid #= false
  }

  /** Non-blocking ftbEnable poke. A poke issued right after forkStimulus is eaten by reset,
    * and a blocking waitSampling inserted to dodge that once ran BEFORE attachMemory and
    * hung the sim -- this is the pattern that works. */
  def enable(dut: FtbTestDut, cd: ClockDomain, on: Boolean): Unit = {
    fork { for (_ <- 0 until 8) { dut.fa.logic.ftbEnable #= on; cd.waitSampling() } }
  }

  def idle(dut: FtbTestDut, cd: ClockDomain): Unit = {
    dut.drv.logic.upd.valid       #= false
    dut.fa.logic.redirect.valid   #= false
    dut.fa.logic.resume.valid     #= false
    dut.probe.logic.feedOut.ready #= false
    dut.ic.logic.invalidateAll    #= false
    cd.waitSampling(3)
  }

  /** Run for `cycles` with feed always ready, collecting every emitted packet. */
  def runAndCollect(dut: FtbTestDut, cd: ClockDomain, cycles: Int): Seq[Emitted] = {
    val out = scala.collection.mutable.ArrayBuffer[Emitted]()
    dut.probe.logic.feedOut.ready #= true
    for (_ <- 0 until cycles) {
      cd.waitSampling()
      sleep(1)
      if (dut.probe.logic.feedOut.valid.toBoolean) {
        for (s <- 0 until 2) {
          val live = if (s == 0) true else dut.probe.logic.s1v.toBoolean
          if (live) {
            val p = dut.probe.logic.feedOut.payload(s)
            val n = p.lenWords.toInt
            out += Emitted(
              p.pc.toLong, n,
              (0 until math.max(n, 1)).map(i => p.words(i).toInt),
              p.simple.toBoolean, p.complex.toBoolean, p.fault.toBoolean)
          }
        }
      }
    }
    out.toSeq
  }
}
```

- [ ] **Step 2: Write `FtbConfirmGuardSpec` — one directed case per §3.5 row**

Create `src/test/scala/m68k040/frontend/FtbConfirmGuardSpec.scala`:

```scala
package m68k040.frontend

import m68k040.VerilatorTest
import m68k040.cache.IcacheSim
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import FtbTestDut._

/** THE load-bearing directed test for the fetch-directed BTB (task #126, design spec §9.2).
  *
  * One case per row of spec §3.5's exhaustive enumeration of the ways a stale or wrong FTB
  * entry can reach the confirm guard. Each asserts BOTH that the guard fires AND that the
  * emitted packet stream is byte-identical to the pure-sequential stream -- i.e. no packet
  * with a post-splice byte ever reached `feed`.
  *
  * COLLECT-THEN-ASSERT everywhere, never abort at the first mismatch. That flaw is exactly
  * what makes PredecodeWordSpec's "exhaustive 65536-opword" claim untrustworthy, and it is a
  * standing project warning.
  *
  * MUTATION DISCIPLINE (mandatory before this task is accepted -- see the plan's Step 4):
  * individually remove each of ftqLenBad, ftqOvershoot, ftqStarved, ftqPast, the availEff
  * clamp, L1 and L2. This suite must go RED for each. A guard term no mutation can kill is
  * either dead or untested and must be investigated before landing. */
class FtbConfirmGuardSpec extends AnyFunSuite {

  /** Build a word image from a sequence of instruction encodings, padded with MOVEQ. */
  def image(instrs: Seq[Seq[Int]], padTo: Int): Seq[Int] = {
    val flat = instrs.flatten
    flat ++ Seq.fill(math.max(0, padTo - flat.length))(0x7000)
  }

  /** Run one image twice -- prediction OFF then ON -- and return (sequential, predicted). */
  def bothArms(imageWords: Seq[Int], base: Long, entryPc: Long,
               installs: Seq[(Long, Int)], cycles: Int = 220,
               startAt: Long = -1L): (Seq[Emitted], Seq[Emitted]) = {
    def arm(on: Boolean): Seq[Emitted] = {
      var res: Seq[Emitted] = Nil
      SimConfig.withVerilator.compile(new FtbTestDut).doSim { dut =>
        val cd = dut.clockDomain; cd.forkStimulus(10)
        IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, imageWords)
        idle(dut, cd)
        for ((p, l) <- installs) install(dut, cd, p, l)
        enable(dut, cd, on)
        cd.waitSampling(10)
        redirect(dut, cd, if (startAt >= 0) startAt else entryPc)
        res = runAndCollect(dut, cd, cycles)
      }
      res
    }
    (arm(false), arm(true))
  }

  /** Assert the two arms emitted the same FRAMING, collecting every difference. */
  def assertSameFraming(label: String, seq: Seq[Emitted], pred: Seq[Emitted]): Unit = {
    val fails = scala.collection.mutable.ArrayBuffer[String]()
    if (pred.isEmpty) fails += s"$label: predicted arm emitted NOTHING (livelock?)"
    val n = math.min(seq.length, pred.length)
    if (n < 6) fails += s"$label: too few packets to be meaningful (seq=${seq.length} pred=${pred.length})"
    for (i <- 0 until n) {
      val a = seq(i); val b = pred(i)
      if (a.pc != b.pc || a.lenWords != b.lenWords || a.simple != b.simple ||
          a.complex != b.complex || a.fault != b.fault || a.words != b.words) {
        fails += f"$label packet #$i: sequential=$a  predicted=$b"
      }
    }
    assert(fails.isEmpty, "\n" + fails.take(25).mkString("\n"))
  }

  // ── A1: the real instruction at B is LONGER than claimed ────────────────────────
  test("A1: real length > Lb -- ftqStarved fires before any packet is emitted at B", VerilatorTest) {
    val base = 0x10000L
    // words: [0]=MOVEQ  [1..2]=ADDI.W (2 words, at base+2)  then MOVEQ filler.
    val img = image(Seq(MOVEQ, ADDIW), 64)
    // CLAIM: a 1-word branch at base+2. The real instruction there is 2 words.
    val (seq, pred) = bothArms(img, base, base, Seq((base + 2, 1)))
    assertSameFraming("A1", seq, pred)
  }

  // ── A2: the real instruction at B is SHORTER than claimed ───────────────────────
  test("A2: real length < Lb -- ftqLenBad fires, the un-predicted packet at B still emits", VerilatorTest) {
    val base = 0x11000L
    val img = image(Seq(MOVEQ, MOVEQ, MOVEQ), 64)   // everything is 1 word
    // CLAIM: a 2-word branch at base+2. The real instruction there is 1 word.
    val (seq, pred) = bothArms(img, base, base, Seq((base + 2, 2)))
    assertSameFraming("A2", seq, pred)
  }

  // ── A3: the instruction at B is COMPLEX ─────────────────────────────────────────
  test("A3: COMPLEX at B -- the complex arm is unreachable under the clamp, ftqStarved fires", VerilatorTest) {
    val base = 0x12000L
    val img = image(Seq(MOVEQ, CPLX), 64)           // line-F at base+2
    val (seq, pred) = bothArms(img, base, base, Seq((base + 2, 1)))
    assertSameFraming("A3", seq, pred)
  }

  // ── A4: no boundary at B; a straddling instruction ends between B and the splice ─
  test("A4: overshoot -- an instruction spanning B emits, then ftqOvershoot redirects", VerilatorTest) {
    val base = 0x13000L
    // ADDI.L at base is 3 words, so base+2 is INSIDE it, not a boundary.
    val img = image(Seq(ADDIL), 64)
    val (seq, pred) = bothArms(img, base, base, Seq((base + 2, 1)))
    assertSameFraming("A4", seq, pred)
  }

  // ── A5: an instruction straddles the SPLICE itself ──────────────────────────────
  test("A5: straddling the splice -- framing needs post-splice words, ftqStarved fires", VerilatorTest) {
    val base = 0x14000L
    // ADDI.W at base+2 (2 words) with a CLAIM of a 1-word branch at base+2 puts the splice
    // in the middle of it; ADDI.L at base+4 additionally straddles.
    val img = image(Seq(MOVEQ, ADDIL), 64)
    val (seq, pred) = bothArms(img, base, base, Seq((base + 2, 1)))
    assertSameFraming("A5", seq, pred)
  }

  // ── A6: entering the window INSIDE the claimed branch ───────────────────────────
  test("A6: pendingDrop > brWordOff -- declined at issue: no truncation, no FTQ push", VerilatorTest) {
    SimConfig.withVerilator.compile(new FtbTestDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x15000L
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq.fill(64)(0x7000))
      idle(dut, cd)
      // A branch at word offset 0 of the window...
      install(dut, cd, base, 1)
      enable(dut, cd, true)
      cd.waitSampling(10)
      // ...but ENTER the window at word offset 2.
      redirect(dut, cd, base + 4)
      cd.waitSampling(8)
      assert(dut.fa.logic.ftqCount.toInt == 0,
        "A6: brWordOff < pendingDrop must be declined at ISSUE -- no FTQ push")
      // And the ring must carry no truncation.
      val fails = scala.collection.mutable.ArrayBuffer[String]()
      for (j <- 0 until dut.fa.logic.ringCount.toInt) {
        val i = (dut.fa.logic.ringHead.toInt + j) % 3
        if (dut.fa.logic.ringKeep(i).toInt != 4)
          fails += s"A6: ringKeep($i)=${dut.fa.logic.ringKeep(i).toInt}, must stay 4"
      }
      assert(fails.isEmpty, "\n" + fails.mkString("\n"))
    }
  }

  // ── A7: decodePc already past B (defensive) ─────────────────────────────────────
  test("A7: ftqPast asserts when a poked FTQ head is behind decodePc", VerilatorTest) {
    SimConfig.withVerilator.compile(new FtbTestDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x16000L
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq.fill(64)(0x7000))
      idle(dut, cd)
      enable(dut, cd, false)
      cd.waitSampling(6)
      redirect(dut, cd, base + 0x40)
      dut.probe.logic.feedOut.ready #= true
      cd.waitSampling(30)
      // Force an FTQ head whose branch PC is BEHIND decodePc. A poke on a register output
      // holds only until the next clock edge, which is exactly long enough to observe the
      // combinational ftqPast.
      dut.fa.logic.ftq(0).brPc  #= base
      dut.fa.logic.ftq(0).brLen #= 1
      dut.fa.logic.ftqHead      #= 0
      dut.fa.logic.ftqCount     #= 1
      sleep(1)
      assert(dut.fa.logic.ftqPast.toBoolean,
        "A7: ftqPast must assert for an FTQ head behind decodePc " +
        "(if this is unkillable, ftqPast was written as `ftqNear && ftqDelta(31)`, " +
        "which is identically False -- see the plan's §10 Q3 resolution)")
    }
  }

  // ── A9: the head's branch is far away -- the clamp must be INACTIVE ─────────────
  test("A9: ftqDelta >= 16 -- no clamp, no confirm, no mismatch", VerilatorTest) {
    SimConfig.withVerilator.compile(new FtbTestDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x17000L
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq.fill(96)(0x7000))
      idle(dut, cd)
      // A branch far ahead of the entry point: base + 0x40 is 32 words from base.
      install(dut, cd, base + 0x40, 1)
      enable(dut, cd, true)
      cd.waitSampling(10)
      redirect(dut, cd, base)
      // Backpressure decode so fetch runs far ahead and the FTQ entry is pushed while
      // decodePc is still near `base`.
      dut.probe.logic.feedOut.ready #= false
      cd.waitSampling(40)
      sleep(1)
      val fails = scala.collection.mutable.ArrayBuffer[String]()
      if (dut.fa.logic.ftqCount.toInt > 0 && dut.fa.logic.ftqNear.toBoolean)
        fails += "A9: a >= 16-word-away head must NOT be ftqNear"
      if (dut.fa.logic.availEff.toInt != dut.fa.logic.ibufAvailProbe.toInt)
        fails += s"A9: clamp must be inactive (availEff=${dut.fa.logic.availEff.toInt} " +
                 s"avail=${dut.fa.logic.ibufAvailProbe.toInt})"
      if (dut.fa.logic.ftqConfirm.toBoolean) fails += "A9: confirm must not fire"
      if (dut.fa.logic.ftqMismatch.toBoolean) fails += "A9: mismatch must not fire"
      assert(fails.isEmpty, "\n" + fails.mkString("\n"))
    }
  }

  // ── L1 / L2: a REPEATED mismatch must not repeat ────────────────────────────────
  test("L1/L2: a repeated A3 case mismatches ONCE; the FTB entry is cleared and progress continues", VerilatorTest) {
    SimConfig.withVerilator.compile(new FtbTestDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x18000L
      val img = image(Seq(MOVEQ, CPLX), 64)
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, img)
      idle(dut, cd)
      install(dut, cd, base + 2, 1)       // claims a 1-word branch where a COMPLEX op sits
      enable(dut, cd, true)
      cd.waitSampling(10)

      var mismatches = 0
      var emits = 0
      val mon = fork {
        while (true) {
          cd.waitSampling(); sleep(1)
          if (dut.fa.logic.ftqMismatch.toBoolean) mismatches += 1
          if (dut.probe.logic.feedOut.valid.toBoolean &&
              dut.probe.logic.feedOut.ready.toBoolean) emits += 1
        }
      }
      redirect(dut, cd, base)
      dut.probe.logic.feedOut.ready #= true
      cd.waitSampling(200)
      mon.terminate()

      val fails = scala.collection.mutable.ArrayBuffer[String]()
      if (emits == 0) fails += "L1: no forward progress at all (livelock)"
      if (mismatches > 3)
        fails += s"L2: $mismatches mismatches -- the FTB entry was not cleared, so the " +
                 "same window mis-frames on every pass (strictly worse than today, violates G4)"
      assert(fails.isEmpty, "\n" + fails.mkString("\n"))
    }
  }

  // ── positive control: a WELL-FORMED entry confirms, with NO flush ───────────────
  test("positive control: a correct entry confirms -- predTaken stamped, ibuf NOT flushed", VerilatorTest) {
    SimConfig.withVerilator.compile(new FtbTestDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x19000L
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq.fill(64)(0x7000))
      idle(dut, cd)
      // TRUE claim: a 1-word instruction at base+2, target = its own fall-through.
      install(dut, cd, base + 2, 1)
      enable(dut, cd, true)
      cd.waitSampling(10)

      var confirms = 0
      var mismatches = 0
      var flushesDuringConfirm = 0
      val mon = fork {
        while (true) {
          cd.waitSampling(); sleep(1)
          val cfFire = dut.fa.logic.ftqConfirmFire.toBoolean
          if (cfFire) {
            confirms += 1
            if (dut.fa.logic.ibufFlushProbe.toBoolean) flushesDuringConfirm += 1
          }
          if (dut.fa.logic.ftqMismatch.toBoolean) mismatches += 1
        }
      }
      redirect(dut, cd, base)
      dut.probe.logic.feedOut.ready #= true
      cd.waitSampling(200)
      mon.terminate()

      val fails = scala.collection.mutable.ArrayBuffer[String]()
      if (confirms == 0)   fails += "positive control: the confirm path never fired -- " +
                                    "the whole IPC win is dead (check ftbEnable reached the DUT)"
      if (mismatches != 0) fails += s"positive control: $mismatches spurious mismatches on a TRUE claim"
      if (flushesDuringConfirm != 0)
        fails += s"positive control: the IBuf was flushed on $flushesDuringConfirm confirm cycles -- " +
                 "a confirm must NOT flush, that is the entire point"
      assert(fails.isEmpty, "\n" + fails.mkString("\n"))
    }
  }
}
```

- [ ] **Step 3: Add the two probes the spec needs, then run**

`FetchAlignPlugin` must expose `ibuf.io.flush` for the positive control. Add next to
`ibufAvailProbe` (Task 9 Step 3):
```scala
    val ibufFlushProbe = Bool(); ibufFlushProbe := ibuf.io.flush
    ibufFlushProbe.simPublic()
```
Then:
```bash
cd ../wt-ftb && ~/sbt/bin/sbt "testOnly m68k040.frontend.FtbConfirmGuardSpec"
```
Expected: all cases PASS. If A2 or A4 fail with a PC divergence, the guard is under-firing —
that is a **genuine correctness finding**, not a test bug. Investigate before proceeding.

- [ ] **Step 4: MUTATION TEST — mandatory, both directions**

For **each** mutation below: apply it, run `FtbConfirmGuardSpec`, confirm the suite goes
**RED**, then revert. Record the result per row. A term no mutation can kill is either dead
or untested and **must be investigated before landing**.

| # | mutation | expected to kill |
|---|---|---|
| M1 | `ftqLenBad := False` | A2 |
| M2 | `ftqOvershoot := False` | A4 |
| M3 | `ftqStarved := False` | A1, A3, A5 |
| M4 | `ftqPast := False` | A7 |
| M5 | `availEff := ibuf.io.avail` (delete the clamp entirely) | A1, A4, A5 (framing divergence) |
| M6 | delete L1 (`ftbSuppress := True` in the mismatch block) | L1/L2 (livelock / no forward progress) |
| M7 | delete L2 (`ftbClrValid := True` in the mismatch block) | L1/L2 (repeated mismatch count) |
| M8 | drop `res.slot0.lenWords === ftqHeadE.brLen` from `ftqConfirm` | A2 |
| M9 | drop `(ftbResBrWordOff >= pendingDrop)` from `applyPrediction` | A6 |
| M10 | drop `!ftqConfirm` from `predictedThisEmit` | positive control (a confirm would also flush) |

- [ ] **Step 5: Run every existing frontend + full gate**

```bash
cd ../wt-ftb
~/sbt/bin/sbt "testOnly m68k040.frontend.*"
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec" 2>&1 | tail -20
tools/fuzz/ported-sweep-parallel.sh 4 2>&1 | tail -20
diff <(sort ported_logs_parallel/all_fails.txt) <(sort /tmp/ftb_baseline_fails.txt)
```
Expected: `AlignerSpec`, `FetchAlignSpec`, `InstructionBufferSpec`, `BtbPluginSpec`,
`GshareSpec`, `RasPluginSpec`, `PredecodeSimpleLenSpec` all at exact baseline parity
(`PredecodeSimpleLenSpec` is now **load-bearing for INV-P** — cite it as such in the RTL
comment on the clamp if not already done); lock-step and corpus at baseline.

- [ ] **Step 6: Commit**

```bash
cd ../wt-ftb
git add src/test/scala/m68k040/frontend/FtbTestDut.scala \
        src/test/scala/m68k040/frontend/FtbConfirmGuardSpec.scala \
        src/main/scala/m68k040/frontend/FetchAlignPlugin.scala
git commit -m "test: FtbConfirmGuardSpec -- one directed case per design spec §3.5 row

Component-level DUT (IcachePlugin + FetchAlign + FtbPlugin + gshare + probe + a pokeable
BtbUpdate driver), DELIBERATELY without BtbPlugin/RasPlugin so the decode-time fallback is
inert and every flush is attributable to this lever. Wrong entries are installed through
the REAL training path, which does not validate the claim against memory -- strictly
stronger than a stub, and the only way to also exercise L1/L2.

Every entry is installed with target = B + 2*Lb (the claim's own fall-through), so the
ARCHITECTURAL stream is unchanged and each case asserts the emitted packet sequence is
byte-identical to the pure-sequential run: no packet with a post-splice byte ever reached
feed. Collect-then-assert throughout, never abort at first mismatch.

Cases: A1 (real longer), A2 (real shorter), A3 (COMPLEX at B), A4 (overshoot), A5 (straddles
the splice), A6 (entered inside the claimed branch -- declined at issue), A7 (ftqPast),
A9 (far head, clamp inactive), L1/L2 (a repeated mismatch must not repeat), plus the
positive control (a correct entry confirms with the IBuf NOT flushed).

Mutation-tested both directions (10 mutations, each must turn the suite red)."
```

---

### Task 12 (Slice 3f): `FtbStreamEquivalenceSpec` — the differential proof, and the A-vs-C decision point

> **THIS IS THE PIVOT POINT.** If this task cannot be closed with full confidence — the
> equivalence does not hold, or it holds only after weakening the property, or the mutation
> discipline cannot be satisfied — **do NOT patch the guard incrementally.** Abandon
> Approach A and go to **Task 16 (Approach C)**. That retreat is pre-authorised by design
> spec §7 and is a documented fallback, not a re-design.

**Files:**
- Create: `src/test/scala/m68k040/frontend/FtbStreamEquivalenceSpec.scala`

**Interfaces:**
- Consumes: `FtbTestDut`, `Emitted`, `install`, `redirect`, `enable`, `idle`,
  `runAndCollect` (Task 11).

**The property, stated exactly (spec §9.3):**

> For a randomized code image **and randomized (including deliberately WRONG) FTB
> contents**, the sequence of `(pc, lenWords, words[0 .. lenWords-1], simple, complex,
> fault)` tuples emitted on `feed` with `ftbEnable = True` is **IDENTICAL** to the sequence
> emitted with `ftbEnable = False`.

i.e. **fetch-direction is architecturally invisible.** The tuple deliberately excludes
`predTaken`/`predTarget`; the equivalence is over the **framing** of whatever is emitted,
which is exactly the §3 obligation and nothing more. Target-correctness is covered by the
branch EU's own verification (H8) and by the end-to-end suites.

- [ ] **Step 1: Write the spec**

Create `src/test/scala/m68k040/frontend/FtbStreamEquivalenceSpec.scala`:

```scala
package m68k040.frontend

import m68k040.{VerilatorTest, SlowTest}
import m68k040.cache.IcacheSim
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import FtbTestDut._

/** THE DIFFERENTIAL PROOF for the fetch-directed BTB (task #126, design spec §9.3).
  *
  * PROPERTY: for a randomized code image AND randomized (including deliberately WRONG) FTB
  * contents, the sequence of (pc, lenWords, words, simple, complex, fault) tuples emitted on
  * `feed` with ftbEnable=True is IDENTICAL to the sequence emitted with ftbEnable=False.
  * That is the statement "fetch-direction is architecturally invisible", and it is the
  * strongest available form of design spec §3's obligation.
  *
  * Every installed entry uses target = brPc + 2*brLen, the claim's OWN fall-through, so the
  * ARCHITECTURAL instruction stream is identical between the two arms by construction and
  * any divergence is a genuine FRAMING bug. The FTB contents are seeded with a deliberate
  * MIX of correct, length-wrong, offset-wrong and fully-aliased entries.
  *
  * Collect-then-assert, never abort at first mismatch. Report the counts ACTUALLY run --
  * do not silently shrink them. */
class FtbStreamEquivalenceSpec extends AnyFunSuite {

  /** Randomized image of SIMPLE instructions of mixed length, plus an occasional COMPLEX
    * op. Returns (words, boundaries) where `boundaries` are the true instruction PCs. */
  def randomImage(rnd: scala.util.Random, base: Long, nWords: Int): (Seq[Int], Seq[Long]) = {
    val ws = scala.collection.mutable.ArrayBuffer[Int]()
    val bs = scala.collection.mutable.ArrayBuffer[Long]()
    while (ws.length < nWords) {
      bs += base + 2L * ws.length
      rnd.nextInt(10) match {
        case 0 | 1 | 2 | 3 | 4 => ws ++= MOVEQ     // 1 word
        case 5 | 6 | 7         => ws ++= ADDIW     // 2 words
        case 8                 => ws ++= ADDIL     // 3 words
        case _                 => ws ++= CPLX      // COMPLEX
      }
    }
    (ws.toSeq.take(nWords + 4), bs.toSeq)
  }

  /** Seed a deliberately mixed FTB. `boundaries` are real instruction PCs. */
  def seedFtb(dut: FtbTestDut, cd: ClockDomain, rnd: scala.util.Random,
              base: Long, boundaries: Seq[Long], n: Int): Unit = {
    for (_ <- 0 until n) {
      val realPc = boundaries(rnd.nextInt(boundaries.length))
      val (pc, len) = rnd.nextInt(4) match {
        // (a) correct-ish: a real boundary with a plausible length
        case 0 => (realPc, 1 + rnd.nextInt(2))
        // (b) length-wrong: a real boundary with a deliberately wrong length
        case 1 => (realPc, 1 + rnd.nextInt(5))
        // (c) offset-wrong: NOT a boundary (nudged by one word)
        case 2 => (realPc + 2, 1 + rnd.nextInt(3))
        // (d) fully aliased: an arbitrary even PC anywhere in the image
        case _ => (base + 2L * rnd.nextInt(boundaries.length), 1 + rnd.nextInt(5))
      }
      install(dut, cd, pc, len)
    }
  }

  test("fetch-direction is architecturally invisible over randomized images x randomized FTBs",
       VerilatorTest, SlowTest) {
    val N_IMAGES = 100
    val N_TABLES = 10
    val WORDS    = 96
    val CYCLES   = 400
    val fails = scala.collection.mutable.ArrayBuffer[String]()
    var comparedPackets = 0
    var comparedRuns    = 0

    // Compile ONCE, reuse across every image/table -- the lock-step OOM post-mortem
    // (2026-07-21) showed per-test recompilation is what made these suites unrunnable.
    SimConfig.withVerilator.compile(new FtbTestDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val rnd = new scala.util.Random(0xF7B)

      for (img <- 0 until N_IMAGES) {
        val base = 0x20000L + 0x1000L * img
        val (words, bounds) = randomImage(rnd, base, WORDS)
        val mem = IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, words)

        for (tbl <- 0 until N_TABLES) {
          val tableSeed = rnd.nextLong()

          def arm(on: Boolean): Seq[Emitted] = {
            // Clear both the FTB and the FTQ/pipeline before each arm.
            idle(dut, cd)
            dut.ic.logic.invalidateAll #= true
            cd.waitSampling(); dut.ic.logic.invalidateAll #= false
            cd.waitSampling(4)
            seedFtb(dut, cd, new scala.util.Random(tableSeed), base, bounds, 8)
            enable(dut, cd, on)
            cd.waitSampling(10)
            redirect(dut, cd, base)
            runAndCollect(dut, cd, CYCLES)
          }

          val seqArm  = arm(false)
          val predArm = arm(true)
          comparedRuns += 1

          if (predArm.isEmpty && seqArm.nonEmpty) {
            fails += s"image $img table $tbl: predicted arm emitted NOTHING (livelock)"
          }
          val n = math.min(seqArm.length, predArm.length)
          if (n < 8) {
            fails += s"image $img table $tbl: only $n comparable packets " +
                     s"(seq=${seqArm.length} pred=${predArm.length}) -- too few to be meaningful"
          }
          for (i <- 0 until n) {
            val a = seqArm(i); val b = predArm(i)
            if (a != b) fails += s"image $img table $tbl packet #$i:\n  seq =$a\n  pred=$b"
            comparedPackets += 1
          }
        }
        mem.stop()   // release this image's AXI agent before attaching the next
      }
    }
    info(s"compared $comparedPackets packet tuples across $comparedRuns paired runs " +
         s"($N_IMAGES images x $N_TABLES randomized FTB tables)")
    assert(fails.isEmpty,
      s"${fails.size} framing divergences -- fetch-direction is NOT architecturally " +
      s"invisible:\n" + fails.take(25).mkString("\n"))
  }
}
```

**Implementation note the implementer must resolve, not paper over:** `IcacheSim`'s
`attachMemoryWithWords` returns an `Axi4ReadOnlySlaveAgent`. Confirm the exact API for
stopping/replacing an agent (read `src/test/scala/m68k040/cache/IcacheSim.scala`); two agents
on the same bus both responding will wedge the handshake — a hazard `FetchAlignSpec` already
documents. If the agent cannot be stopped, use `attachMemoryMutable` once and rewrite the
image contents in place between images instead, followed by an `invalidateAll`. **Do not**
work around it by shrinking `N_IMAGES` to 1.

Also add `SlowTest` to the imports only if that tag exists in
`src/test/scala/m68k040/TestTags.scala` (`VerilatorTest` is defined there at :7); if it does
not, drop it — `fastTest` already excludes `m68k040.SlowTest`, so tagging is a nicety, not a
requirement.

- [ ] **Step 2: Run it**

```bash
cd ../wt-ftb && JAVA_OPTS=-Xmx6g ~/sbt/bin/sbt "testOnly m68k040.frontend.FtbStreamEquivalenceSpec"
```
Expected: PASS, with the `info` line reporting the **actual** counts run. If wall-clock
forces a smaller sweep, **report the real numbers**; do not silently shrink and claim the
target.

- [ ] **Step 3: MUTATION TEST — same discipline as Task 11**

Apply each mutation, confirm the suite goes **RED**, revert, record:

| # | mutation | why it must be caught |
|---|---|---|
| M1 | `availEff := ibuf.io.avail` (delete the clamp) | the whole framing guard |
| M2 | `ftqLenBad := False` | a short-claim splice leaks |
| M3 | `ftqOvershoot := False` | a skipped boundary leaks |
| M4 | `ftqStarved := False` | permanent stall / livelock |
| M5 | `ringKeep(ringTail) := U(4,3 bits)` inside `applyPrediction` (truncate nothing) | the splice is misdescribed |
| M6 | drop the `RegNext(availEff) =/= availEff` term from `p0LiveInvalidate` | a stale `p0LiveReg` mis-frames |
| M7 | drop `!ftbSuppress` from `applyPrediction` | livelock on a repeating mismatch |

- [ ] **Step 4: THE A-vs-C DECISION**

State the verdict explicitly in the task report:

- **PASS with full mutation coverage ⇒ Approach A is proven. Continue to Task 13.**
- **FAIL, or passes only after weakening the property, or a mutation cannot be killed ⇒
  STOP.** Do not patch the guard. Record the exact failure mode, then **go to Task 16
  (Approach C)** — spec §7's pre-authorised retreat, ~+8-12 % instead of ~+20 %, one slice,
  no new hazard class. Tasks 13-15 are skipped in that case; Task 16 replaces them.
- Also record the verdict if Task 1's **G-T1b** forced a clamp redesign that reopened §3.4 —
  spec §7 names that as an independent trigger for the same pivot.

- [ ] **Step 5: Commit**

```bash
cd ../wt-ftb
git add src/test/scala/m68k040/frontend/FtbStreamEquivalenceSpec.scala
git commit -m "test: FtbStreamEquivalenceSpec -- fetch-direction is architecturally invisible

The differential proof for the fetch-directed BTB. Over randomized code images x randomized
(deliberately WRONG: length-wrong, offset-wrong, fully-aliased) FTB tables, the sequence of
(pc, lenWords, words, simple, complex, fault) tuples emitted on feed with ftbEnable=True is
IDENTICAL to the sequence with ftbEnable=False.

Every entry installs target = brPc + 2*brLen (the claim's own fall-through), so the
ARCHITECTURAL stream is identical between arms by construction and any divergence is a
genuine framing bug. The tuple deliberately excludes predTaken/predTarget: the property
under test is FRAMING equivalence -- exactly design spec §3's obligation and nothing more.

Collect-then-assert, one Verilator compile reused across every run, actual counts reported
rather than a claimed target. Mutation-tested (7 mutations, each must turn the suite red).

This is the A-vs-C decision point: passing here is what authorises continuing with
Approach A rather than falling back to §7's replay-buffer retreat."
```

---

### Task 13 (Slice 3g): `FtqFlushSpec` — F1-F12 plus the INV-A/INV-B live monitors

**Files:**
- Create: `src/test/scala/m68k040/frontend/FtqFlushSpec.scala`

**Interfaces:**
- Consumes: `FtbTestDut` and its helpers (Task 11).

- [ ] **Step 1: Write the spec**

Create `src/test/scala/m68k040/frontend/FtqFlushSpec.scala`:

```scala
package m68k040.frontend

import m68k040.VerilatorTest
import m68k040.cache.IcacheSim
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import FtbTestDut._

/** Flush / redirect / exception interaction for the fetch-directed BTB (design spec §4).
  *
  * One directed case per row of the F1-F12 checklist, plus the two structural invariants
  * asserted LIVE throughout (the cheapest possible falsifiability hook, mirroring Lever F's
  * diagFaultKind* precedent):
  *
  *   INV-A: ringKeep(i) > ringDrop(i) for every OCCUPIED ring entry.
  *          A violation means a zero-word or negative-width push.
  *   INV-B: a ring entry has ringKeep < 4 IFF an FTQ entry was pushed the same cycle.
  *          A violation means a splice with nothing describing it -- the F7 corruption. */
class FtqFlushSpec extends AnyFunSuite {

  /** Fork the two structural monitors. Returns the shared failure buffer. */
  def forkInvariantMonitors(dut: FtbTestDut, cd: ClockDomain)
      : (scala.collection.mutable.ArrayBuffer[String], SimThread) = {
    val fails = scala.collection.mutable.ArrayBuffer[String]()
    val t = fork {
      var cycle = 0
      while (true) {
        cd.waitSampling(); sleep(1)
        cycle += 1
        // INV-A over every occupied entry.
        val cnt  = dut.fa.logic.ringCount.toInt
        val head = dut.fa.logic.ringHead.toInt
        for (j <- 0 until cnt) {
          val i = (head + j) % 3
          val k = dut.fa.logic.ringKeep(i).toInt
          val d = dut.fa.logic.ringDrop(i).toInt
          if (k <= d) fails += s"cycle $cycle INV-A: ringKeep($i)=$k <= ringDrop($i)=$d"
        }
        // INV-B, sampled at the issue instant: a truncating issue must coincide with an
        // applyPrediction (which is the sole writer of both the truncation and the push).
        if (dut.ic.logic.cmdPort.valid.toBoolean && dut.ic.logic.cmdPort.ready.toBoolean) {
          val ap = dut.fa.logic.applyPrediction.toBoolean
          val tail = dut.fa.logic.ringTail.toInt
          // The write lands at the NEXT edge, so re-check on the following cycle.
          cd.waitSampling(); sleep(1)
          val k = dut.fa.logic.ringKeep(tail).toInt
          if ((k < 4) != ap)
            fails += s"cycle $cycle INV-B: ringKeep(tail=$tail)=$k but applyPrediction=$ap"
        }
      }
    }
    (fails, t)
  }

  /** Common setup: image of 1-word MOVEQs, one TRUE FTB entry, prediction enabled,
    * running from `base`. Leaves the sim mid-stream with live FTQ entries. */
  def running(dut: FtbTestDut, cd: ClockDomain, base: Long): Unit = {
    IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq.fill(96)(0x7000))
    idle(dut, cd)
    install(dut, cd, base + 2, 1)
    enable(dut, cd, true)
    cd.waitSampling(10)
    redirect(dut, cd, base)
    dut.probe.logic.feedOut.ready #= true
    cd.waitSampling(30)
  }

  test("F1: an external redirect flushes the FTQ and clears ftbSuppress", VerilatorTest) {
    SimConfig.withVerilator.compile(new FtbTestDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val (fails, mon) = forkInvariantMonitors(dut, cd)
      running(dut, cd, 0x30000L)
      redirect(dut, cd, 0x30000L + 0x40)
      cd.waitSampling(); sleep(1)
      if (dut.fa.logic.ftqCount.toInt != 0) fails += "F1: FTQ must be empty after a redirect"
      if (dut.fa.logic.ftbSuppress.toBoolean) fails += "F1: ftbSuppress must be cleared"
      cd.waitSampling(40)
      mon.terminate()
      assert(fails.isEmpty, "\n" + fails.take(20).mkString("\n"))
    }
  }

  test("F2: a mispredictRedirect pulse flushes the FTQ and clears ftbSuppress", VerilatorTest) {
    SimConfig.withVerilator.compile(new FtbTestDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val (fails, mon) = forkInvariantMonitors(dut, cd)
      running(dut, cd, 0x31000L)
      dut.fa.logic.mispredictRedirect.valid   #= true
      dut.fa.logic.mispredictRedirect.payload #= 0x31000L + 0x40
      cd.waitSampling()
      dut.fa.logic.mispredictRedirect.valid #= false
      sleep(1)
      if (dut.fa.logic.ftqCount.toInt != 0) fails += "F2: FTQ must be empty"
      if (dut.fa.logic.ftbSuppress.toBoolean) fails += "F2: ftbSuppress must be cleared"
      cd.waitSampling(40)
      mon.terminate()
      assert(fails.isEmpty, "\n" + fails.take(20).mkString("\n"))
    }
  }

  test("F3: resume while stalled flushes the FTQ", VerilatorTest) {
    SimConfig.withVerilator.compile(new FtbTestDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x32000L
      val (fails, mon) = forkInvariantMonitors(dut, cd)
      // A COMPLEX op stalls the aligner; a resume then clears it.
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base,
        Seq(0x7000, 0xF000) ++ Seq.fill(94)(0x7000))
      idle(dut, cd)
      install(dut, cd, base + 4, 1)
      enable(dut, cd, true)
      cd.waitSampling(10)
      redirect(dut, cd, base)
      dut.probe.logic.feedOut.ready #= true
      cd.waitSampling(40)
      dut.fa.logic.resume.valid #= true; dut.fa.logic.resume.payload #= base + 4
      cd.waitSampling(); dut.fa.logic.resume.valid #= false
      sleep(1)
      if (dut.fa.logic.ftqCount.toInt != 0) fails += "F3: FTQ must be empty after resume"
      cd.waitSampling(40)
      mon.terminate()
      assert(fails.isEmpty, "\n" + fails.take(20).mkString("\n"))
    }
  }

  test("F5/F6: an architectural redirect wins over a same-cycle confirm; a confirm never predictFires",
       VerilatorTest) {
    SimConfig.withVerilator.compile(new FtbTestDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x33000L
      val (fails, mon) = forkInvariantMonitors(dut, cd)
      running(dut, cd, base)
      // F6 is structural: assert it can never be observed.
      val f6 = fork {
        while (true) {
          cd.waitSampling(); sleep(1)
          if (dut.fa.logic.ftqConfirm.toBoolean && dut.fa.logic.predictFire.toBoolean)
            fails += "F6: predictFire fired on a CONFIRMED branch -- the win is lost"
        }
      }
      // F5: hammer redirects while confirms are happening; decodePc must follow the
      // redirect, never the FTQ target.
      for (i <- 0 until 20) {
        val tgt = base + 0x40 + 2 * (i % 8)
        dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= tgt
        cd.waitSampling(); dut.fa.logic.redirect.valid #= false
        sleep(1)
        if (dut.fa.logic.decodePc.toLong != tgt)
          fails += f"F5: decodePc=0x${dut.fa.logic.decodePc.toLong}%x after a redirect to 0x$tgt%x"
        cd.waitSampling(5)
      }
      f6.terminate(); mon.terminate()
      assert(fails.isEmpty, "\n" + fails.take(20).mkString("\n"))
    }
  }

  test("F7: an I-fetch fault does NOT drop the FTQ (the clamp must stay live)", VerilatorTest) {
    SimConfig.withVerilator.compile(new FtbTestDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x34000L
      val (fails, mon) = forkInvariantMonitors(dut, cd)
      // Attach memory covering only the first few windows; fetching past it faults.
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq.fill(16)(0x7000))
      idle(dut, cd)
      install(dut, cd, base + 2, 1)
      enable(dut, cd, true)
      cd.waitSampling(10)
      redirect(dut, cd, base)
      dut.probe.logic.feedOut.ready #= false     // hold decode so FTQ entries accumulate
      cd.waitSampling(30)
      val before = dut.fa.logic.ftqCount.toInt
      // Let fetch run off the end of the attached image to raise a fault.
      cd.waitSampling(60)
      sleep(1)
      if (dut.fa.logic.faultHold.toBoolean && dut.fa.logic.ftqCount.toInt == 0 && before > 0)
        fails += "F7: the FTQ was dropped on an I-fetch fault -- that strands a live splice " +
                 "(truncated window + target words in the IBuf with nothing describing them)"
      cd.waitSampling(20)
      mon.terminate()
      assert(fails.isEmpty, "\n" + fails.take(20).mkString("\n"))
    }
  }

  test("F11: an I-cache invalidate clears the FTB but LEAVES the FTQ alone", VerilatorTest) {
    SimConfig.withVerilator.compile(new FtbTestDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x35000L
      val (fails, mon) = forkInvariantMonitors(dut, cd)
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq.fill(96)(0x7000))
      idle(dut, cd)
      install(dut, cd, base + 2, 1)
      enable(dut, cd, true)
      cd.waitSampling(10)
      redirect(dut, cd, base)
      dut.probe.logic.feedOut.ready #= false     // hold decode so FTQ entries persist
      cd.waitSampling(30)
      val before = dut.fa.logic.ftqCount.toInt
      dut.ic.logic.invalidateAll #= true
      cd.waitSampling(); dut.ic.logic.invalidateAll #= false
      sleep(1)
      if (before > 0 && dut.fa.logic.ftqCount.toInt != before)
        fails += s"F11: the FTQ changed ($before -> ${dut.fa.logic.ftqCount.toInt}) on an " +
                 "invalidate -- it describes windows ALREADY FETCHED; cancelling it strands the splice"
      cd.waitSampling(20)
      mon.terminate()
      assert(fails.isEmpty, "\n" + fails.take(20).mkString("\n"))
    }
  }

  test("F12: a FTQ flush never strands an in-flight I-cache response (no ring wedge)", VerilatorTest) {
    SimConfig.withVerilator.compile(new FtbTestDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x36000L
      val (fails, mon) = forkInvariantMonitors(dut, cd)
      running(dut, cd, base)
      // Hammer redirects (each sets ftqFlush) while fetches are in flight, then let it
      // settle: the ring must drain and packets must keep flowing.
      for (i <- 0 until 30) {
        redirect(dut, cd, base + 2 * (i % 16))
        cd.waitSampling(2)
      }
      dut.probe.logic.feedOut.ready #= true
      var emits = 0
      for (_ <- 0 until 200) {
        cd.waitSampling(); sleep(1)
        if (dut.probe.logic.feedOut.valid.toBoolean) emits += 1
      }
      if (emits == 0) fails += "F12: no packets after redirect hammering -- the ring wedged"
      if (dut.fa.logic.ringCount.toInt > 3) fails += "F12: ringCount out of range"
      mon.terminate()
      assert(fails.isEmpty, "\n" + fails.take(20).mkString("\n"))
    }
  }
}
```

**F4, F8, F9, F10 note.** F4 (`predictFire` also flushes the FTQ) is unreachable in this DUT
because `BtbPlugin`/`RasPlugin` are deliberately absent, so `predictFire` never fires here —
it is instead covered structurally by the F6 monitor plus the full-core gates in Task 14.
F8/F9 (fault-vs-mismatch priority and the `faultHold` clear) are covered by F7's fault case
combined with `FtbConfirmGuardSpec`'s A3/L1 cases. F10 (`quiesce`/`stalled`) is covered by
F3's complex-stall case. **State this coverage mapping explicitly in the task report** — do
not claim F1-F12 are each individually tested when four are covered by argument.

- [ ] **Step 2: Add the `predictFire`/`faultHold`/`decodePc` probes if missing, then run**

`predictFire` and `faultHold` need `simPublic`. Add next to the existing
`spinal.core.sim.SimPublic(decodePc, fetchPc)` line (~:137):
```scala
    spinal.core.sim.SimPublic(faultHold)
```
and after `predictFire := feed.fire && !faultHold && predictedThisEmit` (~:661):
```scala
    predictFire.simPublic()
```
Also confirm `IcachePlugin.logic.cmdPort` is the correct handle for the INV-B monitor's issue
detection; if the FetchService `cmd` handshake is exposed differently, use
`dut.fa.logic.applyPrediction` sampled against `ringTail` movement instead.

```bash
cd ../wt-ftb && ~/sbt/bin/sbt "testOnly m68k040.frontend.FtqFlushSpec"
```
Expected: all cases PASS.

- [ ] **Step 3: Run the invariant monitors across the other frontend suites**

Spec §9.4 asks for INV-A/INV-B "running throughout every other frontend test". Achieve this
cheaply by calling `forkInvariantMonitors` at the top of every `FtbConfirmGuardSpec` and
`FtbStreamEquivalenceSpec` sim body as well, and asserting the buffer is empty at the end.
Add those calls now.

```bash
cd ../wt-ftb && ~/sbt/bin/sbt "testOnly m68k040.frontend.*"
```
Expected: all green, with the invariants live.

- [ ] **Step 4: Commit**

```bash
cd ../wt-ftb
git add src/test/scala/m68k040/frontend/FtqFlushSpec.scala \
        src/test/scala/m68k040/frontend/FtbConfirmGuardSpec.scala \
        src/test/scala/m68k040/frontend/FtbStreamEquivalenceSpec.scala \
        src/main/scala/m68k040/frontend/FetchAlignPlugin.scala
git commit -m "test: FtqFlushSpec -- F1-F12 interactions + live INV-A/INV-B monitors

Directed cases for F1 (external redirect), F2 (mispredictRedirect, which carries BOTH the
commit flush and the ucode ucComplexResume path), F3 (resume while stalled), F5/F6
(architectural redirect beats a same-cycle confirm; a confirm never also predictFires),
F7 (an I-fetch fault must NOT drop the FTQ -- dropping it strands a live splice), F11 (an
I-cache invalidate clears the FTB but leaves the FTQ alone, for the same reason), and F12
(a FTQ flush never strands an in-flight response -- ringCount is decremented only by
rsp.valid, so no wedge is created).

F4/F8/F9/F10 are covered by argument plus other suites; the mapping is stated in the spec's
own comment rather than claimed as individual coverage.

INV-A (ringKeep > ringDrop on every occupied entry) and INV-B (ringKeep < 4 iff an FTQ entry
was pushed the same cycle) now run as live monitors across all three new frontend suites."
```

---

### Task 14 (Slice 3 gate): enable by default, then measure the payoff

> **Sequencing is deliberate: the correctness proof (Tasks 11-13) is complete BEFORE the
> number is chased.** A wrong-but-fast implementation is worse than a right-but-unmeasured
> one. Do not reorder.

**Files:**
- Modify: `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala` (one line: the
  `ftbEnable` reset value)
- Modify: `src/main/scala/m68k040/Config.scala` (only if the FTQ depth sweep changes the
  default)

- [ ] **Step 1: Flip the default on**

```scala
    val ftbEnable = RegInit(True); ftbEnable := ftbEnable; ftbEnable.simPublic()
```
Update the comment above it to record that the default flipped here, and why (Tasks 11-13
closed the correctness proof).

- [ ] **Step 2: Prove it is actually ON in the full core**

The recorded trap is a "feature" that measures bit-identical because the control never
reached the DUT. Before trusting any number:
```bash
cd ../wt-ftb
IPC_SEED=1 IPC_MEM=zero JAVA_OPTS=-Xmx6g ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' 2>&1 | grep -E 'AGGREGATE|hot-loop'
```
Expected: **`hot-loop` moves.** If every kernel is bit-identical to Task 2, the feature is
inert — stop and find out why (check `grep -c FtbPlugin generated/M68kFullCoreSynth.v`, check
that all four DUTs got the Task 6 wiring, check `ftbEnable`'s reset value actually elaborated).

- [ ] **Step 3: Functional gates, prediction LIVE**

```bash
cd ../wt-ftb
~/sbt/bin/sbt "testOnly m68k040.frontend.*"
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec" 2>&1 | tail -20
```
Expected: lock-step at **Task 2's exact count with a byte-identical fail-name set**. A new
lock-step failure here is a real architectural divergence — the strongest possible signal
that §3's guard has a hole. Do not proceed past it.

- [ ] **Step 4: Branch/redirect-heavy ported tests FIRST, then the full corpus**

Spec §9.5 is explicit that a fetch-direction framing bug manifests as a wrong-target or
wrong-PC divergence, not as a slow clock. Run the branch-heavy subset explicitly before the
48-minute sweep:
```bash
cd ../wt-ftb
ls src/test/resources/m68kooo-ported-tests/asm | grep -E 'bcc|bra|bsr|jmp|jsr|rts|dbcc|branch|loop|call'
# run those by name through the ported runner, then:
tools/fuzz/ported-sweep-parallel.sh 4 2>&1 | tail -20
diff <(sort ported_logs_parallel/all_fails.txt) <(sort /tmp/ftb_baseline_fails.txt)
```
Expected: `diff` **empty** — 58 fails, name-for-name identical.

- [ ] **Step 5: THE IPC GATE — the accept/reject decision (spec §9.6)**

Worktree-isolated, paired against Task 2's baseline, `IPC_SEED ∈ {1,2,3,4,5}` pinned, both
memory models:
```bash
cd ../wt-ftb
for s in 1 2 3 4 5; do
  IPC_SEED=$s IPC_MEM=zero   JAVA_OPTS=-Xmx6g ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' 2>&1 | tee /tmp/ipc_ftb_zero_$s.log | grep -E 'AGGREGATE|dependent|independent|mixed|hot-loop|branchy|call-return|load/store'
  IPC_SEED=$s IPC_MEM=l2:5:70 JAVA_OPTS=-Xmx6g ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' 2>&1 | tee /tmp/ipc_ftb_l2_$s.log  | grep -E 'AGGREGATE|dependent|independent|mixed|hot-loop|branchy|call-return|load/store'
done
```

**ACCEPT iff ALL of:**
- realistic-memory (`l2:5:70`) aggregate **≥ +10 %** vs Task 2 (projection ~+20 %);
- `hot-loop`, `branchy` and `call-return` **each** improve — they are the target;
- **no kernel regresses more than 2 %**, with the explicit caveat that `load/store` has real
  seed-to-seed variance (Task 2 measured its 5-seed spread; read its band against **that**,
  not against zero — this is the trap that produced the earlier "-1.77 % regression" false
  alarm, closed as PRNG noise).

**Additionally report, whatever the verdict:**
- the **`branchy` delta specifically**, as the adjudicator of §2.6's fetch-time-direction
  accuracy trade. This is *not* claimed to be neutral; report it honestly either way.
- the **ideal-memory** aggregate (projection ~+29 %).

**REJECT ⇒ evaluate Approach C (Task 16) against the same gate before abandoning the lever.**

- [ ] **Step 6: FTQ depth sweep — measured, not asserted**

Re-run seeds 1-3 under `IPC_MEM=l2:5:70` with `ftqDepth` ∈ {2, 4, 6} (edit `Config.scala`,
rebuild, measure, restore). Note that 6 is **not** a power of two, which is exactly why
`ftqInc` carries the explicit wrap helper. Adopt the best depth; if 4 is within noise of the
best, keep 4 (fewest FFs). Record all three.

- [ ] **Step 7: Post-route + LUT gate (Slice 3's own attribution point)**

Check contention, wait for a window, then:
```bash
cd ../wt-ftb
JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
vivado -mode batch -nojournal -log synth/vivado_FullCore.log -source synth/impl_FullCore.tcl
vivado -mode batch -nojournal -log synth/vivado_census.log \
       -source synth/census.tcl -tclargs synth/fullcore_routed.dcp synth/census_slice3
```
Report WNS, top-10 endpoints **with their families**, TNS, failing-endpoint count **against
Task 1's census**, and CLB LUTs / LUT-as-Memory / FFs. At this point Lever D's `spec2` cone
is still present, so expect the frontend family to be roughly unchanged and the LUT count to
be **up**; the credit lands in Task 15. Pay particular attention to whether the
`Aligner preds(L0) → slot1Ok → io_shift` family (spec §5.3's second honest caveat) has moved —
the `availEff` clamp lands directly on `slot1Ok`.

- [ ] **Step 8: Commit**

```bash
cd ../wt-ftb
git add src/main/scala/m68k040/frontend/FetchAlignPlugin.scala src/main/scala/m68k040/Config.scala
git commit -m "frontend: enable fetch-directed BTB by default + Slice 3 gate

ftbEnable defaults True now that the correctness proof is closed (FtbConfirmGuardSpec's
per-§3.5-row directed cases with 10 mutations, FtbStreamEquivalenceSpec's differential
'fetch-direction is architecturally invisible' with 7 mutations, and FtqFlushSpec's F1-F12
with live INV-A/INV-B monitors).

Gated with prediction LIVE: lock-step at baseline with a byte-identical fail set, ported
corpus diff-empty, IPC measured across 5 pinned seeds x both memory models with the FTQ
depth swept over {2,4,6}, and a full post-route + census run. All numbers in the commit
trailer / task report -- measured, paired, worktree-isolated, uncontended."
```

---

### Task 15 (Slice 4): delete Lever D's `spec2` cone and re-source `slot1WouldPred` from the FTQ

> **Gated SEPARATELY so the FMax and LUT delta is attributable.** This session's repeated
> lesson is that combined gates hide which lever did what.

**Files:**
- Modify: `src/main/scala/m68k040/frontend/Btb.scala` (delete lines **72-87** and **115-201**)
- Modify: `src/main/scala/m68k040/frontend/Aligner.scala` (delete `slot1Sel`: the comment at
  **18-26**, the field at **27**, and the assignment `r.slot1Sel := L0` at **102**)
- Modify: `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala` (`btbQueryBasePc1`/
  `btbQuerySel1` declarations ~:80 and drives ~:439-440; `slot1WouldPred` ~:467;
  `btbPredTaken1`/`btbPredTarget1`/`gsBtbHit1`/`gsBtbType1` if they become dead)
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala` (:96-98, :101-102, :133-134)
- Modify: `src/test/scala/m68k040/fuzz/FuzzDut.scala` (:174-176, :179-180, :197-198)
- Modify: `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` (:253-255, :258-259, :280-281)
- Modify: `src/test/scala/m68k040/bench/IpcBenchSpec.scala` (:188-190, :193-194, :215-216)
- **Delete**: `src/test/scala/m68k040/frontend/BtbLateSelectEquivalenceSpec.scala` (319 lines)

- [ ] **Step 1: Re-source `slot1WouldPred` from the FTQ**

Replace `val slot1WouldPred = slot1PredTaken && res.slot1Valid && res.slot0Valid && !slot0IsPred`
(~:467) with:

```scala
    // ── slot-1 defer, re-sourced from the FTQ (task #126, Slice 4) ───────────────
    // This was the ONLY architectural consumer of Lever D's 9-way `spec2` speculative BTB
    // read -- the design's measured #1 failing cone (3.42 ns of a 4.487 ns path, 14 levels:
    // CARRY8 index adder -> RAMD64E -> tag compare -> 16:1 MUXF7 late select -> suppressSlot1
    // -> decodePc). It is now a 4-BIT COMPARE against a register-derived value, ~1 LUT level.
    //
    // Compares `res.slot0.lenWords` (== L0) rather than the deleted `Aligner.Result.slot1Sel`:
    // `slot1Sel` existed SOLELY to reach the BTB one arm-mux earlier than `slot0.lenWords`,
    // and with the RAM read gone that mux level no longer buys anything -- so `slot1Sel` is
    // deleted outright.
    //
    // Semantics preserved: "the FTQ head's branch is the instruction that would land in
    // SLOT 1 this cycle" (i.e. it starts exactly L0 words after decodePc), slot 1 is live,
    // and this is not already the confirmed slot-0 branch. Defer it so it becomes slot 0
    // next cycle and takes the single-slot confirm path.
    val slot1WouldPred = ftqNear && !ftqAt0 &&
                         (ftqDelta === res.slot0.lenWords) &&
                         res.slot1Valid && res.slot0Valid && !ftqConfirm
```

Then delete the now-dead `val slot1PredTaken = btbPredTaken1` line and, if nothing else reads
them, the `btbPredTaken1`/`btbPredTarget1`/`gsBtbHit1`/`gsBtbType1` ports and their idle
defaults. **Grep before deleting each** — `gsBtbHit1`/`gsBtbType1` may still be referenced.

- [ ] **Step 2: Delete the `spec2` block from `Btb.scala`**

Delete the `query2*` port comment + declarations (**72-87**) and the Lever-D rationale block +
`spec2*` Vecs + 9-way loop + late select (**115-201**), and the corresponding
`predTaken2Comb.simPublic(); predTarget2Comb.simPublic(); predHit2Comb.simPublic();
predType2Comb.simPublic()` lines. Anchor by content grep, not by these line numbers.

- [ ] **Step 3: Delete `slot1Sel` from `Aligner.scala`**

Remove the field (**27**), its comment (**18-26**) and `r.slot1Sel := L0` (**102**).

- [ ] **Step 4: Drop the wiring in all five consumers**

Remove `btb.logic.query2BasePc/query2Sel/query2Valid`, `fa.logic.btbPredTaken1/btbPredTarget1`
and `fa.logic.gsBtbHit1/gsBtbType1` (the `predHit2Comb`/`predType2Comb` sources) from
`FullCoreSynth.scala`, `FuzzDut.scala`, `ExecuteLockStepSpec.scala` and `IpcBenchSpec.scala`.
Also remove `fa.logic.btbQueryBasePc1 := decodePc` / `btbQuerySel1 := res.slot1Sel` from
`FetchAlignPlugin.scala` and the port declarations at ~:80.

- [ ] **Step 5: Delete `BtbLateSelectEquivalenceSpec.scala` and record the retirement**

```bash
cd ../wt-ftb && git rm src/test/scala/m68k040/frontend/BtbLateSelectEquivalenceSpec.scala
```
This was a **49 152-point / 3-mutations-caught** proof. Its removal is a real, deliberate
coverage retirement, not an accident — record it explicitly in the ledger (Task 17) with the
reason: its subject (`spec2*`) no longer exists.

- [ ] **Step 6: Compile and run every gate**

```bash
cd ../wt-ftb
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.frontend.*"
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec" 2>&1 | tail -20
tools/fuzz/ported-sweep-parallel.sh 4 2>&1 | tail -20
diff <(sort ported_logs_parallel/all_fails.txt) <(sort /tmp/ftb_baseline_fails.txt)
```
Expected: all green at baseline. `slot1WouldPred`'s coverage changes shape (it now defers on
an FTQ-covered branch rather than a BTB-covered one), so a **small IPC movement is legitimate
here** — measure it, do not assume it.

- [ ] **Step 7: IPC re-measure (the defer's coverage genuinely changed)**

```bash
cd ../wt-ftb
for s in 1 2 3; do
  IPC_SEED=$s IPC_MEM=l2:5:70 JAVA_OPTS=-Xmx6g ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' 2>&1 | grep -E 'AGGREGATE|branchy|call-return|hot-loop'
done
```
Compare against Task 14's numbers, not Task 2's. A regression > 2 % on any branch kernel means
the re-sourced defer under-covers; report it rather than absorbing it into the aggregate.

- [ ] **Step 8: THE FMax + LUT GATE — this is where the delta lands**

Check contention, wait for a genuinely uncontended window, then:
```bash
cd ../wt-ftb
JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
vivado -mode batch -nojournal -log synth/vivado_FullCore.log -source synth/impl_FullCore.tcl
vivado -mode batch -nojournal -log synth/vivado_census.log \
       -source synth/census.tcl -tclargs synth/fullcore_routed.dcp synth/census_slice4
```

**Accept criteria (spec §9.7):**
- FMax **≥ 221.828 MHz - 2 MHz** (no material regression). Note spec §5.3's own honest
  bound: even *perfect* frontend relief only moves WNS -0.508 → ~-0.454, i.e. ~+1.2 %,
  because the design is a **flat wall** (4512 failing endpoints, TNS -462.267 ns).
  **Do not gate this lever on a top-line MHz number** — gate on TNS and the failing-endpoint
  count against Task 1's census, which is what this initiative has used all session.
- the `spec2` family is **absent from the top-10** and `CENSUS_PROBE spec2_leverD CELLS`
  reports **0**.
- **LUT: explicit pre/post.** Expected net ≤ 0 versus Task 2's base: Lever D's -1302 LUTs /
  -512 LUTRAM is deleted here, against the FTB/FTQ's cost measured in Tasks 6 and 14.
  **A material LUT increase is a REJECT under G3, independent of FMax and IPC.**

- [ ] **Step 9: Commit**

```bash
cd ../wt-ftb
git add -A src/main/scala/m68k040/frontend/Btb.scala \
           src/main/scala/m68k040/frontend/Aligner.scala \
           src/main/scala/m68k040/frontend/FetchAlignPlugin.scala \
           src/main/scala/m68k040/top/FullCoreSynth.scala \
           src/test/scala/m68k040/fuzz/FuzzDut.scala \
           src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala \
           src/test/scala/m68k040/bench/IpcBenchSpec.scala \
           src/test/scala/m68k040/frontend/BtbLateSelectEquivalenceSpec.scala
git commit -m "frontend: delete Lever D's spec2 cone, re-source slot1WouldPred from the FTQ

slot1WouldPred was the ONLY architectural consumer of the 9-way speculative BTB slot-1 read,
and the netlist says that cone is the design's #1 failing path -- 3.42 ns of a 4.487 ns,
14-level path (CARRY8 index adder -> RAMD64E -> tag compare -> 16:1 MUXF7 late select ->
suppressSlot1 -> decodePc), with 7 of the top-10 setup paths in the same family. It is now a
4-bit compare of ftqDelta against L0, ~1 LUT level, off registers.

Deletes: Btb.scala's query2*/spec2* block, Aligner.Result.slot1Sel (it existed solely to
reach the BTB one arm-mux earlier, which buys nothing once the RAM read is gone), the
btbQueryBasePc1/btbQuerySel1 ports and the slot-1 predict wiring in all four DUTs.

The decode-time SLOT-0 BTB/gshare/RAS read is RETAINED -- deliberate departure from the
grounding's sketch (spec §5.2): deleting it would convert every FTB-uncovered branch from a
4-cycle predicted restart into a ~14-cycle commit-time mispredict and could make the lever a
net IPC loss. Revisitable in Phase 2 once real FTB coverage is measured.

Retires BtbLateSelectEquivalenceSpec (319 lines, a 49152-point / 3-mutations-caught proof)
because its subject no longer exists -- a deliberate coverage retirement, recorded in the
ledger, not an accident.

Gated SEPARATELY so this slice's FMax/TNS/LUT delta is attributable."
```

---

### Task 16 (CONDITIONAL — the pre-authorised retreat): Approach C, the recent-fetch-window replay buffer

> **DO NOT IMPLEMENT THIS unless Task 12's decision came out REJECT**, or Task 1's G-T1b
> forced a clamp redesign that reopened §3.4, or Task 14's IPC gate rejected. It is design
> spec §7's documented retreat: **~+8-12 % aggregate instead of ~+20 %, one slice, one gate,
> no new hazard class.** If you land here, **abandon Approach A entirely** — revert Tasks
> 7-15 — rather than patching A's guard incrementally. That instruction is explicit in §7.

**Files:**
- Modify: `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala` (the replay buffer lives
  entirely in the `redirect → ibuf.push` cone)
- Test: `src/test/scala/m68k040/frontend/FetchAlignSpec.scala` (extend) or a new
  `FetchReplaySpec.scala`

**What changes and what does not.** **Nothing about prediction changes.** `predictFire`'s
flush stays exactly as-is; `predTaken`/`predTarget`/EU verification/mispredict recovery are
bit-identical to today. §3 does not apply at all: the IBuf still holds the **sequential**
stream from `decodePc`, there is no splice, no truncation, no length guess and no clamp.

**Mechanism (spec §7.1):**
```scala
case class ReplayEntry() extends Bundle {
  val valid = Bool()
  val base  = UInt(29 bits)              // window base, pc[31:3]
  val data  = Bits(64 bits)              // the 4 words
  val preds = Vec(ChunkPredecode(), 4)
}
```
- ~4 × (1 + 29 + 64 + ~40) = **~536 FF**. No RAM, no new table, no training path.
- **Fill:** on every non-stale, non-fault `ic.rsp.valid`, round-robin, straight from the
  `rspWords`/`rspPreds` values already live in the push cone
  (`FetchAlignPlugin.scala:271-272`). Zero new datapath.
- **Use:** on ANY redirect (external / resume / mispredict / `predictFire`), compare
  `newPc(31 downto 3)` against the 4 `base` fields. On a hit, push that window into the IBuf
  **on cycle N+1** with the leading drop `newPc(2 downto 1)` applied, instead of waiting for
  the I-cache round trip; set `fetchPc := window + 8` so the sequential walk continues from
  the next window. The 4-cycle restart becomes ~1 cycle.
- **Coherence:** cleared on **both** `invalidateAll` and `maintInvalidateAll`, exactly as the
  BTB is. That is the *only* coherence obligation, and it is the same one the BTB already
  discharges. A replay entry is consumed at most a few cycles after it was fetched, so its
  staleness exposure is strictly narrower than the BTB's.

**Honest limits to state up front (spec §7.3):** helps only targets whose window is among the
last 4 fetched — `hot-loop` **yes**, `call-return`'s `rts` **often**, `branchy` **partially**,
cold/far targets **never**. Leaves the ~1-cycle `slot1WouldPred` defer untouched, and does
**not** delete Lever D's `spec2` cone, so it carries none of Approach A's FMax or LUT credit.

- [ ] **Step 1: Record the pivot decision and its trigger in the task report and the ledger**
- [ ] **Step 2: Revert Tasks 7-15's RTL** (keep Tasks 1-6 — the census, the baselines, the
      `len` plumbing and `Ftb.scala` are all independently useful, but `FtbPlugin` must be
      un-instantiated from the four DUTs so it does not cost area for nothing)
- [ ] **Step 3: Implement the replay buffer** per the mechanism above
- [ ] **Step 4: Write `FetchReplaySpec`** — a hit replays in ~1 cycle with the correct
      leading drop; a miss behaves exactly as today; `invalidateAll` and
      `maintInvalidateAll` each clear it; a stale entry is never consumed after either
- [ ] **Step 5: Full functional gates** (lock-step at baseline, corpus diff-empty)
- [ ] **Step 6: IPC gate**, same protocol as Task 14 Step 5. **Expect +8-12 %**, not +20 %.
- [ ] **Step 7: Post-route + LUT gate.** Near-zero corridor risk is claimed (entirely in the
      `redirect → ibuf.push` cone, fed from registers, touching neither the `decodePc` loop
      nor the `fetchPc` loop) — **verify it, do not assume it**.
- [ ] **Step 8: Commit**, with a message that states plainly this is the §7 retreat, why the
      pivot was taken, and what was given up.

---

### Task 17: merge, ledger, and honest close-out

**Files:**
- Modify: `.superpowers/sdd/progress-ipc-push-2026-08-09.md` (git-ignored but force-tracked:
  `git add -f`)

- [ ] **Step 1: Re-run every gate on the merge result, BEFORE the merge commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git checkout feat/rob-predictor-mem && git pull --ff-only 2>/dev/null || true
git merge --no-commit --no-ff feat/ipc-ftb
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec" 2>&1 | tail -20
tools/fuzz/ported-sweep-parallel.sh 4 2>&1 | tail -20
```
The branch may have moved (a concurrent session has been landing FPU docs and vendored tests).
**Run the suites on the merge result and only then make the merge commit** — that is the
discipline the I-side chain used.

- [ ] **Step 2: Final post-route gate on the merge result**

Contention check, then a full `impl_FullCore.tcl` + `census.tcl` run. Report WNS / FMax /
TNS / failing endpoints / CLB LUTs / LUT-as-Memory / FFs against **Task 1's census**.

- [ ] **Step 3: Append the ledger entry**

Append to `.superpowers/sdd/progress-ipc-push-2026-08-09.md` a section covering, at minimum:
- Task 1's census: total failing endpoints, the family table, and the **G-T1a/G-T1b/G-T1c**
  verdicts (this is the artifact future FMax passes will reuse).
- Which approach landed (A or C) and, if C, **why the pivot was taken**.
- The IPC table: per-kernel and aggregate, 5 seeds × 2 memory models, paired base-vs-final,
  plus the FTQ depth sweep.
- The `branchy` delta specifically, as the §2.6 fetch-time-direction accuracy adjudicator —
  **stated honestly whichever way it went**; it was never claimed to be neutral.
- FMax / TNS / failing-endpoint / LUT / FF deltas, with the Slice-3 and Slice-4 numbers
  **separately attributable**.
- The `BtbLateSelectEquivalenceSpec` retirement, with its reason.
- Anything that did **not** work, or that is still open: Phase 2 (cross-window branches, the
  opword-class confirm hardening, deleting the decode-time slot-0 read once coverage is
  measured), Phase 3 (fetch-time RAS for `rts` — this is what covers `call-return`'s third
  transfer; Phase 1 covers `bsr` + `bne` only), Phase 4 (removing the slot-1 defer).

- [ ] **Step 4: Commit the ledger and the merge**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add -f .superpowers/sdd/progress-ipc-push-2026-08-09.md
git commit -m "feat(frontend): fetch-directed BTB -- window-indexed FTB + FTQ + confirm-or-flush

<one-line IPC / FMax / LUT summary with the MEASURED numbers>

Full writeup in .superpowers/sdd/progress-ipc-push-2026-08-09.md.
Design: docs/superpowers/specs/2026-08-09-ipc-fetch-directed-btb-design.md
Plan:   docs/superpowers/plans/2026-08-09-ipc-fetch-directed-btb-implementation-plan.md"
git worktree remove ../wt-ftb
git worktree prune
```

---

## Self-Review

**1. Spec coverage.** Walked every section:

| spec section | covered by |
|---|---|
| §2.1-2.2 architecture / new-vs-reindex | Task 4 (`Ftb.scala`), Task 15 (BtbPlugin retained, `spec2` deleted) |
| §2.3 FTB table + training + invalidation | Task 3 (`len`), Task 4 (table, filter, both invalidate sources) |
| §2.4 registered lookup + value-based freshness | Task 6 |
| §2.5 the four-part fetch-side action + `ringKeep` | Tasks 7, 8 |
| §2.6 direction source, 4 speculative PHT reads, GHR | Task 5, Task 8 Step 5, Task 10 Step 10 |
| §2.7 FTQ | Task 8 |
| §2.8 decode side, confirm/mismatch, `slot1WouldPred` | Tasks 9, 10, 15 |
| §3.1-3.4 the correctness argument + INV-P + the clamp | Task 9 |
| §3.5 A1-A10 | Task 11 (A1-A7, A9 directed; A8 is §3.7 H7, closed by Task 4's dual invalidate; A10 is impossible by FTQ fetch order, stated in Task 8's comment) |
| §3.6 mismatch, L1, L2 | Task 10, tested in Task 11 |
| §3.7 H7/H8/H9 | Task 4 (dual invalidate), unchanged EU verification, gshare accept-corruption |
| §4 F1-F12 + INV-A/INV-B | Task 10 (RTL), Task 13 (tests; F4/F8/F9/F10 coverage-by-argument stated explicitly) |
| §5.1-5.3 FMax treatment | Tasks 14, 15 gates |
| §5.4 the mandatory census | **Task 1**, with G-T1a/b/c recorded and G-T1b wired as a hard dependency of Task 9 |
| §6.1 area | LUT/FF reported at Tasks 6, 14, 15 |
| §6.2 blast radius | the File Structure table |
| §6.3 slicing | Tasks 1-15 map 1:1 onto Slices 0-4 |
| §7 Approach C | **Task 16**, with the decision point at Task 12 Step 4 |
| §8 rejected alternatives | Global Constraints, marked LOCKED with escalation required |
| §9.1 baselines | Task 2 (re-measured, not quoted) |
| §9.2 `FtbConfirmGuardSpec` | Task 11, 10 mutations |
| §9.3 `FtbStreamEquivalenceSpec` | Task 12, 7 mutations |
| §9.4 `FtqFlushSpec` | Task 13 |
| §9.5 existing suites + branch-heavy-first | Tasks 11 Step 5, 14 Step 4, 15 Step 6 |
| §9.6 IPC gate | Task 14 Step 5 + depth sweep Step 6 |
| §9.7 FMax + LUT gate, Slice 4 separately | Tasks 14 Step 7, 15 Step 8 |
| §10 Q1-Q8 | resolved in the dedicated section above |

**Gap found and closed during review:** §9.4 asks for INV-A/INV-B "running throughout every
other frontend test"; Task 13 Step 3 retro-fits the monitors into `FtbConfirmGuardSpec` and
`FtbStreamEquivalenceSpec` rather than leaving that as an aspiration.

**2. Placeholder scan.** No "TBD", "implement later", "add appropriate tests", or
"similar to Task N". Every code step carries the actual code. Three places deliberately
require the implementer to *determine* something rather than transcribe it, and each states
exactly what to determine and what to do with each answer: Task 1's G-T1a/b/c verdicts,
Task 12's `IcacheSim` agent-lifecycle question (with the explicit instruction **not** to
work around it by shrinking the sweep), and Task 13's `cmdPort` handle check.

**3. Type consistency.** Cross-checked every name used across task boundaries:
`BtbUpdate.len` (`UInt(4 bits)`) → `BranchCompletion.btbLen` (`UInt(4 bits)`) →
`BranchTrainPayload.len` (`UInt(4 bits)`) → `FtbPlugin`'s `uLen`, narrowed to
`FtbEntry.brLen` (`UInt(3 bits)`) only after the `1..5` filter. `FtqEntry.brLen` is
`UInt(3 bits)` and is `.resize(4)`-compared against `res.slot0.lenWords` (`UInt(4 bits)`) in
`ftqConfirm`. `ftqDelta` is `UInt(4 bits)`, compared against `effShift` (`UInt(4 bits)`) and
`res.slot0.lenWords` (`UInt(4 bits)`). `spliceWords` is `UInt(5 bits)`, compared against
`ibuf.io.avail.resize(5)`. `availEff` is `UInt(4 bits)`, matching `Aligner.align`'s `avail`
parameter and `ibuf.io.avail`. `ringKeep` is `UInt(3 bits)`, matching the `nWords` expression
it replaces. `specTaken` / `gsSpecTakenIn` / `ftbResPhtTaken` are all `Vec(Bool(), 4)`;
`specIdxBase` / `gsSpecIdxBaseIn` / `ftbResPhtIdxBase` / `FtqEntry.phtIdx` /
`DecodePacket.phtIndex` are all 11 bits. `FtbTestDut`'s helper names (`install`, `redirect`,
`enable`, `idle`, `runAndCollect`, `Emitted`) are used identically in Tasks 11, 12 and 13.
