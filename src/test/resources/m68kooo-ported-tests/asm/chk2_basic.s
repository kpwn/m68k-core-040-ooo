| chk2_basic.s — CHK2.{B,W,L} bounded register check (vec 6 on fail).
|
| CHK2 encoding: 0000_00ss_11_mmm_rrr, extension A(15) Rn(14:12) B=1(11).
| Compares register Rn (Dn or An) against [lo, hi] in memory at ea.
| If out-of-range, takes exception vector 6.
|
| Mode=010 (An) supported.  Bounds table pre-loaded with simple stores
| so we only exercise decode paths we know work.
|
| Cases:
|   1. CHK2.B — D0=5 in-range [-10, 10]     → no trap.
|   2. CHK2.W — D0=5 in-range [-100, 100]   → no trap.
|   3. CHK2.L — D1=0 in-range [-1000, 1000] → no trap.
|   4. CHK2.W — D0=500 out-of-range         → vec 6 handler writes PASS.
|
| PASS: 0xC0FFEE00.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000018   | vec 6 @ 0x18

    | Pre-populate bounds: two .B at (a2), two .W at (a0), two .L at (a1).
    lea     _bounds_b, %a2
    move.w  #0xf60a, (%a2)           | -10, +10

    lea     _bounds_w, %a0
    move.l  #0xff9c0064, %d0         | (a0)+0..1 = 0xff9c=-100, (a0)+2..3 = 0x0064=+100
    move.l  %d0, (%a0)

    lea     _bounds_l, %a1
    move.l  #0xfffffc18, %d0         | (a1)+0..3 = -1000
    move.l  %d0, (%a1)
    move.l  #0x000003e8, %d0         | (a1)+4..7 = +1000
    move.l  %d0, 4(%a1)

    | Case 1: CHK2.B in-range.
    move.l  #5, %d0
    chk2.b  (%a2), %d0                | no trap

    | Case 2: CHK2.W in-range.
    move.l  #5, %d0
    chk2.w  (%a0), %d0                | no trap

    | Case 3: CHK2.L in-range.
    move.l  #0, %d1
    chk2.l  (%a1), %d1                | no trap

    | Case 4: CHK2.W out-of-range (too high).  The handler writes PASS.
    move.l  #500, %d0
    chk2.w  (%a0), %d0

    | If CHK2 failed to trap, report FAIL.
    bra     _fail

_fail:
    lea     0xFFFF0000, %a2
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a2)
_halt_fail:
    bra     _halt_fail

_handler:
    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a2)
_halt:
    bra     _halt

    .align 4
_bounds_b:
    .long   0
    .align 4
_bounds_w:
    .long   0
    .align 4
_bounds_l:
    .long   0, 0
