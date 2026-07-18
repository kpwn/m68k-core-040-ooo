| movel_areg_memdest_disp.s -- MOVE.L An to displaced and predecrement destinations
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Displacement destination with a positive address value.
    lea     0x00118000, %a1
    move.l  #0xffffffff, 8(%a1)
    movea.l #0x11223344, %a0
    move.l  %a0, 8(%a1)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  8(%a1), %d0
    cmp.l   #0x11223344, %d0
    bne     _fail

    | Predecrement destination stores before writeback and sets N.
    move.l  #0x00000000, 0x00118020
    lea     0x00118024, %a3
    movea.l #0x80000004, %a2
    move.l  %a2, -(%a3)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00118020, %a3
    bne     _fail
    move.l  0x00118020, %d1
    cmp.l   #0x80000004, %d1
    bne     _fail

    | Displacement destination with zero sets Z.
    lea     0x00118040, %a5
    move.l  #0xffffffff, 4(%a5)
    movea.l #0x00000000, %a4
    move.l  %a4, 4(%a5)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  4(%a5), %d2
    cmp.l   #0x00000000, %d2
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
