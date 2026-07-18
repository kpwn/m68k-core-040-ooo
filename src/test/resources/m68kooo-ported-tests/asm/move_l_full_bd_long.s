| move_l_full_bd_long.s -- MOVE.L full-format with 32-bit base disp (BD_SIZE=11)
|
| Verifies that the 32-bit base displacement is read correctly from
| ext2:ext3.  Previously the V2 chain only forwarded 2 ext words so BD.L
| would be silently truncated; this landing widens to 3 ext words.

    .text
    .org 0

_start:
    | MOVE.L (bd.L, A0, D1.L*4), D2:
    |   ext1 = 0_001_1_10_1_0_0_11_000 = 0001_1101_0011_0000 = 0x1d30
    |   bits: D1=001, W/L=1(long), scale=10(x4), bit8=1, BS=0, IS=0, BD=11(long)
    |   bd.L = 0x00012345
    |   D1.L = 3
    |   A0 = 0x00100000
    |   EA = A0 + bd.L + D1.L*4 = 0x00100000 + 0x00012345 + 0xC = 0x00112351
    move.l  #0x00100000, %a0
    move.l  #3, %d1
    move.l  #0xABCD1234, 0x00112351
    move.l  #0, %d2
    .word   0x2430, 0x1d30, 0x0001, 0x2345
    cmp.l   #0xABCD1234, %d2
    bne     _fail1

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d0

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
