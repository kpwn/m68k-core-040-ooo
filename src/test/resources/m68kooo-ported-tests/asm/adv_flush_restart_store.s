| adv_flush_restart_store.s — Mispredict discards a buffered store; re-store the
|                             same address must now be visible to a following load.
|
| ASSUMPTION TESTED (lsu.v flush in S_ST_BUF; RAT flush rollback):
|   When a branch misprediction flushes uops that include a buffered
|   store (in S_ST_BUF), the LSU must:
|     (a) discard the buffered store (no AXI write),
|     (b) return to S_IDLE ready to accept new work,
|     (c) the RAT must recover the phys reg(s) the squashed STORE's
|         base / data used, AND the architectural mapping of A7 etc.
|
|   After the flush, the correct-path can re-issue a STORE to the SAME
|   address.  A subsequent LOAD must see the NEW store's value, not
|   the (never-committed) squashed one, and not the pre-store memory.
|
| ATTACK:
|   BNE to skip a "bad" store; then store a "good" value and read back.
|
|     moveq #0, %d0
|     cmp.l #0, %d0         ; Z=1
|     bne   _skip            ; NOT taken; BUT if wrong-path buffered
|                            ; the 'bad' store, the later 'good' store
|                            ; should cleanly supersede it
|   _skip:
|     move.l #0xBAD5E1F5, (%a2)   ; bad store (behind the bne shadow)
|     move.l #0xG00D5151, (%a2)   ; good store
|     move.l (%a2), %d1           ; must see 0xG00D5151
|
|   Since BNE is not taken (Z=1), _skip is the fall-through — it
|   executes.  The "bad" store is architecturally fine; the wrong-path
|   comes from the BPU potentially predicting NE (taken) and fetching
|   ahead beyond _skip.  Either way the final load must see the good.
|
| Actually this is more about whether an in-queue STORE that gets
|   squashed by an older branch resolve leaves behind corrupted iq_mem
|   bitmap state.
|
| PASS: d1 == 0x600D5151; both models agree.

    .text
    .org 0

_start:
    lea     0x00020000, %a7
    lea     0x00016000, %a2

    | Set up a mispredict scenario: BEQ whose direction is cold-predicted
    | not-taken, then on taken-resolve we flush the in-queue store.
    moveq   #1, %d0
    cmp.l   #1, %d0                  | Z=1
    beq     _taken

    | wrong-path: this whole block should be squashed
    move.l  #0xBADBAD55, %d3
    move.l  %d3, (%a2)               | speculative store
    move.l  #0xBADBAD66, %d4
    move.l  %d4, 4(%a2)
    bra     _wrong_path_sentinel

_taken:
    | correct path: write and read back
    move.l  #0x600D5151, %d5
    move.l  %d5, (%a2)
    move.l  (%a2), %d1

    cmp.l   #0x600D5151, %d1
    bne     _fail

    | Also verify 4(%a2) was NOT overwritten by the squashed store
    move.l  4(%a2), %d2
    | 4(%a2) was never architecturally written, so 0xFFFFFFFF (uninit).
    | If the squashed store at 4(%a2) leaked, d2 == 0xBADBAD66.
    cmp.l   #0xBADBAD66, %d2
    beq     _fail                    | leak detected

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_wrong_path_sentinel:
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_f:
    bra     _halt_f
