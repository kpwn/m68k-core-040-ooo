| irq_at_rts_fetch_sweep2.s — variant 2 of the live-HW spurious-bus-
| error-on-RTS repro attempt.  Same shape as irq_at_rts_fetch_sweep.s
| but the called subroutine spans MANY 16-byte I-cache lines (so
| if_stage has continuous prefetch activity — if_req is asserted for a
| meaningful fraction of every cycle, not just briefly at cold start)
| ending in RTS.  This widens the window for a redirect (IRQ-take or
| RTE-return) to land on the SAME cycle as an in-flight fetch response,
| which the all-tiny-loop-body variant 1 could not exercise (loop body
| fits in 1-2 lines, both resident after the first iteration, so
| if_stage is essentially always idle — no in-flight request for a
| redirect to race against).
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinel: 0xDEAD0BE2 (vector-2 bus error fired during the loop).

    .text
    .org 0

    .equ PASS_SENT,  0xFFFF0000
    .equ LOOP_COUNT, 0x00011000
    .equ IRQ_COUNT,  0x00011004

_start:
    lea     0x00012000, %a7
    move.l  #_bus_err_handler, 0x00000008   | vector 2 @ 0x08 (bus error)
    move.l  #_lvl1_handler, 0x00000064      | vector 25 @ 0x64 (autovec lvl1)

    move.l  #0, LOOP_COUNT
    move.l  #0, IRQ_COUNT

    move.l  #60, %d7        | loop iterations (subroutine itself is long)

_loop:
    jsr     _sub
    addq.l  #1, LOOP_COUNT
    subq.l  #1, %d7
    bne     _loop

_pass:
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

| ── The hot-but-LONG subroutine ───────────────────────────────────────
| ~50 nops (~100 bytes, > 6 I-cache lines) then rts.  Spans many lines
| so if_stage is continuously prefetching l1 across the whole call —
| if_req is asserted a meaningful fraction of the time.
_sub:
    .rept 50
    nop
    .endr
    tst.w   %d0
    rts

| ── Vector 2 (bus error) handler ──────────────────────────────────────
_bus_err_handler:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0BE2, %d0
    move.l  %d0, (%a1)
_halt_be:
    bra     _halt_be

| ── Vector 25 (autovector level 1) handler ────────────────────────────
| Also spans a couple lines with some real work (loads/stores) so the
| handler's OWN prefetch activity is in flight when it RTEs.
_lvl1_handler:
    addq.l  #1, IRQ_COUNT
    move.l  IRQ_COUNT, %d1
    move.l  %d1, -(%a7)
    move.l  (%a7)+, %d1
    nop
    nop
    nop
    nop
    rte
