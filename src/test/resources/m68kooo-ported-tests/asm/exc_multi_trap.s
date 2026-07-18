| exc_multi_trap.s — first of two TRAPs in sequence fires correctly
|
| Hypothesis: the exception sequencer correctly enters the first
| handler.  Since phase-2.1 does not yet implement RTE, we can't
| actually return and try the second TRAP — but we DO want to verify
| that when two TRAP instructions sit back-to-back, the FIRST one
| is taken as an exception (not somehow folded with the second, not
| mispredicted past, etc.).
|
| The first TRAP is TRAP #1 (vec 33, @ 0x84); the second (TRAP #2,
| @ 0x88) is placed right after but will never execute because
| handler halts.  If the first TRAP fails to dispatch, control falls
| through to the second TRAP — if neither dispatches we fall into
| the _fail marker.
|
| To be extra paranoid, the handler verifies it came from TRAP #1
| by checking its own entry PC indirectly: it reads back a flag D7
| that the mainline set before the first TRAP, and that the would-be
| post-first-TRAP code would have overwritten had it executed.
|
| PASS: handler runs with D7 == 0x11111111 (mainline's value).
| FAIL: D7 ended up as 0x22222222 (second trap ran) or neither
|       handler fired (fallthrough).

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000084   | vector 33 (TRAP #1)
    move.l  #_handler, 0x00000088   | vector 34 (TRAP #2) — should NOT fire

    move.l  #0x11111111, %d7        | sentinel — set BEFORE first trap
    trap    #1                      | should dispatch HERE
    move.l  #0x22222222, %d7        | if first TRAP missed, d7 becomes this
    trap    #2
    | Fallthrough
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_handler:
    cmp.l   #0x11111111, %d7        | expect mainline's pre-TRAP value
    bne     _fail_h
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt_pass:
    bra     _halt_pass

_fail_h:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fh:
    bra     _halt_fh
