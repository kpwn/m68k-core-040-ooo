| move_full_postindexed_memind_src_indirect_dest.s
| MOVE.L full-format postindexed memory-indirect source to (An)
|
| Covers the Q700 ROM frontier:
|   4080644c: 20b0 05a5 0cbc  move.l @($0cbc)@(0,D0.W*4),(A0)

    .text
    .org 0

_start:
    | Exact ROM table-copy shape: base suppressed, bd.W pointer slot,
    | D0.W scaled by four after the pointer load, destination (A0).
    lea     0x00000cbc, %a3
    move.l  #0x00126000, (%a3)
    lea     0x00126000, %a2
    move.l  #0x89abcdef, 0x0038(%a2)
    lea     0x00127000, %a0
    move.l  #0x0000000e, %d0

    .word   0x20b0, 0x05a5, 0x0cbc
    bpl     _fail1
    beq     _fail1
    bvs     _fail1
    bcs     _fail1
    move.l  (%a0), %d1
    cmp.l   #0x89abcdef, %d1
    bne     _fail1

    | Sign-extension sibling: D0.W=-1, scale=4, load from pointer-4.
    lea     0x00000cc0, %a3
    move.l  #0x00128004, (%a3)
    lea     0x00128000, %a2
    move.l  #0x00000000, (%a2)
    lea     0x00127004, %a0
    move.l  #0x0000ffff, %d0

    .word   0x20b0, 0x05a5, 0x0cc0
    bne     _fail2
    bmi     _fail2
    bvs     _fail2
    bcs     _fail2
    move.l  (%a0), %d1
    cmp.l   #0x00000000, %d1
    bne     _fail2

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

_fail:
    lea     0xffff0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
