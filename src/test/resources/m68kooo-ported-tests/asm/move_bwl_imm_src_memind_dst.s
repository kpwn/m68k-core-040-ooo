| move_bwl_imm_src_memind_dst.s — Task #207 (C1)
|
| MOVE.{B,W,L} #imm, ([bd,An],od) — immediate to memind-dst for all
| three sizes.  imm_is_data encodes the literal source.

    .text
    .org 0

_start:
    | Seed src slots
    lea     0x00114020, %a6          | indirect ptr for memind dst 1
    move.l  #0x00114500, (%a6)
    lea     0x00114024, %a6          | indirect ptr for memind dst 2
    move.l  #0x00114600, (%a6)
    lea     0x00114028, %a6          | indirect ptr for memind dst 3
    move.l  #0x00114700, (%a6)

    lea     0x00114500, %a6
    move.l  #0x11111111, (%a6)
    lea     0x00114600, %a6
    move.l  #0x22222222, (%a6)
    lea     0x00114700, %a6
    move.l  #0x33333333, (%a6)

    lea     0x00114000, %a1

    | MOVE.L #0xCAFEBABE, ([+32,A1]) — size=10, dst_reg=001(A1), dst_mode=110,
    |   src_mode=111 (imm), src_reg=100 (#imm).  Opword: 0010 001 110 111 100
    |   = 0x23BC.  Layout: opword, src_imm_hi, src_imm_lo, dst_desc, dst_bd.
    .word   0x23BC, 0xCAFE, 0xBABE, 0x0164, 0x0020

    | MOVE.W #0xF00D, ([+36,A1]) — size=11 (W), dst_reg=001, dst_mode=110,
    |   src_mode=111, src_reg=100.  Opword: 0011 001 110 111 100 = 0x33BC.
    |   Layout: opword, src_imm, dst_desc, dst_bd.
    .word   0x33BC, 0xF00D, 0x0164, 0x0024

    | MOVE.B #0x7A, ([+40,A1]) — size=01 (B), dst_reg=001, dst_mode=110,
    |   src_mode=111, src_reg=100.  Opword: 0001 001 110 111 100 = 0x13BC.
    |   Layout: opword, src_imm (byte in low 8 bits of word), dst_desc, dst_bd.
    .word   0x13BC, 0x007A, 0x0164, 0x0028

    | Verify [0x00114500] long == 0xCAFEBABE
    lea     0x00114500, %a0
    move.l  (%a0), %d1
    cmp.l   #0xCAFEBABE, %d1
    bne     _fail

    | Verify [0x00114600] word high = 0xF00D, low preserved = 0x2222
    lea     0x00114600, %a0
    move.l  (%a0), %d1
    cmp.l   #0xF00D2222, %d1
    bne     _fail

    | Verify [0x00114700] byte high = 0x7A, rest preserved = 0x333333
    lea     0x00114700, %a0
    move.l  (%a0), %d1
    cmp.l   #0x7A333333, %d1
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d1, (%a0)
_halt_f:
    bra     _halt_f
