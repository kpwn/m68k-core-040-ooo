# D-cache async-LUTRAM → sync-BRAM (250 closure P0.1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans. Checkbox steps, TDD. Final gate = HONEST **POST-ROUTE** (`impl_FullCore.tcl`) vs master baseline — this slice's WHOLE POINT is the post-route FMax jump.

**Goal:** Convert the D-cache `dataMem`/`tagMem` from async-read distributed-RAM (LUTRAM) to synchronous-read BRAM, removing the #1 congestion source + the `fo=1032` net + the `exc→dataMem` cone — the highest-leverage step toward real 250 post-route.

**Architecture:** Make ALL D-cache RAM reads synchronous. The store-RMW old-line read becomes `readSync` launched in store-S0 (extend the existing S0/S1 store pipeline); the tag hit-detect becomes a registered read (hit resolved in S1 alongside the data). Single-outstanding → load-read and store-RMW-read SHARE one sync-read port (simple-dual-port BRAM: 1 write + 1 read per way). Latency-agnostic (+1 cycle hit resolution; lock-step absorbs it).

**Tech Stack:** SpinalHDL / sbt `~/sbt/bin/sbt` / Verilator / Vivado. Reference: `cache/DcachePlugin.scala` (dataMem/tagMem :58-74, the readAsync hit-detect :119-150, the store-RMW S0/S1 :139-178, the load S0→S1 + FSM), `cache/IcachePlugin.scala` (the sync-read BRAM template that already works), `cache/CacheGeometry.scala`. Spec: `docs/superpowers/specs/2026-06-07-dcache-bram-design.md`. Post-route gate: `synth/impl_FullCore.tcl`. Diagnosis: `git show analysis/postroute-250:synth/POSTROUTE_250_ANALYSIS.md`.

**Branch:** `feat/dcache-bram` (off master).

## CRITICAL memory note: do NOT run Verilator and vivado concurrently (an OOM just killed an analysis run that way). One heavy job at a time; `free -h` before place&route (need ~16G).

---

### Task 1: Diagnose the exact async reads + the port-sharing plan
**Files:** read-only `DcachePlugin.scala` + the diagnosis report + `IcachePlugin.scala`.
Enumerate every `readAsync` on `dataMem`/`tagMem` (store-RMW old-line, load hit-detect tag, store hit-detect tag). Confirm single-outstanding mutual exclusion so load-read + store-RMW-read can share one sync-read port. Plan the registered-hit-detect timing (hit resolved in S1).
- [ ] Capture the plan in the Task-1 commit (empty ok).

### Task 2: tagMem → sync-read (registered hit-detect)
**Files:** `cache/DcachePlugin.scala`; Test `cache/DcacheSpec.scala` (directed).
Convert `tagMem.readAsync` (load + store hit-detect) to `readSync` launched in S0; resolve hit in S1 against the registered tag + registered ppn-tag. Keep refill tag-write.
- [ ] failing/representative test (load hit resolved S1, miss→refill, store hit) → implement → DcacheSpec PASS ×2 → commit `dcache: tagMem sync-read (registered hit-detect)`.

### Task 3: dataMem → sync-read (store-RMW old-line via S0)
**Files:** `cache/DcachePlugin.scala`; Test `cache/DcacheSpec.scala`.
Convert the store-RMW old-line `readAsync` to `readSync` launched in store-S0; merge in S1 from the registered old line + write. Share the sync-read port with the load (single-outstanding). Keep refill-vs-store write priority + write-through ACK.
- [ ] failing/representative test (store RMW sub-word merge preserves neighbors; back-to-back; store→load forward) → implement → DcacheSpec PASS ×2 → commit `dcache: dataMem sync-read (store-RMW old-line via S0)`.

### Task 4: Lock-step (behavior-identical) + confirm BRAM inference
**Files:** none (run suites + check util).
- [ ] Step 1: lock-step vs Musashi ×2 — ALL existing programs UNCHANGED (load/store/SQ-forward/MMU/cross-line/loop-load/loop-store/immediates/call-return). ITLB flake → baseline-repro first.
- [ ] Step 2: `make test-fast` + targeted verilator `-z` subsets — report totals.
- [ ] Step 3: gen + quick OOC; check the gen/synth util — confirm `dataMem`/`tagMem` now infer **RAMB36** (not RAMD64E). Report RAMD64E count before/after.
- [ ] Step 4: commit `dcache-bram: lock-step unchanged + BRAM inference confirmed`.

### Task 5: POST-ROUTE GATE (the real measure)
**Files:** none.
- [ ] Step 1: `vivado -mode batch -nojournal -log synth/vivado_fullcore_impl.log -source synth/impl_FullCore.tcl` on the branch; record POSTROUTE WNS/FMAX. Run the SAME flow on master (apples-to-apples; place&route varies). Report the delta — expect a MEANINGFUL jump (highest-leverage change).
- [ ] Step 2: confirm via the routed report: `exc→dataMem` + dataMem cones OFF the worst-path list; `fo=1032` net gone; LUTRAM (RAMD64E) count dropped; BRAM count up. Report the NEW limiter (likely the regfile = P0.2). It's OK if not yet ≥250 (P0.2/P1 follow) — the gate is a clear improvement + the D-cache cones gone, NO functional regression.
- [ ] Step 3: commit `dcache-bram: POST-ROUTE result (FMax delta vs master)`.

---

## Self-Review
**Spec coverage:** tagMem sync (T2); dataMem sync via S0 (T3); lock-step + BRAM inference (T4); post-route gate (T5). ✓
**Placeholder scan:** the port-sharing + registered-hit timing are explicit T1 decisions (spec §5). No TBD.
**Type consistency:** `dataMem`/`tagMem` keep their types; reads become `readSync` (S0 addr → S1 data/tag regs); writes unchanged (refill/store). Hit resolved in S1. Single-outstanding shares the read port. ✓
