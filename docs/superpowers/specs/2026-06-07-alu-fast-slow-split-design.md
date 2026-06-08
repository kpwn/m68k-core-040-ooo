# ALU EU fast/slow path split (FMax recovery, IPC-preserving) — Design

**Status:** Draft (FMax recovery — the real limiter, user-approved "split fast/slow ALU paths").
**Date:** 2026-06-07
**Parent:** [[synth-gate-every-slice]] (the ALU-S1-depth finding), the IPC findings ([[ipc-and-deadlock-findings]]).

## 1. Purpose & motivation

After the AGU fix, the dominant OOC limiter is the ALU EU's S1 datapath: **24 logic levels**, `AluEu.s1Src2 → … → NZVC regfile`. The ALU is **fixed-latency-1** (`AluEuPlugin.scala:38`): its single S1 stage does the AluDatapath op (add/sub/logic) **+ the barrel shifter (`DecOp.SHIFT`, the 64-wide funnel) + the `mergedResult` mux (size-merge / MOVE / partial-reg) + the CCR read-modify-write (`toCcr`) + the NZVCX flag fold + writeback + the same-cycle bypass** — all combinational, because the result must be ready in S1 for back-to-back dependent ops (single-cycle ALU latency = the IPC path). The whole-ISA build piled the shifter + CCR-RMW onto that one cycle → 24 levels.

This slice **splits the ALU EU into a FAST 1-cycle path and a SLOW 2-cycle path**: the common dependent-chain ops (add/sub/logic/cmp/move) stay single-cycle (IPC preserved — the bypass unchanged); the DEEP, rarer ops (the barrel **shifter**, the **CCR-RMW**, and any merge complexity that's on the 24-level cone) move to a 2-cycle path (S1→S2), waking their dependents one cycle later via the IQ's existing DYNAMIC-wakeup mechanism. FMax recovers (the shifter/CCR cone leaves the single-cycle path); dependent-ALU IPC is unchanged (shifts/CCR-RMW are uncommon in tight dependent chains).

## 2. Scope

**In:**
- **Diagnose the deep ops** (the 24-level cone): confirm the barrel shifter (`isShift`) + the CCR-RMW (`toCcr`) are the offenders; check whether any unary (SWAP/EXT) / merge path is also on it. Move exactly those to the slow path; keep everything else (ADD/SUB/AND/OR/EOR/CMP/MOVE/immediates/CLR/the simple .B/.W merge) on the fast 1-cycle path.
- **Fast path (unchanged, latency-1):** add/sub/logic/cmp/move → S1 result + flags + writeback (`intW`/`nzvcW`/`xW` latency-1) + bypass + static-latency-1 wakeup, exactly as now. These are the dependent-chain IPC path.
- **Slow path (latency-2):** shift + CCR-RMW (+ whatever's on the cone) → S1 registers operands/partial → S2 computes the shift/CCR-RMW + flags → S2 writeback + completion + wakeup. The result is available in S2 (latency-2). Single-outstanding-per-slow-op or pipelined — the agent picks (a slow op is rare; a 1-deep S1→S2 is fine).
- **IQ wakeup for slow-ALU producers:** track them DYNAMICALLY (a new bitmap + wakeup port, mirroring the existing `lsBusy`/`cplxBusy` dynamic-completion mechanism `IssueQueuePlugin.scala:123-140`) so a dependent of a slow-ALU op wakes at latency-2, NOT the static latency-1. Fast ALU ops keep the static-latency-1 trigger. The slow-ALU producer's `writesNzvc`/`writesX`/`pdst` dependents all see the +1 latency.
- **Bypass:** the fast-path bypass (`intByp`/`nzvcByp`/`xByp`) is unchanged. A consumer of a slow-ALU result reads the registered value (no 1-cycle bypass for slow producers — the +1 latency covers it; confirm the bypass network doesn't wrongly forward a not-yet-final slow result).
- **Verification:** ALL lock-step UNCHANGED ×2 (latency-agnostic — shift/CCR-RMW dependents just resolve a cycle later). **IPC: dependent-ALU IPC UNCHANGED** (add/sub chains stay 1-cycle — run the IPC bench `IpcBenchSpec`; the shift-heavy path may drop slightly, that's expected + acceptable). **OOC FMax up materially from 156** (the 24-level shifter/CCR cone off the single-cycle path; report the new worst path). No new cross-module crossing.

**Out:** a full ALU pipeline (we keep the fast path single-cycle); moving add/sub/logic to 2 cycles (would tank IPC — the whole point is to NOT do that); the DIV/MUL CPLX EU (already variable-latency).

## 3. Components & dataflow

```
issue -> S0 read operands -> M2S (s1Src1/s1Src2/s1Nzvc/s1X regs)
S1: isSlow = isShift || toCcr (|| <other cone ops per diagnosis>)
    FAST (isShift=0, toCcr=0): AluDatapath -> mergedResult -> NZVCX -> writeback (lat1) + bypass + static wake  [UNCHANGED]
    SLOW: register {op, operands, partial} into S2 regs
S2 (slow only): barrel shift / CCR-RMW -> result + NZVCX -> writeback (lat2) + completion + DYNAMIC wakeup
IQ: slow-ALU producers tracked in a new dynamic bitmap (like cplxBusy); dependents wake at lat2.
```

## 4. Verification
- **Directed:** a fast op (add) completes S1 + bypasses a back-to-back dependent (1 cycle); a slow op (shift/CCR-RMW) completes S2 + its dependent waits 1 extra cycle then reads the correct value; mixed fast/slow interleave.
- **Lock-step (the gate), ×2:** ALL existing programs UNCHANGED — esp. shift/rotate, ANDI/ORI/EORI-to-CCR, and any dependent chains through a shift/CCR result (the +1 latency is invisible to the instruction-level whitebox). ITLB flake → baseline-repro first.
- **`make test-fast`** + targeted verilator `-z` subsets.
- **IPC (`IpcBenchSpec`):** dependent-ALU + independent-ALU IPC UNCHANGED (add/sub chains are the fast path); report the numbers (a shift-heavy kernel may dip — fine).
- **OOC FMax:** gen + `ooc_M68kFullCoreSynth.tcl`; report before (156) / after; the `s1Src2 → NZVC`/shifter/CCR-RMW 24-level cone OFF the single-cycle worst-path list; new worst path. Materially up.

## 5. Open items
- Exactly which ops are on the 24-level cone (the agent diagnoses via the timing report): definitely the shifter + CCR-RMW; possibly SWAP/EXT or the full mergedResult mux. Move the minimum set that clears the cone (keep the fast path as broad as possible for IPC).
- The slow-path wakeup: reuse the `cplxBusy` dynamic-completion pattern (a slow-ALU `wakeup`/`busy` port) — confirm a fast-ALU op and a slow-ALU op can both be in flight (the slow path is a 2nd lane, or the EU serializes slow ops). Keep it simple (a slow op every cycle is rare).
- The CCR-RMW slow path reads NZVC+X (already `s1Nzvc`/`s1X`); moving it to S2 just delays the fold a cycle — confirm the committed-CCR / flag-rename still composes (the slow producer writes NZVC at lat2).
- Confirm the bypass network does NOT forward a slow producer's S1 (not-yet-final) value to a same-cycle consumer (the consumer must wait for S2 — the dynamic wakeup gates issue).
