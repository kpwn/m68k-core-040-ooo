| and_indexed_mem_src.s -- AND.{B,W,L} (d8,An,Xn),Dn source operands
|
| Covers the Q700 ROM frontier c270 4200 = and.w (0,A0,D4.W*2),D1 and
| the widened brief-indexed source family across operand sizes, index
| sizes, and scales.

    .text
    .org 0

_start:
    | Exact frontier shape: AND.W (0,A0,D4.W*2),D1.
    lea     0x00113400, %a0
    lea     0x00113404, %a6
    move.l  #0x0ff00000, (%a6)
    moveq   #2, %d4
    move.l  #0xffff00ff, %d1
    .word   0xc270, 0x4200
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0xffff00f0, %d1
    bne     _fail

    | Byte operand, long data-register index, scale x4.
    lea     0x00113500, %a1
    lea     0x00113504, %a6
    move.l  #0x00000080, (%a6)
    moveq   #1, %d5
    move.l  #0x123456f0, %d2
    .word   0xc431, 0x5c03       | and.b (3,A1,D5.L*4),D2
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x12345680, %d2
    bne     _fail

    | Long operand, long address-register index, scale x8, negative disp8.
    lea     0x00113600, %a2
    lea     0x0011360c, %a6
    move.l  #0x00ff00ff, (%a6)
    movea.l #2, %a3
    move.l  #0xffffffff, %d0
    .word   0xc0b2, 0xbefc       | and.l (-4,A2,A3.L*8),D0
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x00ff00ff, %d0
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
