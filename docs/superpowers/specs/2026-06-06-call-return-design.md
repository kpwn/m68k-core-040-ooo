# Subroutine call/return (BSR/JSR/RTS/RTR/JMP) — Design

**Status:** Draft (feature-completion slice 1 — the foundational control-flow gap). User: "you pick / batch it."
**Date:** 2026-06-06
**Parent:** the branch handling direction ([[branch-handling-direction]] — "calls cracked into store+branch"), the LS cluster ([[ls-cluster]]), the decode matrix ([[decode-matrix-framework]]).

## 1. Purpose

Implement subroutine call/return so real programs run: **BSR**, **JSR** (call: push return-PC + branch), **RTS**, **RTR** (return: pop + branch), **JMP** (computed-target branch, no push). Today BSR decodes as a plain PC-relative BRANCH (no stack push) and JSR/RTS/RTR/JMP are illegal. Two new mechanisms: (a) a **data-driven branch target** (JSR/JMP target = the EA effective ADDRESS; RTS/RTR target = a value LOADED from the stack) — the branch EU currently only forms `pc+2+disp`; the RedirectService already accepts any 32-bit `nextPc`; and (b) **stack push/pop** (predecrement/postincrement A7). Lock-stepped vs Musashi.

## 2. Scope

**In:**
- **BSR** (`0x61xx`, .B/.W/.L disp): crack into [push return-PC (= nextPc) to the stack] + [PC-relative branch to `pc+2+disp`]. Return-PC = the post-instruction PC.
- **JSR** (`0x4E80 | ea`, control modes): crack into [push return-PC] + [branch to the EA effective ADDRESS]. Control EA modes: `(An)`, `(d16,An)`, `(d8,An,Xn)` (if indexed in-scope; else defer), `(xxx).W/.L`, `(d16,PC)`, `(d8,PC,Xn)`. The target is the computed address (AGU), NOT a memory load.
- **JMP** (`0x4EC0 | ea`): branch to the EA effective address (NO push). Same control modes.
- **RTS** (`0x4E75`): crack into [pop a long from the stack → T] + [branch to T]. The branch target is the loaded value (the LS load feeds the branch — a load→branch dependency).
- **RTR** (`0x4E77`): pop CCR (word) then PC (long) from the stack, restore CCR (low 8 bits of SR), branch to PC. (2 pops + a CCR write + an indirect branch.)
- **Stack push/pop**: predecrement A7 by 4 before a push, postincrement A7 by 4 after a pop (RTR: +2 for the CCR word, +4 for PC = +6 total). The implementer picks the mechanism: implement `-(An)`/`(An)+` as a general store/load side-effect (preferred — reused by MOVEM/PEA/LINK later; the EaDecoder currently classifies them MEMCOMPLEX-deferred) OR hand-crack explicit A7 ALU updates + `(A7)` MEMSIMPLE accesses (like the ExceptionUnit's frame stacking). If implementing the general modes, scope them to A7 stack use here + the directed (An)+/-(An) tests; broader use is fine.
- **Indirect/computed-target branch**: a branch µop whose target `nextPc` comes from a source operand (the AGU result for JSR/JMP; the loaded value for RTS/RTR) instead of `branchDisp`. Drives the existing `completionPort.nextPc`/RedirectService. Unconditional (always "taken"/redirect). For RTS the branch depends on the load completing (dynamic wakeup, like any load consumer).
- **RAS / predictor**: `PredecodeMeta` already has `branchType`/`isCall`/`isReturn`; wire call→RAS-push, return→RAS-pop if the predictor consumes it (or leave the predictor as-is and just make execution correct — prediction is an optimization, correctness via the redirect/flush on the resolved target is the gate).
- **Verification**: lock-step vs Musashi — a real subroutine (`BSR sub … sub: … RTS`), JSR via several control EA modes, JMP, RTR (CCR+PC pop), nested calls (call within a handler), call/return interacting with the stack (A7 correct after). PC/SR/A7/regs + stack memory step-for-step. Honest synth ≥250 (the indirect-branch target-mux + the push/pop are off the D-cache cone; the limiter is the fetch FSM — confirm non-regress vs ~266).

**Out:** `LINK`/`UNLK`, `PEA` (related stack ops — next slices); the full indexed `(d8,An,Xn)` mode if not already in-scope (defer to the addressing-mode slice); RAS misprediction-recovery tuning (correctness-first).

## 3. Components & dataflow

```
BSR  : [push.l retPC -> -(A7)] + [bra pc+2+disp]                       (retPC = nextPc)
JSR  : [push.l retPC -> -(A7)] + [ibranch -> AGU(ea address)]
JMP  : [ibranch -> AGU(ea address)]                                    (no push)
RTS  : [pop.l (A7)+ -> T] + [ibranch -> T]
RTR  : [pop.w (A7)+ -> CCRtmp; restore CCR] + [pop.l (A7)+ -> T] + [ibranch -> T]
ibranch: unconditional branch whose nextPc = a source value -> completionPort.nextPc -> redirect
```

## 4. Verification
- **Directed:** decode BSR/JSR/RTS/RTR/JMP → the right crack; the indirect-branch target = source value; A7 predecrement/postincrement correct.
- **Lock-step (the gate), ×2:** `BSR sub…RTS` round trip; JSR through `(An)`/`(d16,An)`/`(xxx).L`/`(d16,PC)`; JMP; RTR (CCR+PC); nested calls; verify A7 + stacked return addresses + final regs/PC/SR vs Musashi. ALL existing programs UNCHANGED (the pre-existing ITLB seed flake → repro on baseline before attributing).
- **Honest MMU-live synth ≥250** (mmuEnable/ipl live, TLBs+walkers inferred); report WNS+FMAX+critical path; confirm non-regress vs ~266 (the new logic is decode-crack + an indirect-target mux + stack push/pop — off the D-cache cone).

## 5. Open items
- Stack-mode mechanism: general `-(An)`/`(An)+` (reused later) vs hand-cracked A7 ALU + `(A7)` — implementer picks + records; if general, keep it correct for A7 + the directed tests.
- The indirect-branch µop: a new DecOp (e.g. `JUMP`/`IBRANCH`) vs a flag on BRANCH carrying "target from source"; pick the lower-churn form. It reads a source operand as the target (the AGU result or the loaded value).
- RTS target dependency: the branch must wait on the pop-load's result (dynamic-completion wakeup — the load produces the target reg the branch consumes). Confirm the IQ/wakeup handles a branch consuming a load result.
- RTR CCR restore: writes the CCR (SR low byte) — route through the committed-CCR path; confirm it composes with the SR system byte (RTR restores only CCR, not the system byte).
- Misaligned/odd stack handling + the predictor RAS interaction are correctness-secondary (the resolved-target redirect/flush is the correctness mechanism).
