| move_imm_ccr_check.s — verify MOVE.W #imm,CCR sets CCR correctly.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    move.w  #0x000a, %ccr
    move.w  %sr, %d3
    and.l   #0x1f, %d3
    cmp.l   #0x0a, %d3
    bne     _fail

    | PASS sentinel.
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0000, %d0
    or.l    %d3, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
