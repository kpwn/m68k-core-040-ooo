| add_l_dn_idx_dst.s -- ADD.L Dn,(d8,An,Xn) brief-indexed mem dst RMW.
|
| Task #201 (A10b): V2 ALU reg-src brief-indexed-mem-dst.  Exercises
| scale x1 (long index) + scale x2 (long index) + scale x4 (word index).
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | ADD.L D3,(4,A1,D2.L*1) — scale x1, long index.
    lea     0x00124000, %a1
    move.l  #0x11111111, 8(%a1)          | memory seed at 4 + D2=4
    moveq   #4, %d2
    move.l  #0x22222222, %d3
    add.l   %d3, 4(%a1,%d2.l*1)
    move.l  8(%a1), %d0
    cmp.l   #0x33333333, %d0
    bne     _fail

    | ADD.L D3,(0,A2,D4.L*2) — scale x2, long index.
    lea     0x00124100, %a2
    move.l  #0x7fffffff, 8(%a2)
    moveq   #4, %d4                      | D4*2=8
    move.l  #0x00000001, %d3
    add.l   %d3, 0(%a2,%d4.l*2)
    bpl     _fail                         | result negative
    beq     _fail
    bvc     _fail                         | overflow
    bcs     _fail                         | no carry (signed overflow)
    move.l  8(%a2), %d0
    cmp.l   #0x80000000, %d0
    bne     _fail

    | ADD.L D5,(-8,A3,D6.W*4) — scale x4, word index, neg disp.
    lea     0x00124200, %a3
    move.l  #0x01020304, 0(%a3)          | target at A3-8+8=A3
    moveq   #2, %d6                      | D6*4=8, so ea=A3-8+8 = A3
    move.l  #0xfefdfcfc, %d5
    add.l   %d5, -8(%a3,%d6.w*4)
    move.l  0(%a3), %d0
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
