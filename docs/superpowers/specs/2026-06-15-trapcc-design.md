# TRAPcc (020+ conditional trap) — Design

**Status:** Draft — controller-driven (ISA-completion track; user: "you pick / batch it, features first").
**Date:** 2026-06-15
**Parent:** [[isa-completion-roadmap]] — first slice of the "bounded 020 integer ops" phase. Reuses the trap/exception machinery ([[exception-subsystem]], [[traps-trap-trapv]]) and the branch-EU condition eval.

## 1. Instruction
TRAPcc — conditional trap (68020/030/040). Encoding (line-5): `0101 cccc 11 111 ttt`
- `cccc` (op[11:8]) = the 4-bit condition code (same 16-condition set as Bcc/Scc/DBcc).
- `ttt` (op[2:0]) selects the operand form (the operand is ignored by the CPU — it exists only for the handler to inspect via the stacked PC; it ONLY affects instruction length):
  - `100` → no operand → **1 word** (just the opword).
  - `010` → `#<data16>` → **2 words**.
  - `011` → `#<data32>` → **3 words**.
  - any other `ttt` → ILLEGAL.

**Behavior:** evaluate `cccc` against the current NZVC. If TRUE → take a TRAP (vector 7), stacking a **format-$2** frame exactly like TRAPV (PC = the NEXT instruction's PC, i.e. past the operand words; PPC = this instruction's own PC). If FALSE → no-op (fall through to nextPc). TRAPcc writes no register and does not change CCR.

## 2. Reuse (this is mostly a decode + a tiny EU-condition generalization)
- **Condition eval:** the shared 16-condition LUT in `execute/BranchEuPlugin.scala:102-119` (`taken = u1.cond.mux(...)`), already used by Bcc/Scc/DBcc/TRAPV. TRAPcc routes its `cccc` through the SAME mux.
- **Execute-time conditional trap:** TRAPV is already a branch-class cond-trap µop (`isTrapv`, `DecodedUop.scala:131`; raised in `BranchEuPlugin.scala:178` via `trapvFaultPort` when V=1, vector 7). **Generalize it:** rename/extend `isTrapv` → a `isCondTrap` cond-trap µop where the trap condition rides the existing `cond` field; the EU raises the `EuFault` (vector 7) iff `taken` (= cond eval) AND `isCondTrap`. **TRAPV becomes a cond-trap with `cond = VS (9)`** (V-set); TRAPcc uses `cond = cccc`. This removes TRAPV's special-case direct `v` read and unifies the two.
  - **Branch-redirect suppression:** for a cond-trap µop the branch must NOT redirect/mispredict regardless of `taken` — gate the EU's mispredict/redirect-valid with `&& !isCondTrap`. (TRAPV previously achieved no-redirect by forcing `cond=F`; now that `cond` carries the real trap condition, the suppression must be explicit. This adds one AND term to the mispredict path — watch the synth gate; if it regresses traceably, fall back to a separate `trapCond` field + a second cond mux on the fault path, leaving the branch `cond=F`.)
- **`EuFault` + ROB:** unchanged — `EuFault{robId, vector=7}` (`BranchEuPlugin.scala:25`), consumed at `RobPlugin.scala:430` (`euFaultCompletion` → faulted + vector). The per-entry stacked-PC (`faultUsesNextPc` → nextPc) and PPC (`pcStore` = instruction PC) are already captured at alloc.
- **Format-$2 delivery:** `ExceptionUnit.scala:377` `is2` already covers vector 7 (TRAPV). TRAPcc delivers the identical format-$2 frame. **No ExceptionUnit change.**

## 3. Decode (`OperationDecoder.scala` + `MicroOpAssembler.scala`)
- **Carve-out (no Scc/DBcc regression):** `isTrapccOp = isLine5 && ss==3 && mode==7 && reg∈{2,3,4}` (where `ss=op[7:6]`, `mode=op[5:3]`, `reg=op[2:0]`). Scc is `mode==0`, DBcc is `mode==1` — orthogonal to `mode==7`, untouched. `mode==7 && reg∉{2,3,4}` stays in the existing illegal bucket (`sccMemBad`). The assembler builds the cond-trap µop (OperationDecoder leaves line-5 ss==3 illegal; the assembler overrides, as it already does for Scc/DBcc).
- **The µop (single, branch-class):** no source/dest register, no memory op. Set: `isBranch := True` (route to branch EU), `isCondTrap := True`, `cond := cccc`, `readsNzvc := True`, `writesNzvc := False`, `dstValid/srcAValid/srcBValid := False`, `faultUsesNextPc := True`, `unimplemented := False`. The `entryPpc`/PPC = the instruction PC (the µop's `pc`); nextPc = `pc + length` (1/2/3 words by `ttt`). The operand words are NOT read by any µop — they are consumed purely by predecode framing (length).

## 4. Predecode framing (`frontend/PredecodeWord.scala` + the test ref) — the #1 bug source
Extend the line-5 `ss==3` branch (`PredecodeWord.scala:379-401`): when `mode==7 && reg∈{2,3,4}` →
- `reg==4` (no operand) → `lenWords := 1`
- `reg==2` (#imm16) → `lenWords := 2`
- `reg==3` (#imm32) → `lenWords := 3`
- mark `simple := True`.
`mode==7 && reg∉{2,3,4}` stays unframed (illegal). Mirror EXACTLY in the `PredecodeRef` golden so the 65536-opword `PredecodeWordSpec` parity holds. **A wrong length → nextPc=pc → wild PC (the recurring failure); test all three forms.**

## 5. Verification
- **Lock-step vs Musashi** (the gate): TRAPcc cond-TRUE → trap → handler → RTE → resume; TRAPcc cond-FALSE → fall through to nextPc; cover all three length forms (no-operand, #imm16, #imm32) and a couple conditions (e.g. T/F/EQ/VS/MI); assert SR/PC/A7/regs step-for-step. **TRAPV must still pass** (the generalization). Confirm Musashi (CPU_TYPE 68040) decodes TRAPcc — it's standard 020+; if the oracle rejects it, STOP and report (don't fake).
- **Directed decode:** `TrapccDecodeSpec` — the three forms decode to the cond-trap µop; `mode==7 reg∉{2,3,4}` illegal; Scc/DBcc unaffected.
- **Parity:** `PredecodeWordSpec` 65536-opword GREEN (the new framing). Also run `OperationDecoderSpec` (a new live op may flip a stale "X→illegal" assert).
- **fastTest** green.
- **Synth gate (controller):** honest post-route `synth/impl_FullCore.tcl` ≥200 MHz. Expect FMax-neutral (TRAPcc adds only decode + a tiny EU gate). If the `!isCondTrap` mispredict gate regresses traceably, note it.

## 6. Out of scope
Nothing deferred for TRAPcc itself (all three forms are in). This is a self-contained slice; the next bounded ops (PACK/UNPK line-8, CMP2/CHK2 + MOVEP line-0) follow, fan-out where the decode regions are independent.
