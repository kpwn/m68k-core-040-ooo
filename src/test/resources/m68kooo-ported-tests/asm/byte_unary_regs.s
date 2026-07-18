| byte_unary_regs.s -- byte unary ops on data registers
|
| Covers the byte-sized unary register forms that preserve the upper
| 24 bits while modifying only the low byte:
|   - CLR.B
|   - NOT.B
|   - NEG.B
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    move.l  #0x123456a5, %d0
    clr.b   %d0
    cmp.l   #0x12345600, %d0
    bne     _fail

    move.l  #0x89abcd10, %d1
    not.b   %d1
    cmp.l   #0x89abcdef, %d1
    bne     _fail

    move.l  #0x00000080, %d2
    neg.b   %d2
    cmp.l   #0x00000080, %d2
    bne     _fail

    move.l  #0x12345601, %d3
    neg.b   %d3
    cmp.l   #0x123456ff, %d3
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
    bra     _fail
