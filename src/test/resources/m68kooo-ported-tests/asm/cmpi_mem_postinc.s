| cmpi_mem_postinc.s -- CMPI.{B,W,L} #imm,(An)+ memory operands
|
| Exercises the exact Q700 ROM frontier:
|   4088107e: 0c9a ffff ffff  cmpi.l #-1,(A2)+
| plus word and byte/A7 sibling forms.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | -- Exact ROM long form: CMPI.L #-1,(A2)+ -------------------------
    lea     0x00112000, %a2
    move.l  #0xffffffff, (%a2)
    .word   0x0c9a, 0xffff, 0xffff    | cmpi.l #-1,(%a2)+
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00112004, %a2
    bne     _fail
    move.l  -4(%a2), %d0
    cmp.l   #0xffffffff, %d0
    bne     _fail

    | -- Word sibling: equal compare, +2 writeback ---------------------
    lea     0x00112020, %a1
    move.w  #0x1234, (%a1)
    cmpi.w  #0x1234, (%a1)+
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00112022, %a1
    bne     _fail
    move.w  -2(%a1), %d0
    cmpi.w  #0x1234, %d0
    bne     _fail

    | -- Byte A7 sibling: byte stack-pointer postincrement is +2 -------
    lea     0x00112040, %a7
    move.b  #0x7f, (%a7)
    cmpi.b  #0x7f, (%a7)+
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00112042, %a7
    bne     _fail
    move.b  -2(%a7), %d0
    cmpi.b  #0x7f, %d0
    bne     _fail

    | -- Byte non-A7 mismatch: +1 writeback and borrow flags -----------
    lea     0x00112060, %a0
    clr.b   (%a0)
    cmpi.b  #0x10, (%a0)+
    bcc     _fail
    bpl     _fail
    beq     _fail
    cmpa.l  #0x00112061, %a0
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
