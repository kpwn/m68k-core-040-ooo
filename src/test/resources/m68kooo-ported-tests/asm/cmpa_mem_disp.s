| cmpa_mem_disp.s -- CMPA.L (d16,An),Am
|
| Covers the ROM shape at 0x40846c16:
|   bbe9 0004    cmpa.l 4(%a1), %a5
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Equality case: Z must be set, and neither address register changes.
    move.l  #0x00102000, 0x00100004
    lea     0x00100000, %a1
    lea     0x00102000, %a5
    cmpa.l  4(%a1), %a5
    bne     _fail
    cmpa.l  #0x00100000, %a1
    bne     _fail
    cmpa.l  #0x00102000, %a5
    bne     _fail

    | Signed less-than case: A5 - [A1+8] is negative.
    move.l  #0x00103000, 0x00100008
    lea     0x00102000, %a5
    cmpa.l  8(%a1), %a5
    bge     _fail

    | Signed greater-than case: positive and non-zero.
    lea     0x00104000, %a5
    cmpa.l  8(%a1), %a5
    ble     _fail

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
