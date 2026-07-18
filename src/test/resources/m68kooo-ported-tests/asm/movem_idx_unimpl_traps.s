| movem_idx_unimpl_traps.s — MOVEM brief-indexed shapes with no decoder
| row (mode-110 An-indexed load/store both sizes, and .W PC-indexed
| load) must take a CLEAN vec-4 ILLEGAL — pinning the fail-safe
| behaviour (no crack-wedge, no partial execution).  Verified against
| the 2026-07-15 audit; MOVEM.L (d8,PC,Xn) load IS implemented (legacy
| row) and is covered by fuzz, not here.  A5 arms the continuation for
| the vec-4 handler; D5 counts traps.
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
    lea     _c5, %a5
    .word   0x4CBB, 0x00C0, 0x0000    | movem.w (0,%pc,%d0.w), %d6-%d7
    bra     _fail
_c5:
    cmp.l   #4, %d5                   | all four must have trapped
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
