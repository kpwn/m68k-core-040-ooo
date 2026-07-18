| add_mem_postinc_rmw.s -- ADD.{B,W,L} Dn,(An)+ read-modify-write
|
| Exercises the exact Q700 ROM frontier:
|   4080cca0: d199  add.l %d0,(%a1)+
|   4080cca4: d19a  add.l %d0,(%a2)+
| plus word, byte, and A7 byte-step sibling forms.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | -- Exact ROM long form: ADD.L D0,(A1)+ -------------------------
    lea     0x00115000, %a1
    move.l  #0x00000010, (%a1)
    moveq   #7, %d0
    .word   0xd199              | add.l %d0,(%a1)+
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00115004, %a1
    bne     _fail
    move.l  -4(%a1), %d1
    cmp.l   #0x00000017, %d1
    bne     _fail

    | -- Exact ROM carry/zero form: ADD.L D0,(A2)+ -------------------
    lea     0x00115020, %a2
    move.l  #0xffffffff, (%a2)
    moveq   #1, %d0
    .word   0xd19a              | add.l %d0,(%a2)+
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcc     _fail
    cmpa.l  #0x00115024, %a2
    bne     _fail
    move.l  -4(%a2), %d2
    cmp.l   #0x00000000, %d2
    bne     _fail

    | -- Word sibling: 0xffff + 1 => 0, carry and zero ---------------
    lea     0x00115040, %a3
    move.l  #0xffff0000, (%a3)
    moveq   #1, %d1
    add.w   %d1, (%a3)+
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcc     _fail
    cmpa.l  #0x00115042, %a3
    bne     _fail
    move.w  -2(%a3), %d3
    cmpi.w  #0, %d3
    bne     _fail

    | -- Byte sibling: 0x7f + 1 => 0x80, negative and overflow --------
    lea     0x00115060, %a0
    move.l  #0x7f000000, (%a0)
    moveq   #1, %d2
    add.b   %d2, (%a0)+
    bpl     _fail
    beq     _fail
    bvc     _fail
    bcs     _fail
    cmpa.l  #0x00115061, %a0
    bne     _fail
    move.b  -1(%a0), %d4
    cmpi.b  #0x80, %d4
    bne     _fail

    | -- A7 byte postincrement steps by 2 -----------------------------
    lea     0x00115100, %a7
    move.l  #0x01000000, (%a7)
    moveq   #1, %d5
    add.b   %d5, (%a7)+
    beq     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a7, %d6
    cmp.l   #0x00115102, %d6
    bne     _fail
    move.b  -2(%a7), %d7
    cmpi.b  #0x02, %d7
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
_fail_halt:
    bra     _fail_halt
