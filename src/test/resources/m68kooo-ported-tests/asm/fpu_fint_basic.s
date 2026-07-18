| fpu_fint_basic.s — FINT.X FPm,FPn directed test (round to integer per FPCR).
|
| Goal: round-to-nearest-even (default FPCR rounding mode) on FP source.
|
| Sequence:
|   FP0 := 3.7  (via FMOVE.S #0x4066_6666 D0 → FP0)
|   FP1 := FINT.X FP0      (expect 4.0 == 0x40800000 single)
|   FP2 := 2.5  (via FMOVE.S #0x40200000 D0 → FP2)
|   FP3 := FINT.X FP2      (expect 2.0 == 0x40000000 single — banker's rounding)
|   FP4 := -3.7 (via FMOVE.S #0xC0666666 D0 → FP4)
|   FP5 := FINT.X FP4      (expect -4.0 == 0xC0800000 single)
|
| Encodings:
|   FINT.X FPm,FPn   ext = (FPm<<10) | (FPn<<7) | 0x01
|   FMOVE.S FPn,(A0) (bug fix 2026-07-11, found via fuzz-corpus
|     widening): source FPn is at ext[9:7], format at ext[12:10] --
|     ext = 0x6400 | (FPn<<7), NOT the old (FPn<<10) this file used
|     to use (invisible only for FPn==FP0; decode_1111.vh read the
|     wrong bit field).
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0F01 — vec-11 F-line trap
|   0xDEAD0F03 — FINT(3.7) != 4.0
|   0xDEAD0F04 — FINT(2.5) != 2.0  (banker's rounding)
|   0xDEAD0F05 — FINT(-3.7) != -4.0

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
    .short  0xF200, 0x4000             | FMOVE.S D0,FP0

    | FP1 := FINT.X FP0,FP1  ext = (0<<10)|(1<<7)|0x01 = 0x81
    .short  0xF200, 0x0081

    | Verify FP1 == 4.0 == 0x40800000.  ext = 0x6400 | (1<<7) = 0x6480
    .short  0xF210, 0x6480
    move.l  (%a0), %d1
    cmp.l   #0x40800000, %d1
    bne     _fail_3

    | FP2 := 2.5
    move.l  #0x40200000, %d0
    .short  0xF200, 0x4100             | FMOVE.S D0,FP2

    | FP3 := FINT.X FP2,FP3  ext = (2<<10)|(3<<7)|0x01 = 0x981
    .short  0xF200, 0x0981

    | Verify FP3 == 2.0 == 0x40000000 (banker's rounding rounds 2.5 to even 2).
    .short  0xF210, 0x6580
    move.l  (%a0), %d1
    cmp.l   #0x40000000, %d1
    bne     _fail_4

    | FP4 := -3.7
    move.l  #0xC0666666, %d0
    .short  0xF200, 0x4200             | FMOVE.S D0,FP4

    | FP5 := FINT.X FP4,FP5  ext = (4<<10)|(5<<7)|0x01 = 0x1281
    .short  0xF200, 0x1281

    | Verify FP5 == -4.0 == 0xC0800000.
    .short  0xF210, 0x6680
    move.l  (%a0), %d1
    cmp.l   #0xC0800000, %d1
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
