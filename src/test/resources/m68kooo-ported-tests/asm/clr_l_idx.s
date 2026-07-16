| clr_l_idx.s -- CLR.L (d8,An,Xn) brief-indexed mem dst.
|
| Task #201 (A10b): V2 unary indexed-mem-dst.  Exercises CLR writing
| literal zero through an indexed EA with scales x1 / x2 / x4.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | CLR.L (4,A1,D2.L*1)
    lea     0x00124000, %a1
    move.l  #0xdeadbeef, 8(%a1)
    moveq   #4, %d2
    clr.l   4(%a1,%d2.l*1)
    move.l  8(%a1), %d0
    cmp.l   #0x00000000, %d0
    bne     _fail

    | CLR.L (0,A2,D4.L*2) — scale x2.
    lea     0x00124100, %a2
    move.l  #0x11223344, 16(%a2)
    moveq   #8, %d4                          | D4*2=16
    clr.l   0(%a2,%d4.l*2)
    move.l  16(%a2), %d0
    cmp.l   #0x00000000, %d0
    bne     _fail

    | CLR.L (-4,A3,D6.W*4) — scale x4, word index, neg disp.
    lea     0x00124200, %a3
    move.l  #0xffffffff, 12(%a3)            | D6=4, D6*4=16, ea=A3-4+16=A3+12
    moveq   #4, %d6
    clr.l   -4(%a3,%d6.w*4)
    move.l  12(%a3), %d0
    cmp.l   #0x00000000, %d0
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
