# ALU immediates + EOR (register/data dest) — Design

**Status:** Implemented. Later mem-RMW and privileged-system slices supersede the
original deferred list below; task #140 also ratified the alternate EA-source
immediate encodings described in Scope.
**Date:** 2026-06-06
**Parent:** [[decode-matrix-framework]], [[isa-completion-roadmap]].

## 1. Purpose

Fill the common ALU gaps: the line-0 **immediate** forms (ADDI/SUBI/ANDI/ORI/EORI/CMPI) to data registers, the **ANDI/ORI/EORI to CCR** forms, and **EOR** (line B, register dest). These reuse the existing ALU datapath (ADD/SUB/AND/OR + a new EOR op + CMP for CMPI) — the new work is the line-0 immediate decode (op + size + the trailing imm word(s)) + EOR's DecOp + the to-CCR write. Lock-stepped vs Musashi. **POST-ROUTE gated** (~240 baseline, don't regress).

## 2. Scope

**In:**
- **Line-0 immediates** (`0000 ooo0 ss mmmrrr` + imm): ORI(000)/ANDI(001)/SUBI(010)/ADDI(011)/EORI(101)/CMPI(110), sizes .B/.W/.L, **data-register destination (mode 0)**. The immediate is the trailing word(s) (1 word for .B/.W, 2 for .L) — predecode length must account for it. Maps to the existing ALU ops (+ new EOR for EORI; CMP for CMPI). Flags per the op (ADDI/SUBI: NZVCX; ANDI/ORI/EORI: NZ, V=C=0; CMPI: NZVC, no write).
- **ANDI/ORI/EORI to CCR** (`0000 ooo0 00 111100` + imm.B): the immediate byte ANDed/ORed/EORed into the CCR (NZVCX low 5 bits). NOT privileged (CCR only — does NOT touch the system byte). Routes through the committed-CCR path.
- **EOR** (line B, opmode 4/5/6 = .B/.W/.L, `1011 rrr 1 ss mmmrrr`): `Dn ^ <ea> -> <ea>` — **data-register destination only** (mode 0; memory dest deferred to the RMW slice 3b). New DecOp `EOR` (or reuse AND/OR datapath with an xor control). Flags NZ, V=C=0.
- **Alternate immediate-source encodings:** confirmed and implemented for the ordinary line-8/9/B/C/D EA-source bands: OR/SUB/AND/ADD/CMP .B/.W/.L and ADDA/SUBA/CMPA .W/.L accept mode-7/reg-4 `#imm`. They frame to two words for byte/word and three for long. Canonical assembler output still uses line-0 ADDI/SUBI/ANDI/ORI/CMPI, so both families remain required.
- **Verification:** lock-step vs Musashi — ADDI/SUBI/ANDI/ORI/EORI/CMPI #imm,Dn (.B/.W/.L) with flag checks; alternate line-8/9/B/C/D immediate-source forms; ANDI/ORI/EORI #imm,CCR; EOR Dn,Dm. The reference test exhaustively sweeps every destination register in the alternate bands.

**Out:** memory-destination RMW (ADDI #x,(An), EORI #x,(An), EOR Dn,(An) — slice 3b, the load-op-store crack); ANDI/ORI/EORI/MOVE to **SR** (privileged + serializing system-byte write — the MOVE-to-SR slice); ADDQ/SUBQ/Scc/DBcc (line 5 — slice 4); bit ops (line 0 opmode 100 — slice 5).

## 3. Components & dataflow

```
ADDI/SUBI/ANDI/ORI/EORI/CMPI #imm,Dn : decode line0 -> ALU op {Dn, #imm} -> Dn + flags
ANDI/ORI/EORI #imm,CCR                : decode -> CCR := CCR op imm[4:0] (committed-CCR path)
EOR Dn,Dm                            : decode lineB opmode4/5/6 mode0 -> Dm := Dm ^ Dn + NZ
```

## 4. Verification
- **Directed:** decode each line-0 immediate (op/size/imm-length) + EOR + to-CCR; the imm word(s) consumed (predecode length correct).
- **Lock-step (the gate), ×2:** ADDI/SUBI/ANDI/ORI/EORI/CMPI #imm,Dn (.B/.W/.L, incl. flag-affecting operands: carry, overflow, zero, negative); ANDI/ORI/EORI #imm,CCR; EOR Dn,Dm — value + NZVCX step-for-step vs Musashi. ALL existing UNCHANGED (ITLB seed flake → repro on baseline first).
- **`make test-fast`** + targeted verilator `-z` subsets.
- **POST-ROUTE gate** (impl_FullCore.tcl, gen first): target 250 MHz and report WNS/FMAX; 200 MHz is the current deployment floor. OOC is a quick sanity proxy only.

## 5. Open items
- EOR as a new DecOp vs reusing `AluDatapath` xor — implementer picks (the ALU likely already has an xor primitive for some path; check).
- Predecode length for line-0 immediates: .B/.W = opword + 1 imm word; .L = opword + 2 imm words. The `PredecodeWord` length table must cover line 0 (or it'll mis-frame nextPc — the recurring "len=0 → nextPc=pc" gotcha from traps/MUL).
- The to-CCR byte: AND/OR/EOR of the immediate byte into NZVCX (5 meaningful bits); confirm the committed-CCR fold accepts a direct CCR write (vs only EU-produced flags).
- Immediate sign/zero extension per size for the ALU op (.B/.W imm sign-handling into the 32-bit datapath).
