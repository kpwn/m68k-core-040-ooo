| add_mem_indirect_rmw.s -- ADD.{B,W,L} Dn,(An) read-modify-write
|
| Exercises the exact Q700 ROM frontier:
|   408810ec: d593  add.l %d2,(%a3)
| plus word and byte sibling forms.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | -- Exact ROM long form: ADD.L D2,(A3) ---------------------------
    lea     0x00114000, %a3
    move.l  #0x00000010, (%a3)
    moveq   #3, %d2
    .word   0xd593              | add.l %d2,(%a3)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a3), %d0
    cmp.l   #0x00000013, %d0
    bne     _fail

    | -- Word sibling: 0xffff + 1 => 0, carry and zero -----------------
    lea     0x00114020, %a1
    move.l  #0xffff0000, (%a1)
    moveq   #1, %d0
    add.w   %d0, (%a1)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcc     _fail
    move.w  (%a1), %d1
    cmpi.w  #0, %d1
    bne     _fail

    | -- Byte sibling: 0x7f + 1 => 0x80, negative and overflow ---------
    lea     0x00114040, %a0
    move.l  #0x7f000000, (%a0)
    moveq   #1, %d1
    add.b   %d1, (%a0)
    bpl     _fail
    beq     _fail
    bvc     _fail
    bcs     _fail
    move.b  (%a0), %d2
    cmpi.b  #0x80, %d2
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
