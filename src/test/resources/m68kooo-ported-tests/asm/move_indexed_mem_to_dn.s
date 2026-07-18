| move_indexed_mem_to_dn.s -- MOVE.{B,W,L} (d8,An,Xn),Dn
|
| Covers the Q700 ROM shape:
|   1a31 3000    move.b 0(%a1,%d3.w),%d5
|   2231 0400    move.l 0(%a1,%d0.w*4),%d1
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    move.l  #0x00102000, %a1
    move.l  #0x00000010, %d3

    | Exact ROM byte indexed source, D3.W, disp 0.
    move.l  #0xAB001234, 0x00102010
    move.l  #0x12345678, %d5
    .word   0x1a31, 0x3000
    bpl     _fail1
    beq     _fail1
    cmp.l   #0x123456AB, %d5
    bne     _fail1

    | Word sibling with word index preserves Dn[31:16] and sets N.
    move.l  #0x8001C0DE, 0x00102014
    move.l  #0xCAFE0000, %d6
    move.w  4(%a1,%d3.w), %d6
    bpl     _fail2
    beq     _fail2
    cmp.l   #0xCAFE8001, %d6
    bne     _fail2

    | Long sibling with word index loads directly into Dn.
    move.l  #0x7FFFFFFF, 0x00102018
    moveq   #0, %d7
    move.l  8(%a1,%d3.w), %d7
    bmi     _fail3
    beq     _fail3
    cmp.l   #0x7FFFFFFF, %d7
    bne     _fail3

    | Exact ROM long indexed source, D0.W, scale x4.
    move.l  #0x13579BDF, 8(%a1)
    moveq   #2, %d0
    moveq   #0, %d1
    .word   0x2231, 0x0400
    bmi     _fail6
    beq     _fail6
    cmp.l   #0x13579BDF, %d1
    bne     _fail6

    | Word sibling with long index and scale x2.
    moveq   #3, %d2
    move.l  #0xBEEFCAFE, 8(%a1)
    move.l  #0xFEED0000, %d4
    move.w  2(%a1,%d2.l*2), %d4
    bpl     _fail7
    beq     _fail7
    cmp.l   #0xFEEDBEEF, %d4
    bne     _fail7

    | Byte sibling with word index and scale x8.
    moveq   #1, %d2
    move.l  #0x7F000000, 8(%a1)
    move.l  #0x89ABCD12, %d4
    move.b  0(%a1,%d2.w*8), %d4
    bmi     _fail8
    beq     _fail8
    cmp.l   #0x89ABCD7F, %d4
    bne     _fail8

    | Long-index sibling uses Xn.L without sign extension.
    move.l  #0x00000030, %d4
    move.l  #0xCAFE55AA, 0x0010202c
    moveq   #0, %d2
    move.w  -4(%a1,%d4.l), %d2
    bpl     _fail4
    beq     _fail4
    cmp.l   #0x0000CAFE, %d2
    bne     _fail4

    | Zero byte result updates Z and preserves upper Dn bytes.
    move.l  #0x00000000, 0x00102030
    move.l  #0x89ABCD12, %d0
    move.b  0(%a1,%d4.l), %d0
    bne     _fail5
    cmp.l   #0x89ABCD00, %d0
    bne     _fail5

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
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d1
    bra     _fail
_fail5:
    move.l  #0xDEAD0005, %d1
    bra     _fail
_fail6:
    move.l  #0xDEAD0006, %d1
    bra     _fail
_fail7:
    move.l  #0xDEAD0007, %d1
    bra     _fail
_fail8:
    move.l  #0xDEAD0008, %d1

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d1, (%a0)
_halt_fail:
    bra     _halt_fail
