| exc_bus_error_chain.s — Mac-ROM-style probe pattern: multiple
| bus errors to different unmapped addresses, counter verified.
|
| The Mac ROM SIMM-detection code probes a fixed set of addresses
| to size the installed RAM.  Each probe that faults increments a
| "no-memory-here" counter.  The RTL must handle back-to-back
| bus-error exceptions:
|   1. Fire exc on probe → handler skips inst, RTEs.
|   2. Mainline probes next address → faults again.
|   3. Handler increments counter + skips + RTEs.
|   4. Repeat.
|
| If the ROB / exception sequencer / RTE path leaks state between
| probes (stale phys-regs, stuck PRF slots, off-by-one A7) the chain
| breaks and the counter won't match.
|
| Probes: 0xDEAD0000, 0xDEAD1000, 0xDEAD2000  (three distinct
| unmapped long-aligned addresses).  Expected counter = 3.
|
| PASS: counter == 3 → sentinel 0xC0FFEE00.
| FAIL: counter != 3 → sentinel 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000008   | vector 2 handler
    moveq   #0, %d7                 | handler-hit counter

    | Probe #1: 0xDEAD0000
    lea     0xDEAD0000, %a0
    move.l  (%a0), %d0              | 2-byte faulting MOVE

    | Probe #2: 0xDEAD1000
    lea     0xDEAD1000, %a0
    move.l  (%a0), %d0              | 2-byte faulting MOVE

    | Probe #3: 0xDEAD2000
    lea     0xDEAD2000, %a0
    move.l  (%a0), %d0              | 2-byte faulting MOVE

    | After all three, check D7 == 3
    cmp.l   #3, %d7
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
    | Skip past the 2-byte faulting MOVE.
    move.l  2(%a7), %d0
    addq.l  #2, %d0
    move.l  %d0, 2(%a7)
    | Bump hit counter.
    addq.l  #1, %d7
    rte
