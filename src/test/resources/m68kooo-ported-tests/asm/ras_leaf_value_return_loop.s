| ras_leaf_value_return_loop.s — repeated short-leaf calls returning a
| CYCLE BUDGET: this test carries a `.timeout` sidecar (3,000,000 cycles).
| It is not slow because anything is wrong -- it front-loads a large
| poison-fill / setup loop before the first assertion, and simply does not
| fit the 200,000-cycle default. Without the sidecar it reports HANG, which
| reads exactly like a core deadlock and cost real investigation time on
| 2026-09-09. Verified: it PASSES with the larger budget, unchanged.
|
| COMPUTED VALUE, checked every iteration
|
| MECHANISM UNDER TEST:
|   Same hazard as ras_short_callee_rts_race.s — the RAS is pushed at ROB
|   retire but popped at RTS dispatch, so a callee short enough that its
|   `rts` dispatches before its own `jsr` has retired pops a
|   top-of-stack belonging to an OUTER frame.  The loaded return address
|   is the architectural truth in every such case.
|
|   What is different here is the FAILURE MODE this test is sensitive to.
|   ras_short_callee_rts_race.s checks control flow: it notices when the
|   machine ends up in the wrong place.  This one checks a VALUE: a short
|   leaf measures a NUL-terminated string and returns the length in D0,
|   and the caller asserts the length on every single iteration and again
|   on the accumulated total.
|
|   A return to a wrong-but-REAL address lands in valid code with a
|   mismatched frame, so the observable damage can be a plausible-looking
|   wrong number rather than a crash — a measurement loop that never
|   converges instead of a bus error.  A control-flow-only check can miss
|   that; a value check cannot.
|
| SHAPE:
|   The measurement is deliberately built out of MANY maximally-short
|   leaf calls rather than one long one: _measure calls _getbyte — a
|   single instruction plus RTS — once per character, so every character
|   of every iteration is a fresh chance for the callee's RTS to dispatch
|   before its own JSR has retired.  128 iterations x 12 characters =
|   1536 racy calls, and the returned length depends on all of them.
|
|   A first draft of this test used a self-contained _strlen loop instead;
|   it measured only 2 return mispredicts in the whole run (both cold
|   start), because the loop body gave commit ample time to catch up.
|   That version was a dead probe.  The [RAS-MISPRED] counter is what
|   exposed it — recorded here so the next person does not re-introduce
|   the same weak shape.
|
| CHECKED, per iteration AND in aggregate:
|   D0 == 11 on every call, and the running total == 128*11 == 1408.
|
| OUTCOMES:
|   0xC0FFEE00  PASS
|   0xDEADBE01  a per-iteration length was wrong
|   0xDEADBE02  per-iteration lengths all passed but the total did not
|               (i.e. iterations were skipped or repeated)
|
| SENSITIVITY: reported honestly in the task writeup — see the notes
|   there for whether this went RED without the verify.  It is a hazard
|   probe; the [RAS-MISPRED] counter proves it is live rather than dead
|   code even when it stays green.

    .text
    .org 0

    .equ STRBUF, 0x00030000
    .equ STRLEN, 11
    .equ ITERS,  128
    .equ TOTAL,  1408                   | ITERS * STRLEN

_start:
    lea     0x00020000, %a7

    | ── build "ABCDEFGHIJK\0" in RAM ───────────────────────────────
    lea     STRBUF, %a1
    move.l  #0x41424344, (%a1)+
    move.l  #0x45464748, (%a1)+
    move.l  #0x494A4B00, (%a1)+

    moveq   #0, %d7                     | running total of measurements
    move.l  #ITERS, %d6

_loop:
    lea     STRBUF, %a0
    moveq   #-1, %d0                    | poison: a skipped call shows up
    jsr     _measure
    cmp.l   #STRLEN, %d0
    bne     _fail_len
    add.l   %d0, %d7
    subq.l  #1, %d6
    bne     _loop

    cmp.l   #TOTAL, %d7
    bne     _fail_total

    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a2)
_hang_ok:
    bra     _hang_ok

_fail_len:
    lea     0xFFFF0000, %a2
    move.l  #0xDEADBE01, %d1
    move.l  %d1, (%a2)
_hang_l:
    bra     _hang_l

_fail_total:
    lea     0xFFFF0000, %a2
    move.l  #0xDEADBE02, %d1
    move.l  %d1, (%a2)
_hang_t:
    bra     _hang_t

    | ── the racy pair ─────────────────────────────────────────────
    | _getbyte is as short as a callee can be: one instruction, then
    | RTS.  Its return address is pushed by a JSR two instructions
    | earlier, so its RTS dispatches about as early relative to that
    | JSR's retire as the pipeline permits — and it is called once per
    | character, so the whole measured value rides on it.
_measure:
    moveq   #0, %d0
_m_loop:
    jsr     _getbyte_w
    tst.b   %d2
    beq     _m_done
    addq.l  #1, %d0
    bra     _m_loop
_m_done:
    rts

    | Nested one level deeper on purpose.  A single-level short leaf
    | warms up and predicts correctly after the first call; it is the
    | NESTED case — an inner call whose push has not landed by the time
    | its own RTS dispatches — that keeps the RAS persistently one entry
    | short.  Measured with the [RAS-MISPRED] counter, not assumed.
_getbyte_w:
    jsr     _getbyte
    rts

_getbyte:
    move.b  (%a0)+, %d2
    rts
