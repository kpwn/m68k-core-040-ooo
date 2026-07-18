| exc_bus_error_rte.s — bus-error handler adjusts stacked PC + RTE,
| mainline resumes past the faulting load.
|
| Mac ROM uses this exact pattern for SIMM/probe detection:
|   1. Attempt a load from an unknown address.
|   2. If bus error fires, the handler patches the stacked PC to
|      the instruction AFTER the faulting MOVE and RTEs.
|   3. Mainline continues, logs "no memory at this address".
|
| Frame is 68040 Format 7 (60 bytes):
|   SP+0 : SR             (word)
|   SP+2 : PC-of-MOVE     (long)  — points AT the faulting inst
|   SP+6 : fmt/vec        (word)
|   SP+8 : effective addr (long), then access-error state
|
| The faulting instruction `move.l (%a0), %d0` is 2 bytes (opword
| 0x2010), so we advance the stacked PC by +2 and RTE.  The
| handler also writes a marker into D7 so mainline can assert it
| ran.
|
| PASS: mainline after RTE writes 0xC0FFEE00 sentinel.
| FAIL: fallthrough or handler-unreachable path.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000008   | vector 2 handler
    moveq   #0, %d7                 | D7=0 — handler sets D7=1
    lea     0xDEAD0000, %a0         | unmapped → SLVERR → vec 2
    move.l  (%a0), %d0              | faulting inst (2 bytes)

    | Execution should resume here after RTE.
    cmp.l   #1, %d7                 | handler must have set D7=1
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d1
    move.l  %d1, (%a1)
_halt_fail:
    bra     _halt_fail

_handler:
    | Advance stacked PC past the 2-byte faulting MOVE.
    | Format 7 frame keeps PC as a longword at SP+2.
    move.l  2(%a7), %d0             | D0 = stacked PC (= PC of MOVE)
    addq.l  #2, %d0                 | +2 → after MOVE
    move.l  %d0, 2(%a7)             | write back
    moveq   #1, %d7                 | marker for mainline
    rte
