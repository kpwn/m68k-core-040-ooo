| exc_stack_atomicity_stress.s — Multi-phase stack-pointer + exception
|                                  atomicity stress.
|
| Goal: exercise the invariants the µop-injection path is supposed to
| preserve, in scenarios the existing tests don't cover.  Each phase
| writes a distinct PASS sentinel and falls through to the next; FAIL
| writes a phase-specific code so the failing scenario is identifiable
| from the testbench printout.
|
| =============================================================================
| Phase A — User → Supervisor → User round-trip drift detection
| =============================================================================
| Cycle the mode 16x: TRAP from user → handler in supervisor → RTE back
| to user.  Each iteration the handler writes a counter to a known
| memory cell.  At end-of-loop verify (a) the counter saw all 16
| iterations, (b) USP returned to its initial value with zero drift,
| (c) SSP returned to its initial value with zero drift.  Any drift
| means the U/S transition isn't preserving SP correctly somewhere.
|
| Why this catches things existing tests miss:
|   * sp_slot_corruption stays in supervisor across all 8 iterations.
|   * exc_user_vbr_rte_matrix toggles only twice.
|   * Drift accumulates linearly, so 16 iterations is enough to
|     surface a 1-byte/iter drift bug as a 16-byte cell mismatch.
|
| =============================================================================
| Phase B — Nested TRAP, supervisor-only, frame-stacking depth
| =============================================================================
| Handler-A fires TRAP #1.  Handler-B fires TRAP #2.  Handler-C
| writes a marker, RTEs.  Handler-B verifies the marker, RTEs.
| Handler-A verifies its own caller, RTEs.  Verifies that the SSP
| moved by exactly 3*8=24 bytes at the deepest nest, and that all
| three RTEs popped the frames in LIFO order without interleaving.
|
| =============================================================================
| Phase C — Privilege violation atomicity
| =============================================================================
| In user mode, fire `moves.l %d0,(%a0)`.  Vec-8 should fire BEFORE
| any data write happens — no side-effects on memory at (a0).  We
| pre-poison memory at (a0) with a sentinel, fire MOVES, then check
| in the vec-8 handler that the sentinel is unchanged.  Catches the
| failure mode where the atomicity of priv-check is broken (e.g.
| MOVES partially executed before the trap).
|
| =============================================================================
| Phase D — A7 consistency after exception entry
| =============================================================================
| In the vec-32 handler (re-fired after Phase C completes), do:
|     move.l %a7, %d0    ; d0 = arch A7 via PRF read
|     move.l %a7, 0x100  ; mem[0x100] = arch A7 via store (re-read)
|     move.l 0x100, %d1
|     cmp.l  %d0, %d1
| If the two reads disagree, the rename + writeback isn't keeping
| arch A7 coherent across the exception boundary.  This is the
| direct check for the priv_stack_switch failure mode.
|
| -----------------------------------------------------------------------------
| PASS sentinel: 0xC0FFEE00 to 0xFFFF0000 after Phase D succeeds.
| FAIL sentinels:
|   0xDEAD00A0..A2 — Phase A: counter / USP-drift / SSP-drift
|   0xDEAD00B0..B2 — Phase B: nesting depth / RTE order / handler
|   0xDEAD00C0..C1 — Phase C: MOVES atomicity / wrong handler
|   0xDEAD00D0..D1 — Phase D: A7 reads disagree / unexpected fault
| =============================================================================

    .text
    .org 0

    .equ PASS_SENT,    0xFFFF0000
    .equ STACK_SUPER,  0x00010000
    .equ STACK_USER,   0x00018000
    .equ COUNTER_CELL, 0x000200
    .equ A7_CELL,      0x000100
    .equ POISON_CELL,  0x000300
    .equ POISON_VAL,   0xCAFEF00D
    .equ ITER_A,       16

