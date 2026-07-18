| move_l_idx_src_d16_an_dst.s -- MOVE.L (d8,An,Xn*scale),(d16,An)
|
| V2 Task #194 / A2: brief-indexed src -> (d16,An) memory dst.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    move.l  #0x00102000, %a1
    move.l  #0x00112000, %a2
    moveq   #2, %d0

    | Seed src: 0x00102008 (A1 + D0.L*4).
    move.l  #0xCAFEBABE, 0x00102008

    | MOVE.L 0(%a1,%d0.l*4), 0x40(%a2)  -- dst at 0x00112040.
    move.l  0(%a1,%d0.l*4), 0x40(%a2)

    move.l  0x00112040, %d1
    cmp.l   #0xCAFEBABE, %d1
    bne     _fail1

    | Negative d16.  Second src uses D0.W*2 with D0=2 -> index=4.
    | src EA = A1 + 4 = 0x00102004.  Seed that.  Dst = A2 + (-4) = 0x0011201C.
    move.l  #0x00112020, %a2
    moveq   #2, %d0
    move.l  #0xAABB1122, 0x00102004
    move.l  0(%a1,%d0.w*2), -4(%a2)
    move.l  0x0011201C, %d2
    cmp.l   #0xAABB1122, %d2
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
