| movew_pc_indexed_scaled.s -- scaled MOVE.W PC-indexed loads
|
| Covers the current Q700 ROM frontier:
|   303b 0206    move.w (6,PC,D0.W*2),D0
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Exact ROM opword.  The table word sits immediately after the branch,
    | so D0.W=-1 selects it through base=(op+2), disp=6, scale=2.
    move.l  #0x12340000, %d0
    move.w  #-1, %d0
_rom_shape:
    .word   0x303b, 0x0206        | move.w (6,pc,d0.w*2),d0
    .word   0x6002                | bra.s _check_rom_shape
_rom_word:
    .word   0x8001

_check_rom_shape:
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x12348001, %d0
    bne     _fail

    | Also cover a long-index scaled sibling in the same decode family:
    | D1.L=1, disp=4, scale=4 selects the word after two padding words.
    moveq   #1, %d1
    move.l  #0x56780000, %d2
_long_scaled:
    .word   0x343b, 0x1c04        | move.w (4,pc,d1.l*4),d2
    .word   0x6006                | bra.s _check_long_scaled
    .word   0x1111, 0x2222
_long_word:
    .word   0x007f

_check_long_scaled:
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x5678007f, %d2
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d3
    move.l  %d3, (%a0)
    bra     _pass

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d3
    move.l  %d3, (%a0)
    bra     _fail
