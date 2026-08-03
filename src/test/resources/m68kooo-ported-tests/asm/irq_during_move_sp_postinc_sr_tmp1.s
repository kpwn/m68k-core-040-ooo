| irq_during_move_sp_postinc_sr_tmp1.s — root-cause investigation repro
| for the A7/SR corruption chain documented in
| project_via1_lockstep_deepdive.md ("v7d update").
|
| `move (sp)+,sr` (opword[5:3]==011, An=A7) cracks into 3 micro-ops
| (rtl/core/decode/decode_uop_assemble.v ~17536):
|
|   phase 0: UOP_LOAD  (An)  -> REG_TMP1        (arch_dst = TMP1)
|   phase 1: UOP_INT   ALU_ADD An,#2            (postinc, arch_dst = An)
|   phase 2: UOP_SYS   SYS_MOVE_SR <- REG_TMP1  (arch_src_a = TMP1)
|
| Every phase carries requires_supervisor=1, which forces
| q_rob_drain_req (rtl/core/m68k_core_fetch.vh ~602) so EACH phase
| individually waits for rob_empty before it can dispatch — i.e. the
| crack is self-serializing, phase-by-phase, with the ROB draining to
| empty between each phase.
|
| Live HW ILA (2026-07-05) showed phase 0 and phase 1 BOTH retire with
| the correct phys tag, but phase 2 becomes visible at the ROB head
| with its phys_src_a (TMP1's renamed tag) ALREADY WRONG on its first
| cycle — a rename-time mistag, not a late completion.  A nested F-line
| exception was observed firing around the same time.
|
| Hypothesis under test: docs/sync_exc_partial_macro.md's "macro_buf"
| deferred-commit mechanism buffers phase 0's non-A7 (TMP1) rat_commit
| until the crack's LAST uop (phase 2) retires and drains.  While that
| buffered commit is pending (mb_empty=0), `take_irq_common` explicitly
| gates arm on `mb_empty` (commit.v ~1332) but `take_trace` does NOT
| (commit.v ~1453) — and neither does the general sync-exception path
| (`take_exc`/`take_priv_exc`).  If ANY of these fire in the window
| where mb is non-empty, `mb_drop` (commit.v ~1492) discards the
| buffered TMP1 commit and (per the doc) "the macro is treated as if
| it never partially retired" — i.e. the WHOLE crack, including the
| already-eagerly-committed A7 postincrement (phase 1), gets replayed
| from the macro's start PC.  Since phase 1's A7 write is eager (not
| deferred), a restart double-applies the postincrement — this test
| looks for observable SR corruption (and, transitively, an A7 drift)
| from that class of interleaving.
|
| Repro strategy: loop `move (sp)+,sr` back-to-back many times with a
| recognizable SR pattern each iteration, while a dense external-IRQ
| sweep (see .args) tries every cycle offset relative to the loop body
| looking for the one that lands inside a crack's phase-0..phase-2
| window.  Any iteration whose post-move SR readback doesn't match the
| expected pattern, or whose SP has drifted from the expected +2/iter,
| is a hit.
|
|   .text
    .org 0

    .equ INIT_SSP,        0x00018000
    .equ PASS_SENT,       0xFFFF0000
    .equ ITER_COUNT_ADDR, 0x000F0000
    .equ SP_SNAPSHOT_ADDR,0x000F0004
    .equ TARGET_ITERS,    400
    .equ SR_PATTERN,      0x2000        | S=1, ipl=0 — keeps all IRQ levels unmasked

_start:
    move.w  #0x2700, %sr
    move.l  #INIT_SSP, %sp
    move.l  #_lvl1_handler, 0x00000064   | vec 25 (autovector level 1)
    clr.l   ITER_COUNT_ADDR

    | Drop to SR_PATTERN via the SAME crack we're testing, so the
    | very first iteration is exercised identically to the rest.
    move.w  #SR_PATTERN, -(%sp)
    move    (%sp)+, %sr

_iter_loop:
    | Record SP before this iteration's push+pop pair so we can catch
    | an A7 double-increment (or any other drift) from a mid-crack
    | restart, not just an SR mismatch.
    move.l  %sp, %d1

    move.w  #SR_PATTERN, -(%sp)   | push known SR pattern
    move    (%sp)+, %sr            | THE CRACK UNDER TEST (3 uops, TMP1)

    | Capture SR into D0 IMMEDIATELY after the crack, before ANY
    | flag-setting instruction (CMP legitimately sets CCR bits, which
    | are part of SR — reading SR back after a CMP would spuriously
    | "corrupt" the low byte with the CMP's own Z/N/V/C side effect;
    | that is correct 68k behavior, not a bug, and must not be
    | mistaken for one).
    move    %sr, %d0                | single-uop MOVE SR,Dn — no TMP1

    | SP must be back to its pre-push value (push -2, pop +2).
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
    | D0 = the corrupted SR readback.  Sentinel carries it directly —
    | 0x2000 would have been PASS-adjacent; anything else is the bug.
    move.l  %d0, PASS_SENT
    bra     .

| Vec-25 autovector level-1 IRQ handler.  Deliberately minimal — the
| point is to perturb commit/macro_buf timing, not to do handler work.
_lvl1_handler:
    rte
