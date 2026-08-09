# Line-4 unary: CLR/NEG/NEGX/NOT/TST/SWAP/EXT/TAS (register forms) — Design

**Status:** Draft (feature-completion slice 6). User: "you pick / batch it, features first."
**Date:** 2026-06-07
**Parent:** [[decode-matrix-framework]], [[isa-completion-roadmap]].

## 1. Purpose

The line-4 single-operand family on data registers: **CLR, NEG, NEGX, NOT, TST, SWAP, EXT/EXTB, TAS**. Common everywhere (loop/flag/clear/sign-extend). Reuse the ALU + the MOVE.B/.W partial-register merge (`.B/.W` preserve upper). Lock-stepped vs Musashi. Gate: lock-step + OOC sanity (FMax-neutral).

## 2. Scope

**In (data-register destination, mode 0):**
- **CLR.B/.W/.L** (`0100 0010 ss 000rrr`): Dn(size) := 0; flags **N=0, Z=1, V=0, C=0**; .B/.W preserve upper (partial merge).
- **NEG.B/.W/.L** (`0100 0100 ss …`): Dn := 0 − Dn; **NZVCX** (X=C; C set unless result 0; V on overflow); partial merge.
- **NEGX.B/.W/.L** (`0100 0000 ss …`): Dn := 0 − Dn − X; **NZVCX** (Z is CLEARED-only — preserves Z if result 0, the 68k NEGX/SUBX rule); partial merge. Reads X.
- **NOT.B/.W/.L** (`0100 0110 ss …`): Dn := ~Dn; **NZ, V=0, C=0**; partial merge.
- **TST.B/.W/.L** (`0100 1010 ss …`): test Dn(size); **NZ, V=0, C=0**; NO write.
- **TST.W/TST.L An** (`0x4A48–0x4A4F` / `0x4A88–0x4A8F`, 68020+): the
  address-register-direct read-only forms are also landed, simple/one-word. `TST.B An`
  remains illegal because byte-size address-register operands do not exist.
- **SWAP** (`0100 1000 0100 0rrr`): swap Dn[31:16]↔Dn[15:0] (full-32 write); **NZ from the 32-bit result, V=0, C=0**.
- **EXT.W** (`0100 1000 1000 0rrr`): sign-extend Dn[7:0]→Dn[15:0] (.W, preserve upper16); **EXT.L** (`…11000 0rrr`): Dn[15:0]→Dn[31:0] (full-32); **EXTB.L** (`0100 1001 1100 0rrr`, 68020+): Dn[7:0]→Dn[31:0]. NZ, V=0, C=0.
- **TAS** (`0100 1010 11 000rrr`): test Dn[7:0] → set **N/Z** from the byte, V=0, C=0; then set Dn[7] := 1 (byte write, partial merge). (The memory form is the atomic RMW — deferred.)

**Out:** ALL memory-destination forms (CLR/NEG/NEGX/NOT/TST-mem, TAS-mem atomic) → deferred to the mem-RMW slice (TST-mem is load+flag, CLR-mem is store-0, NEG/NOT-mem are RMW, TAS-mem is atomic-RMW); write-capable CLR/NEG/NEGX/NOT An-direct forms remain illegal; MOVE from/to SR/CCR/USP, MOVEM, JMP/JSR/NBCD/PEA/LEA (other line-4 ops — separate slices).

## 3. Components & dataflow

```
CLR Dn   : Dn(size):=0, N=0/Z=1/V=0/C=0 (partial merge for .B/.W)
NEG/NEGX : ALU 0-Dn(-X) -> Dn + NZVCX (NEGX: Z clear-only; reads/writes X) (partial merge)
NOT Dn   : ~Dn -> Dn + NZ (partial merge)
TST Dn   : flags only (NZ), no write
SWAP Dn  : {Dn[15:0],Dn[31:16]} -> Dn (full-32) + NZ
EXT/EXTB : sign-extend the low byte/word -> .W/.L + NZ (partial for .W)
TAS Dn   : N/Z from Dn[7:0]; Dn[7]:=1 (byte partial merge)
```

## 4. Verification
- **Directed:** decode each op/size; the flag rules (CLR Z=1; NEG C/V/X; NEGX Z-clear-only + X; NOT/TST NZ; SWAP/EXT NZ; TAS N/Z + set bit7); partial-register preservation for .B/.W.
- **Lock-step (the gate), ×2:** CLR/NEG/NEGX/NOT/TST .B/.W/.L Dn (flag edges incl. zero/negative/overflow/carry, NEGX with X set, NEGX Z-preserve), SWAP, EXT.W/.L + EXTB.L, TAS Dn — value + NZVCX step-for-step vs Musashi. ALL existing UNCHANGED (ITLB seed flake → baseline-repro first).
- **`make test-fast`** + targeted verilator `-z` subsets.
- **OOC-synth sanity** (FMax-neutral; post-route non-deterministic, not gated): 0 err, no UNASSIGNED REGISTER. No new cross-module combinational crossing (standing rule).

## 5. Open items
- NEGX's "Z clear-only" (Z := Z_old && result==0, i.e. preserves Z=0, only clears Z on nonzero) — the 68k extended-arith rule; match Musashi exactly (same as SUBX/ADDX which the divider/etc. may already model — reuse if so).
- CLR on the 68040 does a dummy read then write for memory (the register form is just a write); the register form sets Z=1/N=0 regardless.
- EXT vs EXTB.L opword decode (EXT.W=0x4880, EXT.L=0x48C0, EXTB.L=0x49C0) — distinct from SWAP (0x4840) in the same `0100 1000` group; decode carefully.
- TAS register form = test + set-bit7 (byte partial merge); the memory form is the indivisible bus cycle (deferred).
- All .B/.W writes reuse the MOVE.B/.W partial-register merge (read old Dn). NEGX/NEG/NOT read Dn (the operand IS the source, so the merge source is available like ALU ops).
