| clr_l_memind.s — Task #208 (C2)
|
| Directed test for CLR.L with full-format memory-indirect destination.
| CLR writes literal zero to the computed EA (no LOAD before STORE).
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00115f00, %a7

    | -- No-index: CLR.L ([32,A4])  clears [ [A4+32] + 0 ]
    lea     0x00115200, %a4
    lea     0x00115220, %a0
    move.l  #0x00115400, (%a0)       | [A4+32] = pointer 0x00115400
    lea     0x00115400, %a0
    move.l  #0xDEADBEEF, (%a0)       | [target] = non-zero pre-test value
    | CLR.L opword = 0100_1010_10_mmm_rrr = 0x42B4 for mode=110 reg=100 (A4).
    |   Hmm: CLR = 0x4280 | ss<<6 | mmm<<3 | rrr.
    |   For .L (ss=10) mode=110 A4: 0x4280 | 0x80 | 0x34 = 0x42B4.
    | ext1 bit8=1 BS=0 IS=1 bd=W I/IS=100 = 0x0164.
    .word   0x42B4, 0x0164, 0x0020
    | Post-CLR, the target slot at 0x00115400 must be 0.
    lea     0x00115400, %a0
    move.l  (%a0), %d0
    tst.l   %d0
    bne     _fail1

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
