| ccr_clobber.s — CCR ordering: latest flag-writer wins
|
|   CMP.L D1, D0   where D0 == D1   → would set Z=1
|   ADD.L D2, D2   where D2 != 0    → sets Z=0 (clobbers prior CCR)
|   BEQ _fail      → must NOT be taken (Z=0 from the ADD, not the CMP)
|
| Verifies that the architectural CCR reflects the most recent writer
| in program order, not whichever uop completed first under OoO.

    .text
    .org 0

_start:
    moveq   #5, %d0
    moveq   #5, %d1
    moveq   #3, %d2
    cmp.l   %d1, %d0            | equal → Z=1
    add.l   %d2, %d2            | D2 = 6, Z=0 — clobbers CCR
    beq     _fail               | Z=0 expected → must NOT branch
    bne     _ok                 | Z=0 → taken (PASS path)
    bra     _fail               | safety net

_ok:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d3
    move.l  %d3, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d3
    move.l  %d3, (%a0)
_halt_fail:
    bra     _halt_fail
