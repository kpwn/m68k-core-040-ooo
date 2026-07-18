| move_l_memind_postidx_src_mem_dst.s — Task #207 (C1)
|
| MOVE.L with post-indexed memory-indirect source and (xxx).L dest.
|   src EA = [bd + A1] + D2*1 + od  (BS=0, IS=0, scale=0 → ×1)
|   dst EA = (xxx).L absolute
|
| Opword: 0010_001_111_110_001
|          size=10 (.L) | dst_reg=001 ((xxx).L) | dst_mode=111
|          | src_mode=110 | src_reg=001 (A1) = 0x23F1
| ext1  : full-format, D/A=0, idx=010 (D2), W=0 (.W), scale=00, bit8=1,
|         BS=0, IS=0, BD=10 (word), postidx I/IS=101 (null od)
|       = 0b0_010_0_00_1_0_0_10_1_101 = 0x212D
| ext2  : 0x0020 (bd = +32)
| ext3/4: 0x0011, 0x4C00 (dst = 0x00114C00)

    .text
    .org 0

_start:
    lea     0x00114020, %a6
    move.l  #0x00114400, (%a6)
    lea     0x00114400, %a6
    move.l  #0xCABBA6E5, (%a6)
    lea     0x00114C00, %a6
    move.l  #0x0, (%a6)

    lea     0x00114000, %a1
    moveq   #0, %d2

    | MOVE.L ([+32,A1],D2.W*1,null_od), 0x00114C00.L
    .word   0x23F1, 0x212D, 0x0020, 0x0011, 0x4C00

    | Verify (0x00114C00) == 0xCABBA6E5
    lea     0x00114C00, %a0
    move.l  (%a0), %d1
    cmp.l   #0xCABBA6E5, %d1
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
