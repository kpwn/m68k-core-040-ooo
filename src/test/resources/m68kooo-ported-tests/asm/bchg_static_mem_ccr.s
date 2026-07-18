| bchg_static_mem_ccr.s — BCHG #imm, mem CCR preservation
    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.b  #0x00, 0x00020000
    move.w  #0x000a, %ccr
    bchg    #0, 0x00020000        | static bit number
    move.w  %sr, %d3
    and.l   #0x1f, %d3
    cmp.l   #0x0e, %d3
    beq     _pass
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0000, %d0
    or.l    %d3, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
