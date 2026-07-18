| cmpi_byte_reg.s -- CMPI.B #imm8,Dn
|
| Covers the ROM shape at 0x40846c92:
|   0c02 0006    cmpi.b #6, %d2
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Equality case: Z set, D2 preserved.
    move.l  #0x12340006, %d2
    cmpi.b  #6, %d2
    bne     _fail
    cmp.l   #0x12340006, %d2
    bne     _fail

    | 5 - 6 underflows in byte size: C=1, N=1, Z=0.
    moveq   #5, %d2
    cmpi.b  #6, %d2
    bcc     _fail
    bpl     _fail
    beq     _fail

    | 0xff - 1 has no borrow but remains negative in byte size.
    moveq   #-1, %d2
    cmpi.b  #1, %d2
    bcs     _fail
    bpl     _fail
    beq     _fail

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
