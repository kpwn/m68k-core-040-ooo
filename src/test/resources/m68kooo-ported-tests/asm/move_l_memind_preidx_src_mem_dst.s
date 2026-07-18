| move_l_memind_preidx_src_mem_dst.s — Task #207 (C1)
|
| MOVE.L with pre-indexed memory-indirect source and (An) destination.
|   src EA = [bd + A1 + D2*1] + od  (BS=0, IS=0, scale=0 → ×1)
|   dst EA = (A3)
|
| Opword: 0010_011_010_110_001
|          size=10 (.L) | dst_reg=011 (A3) | dst_mode=010 ((An))
|          | src_mode=110 | src_reg=001 (A1)
|          = 0x26B1
| ext1  : full-format, D/A=0, idx=010 (D2), W=0 (.W), scale=00, bit8=1,
|         BS=0, IS=0, BD=10 (word), preidx I/IS=001 (null od)
|       = 0b0_010_0_00_1_0_0_10_0_001 = 0x2121
| ext2  : 0x0020 (bd = +32)
|
| Memory:
|   A1 = 0x00114000
|   D2 = 0  (sign-extended word 0, scaled ×1 = 0)
|   [A1 + 32 + 0] = [0x00114020] = 0x00114300  indirect pointer
|   [0x00114300] = 0xDECAF00D

    .text
    .org 0

_start:
    lea     0x00114020, %a6
    move.l  #0x00114300, (%a6)
    lea     0x00114300, %a6
    move.l  #0xDECAF00D, (%a6)
    lea     0x00114A00, %a6
    move.l  #0x0, (%a6)

    lea     0x00114000, %a1
    lea     0x00114A00, %a3
    moveq   #0, %d2

    | MOVE.L ([+32,A1,D2.W],null_od), (A3)
    .word   0x26B1, 0x2121, 0x0020

    | Verify (A3) == 0xDECAF00D
    lea     0x00114A00, %a0
    move.l  (%a0), %d1
    cmp.l   #0xDECAF00D, %d1
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d7
    move.l  %d7, (%a0)
_halt_f:
    bra     _halt_f
