| addq_subq_byte_reg.s -- ADDQ.B/SUBQ.B quick arithmetic on Dn
|
| Covers the ROM stop at 0x4080b2d8:
|   5805    addq.b #4, %d5
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | ROM-shaped byte increment; upper 24 bits are preserved and Z is set.
    move.l  #0x123456fc, %d5
    .word   0x5805                | addq.b #4,%d5
    bne     _fail
    cmp.l   #0x12345600, %d5
    bne     _fail

    | 0 encodes as 8 for byte-sized Dn quick ops.
    move.l  #0xabcdef78, %d0
    .word   0x5000                | addq.b #8,%d0
    bpl     _fail
    cmp.l   #0xabcdef80, %d0
    bne     _fail

    | SUBQ.B mirrors the Dn path and preserves the upper 24 bits.
    move.l  #0x0badbe00, %d1
    .word   0x5301                | subq.b #1,%d1
    bpl     _fail
    cmp.l   #0x0badbeff, %d1
    bne     _fail

    | SUBQ.B zero result should set Z.
    move.l  #0xfeed0004, %d2
    .word   0x5902                | subq.b #4,%d2
    bne     _fail
    cmp.l   #0xfeed0000, %d2
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
