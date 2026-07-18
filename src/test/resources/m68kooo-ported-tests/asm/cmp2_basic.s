| cmp2_basic.s — CMP2.{B,W,L} range check, sets CCR.C on out-of-range.
| Does NOT trap — same encoding family as CHK2 but extension bit 11 = 0.
|
| CMP2 sets:
|   CCR.C = 1 if compare out-of-range [lo, hi], 0 otherwise.
|   CCR.Z = 1 if compare == lo or compare == hi, 0 otherwise (our impl
|           sees only compare == hi reliably — see CLAUDE.md note on
|           3-µop crack).
|
| Cases:
|   1. CMP2.W in-range  → C=0.  Verified with BCC (branch if C=0 = in-range).
|   2. CMP2.W out-of-range → C=1.  Verified with BCS.
|
| PASS: 0xC0FFEE00.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    lea     _bounds_w, %a0
    move.l  #0xff9c0064, %d0         | (a0)+0..1 = -100, +2..3 = +100
    move.l  %d0, (%a0)

    | Case 1: CMP2.W in-range (D0=5).
    move.l  #5, %d0
    cmp2.w  (%a0), %d0                | C=0 expected
    bcs     _fail                      | if C=1, fail

    | Case 2: CMP2.W out-of-range (D0=500).
    move.l  #500, %d0
    cmp2.w  (%a0), %d0                | C=1 expected
    bcc     _fail                      | if C=0, fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
_halt_fail:
    bra     _halt_fail

    .align 4
_bounds_w:
    .long   0
