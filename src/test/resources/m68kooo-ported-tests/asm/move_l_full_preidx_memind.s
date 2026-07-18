| move_l_full_preidx_memind.s — Task #202 (B1)
|
| Directed test for MOVE.L with full-format PRE-INDEXED memory-indirect
| source, Dn-direct destination.  Covers V2's new memind crack:
|   EA = [bd + A1 + D2*scale] + od
|
| Layout under test:
|   MOVE.L ([bd,A1,D2.L*4],od.W), %d0    | pre-indexed, od=word
|
| Opword / ext1 decoding:
|   opword = 0010_000_000_110_001    = 0x2031
|     size=.L, dst Dn=D0 (000), dst mode=000 (Dn), src mode=110, src reg=A1
|   ext1   = D_A=0 | idx=010 (D2) | WL=1 (long) | SCALE=10 (x4)
|            | bit8=1 | BS=0 | IS=0 | BD_SIZE=10 (word) | bit3=0
|            | I/IS=010 (pre-indexed, od.W)
|          = 0b0_010_1_10_1_0_0_10_0_010 = 0x2D22
|
| Extension-word layout:
|   ext2 = bd (word, sign-extended)
|   ext3 = od (word, sign-extended)
|
| Memory layout (all initialised by the test):
|   A1 = 0x00114000, D2 = 0x00000002
|   bd = -4   (0xfffc)        |  [bd + A1 + D2*4] = A1 - 4 + 8 = A1 + 4
|   [A1 + 4] = 0x00114200     | memory-indirect pointer
|   od = -8   (0xfff8)        |  final EA = 0x00114200 - 8 = 0x001141F8
|   [0x001141F8] = 0x12345678  | loaded into D0

    .text
    .org 0

_start:
    | Stage the memory slots.
    lea     0x00114004, %a6
    move.l  #0x00114200, (%a6)
    lea     0x001141F8, %a6
    move.l  #0x12345678, (%a6)

    | Load base + index + clear the destination.
    lea     0x00114000, %a1
    move.l  #0x00000002, %d2
    moveq   #0, %d0

    | MOVE.L ([-4,A1,D2.L*4],-8.W), D0
    .word   0x2031, 0x2D22, 0xfffc, 0xfff8

    cmp.l   #0x12345678, %d0
    bne     _fail1

    | After the memind load: N=0 (MSB clear), Z=0.  Retest via tst.l on
    | a copy (cmp's own flags reflect the cmp result, not the load).
    move.l  %d0, %d1
    tst.l   %d1
    bmi     _fail2
    beq     _fail2

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

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
