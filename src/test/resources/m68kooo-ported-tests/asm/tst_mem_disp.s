| tst_mem_disp.s -- TST.{B,W,L} on (d16,An) memory operands
|
| Covers the ROM shape reached after the Q700 checksum loop:
|   40845c1e: 4a2b 0800    tst.b 0x0800(%a3)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    move.l  #0x00800001, 0x00103800
    move.l  #0x80000000, 0x00103804
    move.l  #0x00103000, %a3

    | Byte zero: Z=1, N=0.
    tst.b   0x800(%a3)
    bne     _fail
    bmi     _fail

    | Byte negative: Z=0, N=1.
    tst.b   0x801(%a3)
    beq     _fail
    bpl     _fail

    | Word positive non-zero: Z=0, N=0.
    tst.w   0x802(%a3)
    beq     _fail
    bmi     _fail

    | Long negative: Z=0, N=1.
    tst.l   0x804(%a3)
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
