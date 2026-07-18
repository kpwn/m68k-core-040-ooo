| move_l_idx_src_abs_dst.s -- MOVE.L (d8,An,Xn*scale),(xxx).L
|
| V2 Task #194 / A2: brief-indexed src -> absolute-long memory dst.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    move.l  #0x00102000, %a1
    moveq   #2, %d2

    | Seed source: indexed EA = A1 + D2.L*4 + 0 = 0x00102008.
    move.l  #0x89ABCDEF, 0x00102008

    | MOVE.L (0(%a1,%d2.l*4)), 0x00112000.L.  Absolute-long dst.
    move.l  0(%a1,%d2.l*4), 0x00112000

    | Verify the long landed.
    move.l  0x00112000, %d0
    cmp.l   #0x89ABCDEF, %d0
    bne     _fail1

    | Negative value sets N, clears Z/V/C.  Scale x1 sibling.
    moveq   #12, %d3
    move.l  #0x80002222, 0x0010200c
    | src EA = A1 + D3.W*1 = 0x00102000 + 12 = 0x0010200c.
    move.l  0(%a1,%d3.w), 0x00112010
    bpl     _fail2
    beq     _fail2
    move.l  0x00112010, %d1
    cmp.l   #0x80002222, %d1
    bne     _fail2

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d1
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d1

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d1, (%a0)
_halt_fail:
    bra     _halt_fail
