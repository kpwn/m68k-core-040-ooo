| rom_probe_ccr_chain.s -- Q700 ROM memory-probe CCR chain.
|
| Mirrors the flag/data sequence around ROM PC 408046b2:
|   ST D1; NEG.B D1; ADD.B D1,D1; NEG.B D1; CMP.B <zero>,D1
|
| At the CMP boundary D1 low byte is 0xFE.  CMP.B #0,D1 must set N,
| clear Z/V/C, and preserve X from the preceding NEG.B.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    lea     _byte_zero, %a0

    move.l  #0x00008009, %d1
    st      %d1                    | D1 = 0x000080ff, CCR unchanged
    neg.b   %d1                    | D1 = 0x00008001, X/C set
    move.b  %d1, (%a0)             | store is CCR-neutral
    add.b   %d1, %d1               | D1 = 0x00008002, X/C clear
    beq     _fail1                 | Z must be clear
    neg.b   %d1                    | D1 = 0x000080fe, X/C/N set
    cmp.b   (%a0), %d1             | FE - 00: N=1 Z=0 V=0 C=0, X preserved

    bpl     _fail2                 | N must be set
    beq     _fail3                 | Z must be clear
    bvs     _fail4                 | V must be clear
    bcs     _fail5                 | C must be clear

    moveq   #0, %d2
    addx.b  %d2, %d2               | consumes preserved X=1 -> D2.B = 1
    cmpi.b  #1, %d2
    bne     _fail6

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d0
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d0
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d0
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d0
    bra     _fail
_fail5:
    move.l  #0xDEAD0005, %d0
    bra     _fail
_fail6:
    move.l  #0xDEAD0006, %d0

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

    .data
    .align 2
_byte_zero:
    .byte   0
