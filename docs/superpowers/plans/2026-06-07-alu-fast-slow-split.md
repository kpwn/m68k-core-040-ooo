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
- [x] Capture decisions in the Task-1 commit (empty ok).

**DIAGNOSIS (baseline gen+OOC synth, WNS -2.398, FMAX 156.3):** all 8 worst setup paths are the SAME cone:
`AluEuPlugin.s1Src2[*] -> ... -> finalNzvc_regNext[*] -> RegFilePluginNzvc ram (NZVC writeback)`,
24 logic levels (LUT6=16), 6.319ns data-path delay. The path threads through `s1Src2`
(the shift COUNT / CCR imm operand) and `s1Ctx_uop_useImm` (which gates both the shifter
count form and the CCR imm), folding into `finalNzvc`.
`finalNzvc = Mux(isShift, shiftNzvc, Mux(toCcr, ccrNzvc, aluNzvc))`.
The deep contributor is the BARREL SHIFTER (`Shifter.scala`): multiple 66-bit variable
funnel shifts (`shl`/`shr`, the rotate funnels), the per-size `shTable` generation, and the
ROX `rmod` 7-deep subtract-reduce chain — all as a function of the variable count `s1Src2`.
The `aluNzvc` (plain ADD/SUB) path is a 32-bit adder + simple flag fold — much shallower.

**SLOW-OP SET (minimum that clears the cone):** `isSlow = isShift || toCcr`.
- isShift (DecOp.SHIFT): the dominant deep funnel cone — MUST move to S2.
- toCcr (ANDI/ORI/EORI #imm,CCR): also on the `finalNzvc` mux + reads/writes NZVC+X;
  3 rare instructions => ~zero IPC cost; moving it removes another `finalNzvc` contributor.
**FAST path (UNCHANGED, lat1):** ADD/SUB/AND/OR/EOR/CMP/MOVE/MOVEA/imm/CLR/NEGX/EXT/SWAP —
the `aluNzvc`/`mergedResult` cone (the dependent-chain IPC path) + bypass + static lat1 wake.

**SLOW PATH (lat2):** S1 registers {op,size,operands,flags,useImm,shiftOp/Dir,extByte,ctx,masks}
into S2 regs; S2 computes shifter + CCR-RMW + final NZVCX; S2 drives intW/nzvcW/xW (lat1 write
ports off the S2 stage => arch lat2) + completion + a new `aluSlowWakeup` Flow (pdst). The S2
PRF writes use the SAME lat1 write ports (one extra pipe cycle = arch latency 2). NO bypass of a
slow producer's S1 partial (dynamic wakeup gates the dependent until S2).

**IQ DYNAMIC WAKEUP:** mirror cplxBusy — a new `aluSlowBusy` bitmap + `aluSlowWakeupPort` Flow.
A slow-ALU producer's pdst is recorded in aluSlowBusy (NOT sbInt => no static lat1 trigger);
a consumer latches `aluSlowWait` (like cplxWait) and wakes on the aluSlowWakeup broadcast (lat2).
Both ALU EUs (eu0/eu1, issue ports 0/1) can issue a slow op => the two EUs' wakeup Flows are
ORed into the single IQ port (mutually-exclusive pdsts; at most one valid per EU per cycle).
The static `events` set must EXCLUDE a slow-producer's issue (so no lat1 trigger fires for it).

### Task 2: Slow path (S1->S2 for shift/CCR-RMW) + lat-2 writeback/completion
**Files:** `execute/AluEuPlugin.scala`; Test `execute/AluFastSlowSpec.scala`.
For isSlow ops: register the operands/op into S2; compute shift/CCR-RMW + NZVCX in S2; writeback (intW/nzvcW/xW) + completion at lat2. Fast ops UNCHANGED (S1 lat1). Keep the fast bypass; do NOT bypass a slow producer's S1 partial.
- [x] failing test (a shift result is correct + available lat2; a fast add still lat1 + bypasses) → FAIL → implement → PASS ×2 → commit `alu: slow path (S1->S2) for shift + CCR-RMW (lat2)`.

### Task 3: IQ dynamic wakeup for slow-ALU producers
**Files:** `execute/iq/IssueQueuePlugin.scala`; Test `execute/iq/` directed.
Track slow-ALU producers in a new dynamic bitmap (mirror cplxBusy): a dependent of a slow-ALU producer is NOT statically woken at lat1; it wakes when the slow-ALU `wakeup` broadcasts (lat2). Fast ALU stays static-lat1.
- [x] failing test (a dependent of a shift waits 1 extra cycle then issues with the correct operand; a dependent of an add issues back-to-back) → FAIL → implement → PASS ×2 → commit `iq: dynamic wakeup for slow-ALU producers (lat2)`.
  - REVISED slow-op set: `isSlow = isShift` ONLY (toCcr STAYS fast — shallow 5-bit fold + statically-tracked flag writes; moving it to lat2 would desync the flag scoreboards). Shifter = the deep cone.
  - DYNAMIC mechanism (aluSlow* busy bitmaps + aluSlowWait + per-EU aluSlowWakeup Flow carrying the shift's int+NZVC+X dsts), mirroring cplxBusy across all 3 reg classes a shift writes. (A delayed-static-events variant was tried first + dropped: it mishandled a producer issuing before its dependent was pushed.)

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
