| exc_aline.s — A-line (1010) trap exception (vector 10)
|
| Hypothesis: any opword with bits [15:12] = 1010 is an unimplemented
| A-line instruction in the 68040 ISA; decode raises vector 10.
| Mac OS Toolbox is 100% A-line, so this is the critical path.
|
| Vector 10 lives at 0x00000028 (10 * 4).
|
| We emit a raw 0xA123 word via `dc.w` — the assembler won't
| recognise it as an instruction, but the CPU's decode stage will
| pattern-match on [15:12]=1010 and raise the exception.
|
| PASS: handler sentinel.
| FAIL: fallthrough sentinel.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000028   | vector 10 @ 0x28
    .short  0xA123                  | A-line opword — decode → exc vec 10

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
