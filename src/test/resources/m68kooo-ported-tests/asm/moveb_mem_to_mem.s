| moveb_mem_to_mem.s -- MOVE.B (An),(Am)
|
| Covers the Q700 ROM probe no-op:
|   1e97  move.b (%a7),(%a7)

    .text
    .org 0

_start:
    lea     0x00107000, %a1
    lea     0x00107010, %a2

    move.l  #0x80112233, (%a1)
    move.l  #0xaabbccdd, (%a2)
    .word   0x1491              | move.b (%a1),(%a2)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a2), %d0
    cmp.l   #0x80bbccdd, %d0
    bne     _fail

    | Exact ROM opword, using A7 as both source and destination.
    lea     0x00107020, %a7
    move.l  #0x01015555, (%a7)
    .word   0x1e97              | move.b (%a7),(%a7)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a7), %d1
    cmp.l   #0x01015555, %d1
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
