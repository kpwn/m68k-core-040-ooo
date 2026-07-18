| chk_bounds.s — CHK.W Dn, Dm (vector 6) bounds-check trap.
|
| DEFERRED: CHK is not decoded in phase 2.1.  Decode currently leaves
| the 0x4xxx/A8 family alone, so CHK falls through to NOP and the
| handler sentinel never fires.
|
| Semantics (68040 PRM):
|   CHK.W <ea>, Dn  — if Dn[15:0] (sign-extended) < 0
|                    or Dn[15:0] > <ea>.w, take exception vector 6.
|   CHK.L <ea>, Dn  — same with long operands (68020+).
|
| Test:
|   1. Install handler at vector 6 (offset 0x18).
|   2. Set D0 = 0x00008000 (= 32768, will fail upper bound of 100).
|   3. CHK.W D1, D0  — D1 holds 100; D0 > D1 → trap.
|   4. Handler: write PASS sentinel.
|   5. If CHK doesn't trap, main line writes FAIL sentinel.
|
| PASS: 0xC0FFEE00.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000018   | vec 6 @ 0x18

    move.l  #0x00000064, %d1        | D1 = 100 (upper bound)
    move.l  #0x00008000, %d0        | D0 = 0x8000 (= 32768 — too big)

    chk.w   %d1, %d0                | 0 > D0 > D1 → trap vec 6

    | If CHK did not trap, fall through to FAIL.
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_handler:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
