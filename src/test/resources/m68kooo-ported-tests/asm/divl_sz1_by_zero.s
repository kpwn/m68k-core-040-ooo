| divl_sz1_by_zero.s — DIVU.L / DIVS.L SZ=1 /0 → vec 5.
|
| Task #204 / B3: the /0 fast-path in mul_div.v's issue stage fires an
| immediate res_exc=1 / vec 5 without entering the FSM.  Handler is
| installed at 0x14 (vec 5 offset); handler writes the PASS sentinel.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000014        | vec 5 @ 0x14

    | ── DIVU.L SZ=1 /0 ───────────────────────────────────────────
    move.l  #0x12345678, %d0
    move.l  #0xCAFEBABE, %d1
    moveq   #0, %d2
    divu.l  %d2, %d0:%d1                  | /0 → trap vec 5

    | If we fall through without trap, that's FAIL.
    bra     _fail

_handler:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d3
    move.l  %d3, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d3
    move.l  %d3, (%a0)
    bra     _halt
