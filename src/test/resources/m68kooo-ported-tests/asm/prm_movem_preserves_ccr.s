| prm_movem_preserves_ccr.s — MOVEM.L leaves CCR unchanged.
|
| Spec: M68000 PRM, integer instruction reference, MOVEM condition codes.
| MOVEM does not affect N, Z, V, C, or X.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    moveq   #0, %d0
    moveq   #0, %d1
    cmp.l   %d0, %d1            | Z=1, C=0, N=0, V=0
    movem.l %d0-%d3/%a0-%a1, -(%a7)
    movem.l (%a7)+, %d0-%d3/%a0-%a1
    bne     _fail1
    bmi     _fail1
    bvs     _fail1
    bcs     _fail1

    moveq   #0, %d0
    moveq   #1, %d1
    sub.l   %d1, %d0            | N=1, Z=0, C=1, X=1, V=0
    movem.l %d0-%d3, -(%a7)
    movem.l (%a7)+, %d0-%d3
    bpl     _fail2
    beq     _fail2
    bcc     _fail2
    bvs     _fail2

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

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
