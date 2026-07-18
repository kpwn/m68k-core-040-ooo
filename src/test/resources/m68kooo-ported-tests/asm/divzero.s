| divzero.s — Zero-divide (vector 5) on DIVU/DIVS #0, Dn.
|
| DEFERRED: DIVS/DIVU are not decoded in phase 2.1 (same class as
| multiply_test — mul_div.v exists but the decode routing is not
| emitted).  Test will time out waiting for the divide to issue.
|
| Sequence:
|   1. Install handler at vector 5 (offset 0x14).
|   2. D0 = 0x00010000 (dividend).  D1 = 0 (divisor).
|   3. DIVU D1, D0  — divide-by-zero → trap vec 5.
|   4. Handler writes PASS sentinel.
|   5. If no trap, main line writes FAIL sentinel.
|
| PASS: 0xC0FFEE00.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000014   | vec 5 @ 0x14

    move.l  #0x00010000, %d0        | dividend
    moveq   #0, %d1                  | divisor = 0

    divu.w  %d1, %d0                 | → trap vec 5

    | If no trap, fall through to FAIL.
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, (%a0)
_halt_fail:
    bra     _halt_fail

_handler:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
_halt:
    bra     _halt
