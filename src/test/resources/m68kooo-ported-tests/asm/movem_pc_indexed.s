| movem_pc_indexed.s -- MOVEM.L (d8,PC,Xn),<regs>
|
| Covers the Q700 RAM-test verify instruction:
|   40847356: 4cfb 0038 4024  movem.l (0x24,PC,D4.W),D3-D5
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    move.w  #(_table - (_movem + 4) - 0x24), %d4

    | MOVEM must preserve CCR.
    moveq   #7, %d0
    cmp.l   %d0, %d0

_movem:
    .word   0x4cfb, 0x0038, 0x4024
    bne     _fail

    cmp.l   #0x33333333, %d3
    bne     _fail
    cmp.l   #0x44444444, %d4
    bne     _fail
    cmp.l   #0x55555555, %d5
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
    bra     _pass

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
    bra     _fail

    .align  2
_table:
    .long   0x33333333
    .long   0x44444444
    .long   0x55555555
