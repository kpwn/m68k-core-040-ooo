| irq_at_rts_fetch_sweep3.s — variant 3: cold-miss version.  A single
| JSR to a subroutine that has NEVER been fetched before (genuine
| I-cache miss + AXI fill on first fetch), ending in RTS.  Sweeps
| +ipl=<cycle>:1 across the whole short test, aiming to land the IRQ
| exactly at/around the miss-fill of the RTS's own line, or at the
| RTE-return re-fetch of it afterward.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinel: 0xDEAD0BE2 (vector-2 bus error — the repro).

    .text
    .org 0

    .equ PASS_SENT,  0xFFFF0000
    .equ IRQ_COUNT,  0x00011004

_start:
    lea     0x00012000, %a7
    move.l  #_bus_err_handler, 0x00000008   | vector 2 @ 0x08 (bus error)
    move.l  #_lvl1_handler, 0x00000064      | vector 25 @ 0x64 (autovec lvl1)

    move.l  #0, IRQ_COUNT

    | Padding so _sub lands on a fresh line far from _start's own
    | already-resident lines, and is genuinely cold on first JSR.
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
_lvl1_handler:
    addq.l  #1, IRQ_COUNT
    rte
