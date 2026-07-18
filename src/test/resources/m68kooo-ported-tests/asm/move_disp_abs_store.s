| move_disp_abs_store.s -- MOVE.{B,W,L} (d16,An),(xxx).{W,L}.
|
| Covers the Q700 ROM frontier:
|   4088d204: 21e8 002c 1fc8  move.l 44(%a0),0x1fc8.W
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Exact ROM long source-displacement to absolute-short destination.
    lea     0x00109000, %a0
    move.l  #0xa5a55a5a, %d0
    move.l  %d0, 44(%a0)
    .word   0x21e8, 0x002c, 0x1fc8  | move.l 44(%a0),0x1fc8.W
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    .word   0x2038, 0x1fc8          | move.l 0x1fc8.W,%d0
    cmp.l   #0xa5a55a5a, %d0
    bne     _fail

    | Long source-displacement to absolute-long destination.
    lea     0x00109104, %a1
    move.l  #0x00000000, %d1
    move.l  %d1, -4(%a1)
    .word   0x23e9, 0xfffc, 0x0010, 0x8800  | move.l -4(%a1),0x00108800.L
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    .word   0x2439, 0x0010, 0x8800  | move.l 0x00108800.L,%d2
    cmp.l   #0, %d2
    bne     _fail

    | Word source-displacement to absolute-short destination.
    lea     0x00109200, %a2
    move.l  #0x00008001, %d2
    move.w  %d2, 6(%a2)
    .word   0x31ea, 0x0006, 0x1fcc  | move.w 6(%a2),0x1fcc.W
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  #0, %d3
    .word   0x3638, 0x1fcc          | move.w 0x1fcc.W,%d3
    cmp.w   #0x8001, %d3
    bne     _fail

    | Word source-displacement to absolute-long destination.
    lea     0x00109300, %a3
    move.l  #0x0000007f, %d3
    move.w  %d3, -2(%a3)
    .word   0x33eb, 0xfffe, 0x0010, 0x8804  | move.w -2(%a3),0x00108804.L
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  #0, %d4
    .word   0x3839, 0x0010, 0x8804  | move.w 0x00108804.L,%d4
    cmp.w   #0x007f, %d4
    bne     _fail

    | Byte source-displacement to absolute-short destination.
    lea     0x00109400, %a4
    move.l  #0x00000080, %d4
    move.b  %d4, 7(%a4)
    .word   0x11ec, 0x0007, 0x1fce  | move.b 7(%a4),0x1fce.W
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  #0, %d5
    .word   0x1a38, 0x1fce          | move.b 0x1fce.W,%d5
    cmp.b   #0x80, %d5
    bne     _fail

    | Byte source-displacement to absolute-long destination.
    lea     0x00109500, %a5
    move.l  #0x00000000, %d5
    move.b  %d5, -1(%a5)
    .word   0x13ed, 0xffff, 0x0010, 0x8806  | move.b -1(%a5),0x00108806.L
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  #0xffffffff, %d6
    .word   0x1c39, 0x0010, 0x8806  | move.b 0x00108806.L,%d6
    cmp.b   #0, %d6
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
    bra     _pass

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
    bra     _fail
