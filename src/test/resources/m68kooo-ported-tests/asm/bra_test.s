| bra_test.s — Bring-up test for unconditional branch (BRA)
|
| Strategy: if BRA works, a "poison" store to the TB PASS address is
| skipped, and the correct PASS store at _good executes.
|   - FAIL: 0xDEADBEEF reaches 0xFFFF0000 (branch not taken / fell through)
|   - PASS: 0xC0FFEE00 reaches 0xFFFF0000 (branch taken correctly)
|
| Exercises: commit-redirect path, ROB flush + front-end redirect,
| re-fetch from branch target.

    .text
    .org 0

_start:
    bra     _good               | unconditional branch — skip the FAIL store

    | ── reachable only if BRA fell through (FAIL) ──────────────────────
    move.l  #0xDEADBEEF, %d0
    lea     0xFFFF0000, %a1
    move.l  %d0, (%a1)          | poison store → FAIL if we get here
_halt_fail:
    stop    #0x2700
    bra     _halt_fail

    | ── branch target ───────────────────────────────────────────────────
_good:
    move.l  #0xC0FFEE00, %d0   | D0 = PASS value
    lea     0xFFFF0000, %a1    | A1 = magic TB address
    move.l  %d0, (%a1)         | PASS store
_halt:
    stop    #0x2700
    bra     _halt
