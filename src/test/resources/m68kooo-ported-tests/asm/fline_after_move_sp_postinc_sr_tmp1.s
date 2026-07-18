| fline_after_move_sp_postinc_sr_tmp1.s — single-shot SYNCHRONOUS F-line
| (vec 11) exception placed immediately AFTER the `move (sp)+,sr` TMP1
| crack, one per iteration.  Sibling of fline_before_move_sp_postinc_sr_
| tmp1.s (see that file's header for full background/citations) — this
| is the variant that actually has a chance of overlapping with the
| crack's OWN in-flight window, because:
|
|   - phase2 (SYS_MOVE_SR<-TMP1) is `uop_is_last=1`, so decode/fetch is
|     free to move on to the NEXT macro-PC (this F-line word) as soon as
|     phase2's own dispatch fires — it does NOT need to wait for phase2
|     to RETIRE first.
|   - macro_buf's post-retire drain (`drain_active`, replays phase0's
|     deferred TMP1 rat_commit one entry/cycle — see
|     docs/sync_exc_partial_macro.md) means `mb_empty` can stay FALSE for
|     a few cycles strictly AFTER phase2 retires, while decode has
|     already moved on.
|   - `commit.v`'s `sync_exc_pretest` DOES check `!drain_active`
|     (verified by direct code read this session — an earlier framing
|     that it was ungated was imprecise), so a sync exception dispatched
|     behind phase2 should NOT be able to fire `take_exc`/`take_priv_exc`
|     while drain_active is still replaying the buffered TMP1 commit.
|     This test empirically checks that gate holds for a SYNCHRONOUS
|     (not async-IRQ) exception source, which had not been tried before
|     this session.
|
| PASS here would mean: no corruption from a same-boundary synchronous
| exception either, closing off this specific avenue the same way the
| async-IRQ storm and SR.T1 trace storm were already closed off.

    .text
    .org 0

    .equ INIT_SSP,        0x00018000
    .equ PASS_SENT,       0xFFFF0000
    .equ ITER_COUNT_ADDR, 0x000F0000
    .equ TARGET_ITERS,    400
    .equ SR_PATTERN,      0x2000        | S=1, ipl=0 — keeps all IRQ levels unmasked

_start:
    move.w  #0x2700, %sr
    move.l  #INIT_SSP, %sp
    move.l  #_fline_handler, 0x0000002C  | vec 11 (F-line)
    move.l  #_lvl1_handler,  0x00000064  | vec 25 (autovector level 1) — unused
    clr.l   ITER_COUNT_ADDR

    | Drop to SR_PATTERN via the SAME crack we're testing, so the very
    | first iteration is exercised identically to the rest.
    move.w  #SR_PATTERN, -(%sp)
    move    (%sp)+, %sr

_iter_loop:
    | Record SP before this iteration's push+pop pair.
    move.l  %sp, %d1

    move.w  #SR_PATTERN, -(%sp)   | push known SR pattern
    move    (%sp)+, %sr            | THE CRACK UNDER TEST (3 uops, TMP1)

    | THE F-LINE TRAP, single-shot, immediately AFTER the crack — right
    | in the macro_buf drain-replay shadow if that window is reachable
    | at all from straight-line decode.
    .short  0xFFFF

    | Capture SR into D0 AFTER the F-line round-trip, not immediately
    | after the crack — this is deliberately checking whether the
    | F-line's exception entry/exit retroactively disturbed the crack's
    | already-committed SR/A7 state, not just the crack's own immediate
    | result.
    move    %sr, %d0

    | SP must be back to its pre-push, pre-F-line value.
    cmp.l   %d1, %sp
    bne     _fail_sp_drift

    cmp.w   #SR_PATTERN, %d0
    bne     _fail_sr_corrupt

    addq.l  #1, ITER_COUNT_ADDR
    move.l  ITER_COUNT_ADDR, %d0
    cmp.l   #TARGET_ITERS, %d0
    blt     _iter_loop

    move.l  #0xC0FFEE00, PASS_SENT
    bra     .

_fail_sp_drift:
    move.l  %sp, PASS_SENT
    bra     .

_fail_sr_corrupt:
    move.l  %d0, PASS_SENT
    bra     .

| F-line (vec 11) handler.  Advance the stacked PC by 2 (skip the trap
| word) then plain RTE.  Frame layout per
| fline_before_move_sp_postinc_sr_tmp1.s's header comment.
_fline_handler:
    addq.l  #2, 2(%sp)
    rte

_lvl1_handler:
    rte
