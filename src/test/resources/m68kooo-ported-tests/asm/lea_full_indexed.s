| lea_full_indexed.s -- LEA full-format indexed mode-6 forms
|
| Covers the Q700 ROM frontier:
|   40809a5e: 43f0 05a0 0400  lea @(400,D0.W*4),A1
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | 1. Exact ROM shape: base suppressed, D0.W*4, bd.W=$0400.
    move.l  #0x40803f30, %a0
    move.l  #0x0000005d, %d0
    moveq   #0, %d7
    tst.l   %d7
_rom_shape:
    .word   0x43f0, 0x05a0, 0x0400
    bne     _fail1_flags           | LEA must preserve CCR
    cmpa.l  #0x00000574, %a1
    bne     _fail1_value

    | 2. Base-present sibling: A0 + bd.W + D0.W*4.
    move.l  #0x00100000, %a0
    moveq   #3, %d0
_base_present:
    .word   0x43f0, 0x0520, 0x0040
    cmpa.l  #0x0010004c, %a1
    bne     _fail2

    | 3. Long index and long base displacement.
    move.l  #0x00200000, %a2
    move.l  #0x00000007, %d3
_long_bd:
    .word   0x47f2, 0x3d30, 0x0000, 0x0100
    cmpa.l  #0x0020011c, %a3
    bne     _fail3

    | 4. Index-suppressed form still uses the base-displacement EA.
    move.l  #0x00300000, %a4
    move.l  #0x7fffffff, %d5
_index_suppressed:
    .word   0x4bf4, 0x0160, 0x0ff0
    cmpa.l  #0x00300ff0, %a5
    bne     _fail4

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
_halt:
    bra     _halt

_fail1_flags:
    move.l  #0xDEAD0001, %d2
    bra     _fail
_fail1_value:
    move.l  #0xDEAD0011, %d2
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d2
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d2
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d2
    bra     _fail

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d2, (%a0)
_halt_fail:
    bra     _halt_fail
