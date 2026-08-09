# Trap-family exceptions (TRAP #n, TRAPV) — Design

**Status:** Implemented. Later exception-completion slices added CHK, divide faults,
TRAPcc, and architectural line-A/line-F emulator-vector routing.
**Date:** 2026-06-05
**Parent specs:** `2026-06-03-exception-subsystem-design.md` (format-$0 precise delivery + RTE — the machinery this reuses). [[exception-subsystem]].

## 1. Purpose

Add the synchronous trap instructions that deliver via the existing format-$0 path: **TRAP #n** (unconditional software trap, vector 32+n) and **TRAPV** (trap-on-overflow, vector 7, conditional on V). These reuse the merged exception subsystem (ROB fault capture → commit-side FSM → format-$0 frame → handler → RTE) — the new work is decoding the trap opcodes and, for TRAPV, an **execute-time conditional fault**. Lock-step vs Musashi.

## 2. Scope

**In:**
- **TRAP #n** (`0x4E4n`, n = opword[3:0]): a **decode-time unconditional** trap → `DecodedUop{faulted, faultVector = 32+n}`. Always raises at retire (format-$0). The fundamental OS-call mechanism.
- **TRAPV** (`0x4E76`): **execute-time conditional** trap — raises vector 7 **only if V is set** at execution. Needs a new execute-time-conditional-fault path: the µop reads NZVC (V), and the EU (or a small trap-check) sets the ROB entry faulted (vector 7) only when V=1 — analogous to the branch EU's condition eval feeding a completion. If V=0, it retires as a no-op.
- Reuse the existing format-$0 delivery (the FSM already stacks $0 for any vector); the faulting PC is the trap instruction's PC (TRAP/TRAPV stack the PC of the *next* instruction per 68k — confirm vs Musashi: TRAP stacks nextPC, not the trap PC).
- **Verification:** lock-step vs Musashi — a `TRAP #n` → handler → RTE → resume; a `TRAPV` with V set (traps) and V clear (no-op); commit PC/SR/A7 + the $0 frame match step-for-step. Honest MMU-live synth ≥250.

**Out (later / needs other work):**
- **CHK** (vector 6) — needs a bound-compare datapath (reg vs upper bound, trap if out of range or negative); a follow-up once a compare-trap µop is added.
- **DIV0** (vector 5) — needs DIVU/DIVS (not yet implemented).
- This slice originally deferred TRAP-on-condition variants beyond TRAPV and the line-A/line-F emulator traps. Follow-on implementations now provide TRAPcc plus architectural vector 10/11 routing for unimplemented line-A/line-F opcodes; those are governed by the exception-subsystem design rather than this trap-instruction slice.

## 3. Components & dataflow

```
TRAP #n  : decode -> DecodedUop{faulted, faultVector=32+n}  (unconditional, decode-time)
           -> ROB capture -> exception FSM -> format-$0 -> handler -> RTE
TRAPV    : decode -> a trap-check µop reading NZVC (V); at execute, if V -> mark the
           ROB entry faulted (vector 7) [execute-time conditional fault, like a branch
           completion sets mispredict]; else retire as no-op.
```

For TRAPV the execute-time-conditional-fault is the one new mechanism: a small EU path (reuse the branch EU's NZVC read, or the ALU) evaluates V and drives a "fault completion" (like the LS `lsFaultCompletion` or branch completion) that sets the ROB entry's faulted+vector when the condition holds. The ROB already has the per-entry fault capture + the commit-side FSM.

## 4. Verification
- **Directed:** decode TRAP #5 → faulted vector 37; TRAPV → trap-check µop reading V.
- **Lock-step (the gate):** TRAP #n → handler → RTE → resume; TRAPV (V set → trap; V clear → fall through); vs Musashi, $0 frame + PC/SR/A7 step-for-step (Musashi fully models TRAP/TRAPV). ×2.
- **Honest MMU-live synth ≥250** (mmuEnable live, both TLBs+walkers inferred); report WNS+FMAX.

## 5. Open items
- TRAP/TRAPV stacked PC: 68k stacks the PC of the NEXT instruction (TRAP is not restartable) — confirm vs Musashi and use the µop's nextPc, not its own PC.
- TRAPV execute-time-conditional-fault: which EU evaluates it (branch EU has the NZVC read; or a tiny dedicated path) — pick the lower-churn form in the plan; it must set the ROB entry faulted before/at retire.
