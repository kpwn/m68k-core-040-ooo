| fpu_fintrz_basic.s — FINTRZ.X FPm,FPn directed test (round toward zero).
|
| Goal: truncate fractional part regardless of FPCR rounding mode.
|
| Sequence:
|   FP0 := 3.7  → FINTRZ → expect 3.0  (0x40400000)
|   FP2 := -3.7 → FINTRZ → expect -3.0 (0xC0400000)
|   FP4 := 2.5  → FINTRZ → expect 2.0  (0x40000000)
|
| Encodings:
|   FINTRZ.X FPm,FPn  ext = (FPm<<10) | (FPn<<7) | 0x03
|   FMOVE.S FPn,(A0) (bug fix 2026-07-11, found via fuzz-corpus
|     widening): source FPn is at ext[9:7], format at ext[12:10] --
|     ext = 0x6400 | (FPn<<7), NOT the old (FPn<<10) this file used
|     to use (invisible only for FPn==FP0; decode_1111.vh read the
|     wrong bit field).
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0F01 — vec-11 F-line trap
|   0xDEAD0F03 — FINTRZ(3.7) != 3.0
|   0xDEAD0F04 — FINTRZ(-3.7) != -3.0
|   0xDEAD0F05 — FINTRZ(2.5) != 2.0
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

    | FP0 := 3.7
    move.l  #0x40666666, %d0
    .short  0xF200, 0x4400             | FMOVE.S D0,FP0

    | FP1 := FINTRZ.X FP0,FP1  ext = (0<<10)|(1<<7)|0x03 = 0x83
    .short  0xF200, 0x0083

    | Verify FP1 == 3.0 == 0x40400000
    .short  0xF210, 0x6480
    move.l  (%a0), %d1
    cmp.l   #0x40400000, %d1
    bne     _fail_3

    | FP2 := -3.7
    move.l  #0xC0666666, %d0
    .short  0xF200, 0x4500             | FMOVE.S D0,FP2

    | FP3 := FINTRZ.X FP2,FP3  ext = (2<<10)|(3<<7)|0x03 = 0x983
    .short  0xF200, 0x0983

    | Verify FP3 == -3.0 == 0xC0400000
    .short  0xF210, 0x6580
    move.l  (%a0), %d1
    cmp.l   #0xC0400000, %d1
    bne     _fail_4

    | FP4 := 2.5
    move.l  #0x40200000, %d0
    .short  0xF200, 0x4600             | FMOVE.S D0,FP4

    | FP5 := FINTRZ.X FP4,FP5  ext = (4<<10)|(5<<7)|0x03 = 0x1283
    .short  0xF200, 0x1283

    | Verify FP5 == 2.0 == 0x40000000
    .short  0xF210, 0x6680
    move.l  (%a0), %d1
    cmp.l   #0x40000000, %d1
    bne     _fail_5

    | PASS
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_3:
    move.l  #0xDEAD0F03, %d2
    bra     _do_fail
_fail_4:
    move.l  #0xDEAD0F04, %d2
    bra     _do_fail
_fail_5:
    move.l  #0xDEAD0F05, %d2
    bra     _do_fail
_do_fail:
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
