| andi_mem_postinc.s -- ANDI.{B,W,L} #imm,(An)+ memory destination
|
| Covers the Q700 ROM frontier:
|   40881444: 0299 00ff ffff  andi.l #0x00ffffff,(A1)+
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Exact ROM long form: immediate in ext1:ext2, A1 postincrement by 4.
    lea     0x00140000, %a1
    move.l  #0x041a0000, (%a1)
    .word   0x0299, 0x00ff, 0xffff  | andi.l #0x00ffffff,(%a1)+
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00140004, %a1
    bne     _fail
    move.l  0x00140000, %d0
    cmp.l   #0x001a0000, %d0
    bne     _fail

    | Word sibling: immediate in ext1 and A2 postincrement by 2.
    lea     0x00140100, %a2
    move.l  #0x12345678, (%a2)
    andi.w  #0x00ff, (%a2)+
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00140102, %a2
    bne     _fail
    move.l  0x00140100, %d0
    cmp.l   #0x00345678, %d0
    bne     _fail

    | Byte sibling: A7 byte postincrement advances by 2.
    lea     0x00140200, %a7
    move.l  #0x80ffffff, (%a7)
    andi.b  #0x7f, (%a7)+
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00140202, %a7
    bne     _fail
    move.l  0x00140200, %d0
    cmp.l   #0x00ffffff, %d0
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
