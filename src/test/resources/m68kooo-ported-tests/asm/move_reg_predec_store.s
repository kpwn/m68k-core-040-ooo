| move_reg_predec_store.s -- MOVE register source to predecrement destination
|
| Covers the Q700 ROM frontier:
|   40804236: 3f02  move.w D2,-(A7)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Exact ROM shape: MOVE.W D2,-(A7), with normal word predecrement.
    lea     0x00001400, %sp
    move.l  #0x12348001, %d2
    .word   0x3f02              | move.w %d2,-(%sp)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  %sp, %d0
    cmp.l   #0x000013fe, %d0
    bne     _fail
    move.w  (%sp), %d0
    cmp.w   #0x8001, %d0
    bne     _fail

    | Word An source is legal and stores the low word.
    lea     0x00001420, %a2
    lea     0x12345678, %a3
    move.w  %a3, -(%a2)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a2, %d0
    cmp.l   #0x0000141e, %d0
    bne     _fail
    move.w  (%a2), %d1
    cmp.w   #0x5678, %d1
    bne     _fail

    | Zero word result sets Z and clears N/V/C.
    lea     0x00001440, %a2
    moveq   #0, %d4
    move.w  %d4, -(%a2)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.w  (%a2), %d1
    cmp.w   #0x0000, %d1
    bne     _fail

    | Byte predecrement through a non-stack address register decrements by one.
    lea     0x00001460, %a2
    move.l  #0x00000080, %d3
    move.b  %d3, -(%a2)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a2, %d0
    cmp.l   #0x0000145f, %d0
    bne     _fail
    move.b  (%a2), %d1
    cmp.b   #0x80, %d1
    bne     _fail

    | Byte predecrement through A7 uses the 68k stack-pointer +2 rule.
    lea     0x00001480, %sp
    move.l  #0x0000007f, %d5
    move.b  %d5, -(%sp)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  %sp, %d0
    cmp.l   #0x0000147e, %d0
    bne     _fail
    move.b  (%sp), %d1
    cmp.b   #0x7f, %d1
    bne     _fail

    | Existing long direct-source predecrement path remains covered.
    lea     0x000014a0, %a2
    move.l  #0x80000000, %d6
    move.l  %d6, -(%a2)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a2, %d0
    cmp.l   #0x0000149c, %d0
    bne     _fail
    move.l  (%a2), %d1
    cmp.l   #0x80000000, %d1
    bne     _fail

_pass:
    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a6)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a6
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a6)
_fail_halt:
    bra     _fail_halt
