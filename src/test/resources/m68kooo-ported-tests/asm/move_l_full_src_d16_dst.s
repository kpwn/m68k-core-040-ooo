| move_l_full_src_d16_dst.s -- MOVE.L (bd,An,Xn),(d16,Am)
|
| Verifies the V2 chain now emits the correct mem->(d16,An) crack for
| a full-format no-memind source.  This EA combo previously routed to
| legacy decode_move_long.vh line 774 but is now covered by V2.

    .text
    .org 0

_start:
    | src = (bd.W=0x200, A0, D1.W*4) — EA = A0 + 0x200 + D1*4
    | dst = (d16=0x100, A2) — EA = A2 + 0x100
    | ext1 for src = 0x1520 (D1.W, x4, full, BS=0, IS=0, BD=word, I/IS=000)
    | src ext words = ext1(0x1520) + ext2(0x0200) = 2 half-words
    | dst ext = ext3(0x0100)  — d16
    | opword: MOVE.L src,(d16,A2)
    |   src mode=110, src reg=A0=000
    |   dst mode=101, dst reg=A2=010
    |   opword bits: 0010 010 101 110 000 = 0x2570
    move.l  #0x00100000, %a0
    move.l  #0x00200000, %a2
    moveq   #4, %d1
    move.l  #0xFEEDC0DE, 0x00100210
    move.l  #0, 0x00200100
    .word   0x2570, 0x1520, 0x0200, 0x0100
    move.l  0x00200100, %d3
    cmp.l   #0xFEEDC0DE, %d3
    bne     _fail1

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d0

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
