| move_l_full_bs_only.s -- MOVE.L full-format with BS=1 (An suppressed)
|
| Covers full-format I/IS=000 with BS=1: EA = 0 + bd + Xn*scale.
| Verifies that decode_ea_v2 substitutes REG_TMP0 (phys 16 = always 0)
| for the base so the ADD phase computes just bd+index*scale.

    .text
    .org 0

_start:
    | BS=1 (suppress A0), IS=0, BD_SIZE=10 (word), scale x2, D1.W:
    |   ext1 = 0_001_0_01_1_1_0_10_000 = 0001_0011_1010_0000 = 0x13a0
    |   Nibble breakdown: bit15=0, bits14:12=001(D1), bit11=0(W), bits10:9=01(x2),
    |     bit8=1(full), bit7=1(BS=1), bit6=0(IS=0), bits5:4=10(word bd), bits3:0=0000
    |   opword: MOVE.L (bd,An=supp,D1.W*2),D3 — dst_reg=D3=011, dst_mode=000,
    |           src_mode=110, src_reg=000 (An field ignored by BS=1 but still
    |           must be legal; assembler writes 000 for suppressed base)
    |     opword bits: 0010 011 000 110 000 = 0x2630
    |   ext2 = 0x4000 (bd = 0x4000)
    |   D1 = 3, so EA = 0 + 0x4000 + 6 = 0x00004006
    move.l  #0xAABBCCDD, 0x00004006
    moveq   #3, %d1
    move.l  #0, %d3
    .word   0x2630, 0x13a0, 0x4000
    cmp.l   #0xAABBCCDD, %d3
    bne     _fail1

    | Long bd, BS=1, IS=0, D0.L*4:
    |   ext1 = 0_000_1_10_1_1_0_11_000 = 0000_1101_1011_0000 = 0x0db0
    |   bits15:12=0 (D0), bit11=1 (L), bits10:9=10 (x4), bit8=1, bit7=1 (BS),
    |     bit6=0 (IS=0), bits5:4=11 (long bd), bits3:0=0000
    |   Nibble: bit8=1,bit7=1,bit6=0,bit5=1,bit4=1 → [11:8]=1101=d, [7:4]=1011=b,
    |           [15:12]=0000, [3:0]=0000 → 0x0db0
    |   D0.L = 2, scale x4 → 8
    |   bd = 0x00011000 (long)
    |   EA = 0 + 0x00011000 + 8 = 0x00011008
    move.l  #0x5544AACC, 0x00011008
    move.l  #2, %d0
    move.l  #0, %d3
    .word   0x2630, 0x0db0, 0x0001, 0x1000
    cmp.l   #0x5544AACC, %d3
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
