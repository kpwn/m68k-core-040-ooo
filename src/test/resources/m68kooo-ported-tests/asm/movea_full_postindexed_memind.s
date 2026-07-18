| movea_full_postindexed_memind.s -- MOVEA.L full-format postindexed memory-indirect source
|
| Covers the Q700 ROM frontier:
|   40806e1c: 2270 05a5 0db8  movea.l @($0db8)@(0,D0.W*4),A1

    .text
    .org 0

_start:
    | Exact ROM dispatch-table shape: base suppressed, bd.W points to a
    | pointer slot, D0.W is scaled by four after the pointer load.
    lea     0x00000db8, %a0
    move.l  #0x00124000, (%a0)
    lea     0x00124000, %a2
    move.l  #0x00234567, 0x00bc(%a2)
    move.l  #0x0000002f, %d0
    moveq   #0, %d6
    tst.l   %d6

    .word   0x2270, 0x05a5, 0x0db8
    bne     _fail1              | MOVEA must preserve CCR
    cmpa.l  #0x00234567, %a1
    bne     _fail1

    | Sign-extension sibling: D0.W=-1, scale=4, load from pointer-4.
    lea     0x00000dbc, %a0
    move.l  #0x00125004, (%a0)
    lea     0x00125000, %a2
    move.l  #0x00345678, (%a2)
    move.l  #0x0000ffff, %d0
    moveq   #0, %d6
    tst.l   %d6

    .word   0x2270, 0x05a5, 0x0dbc
    bne     _fail2
    cmpa.l  #0x00345678, %a1
    bne     _fail2

_pass:
    lea     0xffff0000, %a0
    move.l  #0xc0ffee00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xdead0001, %d7
    bra     _fail
_fail2:
    move.l  #0xdead0002, %d7

_fail:
    lea     0xffff0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
