| movea_l_d8_an_xn.s -- MOVEA.L (d8,An,Xn) brief-indexed src.
|
| Task #203 (B2): V2 migration of MOVEA with brief-indexed source.
| MOVEA.L copies 32-bit long directly into An (no sign-extend).
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    | MOVEA.L (d8,An,Xn.W*4), An
    | Set up an array of 32-bit longs at A0 base.
    lea     0x00010000, %a0
    move.l  #0x11223344, 12(%a0)           | store long at A0+12.
    moveq   #2, %d1                        | index D1.W = 2, scale=4 → +8
    moveq   #4, %d6                        | pre-state D6
    cmp.l   %d6, %d6                       | Z=1 baseline
    movea.l (4,%a0,%d1.w*4), %a2           | target = A0 + 4 + 8 = A0 + 12
    bne     _fail                          | CCR still Z=1 (MOVEA preserves)
    cmpa.l  #0x11223344, %a2
    bne     _fail

    | MOVEA.L (d8,An,An.L*1), An — long-index variant on another An.
    lea     0x00011000, %a3
    move.l  #0xC0DE1234, 8(%a3)
    move.l  #8, %a4                        | A4.L = 8, scale=0 → +8
    moveq   #7, %d6
    cmp.l   %d6, %d6
    movea.l (0,%a3,%a4.l), %a5             | target = A3 + 0 + 8 = A3 + 8
    bne     _fail
    cmpa.l  #0xC0DE1234, %a5
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d6
    move.l  %d6, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d6
    move.l  %d6, (%a0)
_halt_fail:
    bra     _halt_fail
