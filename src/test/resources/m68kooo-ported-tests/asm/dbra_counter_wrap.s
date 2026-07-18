| dbra_counter_wrap.s — DBRA with upper-word-non-zero initial counter.
|
| Stage D-6 (agent/decode-v2-branches): validates that the V2 DBcc
| assembly preserves the upper 16 bits of Dn across decrements (PRM
| §4.56 specifies the decrement and compare happen only on bits [15:0]).
|
| Strategy:
|   - D0 = 0x00010000.  Low word starts at 0, so the first DBRA should
|     decrement to -1 (0xFFFF) and exit (branch NOT taken because low
|     word is 0, so we fall through — that's the DBRA exit condition).
|   - After the DBRA we expect D0 == 0x0000FFFF:
|       * upper 16 bits preserved: 0x0000
|       * lower 16 bits = 0 - 1 = 0xFFFF
|   - If the decrement leaks into the upper word, D0 becomes 0x0000FFFF
|     (wrong: -1 for the full 32 bits, 0xFFFFFFFF) — wait, that's the
|     same bit pattern in low word.  A better check is initial 0x00010000
|     → after first DBRA which exits because low word == 0 and decrement
|     makes it 0xFFFF; upper word must still be 0x0001 ... no — on
|     exit, the result is Dn[31:16] preserved, Dn[15:0] = old - 1.
|     So D0 post-DBRA-exit = 0x0001_FFFF.  ✓ If upper leaks: D0 would
|     instead be 0x0000_FFFF.
|
|   - Compare D0 against 0x0001_FFFF and PASS iff equal.

    .text
    .org 0

_start:
    move.l  #0x00010000, %d0          | D0 = upper=0x0001, lower=0x0000
    moveq   #0, %d1                   | D1 scratch

_loop:
    addq.l  #1, %d1                   | (body shouldn't matter)
    dbra    %d0, _loop                | decrement D0 low word, branch if != -1

    | After first iteration: D0[15:0] = 0xFFFF (wraps to -1), exit.
    | D0[31:16] must be preserved = 0x0001.
    | So D0 = 0x0001FFFF.
    cmpi.l  #0x0001FFFF, %d0
    beq     _pass

    | FAIL path.
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, (%a0)
_halt_fail:
    stop    #0x2700
    bra     _halt_fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
_halt:
    stop    #0x2700
    bra     _halt
