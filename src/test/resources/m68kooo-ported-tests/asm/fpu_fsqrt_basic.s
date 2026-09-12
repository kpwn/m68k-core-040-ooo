| fpu_fsqrt_basic.s — FSQRT.X directed test (sqrt of a positive normal).
|
| Goal: exercise the iterative digit-recurrence square root unit on a
| simple finite-positive case where the answer is exactly representable.
|
| Sequence:
|   FP0 := 4.0  (via FMOVE.S #0x40800000 D0 → FP0)
|   FP1 := FSQRT.X FP0  (expect 2.0 == 0x40000000 single)
|   FMOVE.S FP1,(A0); load+cmp.l vs 0x40000000.
|
| Encodings:
|   FMOVE.S Dn,FPn      0xF200 | ext = 0x4000 | (Dn<<10) | (FPn<<7)
|   FMOVE.S FPn,(A0)    0xF210 | ext = 0x6400 | (FPn<<7)
|     (bug fix 2026-07-11, found via fuzz-corpus widening: source FPn
|     is at ext[9:7], format at ext[12:10] -- this file used to use
|     (FPn<<10), invisible only for FPn==FP0; decode_1111.vh read the
|     wrong bit field)
|   FSQRT.X FPm,FPn     0xF200 | ext = (FPm<<10) | (FPn<<7) | 0x04
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0F01 — vec-11 F-line trap (decode hole)
|   0xDEAD0F03 — sqrt(4.0) != 2.0
|
| Note: this test deliberately performs only one FSQRT+FMOVE.S verify
| chain.  Multiple back-to-back FSQRT+FMOVE.S in a single program would
| race on the single x2s_stash_r register inside fpu_top (FMOVE.S
| FPn,(An) crack stashes the 32-bit single in a shared reg, with no
| serialisation between concurrent X2S micro-ops).  See sibling tests
| fpu_fsqrt_zero / fpu_fsqrt_neg for the other special cases.
| ENCODING CORRECTED 2026-09-12: these FMOVE.S loads carried source specifier
| 000 = LONG WORD INTEGER (ext 0x40xx), not 001 = SINGLE (ext 0x44xx), so every
| one of them loaded the bit pattern as a 32-bit INTEGER. 0x40666666 became
| 1080033350.0 instead of ~3.6, and every downstream expectation failed. The core
| was CORRECT: with ext 0x4400 the round-trip returns 0x40666666 exactly and
| FINT gives 0x40800000 = 4.0. Same class as fpu_fmovem_ctrl_predec -- a wrong
| test, not a core defect.
|

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ SCRATCH,   0x00020000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C
    lea     SCRATCH, %a0

    | FP0 := 4.0
    move.l  #0x40800000, %d0
    .short  0xF200, 0x4400             | FMOVE.S D0,FP0  (Dn=0,FPn=0)

    | FP1 := FSQRT.X FP0,FP1  ext = (0<<10)|(1<<7)|0x04 = 0x84
    .short  0xF200, 0x0084             | FSQRT.X FP0,FP1

    | Verify FP1 == 2.0 == 0x40000000 via FMOVE.S FP1,(A0)
    | ext = 0x6400 | (FP1<<7) = 0x6480
    .short  0xF210, 0x6480
    move.l  (%a0), %d1
    cmp.l   #0x40000000, %d1
    bne     _fail_3

    | PASS
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_3:
    move.l  #0xDEAD0F03, %d2
    lea     PASS_SENT, %a1
    move.l  %d2, (%a1)
_halt_fail:
    bra     _halt_fail

_fline:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F01, %d2
    move.l  %d2, (%a1)
_halt_fline:
    bra     _halt_fline
