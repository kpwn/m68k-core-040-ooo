# LS SQ back-pressure + BehavioralMem write-ordering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix two pre-existing shared-LS bugs MOVEM exposed: (1) the store-queue silently drops stores on >8-entry bursts (no back-pressure) — a real DUT bug; (2) `BehavioralMem` applies write data decoupled from the AXI-B ack — a test-harness bug. Then restore the full 15-register MOVEM round-trip lock-step tests.

**Architecture:** (1) `StoreQueue` exposes `io.full`; the LS-EU stalls store alloc in a `WAIT_SQ` FSM state until an entry drains (deadlock-free via incremental ROB-order commit-drain), keeping `io.full` off the IQ critical path. (2) `BehavioralMem` applies write strobes before/atomic with the B response so a refill read of an acked address sees the written data.

**Tech Stack:** SpinalHDL 1.14.1 / Scala 2.13 / sbt at `~/sbt/bin/sbt` (NOT on PATH) / Verilator / Vivado 2025.2 (`xcku5p-ffvb676-2-e`, OOC 4 ns). Lock-step vs `m68k040.oracle.Musashi`.

**Working dir:** isolated git worktree on `feat/ls-sq-backpressure` off current `master` (`18d8ea0`).

**Discipline (HARD):** `~/sbt/bin/sbt`. NEVER Verilator+Vivado concurrent (`pgrep -af vivado` first). NEVER the whole `ExecuteLockStepSpec` — `-z` subsets, each its OWN `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt …` JVM, never batched, output to files under `/home/qwertyoruiop/tmp`. The synth gate (≥200) is the final step. Read the spec `docs/superpowers/specs/2026-06-11-ls-sq-backpressure-drain-design.md` fully first.

**Templates to study first:** `ls/StoreQueue.scala` (the `io`, `count`/`head`/`tail`, the `:218` alloc block, the drain/drainAck/`empty`); `execute/LsEuPlugin.scala` (`val sq = new StoreQueue(8)` at :160, the AGU + the existing `WAIT_A`/`WAIT_B` misalign FSM — the model for `WAIT_SQ`); `test/.../ls/BehavioralMem.scala` (the two `StreamMonitor`s + stock `Axi4WriteOnlySlaveAgent`); `test/.../ls/BehavioralMemSpec.scala`, `StoreQueueSpec.scala`; the MOVEM lock-step tests + `st-ld-drain` probe in `ExecuteLockStepSpec.scala` (the ≤8 caps + refill workarounds to revert).

---

## File Structure

- `src/test/scala/m68k040/ls/BehavioralMem.scala` — write-before-ack ordering fix (Task 1).
- `src/test/scala/m68k040/ls/BehavioralMemSpec.scala` — ordering assertion (Task 1).
- `src/main/scala/m68k040/ls/StoreQueue.scala` — `io.full` + sim guard (Task 2).
- `src/test/scala/m68k040/ls/StoreQueueSpec.scala` — full→drain→accept case (Task 2).
- `src/main/scala/m68k040/execute/LsEuPlugin.scala` — `WAIT_SQ` stall on `sq.io.full` (Task 3).
- `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` — restore full-15 MOVEM + de-workaround (Task 4).

---

## Task 1: BehavioralMem — write-before-ack ordering (harness fix)

**Files:** `src/test/scala/m68k040/ls/BehavioralMem.scala`, `BehavioralMemSpec.scala`.

- [ ] **Step 1: Write the failing ordering test (`BehavioralMemSpec`)**

Add a sim test driving the AXI write channels of a `BehavioralMemAgent`: issue a write burst to
an address, wait for the B (write-response) handshake, then on the VERY NEXT cycle issue an AXI
read of the same address and assert the read returns the just-written data (NOT the pre-write
value). With the current decoupled `StreamMonitor` apply, this should be able to FAIL (read sees
stale) — if it's timing-flaky, loop it several times / vary spacing so the race manifests.
Run: `JAVA_OPTS=-Xmx10g timeout 900 ~/sbt/bin/sbt 'testOnly m68k040.ls.BehavioralMemSpec'` →
expect the new case to FAIL (or flake) on the unfixed code.

- [ ] **Step 2: Fix the write-apply ordering**

In `BehavioralMem.scala`, apply the per-byte strobes BEFORE/atomic-with the B response instead of
in a decoupled `axi.w` `StreamMonitor`. The invariant: **once B is observed for a write, a
subsequent read of that address returns the written bytes.** Implementer's choice of the cleanest
mechanism, e.g.:
- Drive the write path with a custom `Axi4WriteOnlySlaveAgent` subclass that applies the buffered
  (aw,w) beats to `mem` in the same place it completes the burst / drives `b` (so apply
  happens-before B), rather than via the separate `StreamMonitor(axi.w)`.
