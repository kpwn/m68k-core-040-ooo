| exc_rte.s — RTE (return from exception) round-trip test
|
| Installs a TRAP #0 handler that simply executes RTE.  After the RTE
| returns control to the instruction after TRAP, the user code writes
| the PASS sentinel.  If RTE fails, the handler loops / execution
| falls into _halt_fail and we write FAIL.
|
| PASS: user code after RTE writes C0FFEE00.
| FAIL: fallthrough writes DEADBEEF (shouldn't be reachable if RTE works).

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000080   | vector 32 @ 0x80
    trap    #0

    | After RTE we should be here.  Write PASS sentinel.
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

    | Fallthrough (unreachable if RTE works, kept for diagnostic).
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_handler:
    rte
