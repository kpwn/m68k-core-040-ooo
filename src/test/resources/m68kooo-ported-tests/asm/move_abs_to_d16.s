| move_abs_to_d16.s -- MOVE.{B,W,L} absolute source to d16(An) destination
|
| Covers the ROM frontier:
|   4081b720: 1d78 0ba4 ffe9  move.b 0x0ba4.W,-23(A6)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Exact byte abs.W source to displaced destination.  Destination is
    | A6-23 so the opword/extension triplet matches the ROM frontier.
    lea     0x00000ba4, %a0
    move.b  #0x82, (%a0)
    lea     0x00107017, %a6
    move.l  #0x11223344, -23(%a6)
    .word   0x1d78, 0x0ba4, 0xffe9
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  -23(%a6), %d0
    cmp.l   #0x82223344, %d0
    bne     _fail

    | Word abs.W source to displaced destination.
    lea     0x00000bac, %a0
    move.w  #0x8001, (%a0)
    lea     0x00107120, %a5
    move.l  #0x12345678, -32(%a5)
    .word   0x3b78, 0x0bac, 0xffe0
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  -32(%a5), %d0
    cmp.l   #0x80015678, %d0
    bne     _fail

    | Long abs.W source to displaced destination.
    lea     0x00000bb0, %a0
    move.l  #0x01020304, (%a0)
    lea     0x00107210, %a4
    move.l  #0xaaaaaaaa, 16(%a4)
    .word   0x2978, 0x0bb0, 0x0010
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  16(%a4), %d0
    cmp.l   #0x01020304, %d0
    bne     _fail

    | Byte/word/long abs.L source siblings, since the source extension
    | size changes where the destination displacement lives.
    lea     0x00107300, %a0
    move.b  #0x7f, (%a0)
    lea     0x00107408, %a1
    move.l  #0xaaaaaaaa, 4(%a1)
    .word   0x1379
    .long   0x00107300
    .word   0x0004
    bmi     _fail
    beq     _fail
    move.l  4(%a1), %d0
    cmp.l   #0x7faaaaaa, %d0
    bne     _fail

    lea     0x00107310, %a0
    move.w  #0x0000, (%a0)
    lea     0x00107420, %a2
    move.l  #0xffffffff, -4(%a2)
    .word   0x3579
    .long   0x00107310
    .word   0xfffc
    bne     _fail
    bmi     _fail
    move.l  -4(%a2), %d0
    cmp.l   #0x0000ffff, %d0
    bne     _fail

    lea     0x00107320, %a0
    move.l  #0x87654321, (%a0)
    lea     0x00107440, %a3
    move.l  #0xaaaaaaaa, 12(%a3)
    .word   0x2779
    .long   0x00107320
    .word   0x000c
    bpl     _fail
    beq     _fail
    move.l  12(%a3), %d0
    cmp.l   #0x87654321, %d0
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
