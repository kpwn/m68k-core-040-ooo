| lea_pc_indexed.s - LEA (d8,PC,Xn.{W,L}) brief forms
|
| Covers the ROM decode gap at 0x4084750a:
|   41fb 88f8    lea (-8,%pc,%a0.l),%a0
| and the lowmem setup frontier at 0x40803e46:
|   41fb 0008    lea (8,%pc,%d0.w),%a0
|
| The important corner is that A0 is both the old index register and the
| destination. Decode must read old A0 in the final crack phase, not the
| destination value being produced.

    .text
    .org 0

_start:
    move.l  #0x20, %a0

_rom_shape:
    .word   0x41fb, 0x88f8
    cmpa.l  #(_rom_shape + 26), %a0
    bne     _fail

    move.l  #0x10, %d0

_word_shape:
    .word   0x41fb, 0x0008
    cmpa.l  #(_word_shape + 26), %a0
    bne     _fail

    move.l  #0xC0FFEE00, %d0
    lea     0xFFFF0000, %a1
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d0
    lea     0xFFFF0000, %a1
    move.l  %d0, (%a1)
_halt_fail:
    bra     _halt_fail
