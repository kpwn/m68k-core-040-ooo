| movec_itt0.s — program ITT0 and verify it via MOVEC read-back.
|
| DEFERRED (two blockers):
|   1. MOVEC is stubbed — ITT0 control register not writable from
|      assembly (movec-vbr pending).  Write is a NOP, read-back
|      yields 0.
|   2. The phase-2 MMU stub (rtl/core/mem/mmu.v) exists as a unit
|      but is not wired into the AGU/LSU/fetch path (mmu-integrate
|      agent is the gate for a more meaningful end-to-end test).
|
| When the movec-vbr agent lands we expect ITT0 to round-trip
| through the control-register file.  End-to-end MMU behaviour
| still requires mmu-integrate but this test only checks the
| MOVEC register plumbing for ITT0.
|
| Sequence:
|   1. Write ITT0 = 0x4000C000  (base=0x40000000, mask=0x0FFF, E=1).
|   2. Read ITT0 back into D1.
|   3. Compare.  PASS on equal, FAIL otherwise.
|
| Until MOVEC is real, D1 will be 0 (or uninitialised) and the
| CMP fails → FAIL sentinel.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #0x4000C000, %d0
    movec   %d0, %itt0
    movec   %itt0, %d1
    cmp.l   %d0, %d1
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, (%a1)
_halt_fail:
    bra     _halt_fail
