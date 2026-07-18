| move_l_full_postidx_memind.s — Task #202 (B1)
|
| Directed test for MOVE.L with full-format POST-INDEXED memory-indirect
| source, Dn-direct destination.  Covers V2's new memind crack:
|   EA = [bd + A1] + D2*scale + od
|
| Distinction vs pre-indexed: the memory-indirect LOAD happens BEFORE
| adding the index (post-indexed), so the stored pointer is
| independent of D2.
|
| Opword / ext1:
|   opword = 0010_000_000_110_001    = 0x2031
|   ext1   = D_A=0 | idx=010 (D2) | WL=1 (long) | SCALE=10 (x4)
|            | bit8=1 | BS=0 | IS=0 | BD_SIZE=10 (word) | bit3=1
|            | I/IS=110 (post-indexed, od.W)
|          = 0b0_010_1_10_1_0_0_10_1_110 = 0x2D2E
|
| Memory layout:
|   A1 = 0x00114000,  D2 = 0x00000004
|   bd = -4  (0xfffc)       | [A1 - 4] holds memory-indirect pointer
|   [A1 - 4] = 0x00114100   | the indirect pointer
|   od = +16 (0x0010)       | final EA = 0x00114100 + D2*4 + od
|                           |          = 0x00114100 + 16 + 16 = 0x00114120
|   [0x00114120] = 0xCAFEBEEF   | loaded into D0

    .text
    .org 0

_start:
    | Stage the memory slots.  NB: predec before bd is negative — A1-4.
    lea     0x00113FFC, %a6
    move.l  #0x00114100, (%a6)
    lea     0x00114120, %a6
    move.l  #0xCAFEBEEF, (%a6)

    | Load base + index + clear destination.
    lea     0x00114000, %a1
    move.l  #0x00000004, %d2
    moveq   #0, %d0

    | MOVE.L ([-4,A1],D2.L*4,+16.W), D0
    .word   0x2031, 0x2D2E, 0xfffc, 0x0010

    cmp.l   #0xCAFEBEEF, %d0
    bne     _fail1

    | After the memind load: loaded value has MSB=1 → N=1.  Retest on
    | a copy (cmp's own flags reflect the cmp result, not the load).
    move.l  %d0, %d1
    tst.l   %d1
    bpl     _fail2
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
