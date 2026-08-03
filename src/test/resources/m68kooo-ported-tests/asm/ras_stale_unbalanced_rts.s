| ras_stale_unbalanced_rts.s — RTS must ignore a STALE RAS entry
|
| MECHANISM UNDER TEST (measured on real HW, System 7.5.3 boot):
|   An RTS retired to a stale return-address-stack entry left behind by
|   an EARLIER, UNRELATED call, without that prediction ever being
|   checked against the return address actually loaded from the stack.
|   The architectural truth for an RTS is the longword at (A7); the RAS
|   is only a fetch-steering predictor.  A disagreement MUST flush and
|   redirect to the loaded value.
|
|   Stronger than adv_bsr_rts_a7_modified.s in two ways:
|     (a) the RAS entry belongs to a DIFFERENT, never-returned-from call,
|         so the popped prediction has no relationship at all to the
|         stack the RTS reads;
|     (b) the correct target is NOT the RTS's fall-through.  In
|         adv_bsr_rts_a7_modified.s the landing pad sits at rts+2, so
|         "commit redirected to the loaded return address" and "commit
|         lost br_taken and fell through" produce the IDENTICAL PC and
|         the test cannot tell them apart.  Here they are separate
|         labels with separate sentinels.
|
| ATTACK:
|   1. `bsr _sub` pushes _stale_ret onto the RAS when it retires.
|   2. _sub throws the architectural return address away (addq #4,A7)
|      and never executes an RTS — the RAS keeps _stale_ret.  Nothing
|      between here and the RTS takes a branch, so no flush clears the
|      RAS.
|   3. _sub builds a return frame pointing at _target by hand and falls
|      through into an RTS.  The RAS still predicts _stale_ret.
|
| THREE DISTINGUISHABLE OUTCOMES, all writing the tb sentinel address:
|   _target      0xC0FFEE00  PASS — loaded return address won
|   _stale_ret   0xDEADBEEF  the RAS prediction was committed as the
|                            architectural target (the HW bug shape)
|   _fallthru    0xBADF00D1  the RTS retired with br_taken/br_target
|                            lost, so commit redirected to rts+2
|
| SENSITIVITY (verified, not assumed): with commit.v's mispredict
|   compare tampered to skip UOP_LOAD entries, this test goes
|   [PASS] -> [FAIL] and the commit.v CFI monitor prints
|   "macro at pc=... retired with architectural next-pc=..., but the
|   NEXT macro retired at pc=..." for the RTS.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    | ── 1. unbalanced RAS push ─────────────────────────────────────
    bsr     _sub

_stale_ret:
    | Only reachable if the RTS committed the RAS prediction.
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_hang_stale:
    bra     _hang_stale

_sub:
    | ── 2. discard the real return address; never RTS from here ────
    addq.l  #4, %a7

    | ── 3. hand-built return frame + fall-through into the RTS ─────
    move.l  #_target, -(%a7)
    rts

_fallthru:
    | Only reachable if the RTS lost its taken/target completion and
    | commit redirected to the fall-through instead.
    lea     0xFFFF0000, %a0
    move.l  #0xBADF00D1, %d0
    move.l  %d0, (%a0)
_hang_fall:
    bra     _hang_fall

_target:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_hang_ok:
    bra     _hang_ok
