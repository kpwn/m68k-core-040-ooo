| movew_mem_to_mem.s -- MOVE.W (An),(Am)
|
| Covers the Q700 ROM probe no-op:
|   3e97  move.w (%a7),(%a7)

    .text
    .org 0

_start:
    lea     0x00105000, %a1
    lea     0x00105010, %a2

    move.l  #0x11223344, (%a1)
    move.l  #0xaaaabbbb, (%a2)
    .word   0x3491              | move.w (%a1),(%a2)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a2), %d0
    cmp.l   #0x1122bbbb, %d0
    bne     _fail

    | Exact ROM opword, using A7 as both source and destination.
    lea     0x00105020, %a7
    move.l  #0x80015555, (%a7)
    .word   0x3e97              | move.w (%a7),(%a7)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a7), %d1
    cmp.l   #0x80015555, %d1
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
