| and_l_d8_an_xn_src.s -- AND.L (d8,An,Xn),Dn (V2-fired indexed src).
|
| Task #222 (E6).  Long-size brief-indexed AND source.  Covers
| scale x1/x2/x4/x8 with both word and long indexes.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | AND.L scale x1 long-index — disp 0.
    lea     0x00132000, %a0
    lea     0x00132004, %a6
    move.l  #0xF0F00F0F, (%a6)
    moveq   #4, %d3
    move.l  #0xCAFEBABE, %d0
    .word   0xc0b0, 0x3800       | and.l (0,A0,D3.L*1),D0  off=4
    cmp.l   #0xC0F00A0E, %d0
    bne     _fail

    | AND.L scale x2 word-index — neg disp8.
    lea     0x00132100, %a1
    lea     0x00132104, %a6
    move.l  #0x0F0FF0F0, (%a6)
    moveq   #2, %d4
    move.l  #0xFFFFFFFF, %d1
    .word   0xc2b1, 0x4200       | and.l (0,A1,D4.W*2),D1  off=4
    cmp.l   #0x0F0FF0F0, %d1
    bne     _fail

    | AND.L scale x8 long-index — pos disp8 0x10, A4=4, off=16+4*8=48.
    lea     0x00132200, %a3
    lea     0x00132230, %a6           | 0x30=48
    move.l  #0xAAAA5555, (%a6)
    movea.l #4, %a4
    move.l  #0xFFFFFFFF, %d2
    .word   0xc4b3, 0xce10       | and.l (16,A3,A4.L*8),D2 off=16+32=48
    cmp.l   #0xAAAA5555, %d2
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
