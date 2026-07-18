| trace_storm_move_sp_postinc_sr_tmp1.s — root-cause investigation repro,
| SYNCHRONOUS-exception sibling of irq_during_move_sp_postinc_sr_tmp1.s.
|
| Companion finding: the IRQ-storm version of this test (dense +ipl
| sweep, one interrupt per retiring instruction) did NOT reproduce a
| TMP1/RAT corruption on the `move (sp)+,sr` 3-phase crack — every
| observed IRQ arm correctly waited for `mb_empty` (commit.v ~1332)
| before arming, exactly as `take_irq_common`'s explicit gate intends.
| (An earlier apparent "corruption" in that test was a test-authoring
| bug — comparing SR readback across an intervening CMP, whose Z-flag
| side effect legitimately touches the CCR bits of SR; not an RTL bug.
| Fixed in that file by capturing SR immediately after the crack.)
|
| This test targets the SYNCHRONOUS-exception class instead, because:
|
|   1. `take_irq_common` (commit.v ~1321) explicitly ANDs in `mb_empty`
|      — arming is blocked while a cracked macro's earlier phases have
|      buffered (deferred) rat_commits sitting in macro_buf.
|   2. `take_trace` (commit.v ~1453), `take_priv_exc`/`take_exc`
|      (~1157/~1166), `take_cache_maint`/`take_ptest` (~1208/~1213) do
|      NOT have that same explicit `mb_empty` check — only `rob_is_last_uop`
|      is required, plus (for trace) `!drain_active`.  `drain_active`
|      is the flag that turns on WHEN a crack's last uop retires with
|      mb non-empty; there is a narrow same-cycle window where mb is
|      non-empty but `drain_active` hasn't been asserted yet (this is
|      the "start draining" transition cycle itself, i.e. exactly the
|      cycle our crack's phase 2 — SYS_MOVE_SR, uop_is_last=1 — retires
|      and pushes/drains phase 0's buffered TMP1 commit in the same
|      cycle).  If `trace_pend` is ALREADY set (from the immediately
|      PRECEDING macro's retire, since T1=1 traces every instruction)
|      at the exact cycle our crack's phase 2 retires, `take_trace`
|      could fire on that cycle without the `mb_empty` gate — landing
|      `mb_drop` (commit.v ~1492) squarely on the crack's own
|      last-uop-retire/drain-start cycle instead of a clean, unrelated
|      instruction boundary.
|
| Repro strategy: set SR.T1=1 (trace every instruction — exception
| entry auto-clears T1/T0 while the handler runs per PRM §8; RTE
| restores the pushed SR with T1=1 still set, so tracing repeats
| after every subsequent instruction, exactly like the IRQ-storm test
| but SYNCHRONOUS and self-contained — no testbench +ipl args needed).
| Loop the `move (sp)+,sr` crack many times under continuous trace
| pressure and check SR immediately after each crack (before any
| flag-setting instruction) against the known pushed pattern.

    .text
    .org 0

    .equ INIT_SSP,        0x00018000
    .equ PASS_SENT,       0xFFFF0000
    .equ ITER_COUNT_ADDR, 0x000F0000
    .equ TARGET_ITERS,    20
    | S=1, T1=1, ipl=0.  T1 MUST be baked into the pattern itself —
    | `move (sp)+,sr` loads SR verbatim, so if the pushed pattern had
    | T1=0 the crack would silently self-disable tracing on its very
    | first execution (the crack's own retire is what would need to
    | re-arm it, but by then the write has already happened with T1=0).
    | Keeping T1=1 in the steady-state pattern keeps the storm running
    | for the entire test, matching the IRQ-storm sibling's continuous
    | pressure.
    .equ SR_PATTERN,      0xA000

_start:
    move.w  #0x2700, %sr
    move.l  #INIT_SSP, %sp
    move.l  #_trace_handler, 0x00000024  | vec 9 (trace)
    clr.l   ITER_COUNT_ADDR

    | Drop to SR_PATTERN (S=1,T1=1,ipl=0) via the SAME crack under
    | test.  From here on every retiring instruction takes a vec-9
    | trace exception; the handler RTEs after re-stamping T1=1 into
    | the popped frame, so tracing is sustained for the rest of the run.
    move.w  #SR_PATTERN, -(%sp)
    move    (%sp)+, %sr

_iter_loop:
    move.l  %sp, %d1

    move.w  #SR_PATTERN, -(%sp)   | push known SR pattern
    move    (%sp)+, %sr            | THE CRACK UNDER TEST (3 uops, TMP1)

    | Capture SR into D0 IMMEDIATELY after the crack, before any
    | flag-setting instruction (see header note on CMP's Z-flag).
    move    %sr, %d0

    move.l  %d1, %d2               | (no-op copy; keeps D1 free of any
                                     | flag-setting use before the SP
                                     | check below, for clarity only)
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

| Vec-9 trace handler.  Sets T1 in the stacked SR (T1/T0 are auto-
| cleared by exception entry, so the handler must explicitly restore
| T1 in the frame for tracing to persist across the RTE, mirroring the
| sustained pressure the IRQ-storm sibling test achieves via +ipl
| re-injection).
|
| IMPORTANT (2026-07-05 test-authoring fix): use OR, not a literal
| MOVE of the full SR_PATTERN word, to set T1.  `move.w #0xA000,(%sp)`
| clobbers the CCR bits (low byte) the exception frame legitimately
| captured from the traced instruction (e.g. a `cmp` result) with a
| hardcoded 0 -- discovered live-debugging the trace_next_pc RTE-rearm
| fix: the RTL correctly threaded arch_ccr through exc_fire_saved_sr
| into the frame (confirmed via commit.v CORE_DEBUG instrumentation),
| but this handler was stomping it back to 0 every single round-trip,
| so `cmp.l %d1,%sp; bne _fail_sp_drift` always saw Z=0 and always
| "failed" -- a test bug, not an RTL bug.  OR-ing just the T1 bit
| preserves whatever CCR/S the frame actually holds.
_trace_handler:
    or.w    #0x8000, (%sp)
    rte
