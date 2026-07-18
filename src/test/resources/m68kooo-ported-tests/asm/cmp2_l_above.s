| cmp2_l_above.s — CMP2.L with Rn above the upper bound.  Sets CCR.C=1
| but does NOT trap (CMP2 never traps).  Verify with BCS branching to
| PASS; BCC fall-through writes FAIL.
|
| Bounds: [-1000, +1000] stored as two .L at (a0).  D0 = +5000 → above
| upper.
|
| PASS: 0xC0FFEE00.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | Lay down 32-bit bounds: lo = -1000 (0xFFFFFC18), hi = +1000 (0x000003E8).
    lea     _bounds, %a0
    move.l  #0xfffffc18, %d0
    move.l  %d0, (%a0)
    move.l  #0x000003e8, %d0
    move.l  %d0, 4(%a0)

    | D0 = +5000 (above upper bound).
    move.l  #5000, %d0
    cmp2.l  (%a0), %d0                | C=1 expected, no trap
    bcc     _fail                      | if CCR.C=0, fail

    | PASS path — reached when CCR.C=1.
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
_bounds:
    .long   0, 0
