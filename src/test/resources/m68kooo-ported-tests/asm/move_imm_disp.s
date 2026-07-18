| move_imm_disp.s -- MOVE.{B,W,L} #imm,(d16,An) memory destinations
|
| Exercises the exact Q700 ROM frontier:
|   4088159c: 397c 0001 ff66  move.w #1,-154(A4)
| plus byte and long siblings sharing the same extension ordering.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | -- Exact ROM word form: MOVE.W #1,-154(A4) ---------------------
    lea     0x001150c0, %a4
    .word   0x397c, 0x0001, 0xff66
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.w  -154(%a4), %d0
    cmpi.w  #0x0001, %d0
    bne     _fail

    | -- Byte sibling: immediate byte is ext1 low byte, d16 in ext2 ----
    lea     0x00115100, %a0
    move.b  #0x80, 7(%a0)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.b  7(%a0), %d1
    cmpi.b  #0x80, %d1
    bne     _fail

    | -- Long sibling: imm32 in ext1:ext2, d16 in ext3 ----------------
    lea     0x00115140, %a1
    move.l  #0x12345678, -12(%a1)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  -12(%a1), %d2
    cmp.l   #0x12345678, %d2
    bne     _fail

_pass:
    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a6)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a6
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a6)
_fail_halt:
    bra     _fail_halt