- Keep `pokeByte/peekByte/poke128/peek128` and the shared-`SparseMemory` semantics unchanged.
Do NOT change the read agent.

- [ ] **Step 3: Run the ordering test to PASS**

Run: `JAVA_OPTS=-Xmx10g timeout 900 ~/sbt/bin/sbt 'testOnly m68k040.ls.BehavioralMemSpec'`
Expected: ALL pass, including the new write-before-ack case, stably over repeats.

- [ ] **Step 4: Commit**

```bash
git add src/test/scala/m68k040/ls/BehavioralMem.scala src/test/scala/m68k040/ls/BehavioralMemSpec.scala
git commit -m "test(ls): BehavioralMem applies write data before the AXI-B ack (write-before-ack AXI-slave contract)"
```

---

## Task 2: StoreQueue — expose `io.full` + sim guard

**Files:** `src/main/scala/m68k040/ls/StoreQueue.scala`, `StoreQueueSpec.scala`.

- [ ] **Step 1: Add `io.full` + an alloc-when-full sim guard**

In `StoreQueue.io`, add `val full = out(Bool())`; drive `io.full := (count === U(depth))`
(`count` is `log2Up(depth+1)` wide, so it represents `depth` exactly). Add a simulation
assertion that alloc never fires while full (catches a missing back-pressure regression):
`assert(!(io.alloc.valid && io.full && !io.flush), "StoreQueue alloc while full — missing back-pressure")`.
(The alloc block at `:218` is otherwise unchanged — the LS-EU is responsible for not allocating
when full; the assert documents/enforces the contract.)

- [ ] **Step 2: Extend `StoreQueueSpec` with a full→drain→accept case**

Add a test: alloc `depth` (8) entries without draining (hold `drainAck` / keep them uncommitted) →
assert `io.full` is high after the 8th. Commit+drain one (pulse `commit` then `drainAck`) → assert
`io.full` deasserts → a 9th alloc now succeeds and is forwardable. Keep existing forward/drain/
flush/split tests green.
Run: `JAVA_OPTS=-Xmx10g timeout 900 ~/sbt/bin/sbt 'testOnly m68k040.ls.StoreQueueSpec'` → PASS.

- [ ] **Step 3: Commit**

```bash
git add src/main/scala/m68k040/ls/StoreQueue.scala src/test/scala/m68k040/ls/StoreQueueSpec.scala
git commit -m "ls(sq): expose io.full (count==depth) + alloc-while-full sim guard"
```

---

## Task 3: LS-EU — `WAIT_SQ` back-pressure stall

**Files:** `src/main/scala/m68k040/execute/LsEuPlugin.scala`.

- [ ] **Step 1: Stall store alloc when the SQ is full**

In `LsEuPlugin`, gate a store µop's SQ-alloc on `!sq.io.full`. When a store reaches its alloc
point and `sq.io.full`, enter a new `WAIT_SQ` FSM state (mirror the existing misalign `WAIT_A`/
`WAIT_B` states): hold the µop, do NOT alloc, deassert the EU's acceptance of the next issue, and
remain until `!sq.io.full` (an entry drains), then alloc + complete normally. CRITICAL: read
`sq.io.full` ONLY in the execute/alloc FSM (NOT in any path that feeds the IQ issue-select/ready
cone — same discipline as the push-only `lsBusy` read) so the IQ critical path is untouched. A
split (two-slot) store needs room for its alloc too — if a single entry per store covers it, the
single `full` check suffices; if a split needs 2 entries, stall until `count <= depth-2` for the
split case (check how the misalign slot-B alloc works and guard accordingly).

- [ ] **Step 2: Compile + targeted LS sanity**

```bash
JAVA_OPTS=-Xmx10g timeout 900 ~/sbt/bin/sbt 'testOnly m68k040.ls.LsEuSpec m68k040.ls.LsBackendInjectSpec'
```
Expected: green (the common, non-full path is unchanged; the stall only engages when full).

- [ ] **Step 3: Commit**

```bash
git add src/main/scala/m68k040/execute/LsEuPlugin.scala
git commit -m "ls(eu): WAIT_SQ stall — back-pressure store alloc on sq.io.full (deadlock-free via incremental commit-drain)"
```

---

## Task 4: Restore full-strength MOVEM lock-step + verify + gate

**Files:** `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala`.

- [ ] **Step 1: Restore the full 15-register MOVEM round-trip + de-workaround**

