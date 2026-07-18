| loop_count.s — Backward branch loop with counter
|
| DBcc isn't decoded yet, so build a manual loop:
|   D0 = 0      (accumulator)
|   D1 = 5      (counter)
| _loop:
|   D0 = D0 + D1
|   D1 = D1 - 1
|   BNE _loop   (branch while D1 != 0)
|
| Sums 5+4+3+2+1 = 15. Exercises a backward-taken branch every
| iteration plus the dependency D1→D1 (RAW) and D0→D0 (RAW),
| both serialised by the loop branch. Also stresses the BPU's
| handling of consistently-taken backward edges.

    .text
    .org 0

_start:
    moveq   #0, %d0
    moveq   #5, %d1
_loop:
    add.l   %d1, %d0
    subq.l  #1, %d1
    bne     _loop

    | check D0 == 15
    moveq   #15, %d2
    cmp.l   %d2, %d0
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d3
    move.l  %d3, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d3
    move.l  %d3, (%a0)
_halt_fail:
    bra     _halt_fail
