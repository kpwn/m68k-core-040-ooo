| lea_memind_an.s — Task #208 (C2)
|
| Directed test for LEA with full-format memory-indirect source and
| An destination.  Covers all three I/IS variants:
|   no-index  : LEA ([bd,A4]),A5
|   pre-idx   : LEA ([bd,A4,D2*4],od),A5
|   post-idx  : LEA ([bd,A4],D2*4,od),A5
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00115f00, %a7

    | -- No-index: LEA ([32,A4]),A5  =  [A4+32]
    lea     0x00115200, %a4
    lea     0x00115220, %a0
    move.l  #0x00115400, (%a0)       | [A4+32] = 0x00115400
    | opword = 4bf4, ext1 = 0x0162, ext2 = 0x0020
    | 4bf4 = 0100_1011_11_110_100 (LEA, dst A5, mode 110 A4)
    | ext1 = 0b0_000_0_00_1_0_1_10_0_010 (bit8=1 full-fmt, BS=0, IS=1,
    |        bd=W, I/IS=010 pre-idx+od=W, but IS=1 masks idx
    |        — wait: no-index requires I/IS=100 with IS=1).
    | Rewrite: ext1 = 0b0_000_0_00_1_0_1_10_0_100 = 0x0164
    .word   0x4bf4, 0x0164, 0x0020
    cmpa.l  #0x00115400, %a5
    bne     _fail1

    | -- Pre-indexed: LEA ([-4,A4,D2.L*4],-8),A5
    lea     0x00115300, %a4
    move.l  #0x00000002, %d2
    lea     0x001152fc, %a0
    move.l  #0x00115500, (%a0)       | [A4 + (-4) + 8] = [A4+4]? let's recompute.
    | Wait: A4=0x00115300, bd=-4, D2*4=8, so inner EA = A4-4+8 = 0x00115304.
    lea     0x00115304, %a0
    move.l  #0x00115500, (%a0)
    | final EA = [inner] + od = 0x00115500 - 8 = 0x001154F8
    | 4bf4 LEA A5 mode=110 A4
    | ext1 = D_A=0 idx=D2 WL=1 SC=10 bit8=1 BS=0 IS=0 bd=W bit3=0 I/IS=010
    | = 0b0_010_1_10_1_0_0_10_0_010 = 0x2D22
    .word   0x4bf4, 0x2D22, 0xfffc, 0xfff8
    cmpa.l  #0x001154F8, %a5
    bne     _fail2

    | -- Post-indexed: LEA ([-4,A4],D2.L*4,-8),A5
    lea     0x00115400, %a4
    move.l  #0x00000002, %d2
    lea     0x001153fc, %a0
    move.l  #0x00115600, (%a0)       | [A4-4] = 0x00115600
    | final EA = 0x00115600 + D2*4 - 8 = 0x00115600 + 8 - 8 = 0x00115600
    | ext1 post-idx: bit3=1, I/IS=110 (post-idx + od=W)
    | = 0b0_010_1_10_1_0_0_10_1_110 = 0x2D2E
    .word   0x4bf4, 0x2D2E, 0xfffc, 0xfff8
    cmpa.l  #0x00115600, %a5
    bne     _fail3

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d7

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
