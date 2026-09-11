| fpu_fmove_s_fp0_an.s — FMOVE.S FP0,(A0): the exact shape reported failing on hardware.
|
|   opword 0xF210 = F200 | <ea = (A0)>            (mode 2, reg 0)
|   ext    0x6400 = opclass 011 (FPn -> <ea>) | fmt 001 (Single) | FP0<<7 | k=0
|
| FP0 = 1.0 -> the single-precision image is 0x3F800000.
|
| PASS 0xC0FFEE00.  FAIL: 0xDEAD0F01 = F-line trap (unimplemented), 0xDEAD0F02 = wrong
| value stored, 0xDEAD0F03 = FMOVE.D wrong, 0xDEAD0F04 = FMOVE.L wrong.

    .text
    .org 0
    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C
    lea     _scratch, %a0

    moveq   #1, %d0
    .short  0xF200, 0x4000             | FMOVE.L D0,FP0   -> 1.0

    | ---- FMOVE.S FP0,(A0) ----
    .short  0xF210, 0x6400
    move.l  (%a0), %d1
    cmp.l   #0x3F800000, %d1
    bne     _f02

    | ---- FMOVE.D FP0,(A0) : 1.0 double = 0x3FF0000000000000 ----
    .short  0xF210, 0x7400             | fmt 101 (Double)
    move.l  (%a0), %d1
    cmp.l   #0x3FF00000, %d1
    bne     _f03
    move.l  4(%a0), %d1
    tst.l   %d1
    bne     _f03

    | ---- FMOVE.L FP0,(A0) : 1.0 -> integer 1 ----
    .short  0xF210, 0x6000             | fmt 000 (Long)
    move.l  (%a0), %d1
    cmp.l   #1, %d1
    bne     _f04

_pass:
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_f02: move.l #0xDEAD0F02, %d2
    bra _report
_f03: move.l #0xDEAD0F03, %d2
    bra _report
_f04: move.l #0xDEAD0F04, %d2
    bra _report
_fline: move.l #0xDEAD0F01, %d2
_report:
    lea     PASS_SENT, %a1
    move.l  %d2, (%a1)
_fhalt:
    bra     _fhalt

    .align 2
_scratch:
    .long   0, 0
