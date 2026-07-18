| moveb_postinc_both.s -- MOVE.B (An)+,(Am)+
|
| Covers the Q700 ROM ASC byte-copy shape:
|   40807126: 12dc    move.b (%a4)+,(%a1)+
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00104000, %a4
    lea     0x00104100, %a1
    move.l  #0x807f1234, (%a4)
    move.l  #0xaaaaaaaa, (%a1)

    | Copy a negative byte and postincrement both address registers.
    move.b  (%a4)+, (%a1)+
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00104001, %a4
    bne     _fail
    cmpa.l  #0x00104101, %a1
    bne     _fail
    lea     0x00104100, %a0
    move.l  (%a0), %d0
    cmp.l   #0x80aaaaaa, %d0
    bne     _fail

    | Copy a positive byte through the same decoded instruction.
    move.b  (%a4)+, (%a1)+
    bmi     _fail
    beq     _fail
    cmpa.l  #0x00104002, %a4
    bne     _fail
    cmpa.l  #0x00104102, %a1
    bne     _fail
    lea     0x00104100, %a0
    move.l  (%a0), %d1
    cmp.l   #0x807faaaa, %d1
    bne     _fail

    | Source A7 byte postincrement advances by two.
    lea     0x00104200, %a7
    lea     0x00104300, %a0
    move.l  #0xff010203, (%a7)
    move.l  #0x55555555, (%a0)
    move.b  (%a7)+, (%a0)+
    bpl     _fail
    cmpa.l  #0x00104202, %a7
    bne     _fail
    cmpa.l  #0x00104301, %a0
    bne     _fail
    lea     0x00104300, %a2
    move.l  (%a2), %d2
    cmp.l   #0xff555555, %d2
    bne     _fail

    | Destination A7 byte postincrement also advances by two.
    lea     0x00104400, %a2
    lea     0x00104500, %a7
    move.l  #0x7e010203, (%a2)
    move.l  #0x66666666, (%a7)
    move.b  (%a2)+, (%a7)+
    bmi     _fail
    beq     _fail
    cmpa.l  #0x00104401, %a2
    bne     _fail
    cmpa.l  #0x00104502, %a7
    bne     _fail
    lea     0x00104500, %a3
    move.l  (%a3), %d3
    cmp.l   #0x7e666666, %d3
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
