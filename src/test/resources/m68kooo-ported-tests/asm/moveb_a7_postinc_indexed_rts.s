| moveb_a7_postinc_indexed_rts.s -- ROM SCC byte-stack helper shape
|
| Covers the Q700 ROM sequence around:
|   40847956: 1f13       move.b (%a3),-(%a7)
|   40847994: 179f 3800  move.b (%a7)+,(0,%a3,%d3.l)
|   4084799a: 4e75       rts
|
| The byte predecrement/postincrement A7 steps must preserve the BSR return
| longword, and RTS must redirect to the caller rather than falling through to
| the following BSR-shaped instruction.

    .text
    .org 0

_start:
    lea     0x00108000, %a7
    lea     0x00109000, %a3
    moveq   #4, %d3
    moveq   #0, %d6

    move.l  #0x55000000, (%a3)
    move.l  #0xaaaaaaaa, 4(%a3)

    bsr     _rom_shape

_returned:
    move.l  %a7, %d0
    cmp.l   #0x00108000, %d0
    bne     _fail

    move.l  4(%a3), %d1
    cmp.l   #0x55aaaaaa, %d1
    bne     _fail

    move.l  #0xC0FFEE00, %d0
    move.l  %d0, 0xFFFF0000
_halt:
    bra     _halt

_rom_shape:
    move.b  (%a3), -(%a7)
    move.b  (%a7)+, (0,%a3,%d3.l)
    tst.l   %d6
    rts

    | If the RTS above falls through, this BSR-like instruction will execute.
    .word   0x6100, 0xff00

_fail:
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, 0xFFFF0000
_fail_halt:
    bra     _fail_halt
