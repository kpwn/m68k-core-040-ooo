| addq_subq_word_reg.s -- ADDQ.W/SUBQ.W quick arithmetic on Dn
|
| Covers the ROM stop at 0x4084722c:
|   5243    addq.w #1, %d3
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | ROM-shaped increment; high word is preserved.
    move.l  #0x007c00fc, %d3
    addq.w  #1, %d3
    cmp.l   #0x007c00fd, %d3
    bne     _fail

    | 0 encodes as 8 for word-sized Dn quick ops.
    move.l  #0x12340000, %d0
    addq.w  #8, %d0
    cmp.l   #0x12340008, %d0
    bne     _fail

    | Word overflow wraps low word, preserves high word, and sets Z.
    move.l  #0xfeedffff, %d1
    addq.w  #1, %d1
    bne     _fail
    cmp.l   #0xfeed0000, %d1
    bne     _fail

    | SUBQ.W mirrors the Dn path and preserves the high word.
    move.l  #0xabcd0003, %d2
    subq.w  #4, %d2
    bpl     _fail
    cmp.l   #0xabcdffff, %d2
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
