| exc_trap0.s — TRAP #0 synchronous exception (vector 32)
|
| Hypothesis: TRAP #0 dispatches to mem[VBR + 32*4].  With VBR=0,
| vector 32 lives at physical address 0x00000080.
|
| This is the simplest TRAP form and the one Mac OS _A-line handlers
| often chain into.  Verifying it nails down the baseline entry path.
|
| PASS: handler writes C0FFEE00 sentinel.
| FAIL: fallthrough writes DEADBEEF sentinel.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000080   | vector 32 @ 0x80
    trap    #0

    | Fallthrough = no dispatch happened
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_handler:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
