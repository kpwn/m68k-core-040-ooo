| tst_mem_indirect.s -- TST.{B,W,L} on (An) memory operands
|
| Covers the Q700 ROM ASC loop shape:
|   40807116: 4a15    tst.b (%a5)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00106000, %a5
    move.l  #0x80000000, (%a5)

    | Byte negative.
    tst.b   (%a5)
    beq     _fail
    bpl     _fail
    bvs     _fail
    bcs     _fail

    | Byte zero.
    lea     0x00106010, %a0
    move.l  #0x00000001, (%a0)
    tst.b   (%a0)
    bne     _fail
    bmi     _fail

    | Word positive non-zero.
    lea     0x00106020, %a1
    move.l  #0x00010000, (%a1)
    tst.w   (%a1)
    beq     _fail
    bmi     _fail

    | Long negative.
    lea     0x00106030, %a2
    move.l  #0x80000000, (%a2)
    tst.l   (%a2)
    beq     _fail
    bpl     _fail

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
