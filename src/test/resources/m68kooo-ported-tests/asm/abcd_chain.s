| abcd_chain.s — Multi-byte BCD addition via X-flag propagation
|
| Demonstrates the intended use of ABCD: implement wide-precision BCD
| arithmetic by chaining byte-level BCD add + X-flag carry.
|
| Compute BCD(0x123456) + BCD(0x876544) = BCD(0x100_0000).
|   byte 0 (LSB):  0x56 + 0x44 = 0x00, X=1   (low-byte wrap)
|   byte 1      :  0x34 + 0x65 + 1 = 0x00, X=1
|   byte 2 (MSB):  0x12 + 0x87 + 1 = 0x00, X=1
| Final result in D0/D1/D2 = 0/0/0.  We chain ABCDs back-to-back so the
| X flag flows through; inserting any CMP or MOVE-to-flags between ABCDs
| would clobber X and break the chain.

    .text
    .org 0

_start:
    | Source A bytes (LSB..MSB)
    move.l  #0x00000056, %d0          | a_lo
    move.l  #0x00000034, %d1          | a_mid
    move.l  #0x00000012, %d2          | a_hi

    | Source B bytes (LSB..MSB)
    move.l  #0x00000044, %d3          | b_lo
    move.l  #0x00000065, %d4          | b_mid
    move.l  #0x00000087, %d5          | b_hi

    | Clear X for the LSB add.
    moveq   #0, %d6
    add.l   %d6, %d6                  | X=0

    | Chain three ABCDs back-to-back so X flows through.
    abcd    %d3, %d0                  | 0x56 + 0x44 + 0 = 0x00, X=1
    abcd    %d4, %d1                  | 0x34 + 0x65 + 1 = 0x00, X=1
    abcd    %d5, %d2                  | 0x12 + 0x87 + 1 = 0x00, X=1 (final carry)

    | Now verify all three results are zero (do this AFTER the chain).
    tst.l   %d0
    bne     _fail
    tst.l   %d1
    bne     _fail
    tst.l   %d2
    bne     _fail

    | ── Second round: no-wrap chain 0x010203 + 0x040506 = 0x050709 ──
    move.l  #0x00000003, %d0          | a_lo
    move.l  #0x00000002, %d1          | a_mid
    move.l  #0x00000001, %d2          | a_hi
    move.l  #0x00000006, %d3          | b_lo
    move.l  #0x00000005, %d4          | b_mid
    move.l  #0x00000004, %d5          | b_hi
    moveq   #0, %d6
    add.l   %d6, %d6                  | X=0

    abcd    %d3, %d0                  | 3+6+0 = 9, X=0
    abcd    %d4, %d1                  | 2+5+0 = 7, X=0
    abcd    %d5, %d2                  | 1+4+0 = 5, X=0

    move.l  #0x00000009, %d7
    cmp.l   %d7, %d0
    bne     _fail
    move.l  #0x00000007, %d7
    cmp.l   %d7, %d1
    bne     _fail
    move.l  #0x00000005, %d7
    cmp.l   %d7, %d2
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
