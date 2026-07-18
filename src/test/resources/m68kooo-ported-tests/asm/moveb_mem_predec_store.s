| moveb_mem_predec_store.s -- MOVE.B (An),-(Am)
|
| Covers the Q700 ROM SCC probe push:
|   1f13  move.b (%a3),-(%a7)

    .text
    .org 0

_start:
    lea     0x00106010, %a1
    lea     0x00106004, %a2

    move.l  #0x80015555, (%a1)
    move.l  #0xaaaaaaaa, -4(%a2)
    .word   0x1511              | move.b (%a1),-(%a2)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a2, %d0
    cmp.l   #0x00106003, %d0
    bne     _fail
    move.l  -3(%a2), %d1
    cmp.l   #0xaaaaaa80, %d1
    bne     _fail

    | Exact ROM opword.  Byte predecrement through A7 steps by 2.
    lea     0x00106030, %a3
    lea     0x00106024, %a7
    move.l  #0x11000000, (%a3)
    move.l  #0xaaaabbbb, -4(%a7)
    .word   0x1f13              | move.b (%a3),-(%a7)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a7, %d2
    cmp.l   #0x00106022, %d2
    bne     _fail
    move.l  -2(%a7), %d3
    cmp.l   #0xaaaa11bb, %d3
    bne     _fail

    | Absolute-word source through A7 keeps the byte-stack +2 rule.
    lea     0x00006050, %a0
    lea     0x00106070, %a7
    move.l  #0x80000000, (%a0)
    move.l  #0xaaaabbbb, -4(%a7)
    .word   0x1f38, 0x6050       | move.b 0x6050.w,-(%a7)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a7, %d4
    cmp.l   #0x0010606e, %d4
    bne     _fail
    move.b  (%a7), %d5
    cmp.b   #0x80, %d5
    bne     _fail

    | Absolute-long source through a non-A7 destination decrements by one.
    lea     0x00106080, %a0
    lea     0x00106090, %a2
    move.l  #0x00000000, (%a0)
    move.l  #0xaaaabbbb, -4(%a2)
    .word   0x1539, 0x0010, 0x6080 | move.b 0x00106080.l,-(%a2)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a2, %d6
    cmp.l   #0x0010608f, %d6
    bne     _fail
    move.b  (%a2), %d7
    cmp.b   #0x00, %d7
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
