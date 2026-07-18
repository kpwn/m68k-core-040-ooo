| movel_mem_predec_store.s -- MOVE.L (An),-(Am) and (d16,An),-(Am)
|
| Covers the Q700 ROM vector-hook setup instruction:
|   40847a9e: 2f15  move.l (%a5),-(%a7)

    .text
    .org 0

_start:
    lea     0x00107010, %a1
    lea     0x00107024, %a2

    move.l  #0x80015555, (%a1)
    move.l  #0xaaaaaaaa, -4(%a2)
    .word   0x2511              | move.l (%a1),-(%a2)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a2, %d0
    cmp.l   #0x00107020, %d0
    bne     _fail
    move.l  (%a2), %d1
    cmp.l   #0x80015555, %d1
    bne     _fail

    | Exact ROM opword.  Long predecrement through A7 steps by 4.
    lea     0x00107040, %a5
    lea     0x00107058, %a7
    move.l  #0x11223344, (%a5)
    move.l  #0xaaaaaaaa, -4(%a7)
    .word   0x2f15              | move.l (%a5),-(%a7)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a7, %d2
    cmp.l   #0x00107054, %d2
    bne     _fail
    move.l  (%a7), %d3
    cmp.l   #0x11223344, %d3
    bne     _fail

    | Displaced source widens the same crack shape.
    move.l  #0x00000000, 8(%a5)
    move.l  #0xaaaaaaaa, -4(%a7)
    .word   0x2f2d, 0x0008       | move.l 8(%a5),-(%a7)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a7, %d4
    cmp.l   #0x00107050, %d4
    bne     _fail
    move.l  (%a7), %d5
    bne     _fail

    | Absolute-word source: current ROM frontier at 0x4080013c.
    lea     0x00007000, %a0
    lea     0x00107070, %a7
    move.l  #0x00000000, (%a0)
    move.l  #0xaaaaaaaa, -4(%a7)
    .word   0x2f38, 0x7000       | move.l 0x7000.w,-(%a7)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a7, %d6
    cmp.l   #0x0010706c, %d6
    bne     _fail
    move.l  (%a7), %d7
    bne     _fail

    | Absolute-long source uses the same crack with a four-byte address.
    lea     0x00107080, %a0
    lea     0x001070a0, %a2
    move.l  #0x80000001, (%a0)
    move.l  #0xaaaaaaaa, -4(%a2)
    .word   0x2539, 0x0010, 0x7080 | move.l 0x00107080.l,-(%a2)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a2, %d0
    cmp.l   #0x0010709c, %d0
    bne     _fail
    move.l  (%a2), %d1
    cmp.l   #0x80000001, %d1
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
