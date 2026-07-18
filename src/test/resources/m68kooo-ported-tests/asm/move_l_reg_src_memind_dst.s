| move_l_reg_src_memind_dst.s — Task #207 (C1)
|
| MOVE.L D0, ([+32,A1])  — Dn source to memory-indirect no-index dst.
|   dst EA = [A1 + 32] + null_od
|
| Opword: 0010_110_111_000_000
|          size=10 (.L) | dst_reg=110 | dst_mode=111 | src_mode=000 (Dn) | src_reg=000 (D0)
|          WAIT — dst = memind requires dst_mode=111 reg=110? No!
|          memind DST uses the normal (d8,An,Xn)/full-format encoding,
|          which is mmm=110 reg=An.  So dst=(mode=110, reg=A1) = 001
|          Opword: size=10 (.L) | dst_reg=001 (A1) | dst_mode=110 | src_mode=000 (Dn) | src_reg=000 (D0)
|          = 0010_001_110_000_000 = 0x2380

    .text
    .org 0

_start:
    | Seed src slot
    lea     0x00114020, %a6
    move.l  #0x00114500, (%a6)
    lea     0x00114500, %a6
    move.l  #0x00000000, (%a6)

    move.l  #0xDEADBEEF, %d0
    lea     0x00114000, %a1

    | MOVE.L D0, ([+32,A1])
    .word   0x2380, 0x0164, 0x0020

    | Verify [0x00114500] == 0xDEADBEEF
    lea     0x00114500, %a0
    move.l  (%a0), %d1
    cmp.l   #0xDEADBEEF, %d1
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