Revert the ≤8 caps and refill workarounds added in `18d8ea0`:
- The prologue/epilogue test → the CLASSIC full form:
  `move.l #...,%d0..%d7 ; move.l #...,%a0..%a6 ; move.l #0x00004000,%sp ;`
  `movem.l %d0-%d7/%a0-%a6,-(%sp) ; movem.l (%sp)+,%d0-%d7/%a0-%a6 ;`
  then ADD-fold every reloaded reg into D0 + `move.l %sp,%d1`, `nInstr` set accordingly,
  `checkMem = Seq(0x3fc4L), checkSpan = 60` (15 longs, predec ordering). (Recover the exact
  original from `git show 6688fe0:src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala`
  — the pre-weakening version — or the spec's description.)
- The `(An)+` load + `.W` sign-extend tests → multi-register, WITHOUT the `move.l 0x3000,%dN`
  drain+refill workaround lines (the BehavioralMem fix makes the immediate store→load correct).
- The `rtr-frame` test → drop the read-back-to-refill workaround if it now passes without it
  (keep the test; only remove the now-unnecessary `move.l 0x2000,%d4`/`%d5` lines + their comment).

- [ ] **Step 2: Run the full MOVEM lock-step + the drain probe (own JVMs)**

```bash
JAVA_OPTS=-Xmx10g timeout 1500 ~/sbt/bin/sbt 'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z MOVEM' > /home/qwertyoruiop/tmp/ls-movem.log 2>&1
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "drain race"' > /home/qwertyoruiop/tmp/ls-drain.log 2>&1
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt 'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "rtr"' > /home/qwertyoruiop/tmp/ls-rtr.log 2>&1
grep -iE "Tests:|diverged|FAILED" /home/qwertyoruiop/tmp/ls-*.log
```
Expected: MOVEM 6/6 incl. the full-15 round-trip, the `st-ld-drain` probe, and `rtr-frame` —
all PASS, 0 diverged. Repeat the round-trip 3× to confirm stability (no seed flake).

- [ ] **Step 3: Non-MOVEM LS regression + fastTest**

```bash
JAVA_OPTS=-Xmx10g timeout 1300 ~/sbt/bin/sbt 'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "RMW"' > /home/qwertyoruiop/tmp/ls-rmw.log 2>&1
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt fastTest > /home/qwertyoruiop/tmp/ls-fasttest.log 2>&1
grep -iE "Tests:|diverged|FAILED|All tests" /home/qwertyoruiop/tmp/ls-rmw.log /home/qwertyoruiop/tmp/ls-fasttest.log
```
Expected: green — the SQ back-pressure does not perturb RMW / common LS.

- [ ] **Step 4: Commit tests + the ≥200 synth gate (vivado — NO Verilator concurrent)**

```bash
git add src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala
git commit -m "test(movem): restore full 15-register MOVEM round-trip + drop the store->load drain workarounds (LS bugs fixed)"
pgrep -af vivado   # must be empty (no external job, no concurrent Verilator)
JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
timeout 1800 vivado -mode batch -nojournal -nolog -source synth/ooc_M68kFullCoreSynth.tcl
grep -nE "RESULT FullCore|Slack \(VIOLATED|Source:|Destination:" synth/M68kFullCoreSynth_timing.rpt | head
git commit --allow-empty -m "synth: LS SQ back-pressure gate — FMax <X> (>=200), worst path <…>"
```
Expected: FMax ≥ 200 (the `count==depth` compare feeds the LS-EU FSM, not the IQ select cone;
expect FMax-neutral, D-cache→DTLB still the limiter). If `io.full` shows on the critical path,
register it and note it.

---

## Done-When

- `BehavioralMem` honors write-before-ack; `BehavioralMemSpec` ordering case green + stable.
- `StoreQueue.io.full` exposed + alloc-while-full guard; `StoreQueueSpec` full→drain→accept green.
- LS-EU `WAIT_SQ` back-pressures store alloc on `sq.io.full`; `LsEuSpec`/`LsBackendInjectSpec` green.
- Full **15-register `MOVEM.L D0-D7/A0-A6,-(A7)`+`(A7)+` round-trip** lock-step PASSES (3× stable,
  0 diverged); `(An)+`/`.W`/`rtr-frame` pass WITHOUT the drain workarounds; `st-ld-drain` probe
  stable; RMW + non-MOVEM LS + `fastTest` green (no regression).
- ≥200 synth gate reported.
- Memory updated ([[ls-cluster]]): both LS bugs fixed (SQ back-pressure DUT fix + BehavioralMem
  write-before-ack harness fix); full MOVEM round-trip now the standing coverage.

## Non-Goals

- Deepening the SQ; store-to-load forwarding changes; write-allocate; L2; MOVEM fault-continuation.
- Making the DUT tolerate an incorrect AXI slave (the harness is fixed instead).
