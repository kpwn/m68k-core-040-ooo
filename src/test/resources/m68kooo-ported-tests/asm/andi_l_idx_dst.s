| andi_l_idx_dst.s -- ANDI.L #imm,(d8,An,Xn) brief-indexed mem dst RMW.
|
| Task #201 (A10b): V2 ALU imm-src brief-indexed-mem-dst.  Exercises
| ANDI masking memory through an indexed EA, plus ADDI/ORI confirm.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | ANDI.L #0xFF,(4,A1,D2.L*1) — mask low byte.
    lea     0x00124000, %a1
    move.l  #0xdeadbeef, 8(%a1)
    moveq   #4, %d2
    andi.l  #0xff, 4(%a1,%d2.l*1)
    move.l  8(%a1), %d0
    cmp.l   #0x000000ef, %d0
    bne     _fail

    | ADDI.L #1,(0,A2,D4.L*2) — +1 with scale x2.
    lea     0x00124100, %a2
    move.l  #0x7ffffffe, 12(%a2)           | D4=6 so D4*2=12, ea=A2+12
    moveq   #6, %d4
    addi.l  #1, 0(%a2,%d4.l*2)
    move.l  12(%a2), %d0
    cmp.l   #0x7fffffff, %d0
    bne     _fail

    | ORI.L #0xFF000000,(4,A3,D5.W*1) — set high byte.
    lea     0x00124200, %a3
    move.l  #0x00aabbcc, 8(%a3)
    moveq   #4, %d5
    ori.l   #0xff000000, 4(%a3,%d5.w*1)
    move.l  8(%a3), %d0
    cmp.l   #0xffaabbcc, %d0
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
