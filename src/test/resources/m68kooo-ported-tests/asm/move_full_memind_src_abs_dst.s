| move_full_memind_src_abs_dst.s -- MOVE.{B,W,L} full memory-indirect source to abs dest
|
| Covers the Q700 ROM frontier exposed by the timer-delay ROM patch:
|   21f0 81e2 0ddc ffe8 1ef0    move.l @($0ddc)@(-24),0x1ef0.W
|
| The exact ROM shape uses full-format mode-6 source EA with base and index
| suppressed, bd.W, preindexed memory-indirect, od.W, and an absolute-word
| destination.  Byte/word siblings pin the same source EA path with different
| final transfer sizes.

    .text
    .org 0

_start:
    | Exact ROM long shape: pointer at $0ddc, source at pointer - 24,
    | destination at $1ef0.W.
    lea     0x00000ddc, %a0
    move.l  #0x00114218, (%a0)
    lea     0x00114200, %a0
    move.l  #0x89abcdef, (%a0)
    lea     0x00001ef0, %a1
    move.l  #0x00000000, (%a1)

    .word   0x21f0, 0x81e2, 0x0ddc, 0xffe8, 0x1ef0
    bpl     _fail1
    beq     _fail1
    bvs     _fail1
    bcs     _fail1
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.l  (%a1), %d0
    cmp.l   #0x89abcdef, %d0
    bne     _fail1

    | Word sibling: same base/index-suppressed full source EA, od.W = +4.
    lea     0x00000de0, %a0
    move.l  #0x00114240, (%a0)
    lea     0x00114244, %a0
    move.w  #0x8001, (%a0)
    lea     0x00001ef4, %a1
    move.w  #0x0000, (%a1)

    .word   0x31f0, 0x81e2, 0x0de0, 0x0004, 0x1ef4
    bpl     _fail2
    beq     _fail2
    bvs     _fail2
    bcs     _fail2
    moveq   #32, %d7
2:  subq.l  #1, %d7
    bne     2b
    move.w  (%a1), %d1
    cmp.w   #0x8001, %d1
    bne     _fail2

    | Byte sibling: zero byte should set Z, clear N/V/C, and store one byte.
    lea     0x00000de4, %a0
    move.l  #0x00114262, (%a0)
    lea     0x00114260, %a0
    move.b  #0x00, (%a0)
    lea     0x00001ef8, %a1
    move.b  #0xff, (%a1)

    .word   0x11f0, 0x81e2, 0x0de4, 0xfffe, 0x1ef8
    bne     _fail3
    bmi     _fail3
    bvs     _fail3
    bcs     _fail3
    moveq   #32, %d7
3:  subq.l  #1, %d7
    bne     3b
    move.b  (%a1), %d2
    cmp.b   #0x00, %d2
    bne     _fail3

    | Base-present/null-outer sibling uses the same crack but reads the
    | pointer from A3 + bd.W and then stores through an absolute destination.
    lea     0x00114310, %a3
    lea     0x00114300, %a0
    move.l  #0x00114380, (%a0)
    lea     0x00114380, %a0
    move.l  #0x12345678, (%a0)
    lea     0x00001efc, %a1
    move.l  #0x00000000, (%a1)

    .word   0x21f3, 0x8161, 0xfff0, 0x1efc
    bmi     _fail4
    beq     _fail4
    bvs     _fail4
    bcs     _fail4
    moveq   #32, %d7
4:  subq.l  #1, %d7
    bne     4b
    move.l  (%a1), %d3
    cmp.l   #0x12345678, %d3
    bne     _fail4

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
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

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
