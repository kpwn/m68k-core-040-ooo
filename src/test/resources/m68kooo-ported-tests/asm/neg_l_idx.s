| neg_l_idx.s -- NEG.L (d8,An,Xn) brief-indexed mem dst.
|
| Task #201 (A10b): V2 unary indexed-mem-dst NEG/NEGX/NOT (LOAD-INT-
| STORE crack).  Covers scale x1 / x2 with long and word indexes.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | NEG.L (4,A1,D2.L*1) — negate non-zero: sets NC, clears Z, X=C.
    lea     0x00124000, %a1
    move.l  #0x00000001, 8(%a1)
    moveq   #4, %d2
    neg.l   4(%a1,%d2.l*1)
    move.l  8(%a1), %d0
    cmp.l   #0xffffffff, %d0
    bne     _fail

    | NEG.L (0,A2,D4.W*2) — negate a large neg value → positive.
    lea     0x00124100, %a2
    move.l  #0x80000001, 16(%a2)          | D4=8, D4*2=16
    moveq   #8, %d4
    neg.l   0(%a2,%d4.w*2)
    move.l  16(%a2), %d0
    cmp.l   #0x7fffffff, %d0
    bne     _fail

    | NOT.L (4,A3,D5.L*1) — flip all bits.
    lea     0x00124200, %a3
    move.l  #0x12345678, 8(%a3)
    moveq   #4, %d5
    not.l   4(%a3,%d5.l*1)
    move.l  8(%a3), %d0
    cmp.l   #0xedcba987, %d0
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
