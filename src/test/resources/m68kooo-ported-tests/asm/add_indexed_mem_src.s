| add_indexed_mem_src.s -- ADD.{B,W,L} (d8,An,Xn),Dn source operands
|
| Covers brief-indexed ADD memory sources across byte, word, and long
| widths with word/long indexes and scaled indexes.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Word operand, word data-register index, scale x2.
    lea     0x00124000, %a0
    move.l  #0x00030000, 4(%a0)
    moveq   #2, %d4
    move.l  #0xabcd7ffe, %d1
    .word   0xd270, 0x4200       | add.w (0,A0,D4.W*2),D1
    bpl     _fail
    beq     _fail
    bvc     _fail
    bcs     _fail
    cmp.l   #0xabcd8001, %d1
    bne     _fail

    | Byte operand, long data-register index, scale x4.
    lea     0x00124100, %a1
    move.l  #0x00000001, 4(%a1)
    moveq   #1, %d5
    move.l  #0x1234567f, %d2
    .word   0xd431, 0x5c03       | add.b (3,A1,D5.L*4),D2
    bpl     _fail
    beq     _fail
    bvc     _fail
    bcs     _fail
    cmp.l   #0x12345680, %d2
    bne     _fail

    | Long operand, long address-register index, scale x8, negative disp8.
    lea     0x00124200, %a2
    move.l  #0x00000001, 12(%a2)
    movea.l #2, %a3
    move.l  #0x7fffffff, %d0
    .word   0xd0b2, 0xbefc       | add.l (-4,A2,A3.L*8),D0
    bpl     _fail
    beq     _fail
    bvc     _fail
    bcs     _fail
    cmp.l   #0x80000000, %d0
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
