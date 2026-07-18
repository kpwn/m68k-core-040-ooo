| roxl_roxr_byte_word.s -- byte/word ROXL/ROXR preserve upper bits and chain X
|
| The Q700 ROM RTC helper shifts command bits with:
|   roxl.b #1,D1
|   roxl.b #1,D3
| A long-only ROXL implementation loses the byte-sized X chain and sends
| zero commands to VIA1/RTC.

    .text
    .org 0

_start:
    | Prime X=1.
    move.b  #0x80, %d0
    lsl.b   #1, %d0

    | ROXL.B pulls X into bit 0 and preserves the upper 24 bits.
    move.l  #0x12345600, %d1
    roxl.b  #1, %d1
    move.l  #0x12345601, %d7
    cmp.l   %d7, %d1
    bne     _fail

    | Prime X=0.
    move.b  #0x00, %d0
    lsl.b   #1, %d0

    | ROXL.B bit 7 becomes X, then the next ROXL.B consumes that X.
    move.l  #0x11111180, %d2
    roxl.b  #1, %d2
    move.l  #0x11111100, %d7
    cmp.l   %d7, %d2
    bne     _fail
    move.l  #0x22222200, %d3
    roxl.b  #1, %d3
    move.l  #0x22222201, %d7
    cmp.l   %d7, %d3
    bne     _fail

    | Prime X=1 and verify ROXR.B inserts it at bit 7.
    move.b  #0x80, %d0
    lsl.b   #1, %d0
    move.l  #0xdeadbe00, %d4
    roxr.b  #1, %d4
    move.l  #0xdeadbe80, %d7
    cmp.l   %d7, %d4
    bne     _fail

    | Prime X=1 and verify ROXL.W preserves the upper 16 bits.
    move.w  #0x8000, %d0
    lsl.w   #1, %d0
    move.l  #0x12340000, %d1
    roxl.w  #1, %d1
    move.l  #0x12340001, %d7
    cmp.l   %d7, %d1
    bne     _fail

    | Prime X=1 and verify ROXR.W inserts it at bit 15.
    move.w  #0x8000, %d0
    lsl.w   #1, %d0
    move.l  #0xabcd0000, %d2
    roxr.w  #1, %d2
    move.l  #0xabcd8000, %d7
    cmp.l   %d7, %d2
    bne     _fail

    | Prime X=0. ROXL.W bit 15 becomes X for the following ROXL.W.
    move.w  #0x0000, %d0
    lsl.w   #1, %d0
    move.l  #0x77778000, %d3
    roxl.w  #1, %d3
    move.l  #0x77770000, %d7
    cmp.l   %d7, %d3
    bne     _fail
    move.l  #0x33330000, %d4
    roxl.w  #1, %d4
    move.l  #0x33330001, %d7
    cmp.l   %d7, %d4
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
