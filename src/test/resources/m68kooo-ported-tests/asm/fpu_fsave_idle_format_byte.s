| fpu_fsave_idle_format_byte.s — verify FSAVE writes 68040 IDLE frame (0x41)
|
| Per the 68040 PRM, FSAVE writes a state frame whose first byte is the
| version/type byte: 0x40 (NULL) or 0x41 (IDLE) for 4-byte short frames.
| Our implementation tracks no in-flight FPU state worth checkpointing
| (no exception sequencer mid-op; the side-channel mechanism in commit.v
| stashes FPCR/FPSR/FPIAR at retire), so the architecturally correct
| frame is IDLE: "FPU has been used but no in-flight work".
|
| Writing IDLE (0x41) instead of NULL (0x40) keeps OS code from skipping
| the matched FRESTORE under the optimisation
|   if ((frame[0] & 0xf) == 0) skip_restore;
|
| PASS: version/type byte at A7+0 (highest pushed byte for predec) == 0x41.
| FAIL: byte != 0x41.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7

    | FSAVE -(A7) — pushes the 4-byte frame.  After this, A7 -= 4 and
    | mem[A7..A7+3] holds the frame, with the format byte at A7+0.
    .short  0xF327                     | FSAVE -(A7)

    | Read the format byte (high byte of long at (A7)).
    move.b  (%a7), %d0
    andi.l  #0xFF, %d0
    cmp.l   #0x41, %d0
    bne     _fail

    | PASS
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD1801, %d2
    move.l  %d2, (%a1)
_halt_f:
    bra     _halt_f
