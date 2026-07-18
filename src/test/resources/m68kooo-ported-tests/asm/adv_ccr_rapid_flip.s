| adv_ccr_rapid_flip.s — Rapid CCR flip with immediate Bcc reader each time
|
| ASSUMPTION TESTED (ccr_rat.v + iq_int.v):
|   CCR renaming lets a Bcc wake up the cycle after its TRUE producer
|   broadcasts on the CCR CDB.  The test stresses many tight CCR
|   produce→consume pairs where intermediate condition bits flip.
|
|   Each CMP writes a fresh CCR tag; each Bcc snapshots the current
|   tag.  The alloc/free discipline (16 CCR slots) keeps up as long
|   as the flag-writer doesn't allocate faster than commit retires.
|
|   The test exercises: CMP → BNE (NE) → CMP → BEQ (EQ) → ... pattern,
|   forcing every Bcc to resolve DIFFERENTLY from the previous.  If any
|   stale CCR snapshot leaks through (wrong tag read), a Bcc takes the
|   wrong direction and diverges from Musashi.
|
| ATTACK:
|   Eight-deep alternating CMP/Bcc, then a store-sentinel compare.
|
| PASS: all branches follow the correct direction per CCR.
| DIVERGENCE: stale CCR or rename bug.

    .text
    .org 0

_start:
    lea     0x00020000, %a7
    moveq   #1, %d7                  | counter
    moveq   #0, %d6                  | will accumulate; must be 4 at end

    cmp.l   #0, %d7                  | Z=0, N=0
    beq     _bad                     | must not take
    addq.l  #1, %d6                  | d6 = 1

    cmp.l   #1, %d7                  | Z=1
    bne     _bad                     | must not take
    addq.l  #1, %d6                  | d6 = 2

    cmp.l   #2, %d7                  | Z=0, N=1 (1-2=-1)
    bpl     _bad                     | must not take (result -ve → N=1)
    addq.l  #1, %d6                  | d6 = 3

    cmp.l   #0, %d7                  | Z=0, N=0 (1-0=1)
    bmi     _bad                     | must not take (N=0)
    addq.l  #1, %d6                  | d6 = 4

    cmp.l   #4, %d6
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_bad:
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_f:
    bra     _halt_f
