| exc_trap_chain.s — TRAP #0 handler writes PASS marker; main flow
| never resumes (no RTE available in phase 2.1).
|
| Hypothesis: once TRAP #0 dispatches, the mainline code past the
| TRAP is architecturally squashed (the fault μop commits without
| touching arch state and flush_en wipes every newer μop).  Since
| phase 2.1 has no RTE, the handler can only commit its OWN work,
| not return — so a D0 write placed in the mainline AFTER the TRAP
| must NOT be visible.
|
| Mechanic: mainline sets D0 = DEADBEEF BEFORE the trap; TRAP #0
| handler re-sets D0 = C0FFEE00 and stores it.  If the mainline's
| post-TRAP writes leaked through (squash failure), D0 would be
| clobbered AFTER the handler's write before the sentinel lands.
|
| PASS: sentinel 0xC0FFEE00.
| FAIL: sentinel is 0xDEADBEEF (mainline leaked) or anything else.
|
| Vec 32 (TRAP #0) lives at 0x00000080.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000080   | vec 32 @ 0x80
    move.l  #0xDEADBEEF, %d0        | pre-TRAP marker
    trap    #0                      | should NOT return

    | These lines MUST NEVER execute.  If they do, D0 gets clobbered
    | with FAIL values.
    move.l  #0xDEADBEE2, %d0
    lea     0xFFFF0000, %a0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_handler:
    | D0 still holds 0xDEADBEEF from the mainline here (renaming
    | snapshot pre-TRAP).  Overwrite with PASS and store.
    move.l  #0xC0FFEE00, %d0
    lea     0xFFFF0000, %a0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
