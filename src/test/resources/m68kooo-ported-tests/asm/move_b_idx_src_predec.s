| move_b_idx_src_predec.s -- MOVE.B (d8,An,Xn.W*scale),-(An)
|
| V2 Task #194 / A2: brief-indexed src -> -(An) predecrement dst.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    move.l  #0x00102000, %a3
    move.l  #0x00112010, %a5
    move.l  #0x00000001, %d1

    | Seed src byte: ea = A3 + (-2) + D1.W*2 = 0x00102000.
    move.l  #0x7A000000, 0x00102000

    | MOVE.B (-2(%a3,%d1.w*2)), -(%a5)  -- A5 -= 1, then store byte at 0x0011200F.
    move.b  -2(%a3,%d1.w*2), -(%a5)

    | Verify A5 = 0x0011200F.
    move.l  %a5, %d2
    cmp.l   #0x0011200F, %d2
    bne     _fail1

    | Verify the byte at 0x0011200F is 0x7A.
    move.b  0x0011200F, %d3
    andi.l  #0xFF, %d3
    cmp.l   #0x7A, %d3
    bne     _fail2

    | Negative byte variant for flags, long-index to hit index_long.
    move.l  #0x00000003, %d2
    move.l  #0x9F000000, 0x00102002
    move.b  -1(%a3,%d2.l), -(%a5)
    bpl     _fail3
    beq     _fail3
    move.l  %a5, %d3
    cmp.l   #0x0011200E, %d3
    bne     _fail3
    move.b  0x0011200E, %d4
    andi.l  #0xFF, %d4
    cmp.l   #0x9F, %d4
    bne     _fail3

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
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d1

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d1, (%a0)
_halt_fail:
    bra     _halt_fail
