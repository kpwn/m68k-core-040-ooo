| bit_static_indexed_mem.s -- BTST/BCHG/BCLR/BSET #imm,(d8,An,Xn)
|
| Covers the ROM frontier form:
|   4088be8c: 0833 0003 1000  btst #3,(0,A3,D1.W)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x0011a000, %a3

    | BTST #3,(0,A3,D1.W): old bit set -> Z clear.
    move.l  #0x00000004, %d1
    lea     0x0011a004, %a0
    move.l  #0x08000000, (%a0)
    .word   0x0833, 0x0003, 0x1000
    beq     _fail1

    | BTST #2,(0,A3,D1.W): old bit clear -> Z set.
    .word   0x0833, 0x0002, 0x1000
    bne     _fail2

    | BCLR #7,(0,A3,D1.W): clears the old set bit and reports Z clear.
    move.l  #0xff000000, (%a0)
    .word   0x08b3, 0x0007, 0x1000
    beq     _fail3
    move.l  0x0011a004, %d7
    cmp.l   #0x7f000000, %d7
    bne     _fail3

    | BSET #2,(0,A3,D1.W): sets the old clear bit and reports Z set.
    move.l  #0x00000000, (%a0)
    .word   0x08f3, 0x0002, 0x1000
    bne     _fail4
    move.l  0x0011a004, %d7
    cmp.l   #0x04000000, %d7
    bne     _fail4

    | BCHG #0,(0,A3,D1.W): toggles the old set bit and reports Z clear.
    move.l  #0x01000000, (%a0)
    .word   0x0873, 0x0000, 0x1000
    beq     _fail5
    move.l  0x0011a004, %d7
    cmp.l   #0x00000000, %d7
    bne     _fail5

    | BTST with long index scaled by two: A3 + 6 + D2.L*2.
    move.l  #0x00000003, %d2
    lea     0x0011a00c, %a0
    move.l  #0x10000000, (%a0)
    .word   0x0833, 0x0004, 0x2a06
    beq     _fail6

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d7
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d7
    bra     _fail
_fail5:
    move.l  #0xDEAD0005, %d7
    bra     _fail
_fail6:
    move.l  #0xDEAD0006, %d7

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
