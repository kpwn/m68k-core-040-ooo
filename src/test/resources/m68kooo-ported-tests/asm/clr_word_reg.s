| clr_word_reg.s -- CLR.W Dn preserves upper word and writes CLR flags
|
| Covers the ROM stop at 0x40846d76:
|   4243    clr.w %d3
|   4843    swap  %d3
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | ROM-shaped path: clear low word, then swap preserved high word down.
    move.l  #0x007c0100, %d3
    clr.w   %d3
    swap    %d3
    cmp.l   #0x0000007c, %d3
    bne     _fail

    | CLR.W itself preserves the upper word and sets Z with C/V clear.
    move.l  #0x12340005, %d0
    clr.w   %d0
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x12340000, %d0
    bne     _fail

    | The all-ones case catches accidental full-register zeroing.
    move.l  #0xffffffff, %d1
    clr.w   %d1
    cmp.l   #0xffff0000, %d1
    bne     _fail

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
