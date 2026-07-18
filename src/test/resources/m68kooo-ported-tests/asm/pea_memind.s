| pea_memind.s — Task #208 (C2)
|
| Directed test for PEA with full-format memory-indirect source.
| Covers all three I/IS variants; each pushes the computed EA onto
| (A7) and we check the top-of-stack value.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00115f00, %a7

    | -- No-index: PEA ([32,A4])  pushes [A4+32]
    lea     0x00115200, %a4
    lea     0x00115220, %a0
    move.l  #0x11223344, (%a0)       | [A4+32] = 0x11223344
    | PEA = 0x4840 | mmm<<3 | rrr.  mode=110 rrr=100 (A4) => 0x4874.
    | ext1 = bit8=1 BS=0 IS=1 bd=W I/IS=100 = 0x0164
    .word   0x4874, 0x0164, 0x0020
    move.l  (%a7)+, %d0
    cmp.l   #0x11223344, %d0
    bne     _fail1

    | -- Pre-indexed: PEA ([-4,A4,D2.L*4],-8)
    lea     0x00115300, %a4
    move.l  #0x00000002, %d2
    lea     0x00115304, %a0
    move.l  #0x00115500, (%a0)       | [A4-4+D2*4] = 0x00115500
    | final EA = 0x00115500 - 8 = 0x001154F8
    | ext1 pre-idx I/IS=010, bd=W, od=W, D2.L*4
    |   = 0b0_010_1_10_1_0_0_10_0_010 = 0x2D22
    .word   0x4874, 0x2D22, 0xfffc, 0xfff8
    move.l  (%a7)+, %d0
    cmp.l   #0x001154F8, %d0
    bne     _fail2

    | -- Post-indexed: PEA ([-4,A4],D2.L*4,-8)
    lea     0x00115400, %a4
    move.l  #0x00000002, %d2
    lea     0x001153fc, %a0
    move.l  #0x00115600, (%a0)       | [A4-4] = 0x00115600
    | final EA = 0x00115600 + D2*4 - 8 = 0x00115600
    | ext1 post-idx I/IS=110, bd=W, od=W, D2.L*4
    |   = 0b0_010_1_10_1_0_0_10_1_110 = 0x2D2E
    .word   0x4874, 0x2D2E, 0xfffc, 0xfff8
    move.l  (%a7)+, %d0
    cmp.l   #0x00115600, %d0
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
