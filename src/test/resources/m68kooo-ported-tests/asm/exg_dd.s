| exg_dd.s -- task #220 / E1 directed: EXG D0,D1 round-trip.
|
| Verifies single-µop dual-dst PRF EXG between two data registers
| preserves the full 32-bit value of each operand.  CCR must be
| untouched (we test by setting Z prior to EXG and reading it
| back via Bcc afterwards).
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Load distinguishable patterns into D0/D1.
    move.l  #0xCAFEBABE, %d0
    move.l  #0x12345678, %d1

    | Force Z=0 (and clear V/C/N) so the post-EXG Bcc reads stable CCR.
    moveq   #1, %d2
    tst.l   %d2

    | Single-µop swap.  After EXG: D0 = 0x12345678, D1 = 0xCAFEBABE.
    exg     %d0, %d1

    | CCR.Z should still be 0 (EXG never touches CCR).
    beq     _fail

    cmp.l   #0x12345678, %d0
    bne     _fail
    cmp.l   #0xCAFEBABE, %d1
    bne     _fail

    | Round-trip swap a second time — back to the originals.
    exg     %d0, %d1
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail
    cmp.l   #0x12345678, %d1
    bne     _fail

    | Same-register edge case (EXG D2,D2) — value must be preserved.
    move.l  #0xDEADBEEF, %d2
    exg     %d2, %d2
    cmp.l   #0xDEADBEEF, %d2
    bne     _fail

    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a6)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a6
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a6)
_fail_halt:
    bra     _fail_halt
