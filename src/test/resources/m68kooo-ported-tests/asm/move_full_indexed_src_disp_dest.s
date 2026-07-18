| move_full_indexed_src_disp_dest.s -- MOVE.{B,W,L} full-indexed source to d16 dest
|
| Covers the Q700 ROM frontier:
|   408099e0: 2f70 25a0 0e00 0008  move.l @(0e00,D2.W*4),8(SP)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Exact ROM long shape: base suppressed, D2.W*4, bd.W=$0e00, 8(A7).
    lea     0x00000e10, %a0
    move.l  #0x89abcdef, (%a0)
    lea     0x00112000, %a7
    move.l  #0x00000000, 8(%a7)
    moveq   #4, %d2

    .word   0x2f70, 0x25a0, 0x0e00, 0x0008
    bpl     _fail1
    beq     _fail1
    bvs     _fail1
    bcs     _fail1
    moveq   #32, %d6
1:  subq.l  #1, %d6
    bne     1b
    move.l  8(%a7), %d0
    cmp.l   #0x89abcdef, %d0
    bne     _fail1

    | Same long shape with a negative word index.  The ROM reaches both
    | $0e00 and $1e00 table forms with D2.W carrying signed offsets.
    lea     0x00000d54, %a0
    move.l  #0x01234567, (%a0)
    lea     0x00112000, %a7
    move.l  #0x00000000, 8(%a7)
    move.l  #0xffffffd5, %d2

    .word   0x2f70, 0x25a0, 0x0e00, 0x0008
    bmi     _fail4
    beq     _fail4
    bvs     _fail4
    bcs     _fail4
    moveq   #32, %d6
4:  subq.l  #1, %d6
    bne     4b
    move.l  8(%a7), %d0
    cmp.l   #0x01234567, %d0
    bne     _fail4

    lea     0x00001d54, %a0
    move.l  #0x76543210, (%a0)
    lea     0x00112000, %a7
    move.l  #0x00000000, 8(%a7)
    move.l  #0xffffffd5, %d2

    .word   0x2f70, 0x25a0, 0x1e00, 0x0008
    bmi     _fail5
    beq     _fail5
    bvs     _fail5
    bcs     _fail5
    moveq   #32, %d6
5:  subq.l  #1, %d6
    bne     5b
    move.l  8(%a7), %d0
    cmp.l   #0x76543210, %d0
    bne     _fail5

    | Word sibling: base present, index suppressed, bd.W, d16 destination.
    lea     0x00112100, %a1
    lea     0x00112200, %a3
    move.w  #0x8001, 0x10(%a1)
    move.l  #0xaabbccdd, 4(%a3)

    .word   0x3771, 0x0160, 0x0010, 0x0006
    bpl     _fail2
    beq     _fail2
    bvs     _fail2
    bcs     _fail2
    moveq   #32, %d6
2:  subq.l  #1, %d6
    bne     2b
    move.l  4(%a3), %d1
    cmp.l   #0xaabb8001, %d1
    bne     _fail2

    | Byte sibling: base present, D4.L*2, bd.L=-4, negative d16 dest.
    lea     0x00113000, %a0
    lea     0x00113100, %a5
    move.b  #0x00, 4(%a0)
    move.l  #0x11223344, -4(%a5)
    move.l  #0x00000004, %d4

    .word   0x1b70, 0x4b30, 0xffff, 0xfffc, 0xfffd
    bne     _fail3
    bmi     _fail3
    bvs     _fail3
    bcs     _fail3
    moveq   #32, %d6
3:  subq.l  #1, %d6
    bne     3b
    move.l  -4(%a5), %d3
    cmp.l   #0x11003344, %d3
    bne     _fail3

_pass:
    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a6)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d7
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d7
    bra     _fail
_fail5:
    move.l  #0xDEAD0005, %d7

_fail:
    lea     0xFFFF0000, %a6
    move.l  %d7, (%a6)
_halt_fail:
    bra     _halt_fail
