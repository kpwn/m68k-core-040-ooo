| fpu_fmove_fpn_mem_nonzero_reg.s — FMOVE.S FPn,(An) must read the
| SOURCE FPn from the right extension-word bit field for FPn != FP0.
|
| Bug found via widening tools/fuzz/gen_program.py to emit real FPU
| coverage (2026-07-11): decode_1111.vh's "FMOVE.S FPn,(An)" (FP
| register -> memory, the X2S bridge crack) read the source FPn
| register from ext1[12:10] instead of ext1[9:7].  Per real 68881/
| 68040 encoding (confirmed against GNU as, which is ISA-faithful
| here), ext1[12:10] is the destination FORMAT specifier (010=single
| for the .S form this crack implements) and ext1[9:7] is the FPn
| register field — symmetric with the "F<op> <ea>,FPn" load direction,
| which already reads FPn from ext1[9:7] correctly.
|
| The bug was invisible for FPn==FP0: both bit fields read as 000
| for register 0, which is exactly what the older
| fpu_fmove_dn_fpn_roundtrip.s test (hand-encoded via raw
| `.short 0xF210, 0x6000`, itself with ext1[12:10]=000 — a
| coincidentally-valid-looking but non-standard "format" field) always
| exercised.  It reproduces deterministically for any FPn != 0 once
| the extension word is built the way a real assembler (or this
| project's own fuzz corpus, once it started using GNU as's native
| `fmove.s` mnemonic instead of hand-picked hex) actually populates
| it: `fmove.s %fp3,(%a0)` assembles to ext=0x6580 (format=001 at
| bits[12:10], FPn=3 at bits[9:7]) — the buggy code read bits[12:10]
| (=001=FP1, not FP3) as if THAT were the register field.
|
| This test uses two DIFFERENT non-zero FPn registers (FP3, FP5) with
| DISTINCT patterns, so a register-field mixup (reading the wrong FPn,
| or always reading FP0/FP1) shows up as a definite wrong-value FAIL
| rather than silently degenerating to a case that happens to match.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0F01 — vec-11 F-line trap
|   0xDEAD0FE3 — FP3,(A0) store-back mismatch
|   0xDEAD0FE5 — FP5,(A1) store-back mismatch

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ SCRATCH0,  0x00020000
    .equ SCRATCH1,  0x00020004
    .equ PAT3,      0x40400000      | +3.0f single-precision
    .equ PAT5,      0x40A00000      | +5.0f single-precision

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 (F-line) @ 0x2C

    | FMOVE.S D0,FP3 / FMOVE.S D1,FP5 -- seed two distinct FP
    | registers with distinct exact single-precision patterns.
    move.l  #PAT3, %d0
    .short  0xF200, 0x4580              | FMOVE.S D0,FP3
    move.l  #PAT5, %d1
    .short  0xF201, 0x4680              | FMOVE.S D1,FP5

    | Store each back through its OWN FPn -- a register-field bug
    | (reading the wrong bit field) would read the wrong FP register
    | (e.g. always FP0/FP1, or FP1 for FP3's encoding) instead.
    lea     SCRATCH0, %a0
    .short  0xF210, 0x6580              | FMOVE.S FP3,(A0)
    lea     SCRATCH1, %a1
    .short  0xF211, 0x6680              | FMOVE.S FP5,(A1)

    move.l  SCRATCH0, %d2
    cmp.l   #PAT3, %d2
    bne     _fail_fp3

    move.l  SCRATCH1, %d3
    cmp.l   #PAT5, %d3
    bne     _fail_fp5

    | PASS
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_fp3:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0FE3, %d2
    move.l  %d2, (%a1)
_halt_fp3:
    bra     _halt_fp3

_fail_fp5:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0FE5, %d2
    move.l  %d2, (%a1)
_halt_fp5:
    bra     _halt_fp5

_fline:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F01, %d2
    move.l  %d2, (%a1)
_halt_fline:
    bra     _halt_fline
