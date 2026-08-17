| exc_fline.s — F-line (1111) trap exception (vector 11)
|
| Generic unimplemented F-line opwords raise vector 11.  Non-FPU
| coprocessor IDs use a format-$0 frame; on-chip FPU unsupported
| instructions use the ROM FPSP's format-$2 path.
|
| Vector 11 lives at 0x0000002C (11 * 4).
|
| Use 0xFFFF rather than 0xF123.  Musashi/68040 decodes 0xF123 as
| PSAVE/PMMU, so it can fail in the reference before reaching vector
| 11; 0xFFFF is a generic line-F trap in that model.
|
| PASS: handler sentinel.
| FAIL: fallthrough sentinel.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x0000002C   | vector 11 @ 0x2C
    .short  0xFFFF                  | F-line opword — decode → exc vec 11

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
