| sub_mem_disp_rmw.s -- SUB.{B,W,L} Dn,(d16,An) read-modify-write
|
| Exercises the exact Q700 ROM frontier:
|   408810e8: 95ab 0004  sub.l D2,4(A3)
| plus word and byte sibling forms.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | -- Exact ROM long form: SUB.L D2,4(A3) --------------------------
    lea     0x00113000, %a3
    move.l  #0x00000010, 4(%a3)
    moveq   #3, %d2
    .word   0x95ab, 0x0004        | sub.l %d2,4(%a3)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  4(%a3), %d0
    cmp.l   #0x0000000d, %d0
    bne     _fail

    | -- Word sibling: 1 - 2 => 0xffff, borrow and negative -----------
    lea     0x00113020, %a1
    move.l  #0x00000001, 4(%a1)
    moveq   #2, %d0
    sub.w   %d0, 6(%a1)
    bcc     _fail
    bpl     _fail
    beq     _fail
    move.w  6(%a1), %d1
    cmpi.w  #0xffff, %d1
    bne     _fail

    | -- Byte sibling: 5 - 5 => zero -------------------------------
    lea     0x00113040, %a0
    move.l  #5, 8(%a0)
    moveq   #5, %d1
    sub.b   %d1, 11(%a0)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.b  11(%a0), %d2
    cmpi.b  #0, %d2
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
