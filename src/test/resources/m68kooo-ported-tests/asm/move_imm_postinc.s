| move_imm_postinc.s -- MOVE.{B,W,L} #imm,(An)+ memory destination
|
| Covers the Q700 ROM frontier:
|   4088140a: 32fc 0001  move.w #1,(A1)+
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Exact ROM word form: immediate in ext1, A1 postincrement by 2.
    lea     0x00150000, %a1
    move.l  #0xffffffff, (%a1)
    .word   0x32fc, 0x0001      | move.w #1,(%a1)+
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00150002, %a1
    bne     _fail
    move.l  0x00150000, %d0
    cmp.l   #0x0001ffff, %d0
    bne     _fail

    | Long sibling: immediate in ext1:ext2, A2 postincrement by 4.
    lea     0x00150100, %a2
    move.l  #0x00000000, (%a2)
    move.l  #0x89abcdef, (%a2)+
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00150104, %a2
    bne     _fail
    move.l  0x00150100, %d0
    cmp.l   #0x89abcdef, %d0
    bne     _fail

    | Byte sibling: A7 byte postincrement advances by 2.
    lea     0x00150200, %a7
    move.l  #0xff556677, (%a7)
    move.b  #0, (%a7)+
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00150202, %a7
    bne     _fail
    move.l  0x00150200, %d0
    cmp.l   #0x00556677, %d0
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
    bra     _pass

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
    bra     _fail
