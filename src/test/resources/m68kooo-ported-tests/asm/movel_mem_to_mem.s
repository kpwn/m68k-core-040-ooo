| movel_mem_to_mem.s -- MOVE.L memory-to-memory forms
|
| Covers the Q700 ROM diagnostic instruction:
|   40847698: 2092    move.l (%a2),(%a0)
| and the later indexed destination frontier:
|   40887a26: 2391 0000  move.l (%a1),(0,%a1,%d0.w)

    .text
    .org 0

_start:
    | Copy a negative longword through plain address-register indirect EAs.
    lea     0x00104000, %a2
    lea     0x00104100, %a0
    move.l  #0x80000004, (%a2)
    move.l  #0xaaaaaaaa, (%a0)
    .word   0x2092              | move.l (%a2),(%a0)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00104000, %a2
    bne     _fail
    cmpa.l  #0x00104100, %a0
    bne     _fail
    move.l  (%a0), %d0
    cmp.l   #0x80000004, %d0
    bne     _fail

    | The ROM immediately follows with MOVE.L (A2),4(A0).
    move.l  #0x13579bdf, (%a2)
    move.l  #0xaaaaaaaa, 4(%a0)
    .word   0x2152, 0x0004       | move.l (%a2),4(%a0)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00104000, %a2
    bne     _fail
    cmpa.l  #0x00104100, %a0
    bne     _fail
    move.l  4(%a0), %d0
    cmp.l   #0x13579bdf, %d0
    bne     _fail

    | The ROM frame setup later uses MOVE.L -4(A2),30(A5).
    lea     0x00104200, %a5
    move.l  #0x2468ace0, -4(%a2)
    move.l  #0xaaaaaaaa, 30(%a5)
    .word   0x2b6a, 0xfffc, 0x001e | move.l -4(%a2),30(%a5)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00104000, %a2
    bne     _fail
    cmpa.l  #0x00104200, %a5
    bne     _fail
    move.l  30(%a5), %d0
    cmp.l   #0x2468ace0, %d0
    bne     _fail

    | A zero source must set Z and clear N/V/C without changing X.
    move.l  #0x00000000, (%a2)
    .word   0x2092              | move.l (%a2),(%a0)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a0), %d1
    bne     _fail

    | Source (An) to brief-indexed destination, exact ROM shape.
    lea     0x00104300, %a1
    move.l  #0x80000005, (%a1)
    move.l  #0xaaaaaaaa, 8(%a1)
    moveq   #8, %d0
    .word   0x2391, 0x0000       | move.l (%a1),(0,%a1,%d0.w)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  8(%a1), %d1
    cmp.l   #0x80000005, %d1
    bne     _fail

    | Same family with a long index and scale x2.
    lea     0x00104400, %a2
    lea     0x00104500, %a3
    move.l  #0x12345678, (%a2)
    move.l  #0xaaaaaaaa, 8(%a3)
    moveq   #2, %d1
    .word   0x2792, 0x1a04       | move.l (%a2),(4,%a3,%d1.l*2)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  8(%a3), %d0
    cmp.l   #0x12345678, %d0
    bne     _fail

_pass:
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, 0xFFFF0000
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, 0xFFFF0000
_fail_halt:
    bra     _fail_halt
