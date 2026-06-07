# Shifts & rotates (line E, register forms) — Design

**Status:** Draft (feature-completion slice 4). User: "you pick / batch it, features first."
**Date:** 2026-06-07
**Parent:** [[decode-matrix-framework]], [[isa-completion-roadmap]].

## 1. Purpose

Add the line-E shift/rotate family in register-destination form: **ASL/ASR, LSL/LSR, ROXL/ROXR, ROL/ROR** (.B/.W/.L), with both immediate (count 1-8) and register (Dc mod 64) shift counts. These reuse the ALU EU with a new shifter datapath. Lock-stepped vs Musashi. **Gate: lock-step (correctness) + OOC-synth sanity proxy** — NOT post-route (which is currently unreliable/non-deterministic, see [[synth-gate-every-slice]]); shifts are FMax-neutral decode/ALU work.

## 2. Scope

**In:**
- **Register-form shifts/rotates** (`1110 ccc d ss i tt rrr`): `tt` = 00 ASL/ASR, 01 LSL/LSR, 10 ROXL/ROXR, 11 ROL/ROR; `d` = direction (1=left, 0=right); `ss` = .B/.W/.L; `i` = 0 → count = `ccc` (1-8, immediate), `i` = 1 → count = `Dc[5:0] mod 64` (register `ccc`); dest = `Dr` (data reg `rrr`).
- **Shifter datapath** (new, in/near `AluDatapath`): a combinational barrel shifter handling all 8 ops × left/right × .B/.W/.L × count 0-63. Pure shifts (ASL/LSL/ASR/LSR) fill 0 / sign / 0; rotates (ROL/ROR) wrap; extend rotates (ROXL/ROXR) rotate through X.
- **Flags (the fiddly part — match Musashi exactly):**
  - N/Z from the result (per size).
  - **C** = the last bit shifted/rotated OUT (for count>0); for LSL/ASL/ROL left → the last bit out of the MSB; right → out of the LSB. **Count==0: C=0** (shifts) / **C=X** (ROXL/ROXR) / unaffected-ish (ROL/ROR set C=0 on count 0). Match Musashi's exact rule per op.
  - **X** = C for ASL/ASR/LSL/LSR/ROXL/ROXR; **ROL/ROR do NOT affect X**.
  - **V**: set by **ASL only** if the MSB changed at any point during the shift (sign overflow); 0 for all others.
  - Count==0 special cases per op (no flag change except as Musashi specifies).
- **Register shift count** (`i=1`): count = `Dc mod 64` (a 2nd data-reg source read). 0 count → the count==0 flag rules.
- **Verification:** lock-step vs Musashi — each op (8) × imm + reg count × .B/.W/.L, with operands exercising C/X/V/N/Z edges (shift-out-1, sign change for ASL-V, count 0, count ≥ size, ROX through X). ALL existing UNCHANGED.

**Out:** **memory-destination single-bit shifts** (`1110 ccc d 11 mmmrrr` — shift <ea> by 1; deferred to the memory-RMW slice, needs the load-op-store crack); the line-E memory forms generally.

## 3. Components & dataflow

```
decode line E (reg form) -> shift uop {op(8), dir, size, count (imm or Dc-read), Dr}
ALU EU: barrel-shift(Dr, count, op, dir, size) -> result + C/X/V (+N/Z) -> Dr + flags
  ROXL/ROXR read X (xRd) + write X; ROL/ROR leave X; ASL writes V on MSB-change
```

## 4. Verification
- **Directed:** the shifter datapath vs a Scala reference over all 8 ops × dir × size × counts {0,1,size-1,size,>size,63} + the flag rules (C last-out, X, ASL-V MSB-change, ROX-through-X, count-0 cases).
- **Lock-step (the gate), ×2:** each op imm + reg count, .B/.W/.L, flag-edge operands — result + NZVCX step-for-step vs Musashi. ALL existing UNCHANGED (ITLB seed flake → baseline-repro first).
- **`make test-fast`** + targeted verilator `-z` subsets.
- **OOC-synth sanity** (proxy only — post-route is unreliable per [[synth-gate-every-slice]]): gen + `ooc_M68kFullCoreSynth.tcl`, confirm 0 err / no UNASSIGNED REGISTER / reasonable LUT (the barrel shifter adds some LUTs). Shifts are FMax-neutral; do NOT gate on the noisy post-route.

## 5. Open items
- Barrel shifter structure: one combinational shifter parameterized by op/dir/size/count, vs separate. A 64-wide funnel/barrel shifter covers rotates + extend-rotates + shifts with the right fill — pick the compact form; it adds LUTs but is FMax-neutral.
- ASL's V (MSB-changed-during-shift): compute by checking if any of the bits shifted out (plus the new MSB) differ from the original sign — Musashi's exact definition; verify against the reference.
- The register shift count needs a 2nd data-reg read port (Dc) on the shift µop — confirm the ALU EU read ports / rename support it (the ALU already reads 2 srcs for normal ops; the count is the 2nd src).
- ROXL/ROXR read+write X (the X regfile) — already wired for ADD/SUB/etc.; reuse.
