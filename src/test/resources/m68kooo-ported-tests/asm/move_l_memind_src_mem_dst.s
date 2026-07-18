| move_l_memind_src_mem_dst.s — Task #207 (C1)
|
| Directed test for MOVE.L with full-format memory-indirect NO-INDEX
| source and simple memory destination (d16,An).
|   src EA = [A1 + 32]    (null od)
|   dst EA = 0 + A2       (d16=0)
|
| Opword: 0010_010_010_110_001 = 0x24B1
|          size=10 (.L) | dst_reg=010 (A2) | dst_mode=010 ((An))
|                           wait — we want (d16,A2): dst_mode=101, dst_reg=010
|          size=10 (.L) | dst_reg=010 (A2) | dst_mode=101 | src_mode=110 | src_reg=001
|          = 0010_010_101_110_001 = 0x2571
| ext1  : 0x0164 (memind no-idx, bd.W, BS=0, IS=1, I/IS=100)
| ext2  : 0x0020 (bd = +32)
| ext3  : 0x0000 (dst d16 = 0)
|
| Memory:
|   A1 = 0x00114000
|   A2 = 0x00114800 (dst base; write lands at A2+0 = 0x00114800)
|   [A1 + 32] = 0x00114200           indirect pointer
|   [0x00114200] = 0xBADC0FFE       the value to copy into (0,A2)

    .text
    .org 0

_start:
    lea     0x00114020, %a6
    move.l  #0x00114200, (%a6)
    lea     0x00114200, %a6
    move.l  #0xBADC0FFE, (%a6)
    lea     0x00114800, %a6
    move.l  #0x0, (%a6)

    lea     0x00114000, %a1
    lea     0x00114800, %a2

    | MOVE.L ([+32,A1]), (0,A2)
    .word   0x2571, 0x0164, 0x0020, 0x0000

    | Verify (0,A2) == 0xBADC0FFE
    lea     0x00114800, %a0
    move.l  (%a0), %d1
    cmp.l   #0xBADC0FFE, %d1
    bne     _fail

    | Pass sentinel
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
