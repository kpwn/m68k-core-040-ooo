| movew_pc_indexed_word.s -- MOVE.W (d8,PC,Xn.W),Dn ROM jump-table load
|
| Covers the Q700 ROM shape:
|   363b 3006    move.w (6,PC,D3.W),D3
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    moveq   #0x11, %d3
    add.w   %d3, %d3

_rom_shape:
    .word   0x363b, 0x3006
    bra     _check

    .org    0x2e
_table:
    .word   0x8f83

_check:
    bpl     _fail
    cmp.l   #0x00008f83, %d3
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
    bra     _pass

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d2
    move.l  %d2, (%a0)
    bra     _fail
