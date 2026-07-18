| exc_rte_format_error.s — malformed RTE frame dispatches vector 14
|
| RTE must not complete if the stacked format word is unsupported.  The
| exception sequencer reports the bad format nibble to commit; commit
| should raise the format-error exception (vector 14) instead of
| restoring SR/PC from the malformed frame.
|
| PASS: vector-14 handler runs.
| FAIL: RTE incorrectly resumes at the stacked PC, or no handler runs.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_format_handler, 0x00000038   | vector 14 @ 0x38

    | Hand-build an 8-byte frame with a bad format nibble at SP+6.
    | Layout expected by exception.v's RTE pop:
    |   SP+0: SR
    |   SP+2: PC high
    |   SP+4: PC low
    |   SP+6: format/vector word
    subq.l  #8, %a7
    move.w  #0x2000, (%a7)                 | supervisor SR
    move.l  #_bad_resume, %d0
    swap    %d0
    move.w  %d0, 2(%a7)                    | bad resume PC high
    swap    %d0
    move.w  %d0, 4(%a7)                    | bad resume PC low
    move.w  #0x9000, %d0
    move.w  %d0, 6(%a7)                    | format=9 => vec 14
    rte

_bad_resume:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_bad:
    bra     _halt_bad

_format_handler:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
