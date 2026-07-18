| subq_bvc_not_runaway.s — SUBQ.L #1,Dn does NOT set V for normal counts
|
| Regression test for task #193 fuzz-timeout audit.  The fuzz generator
| used to emit `subq.l #1, %d6; bvc <back>` as a bounded-counter back-
| branch, but SUBQ.L only sets V on signed overflow (when Dn wraps from
| INT32_MIN).  For d6 starting at small positive values, V stays 0 and
| BVC is always taken — producing a 2^32-iteration runaway loop that
| the RTL and Musashi both correctly execute but neither finishes in
| any realistic cycle budget.
|
| This test asserts the 68040 semantic: SUBQ.L #1, Dn from +N to
| 0 to -1 to -2... does NOT set V at any point; only wrapping the
| INT32_MIN boundary sets V.
|
| PASS: V remains 0 across normal range decrements.

    .text

_start:
    | Counter starts at 3, decrements to -3 (7 iterations).  None should
    | set V.  If any do, tally to d4 via BVS-taken branch.
    moveq   #3, %d5
    moveq   #0, %d4

_lp:
    subq.l  #1, %d5
    bvs     _saw_v             | V should never be set here
    cmpi.l  #-3, %d5
    bgt     _lp

    | V was never set → d4 untouched.
    cmp.l   #0, %d4
    bne     _fail

    | Now force V: INT32_MIN - 1 = INT32_MAX (with V=1).
    move.l  #0x80000000, %d5
    subq.l  #1, %d5
    bvc     _missed_v          | expected V=1
    cmp.l   #0x7FFFFFFF, %d5
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_saw_v:
    addq.l  #1, %d4
    cmpi.l  #-3, %d5
    bgt     _lp
    bra     _fail

_missed_v:
    | V was not set after INT32_MIN - 1 — that's a real RTL bug.
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xBADBAD00, %d0
    move.l  %d0, (%a0)
_fhlt:
    bra     _fhlt
