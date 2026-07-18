| moveb_indexed_dest.s -- MOVE.B to brief indexed destinations
|
| Covers the Q700 ROM table-copy shapes reached by the 16M+ snapshot run:
|   4084ae70: 1780 3802    move.b %d0,(2,%a3,%d3:l)
|   4084ae74: 1799 3802    move.b (%a1)+,(2,%a3,%d3:l)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00107000, %a3
    moveq   #4, %d3

    | Register source to indexed destination: store to A3 + D3 + 2.
    move.l  #0x00000080, %d0
    move.b  %d0, (2,%a3,%d3.l)
    beq     _fail
    bpl     _fail
    bvs     _fail
    bcs     _fail
    nop
    nop
    nop
    nop

    move.b  6(%a3), %d1
    cmpi.b  #0x80, %d1
    bne     _fail

    | Postincrement source to indexed destination.  The source byte is
    | zero so MOVE.B must leave Z=1 and N=0, and A1 must advance by one.
    lea     _src_bytes, %a1
    moveq   #8, %d3

    move.b  (%a1)+, (2,%a3,%d3.l)
    bne     _fail
    bmi     _fail
    nop
    nop
    nop
    nop

    move.b  (%a1)+, (3,%a3,%d3.l)
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    move.b  11(%a3), %d2
    cmpi.b  #0x5a, %d2
    bne     _fail
    bra     _pass

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

    .align 2
_src_bytes:
    .byte   0x00, 0x5a
