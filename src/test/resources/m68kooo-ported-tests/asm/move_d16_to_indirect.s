| move_d16_to_indirect.s -- MOVE.{B,W,L} (d16,An),(Am)
|
| Covers the ROM frontier:
|   4081ac2e: 34ae 000c  move.w 12(A6),(A2)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Byte displaced source to plain memory destination.
    lea     0x00107500, %a0
    lea     0x00107600, %a1
    move.l  #0x81223344, 8(%a0)
    move.l  #0xaaaaaaaa, (%a1)
    .word   0x12a8, 0x0008       | move.b 8(A0),(A1)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a1), %d0
    cmp.l   #0x81aaaaaa, %d0
    bne     _fail

    | Exact ROM word shape: move.w 12(A6),(A2).
    lea     0x00107700, %a6
    lea     0x00107800, %a2
    move.l  #0x8001ffff, 12(%a6)
    move.l  #0x12345678, (%a2)
    .word   0x34ae, 0x000c
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a2), %d0
    cmp.l   #0x80015678, %d0
    bne     _fail

    | Long sibling was already decoded; keep it covered with the same matrix.
    lea     0x00107900, %a4
    lea     0x00107a00, %a3
    move.l  #0x01020304, -4(%a4)
    move.l  #0xaaaaaaaa, (%a3)
    .word   0x26ac, 0xfffc       | move.l -4(A4),(A3)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a3), %d0
    cmp.l   #0x01020304, %d0
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
