| prm_addx_l_zero_chain.s — ADDX.L X-chain and sticky Z behavior.
|
| Spec: M68000 PRM, integer instruction reference, ADDX.
| ADDX adds source + destination + X.  X and C are set from carry-out;
| Z is cleared when the result is non-zero and otherwise left unchanged,
| allowing multi-precision zero detection across a chain.

    .text
    .org 0

_start:
    move.l  #0xffffffff, %d0
    move.l  #1, %d1
    add.l   %d1, %d0            | result 0, X=C=1, Z=1
    bne     _fail1
    bcc     _fail1

    move.l  #0xffffffff, %d2
    moveq   #0, %d3
    addx.l  %d3, %d2            | -1 + 0 + X = 0, X=C=1, Z remains 1
    bne     _fail2
    bcc     _fail2
    cmp.l   #0, %d2
    bne     _fail2

    moveq   #0, %d4
    moveq   #0, %d5
    addx.l  %d5, %d4            | 0 + 0 + X = 1, X=C=0, Z cleared
    beq     _fail3
    bcs     _fail3
    cmp.l   #1, %d4
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
