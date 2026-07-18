| movec_usp_min.s — minimal repro: does MOVEC An,USP wedge the core?
| PASS: 0xC0FFEE00 at 0xFFFF0000 right after the movec.
    .text
    .org 0
_start:
    move.w  #0x2700, %sr
    move.l  #0x000FE000, %sp
    move.l  #0x000F8000, %a0
    movec   %a0, %usp
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, (%a1)
_halt:
    bra     _halt
