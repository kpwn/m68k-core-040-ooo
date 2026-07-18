| move_l_memind_predec.s — Task #212 (D1)
|
| MOVE.L ([bd.W,An],od), -(Am) — memind-src -> predec-dst.
| Tests Shape 2: memind-src no-idx -> predec mem-dst.
|
| Q700 ROM frontier: 40805e6e  2f30 81e2 0db8 00e8
|
| PASS: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Seed inner pointer slot: [A0 + 0x0db8] with A0 = 0
    | → inner = 0x00115000
    lea     0x00000db8, %a0
    move.l  #0x00115000, (%a0)

    | Seed final source word at 0x00115000 + 0x00e8 = 0x001150e8
    lea     0x001150e8, %a0
    move.l  #0xCAFEBABE, (%a0)

    | Set up dst predec: A7 = 0x00116004 → after SUB 4 = 0x00116000
    | So STORE lands at 0x00116000
    lea     0x00116000, %a0
    move.l  #0x00000000, (%a0)
    lea     0x00116004, %a7

    moveq   #0, %d0
    movea.l %d0, %a0

    | MOVE.L ([0x0db8,A0],0x00e8), -(A7)
    .word   0x2f30, 0x81e2, 0x0db8, 0x00e8

    | Verify [0x00116000] == 0xCAFEBABE
    lea     0x00116000, %a1
    move.l  (%a1), %d1
    cmp.l   #0xCAFEBABE, %d1
    bne     _fail

    | Verify A7 == 0x00116000
    cmp.l   #0x00116000, %a7
    bne     _fail

    | Restore stack so final writes don't clobber
    lea     0x00800000, %a7
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0x00800000, %a7
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0002, %d7
    move.l  %d7, (%a0)
_halt_f:
    bra     _halt_f
