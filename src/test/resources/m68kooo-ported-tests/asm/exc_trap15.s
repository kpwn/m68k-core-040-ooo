| exc_trap15.s — TRAP #15 synchronous exception (vector 47)
|
| Hypothesis: TRAP #n dispatches to the handler at
|   mem[VBR + (32+n)*4].  With VBR=0 (phase-2.1 default), vector 47
|   lives at physical address 0x000000BC (47 * 4 = 0xBC).
|
| Setup:
|   1. Initialise A7 to scratch RAM at 0x00010000.
|   2. Write handler PC (_handler) into the vector slot at 0xBC.
|   3. Execute TRAP #15 — the exception sequencer should fetch the
|      new PC from 0xBC and resume at _handler.
|
| PASS: handler writes the sentinel at 0xFFFF0000.
| FAIL: if TRAP falls through (i.e. the exception never fires), the
| fallthrough code writes the FAIL sentinel.
|
| NOTE: Phase 2.1 doesn't implement RTE, so the handler simply
| halts after writing the sentinel.

    .text
    .org 0

_start:
    lea     0x00010000, %a7         | supervisor stack scratch
    | Install handler at vector 47 (0xBC = 47*4)
    move.l  #_handler, 0x000000BC
    | Trigger the exception
    trap    #15

    | If we get here, TRAP #15 did NOT dispatch — fail
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_handler:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)              | PASS sentinel
_halt:
    bra     _halt
