| exc_aline_stacked_pc.s — verify Format-0 stacked PC for A-line equals
| the A-line word's PC, not the previous-retired instruction's PC.
|
| Hypothesis under test (HW JTAG hint, 2026-05-13): debug-register
| `exc_pc` readback was observed at PC-2 (= MOVEQ before the A-line)
| instead of the A-line word itself.  This test isolates whether the
| ACTUALLY-PUSHED frame PC is also off-by-2, or whether `exc_pc` is
| just a debug-register quirk (the OS keeps running thousands of
| A-lines successfully → frame must be correct).
|
| Setup:
|   - VBR=0 (default at reset).  Vector 10 lives at 0x00000028.
|   - Install handler at 0x00000028.
|   - _aline_target is a known-PC A-line word, preceded by a 2-byte
|     MOVEQ that ALSO ends with an opword distinct from 1010 (so we
|     can tell which one's PC ends up stacked).
|
| Handler:
|   - Reads A7+2 / A7+4 = stacked PC (high / low half-words).
|   - If stacked-PC == address-of-A-line-word → PASS.
|   - If stacked-PC == address-of-MOVEQ → FAIL with 0xDEAD_0001
|     (this is what the HW exc_pc readback shows; the question is
|     whether the FRAME is the same).
|   - If stacked-PC == anything else → FAIL with 0xDEAD_0002.
|
| PASS: 0xC0FFEE00 at 0xFFFF0000.
| FAIL: 0xDEAD0001 (= stacked PC = PC-2, frame off-by-2 confirmed)
|        0xDEAD0002 (= stacked PC mismatch, unrelated bug)

    .text
    .org 0

_start:
    | Plant the handler vector at low-RAM 0x28 (VBR=0 at reset).
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000028

    | Run-up MOVEQ that we explicitly DO NOT want the stacked PC to
    | refer to.  The next opword is the A-line itself.
    .align 4
_run_up:
    moveq.l #1, %d0              | 2 bytes, opword 0x7001
_aline_target:
    .short  0xA123               | 2-byte A-line opword

    | If decode didn't trap, fall through to FAIL_NO_TRAP.
_fail_no_trap:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0003, %d0
    move.l  %d0, (%a0)
_halt_no_trap:
    bra     _halt_no_trap

| ── Handler at vector 10 ─────────────────────────────────────────────
| Format-0 frame layout at entry:
|   A7+0  SR     (16-bit)
|   A7+2  PC_hi  (16-bit)
|   A7+4  PC_lo  (16-bit)
|   A7+6  fmt/vec (16-bit, nibble=0, low byte=vec*4=0x28)
| Stacked PC reassembled = (PC_hi << 16) | PC_lo.

_handler:
    | Reassemble stacked PC from frame.
    move.w  2(%a7), %d0          | PC_hi
    swap    %d0
    move.w  4(%a7), %d0          | PC_lo (replaces low half — preserves high)
    | Wait — move.w to low half clears high; redo via OR.
    move.w  2(%a7), %d0
    swap    %d0
    clr.w   %d0
    move.w  4(%a7), %d1
    or.l    %d1, %d0             | %d0 = (PC_hi<<16) | PC_lo

    | Expected: address of _aline_target.
    move.l  #_aline_target, %d1
    cmp.l   %d0, %d1
    beq     _pass

    | Mismatch — distinguish "off-by-2 to MOVEQ" from "other".
    move.l  #_run_up, %d1
    cmp.l   %d0, %d1
    beq     _fail_off_by_2

    | Some other unexpected stacked PC.
    bra     _fail_other

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt_pass:
    bra     _halt_pass

_fail_off_by_2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
_halt_fail_2:
    bra     _halt_fail_2

_fail_other:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0002, %d0
    move.l  %d0, (%a0)
_halt_fail_other:
    bra     _halt_fail_other
