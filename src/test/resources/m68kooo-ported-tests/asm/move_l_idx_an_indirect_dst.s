| move_l_idx_an_indirect_dst.s -- MOVE.L (d8,An,Xn.W*1),(Am)
|
| Covers Q700 ROM frontier legacy shape (decode_move_long.vh line 770):
|   4080092a: 26b0 3000  move.l (0,A0,D3.W),(A3)
|
| V2 Task #194 / A2 coverage check.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    move.l  #0x00102000, %a0
    move.l  #0x00112000, %a3
    moveq   #4, %d3

    | Seed src: A0 + D3.W = 0x00102004.
    move.l  #0x11223344, 0x00102004

    | MOVE.L (0,A0,D3.W), (A3)  -- stores to 0x00112000.
    .word   0x26b0, 0x3000

    move.l  0x00112000, %d0
    cmp.l   #0x11223344, %d0
    bne     _fail1

    | Second pattern with long index.
    moveq   #8, %d4
    move.l  #0xDEADBEEF, 0x00102008
    | .word for move.l (0,A0,D4.L),(A3)
    .word   0x26b0, 0x4800
    move.l  0x00112000, %d1
    cmp.l   #0xDEADBEEF, %d1
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
