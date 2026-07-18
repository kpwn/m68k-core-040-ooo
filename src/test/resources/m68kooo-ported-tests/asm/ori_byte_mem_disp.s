| ori_byte_mem_disp.s -- ORI.B #imm,(d16,An) ROM-path regression
|
| Covers the Q700 ROM shape:
|   002d 0007 0600    ori.b #7, 0x600(%a5)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Z flag from a zero byte immediate/data result.
    move.l  #0x00000000, 0x00100600
    move.l  #0x00100000, %a5
    ori.b   #0x00, 0x600(%a5)
    bne     _fail
    move.l  0x00100600, %d0
    cmp.l   #0x00000000, %d0
    bne     _fail

    | Exact ROM displacement shape, plus neighboring-byte preservation.
    move.l  #0xAA00CCF0, 0x00100600
    move.l  #0x00100000, %a5
    move.l  #0x00123456, %a3
    ori.b   #0x07, 0x600(%a5)
    bpl     _fail
    cmpa.l  #0x00123456, %a3
    bne     _fail
    move.l  0x00100600, %d1
    cmp.l   #0xAF00CCF0, %d1
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
