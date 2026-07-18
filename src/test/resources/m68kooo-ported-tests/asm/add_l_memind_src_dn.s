| add_l_memind_src_dn.s — Task #208 (C2)
|
| Directed test for ADD.L with full-format memory-indirect SOURCE and
| Dn destination.  Covers no-index memind EA.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00115f00, %a7

    | -- No-index: ADD.L ([32,A4]),D0
    |    EA = *[A4+32] + 0
    lea     0x00115200, %a4
    lea     0x00115220, %a0
    move.l  #0x00115400, (%a0)       | [A4+32] = pointer 0x00115400
    lea     0x00115400, %a0
    move.l  #0x00001111, (%a0)       | [target] = 0x00001111
    move.l  #0x00002222, %d0         | D0 = 0x00002222
    | ADD.L <mem>,D0 = 1101_ddd_010_mmm_rrr  (op[8]=0, size=10 .L)
    |   dst D0 = 000, so: 1101_000_010_110_100 = 0xD0B4
    |   For memind we need mode 110 reg 100 (A4), ext1=0x0164.
    .word   0xD0B4, 0x0164, 0x0020
    | D0 should be 0x2222 + 0x1111 = 0x3333.
    cmp.l   #0x00003333, %d0
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
