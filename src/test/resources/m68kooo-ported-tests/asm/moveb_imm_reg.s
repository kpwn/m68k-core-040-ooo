| moveb_imm_reg.s -- MOVE.B #imm8,Dn
|
| Covers the Q700 ROM SCC mismatch path:
|   1c3c 00ff  move.b #$ff,%d6

    .text
    .org 0

_start:
    move.l  #0x12345678, %d6
    .word   0x1c3c, 0x00ff      | move.b #0xff,%d6
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x123456ff, %d6
    bne     _fail

    move.l  #0x89abcdef, %d2
    .word   0x143c, 0x0000      | move.b #0,%d2
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x89abcd00, %d2
    bne     _fail

_pass:
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, 0xFFFF0000
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, 0xFFFF0000
_fail_halt:
    bra     _fail_halt
