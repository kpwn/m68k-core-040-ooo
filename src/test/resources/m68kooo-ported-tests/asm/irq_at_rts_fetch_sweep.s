| irq_at_rts_fetch_sweep.s — Repro attempt for the live-HW "bus error on
| a completely ordinary RTS" finding (2026-07-04, exc#4818 @ Q700 ROM PC
| 0x408701b2).  Live HW state: exc-ring shows a level-1 autovector IRQ
| (vec=0x19) taken at PC=0x408701b2 (an in-range, well-formed `rts`
| instruction one line into the ROM's 1 MB window), immediately followed
| by a vec=0x02 (bus error) ALSO at PC=0x408701b2 — i.e. the interrupt's
| eventual RTE-return re-fetch of the interrupted RTS spuriously bus-
| errors on a completely ordinary, already-resident ROM line.
|
| This test builds the minimal shape: a hot (repeatedly-JSR'd, so
| i-cache-resident) subroutine ending in RTS, called in a tight loop.
| The harness sweeps +ipl=<cycle>:1 externally (one shot per sim
| invocation) across a range covering many loop iterations, to hit
| every possible phase relationship between the IRQ-take redirect, the
| handler's own fetch/RTE-return redirect, and the if_stage line-buffer
| state machine at the moment the interrupted RTS is re-fetched.
|
| Vector 2 (bus error) is wired to a handler that writes a FAIL sentinel
| and halts, so if the if_stage/icache redirect-race bug fires as a
| genuine spurious AXI fault, the test goes RED distinctly from a
| double-fault crash.
|
| PASS sentinel: 0xC0FFEE00 (loop completed, no spurious bus error).
| FAIL sentinel: 0xDEAD0BE2 (vector-2 bus error fired during the loop —
|                the repro).

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

    move.l  #400, %d7        | loop iterations — plenty of phase coverage

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

| ── The hot subroutine ────────────────────────────────────────────────
| Small, always resident after the first call (single 16-byte line: the
| whole body + rts fits in one I-cache line).  This mirrors the live-HW
| shape: `tst.w d0` / `rts` sitting at the tail of an ordinary routine.
_sub:
    tst.w   %d0
    nop
    rts

| ── Vector 2 (bus error) handler ──────────────────────────────────────
_bus_err_handler:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0BE2, %d0
    move.l  %d0, (%a1)
_halt_be:
    bra     _halt_be

| ── Vector 25 (autovector level 1) handler ────────────────────────────
| Minimal — bump a counter, RTE straight back.  Deliberately tiny so the
| sweep exercises tight timing between IRQ-take and RTE-return.
_lvl1_handler:
    addq.l  #1, IRQ_COUNT
    rte
