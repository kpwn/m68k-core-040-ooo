| movew_reg_basic.s -- MOVE.W Dm,Dn
|
| Covers the ROM shape at 0x4084721e:
|   3203    move.w %d3, %d1
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Positive word: Z=0, N=0, destination low word is replaced and the
    | upper word is preserved.
    move.l  #0xFFFF1234, %d3
    move.l  #0xAAAAAAAA, %d1
    move.w  %d3, %d1
    beq     _fail
    bmi     _fail
    cmp.l   #0xAAAA1234, %d1
    bne     _fail

    | Negative word: N=1, Z=0, upper word still preserved.
    move.l  #0x13572468, %d1
    move.l  #0x00008001, %d3
    move.w  %d3, %d1
    bpl     _fail
    beq     _fail
    cmp.l   #0x13578001, %d1
    bne     _fail

    | Zero word: Z=1 and only the low word is zeroed.
    move.l  #0xDEADBEEF, %d1
    moveq   #0, %d3
    move.w  %d3, %d1
    bne     _fail
    cmp.l   #0xDEAD0000, %d1
    bne     _fail

    | Immediate word form uses the same partial-register behavior.
    move.l  #0x11223344, %d4
    move.w  #0x8001, %d4
    bpl     _fail
    cmp.l   #0x11228001, %d4
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
