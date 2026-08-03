| ras_stale_across_trap_rte.s — RAS staleness across an EXCEPTION flush
|
| MECHANISM UNDER TEST (secondary half of the HW System 7.5.3 wild jump):
|   The RAS's speculative pointer is rolled back on `flush_en`, and
|   `flush_en` fires for EXCEPTION entry and RTE just as it does for a
|   branch mispredict.  The HW window in which the stale entry was used
|   was dense with A-line trap entries and RTEs, so the question is
|   whether an exception boundary leaves the RAS in a state that can
|   hand a later RTS a prediction belonging to a pre-exception frame —
|   and, if it does, whether the RTS still lands on the loaded return
|   address.
|
|   Note commit.v stages the BSR's RAS push one cycle past retire
|   specifically so it survives a same-cycle flush, so a push CAN be
|   delivered into a stack that an exception flush has just rewound.
|
| ATTACK:
|   1. Two unbalanced BSRs leave two entries on the RAS with zero
|      architectural call depth.
|   2. TRAP #0 -> handler -> RTE.  Exception entry and the RTE both
|      flush.
|   3. A hand-built RTS then returns to _target.  Whatever the RAS
|      holds at that point (pre-trap leftovers, empty, or a BPU
|      fallback), the longword at (A7) is the only architectural truth.
|
| OUTCOMES (all write the tb sentinel address, all distinguishable):
|   _target     0xC0FFEE00  PASS
|   _bad_r1     0xDEAD0011  committed the stale pre-trap RAS entry #1
|   _bad_r2     0xDEAD0012  committed the stale pre-trap RAS entry #2
|   _fallthru   0xDEAD0013  RTS lost br_taken and fell through
|
| SENSITIVITY (verified, not assumed): tampering commit.v's mispredict
|   compare to skip UOP_LOAD entries turns this test red.

    .text
    .org 0

    .equ VBRBASE, 0x00010000

_start:
    lea     0x00020000, %a7
    move.l  #VBRBASE, %d0
    movec   %d0, %vbr
    move.l  #_trap0, VBRBASE+0x80       | vec 32 = TRAP #0

    | ── 1. two unbalanced RAS pushes ───────────────────────────────
    bsr     _u1
_r1:
    bra     _bad_r1

_u1:
    addq.l  #4, %a7
    bsr     _u2
_r2:
    bra     _bad_r2

_u2:
    addq.l  #4, %a7

    | ── 2. exception entry + RTE across the drifted RAS ────────────
    trap    #0

    | ── 3. hand-built return frame; RAS state is whatever the
    |       exception boundary left behind ──────────────────────────
    move.l  #_target, -(%a7)
    rts
_fallthru:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0013, %d0
    move.l  %d0, (%a0)
_hf:
    bra     _hf

_target:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_hang_ok:
    bra     _hang_ok

_trap0:
    rte

_bad_r1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0011, %d0
    move.l  %d0, (%a0)
_h1:
    bra     _h1

_bad_r2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0012, %d0
    move.l  %d0, (%a0)
_h2:
    bra     _h2
