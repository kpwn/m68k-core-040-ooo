# ALU EU fast/slow path split Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans. Checkbox steps, TDD. Gate = LOCK-STEP (latency-agnostic) + IPC preserved (IpcBenchSpec) + OOC FMax up from 156. STANDING RULE: registered module boundaries.

**Goal:** Split the ALU EU into a FAST 1-cycle path (add/sub/logic/cmp/move — keeps the back-to-back-dependent bypass / IPC) and a SLOW 2-cycle path (barrel shifter + CCR-RMW + whatever's on the 24-level cone), recovering FMax without dropping dependent-ALU IPC.

**Architecture:** Fast ops complete in S1 (unchanged: writeback lat1 + bypass + static-latency-1 wakeup). Slow ops (isShift || toCcr [+ diagnosis]) register in S1, finalize in S2 (writeback lat2 + completion + DYNAMIC wakeup via the IQ's existing cplxBusy/lsBusy-style mechanism). Dependents of slow ops wake at lat2.

**Tech Stack:** SpinalHDL / sbt `~/sbt/bin/sbt` / Verilator / Vivado(OOC). Reference: `execute/AluEuPlugin.scala` (the fixed-lat1 S1 :38-117 — the AluDatapath op, the barrel shifter `isShift`/`DecOp.SHIFT`, `mergedResult`, `toCcr` CCR-RMW, `intW`/`nzvcW`/`xW` lat1 + `intByp`/`nzvcByp`/`xByp`), `execute/iq/IssueQueuePlugin.scala` (:84 static latency-1 trigger; :123-140 the dynamic lsBusy/cplxBusy wakeup to mirror), `execute/Shifter.scala`, `bench/IpcBenchSpec.scala`. Spec: `docs/superpowers/specs/2026-06-07-alu-fast-slow-split-design.md`.

**Branch:** `feat/alu-fast-slow-split` (off master).

## CRITICAL memory: heavy jobs ONE AT A TIME; `-z` subsets for lock-step; an m68k030 vivado may run — `free -h`+`pgrep vivado` before any OOC synth.

---

### Task 1: Diagnose the 24-level cone + the slow-op set + the slow-wakeup plan
**Files:** read-only `AluEuPlugin.scala`, `IssueQueuePlugin.scala`, the OOC timing report (gen+synth current master, read `synth/M68kFullCoreSynth_timing.rpt` for the s1Src2->NZVC path's logic). Identify EXACTLY which ops/sub-paths make the 24 levels (shifter? CCR-RMW? mergedResult? SWAP/EXT?). Pick the MINIMUM slow-op set that clears the cone (keep the fast path broad). Plan the slow-path S1->S2 stage + the IQ dynamic-wakeup bitmap (mirror cplxBusy).
- [ ] Capture decisions in the Task-1 commit (empty ok).

### Task 2: Slow path (S1->S2 for shift/CCR-RMW) + lat-2 writeback/completion
**Files:** `execute/AluEuPlugin.scala`; Test `execute/AluFastSlowSpec.scala`.
For isSlow ops: register the operands/op into S2; compute shift/CCR-RMW + NZVCX in S2; writeback (intW/nzvcW/xW) + completion at lat2. Fast ops UNCHANGED (S1 lat1). Keep the fast bypass; do NOT bypass a slow producer's S1 partial.
- [ ] failing test (a shift result is correct + available lat2; a fast add still lat1 + bypasses) → FAIL → implement → PASS ×2 → commit `alu: slow path (S1->S2) for shift + CCR-RMW (lat2)`.

### Task 3: IQ dynamic wakeup for slow-ALU producers
**Files:** `execute/iq/IssueQueuePlugin.scala`; Test `execute/iq/` directed.
Track slow-ALU producers in a new dynamic bitmap (mirror cplxBusy): a dependent of a slow-ALU producer is NOT statically woken at lat1; it wakes when the slow-ALU `wakeup` broadcasts (lat2). Fast ALU stays static-lat1.
- [ ] failing test (a dependent of a shift waits 1 extra cycle then issues with the correct operand; a dependent of an add issues back-to-back) → FAIL → implement → PASS ×2 → commit `iq: dynamic wakeup for slow-ALU producers (lat2)`.

### Task 4: Lock-step + IPC + OOC FMax
**Files:** `lockstep/ExecuteLockStepSpec.scala`, run suites.
- [ ] Step 1: lock-step vs Musashi ×2 — ALL existing UNCHANGED, esp. shift/rotate, ANDI/ORI/EORI-to-CCR, dependent chains through a shift/CCR result. ITLB flake → baseline-repro first.
- [ ] Step 2: `make test-fast` + targeted verilator `-z` subsets.
- [ ] Step 3: **IPC (`IpcBenchSpec`)** — dependent-ALU + independent-ALU IPC UNCHANGED (the fast path); report all kernels (a shift-heavy one may dip — fine).
- [ ] Step 4: **OOC FMax** — gen + `ooc_M68kFullCoreSynth.tcl`; report before(156)/after; the 24-level shifter/CCR cone OFF the single-cycle worst-path list; new worst path. Should be materially up.
- [ ] Step 5: commit `alu-fast-slow-split: lock-step + IPC preserved + OOC FMax up`.

---

## Self-Review
**Spec coverage:** diagnose slow set (T1); slow S1->S2 path (T2); IQ dynamic wakeup (T3); lock-step + IPC + FMax (T4). ✓
**Placeholder scan:** the exact slow-op set + the slow-wakeup mechanism are explicit T1 decisions (spec §5). No TBD.
**Type consistency:** fast path = current S1 lat1 (intW/nzvcW/xW + bypass + static wake); slow path = S2 lat2 (same write ports, +1 cycle) + dynamic wakeup (mirror cplxBusy); dependents of slow producers wake at lat2. Lock-step latency-agnostic. ✓
