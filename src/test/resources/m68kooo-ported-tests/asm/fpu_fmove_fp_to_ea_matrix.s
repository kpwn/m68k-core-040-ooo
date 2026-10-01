| fpu_fmove_fp_to_ea_matrix.s -- FPn->EA destination matrix smoke.
|
| Exercises the matrix axes added for FMOVE.<fmt> FPn,<ea>: memory
| destination EAs ((An), (An)+, -(An), d16(An), d8(An,Xn), abs.W,
| abs.L), integer Dn destinations for valid integer/single formats,
| and representative native .L/.S/.X/.W/.D/.B format footprints.
| Packed .P must take the vector-11 FPSP assist without storing data.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0F01 -- unexpected or malformed vec-11 F-line trap
|   0xDEAD0F31 -- data/footprint mismatch
|   0xDEAD0F32 -- postincrement/predecrement byte count wrong
|   0xDEAD0F33 -- indexed destination clobbered base/index

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ BUF,       0x00025000
    .equ ABSW,      0x00003000
    .equ ABSL,      0x00026000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C

    move.l  #0x3F800000, %d0          | +1.0f, exact single
    fmove.s %d0, %fp0

    | Dn direct valid forms: .L/.W/.B/.S.  Numeric exactness for .W/.B
    | register merge is not asserted here; the important regression is
    | that the valid direct encodings decode instead of trapping.
    fmove.l %fp0, %d1
    fmove.w %fp0, %d2
    fmove.b %fp0, %d3
    fmove.s %fp0, %d4

    lea     BUF, %a0
    fmove.l %fp0, (%a0)               | (An), .L

    lea     BUF+16, %a1
    fmove.s %fp0, (%a1)+              | (An)+, .S, +4
    cmpa.l  #(BUF+20), %a1
    bne     _fail_update
    move.l  BUF+16, %d5
    cmp.l   #0x3F800000, %d5
    bne     _fail_data

    lea     BUF+44, %a2
    fmove.x %fp0, -(%a2)              | -(An), .X, -12
    cmpa.l  #(BUF+32), %a2
    bne     _fail_update

    lea     BUF, %a3
    fmove.w %fp0, 64(%a3)             | d16(An), .W

    lea     BUF, %a4
    moveq   #8, %d6
    fmove.d %fp0, (80,%a4,%d6.w)      | d8(An,Xn), .D, scale 1
    cmpa.l  #BUF, %a4
    bne     _fail_index
    cmp.l   #8, %d6
    bne     _fail_index

    fmove.b %fp0, ABSW:w              | abs.W, .B

    fmove.s %fp0, ABSL:l              | native abs.L destination
    cmp.l   #0x3F800000, ABSL
    bne     _fail_data
    move.l  #0x11223344, ABSL
    move.l  #0x55667788, ABSL+4
    move.l  #0x99AABBCC, ABSL+8
    moveq   #0, %d7
_packed:
    fmove.p %fp0, ABSL:l{#0}          | unsupported packed static-k: FPSP
_after_packed:
    cmp.l   #1, %d7
    bne     _fail_fline
    cmp.l   #0x11223344, ABSL
    bne     _fail_data
    cmp.l   #0x55667788, ABSL+4
    bne     _fail_data
    cmp.l   #0x99AABBCC, ABSL+8
    bne     _fail_data

    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_data:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F31, %d2
    move.l  %d2, (%a1)
_hd:
    bra     _hd

_fail_update:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F32, %d2
    move.l  %d2, (%a1)
_hu:
    bra     _hu

_fail_index:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F33, %d2
    move.l  %d2, (%a1)
_hi:
    bra     _hi

_fline:
    | Format-2 frame: next PC, vector offset, then faulting instruction PC.
    | All earlier native forms must still execute without taking this path.
    cmp.w   #0x202C, 6(%a7)
    bne     _fail_fline
    cmp.l   #_after_packed, 2(%a7)
    bne     _fail_fline
    cmp.l   #_packed, 8(%a7)
    bne     _fail_fline
    tst.l   %d7
    bne     _fail_fline
    moveq   #1, %d7
    rte
_fail_fline:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F01, %d2
    move.l  %d2, (%a1)
_hf:
    bra     _hf
