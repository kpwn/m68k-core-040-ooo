| cmp_l_d8_an_xn_src.s -- CMP.L (d8,An,Xn),Dn (V2-fired indexed src).
|
| Task #222 (E6) — V2 assembler now owns the brief-indexed CMP source
| at all scales.  Previous legacy was scale x1 only — this test
| extends to x2/x4/x8 with both word and long indexes.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | CMP.L scale x2 word-index — equal (Z=1).
    | EA = A0 + 4 + 2*2 = A0+8.
    lea     0x00131000, %a0
    lea     0x00131008, %a6
    move.l  #0xFEEDBABE, (%a6)
    moveq   #2, %d3
    move.l  #0xFEEDBABE, %d0
    .word   0xb0b0, 0x3204       | cmp.l (4,A0,D3.W*2),D0
    bne     _fail

    | CMP.L scale x4 long-index — D1 > src (so D1-src > 0, N=0, Z=0, C=0).
    | EA = A1 + 0 + 5*4 = A1+20.
    lea     0x00131100, %a1
    lea     0x00131114, %a6
    move.l  #0x00000010, (%a6)
    movea.l #5, %a2
    move.l  #0x00000020, %d1
    .word   0xb2b1, 0xac00       | cmp.l (0,A1,A2.L*4),D1
    bcs     _fail
    beq     _fail
    bmi     _fail

    | CMP.L scale x8 long-index — D2 < src (N=1, C=1).
    | EA = A3 + 0 + 2*8 = A3+16.
    lea     0x00131200, %a3
    lea     0x00131210, %a6
    move.l  #0x80000000, (%a6)
    moveq   #2, %d4
    move.l  #0x10000000, %d2
    .word   0xb4b3, 0x4e00       | cmp.l (0,A3,D4.L*8),D2
    bcc     _fail
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
