| movea_absw_split.s -- MOVEA.L (abs.w),An from an unaligned longword
|
| ROM frontier repro:
|   40009a9a: 2678 02ae    movea.l 0x02ae,%a3
|
| The source longword starts at byte offset 2 within the aligned word,
| so the LSU has to reassemble bytes from two 32-bit beats:
|   [0x02ae..0x02b1] = 40 80 00 00  => 0x40800000
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x000002ae, %a0

    | Program the source value without using an unaligned long store.
    move.w  #0x4080, (%a0)
    clr.w   2(%a0)

    | Exact ROM form under investigation.
    movea.l 0x000002ae, %a3

    move.l  #0x40800000, %d0
    move.l  %a3, %d1
    cmp.l   %d0, %d1
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, (%a1)
_halt_fail:
    bra     _halt_fail
