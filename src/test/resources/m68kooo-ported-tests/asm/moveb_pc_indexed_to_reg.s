| moveb_pc_indexed_to_reg.s -- MOVE.B (d8,PC,Xn),Dn
|
| Covers the Q700 ROM shape:
|   103b 70ee    move.b (-18,PC,D7.W),D0
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Exact ROM word-index shape.  The brief PC base is the extension-word
    | address, so 0x70ee targets (_rom_shape + 2 - 18 + D7.W).
    moveq   #4, %d7
    move.l  #0x12345678, %d0
    bra     _rom_shape

_rom_table:
    .byte   0x00, 0x00, 0x01, 0x04
_rom_byte:
    .byte   0x05
    .byte   0x00, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff
    .byte   0x66, 0x77, 0x88, 0x99

_rom_shape:
    .word   0x103b, 0x70ee          | move.b (-18,PC,D7.W),D0
    bmi     _fail1
    beq     _fail1
    bvs     _fail1
    bcs     _fail1
    cmp.l   #0x12345605, %d0
    bne     _fail1

    | Long-index sibling: load a negative byte and preserve upper Dn bytes.
    moveq   #2, %d4
    move.l  #0xABCDEF12, %d2
    bra     _long_shape

_long_table:
    .byte   0x11, 0x22, 0x80, 0x44

_long_shape:
    move.b  _long_table(%pc,%d4.l), %d2
    bpl     _fail2
    beq     _fail2
    bvs     _fail2
    bcs     _fail2
    cmp.l   #0xABCDEF80, %d2
    bne     _fail2

    | Zero byte updates Z and still only replaces the low byte.
    moveq   #1, %d5
    move.l  #0x0BADBEEF, %d3
    bra     _zero_shape

_zero_table:
    .byte   0x7f, 0x00, 0x55, 0x66

_zero_shape:
    move.b  _zero_table(%pc,%d5.w), %d3
    bne     _fail3
    bmi     _fail3
    bvs     _fail3
    bcs     _fail3
    cmp.l   #0x0BADBE00, %d3
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
