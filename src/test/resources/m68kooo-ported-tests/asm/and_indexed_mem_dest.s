| and_indexed_mem_dest.s -- AND.{B,W,L} Dn,(d8,An,Xn) memory destinations
|
| Covers the brief-indexed read-modify-write family across byte, word,
| and long widths.  The checks verify the written memory value, not the
| transient CCR result.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00120000, %a0
    moveq   #4, %d2

    | Byte form: 0x123456ff & 0x0f -> 0x023456ff.
    move.l  #0x123456ff, 4(%a0)
    move.l  #0x0000000f, %d1
    and.b   %d1, 0(%a0,%d2.w)
    move.l  4(%a0), %d0
    cmp.l   #0x023456ff, %d0
    bne     _fail

    | Word form: 0x1234ffff & 0x00f0 -> 0x0030ffff.
    move.l  #0x1234ffff, 4(%a0)
    move.l  #0x000000f0, %d1
    and.w   %d1, 0(%a0,%d2.w)
    move.l  4(%a0), %d0
    cmp.l   #0x0030ffff, %d0
    bne     _fail

    | Long form: 0xf0f0f0f0 & 0x0ff00ff0 -> 0x00f000f0.
    move.l  #0xf0f0f0f0, 4(%a0)
    move.l  #0x0ff00ff0, %d1
    and.l   %d1, 0(%a0,%d2.w)
    move.l  4(%a0), %d0
    cmp.l   #0x00f000f0, %d0
    bne     _fail

_pass:
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
_halt_fail:
    bra     _halt_fail
