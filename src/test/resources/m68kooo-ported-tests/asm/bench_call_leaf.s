| bench_call_leaf.s — repeated SHORT-LEAF calls, the canonical RAS workload
|
| WHY THIS BENCH EXISTS
|   Every other bench_*.s in the set is straight-line or loop-branch code
|   with ZERO subroutine calls, so the whole return-address-prediction
|   path is unmeasured by `make bench`.  Task #223 (move the RAS push
|   from ROB retire to dispatch) needed a cycle number, not a mispredict
|   rate, and there was nothing in the rig that could produce one.
|
| SHAPE
|   200 iterations x 4 calls to a 2-instruction leaf = 800 calls.  The
|   leaf is deliberately as short as a callee can usefully be: its `rts`
|   dispatches within a couple of cycles of its own `bsr`, which is
|   exactly the window in which a retire-time RAS push has not landed yet.
|
|   The four calls per iteration are to the SAME leaf from FOUR distinct
|   call sites, so the BTB cannot rescue the return either (one BTB entry
|   per RTS PC, four different correct targets).
|
| INSTRUCTION MIX: 800 BSR + 800 RTS + ~2400 ALU ops.
| DETERMINISM: no memory traffic beyond the call stack itself.

    .text
    .org 0

    .equ ITERS, 200

_start:
    lea     0x00020000, %a7

    moveq   #0, %d3
    move.l  #ITERS, %d2

_loop:
    bsr     _leaf
    bsr     _leaf
    bsr     _leaf
    bsr     _leaf
    subq.l  #1, %d2
    bne     _loop

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)

_halt:
    stop    #0x2700
    bra     _halt

    | ── the leaf ──────────────────────────────────────────────────
    | One ALU op, then return.  Nothing here is allowed to grow: the
    | point of the bench is the call/return turnaround, not the callee.
_leaf:
    addq.l  #1, %d3
    rts
