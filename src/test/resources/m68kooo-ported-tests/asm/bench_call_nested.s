| bench_call_nested.s — 3-deep nested call/return chain (RAS depth workload)
|
| WHY THIS BENCH EXISTS
|   Companion to bench_call_leaf.s.  A single-level leaf call warms up
|   and can be predicted from one RAS entry; the case that keeps a
|   retire-time-pushed RAS persistently WRONG (not merely cold) is the
|   NESTED one — an inner call whose push has not landed by the time its
|   own `rts` dispatches, so the inner return pops the OUTER frame's
|   address and lands somewhere real but unrelated.
|
| SHAPE
|   150 iterations of  _lvl1 -> _lvl2 -> _lvl3, mixing BSR and JSR so
|   both crack shapes (PC-relative BSR and absolute-target JSR) are on
|   the measured path.  450 calls, 450 returns, RAS depth 3.
|
| DETERMINISM: no memory traffic beyond the call stack itself.

    .text
    .org 0

    .equ ITERS, 150

_start:
    lea     0x00020000, %a7

    moveq   #0, %d3
    move.l  #ITERS, %d2

_loop:
    bsr     _lvl1
    subq.l  #1, %d2
    bne     _loop

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)

_halt:
    stop    #0x2700
    bra     _halt

    | ── the call chain ────────────────────────────────────────────
_lvl1:
    addq.l  #1, %d3
    jsr     _lvl2
    addq.l  #1, %d3
    rts

_lvl2:
    addq.l  #1, %d3
    bsr     _lvl3
    rts

_lvl3:
    addq.l  #1, %d3
    rts
