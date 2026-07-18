| adv_store_squash_mispredict.s — Speculative store squashed by branch mispredict
|
| ASSUMPTION TESTED (lsu.v store commit discipline, lines 37-45):
|   "Stores do NOT drive the cache at execute time. [...] If a branch
|    mispredict flushes the pipeline before the store commits, flush_en
|    causes the LSU to discard the buffered store."
|
|   The LSU's S_ST_BUF waits for commit_store_en.  If flush_en fires
|   while in S_ST_BUF, the buffered store must be dropped (not written
|   to memory).
|
| ATTACK:
|   Set up Z=1 via CMP, then BEQ.  The BPU cold-predicts not-taken, so
|   the pipeline fetches the fall-through and speculatively dispatches
|   a store to 0x00012000 with payload 0xBADBAD01.  When BEQ resolves
|   taken, the fall-through must be flushed and the store dropped.
|
|   After the taken branch, re-read memory at 0x00012000 — must still
|   be 0.  If we see 0xBADBAD01, the speculative store leaked.
|
| PASS means no divergence + 0xC0FFEE00 sentinel.

    .text
    .org 0

_start:
    | Seed poison address with a known value 0x0 that predates any
    | speculation.  Use an unrelated register address so CCR clobber
    | doesn't delay the init.
    lea     0x00012000, %a1
    move.l  #0x00000000, %d7
    move.l  %d7, (%a1)              | retire barrier via store-commit
    move.l  (%a1), %d7              | read-back to ensure committed
    cmp.l   #0, %d7
    bne     _fail

    lea     0x00020000, %a7
    moveq   #1, %d0
    cmp.l   #1, %d0                 | Z=1
    beq     _good
    | ─ Wrong-path shadow: speculative fall-through ─
    move.l  #0xBADBAD01, %d3
    move.l  %d3, (%a1)              | speculative store — MUST be squashed
    bra     _fail

_good:
    | BEQ resolved taken — flush should have happened.  Re-read poison.
    move.l  (%a1), %d6
    cmp.l   #0, %d6
    bne     _fail                   | 0xBADBAD01 here → speculative store leaked

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_f:
    bra     _halt_f
