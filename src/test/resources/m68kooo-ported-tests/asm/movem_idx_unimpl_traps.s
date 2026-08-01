| movem_idx_unimpl_traps.s — MOVEM brief-indexed shapes with no decoder
| row (mode-110 An-indexed load/store, both sizes) must take a CLEAN
| vec-4 ILLEGAL — pinning the fail-safe behaviour (no crack-wedge, no
| partial execution).  Originally written against the 2026-07-15 audit
| to pin FOUR still-unimplemented shapes; ported-tests triage
| (movem_pc_idx_w HANG investigation) implemented the fourth shape for
| real (MOVEM.W (d8,PC,Xn) LOAD — the FSM that computes the EA was
| already fully size-generic, only OperationDecoder.scala's decoder gate
| artificially restricted PC-indexed MOVEM to `.L`; positive coverage:
| movem_pc_idx_w.s), so pinning it as "must trap" here would now be
| wrong — that case is REMOVED.  Mode-110 (An-indexed) load/store, both
| sizes, remain a genuine unimplemented gap in this fork (no EA-compute
| crack for that mode at all) and stay pinned.  MOVEM.L (d8,PC,Xn) load
| IS implemented (legacy row) and is covered by fuzz, not here.  A5
| arms the continuation for the vec-4 handler; D5 counts traps.
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
    movem.l %d6-%d7, (0,%a0,%d0.l)    | store .L An-indexed
    bra     _fail
_c2:
    lea     _c3, %a5
    movem.l (0,%a0,%d0.l), %d6-%d7    | load .L An-indexed
    bra     _fail
_c3:
    lea     _c4, %a5
    movem.w (0,%a0,%d0.l), %d6-%d7    | load .W An-indexed
    bra     _fail
_c4:
    cmp.l   #3, %d5                   | all three must have trapped
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
