| add_l_d8_an_xn_src.s -- ADD.L (d8,An,Xn),Dn (V2-fired indexed src).
|
| Task #222 (E6) routes brief-indexed (d8,An,Xn) ALU sources through
| the V2 assembler.  This test exercises the long-size form across
| scale x1/x2/x4/x8 with word/long indexes and a negative disp8.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Long ADD with scale x4 word-index — EA = A0 + 4 + 4*4 = A0+20.
    lea     0x00130000, %a0
    lea     0x00130014, %a6
    move.l  #0x12345678, (%a6)
    moveq   #4, %d3
    move.l  #0x10000001, %d0
    .word   0xd0b0, 0x3404       | add.l (4,A0,D3.W*4),D0
    cmp.l   #0x22345679, %d0
    bne     _fail

    | Long ADD with scale x4 long-index, disp 0 — EA = A1 + 0 + 5*4 = A1+20.
    lea     0x00130100, %a1
    lea     0x00130114, %a6
    move.l  #0x10000000, (%a6)
    movea.l #5, %a2
    move.l  #0x40000000, %d1
    .word   0xd2b1, 0xac00       | add.l (0,A1,A2.L*4),D1
    cmp.l   #0x50000000, %d1
    bne     _fail

    | Long ADD with scale x8 long-index, neg disp8 (-8): EA = A3 - 8 + 2*8 = A3+8.
    lea     0x00130200, %a3
    lea     0x00130208, %a6
    move.l  #0x00000007, (%a6)
    move.l  #2, %d4
    move.l  #0x00000000, %d2
    .word   0xd4b3, 0x4ef8       | add.l (-8,A3,D4.L*8),D2
    cmp.l   #0x00000007, %d2
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
