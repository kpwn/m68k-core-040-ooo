| tst_mem_postinc.s -- TST.{B,W,L} on (An)+ memory operands
|
| Covers the Q700 ROM shape:
|   4a98    tst.l (%a0)+
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Exact ROM long postincrement shape, negative operand.
    lea     0x00102000, %a0
    move.l  #0x80000000, (%a0)
    .word   0x4a98                | tst.l (%a0)+
    bpl     _fail1
    beq     _fail1
    bvs     _fail1
    bcs     _fail1
    cmpa.l  #0x00102004, %a0
    bne     _fail1
    move.l  -4(%a0), %d0
    cmp.l   #0x80000000, %d0
    bne     _fail1

    | Word sibling, zero operand.
    lea     0x00102100, %a1
    move.l  #0x0000BEEF, (%a1)
    tst.w   (%a1)+
    bne     _fail2
    bmi     _fail2
    bvs     _fail2
    bcs     _fail2
    cmpa.l  #0x00102102, %a1
    bne     _fail2
    move.l  -2(%a1), %d0
    cmp.l   #0x0000BEEF, %d0
    bne     _fail2

    | Byte sibling on A7 uses the architectural +2 byte postincrement.
    lea     0x00102200, %a7
    move.l  #0x7F112233, (%a7)
    tst.b   (%a7)+
    bmi     _fail3
    beq     _fail3
    bvs     _fail3
    bcs     _fail3
    cmpa.l  #0x00102202, %a7
    bne     _fail3
    move.l  -2(%a7), %d0
    cmp.l   #0x7F112233, %d0
    bne     _fail3

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d1
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d1
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d1

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d1, (%a0)
_fail_halt:
    bra     _fail_halt
