| chk2_w_below.s — CHK2.W with Rn below the lower bound.  Out-of-range
| → trap vec 6.  The handler writes PASS; reaching the post-CHK2 fall-
| through (no trap) writes FAIL.
|
| Bounds: [-100, +100] stored as two .W at (a0).  D0 = -200 (sx-to-32 =
| 0xFFFFFF38, low word = 0xFF38).
|
| PASS: 0xC0FFEE00.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000018   | install vec 6 handler

    | Bounds [-100, +100]: 0xFF9C 0064 stored at _bounds.
    lea     _bounds, %a0
    move.l  #0xff9c0064, %d0
    move.l  %d0, (%a0)

    | D0 = -200 (low 16 bits = 0xFF38).
    move.l  #0xffffff38, %d0
    chk2.w  (%a0), %d0                | must trap → handler runs

    | If we reach here, CHK2 didn't trap → FAIL.
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
_halt_fail:
    bra     _halt_fail

_handler:
    | Vec-6 handler — CHK2 correctly trapped on below-range Rn.  PASS.
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

    .align 4
_bounds:
    .long   0
