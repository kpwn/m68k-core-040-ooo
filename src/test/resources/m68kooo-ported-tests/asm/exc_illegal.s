| exc_illegal.s — ILLEGAL opcode (vector 4)
|
| Hypothesis: opword 0x4AFC is the 68040-reserved "ILLEGAL"
| instruction.  Decode recognises it explicitly and raises vector 4.
| Any other unrecognised opword that doesn't match A-line / F-line
| should ALSO take vector 4, but 0x4AFC is the canonical guaranteed
| trigger.
|
| Vector 4 lives at 0x00000010 (4 * 4).
|
| PASS: handler sentinel.
| FAIL: fallthrough sentinel.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000010   | vector 4 @ 0x10
    .short  0x4AFC                  | ILLEGAL — decode → exc vec 4

    | If decode didn't raise the trap, fall through to FAIL
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
