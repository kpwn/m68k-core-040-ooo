| moveb_pc_indexed_full_scaled.s -- full-format scaled PC-indexed MOVE.B
|
| Covers the Q700 ROM frontier:
|   408817a6: 143b 0320 003c  move.b (0x3c,PC,D0.W*2),D2

    .text
    .org 0

_start:
    | Exact full-extension frontier form.  The full extension uses the
    | extension-word address as the PC base, adds bd.W=0x003c, then
    | adds D0.W scaled by 2.
    moveq   #1, %d0
    move.l  #0x12345678, %d2
_rom_shape:
    .word   0x143b, 0x0320, 0x003c  | move.b (0x3c,pc,d0.w*2),d2
    bra     _rom_check
    .space  0x34, 0
_rom_table:
    .byte   0x11, 0x00, 0x85, 0x00, 0x22, 0x00, 0x33, 0x00
_rom_check:
    bpl     _fail1
    beq     _fail1
    bvs     _fail1
    bcs     _fail1
    cmp.l   #0x12345685, %d2
    bne     _fail1

    | Long-index scaled sibling using the same full-extension shape.
    moveq   #2, %d1
    move.l  #0xabcdef12, %d3
_long_shape:
    .word   0x163b, 0x1d20, 0x003c  | move.b (0x3c,pc,d1.l*4),d3
    bra     _long_check
    .space  0x34, 0
_long_table:
    .byte   0x44, 0x00, 0x55, 0x00, 0x66, 0x00, 0x7f, 0x00, 0x00
    .align  2
_long_check:
    bne     _fail2
    bmi     _fail2
    bvs     _fail2
    bcs     _fail2
    cmp.l   #0xabcdef00, %d3
    bne     _fail2

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d4
    move.l  %d4, (%a0)
    bra     _pass

_fail1:
    move.l  #0xDEAD0001, %d4
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d4

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d4, (%a0)
    bra     _fail
