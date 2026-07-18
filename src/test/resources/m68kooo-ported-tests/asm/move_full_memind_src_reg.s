| move_full_memind_src_reg.s -- MOVE.{B,W,L} full memory-indirect source to Dn
|
| Covers the Q700 ROM frontier:
|   40806d2c: 1030 81e2 0cbc fffc  move.b ([0x0cbc],-4),D0
|
| The ROM form uses a full-format mode-6 source EA with base and index
| suppressed, bd.W, preindexed memory-indirect, and od.W.  Word/long
| siblings keep this family widened instead of landing only the byte crack.

    .text
    .org 0

_start:
    | Exact byte family: D0.B loaded from *(long[0x0cbc] - 4), upper
    | D0 bits preserved, NZVC from the byte value.
    lea     0x00000cbc, %a0
    move.l  #0x00114224, (%a0)
    lea     0x00114220, %a0
    move.b  #0x5a, (%a0)
    move.l  #0xaabbcc00, %d0

    .word   0x1030, 0x81e2, 0x0cbc, 0xfffc
    beq     _fail1
    bmi     _fail1
    bvs     _fail1
    bcs     _fail1
    cmp.l   #0xaabbcc5a, %d0
    bne     _fail1

    | Word sibling: same base/index-suppressed full source EA with od.W.
    lea     0x00000cc0, %a0
    move.l  #0x00114242, (%a0)
    lea     0x00114246, %a0
    move.w  #0x8001, (%a0)
    move.l  #0x55aa0000, %d1

    .word   0x3230, 0x81e2, 0x0cc0, 0x0004
    bpl     _fail2
    beq     _fail2
    bvs     _fail2
    bcs     _fail2
    cmp.l   #0x55aa8001, %d1
    bne     _fail2

    | Long sibling: null outer displacement should use the pointer value
    | directly and set NZVC from the loaded long.
    lea     0x00000cc4, %a0
    move.l  #0x00114260, (%a0)
    lea     0x00114260, %a0
    move.l  #0x89abcdef, (%a0)

    .word   0x2430, 0x81e1, 0x0cc4
    bpl     _fail3
    beq     _fail3
    bvs     _fail3
    bcs     _fail3
    cmp.l   #0x89abcdef, %d2
    bne     _fail3

_pass:
    lea     0xffff0000, %a0
    move.l  #0xc0ffee00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xdead0001, %d7
    bra     _fail
_fail2:
    move.l  #0xdead0002, %d7
    bra     _fail
_fail3:
    move.l  #0xdead0003, %d7

_fail:
    lea     0xffff0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
