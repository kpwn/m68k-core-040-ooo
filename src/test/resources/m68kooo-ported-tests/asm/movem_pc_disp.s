| movem_pc_disp.s -- MOVEM.L (d16,PC),<regs>
|
| Covers the Q700 ROM block-copy helper instruction:
|   0x4cfa 0x003f 0x00fa  movem.l (d16,pc),d0-d5

    .text
    .org 0

_start:
    | MOVEM must preserve CCR.
    moveq   #7, %d6
    cmp.l   %d6, %d6

_movem:
    .word   0x4cfa, 0x003f
    .word   _table - (_movem + 4)
    bne     _fail

    cmp.l   #0x11111111, %d0
    bne     _fail
    cmp.l   #0x22222222, %d1
    bne     _fail
    cmp.l   #0x33333333, %d2
    bne     _fail
    cmp.l   #0x44444444, %d3
    bne     _fail
    cmp.l   #0x55555555, %d4
    bne     _fail
    cmp.l   #0x66666666, %d5
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_fail_halt:
    bra     _fail_halt

    .align  2
_table:
    .long   0x11111111
    .long   0x22222222
    .long   0x33333333
    .long   0x44444444
    .long   0x55555555
    .long   0x66666666
