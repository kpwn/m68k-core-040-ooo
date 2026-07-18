| prm_bfextu_reg_mod32.s — register bitfield offset wraps modulo 32.
|
| Spec: M68040 User's Manual, bit-field operations on data registers.
| For register operands, offsets are interpreted modulo 32 and fields
| may wrap around bit 0 back to bit 31.

    .text
    .org 0

_start:
    move.l  #0xA000000B, %d0
    move.l  #60, %d3
    moveq   #8, %d4

    bfextu  %d0{%d3:8}, %d1    | 60 mod 32 = 28, expected 0xBA
    cmp.l   #0x000000BA, %d1
    bne     _fail1

    move.l  #32, %d3
    bfextu  %d0{%d3:4}, %d2    | 32 mod 32 = 0, top nibble = 0xA
    cmp.l   #0x0000000A, %d2
    bne     _fail2

    move.l  #60, %d3
    move.l  #40, %d4           | width 40 maps to effective width 8
    bfextu  %d0{%d3:%d4}, %d5
    cmp.l   #0x000000BA, %d5
    bne     _fail3

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

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
