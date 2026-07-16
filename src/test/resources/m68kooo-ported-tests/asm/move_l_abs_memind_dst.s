| move_l_abs_memind_dst.s — Task #212 (D1)
|
| MOVE.L (abs).W, ([bd.W,An],od) — abs source, memind dst no-idx.
| Tests Shape 4: abs-src -> memind-dst (no-index).
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Seed abs source slot (0x0008.W = 0x00000008)
    lea     0x00000008, %a0
    move.l  #0x11223344, (%a0)

    | Seed inner pointer slot: [A0 + 0x0cbc]
    | With A0 = 0, inner slot = 0x00000cbc = 0x00115000
    lea     0x00000cbc, %a0
    move.l  #0x00115000, (%a0)

    | Seed destination slot at (0x00115000 + 0xfff8 sign-extend = -8)
    | = 0x00114ff8
    lea     0x00114ff8, %a0
    move.l  #0x00000000, (%a0)

    | Clear A0 for the memind base.
    moveq   #0, %d0
    movea.l %d0, %a0

    | MOVE.L (0x0008).W, ([0x0cbc,A0], 0xfff8) — hand-encoded
    .word   0x21b8, 0x0008, 0x81e2, 0x0cbc, 0xfff8

    | Verify [0x00114ff8] == 0x11223344
    lea     0x00114ff8, %a1
    move.l  (%a1), %d1
    cmp.l   #0x11223344, %d1
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0004, %d7
    move.l  %d7, (%a0)
_halt_f:
    bra     _halt_f
