| chk2_b_in_range.s — CHK2.B Rn in range; expect no trap and PASS.
|
| Bounds: [-10, +10] stored as two consecutive bytes at (a0).  D0 = 0
| (in range) → CHK2.B must NOT trap.  Reaching the PASS write means no
| trap fired.  If the vec-6 handler runs we report FAIL.
|
| PASS: 0xC0FFEE00.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000018   | install vec 6 handler

    | Lay down bounds at _bounds: byte 0 = -10 (0xF6), byte 1 = +10 (0x0A).
    lea     _bounds, %a0
    move.w  #0xf60a, (%a0)

    | D0 = 0, well inside [-10, 10].
    moveq   #0, %d0
    chk2.b  (%a0), %d0                | must not trap

    | Reaching here = PASS path (no trap).
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_handler:
    | If we land here, CHK2 trapped the in-range case → FAIL.
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
_halt_fail:
    bra     _halt_fail

    .align 4
_bounds:
    .long   0
