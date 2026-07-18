| andi_mem_disp.s -- ANDI.B/W/L #imm,(d16,An) ROM-path regression
|
| Covers the Q700 ROM shape:
|   022a 001f 1600    andi.b #0x1f, 0x1600(%a2)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    move.l  #0x00100000, %a2

    | Exact ROM byte/displacement shape, plus neighboring-byte preservation.
    move.l  #0xBF00CCF0, 0x00101600
    andi.b  #0x1f, 0x1600(%a2)
    beq     _fail1
    bmi     _fail1
    move.l  0x00101600, %d0
    cmp.l   #0x1F00CCF0, %d0
    bne     _fail1

    | Zero-result byte flags.
    move.l  #0xE0000000, 0x00101600
    andi.b  #0x1f, 0x1600(%a2)
    bne     _fail2
    move.l  0x00101600, %d1
    cmp.l   #0x00000000, %d1
    bne     _fail2

    | Word sibling uses the same d16 extension layout as byte.
    move.l  #0xABCD1234, 0x00101604
    andi.w  #0x0f0f, 0x1604(%a2)
    beq     _fail3
    bmi     _fail3
    move.l  0x00101604, %d2
    cmp.l   #0x0B0D1234, %d2
    bne     _fail3

    | Long sibling places the d16 extension after the imm32.
    move.l  #0xF0F00F0F, 0x00101608
    andi.l  #0x0FF0FFFF, 0x1608(%a2)
    beq     _fail4
    bmi     _fail4
    move.l  0x00101608, %d3
    cmp.l   #0x00F00F0F, %d3
    bne     _fail4

    | Zero-result long flags and storeback.
    move.l  #0x12345678, 0x0010160c
    andi.l  #0x00000000, 0x160c(%a2)
    bne     _fail5
    move.l  0x0010160c, %d4
    cmp.l   #0x00000000, %d4
    bne     _fail5

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

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
