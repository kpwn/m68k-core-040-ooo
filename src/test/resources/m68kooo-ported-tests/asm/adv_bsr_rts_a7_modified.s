| adv_bsr_rts_a7_modified.s — BSR, then tamper with (A7), then RTS
|
| ASSUMPTION TESTED:
|   The RAS predicts the RTS target; the RTS's real LOAD from (A7) is
|   the ground-truth target.  On RAS mispredict (e.g. someone rewrites
|   (A7) between BSR and RTS) commit MUST fire a redirect to the loaded
|   target rather than trusting the RAS prediction.
|
|   commit.v line 382: actual_next = rob_br_taken ? rob_br_target :
|     rob_npc_fallthru.  The RTS completion in lsu.v line 565-568 sets
|     cmpl_br_taken=1 and cmpl_br_target=loaded-value, so the commit
|     compare should catch a RAS/load disagreement.
|
| ATTACK:
|   BSR _sub; inside _sub, rewrite (A7) to _after; RTS pops _after.
|   Musashi jumps to _after → PASS.  If RTL follows the (stale) RAS
|   prediction, it lands at the fall-through of the BSR (a bra to a
|   hang) and NEVER writes any sentinel — timeouts out.
|
| SENTINEL: only _after writes a sentinel.  The hung case writes
|   nothing — the test will time out, which the diff tool captures as
|   state-mismatch (RTL no mem_writes, Musashi has PASS sentinel).
|
| PASS requires BOTH models to land at _after — otherwise we have a
|   divergence.

    .text
    .org 0

_start:
    lea     0x00020000, %a7
    bsr     _sub
    bra     _halt_fall              | should NEVER reach here if _sub's RTS works

_sub:
    | Replace the pushed return PC with _after.
    move.l  #_after, (%a7)
    rts                              | RTS pops _after (not fall-through of BSR)

_after:
    | Successful redirect via RTS load-target — write PASS.
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_halt_fall:
    | If we ever end up here the RTS used the RAS prediction instead of
    | the loaded value.  The testbench will time out; diff tool flags
    | the mismatch.
    bra     _halt_fall
