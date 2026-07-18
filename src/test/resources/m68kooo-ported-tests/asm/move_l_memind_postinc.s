| move_l_memind_postinc.s — Task #212 (D1)
|
| MOVE.L ([bd.W,An],od), (Am)+ — memind-src -> postinc-dst.
| Tests Shape 2: memind-src no-idx -> postinc mem-dst.
|
| PASS: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Seed inner pointer slot: [A0 + 0x0db8] with A0 = 0
    lea     0x00000db8, %a0
    move.l  #0x00115000, (%a0)

    | Seed source value at 0x00115000 + 0x00e8 = 0x001150e8
    lea     0x001150e8, %a0
    move.l  #0x77889900, (%a0)

    | Set up postinc dst: A1 = 0x00116000 → STORE lands at 0x00116000,
    | then A1 += 4 = 0x00116004
    lea     0x00116000, %a0
    move.l  #0x00000000, (%a0)
    lea     0x00116000, %a1

    moveq   #0, %d0
    movea.l %d0, %a0

    | MOVE.L ([0x0db8,A0],0x00e8), (A1)+
    |   size=.L(10) dst_reg=A1(001) dst_mode=postinc(011) src_mode=110 src_reg=A0(000)
    |   = 0010_001_011_110_000 = 0x22F0
    |   src_ext1 = 0x81e2 (full-ext; BS=1, IS=0, BD_SIZE=10 (word), I/IS=010 = preidx + word OD)
    .word   0x22F0, 0x81e2, 0x0db8, 0x00e8

    | Verify [0x00116000] == 0x77889900
    lea     0x00116000, %a2
    move.l  (%a2), %d1
    cmp.l   #0x77889900, %d1
    bne     _fail

    | Verify A1 == 0x00116004
    cmp.l   #0x00116004, %a1
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0003, %d7
    move.l  %d7, (%a0)
_halt_f:
    bra     _halt_f
