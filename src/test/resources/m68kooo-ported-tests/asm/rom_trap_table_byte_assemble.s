| rom_trap_table_byte_assemble.s -- Q700 trap-table byte decoder idiom
|
| The Q700 ROM builds trap-table offsets with:
|   move.b (a0)+,d1
|   bmi    special
|   lsl.w  #8,d1
|   move.b (a0)+,d1
|   add.w  d1,d1
|
| This depends on sequential partial-Dn writes and word-sized shifts/adds
| preserving exactly the architectural bits.  A failure here can make the
| ROM see an early zero terminator and skip the low OS trap table.

    .text
    .org 0

_start:
    lea     _table, %a0
    move.l  #0xa5a55a5a, %d1

    move.b  (%a0)+, %d1
    bmi     _fail1
    lsl.w   #8, %d1
    move.b  (%a0)+, %d1
    add.w   %d1, %d1

    | ROM takes the BEQ terminator here only if the assembled word is zero.
    beq     _fail2
    cmpi.w  #0x0246, %d1
    bne     _fail3
    swap    %d1
    cmpi.w  #0xa5a5, %d1
    bne     _fail3
    swap    %d1

    move.b  (%a0)+, %d1
    bmi     _special
    bra     _fail4
_special:
    and.w   #0x007f, %d1
    cmpi.w  #0x007f, %d1
    bne     _fail5

_pass:
    lea     0xffff0000, %a1
    move.l  #0xc0ffee00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_fail1:
    move.l  #0xdead0001, %d0
    bra     _fail
_fail2:
    move.l  #0xdead0002, %d0
    bra     _fail
_fail3:
    move.l  #0xdead0003, %d0
    bra     _fail
_fail4:
    move.l  #0xdead0004, %d0
    bra     _fail
_fail5:
    move.l  #0xdead0005, %d0
    bra     _fail

_fail:
    lea     0xffff0000, %a1
    move.l  %d0, (%a1)
_fail_halt:
    bra     _fail_halt

_table:
    .byte   0x01, 0x23, 0xff
    .align 2
