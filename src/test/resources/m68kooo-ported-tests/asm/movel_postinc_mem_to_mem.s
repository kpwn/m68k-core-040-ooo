| movel_postinc_mem_to_mem.s -- MOVE.L (An)+,(Am)+
|
| Covers the Q700 ROM table-copy helper:
|   24d9  move.l (%a1)+,(%a2)+

    .text
    .org 0

_start:
    lea     0x00103000, %a1
    lea     0x00103100, %a2

    move.l  #0x11223344, (%a1)
    move.l  #0xaaaaaaaa, (%a2)
    .word   0x24d9              | move.l (%a1)+,(%a2)+
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail

    move.l  -4(%a2), %d0
    cmp.l   #0x11223344, %d0
    bne     _fail
    move.l  %a1, %d1
    cmp.l   #0x00103004, %d1
    bne     _fail
    move.l  %a2, %d2
    cmp.l   #0x00103104, %d2
    bne     _fail

    | Negative source verifies MOVE.L flags come from the copied value.
    move.l  #0x80000000, (%a1)
    .word   0x24d9              | move.l (%a1)+,(%a2)+
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  -4(%a2), %d3
    cmp.l   #0x80000000, %d3
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
