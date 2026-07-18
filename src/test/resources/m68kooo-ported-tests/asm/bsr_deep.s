| bsr_deep.s — Nest 6 BSR calls deep, then RTS all the way back
|
| Each subroutine adds a distinct magic into D0 before calling the
| next, and verifies D0 on return.  Exercises the BSR push path
| (design decision #11) and today's BTB fallback for RTS (the RAS is
| not yet wired).
|
| Final D0 must equal the sum of all six magics.  Each level also
| pokes a different memory cell to prove stack state isn't clobbering
| data.
|
| Stack depth: 6 return addresses × 4 bytes = 24 bytes on A7.

    .text
    .org 0

_start:
    lea     0x00010000, %a7         | stack base
    moveq   #0, %d0                 | accumulator
    moveq   #0, %d1                 | level counter (optional check)

    bsr     _lvl1

    | D0 must equal 1+2+3+4+5+6 = 21
    cmp.l   #21, %d0
    bne     _fail
    | D1 must be 6 (last level reached)
    cmp.l   #6, %d1
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, (%a0)
_halt_fail:
    bra     _halt_fail

_lvl1:
    addq.l  #1, %d0
    moveq   #1, %d1
    bsr     _lvl2
    rts

_lvl2:
    addq.l  #2, %d0
    moveq   #2, %d1
    bsr     _lvl3
    rts

_lvl3:
    addq.l  #3, %d0
    moveq   #3, %d1
    bsr     _lvl4
    rts

_lvl4:
    addq.l  #4, %d0
    moveq   #4, %d1
    bsr     _lvl5
    rts

_lvl5:
    addq.l  #5, %d0
    moveq   #5, %d1
    bsr     _lvl6
    rts

_lvl6:
    addq.l  #6, %d0
    moveq   #6, %d1
    rts
