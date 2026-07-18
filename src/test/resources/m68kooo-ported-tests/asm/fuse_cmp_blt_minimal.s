| Minimal repro: CMP.L D3,D0 + BLT
    .text
    .org 0
_start:
    move.l  #0x3a63b47d, %d3   | from fuzz trace
    move.l  #0x0000002b, %d0   | from fuzz trace  (0x2b - 0x3a63b47d)
    | d0 - d3 = 0x2b - 0x3a63b47d = large negative.  N=1, V=0.  BLT (N^V)=1 taken.
    cmp.l   %d3, %d0
    blt     _pass
    bra     _fail
_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
