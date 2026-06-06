# Divider (DIVU/DIVS, full 040) + CHK + DIV0 — Design

**Status:** Draft (slice — user-approved: full 040 divider incl. 64÷32; radix-2 default).
**Date:** 2026-06-06
**Parent:** the decode matrix ([[decode-matrix-framework]]), the EUs, and the exception subsystem ([[exception-subsystem]] — execute-time fault completions).

## 1. Purpose

Add integer division (DIVU/DIVS, all 68040 forms) on a new multi-cycle divider EU, plus the two associated traps — DIV0 (vector 5, divide-by-zero) and CHK (vector 6, bounds-check). DIV0 and CHK reuse the execute-time fault-completion pattern (`BranchEuPlugin.trapvFault` → ROB faulted/vector → format-$0 delivery). CHK is independent of the divider (a bound-compare) and goes first to validate the generalized fault completion. Lock-stepped vs Musashi (which models DIVU/DIVS results, overflow, DIV0, CHK exactly).

## 2. Scope

**In:**
- **CHK** (`CHK.W`/`CHK.L`, vector 6): compare `Dn` against the EA operand bound — if `Dn < 0` (set N=1) or `Dn > bound` (set N=0) → execute-time trap vector 6; else no-op (N per the 68k rule; other CCR bits undefined/per-Musashi). Reuses the fault-completion path (generalized to carry a vector). `.W` compares the low 16 bits (sign-extended), `.L` the full 32.
- **Multi-cycle divider EU** (`execute/DivEuPlugin.scala`): radix-2 non-restoring iterative core (~32 cycles; the agent MAY use radix-4 only if it holds the 250 gate + area). Single-outstanding, busy-gated, with dynamic-completion wakeup (mirror `LsEuPlugin`'s `completion`/`wakeup`). Unsigned core; DIVS via operand sign-normalization + quotient/remainder sign fix-up. Produces quotient, remainder, NZVC, and execute-time DIV0/overflow.
- **DIV forms (full 040):**
  - `DIVU.W`/`DIVS.W ea,Dn`: 32-bit dividend (Dn) ÷ 16-bit divisor (ea) → 16-bit quotient (Dn[15:0]) + 16-bit remainder (Dn[31:16]). Overflow if quotient > 16 bits → **V=1, NO result write, NO trap** (68k semantics).
  - `DIVU.L`/`DIVS.L ea,Dq` (32÷32): 32-bit dividend (Dq) ÷ 32-bit divisor (ea) → 32-bit quotient (Dq). (`ea,Dr:Dq` form → Dr=remainder, Dq=quotient.)
  - `DIVU.L`/`DIVS.L ea,Dr:Dq` (64÷32): 64-bit dividend (Dr:Dq) ÷ 32-bit divisor (ea) → 32-bit quotient (Dq) + 32-bit remainder (Dr). Overflow if quotient > 32 bits → V=1, no write. **This form reads TWO GPRs (Dr:Dq = 64-bit dividend) and writes TWO GPRs** — the extension word encodes Dq (bits 14:12) and Dr (bits 2:0). Decode/rename/dispatch must handle the 2-source/2-dest µop (or crack appropriately).
  - Flags: N/Z from the quotient; **V on overflow** (quotient doesn't fit); C always 0. DIV0 → trap (flags unaffected/undefined per Musashi).
- **DIV0 trap** (vector 5): divisor == 0 at execute → execute-time fault (no result write). Reuse the generalized fault completion.
- **EU integration:** issue the div µop from the IQ to the DivEU; busy single-outstanding; the result writeback may need 2 PRF writes for the pair forms (Dr:Dq) — 2 write ports or 2 cycles. Dynamic-completion wakeup broadcasts the producing pdst(s) when the divide finishes.
- **Verification:** lock-step vs Musashi — DIVU/DIVS .W/.L(32)/.L(64) quotient+remainder+NZVC incl. overflow cases; DIV0 → handler → RTE; CHK in-bounds (no trap) + out-of-bounds (Dn<0 and Dn>bound, vector 6) → handler → RTE. Honest synth ≥250 (the iterative divider is multi-cycle with a shallow per-cycle path; should not regress ~257; the 64-bit pair read/write must not blow up the PRF/rename critical path — watch it).

**Out:** MULU/MULS (multiply — separate); the deprecated 68020 `DIVxL` quirks beyond the 040; BCD/other arithmetic.

## 3. Components & dataflow

```
CHK   : decode -> a bound-compare µop (reads Dn + ea); execute: trap iff Dn<0||Dn>bound -> fault{vec6}
DIV   : decode/crack -> div µop {form, signed, dividend regs (1 or pair), divisor ea, dest regs};
        DivEU: busy N cycles (radix-2); divisor==0 -> fault{vec5}; else quotient/remainder + V(overflow);
        writeback (quotient [+ remainder for pair forms]) + NZVC; dynamic-completion wakeup
fault completion (generalized): Flow{robId, vector} -> ROB faulted+vector -> format-$0 FSM -> handler -> RTE
```

## 4. Verification
- **Directed:** the radix-2 divider core (unsigned, then signed sign-fixup) vs a Scala reference over random operands incl. edge cases (max/min, divisor 1, overflow, divisor 0); CHK compare logic.
- **Lock-step (the gate):** vs Musashi, ×2 — DIVU/DIVS .W, .L(32÷32), .L(64÷32) with normal + overflow operands (quotient+remainder+N/Z/V step-for-step); DIV0 → handler → RTE; CHK in-bounds + both out-of-bounds cases → handler → RTE. All existing programs UNCHANGED.
- **Honest MMU-live synth ≥250** (mmuEnable/ipl inputs live, TLBs+walkers inferred); the divider is multi-cycle (shallow per-cycle) — report WNS+FMAX+critical path; confirm the 64-bit pair read/write didn't deepen the PRF/rename/IQ cone. Don't merge below 250.

## 5. Open items
- The fault-completion generalization: add a `vector` field to `trapvFault` (rename to a general `euFault{robId, vector}`) OR add separate DIV0/CHK completion ports — pick the lower-churn form (the ROB already does `faultVecStore := ...` on completions).
- The 64÷32 pair-register µop: 2 GPR sources + 2 GPR dests. Confirm rename/dispatch/PRF support 2 dests for one µop, or crack into a primary-divide + a remainder-move. Decide in Task-diagnosis; the cracked form may be simpler for the OoO rename.
- Which EU issues CHK (it's a compare+trap — could be the ALU EU or the branch EU's condition path) vs the DivEU. CHK doesn't need the divider; put it where the bound-compare + fault completion is cheapest.
- Radix-2 vs radix-4: default radix-2 (simplicity + FMax). Only go radix-4 if the gate + area are comfortable and latency matters (it rarely does — division is uncommon, lock-step is latency-agnostic).
- DIVS overflow detection during sign-normalized division (the magnitude-divide-then-fixup must detect quotient-out-of-range correctly for signed, incl. the INT_MIN/-1 edge).
