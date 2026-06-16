# Bit-field register forms (BFxxx Dn, static offset/width) — Design

**Status:** Draft — controller-driven (ISA-completion, the bit-field family — slice 1 of 3: register/static).
**Date:** 2026-06-16
**Parent:** [[isa-completion-roadmap]]. Reuses the barrel shifter (`execute/Shifter.scala` rotate) + the ALU EU. Adds a bit-field datapath (rotate + mask + priority-encode) + `DecOp.BITFIELD`.

## 1. Scope (slice 1)
The 8 bit-field ops, **REGISTER operand (Dn) + STATIC offset/width (immediate in the ext word)** only:
BFTST, BFCHG, BFCLR, BFSET, BFEXTU, BFEXTS, BFFFO, BFINS.
**DEFERRED** (later slices): dynamic offset/width (Do=1 or Dw=1 → read offset-Dn / width-Dn = 3-4 sources → needs a crack); memory-operand bit-fields (field spans ≤5 bytes → µcode). Those stay ILLEGAL (no regression — they're unimplemented today).

## 2. Encoding (line-E)
Opword: `1110 1ooo 11 000 rrr` — bits[15:12]=1110; bits[11:8]=`1ooo` (op: 8=BFTST,9=BFCHG,A=BFCLR,B=BFSET,C=BFEXTU,D=BFEXTS,E=BFFFO,F=BFINS); bits[7:6]=`11` (ss=3); bits[5:3]=`000` (mode=Dn); bits[2:0]=`rrr` (Dn = the field register Dy). Extension word: `0 | Do(b11) | offset(b10:6) | Dw(b5) | width(b4:0)`, plus bits[14:12] = the data register Dn2 (dest for BFEXTU/EXTS/FFO; source for BFINS). For slice 1: Do=0, Dw=0 (static) — if Do=1 or Dw=1 → ILLEGAL (deferred).

## 3. Semantics (transcribe Musashi VERBATIM — `m68k_in.c` BFxxx `_32_d` forms; the harness compares the FULL CCR)
m68k bit numbering: **offset 0 = the MSB (bit 31)**; the field is `width` bits from bit (31-offset) toward the LSB. Normalize: `offset &= 31; width = ((width-1)&31)+1` (width 0→32). `V=0, C=0, X UNCHANGED` for ALL 8.
- **mask** = `ROR_32(0xffffffff << (32-width), offset)` (the field bits set, positioned).
- **BFTST**: N = bit31 of `(Dy << offset)` (the field's MSB); Z = `(Dy & mask)` (field bits, tested nonzero). No write.
- **BFCHG/BFCLR/BFSET**: N = bit31 of `(Dy<<offset)`, Z = `(Dy & mask)` — both from the OLD Dy; then `Dy ^= mask` (CHG) / `Dy &= ~mask` (CLR) / `Dy |= mask` (SET). Writes Dy.
- **BFEXTU**: `data = ROL_32(Dy, offset)`; N = bit31(data); `data >>= (32-width)` (logical, zero-extend); Z = data; Dn2 := data.
- **BFEXTS**: same but `data = (sint32)data >> (32-width)` (ARITHMETIC right-shift, sign-extend); Dn2 := data; N = bit31 of the pre-shift rotated data; Z = data.
- **BFFFO**: `data = ROL_32(Dy, offset)`; N = bit31(data); `field = data >> (32-width)`; Z = field; scan the field MSB→LSB for the first SET bit; `Dn2 := offset + (#bits scanned before the first set bit)` (= offset+width if the field is all zero). (Implement as: count leading zeros of `field` within its `width` MSBs → `Dn2 = offset + clz`.)
- **BFINS**: `insert = (Dn2 << (32-width)) & 0xffffffff` (left-justify Dn2's low `width` bits); N = bit31(insert); Z = insert; `insert = ROR_32(insert, offset)`; `Dy = (Dy & ~mask) | insert`. Writes Dy. (N/Z are from the INSERTED value, NOT the old field.)

## 4. Datapath (`execute/...` — reuse the barrel shifter rotate; add mask-gen + priority-encode)
A new bit-field datapath, on the ALU EU's slow/shifter path (it reuses the lat-2 barrel rotate; `DecOp.BITFIELD`):
- Compute `offset`(5b), `width`(6b, 1-32) from the imm (the ext word's static fields). `rotL = ROL(Dy, offset)` and `mask = ROR(0xffffffff<<(32-width), offset)` via the barrel shifter (variable rotate by offset). 
- N = `rotL[31]` (field MSB) for TST/CHG/CLR/SET/EXTU/EXTS/FFO; for BFINS N = `(Dn2<<(32-width))[31]`. Z = `(Dy & mask) != 0` for TST/CHG/CLR/SET; `extracted != 0` for EXTU/EXTS/FFO; `(Dn2<<(32-width)) != 0` for BFINS. **Match Musashi's FLAG_Z semantics (it stores the value, the m68k Z bit = value==0).**
- Result: CHG/CLR/SET → `Dy ^|&~ mask`; EXTU → `rotL >> (32-width)` (logical); EXTS → arithmetic `>>`; BFFFO → `offset + clz(field)` (a priority encoder over the `width` MSBs of `rotL`); BFINS → `(Dy & ~mask) | ROR(Dn2<<(32-width), offset)`.
- **Priority encoder (BFFFO)**: a 32-bit count-leading-zeros over the field's left-justified `width` bits, clamped to `width` (all-zero → width). New (~a small CLZ tree). 
- **Flag write**: NZ only (V=0, C=0, X UNCHANGED) — write `{N, Z, 0, 0}` to NZVC, do NOT touch X (readsX=False, writesX=False; if the NZVC write port is all-4-bits, write V=C=0 explicitly; X via a separate port left unwritten).
The whole bit-field op is ONE µop (single EU op, lat-matched to the shifter's lat-2 — use the dynamic slowWakeup like SHIFT). Sources: srcA=Dy (field reg, op[2:0]); for BFINS srcB=Dn2 (ext word bits[14:12]); EXTU/EXTS/FFO write Dn2 (dst=Dn2); CHG/CLR/SET/INS write Dy (dst=Dy); TST writes nothing.

## 5. Decode (`OperationDecoder.scala` + `MicroOpAssembler.scala`)
- In the `is(0xE)` block, add an `elsewhen(ss === 3)` arm (currently absent): `isBitfieldReg = (op[11]=1 i.e. op[11:8]>=8) && (mode(op[5:3])==0)`. (op[11]=0 with ss=3 = the deferred memory single-bit shift — leave illegal. mode≠0 = memory bit-field — leave illegal/deferred. Do=1 or Dw=1 in the ext word → illegal, deferred.) Set `DecOp.BITFIELD` + a 3-bit `bfOp` (op[10:8] = 0..7 for TST/CHG/CLR/SET/EXTU/EXTS/FFO/INS), size=LONG.
- The µop (single, ALU/shifter cluster): srcA=Dy(op[2:0]); BFINS srcB=Dn2(ext[14:12]); dst = Dn2 (EXTU/EXTS/FFO) / Dy (CHG/CLR/SET/INS) / none (TST). `useImm=True; imm =` packed {offset(5), width(6 normalized)} (or carry offset+raw-width and normalize in the EU). readsNzvc=False; writesNzvc=True (NZ, V=C=0); writesX=False. Add `DecOp.BITFIELD` + `bfOp` field (3 bits) threaded decode→rename→EU (minimal new field).

## 6. Predecode (`PredecodeWord.scala` + `PredecodeRef`)
Line-E: add `isBitfieldReg = (op[11:8]>=8) && (op[7:6]==3) && (op[5:3]==0)` → SIMPLE **len=2** (opword + ext word). Keep shifts (ss≠3) len=1. ss=3 non-bitfield (op[11]=0 memory-shift, or mode≠0) stays COMPLEX/illegal. Mirror in `PredecodeRef`; 65536 parity.

## 7. Verification
- **Lock-step vs Musashi** (gate): all 8 ops, register form, with a spread of static offset/width incl EDGE cases — offset=0 (field at MSB), offset near 31, width=1, width=32 (the 0→32 encoding: width field=0), a field NOT at a byte boundary; BFEXTS sign-extend (negative field MSB); BFFFO with the first-set at various positions + an all-zero field (→ offset+width); BFINS inserting into the middle. Assert the FULL CCR (N/Z; V=C=0; **X UNCHANGED** — sentinel X before each op) + the written reg (Dy or Dn2) vs Musashi. **Run each test ×2+ seeds** (per the CMP2/MOVEP lesson — per-seed flakes hide bugs). Confirm gas `-m68040` + Musashi accept `bftst %d0{#4:#8}`, `bfextu %d0{#0:#16},%d1`, `bfins %d2,%d0{#8:#8}`, etc.; if rejected/diverged on the encoding, STOP+report.
- **Directed** `BitfieldDecodeSpec`: the 8 ops decode to the BITFIELD µop with the right bfOp/srcs/dst/offset/width; Do=1/Dw=1 → illegal (deferred); mode≠0 / op[11]=0 → not bit-field; shifts (ss≠3) still decode.
- **Parity** `PredecodeWordSpec` 65536 GREEN; `OperationDecoderSpec`.
- **fastTest** green.
- **Synth gate (controller):** post-route ≥200. Watch the shifter/ALU slow-path cone (the bit-field datapath + the CLZ add to it); honest report. The CLZ priority encoder is the main new logic — if it pushes the shifter cone, note it.

## 8. Out of scope (deferred → later bit-field slices)
- **Dynamic offset/width** (Do=1 / Dw=1): read offset-Dn and/or width-Dn (up to 4 sources for BFINS-dynamic) → needs a crack (MOV offset-Dn→T0, MOV width-Dn→T1, then the bitfield op reads T0/T1). A clean slice-2.
- **Memory-operand bit-fields** (`BFxxx <ea>{...}`): the field spans ≤5 bytes at a control-alterable EA → µcode multi-byte load/modify/store. Slice-3 (the meatiest; bundle with the µcode-engine generalization). Leave ILLEGAL.
