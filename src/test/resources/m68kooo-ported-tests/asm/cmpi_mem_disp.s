| cmpi_mem_disp.s -- CMPI.{B,W,L} #imm,(d16,An) memory operands
|
| Covers the Q700 ROM frontier:
|   40880d60: 0c2e 0001 ffe6  cmpi.b #1,-26(A6)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | -- Exact ROM byte form: CMPI.B #1,-26(A6) ------------------------
    lea     0x00110020, %a6
    move.l  #0x01020304, -26(%a6)
    .word   0x0c2e, 0x0001, 0xffe6  | cmpi.b #1,-26(%a6)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  -26(%a6), %d0
    cmp.l   #0x01020304, %d0
    bne     _fail

    | Byte mismatch: memory 2 minus immediate 3 should be negative/borrow.
    .word   0x0c2e, 0x0003, 0xffe7  | cmpi.b #3,-25(%a6)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcc     _fail

    | -- Word sibling: immediate in ext1, displacement in ext2. ---------
    lea     0x00110100, %a1
    move.l  #0x12345678, 4(%a1)
    .word   0x0c69, 0x1234, 0x0004  | cmpi.w #0x1234,4(%a1)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail

    | -- Long sibling: immediate in ext1:ext2, displacement in ext3. -----
    lea     0x00110200, %a2
    move.l  #0x11223344, 8(%a2)
    .word   0x0caa, 0x1122, 0x3344, 0x0008  | cmpi.l #0x11223344,8(%a2)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a0)
    bra     _pass

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d1
    move.l  %d1, (%a0)
    bra     _fail
