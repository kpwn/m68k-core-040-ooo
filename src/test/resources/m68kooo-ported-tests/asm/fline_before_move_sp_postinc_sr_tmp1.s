| fline_before_move_sp_postinc_sr_tmp1.s — single-shot SYNCHRONOUS F-line
| (vec 11) exception placed immediately BEFORE the `move (sp)+,sr` TMP1
| crack, one per iteration.  Sibling of irq_during_move_sp_postinc_sr_tmp1
| .s / trace_storm_move_sp_postinc_sr_tmp1.s (see
| project_via1_lockstep_deepdive.md "v7d update" + "2026-07-05 sim-only
| follow-up") — those tests tried ASYNC IRQ storms and continuous SR.T1
| tracing to get *something* to interleave with this crack's phase0
| (LOAD (An)->TMP1) .. phase2 (SYS_MOVE_SR<-TMP1) window and did not
| reproduce the HW-observed rename-tag mistag.  This test tries the next
| untried avenue named in that doc: a SINGLE-SHOT SYNCHRONOUS F-line trap
| (decode-time rob_exc=1, no IRQ/mb_empty gate involved at all) landing
| directly adjacent to the crack via straight-line program order instead
| of async injection.
|
| Structural note (documented in this session's memory-file update):
| `commit.v`'s `sync_exc_pretest` (drives take_exc/take_priv_exc/take_trace)
| DOES gate on `!drain_active` (contrary to an earlier read of "no gate at
| all") — so a sync exception firing during macro_buf's post-retire drain
| replay is blocked.  And every phase of this crack forces
| `q_rob_drain_req` (ROB-empty) before its OWN dispatch, so decode/fetch
| cannot even present a DIFFERENT macro's uops in the phase0..phase2 gap
| under normal operation.  This test's "before" variant is a NEGATIVE
| CONTROL: with the F-line trap fully retiring (handler executes, RTEs)
| BEFORE the crack's phase 0 ever dispatches, there is no possible
| temporal overlap at all — this iteration should always be clean.  It
| exists to establish a byte-for-byte-identical-methodology baseline
| against the "after" sibling test, which places the SAME F-line trap
| immediately AFTER the crack instead (see
| fline_after_move_sp_postinc_sr_tmp1.s).
|
| F-line vector 11 lives at 0x0000002C.  Opword 0xFFFF is a generic
| line-F trap (0xF1xx risks decoding as PSAVE/PMMU in some references —
| see exc_fline.s's header comment).  PC pushed for an F-line trap is the
| FAULTING instruction's own address (matches vec 2/3/4/8/10/11 in
| commit.v's PC-selection comment) — the handler must advance the
| stacked PC by 2 before RTE to skip past the trap word, exactly as
| aline_cross_cacheline_sp.s's `_aline_disp` does for A-line.

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
    move.l  #_lvl1_handler,  0x00000064  | vec 25 (autovector level 1) — unused, harmless if it ever fires
    clr.l   ITER_COUNT_ADDR

    | Drop to SR_PATTERN via the SAME crack we're testing, so the very
    | first iteration is exercised identically to the rest.
    move.w  #SR_PATTERN, -(%sp)
    move    (%sp)+, %sr

_iter_loop:
    | Record SP before this iteration's push+pop pair so we can catch an
    | A7 drift from either the F-line round-trip or the crack itself.
    move.l  %sp, %d1

    | THE F-LINE TRAP, single-shot, immediately BEFORE the crack.
    .short  0xFFFF

    move.w  #SR_PATTERN, -(%sp)   | push known SR pattern
    move    (%sp)+, %sr            | THE CRACK UNDER TEST (3 uops, TMP1)

    | Capture SR into D0 IMMEDIATELY after the crack, before ANY
    | flag-setting instruction (CMP legitimately sets CCR bits — part of
    | SR — reading SR back after a CMP would spuriously "corrupt" the
    | low byte with the CMP's own side effect; that is correct 68k
    | behavior and must not be mistaken for a bug).
    move    %sr, %d0

    | SP must be back to its pre-F-line, pre-push value (F-line round
    | trip is architecturally a no-op on SP; push -2, pop +2).
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
    | D1 = expected SP, current %sp = actual (drifted) SP.  Sentinel
    | carries the actual SP so the drift magnitude is visible.
    move.l  %sp, PASS_SENT
    bra     .

_fail_sr_corrupt:
    | D0 = the corrupted SR readback.  Sentinel carries it directly.
    move.l  %d0, PASS_SENT
    bra     .

| F-line (vec 11) handler.  Advance the stacked PC by 2 (skip the trap
| word) then plain RTE — no register side effects beyond that.  Frame
| layout (format-0/2, matches aline_cross_cacheline_sp.s's _aline_disp
| convention): SR @ sp+0 (2 bytes), PC @ sp+2 (4 bytes), format/vector
| @ sp+6 (2 bytes) [+ format-2's extra instruction-address word @ sp+8,
| untouched here and consumed automatically by RTE per the format
| nibble].
_fline_handler:
    addq.l  #2, 2(%sp)
    rte

| Vec-25 autovector level-1 IRQ handler.  Not expected to fire in this
| test (no +ipl injection in the .args), kept only for parity with the
| sibling test's vector table in case a stray IRQ occurs.
_lvl1_handler:
    rte
