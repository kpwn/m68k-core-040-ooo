| trapv_basic.s — TRAPV (vector 7) raised only if V flag set.
|
| DEFERRED: TRAPV is not decoded in phase 2.1.  It's opword 0x4E76;
| decode leaves it as NOP and the V-flag pre-condition is never tested.
|
| Sequence:
|   Part 1 (should TRAP):
|     1. Install handler at vector 7 (offset 0x1C).
|     2. Execute an ADD that overflows (V=1).
|     3. TRAPV → should enter handler.
|     4. Handler increments D7 counter and RTEs.
|
|   Part 2 (should NOT trap):
|     5. After return, execute a clean ADD that leaves V=0.
|     6. TRAPV → should fall through (no trap).
|
|   Part 3 (final):
|     7. If D7 == 1 (handler fired exactly once) → PASS.
|
| PASS: 0xC0FFEE00.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x0000001C   | vec 7 @ 0x1C
    moveq   #0, %d7                  | D7 = trap counter

    | Part 1: set V=1 via signed overflow, then TRAPV.
    move.l  #0x7FFFFFFF, %d0
    add.l   #1, %d0                  | V=1  (0x7FFFFFFF + 1 = 0x80000000)

    trapv                             | → handler (RTEs)

    | Part 2: set V=0 via no-overflow add, TRAPV must NOT fire.
    move.l  #1, %d0
    add.l   #1, %d0                  | V=0

    trapv                             | → no trap

    | Part 3: verify D7 == 1 (handler fired once and only once).
    cmp.l   #1, %d7
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d1
    move.l  %d1, (%a0)
_halt_fail:
    bra     _halt_fail

_handler:
    addq.l  #1, %d7                  | increment trap counter
    rte
