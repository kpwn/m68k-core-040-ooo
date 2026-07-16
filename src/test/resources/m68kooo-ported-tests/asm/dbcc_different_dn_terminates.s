| dbcc_different_dn_terminates.s — DBcc with work-Dn != counter-Dn
|
| Regression test added for task #193 test-story cleanup.  The fuzz
| generator used to emit `addq.l #1, <work>` inside a DBcc loop with
| `<work> == counter`, which undoes the DBcc low-word decrement and
| spins forever.  This test asserts the 68040 semantics for the
| "different Dn" case: DBRA (DBF) decrements the low 16 bits of its
| counter until it underflows to -1 regardless of any unrelated Dn
| that the loop body may be writing.
|
| PASS: loop exits after exactly (counter_initial + 1) iterations.
| FAIL: timeout OR wrong iteration count.

    .text

_start:
    | Counter = d5 initial = 3.  Loop will run d5+1 = 4 iters then exit.
    moveq   #3, %d5
    | Iteration tally in d4.
    moveq   #0, %d4

_lp:
    addq.l  #1, %d4        | bump tally
    dbra    %d5, _lp       | decrement d5 (low 16), loop while != -1

    | After exit d4 should be 4.
    cmp.l   #4, %d4
    bne     _fail

    | d5 low 16 should be 0xFFFF (= -1).  We test full-word = 0xFFFF
    | because MOVEQ sign-extended #3 gives d5=0x00000003 and four
    | decrements take low 16 from 3→2→1→0→0xFFFF; upper 16 are
    | unchanged on DBcc (stays 0).
    cmp.l   #0x0000FFFF, %d5
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xBADBAD00, %d0
    move.l  %d0, (%a0)
_fhlt:
    bra     _fhlt
