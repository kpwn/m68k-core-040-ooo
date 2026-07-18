| move_l_full_mem_dst.s -- MOVE.L Dn,(bd,An,Xn*scale)
|
| Full-format no-memind destination (I/IS=000).  Symmetric with
| move_l_full_mem_src_to_dn but with a reg source and the full-format
| EA on the dst side.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Word bd + D1.W*4 + A0 — EA = A0 + 0x1234 + D1*4
    |   MOVE.L D2,(bd,A0,D1.W*4):
    |     src = D2: mode[5:3]=000, reg[2:0]=010
    |     dst = (bd,A0,D1.W*4): mode[8:6]=110, reg[11:9]=000 (A0)
    |     opword bits: 0010 000 110 000 010 = 0x2182
    |   ext1 = 0x1520 (D1.W, scale x4, BS=0, IS=0, BD=word, I/IS=000)
    |   ext2 = 0x1234 (bd.W)
    move.l  #0x00100000, %a0
    moveq   #2, %d1
    move.l  #0xFEEDC0DE, %d2
    move.l  #0, 0x0010123c
    .word   0x2182, 0x1520, 0x1234
    move.l  0x0010123c, %d3
    cmp.l   #0xFEEDC0DE, %d3
    bne     _fail1

    | Long bd + D1.L*2 + A1 — EA = A1 + 0x00012000 + D1.L*2
    |   ext1 for full-format long bd: 0x1b30 (D1.L, scale x2, BS=0, IS=0, BD=long)
    |   dst_mode=110, dst_reg=A1=001
    |   opword bits: 0010 001 110 000 010 = 0x2382
    move.l  #0x00100000, %a1
    moveq   #4, %d1
    move.l  #0x11223344, %d2
    move.l  #0, 0x00112008
    .word   0x2382, 0x1b30, 0x0001, 0x2000
    move.l  0x00112008, %d3
    cmp.l   #0x11223344, %d3
    bne     _fail2

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d0
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d0

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
