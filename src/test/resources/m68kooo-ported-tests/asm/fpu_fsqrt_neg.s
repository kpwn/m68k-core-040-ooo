| fpu_fsqrt_neg.s — FSQRT.X special-case directed test (sqrt of negative).
|
| Goal: verify FSQRT(-1.0) returns NaN per IEEE 754 §6.2 (invalid op).
| Result format: single-precision NaN has exp == 0xFF and frac != 0.
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
|   0xDEAD0F01 — vec-11 F-line trap
|   0xDEAD0F03 — sqrt(-1.0) was not NaN (exp != 0xFF or frac == 0)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ SCRATCH,   0x00020000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C
    lea     SCRATCH, %a0

    | FP0 := -1.0
    move.l  #0xBF800000, %d0
    .short  0xF200, 0x4000             | FMOVE.S D0,FP0

    | FP1 := FSQRT.X FP0,FP1
    .short  0xF200, 0x0084             | FSQRT.X FP0,FP1

    | Read FP1 as single
    .short  0xF210, 0x6480             | FMOVE.S FP1,(A0)
    move.l  (%a0), %d1

    | Check single NaN: exp (bits 30..23) == 0xFF AND mantissa (bits 22..0) != 0.
    move.l  %d1, %d2
    lsr.l   #8, %d2
    lsr.l   #8, %d2
    lsr.l   #7, %d2
    and.l   #0xFF, %d2                 | extract exp
    cmp.l   #0xFF, %d2
    bne     _fail_3

    move.l  %d1, %d2
    and.l   #0x007FFFFF, %d2           | extract mantissa
    tst.l   %d2
    beq     _fail_3                    | mantissa zero → infinity, not NaN

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
