| tst_after_arith.s — TST sets Z/N on a freshly computed value
|
| 1. Compute D0 = 5 - 5 = 0 via SUB. SUB itself sets Z=1.
| 2. ADDQ #1,D0 → D0 = 1, Z=0  (clobber CCR with non-zero result)
| 3. SUBQ #1,D0 → D0 = 0, Z=1  (CCR back to Z=1)
| 4. TST.L D0   → re-derives Z/N from D0 (no operand change), Z=1
| 5. BEQ _ok must be taken.
|
| Then test the negative path:
| 6. MOVE.L #-1,D0
| 7. TST.L D0 → N=1
| 8. BMI _good2

    .text
    .org 0

_start:
    moveq   #5, %d0
    moveq   #5, %d1
    sub.l   %d1, %d0            | D0 = 0
    addq.l  #1, %d0             | D0 = 1, Z=0
    subq.l  #1, %d0             | D0 = 0, Z=1
    tst.l   %d0                 | re-derive Z from D0 → Z=1
    beq     _ok
    bra     _fail

_ok:
    move.l  #0xFFFFFFFF, %d0
    tst.l   %d0                 | N=1, Z=0
    bmi     _good2
    bra     _fail

_good2:
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
