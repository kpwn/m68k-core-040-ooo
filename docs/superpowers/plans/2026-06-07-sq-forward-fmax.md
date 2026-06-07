# Store-queue forward FMax (250 closure P1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans. Checkbox steps, TDD. Final gate = HONEST **POST-ROUTE** (`impl_FullCore.tcl`) vs master ~235 — the point of this slice is the FMax jump on the SQ-forward cone.

**Goal:** Shorten the binding post-route cone `LsEu s2Paddr → fwdData` (SQ-forward overlap+age search, 14 levels w/ a CARRY8 adder) by pre-registering the per-entry address-range bounds at store-alloc, so the forward path is a compare (no adder). Behavior identical.

**Architecture:** At store ALLOC, compute + store `paddrLo = paddr`, `paddrHi = paddr + nbytes` (and the slot-B misalign bounds) into the SQ entry registers. The forward overlap test then compares the load range against the stored bounds — dropping the adder. Fallback: split the forward into 2 cycles (latency-agnostic) if the compare-only cone is still binding.

**Tech Stack:** SpinalHDL / sbt `~/sbt/bin/sbt` / Verilator / Vivado. Reference: `ls/StoreQueue.scala` (alloc :209-217, the fwd query/overlap/age reduce :126-152, drain :126-133), `execute/LsEuPlugin.scala` (`s2Paddr`, `sq.io.fwd.query`, `fwdData`), `lockstep/ExecuteLockStepSpec.scala`. Spec: `docs/superpowers/specs/2026-06-07-sq-forward-fmax-design.md`. Post-route gate: `synth/impl_FullCore.tcl`. Diagnosis: `analysis/postroute-250:synth/POSTROUTE_250_ANALYSIS.md`.

**Branch:** `feat/sq-forward-fmax` (off master).

## CRITICAL memory: never Verilator + vivado concurrently (an OOM killed a run that way); `free -h` before place&route.

---

### Task 1: Diagnose the forward cone + the alloc-time pre-register plan
**Files:** read-only `StoreQueue.scala` + the diagnosis. Identify the `paddr + nbytes` adder in the forward path + where alloc computes/stores the entry; confirm the slot-B (misalign) bounds; decide pre-register vs (fallback) 2-cycle split.
- [ ] Capture the plan in the Task-1 commit (empty ok).

### Task 2: Pre-register the entry range bounds at alloc
**Files:** `ls/StoreQueue.scala`; Test `ls/StoreQueueSpec.scala` (or the LS forward spec).
Add `paddrLo`/`paddrHi` (+ slot-B) registers, written at alloc (`paddrHi = paddr + nbytes`). Change the forward overlap test to compare the load range against the stored bounds (no adder). Keep the youngest-match age reduce + byte-assemble identical.
- [ ] failing/representative directed test (forward full + partial overlap + youngest-of-multiple + no-overlap, value identical) → implement → PASS ×2 → commit `sq: pre-register entry range bounds at alloc (drop forward-path adder)`.

### Task 3: Lock-step (behavior-identical) + post-route check; split if needed
**Files:** none (run suites); `StoreQueue.scala` only if the 2-cycle split fallback is needed.
- [ ] Step 1: lock-step vs Musashi ×2 — ALL existing UNCHANGED (st-ld-sameline, st-ld-subword, cross-line, loop-store, MMU stores, immediates, call-return). ITLB flake → baseline-repro first.
- [ ] Step 2: `make test-fast` + targeted verilator `-z` subsets.
- [ ] Step 3: **POST-ROUTE** (gen + `impl_FullCore.tcl`) — report WNS/FMAX vs master (~235, same flow); confirm `s2Paddr→fwdData` cone off/shortened; report the new limiter. If the compare-only cone is STILL binding, do the 2-cycle split fallback (register the overlap-match, assemble fwdData next cycle — latency-agnostic), re-lock-step, re-synth (before/after).
- [ ] Step 4: commit `sq-forward-fmax: lock-step unchanged + POST-ROUTE result vs master`.

---

## Self-Review
**Spec coverage:** pre-register bounds (T2); lock-step + post-route + split-fallback (T3). ✓
**Placeholder scan:** pre-register-vs-split is an explicit T1/T3 decision (spec §5). No TBD.
**Type consistency:** new `paddrLo`/`paddrHi` (+slot-B) SQ entry regs written at alloc from `paddr`/`nbytes`; forward overlap = compare against them; age reduce + byte-assemble unchanged; value identical. ✓
