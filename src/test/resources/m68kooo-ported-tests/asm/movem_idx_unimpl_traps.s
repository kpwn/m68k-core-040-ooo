| movem_idx_unimpl_traps.s — MOVEM brief-indexed shapes with no decoder
| row must take a CLEAN vec-4 ILLEGAL — pinning the fail-safe behaviour
| (no crack-wedge, no partial execution).  Originally written against
| the 2026-07-15 audit to pin FOUR still-unimplemented shapes.
|
| V1-decode-retirement phase 2 (2026-07-22) implemented two of those
| four shapes for real (they are no longer gaps, so pinning them as
| "must trap" here would be wrong):
|   - MOVEM.L (d8,An,Xn) LOAD  — now a real EA-compute-then-N-LOAD
|     crack (`movem_ea_is_idx_an_brief_f3`).  Positive coverage:
|     movem_idx_an_load.s (+ fuzz `emit_movem_idx_an_load`).
|   - MOVEM.W (d8,PC,Xn) LOAD  — the crack that made An-indexed .W work
|     was generalized to also drop the old .L-only restriction on the
|     PC-indexed crack.  Positive coverage: movem_pc_idx_w.s.
|
| STORE direction for brief-indexed EAs (mode 110) was NOT touched by
| that fix — `v2_movem_ea_ok_store` still has no indexed-EA row at all
| — so it remains a genuine gap and stays pinned here.
|
| A5 arms the continuation for the vec-4 handler; D5 counts traps.
|
| PASS: 0xC0FFEE00.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_h4, 0x00000010          | vec 4 handler
    moveq   #0, %d5
    lea     0x00020000, %a0
    moveq   #0, %d0

    lea     _c2, %a5
    movem.l %d6-%d7, (0,%a0,%d0.l)    | store .L An-indexed — still unimplemented
    bra     _fail
_c2:
    lea     _c3, %a5
    movem.w %d6-%d7, (0,%a0,%d0.l)    | store .W An-indexed — still unimplemented
    bra     _fail
_c3:
    cmp.l   #2, %d5                   | both stores must have trapped
    bne     _fail

    | PASS
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_h4:
    addq.l  #1, %d5
    move.l  %a5, (2,%a7)              | resume at the armed continuation
    rte
