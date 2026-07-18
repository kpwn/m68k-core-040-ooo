| prm_move_memdest_sets_ccr.s — MOVE to memory sets N/Z and clears V/C.
|
| Spec: M68000 PRM, integer instruction reference, MOVE condition codes.
| MOVE sets N and Z from the moved operand and always clears V and C,
| regardless of destination addressing mode.

    .text
    .org 0

_start:
    lea     0x00000800, %a0

    move.l  #0x7fffffff, %d0
    addq.l  #1, %d0             | V=1, C=0, N=1
    bvc     _fail1

    moveq   #0, %d1
    move.l  %d1, (%a0)          | Z=1, N=0, V=0, C=0
    bne     _fail2
    bmi     _fail2
    bvs     _fail2
    bcs     _fail2
    cmp.l   #0, (%a0)
    bne     _fail2

    move.l  #0x80000000, %d1
    move.l  %d1, (%a0)          | N=1, Z=0, V=0, C=0
    bpl     _fail3
    beq     _fail3
    bvs     _fail3
    bcs     _fail3

_pass:
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a1)
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
    lea     0xFFFF0000, %a1
    move.l  %d7, (%a1)
_halt_fail:
    bra     _halt_fail
