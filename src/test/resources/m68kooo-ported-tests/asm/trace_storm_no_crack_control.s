| trace_storm_no_crack_control.s — control experiment for
| trace_storm_move_sp_postinc_sr_tmp1.s.  That test wedged (infinite
| livelock, never reaching the FAIL/PASS sentinel) under SR.T1=1
| continuous per-instruction tracing.  This variant removes the
| `move (sp)+,sr` 3-phase crack entirely (replaced with a plain
| single-uop ADDQ) to determine whether the livelock is:
|   (a) specific to the crack / TMP1 macro_buf interaction, or
|   (b) a general defect in sustained SR.T1=1 (trace-every-instruction)
|       handling that would wedge on ANY instruction stream.
| If this ALSO wedges, the bug is unrelated to TMP1/cracks — it is a
| general take_trace / trace_pend re-arm-too-early livelock.  If this
| PASSES cleanly, the livelock is specific to the crack under test.

    .text
    .org 0

    .equ INIT_SSP,        0x00018000
    .equ PASS_SENT,       0xFFFF0000
    .equ ITER_COUNT_ADDR, 0x000F0000
    .equ TARGET_ITERS,    20
    .equ SR_PATTERN,      0xA000        | S=1, T1=1, ipl=0

_start:
    move.w  #0x2700, %sr
    move.l  #INIT_SSP, %sp
    move.l  #_trace_handler, 0x00000024  | vec 9 (trace)
    clr.l   ITER_COUNT_ADDR
    clr.l   %d3

    move.w  #SR_PATTERN, -(%sp)
    move    (%sp)+, %sr

_iter_loop:
    move.l  %sp, %d1

    addq.l  #1, %d3                | plain single-uop ALU op — NO crack,
                                     | NO TMP1, NO macro_buf involvement

    move    %sr, %d0
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

| IMPORTANT (2026-07-05 test-authoring fix): OR in just the T1 bit
| rather than clobbering the whole SR word with the literal
| SR_PATTERN.  `move.w #SR_PATTERN,(%sp)` stomps the CCR bits the
| exception frame legitimately captured from the traced instruction
| (e.g. a `cmp` result) back to 0 on every single round-trip -- found
| live-debugging the trace_next_pc RTE-rearm fix: CORE_DEBUG
| instrumentation on commit.v/ccr_rat.v confirmed the RTL correctly
| threads arch_ccr through exc_fire_saved_sr into the pushed frame
| (e.g. Z=1 after `cmp.l %d1,%sp` when sp==d1), but this handler was
| discarding it, so `cmp.l %d1,%sp; bne _fail_sp_drift` always saw
| Z=0 and "failed" -- a test bug, not an RTL bug.
_trace_handler:
    or.w    #0x8000, (%sp)
    rte
