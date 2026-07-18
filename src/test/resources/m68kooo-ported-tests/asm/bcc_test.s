| bcc_test.s — Test for conditional branches (Bcc)
|
| Uses the initial CCR state (all zeros, Z=0) to test:
|   1. BNE taken  (Z=0 → branch to _not_zero)
|   2. BEQ not-taken (Z=0 → fall through)
|   3. A PASS store at 0xFFFF0000
|
| No flag-setting instructions before the branches, so no CCR RAW hazard.
| The FAIL path can only be reached if BNE incorrectly falls through.

    .text
    .org 0

_start:
    bne     _not_zero           | Z=0 on reset → taken, jump to _not_zero

    | ── FAIL path: BNE fell through (BNE not working) ─────────────────
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)          | FAIL store
_halt_fail:
    stop    #0x2700
    bra     _halt_fail

_not_zero:
    | BEQ should NOT be taken here (Z=0 still)
    beq     _halt_fail          | if taken → FAIL (would loop on halt_fail but
                                |   more importantly: proves BEQ incorrectly taken)
    | Fell through (correct for Z=0) → PASS
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)          | PASS store
_halt:
    stop    #0x2700
    bra     _halt
