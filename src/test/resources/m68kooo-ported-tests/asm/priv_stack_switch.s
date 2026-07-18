| priv_stack_switch.s — exception entry pushes frame to SSP when
| caller was in supervisor.
|
| Hypothesis: with SR.S=1 from reset, the current A7 IS the SSP.
| When an exception fires (here TRAP #0), the sequencer pushes a
| format-0 frame to that same stack, decrementing A7 by at least
| 8 bytes, and leaves vector 32's byte offset (=0x80) encoded in the
| format word.  This mirrors exc_stack_frame_format.s but tightens the
| check by also verifying A7 decremented by >=8 AND <=12 (format 0
| is 8 bytes; format 2 is 12).
|
| PASS: handler sees valid frame + A7 dropped 8 or 12 bytes.
| FAIL: A7 drop out of range OR vector-offset mismatch.
|
| Supervisor stays supervisor across this — no mode switch.  Just
| verifies the SSP path of the sequencer.

    .text
    .org 0

_start:
    lea     0x00010000, %a7          | SSP = 0x00010000
    move.l  %a7, %a6                 | save old SSP for handler check
    lea     0x00000080, %a0          | vec 32 @ 0x80
    move.l  #_handler, %d0
    move.l  %d0, (%a0)
    trap    #0

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_handler:
    | Frame-size check: 8 <= (old - new) <= 12
    move.l  %a6, %d0
    sub.l   %a7, %d0
    cmp.l   #8, %d0
    blt     _fail_h
    cmp.l   #12, %d0
    bgt     _fail_h

    | Vector-offset check: low 12 bits of the format word == 0x080.
    | Format word position varies by sequencer choice; probe two
    | candidate offsets (SP+0 and SP+6) per exc_stack_frame_format.
    move.w  (%a7), %d1
    and.w   #0x0FFF, %d1
    cmp.w   #0x080, %d1
    beq     _pass_h
    move.w  6(%a7), %d1
    and.w   #0x0FFF, %d1
    cmp.w   #0x080, %d1
    beq     _pass_h

_fail_h:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEE1, %d0
    move.l  %d0, (%a0)
_halt_fh:
    bra     _halt_fh

_pass_h:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
