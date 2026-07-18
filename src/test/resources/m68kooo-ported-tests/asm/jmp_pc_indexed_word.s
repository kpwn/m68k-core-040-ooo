| jmp_pc_indexed_word.s -- JMP (d8,PC,Xn.W) ROM jump-table branch
|
| Covers the Q700 ROM shape:
|   4efb 3002    jmp (2,PC,D3.W)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    move.w  #(_target - (_rom_shape + 4)), %d3

_rom_shape:
    .word   0x4efb, 0x3002

_fail_fallthrough:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d2
    move.l  %d2, (%a0)
    bra     _fail_fallthrough

_target:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
    bra     _target
