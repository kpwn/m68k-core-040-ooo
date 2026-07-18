| move_abs_mem_to_abs.s -- MOVE.{B,W,L} absolute memory to absolute memory
|
| Covers the Q700 ROM frontier:
|   40804132: 21f8 0c08 0c04  move.l 0x0c08.W,0x0c04.W
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | MOVE.L (xxx).W,(xxx).W, exact ROM shape.
    lea     0x00000c08, %a0
    lea     0x00000c04, %a1
    move.l  #0x50f0f000, (%a0)
    move.l  #0x00000000, (%a1)

    .word   0x21f8, 0x0c08, 0x0c04
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.l  (%a1), %d0
    cmp.l   #0x50f0f000, %d0
    bne     _fail

    | MOVE.L (xxx).W,(xxx).L.
    lea     0x00000c10, %a0
    lea     0x00118000, %a1
    move.l  #0x89abcdef, (%a0)
    move.l  #0x00000000, (%a1)

    .word   0x23f8, 0x0c10, 0x0011, 0x8000
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.l  (%a1), %d1
    cmp.l   #0x89abcdef, %d1
    bne     _fail

    | MOVE.L (xxx).L,(xxx).W.
    lea     0x00118010, %a0
    lea     0x00000c14, %a1
    move.l  #0x00000000, (%a0)
    move.l  #0xffffffff, (%a1)

    .word   0x21f9, 0x0011, 0x8010, 0x0c14
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.l  (%a1), %d2
    cmp.l   #0x00000000, %d2
    bne     _fail

    | MOVE.L (xxx).L,(xxx).L.
    lea     0x00118020, %a0
    lea     0x00118030, %a1
    move.l  #0x12345678, (%a0)
    move.l  #0x00000000, (%a1)

    .word   0x23f9, 0x0011, 0x8020, 0x0011, 0x8030
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.l  (%a1), %d3
    cmp.l   #0x12345678, %d3
    bne     _fail

    | MOVE.W short/long absolute source-destination layouts.
    lea     0x00000c20, %a0
    lea     0x00000c22, %a1
    move.w  #0x8001, (%a0)
    move.w  #0x0000, (%a1)
    .word   0x31f8, 0x0c20, 0x0c22
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.w  (%a1), %d4
    cmp.w   #0x8001, %d4
    bne     _fail

    lea     0x00000c24, %a0
    lea     0x00118040, %a1
    move.w  #0x7fff, (%a0)
    move.w  #0x0000, (%a1)
    .word   0x33f8, 0x0c24, 0x0011, 0x8040
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.w  (%a1), %d5
    cmp.w   #0x7fff, %d5
    bne     _fail

    lea     0x00118050, %a0
    lea     0x00000c28, %a1
    move.w  #0x0000, (%a0)
    move.w  #0xffff, (%a1)
    .word   0x31f9, 0x0011, 0x8050, 0x0c28
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.w  (%a1), %d6
    cmp.w   #0x0000, %d6
    bne     _fail

    lea     0x00118060, %a0
    lea     0x00118070, %a1
    move.w  #0x8000, (%a0)
    move.w  #0x0000, (%a1)
    .word   0x33f9, 0x0011, 0x8060, 0x0011, 0x8070
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.w  (%a1), %d0
    cmp.w   #0x8000, %d0
    bne     _fail

    | MOVE.B short/long absolute source-destination layouts.
    lea     0x00000c40, %a0
    lea     0x00000c42, %a1
    move.b  #0x00, (%a0)
    move.b  #0xff, (%a1)
    .word   0x11f8, 0x0c40, 0x0c42
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.b  (%a1), %d1
    cmp.b   #0x00, %d1
    bne     _fail

    lea     0x00000c44, %a0
    lea     0x00118080, %a1
    move.b  #0x80, (%a0)
    move.b  #0x00, (%a1)
    .word   0x13f8, 0x0c44, 0x0011, 0x8080
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.b  (%a1), %d2
    cmp.b   #0x80, %d2
    bne     _fail

    lea     0x00118090, %a0
    lea     0x00000c46, %a1
    move.b  #0x7f, (%a0)
    move.b  #0x00, (%a1)
    .word   0x11f9, 0x0011, 0x8090, 0x0c46
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.b  (%a1), %d3
    cmp.b   #0x7f, %d3
    bne     _fail

    lea     0x001180a0, %a0
    lea     0x001180b0, %a1
    move.b  #0x00, (%a0)
    move.b  #0xff, (%a1)
    .word   0x13f9, 0x0011, 0x80a0, 0x0011, 0x80b0
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.b  (%a1), %d4
    cmp.b   #0x00, %d4
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
