| movel_mem_predec_src.s -- MOVE.L -(An),Dn source predecrement
|
| Covers the Q700 ROM frontier 2620 = move.l -(A0),D3 and the A7 long
| predecrement step.  MOVE.L sets NZVC from the loaded long and preserves X.

    .text
    .org 0

_start:
    lea     0x00113200, %a1
    move.l  #0x11223344, (%a1)
    lea     0x00113204, %a0
    moveq   #0, %d3
    .word   0x2620              | move.l -(%a0),%d3
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00113200, %a0
    bne     _fail
    cmp.l   #0x11223344, %d3
    bne     _fail

    lea     0x00113300, %a2
    move.l  #0x80000000, (%a2)
    lea     0x00113304, %a7
    moveq   #0, %d4
    .word   0x2827              | move.l -(%a7),%d4
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00113300, %a7
    bne     _fail
    cmp.l   #0x80000000, %d4
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
