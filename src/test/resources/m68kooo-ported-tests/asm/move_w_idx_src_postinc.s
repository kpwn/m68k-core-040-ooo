| move_w_idx_src_postinc.s -- MOVE.W (d8,An,Xn.W),(An)+
|
| V2 Task #194 / A2: brief-indexed src -> (An)+ postincrement dst.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    move.l  #0x00102000, %a3
    move.l  #0x00112000, %a4
    moveq   #0, %d0

    | Seed src: ea = A3 + 4 = 0x00102004, word value.  D0.W index = 0.
    move.l  #0xFEEDBEEF, 0x00102004

    | Seed dst long so we can verify only upper word was written.
    move.l  #0x12345678, 0x00112000

    | MOVE.W (4(%a3,%d0.w)),(%a4)+  -- should store 0xFEED at A4, then A4 += 2.
    move.w  4(%a3,%d0.w), (%a4)+

    | A4 must have advanced by 2.
    move.l  %a4, %d1
    cmp.l   #0x00112002, %d1
    bne     _fail1

    | Upper word at 0x00112000 = 0xFEED, lower word untouched.
    move.l  0x00112000, %d2
    cmp.l   #0xFEED5678, %d2
    bne     _fail2

    | Negative source word sets N.
    move.l  #0x8001DEAD, 0x00102004
    move.w  4(%a3,%d0.w), (%a4)+
    bpl     _fail3
    move.l  %a4, %d1
    cmp.l   #0x00112004, %d1
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
