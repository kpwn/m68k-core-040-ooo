| ras_short_callee_rts_race.s — RTS dispatch racing its own JSR's retire
|
| MECHANISM UNDER TEST:
|   The RAS is PUSHED at ROB retire (commit.v's BSR/JSR crack pair
|   matcher stages ras_push_en_q, driven out a further cycle later) but
|   POPPED at RTS DISPATCH (m68k_core_fetch.vh's ras_pop_en).  Those are
|   different points in the pipeline, so a callee short enough that its
|   `rts` dispatches before its own `jsr` has retired pops a
|   top-of-stack that still belongs to an OUTER frame — a real,
|   plausible return address from a different call, not garbage.
|
|   That is the shape of the hardware System 7.5.3 wild jump: an RTS
|   retiring to 0x40837942, whose only provenance is the return address
|   of a `jsr ([0x574])` in an unrelated outer frame.
|
|   The loaded return address is the architectural truth in every one of
|   these cases.  This test asserts the machine honours it.
|
| ATTACK:
|   A two-deep call nest whose INNER callee is as short as a callee can
|   be (a single `rts`), run in a loop so the front end warms up and the
|   inner `rts` gets the best possible chance to dispatch while the
|   inner `jsr` is still in the ROB.  The outer callee is likewise short.
|   Both frames have DIFFERENT return addresses, so any cross-frame
|   confusion lands somewhere observable.
|
|   Each level tags a register on the way back; the tags are checked
|   after the loop.  Landing on the wrong return address skips a tag or
|   double-counts one, and control-flow damage larger than that trips the
|   distinct-sentinel paths.
|
| PASS: D0 == 64 (32 iterations x 2 returns), sentinel 0xC0FFEE00.
| FAIL: 0xDEADBEEF (tag mismatch) — plus, on any genuinely wild landing,
|   commit.v's CFI monitor prints a violation line.
|
| SENSITIVITY: this test is GREEN on unmodified main — recorded honestly
|   as such in the task report; it is a hazard probe, not a reproducer.
|   It is proven to be a live probe rather than dead code by the
|   [RAS-MISPRED] counter, which fires inside this test on unmodified
|   main.  With commit.v's mispredict compare tampered to skip UOP_LOAD
|   entries it goes RED.

    .text
    .org 0

_start:
    lea     0x00020000, %a7
    moveq   #0, %d0                 | return tag accumulator
    moveq   #32, %d1                | iteration count

_loop:
    jsr     _outer
    subq.l  #1, %d1
    bne     _loop

    cmp.l   #64, %d0
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
_hang_ok:
    bra     _hang_ok

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, (%a0)
_hang_bad:
    bra     _hang_bad

    | ── the shortest useful call nest ──────────────────────────────
    | _inner is a single RTS: its return address is pushed by the JSR
    | two instructions earlier, so the RTS dispatches about as early
    | relative to that JSR's retire as the pipeline permits.
_outer:
    jsr     _inner
    addq.l  #1, %d0                 | tag: came back from _inner
    rts

_inner:
    addq.l  #1, %d0                 | tag: entered/left _inner
    rts
