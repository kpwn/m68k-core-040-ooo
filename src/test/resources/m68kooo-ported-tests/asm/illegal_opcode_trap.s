| illegal_opcode_trap.s — end-to-end ILLEGAL (vec 4) integration test
|
| Task #133 companion to the upcoming #134 decode-default-flip.  The
| existing exc_illegal.s exercises 0x4AFC and just checks "handler
| runs"; this test also uses 0x4AFC (the only opword guaranteed to
| raise vec-4 today) but layers on Format-0 stack-frame size + saved-PC
| checks that we will rely on once #134 widens the trap to ANY
| undecoded opword.  Post-#134 the same framework tests the generalised
| default path without any edit here.
|
| Checks, in order:
|   (1) The handler runs at all (reset = fail).
|   (2) A7 was decremented by EXACTLY 8 bytes (Format-0 frame).
|   (3) The saved PC on the stack points at the ILLEGAL opword (0x40800010).
|       For Format-0: PC_hi at A7+2, PC_lo at A7+4 (top half of the
|       32-bit word written at A7+4).
|
| Vector 4 lives at 0x00000010 (4 * 4).
|
| Test binary layout keeps the handler close (at 0x40800020) so any
| gross mis-vectoring is visible in last_pc, not a wild jump.
|
| PASS: all checks pass → sentinel 0xC0FFEE00 at 0xFFFF0000.
| FAIL: any check fails → sentinel 0xDEADBEEF at 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7         | stack scratch
    move.l  %a7, %a3                | save pre-trap A7 for frame-size check
    move.l  #_handler, 0x00000010   | vector 4 @ 0x10
    .short  0x4AFC                  | ILLEGAL opword @ 0x40800010

    | Fallthrough: decode did not trap — mark FAIL
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_handler:
    | (2) frame-size: pre-A7 (in A3) - post-A7 must be exactly 8
    move.l  %a3, %d0
    sub.l   %a7, %d0
    cmp.l   #8, %d0
    bne     _fail_h

    | (3) saved PC check.  Stack frame at A7..A7+7 contains:
    |   A7+0: SR (word)
    |   A7+2: PC_hi (word)      ← 0x4080
    |   A7+4: PC_lo (word)      ← 0x0010
    |   A7+6: format/vec word
    | Read PC_hi and PC_lo and compare to the known ILLEGAL opword PC.
    move.w  2(%a7), %d1             | d1.w = PC_hi
    cmp.w   #0x4080, %d1
    bne     _fail_h
    move.w  4(%a7), %d1             | d1.w = PC_lo
    cmp.w   #0x0010, %d1
    bne     _fail_h

    | All checks passed.
_pass_h:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail_h:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fh:
    bra     _halt_fh
