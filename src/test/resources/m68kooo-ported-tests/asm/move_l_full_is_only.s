| move_l_full_is_only.s -- MOVE.L full-format with IS=1 (index suppressed)
|
| Covers full-format I/IS=000 with IS=1: EA = An + bd (no index).
| The decode_ea_v2 should route this as a plain base+disp (is_indexed=0)
| so the standard non-indexed memory crack handles it — equivalent to
| (d16,An) or absolute but with a widened (word or long) displacement.

    .text
    .org 0

_start:
    | IS=1, BS=0, BD_SIZE=10 (word):
    |   ext1 = 0_000_0_00_1_0_1_10_000 = 0000_0001_0110_0000 = 0x0160
    |   A0 = 0x00100000, bd.W = 0x1234 (sign-ext)
    |   EA = A0 + 0x1234 = 0x00101234
    move.l  #0x00100000, %a0
    move.l  #0xCAFEBABE, 0x00101234
    move.l  #0, %d2
    .word   0x2430, 0x0160, 0x1234
    cmp.l   #0xCAFEBABE, %d2
    bne     _fail1

    | IS=1, BS=1, BD_SIZE=11 (long):
    |   ext1 = 0_000_0_00_1_1_1_11_000 = 0000_0001_1111_0000 = 0x01f0
    |   No base, no index, just long bd
    |   bd = 0x00100040 — treated like absolute long
    move.l  #0x87654321, 0x00100040
    move.l  #0, %d3
    .word   0x2630, 0x01f0, 0x0010, 0x0040
    cmp.l   #0x87654321, %d3
    bne     _fail2

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d0
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d0

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
