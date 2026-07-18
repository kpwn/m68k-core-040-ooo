| exc_stack_frame_format.s — verify format-0 frame pushed by TRAP #0
|
| Hypothesis: on TRAP #0 the exception sequencer builds a 4-word
| format-0 stack frame containing:
|   - saved SR (word)
|   - saved PC (long, pointing at the instruction AFTER the TRAP)
|   - format+vector word (format_code << 12 | vec_offset)
| Layout per exceptions.md states 3..5 — exact byte order depends on
| the final sequencer implementation, so we only sanity-check:
|   (a) The handler actually runs.
|   (b) A7 has been decremented by >= 8 bytes (frame was pushed).
|   (c) There is a 32-bit word on the stack whose low 16 bits look
|       like vector 32's table offset — bits [11:0] == 0x080.
|
| Saved-PC checks are deliberately loose since the exact "next PC"
| after TRAP #0 depends on whether the stacked PC points at the
| TRAP or the instruction following.  Both are allowed by the m68k
| manual depending on frame format; Phase 2.1 convention is
| "PC-of-next-instruction".
|
| PASS: handler sees vector offset 0x080 in the stacked format word.
| FAIL: anything else.

    .text
    .org 0

_start:
    lea     0x00010000, %a7         | stack scratch
    move.l  %a7, %a6                | save pre-trap SP in A6 for handler check
    lea     0x00000080, %a0         | vector 32 @ 0x80
    move.l  #_handler, %d0
    move.l  %d0, (%a0)
    trap    #0

    | Fallthrough = no dispatch
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_handler:
    | Check (b): SP must have dropped by at least 8 bytes
    move.l  %a6, %d0                | d0 = old SP
    sub.l   %a7, %d0                | d0 = old SP - new SP = frame size
    cmp.l   #8, %d0
    blt     _fail_h                 | frame too small → fail

    | Check (c): scan the frame for a word whose low 12 bits == 0x080.
    | We look at both possible locations: SP+0 (word) and SP+6 (word)
    | in case the sequencer chose either format byte order.
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
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fh:
    bra     _halt_fh

_pass_h:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
