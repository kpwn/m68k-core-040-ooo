| move_imm_predec.s -- MOVE.{B,W,L} #imm,-(An).

    .text
    .org 0

_start:
    | Byte immediate to non-A7 predecrements by one.
    lea     0x00108501, %a1
    move.l  #0xaaaaaaaa, -1(%a1)
    .word   0x133c, 0x0080      | move.b #0x80,-(%a1)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a1), %d0
    cmp.l   #0x80aaaaaa, %d0
    bne     _fail

    | Byte immediate to A7 predecrements by two and sets Z for zero.
    lea     0x00108512, %a7
    move.l  #0xbbbbbbbb, -2(%a7)
    .word   0x1f3c, 0x0000      | move.b #0,-(%a7)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a7), %d1
    cmp.l   #0x00bbbbbb, %d1
    bne     _fail

    | Word immediate predecrements by two.
    lea     0x00108522, %a2
    move.l  #0xcccccccc, -2(%a2)
    .word   0x353c, 0x8001      | move.w #0x8001,-(%a2)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a2), %d2
    cmp.l   #0x8001cccc, %d2
    bne     _fail

    | Word immediate through A7 uses the normal two-byte word step.
    lea     0x00108542, %a7
    move.l  #0xeeeeeeee, -2(%a7)
    .word   0x3f3c, 0x7fff      | move.w #0x7fff,-(%a7)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a7), %d6
    cmp.l   #0x7fffeeee, %d6
    bne     _fail
    move.l  %a7, %d7
    cmp.l   #0x00108540, %d7
    bne     _fail

    | Long immediate predecrements by four.
    lea     0x00108534, %a3
    move.l  #0xdddddddd, -4(%a3)
    .word   0x273c, 0x1234, 0x5678  | move.l #0x12345678,-(%a3)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a3), %d3
    cmp.l   #0x12345678, %d3
    bne     _fail

    | Long immediate through A7 predecrements by four.
    lea     0x00108564, %a7
    move.l  #0xeeeeeeee, -4(%a7)
    .word   0x2f3c, 0x8000, 0x0000  | move.l #0x80000000,-(%a7)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a7), %d4
    cmp.l   #0x80000000, %d4
    bne     _fail
    move.l  %a7, %d5
    cmp.l   #0x00108560, %d5
    bne     _fail

_pass:
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, 0xFFFF0000
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, 0xFFFF0000
_fail_halt:
    bra     _fail_halt
