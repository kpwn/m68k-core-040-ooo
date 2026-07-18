| tst_l_idx.s -- TST.L (d8,An,Xn) brief-indexed mem dst.
|
| Task #201 (A10b): V2 unary indexed-mem-dst TST (LOAD-INT no STORE).
| Exercises positive, zero, and negative mem values.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | TST.L (4,A1,D2.L*1) on positive value: N=0, Z=0.
    lea     0x00124000, %a1
    move.l  #0x12345678, 8(%a1)
    moveq   #4, %d2
    tst.l   4(%a1,%d2.l*1)
    beq     _fail                             | not zero
    bmi     _fail                             | not negative

    | TST.L (0,A2,D4.W*2) on zero value: Z=1.
    lea     0x00124100, %a2
    move.l  #0x00000000, 16(%a2)
    moveq   #8, %d4                          | D4*2=16
    tst.l   0(%a2,%d4.w*2)
    bne     _fail                             | should be zero
    bmi     _fail

    | TST.L (4,A3,D5.L*1) on negative value: N=1.
    lea     0x00124200, %a3
    move.l  #0xff000000, 8(%a3)
    moveq   #4, %d5
    tst.l   4(%a3,%d5.l*1)
    bpl     _fail                             | should be negative
    beq     _fail

    | Memory must be unchanged by TST.
    move.l  8(%a3), %d0
    cmp.l   #0xff000000, %d0
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
