| cmp_branches.s — Sweep several Bcc conditions after CMP
|
| For each pair (a,b) we set D0=a, D1=b, CMP.L D1,D0 (computes D0-D1)
| and then take a Bcc that should be true. If any branch is wrong we
| fall into the FAIL section.
|
| Conditions exercised: BEQ (a==b), BNE (a!=b), BMI (a-b<0 signed),
| BPL (a-b>=0 signed), BCS (a<b unsigned), BCC (a>=b unsigned).
|
| This is the canonical compare-and-branch pattern Mac OS uses
| everywhere; if CMP doesn't update CCR or Bcc reads stale flags,
| we land in _fail.

    .text
    .org 0

_start:
    | a==b → BEQ taken
    moveq   #7, %d0
    moveq   #7, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | a!=b → BNE taken
    moveq   #4, %d0
    moveq   #9, %d1
    cmp.l   %d1, %d0
    beq     _fail

    | signed: 2 - 5 = -3, N=1 → BMI taken
    moveq   #2, %d0
    moveq   #5, %d1
    cmp.l   %d1, %d0
    bpl     _fail

    | signed: 5 - 2 = 3, N=0 → BPL taken
    moveq   #5, %d0
    moveq   #2, %d1
    cmp.l   %d1, %d0
    bmi     _fail

    | unsigned: 1 - 2, borrow → C=1 → BCS taken
    moveq   #1, %d0
    moveq   #2, %d1
    cmp.l   %d1, %d0
    bcc     _fail

    | unsigned: 9 - 4, no borrow → C=0 → BCC taken
    moveq   #9, %d0
    moveq   #4, %d1
    cmp.l   %d1, %d0
    bcs     _fail

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
