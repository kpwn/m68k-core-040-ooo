| raw_chain.s — Long Read-After-Write dependency chain
|
| Builds a 7-deep chain D1=D0+1, D2=D1+1, ... D7=D6+1.
| Starting from D0=1, D7 must equal 8.
|
| Stresses the rename + CDB wakeup path: every uop's source is the
| previous uop's destination, so the issue queue must wake each one
| exactly when the prior result broadcasts. A bypass bug (wrong tag,
| missed wake) shows up as a wrong final value or a hang.

    .text
    .org 0

_start:
    moveq   #1, %d0
    move.l  %d0, %d1
    addq.l  #1, %d1             | D1 = 2
    move.l  %d1, %d2
    addq.l  #1, %d2             | D2 = 3
    move.l  %d2, %d3
    addq.l  #1, %d3             | D3 = 4
    move.l  %d3, %d4
    addq.l  #1, %d4             | D4 = 5
    move.l  %d4, %d5
    addq.l  #1, %d5             | D5 = 6
    move.l  %d5, %d6
    addq.l  #1, %d6             | D6 = 7
    move.l  %d6, %d7
    addq.l  #1, %d7             | D7 = 8

    | verify D7 == 8
    moveq   #8, %d0
    cmp.l   %d0, %d7
    bne     _fail

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
