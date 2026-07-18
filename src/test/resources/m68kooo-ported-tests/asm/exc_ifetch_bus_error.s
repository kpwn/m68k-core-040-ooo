| exc_ifetch_bus_error.s — Bus error on unmapped instruction fetch
|
| Fetch responses may fault speculatively, but the fault is only
| architectural once the faulting instruction stream reaches commit.
| Jumping to 0xAAAA_0000 should therefore dispatch a precise vector-2
| exception and land in the handler below, rather than stopping the
| harness or wedging on a bad prefetch line.
|
| PASS: vector-2 handler writes sentinel.
| FAIL: fallthrough or wrong handler writes fail sentinel.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000008   | vector 2 @ 0x08
    lea     0xAAAA0000, %a0         | unmapped — instruction fetch DECERR
    jmp     (%a0)

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d1
    move.l  %d1, (%a1)
_halt_fail:
    bra     _halt_fail

_handler:
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a1)
_halt:
    bra     _halt
