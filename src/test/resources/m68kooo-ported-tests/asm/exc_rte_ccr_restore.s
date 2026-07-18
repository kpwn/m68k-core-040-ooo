| exc_rte_ccr_restore.s — RTE must restore CCR, not just SR[15:5].
|
| Sequence:
|   1. Set Z=1 in supervisor mode.
|   2. TRAP #0 to a handler that deliberately clears Z.
|   3. RTE back to the caller.
|   4. BEQ must see the restored Z=1 and branch to PASS.
|
| Without CCR restore, the handler's clobber survives and BEQ falls
| through to FAIL.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000080   | vector 32

    moveq   #0, %d0                 | Z=1 before TRAP
    trap    #0

    beq     _pass                   | restored Z must still be set

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_handler:
    moveq   #1, %d0                 | Z=0, clobber the live CCR
    rte

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
