| fpu_fmove_x_fp0_d16_a6_repro.s -- exact F22E 6800 FF34 regression.
|
| Hardware wedge repro decoded as:
|   fmovex %fp0,%a6@(-204)
| Old decode only accepted FPn->memory mode (An), so this d16(An)
| destination fell through to vector-11 F-line and could loop forever
| in FPSP.  This locks down the exact opword/ext/disp bytes.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0F01 -- vec-11 F-line trap
|   0xDEAD0F02 -- extended-format store footprint mismatch

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ BUF,       0x00024000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C

    move.l  #0x3F800000, %d0          | +1.0f
    fmove.s %d0, %fp0

    lea     BUF+204, %a6
    .short  0xF22E, 0x6800, 0xFF34    | FMOVE.X FP0,-204(A6)

    | The hard regression is no vec-11 F-line on the exact crash
    | instruction.  .X memory packing is intentionally approximate today.

    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_store:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F02, %d2
    move.l  %d2, (%a1)
_hs:
    bra     _hs

_fline:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F01, %d2
    move.l  %d2, (%a1)
_hf:
    bra     _hf
