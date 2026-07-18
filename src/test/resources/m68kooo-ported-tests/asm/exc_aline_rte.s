| exc_aline_rte.s — A-line trap (vec 10) with RTE round-trip.
|
| Covers the Mac OS Toolbox hot path: vec-10 entry, handler-side
| stacked-PC adjustment, and RTE back to the caller.
|
| Single A-line trap, handler increments D7, adjusts stacked PC by
| +2 (Toolbox convention), RTEs, mainline checks D7 and writes PASS.
|
| Frame format 0 (8 bytes):
|   (A7+0): SR
|   (A7+2): PC (long)
|   (A7+6): format/vector word

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000028
    moveq   #0, %d7

    .short  0xA001

    cmp.l   #1, %d7
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d4
    move.l  %d4, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d4
    move.l  %d4, (%a0)
_halt_fail:
    bra     _halt_fail

_handler:
    | Skip the A-line opword by adjusting stacked PC +2.
    move.l  2(%a7), %d0         | D0 = stacked PC
    addq.l  #2, %d0
    move.l  %d0, 2(%a7)         | write back
    addq.l  #1, %d7
    rte
