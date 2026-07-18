| move_predec_to_postinc.s -- MOVE.{B,W,L} -(An),(Am)+
|
| Covers the Q700 ROM frontier:
|   4080ca28: 22e0  move.l -(A0),(A1)+
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Exact long frontier shape.  Source predecrements before the load;
    | destination postincrements after the store.
    lea     0x00108004, %a0
    lea     0x00108100, %a1
    move.l  #0x12345678, -4(%a0)
    move.l  #0xaaaaaaaa, (%a1)
    .word   0x22e0              | move.l -(A0),(A1)+
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00108000, %a0
    bne     _fail
    cmpa.l  #0x00108104, %a1
    bne     _fail
    move.l  -4(%a1), %d0
    cmp.l   #0x12345678, %d0
    bne     _fail

    | Word sibling, including zero flag behavior.
    lea     0x00108202, %a2
    lea     0x00108300, %a3
    move.l  #0x0000ccdd, -2(%a2)
    move.l  #0x11223344, (%a3)
    .word   0x36e2              | move.w -(A2),(A3)+
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00108200, %a2
    bne     _fail
    cmpa.l  #0x00108302, %a3
    bne     _fail
    move.l  -2(%a3), %d1
    cmp.l   #0x00003344, %d1
    bne     _fail

    | Byte source A7 predecrement uses the architectural two-byte step.
    lea     0x00108402, %a7
    lea     0x00108500, %a4
    move.l  #0x80445566, -2(%a7)
    move.l  #0x11223344, (%a4)
    .word   0x18e7              | move.b -(A7),(A4)+
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00108400, %a7
    bne     _fail
    cmpa.l  #0x00108501, %a4
    bne     _fail
    move.l  -1(%a4), %d2
    cmp.l   #0x80223344, %d2
    bne     _fail

    | Byte destination A7 postincrement also uses a two-byte step.
    lea     0x00108601, %a0
    lea     0x00108700, %a7
    move.l  #0x7f445566, -1(%a0)
    move.l  #0x11223344, (%a7)
    .word   0x1ee0              | move.b -(A0),(A7)+
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00108600, %a0
    bne     _fail
    cmpa.l  #0x00108702, %a7
    bne     _fail
    move.l  -2(%a7), %d3
    cmp.l   #0x7f223344, %d3
    bne     _fail

    | Same-register byte A7 uses source predecrement before destination
    | postincrement, so the final A7 value returns to its original address.
    lea     0x00108802, %a7
    move.l  #0x81112233, -2(%a7)
    .word   0x1ee7              | move.b -(A7),(A7)+
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00108802, %a7
    bne     _fail
    move.l  -2(%a7), %d4
    cmp.l   #0x81112233, %d4
    bne     _fail

    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a6)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a6
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a6)
_fail_halt:
    bra     _fail_halt
