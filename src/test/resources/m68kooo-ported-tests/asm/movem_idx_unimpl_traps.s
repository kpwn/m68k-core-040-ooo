| movem_idx_unimpl_traps.s — MOVEM EA shapes with no decoder row must
| take a CLEAN vec-4 ILLEGAL — pinning the fail-safe behaviour (no
| crack-wedge, no partial execution, no front-end hang).  Originally
| written against the 2026-07-15 audit to pin FOUR still-unimplemented
| brief-indexed shapes.
|
| This file has now been re-pointed TWICE, both times for the same
| reason: a shape it pinned as "must trap" got implemented for real,
| and continuing to pin an implemented shape as illegal would enshrine
| non-68040 behaviour.
|
|   V1-decode-retirement phase 2 (2026-07-22) implemented two of the
|   original four:
|     - MOVEM.L (d8,An,Xn) LOAD  — positive coverage in
|       movem_idx_an_load.s (+ fuzz `emit_movem_idx_an_load`).
|     - MOVEM.W (d8,PC,Xn) LOAD  — positive coverage in
|       movem_pc_idx_w.s.
|
|   task movem-idx-an-store-2026-09-11 implemented the remaining two:
|     - MOVEM.{W,L} <list>,(d8,An,Xn) STORE.  `(d8,An,Xn)` is a
|       CONTROL ALTERABLE mode, so this is a real, legal MC68040
|       instruction (Musashi's movem_re_* EA mask `A+-DXWL` includes
|       mode 6) — trapping it was a divergence from silicon, not a
|       fail-safe.  Positive coverage: movem_idx_an_store.s.
|
| What is pinned here NOW is a shape that is genuinely illegal on real
| MC68040 silicon in BOTH directions: EA mode 7 / reg 4 = `#<data>`.
| MOVEM has no immediate EA at all (register-list -> immediate is not
| alterable; immediate -> register-list is not in the load EA mask
| either), so both encodings must take vector 4.  GAS will not emit
| them, hence the raw .word forms:
|
|   0x48FC = 0100 1 0 001 1 111 100 = MOVEM.L <list>,#<data>   (store)
|   0x4CFC = 0100 1 1 001 1 111 100 = MOVEM.L #<data>,<list>   (load)
|
| Each is followed by its register-mask word (never consumed — the
| vec-4 handler resumes at the continuation held in A5).
|
| A5 arms the continuation for the vec-4 handler; D5 counts traps, so a
| shape that silently EXECUTES instead of trapping fails the D5 check
| even if it happens to fall through to the next label.
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
    .word   0x48FC, 0x00C0            | movem.l %d6-%d7,#<data> — no immediate EA
    bra     _fail
_c2:
    lea     _c3, %a5
    .word   0x4CFC, 0x00C0            | movem.l #<data>,%d6-%d7 — no immediate EA
    bra     _fail
_c3:
    cmp.l   #2, %d5                   | both encodings must have trapped
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
