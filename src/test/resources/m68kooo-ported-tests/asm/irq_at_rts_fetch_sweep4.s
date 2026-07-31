| irq_at_rts_fetch_sweep4.s — variant 4: NESTED IRQ version.  Live HW
| evidence shows a dense flurry of exceptions (F-line traps, each its
| own entry+RTE) immediately preceding the spurious vec=2 bus error at
| an ordinary RTS.  Variants 1-3 (single IRQ, warm+cold, small+large
| subroutine) swept ~4700 cycle-phases with zero repro.  This variant
| nests a SECOND, higher-priority IRQ inside the first handler (so the
| first handler's own RTE-return redirect races a second IRQ-take
| redirect, stacking two live redirects instead of one) while the
| interrupted PC is a cold-miss RTS fetch, matching the "storm right
| before the fault" shape of the live HW evidence more closely.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinel: 0xDEAD0BE2 (vector-2 bus error — the repro).

    .text
    .org 0

    .equ PASS_SENT,  0xFFFF0000
    .equ IRQ1_COUNT, 0x00011004
    .equ IRQ2_COUNT, 0x00011008

_start:
    lea     0x00012000, %a7
    move.l  #_bus_err_handler, 0x00000008   | vector 2 @ 0x08 (bus error)
    move.l  #_lvl1_handler, 0x00000064      | vector 25 @ 0x64 (autovec lvl1)
    move.l  #_lvl2_handler, 0x00000068      | vector 26 @ 0x68 (autovec lvl2)

    move.l  #0, IRQ1_COUNT
    move.l  #0, IRQ2_COUNT

    .rept 400
    nop
    .endr

    jsr     _sub

_pass:
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_sub:
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
| Deliberately leaves interrupts enabled at level 1 (autovector entry
| sets SR.IPL=1) so a level-2 IRQ CAN nest here.  A few filler
| instructions before RTE give the nested IRQ room to land mid-handler.
_lvl1_handler:
    addq.l  #1, IRQ1_COUNT
    nop
    nop
    nop
    nop
    nop
    nop
    rte

| ── Vector 26 (autovector level 2) handler — the nested one ──────────
_lvl2_handler:
    addq.l  #1, IRQ2_COUNT
    rte
