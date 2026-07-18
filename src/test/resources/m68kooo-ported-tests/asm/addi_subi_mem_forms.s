| addi_subi_mem_forms.s -- ADDI/SUBI #imm,<ea> memory-destination forms
|
| Covers the group-0 arithmetic immediate memory RMW slice:
|   (An), (An)+, (d16,An), (xxx).W, (xxx).L
|
| Includes the Q700 ROM frontier shape:
|   06a8 ffff ffed 000c  addi.l #-19,12(A0)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | ADDI.L (d16,An): exact ROM frontier encoding.
    lea     0x00106000, %a0
    move.l  #0x00000020, 12(%a0)
    .word   0x06a8, 0xffff, 0xffed, 0x000c
    bmi     _fail1
    beq     _fail1
    bvs     _fail1
    move.l  0x0010600c, %d0
    cmp.l   #0x0000000d, %d0
    bne     _fail1

    | ADDI.B (An)+: byte RMW plus normal An postincrement.
    lea     0x00106100, %a1
    move.l  #0x7e223344, (%a1)
    addi.b  #1, (%a1)+
    bmi     _fail2
    beq     _fail2
    bvs     _fail2
    bcs     _fail2
    cmpa.l  #0x00106101, %a1
    bne     _fail2
    move.l  0x00106100, %d1
    cmp.l   #0x7f223344, %d1
    bne     _fail2

    | SUBI.W (An): word-sized memory RMW.
    lea     0x00106200, %a2
    move.l  #0x12340000, (%a2)
    subi.w  #0x20, (%a2)
    bmi     _fail3
    beq     _fail3
    bvs     _fail3
    bcs     _fail3
    move.l  0x00106200, %d2
    cmp.l   #0x12140000, %d2
    bne     _fail3

    | SUBI.L (xxx).W: absolute-short destination in low RAM.
    move.l  #1, 0x0300.w
    subi.l  #2, 0x0300.w
    bpl     _fail4
    beq     _fail4
    bvs     _fail4
    bcc     _fail4
    move.l  0x0300.w, %d3
    cmp.l   #0xffffffff, %d3
    bne     _fail4

    | ADDI.B (xxx).L: absolute-long byte destination.
    move.l  #0x10000000, 0x00106300.l
    addi.b  #0x22, 0x00106300.l
    bmi     _fail5
    beq     _fail5
    bvs     _fail5
    bcs     _fail5
    move.l  0x00106300, %d4
    cmp.l   #0x32000000, %d4
    bne     _fail5

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d6
    move.l  %d6, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d6
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d6
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d6
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d6
    bra     _fail
_fail5:
    move.l  #0xDEAD0005, %d6

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d6, (%a0)
_halt_fail:
    bra     _halt_fail
