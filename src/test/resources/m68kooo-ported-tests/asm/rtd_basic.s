| rtd_basic.s -- RTD #disp16 return and stack deallocation
|
| Covers the Q700 ROM frontier:
|   408099ec: 4e74 0004  rtd #4
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | RTD #4 pops the return PC and discards one longword argument.
    move.l  #0xfeedface, -(%a7)
    bsr     _sub_discard_arg
_after_discard:
    cmp.l   #0x12345678, %d0
    bne     _fail1
    cmpa.l  #0x00010000, %a7
    bne     _fail1

    | RTD #0 behaves like RTS for SP, leaving the caller's argument.
    move.l  #0xabcdef01, -(%a7)
    bsr     _sub_keep_arg
_after_keep:
    cmp.l   #0x87654321, %d1
    bne     _fail2
    cmpa.l  #0x0000fffc, %a7
    bne     _fail2
    move.l  (%a7)+, %d2
    cmp.l   #0xabcdef01, %d2
    bne     _fail2
    cmpa.l  #0x00010000, %a7
    bne     _fail2

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_sub_discard_arg:
    move.l  #0x12345678, %d0
    .word   0x4e74, 0x0004

_sub_keep_arg:
    move.l  #0x87654321, %d1
    .word   0x4e74, 0x0000

_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d7

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
