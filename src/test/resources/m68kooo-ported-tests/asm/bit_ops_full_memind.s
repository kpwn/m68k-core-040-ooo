| bit_ops_full_memind.s -- static bit ops through full-format memory-indirect EA
|
| Covers the Q700 ROM frontier:
|   408005b0: 0830 0000 81e2 0ddc ffe7
|             btst #0,@($0ddc)@(-25)
|
| Scope: base/index suppressed full-format mode-6 EA, bd.W, preindexed
| memory-indirect, with both word and null outer-displacement forms.

    .text
    .org 0

_start:
    | Exact ROM BTST shape: pointer at $0ddc, target byte at pointer - 25.
    lea     0x00000ddc, %a0
    move.l  #0x00114218, (%a0)
    lea     0x001141ff, %a1
    move.b  #0x01, (%a1)

    .word   0x0830, 0x0000, 0x81e2, 0x0ddc, 0xffe7
    beq     _fail1
    move.b  (%a1), %d0
    cmp.b   #0x01, %d0
    bne     _fail1

    | BTST of a clear bit sets Z and leaves the byte unchanged.
    move.b  #0x00, (%a1)
    .word   0x0830, 0x0000, 0x81e2, 0x0ddc, 0xffe7
    bne     _fail2
    move.b  (%a1), %d0
    cmp.b   #0x00, %d0
    bne     _fail2

    | BCLR clears a set bit and reports the old bit as set.
    lea     0x00000de0, %a0
    move.l  #0x00114240, (%a0)
    lea     0x00114239, %a2
    move.b  #0x05, (%a2)
    .word   0x08b0, 0x0000, 0x81e2, 0x0de0, 0xfff9
    beq     _fail3
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.b  (%a2), %d1
    cmp.b   #0x04, %d1
    bne     _fail3

    | BSET null-outer form writes back through the pointer itself.
    lea     0x00000de4, %a0
    move.l  #0x00114280, (%a0)
    lea     0x00114280, %a3
    move.b  #0x00, (%a3)
    .word   0x08f0, 0x0003, 0x81e1, 0x0de4
    bne     _fail4
    moveq   #32, %d7
2:  subq.l  #1, %d7
    bne     2b
    move.b  (%a3), %d2
    cmp.b   #0x08, %d2
    bne     _fail4

    | BCHG toggles a set bit and reports the old bit as set.
    lea     0x00000de8, %a0
    move.l  #0x001142c0, (%a0)
    lea     0x001142c2, %a4
    move.b  #0x80, (%a4)
    .word   0x0870, 0x0007, 0x81e2, 0x0de8, 0x0002
    beq     _fail5
    moveq   #32, %d7
3:  subq.l  #1, %d7
    bne     3b
    move.b  (%a4), %d3
    cmp.b   #0x00, %d3
    bne     _fail5

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