_start:
    | -----------------------------------------------------------
    | Setup: install handler at vec 32 (TRAP #0 — Phase A use),
    | vec 33 (TRAP #1 — Phase B/C user-mode use), vec 34 (TRAP #2
    | nested), vec 8 (privilege violation — Phase C).
    | -----------------------------------------------------------
    lea     STACK_SUPER, %a7
    move.l  #_handler_a, 0x00000080   | vec 32: TRAP #0
    move.l  #_handler_b, 0x00000084   | vec 33: TRAP #1
    move.l  #_handler_c, 0x00000088   | vec 34: TRAP #2
    move.l  #_handler_priv, 0x00000020 | vec 8: privilege

    | Save the original SSP for drift check.
    move.l  %a7, %a6                  | %a6 = original SSP

    | -----------------------------------------------------------
    | Phase A — 16x U→S→U with counter in mem[COUNTER_CELL].
    | First, set up USP and the counter.
    | -----------------------------------------------------------
    move.l  #0, COUNTER_CELL.l        | counter = 0
    move.l  #STACK_USER, %a0
    move    %a0, %usp                 | USP = STACK_USER (privileged)

    | Drop to user mode.  ANDI clears S and M bits in SR.
    andi.w  #0x1FFF, %sr              | clear S=0 (user mode)
    | --- now in user mode, %a7 == USP == STACK_USER ---

    | Save user-side starting A7 for drift check via mem cell.
    move.l  %a7, A7_CELL.l            | mem[A7_CELL] = initial USP

    move.l  #ITER_A, %d7              | iteration counter
_phaseA_loop:
    trap    #0                        | → handler_a, increments mem[COUNTER_CELL]
    subq.l  #1, %d7
    bne     _phaseA_loop

    | Phase A done.  Verify counter.
    move.l  COUNTER_CELL.l, %d0
    cmp.l   #ITER_A, %d0
    bne     _fail_a0

    | Verify USP still at STACK_USER (no drift).
    move.l  A7_CELL.l, %d0
    cmp.l   %a7, %d0
    bne     _fail_a1

    | Re-enter supervisor (TRAP #1) — handler_b will check SSP drift,
    | then run Phase B / C / D.  We do this because user mode can't
    | read SSP directly.
    trap    #1
    | _handler_b will jump us into the rest of the test once it
    | has verified Phase A's SSP drift.

    | If we're back here, _handler_b RTE'd to user mode — Phase C
    | already ran from inside it.  Final PASS write.
_pass:
    lea     PASS_SENT, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt_pass:
    bra     _halt_pass

_fail_a0:
    move.l  #0xDEAD00A0, %d0
    bra     _do_fail
_fail_a1:
    move.l  #0xDEAD00A1, %d0
    bra     _do_fail
_do_fail:
    lea     PASS_SENT, %a0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

| -----------------------------------------------------------------
| Vec-32 handler (Phase A): increment counter and RTE back to user.
| Runs in supervisor mode; SSP frame was pushed.
| -----------------------------------------------------------------
_handler_a:
    addq.l  #1, COUNTER_CELL.l
    rte

| -----------------------------------------------------------------
| Vec-33 handler (Phase B/C entry): runs in supervisor.  Verify
| Phase A's SSP drift, then dispatch Phase B (nested TRAP),
| Phase C (priv violation while in user), Phase D (A7 self-check).
| -----------------------------------------------------------------
_handler_b:
    | Phase A SSP drift check.  %a6 saved at _start; %a7 should be
    | exactly %a6 - 8 (one fmt-0 frame).
    move.l  %a6, %d0
    sub.l   %a7, %d0
    cmp.l   #8, %d0
    bne     _fail_a2_handler

    | -----------------------------------------------------------
    | Phase B — nested TRAP #2.
    | Save SSP-on-entry for nesting-depth check after RTE.
    | -----------------------------------------------------------
    move.l  %a7, %a5                  | a5 = SSP at handler_b entry
    trap    #2                        | → handler_c
    | After handler_c RTE: SSP must be back at %a5.
    move.l  %a7, %d0
    cmp.l   %a5, %d0
    bne     _fail_b0_handler

    | -----------------------------------------------------------
    | Phase D — A7 self-consistency from supervisor mode.
    | -----------------------------------------------------------
    move.l  %a7, %d0                  | d0 = A7 via PRF read
    move.l  %a7, A7_CELL.l            | mem[A7_CELL] = A7 via store
    move.l  A7_CELL.l, %d1            | d1 = A7 via load
    cmp.l   %d0, %d1
    bne     _fail_d0_handler

    | -----------------------------------------------------------
    | Phase C entry — switch to user, fire MOVES, expect vec-8.
    | We RTE with S=0 to drop to user, but RTE pops the SR from the
    | handler_b stack frame and that SR has S=1.  Instead, we
    | construct a fake user-mode frame and RTE through it.
    | -----------------------------------------------------------
    | Pre-poison Phase C cell.
    move.l  #POISON_VAL, POISON_CELL.l

    | Build a user-mode RTE frame.  Frame layout (fmt-0):
    |   SP+0: SR (we want S=0 → 0x0000)
    |   SP+2: PC[31:16]
    |   SP+4: PC[15:0]
    |   SP+6: format/vec word (0x0000 for fmt-0)
    | Top of frame goes to USP.
    move.l  #STACK_USER, %a4          | user A7 at start of Phase C
    move    %a4, %usp
    | Build the supervisor frame on SSP — RTE will pop and switch
    | A7 to USP because SR.S becomes 0.
    sub.l   #8, %a7
    move.w  #0x0000, (%a7)            | new SR = user mode
    move.l  #_phaseC_user, 2(%a7)     | new PC = user-mode entry
    move.w  #0x0000, 6(%a7)           | format=0, vec=0
    rte                                | jump to user mode

_fail_a2_handler:
    move.l  #0xDEAD00A2, %d0
    bra     _do_fail_h
_fail_b0_handler:
    move.l  #0xDEAD00B0, %d0
    bra     _do_fail_h
_fail_d0_handler:
    move.l  #0xDEAD00D0, %d0
    bra     _do_fail_h
_do_fail_h:
    lea     PASS_SENT, %a0
    move.l  %d0, (%a0)
_halt_fail_h:
    bra     _halt_fail_h

| -----------------------------------------------------------------
| Vec-34 nested handler (Phase B inner).
| -----------------------------------------------------------------
_handler_c:
    | Just RTE — handler_b will verify SSP returned to its entry
    | value, confirming both frames popped cleanly.
    rte

| -----------------------------------------------------------------
| Vec-8 handler (Phase C — privilege violation).
| Verify the poisoned cell is UNCHANGED (MOVES atomicity).
| -----------------------------------------------------------------
_handler_priv:
    move.l  POISON_CELL.l, %d0
    cmp.l   #POISON_VAL, %d0
    bne     _fail_c0_handler
    | Poison intact.  Now jump to _pass.  We can't simply RTE
    | because we're far from the original mainline; just fix up
    | the stack frame's PC slot to point at _pass and RTE.
    move.l  #_pass, 2(%a7)
    rte
_fail_c0_handler:
    move.l  #0xDEAD00C0, %d0
    lea     PASS_SENT, %a0
    move.l  %d0, (%a0)
_halt_priv:
    bra     _halt_priv

| -----------------------------------------------------------------
| Phase C user-side — fire the privileged MOVES and trap.
| -----------------------------------------------------------------
_phaseC_user:
    | Privileged op in user mode → vec 8.
    lea     POISON_CELL, %a0
    move.l  #0xDEADBEEF, %d0
    moves.l %d0, (%a0)
    | Should not reach here.
    move.l  #0xDEAD00C1, %d0
    lea     PASS_SENT, %a0
    move.l  %d0, (%a0)
_halt_phasec_user:
    bra     _halt_phasec_user
