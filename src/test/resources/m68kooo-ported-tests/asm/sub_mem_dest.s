| sub_mem_dest.s -- SUB.{B,W,L} Dn,<memory> read-modify-write forms
|
| Covers the ROM frontier:
|   40887a24: 9191  sub.l %d0,(%a1)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Long direct memory destination: SUB.L D0,(A1), opcode 0x9191.
    lea     0x00106000, %a1
    move.l  #0x00000020, (%a1)
    move.l  #0x00000005, %d0
    .word   0x9191
    beq     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a1), %d7
    cmp.l   #0x0000001b, %d7
    bne     _fail

    | Byte borrow path preserves untouched bytes and sets N/C/X.
    lea     0x00106100, %a0
    move.l  #0x01020304, (%a0)
    moveq   #2, %d1
    sub.b   %d1, (%a0)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcc     _fail
    move.l  (%a0), %d7
    cmp.l   #0xff020304, %d7
    bne     _fail

    | Word postincrement uses the original address, then increments by 2.
    lea     0x00106200, %a2
    move.l  #0x0010aaaa, (%a2)
    moveq   #1, %d2
    sub.w   %d2, (%a2)+
    cmpa.l  #0x00106202, %a2
    bne     _fail
    move.l  0x00106200, %d7
    cmp.l   #0x000faaaa, %d7
    bne     _fail

    | Long predecrement updates An before the memory RMW.
    lea     0x00106304, %a3
    move.l  #0x00000010, 0x00106300
    moveq   #3, %d3
    sub.l   %d3, -(%a3)
    cmpa.l  #0x00106300, %a3
    bne     _fail
    move.l  (%a3), %d7
    cmp.l   #0x0000000d, %d7
    bne     _fail

    | Existing displacement shape remains covered while widening siblings.
    lea     0x00106400, %a4
    move.l  #0x00000040, 8(%a4)
    moveq   #6, %d4
    sub.l   %d4, 8(%a4)
    move.l  8(%a4), %d7
    cmp.l   #0x0000003a, %d7
    bne     _fail

    | Absolute long destination.
    lea     0x00106500, %a6
    move.l  #0x00000030, (%a6)
    moveq   #5, %d5
    .word   0x9bb9
    .long   0x00106500
    move.l  (%a6), %d7
    cmp.l   #0x0000002b, %d7
    bne     _fail

    | Absolute word destination.
    lea     0x00006600, %a6
    move.l  #0x0010bbbb, (%a6)
    moveq   #1, %d6
    .word   0x9d78, 0x6600
    move.l  (%a6), %d7
    cmp.l   #0x000fbbbb, %d7
    bne     _fail

    | Brief indexed destination: SUB.L D1,(4,A5,D0.W).
    lea     0x00106600, %a5
    move.l  #0x00000020, 8(%a5)
    moveq   #4, %d0
    moveq   #2, %d1
    .word   0x93b5, 0x0004
    move.l  8(%a5), %d7
    cmp.l   #0x0000001e, %d7
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
