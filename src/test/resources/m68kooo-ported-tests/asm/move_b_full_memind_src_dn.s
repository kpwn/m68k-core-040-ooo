| move_b_full_memind_src_dn.s — Task #207 (C1)
|
| Directed test for MOVE.B with full-format memory-indirect NO-INDEX
| source and Dn-direct destination.  I/IS=100, IS=1 (Xn suppressed).
|   EA = [bd + A1]       (null od)
|
| Opword: 0001_000_000_110_001 = 0x1031   (MOVE.B, dst=Dn=D0, mode=110, reg=A1)
| ext1  : 0x0164
|
| Memory:
|   A1 = 0x00114000
|   [A1 + 32] = 0x00114200
|   [0x00114200] = 0xCAFEBABE  → byte at [0x00114200] = 0xCA (big-endian)
|                                 merged into D0's low byte (Dn[31:8] kept).

    .text
    .org 0

_start:
    lea     0x00114020, %a6
    move.l  #0x00114200, (%a6)
    lea     0x00114200, %a6
    move.l  #0xCAFEBABE, (%a6)

    move.l  #0x55667788, %d0
    lea     0x00114000, %a1

    | MOVE.B ([+32,A1]), D0   — expect D0 = 0x556677_CA
    .word   0x1031, 0x0164, 0x0020

    cmp.l   #0x556677CA, %d0
    bne     _fail1

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail1:
    lea     0xFFFF0000, %a0
    move.l  %d0, (%a0)
_halt_f1:
    bra     _halt_f1
