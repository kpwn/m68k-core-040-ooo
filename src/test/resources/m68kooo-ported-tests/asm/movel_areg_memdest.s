| movel_areg_memdest.s -- MOVE.L An,<memory destination>
|
| Covers the Q700 ROM RAM-sizing helper instruction:
|   0x2ec8  move.l a0,(a7)+

    .text
    .org 0

_start:
    | Exact ROM blocker: MOVE.L A0,(A7)+ stores the address-register
    | value, updates NZVC from that value, and post-increments A7 by 4.
    lea     0x00200000, %a7
    movea.l #0x00000000, %a0
    .word   0x2ec8              | move.l %a0,(%a7)+
    bne     _fail               | Z must be set for zero data
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a7, %d2
    cmp.l   #0x00200004, %d2
    bne     _fail
    move.l  0x00200000, %d1
    bne     _fail

    | Same exact encoding, but with a negative long value in A0.
    lea     0x00200010, %a7
    movea.l #0x80000004, %a0
    .word   0x2ec8              | move.l %a0,(%a7)+
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a7, %d2
    cmp.l   #0x00200014, %d2
    bne     _fail
    move.l  0x00200010, %d1
    cmp.l   #0x80000004, %d1
    bne     _fail

    | Also cover the non-postincrement memory-destination form that uses
    | the same address-register source plumbing.
    lea     0x00200020, %a1
    movea.l #0x00001234, %a0
    .word   0x2288              | move.l %a0,(%a1)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a1), %d1
    cmp.l   #0x00001234, %d1
    bne     _fail

    move.l  #0xC0FFEE00, %d0
    move.l  %d0, 0xFFFF0000
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, 0xFFFF0000
_fail_halt:
    bra     _fail_halt
