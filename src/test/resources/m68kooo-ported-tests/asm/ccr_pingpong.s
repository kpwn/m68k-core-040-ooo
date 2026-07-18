| ccr_pingpong.s — Alternating CCR writer / CCR reader stress
|
| Every pair (CMP, Bcc) exercises the CCR stall mechanism (design #6).
| We serialise 8 CMP→Bcc pairs — each Bcc reads CCR and must see the
| CCR produced by the immediately-prior CMP, not a stale/leaked value.
|
| We also interleave ADD (writes CCR), Bcc (reads CCR), SUB (writes
| CCR), Bcc (reads CCR) — every flag-writer has a flag-reader right
| behind it.  A broken ccr_inflight counter (e.g. off-by-one decrement
| on flush) would drift ccr_clean and deadlock or mis-stall.

    .text
    .org 0

_start:
    | pair 1: CMP equal → Z=1, BEQ taken
    moveq   #5, %d0
    moveq   #5, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | pair 2: CMP not-equal → Z=0, BNE taken
    moveq   #9, %d0
    moveq   #4, %d1
    cmp.l   %d1, %d0
    beq     _fail

    | pair 3: ADD.L generates result 0 → Z=1
    moveq   #0, %d2
    add.l   %d2, %d2      | 0+0=0 → Z=1
    bne     _fail

    | pair 4: SUB.L 5-5 = 0 → Z=1, carry=0
    moveq   #5, %d3
    sub.l   %d3, %d3      | 0 → Z=1, C=0
    bne     _fail
    bcs     _fail         | C must be 0

    | pair 5: CMP 1 vs 2 → C=1, BCS taken
    moveq   #1, %d4
    moveq   #2, %d5
    cmp.l   %d5, %d4
    bcc     _fail

    | pair 6: CMP signed 2 - 5 = -3 → N=1, BMI taken
    moveq   #2, %d6
    moveq   #5, %d7
    cmp.l   %d7, %d6
    bpl     _fail

    | pair 7: ADD 0x7FFFFFFF + 1 → V=1, N=1
    move.l  #0x7FFFFFFF, %d0
    addq.l  #1, %d0
    bvc     _fail
    bpl     _fail

    | pair 8: SUB resulting in negative → N=1, C=1
    moveq   #3, %d1
    moveq   #7, %d2
    sub.l   %d2, %d1      | 3-7 = -4 → N=1, C=1
    bpl     _fail
    bcc     _fail

    | Last CCR write then branch-not-taken + branch-taken check combined.
    moveq   #0, %d3
    tst.l   %d3           | Z=1, N=0
    bne     _fail         | Z=1 → BNE not taken → fall through
    bmi     _fail         | N=0 → BMI not taken → fall through

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
