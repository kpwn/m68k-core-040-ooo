| move_full_memind_src_predec.s -- MOVE.{B,W,L} ([bd.W,An],od),-(Am)
|
| Covers the Q700 ROM frontier:
|   40805e6e: 2f30 81e2 0db8 00e8    move.l ([$0db8],$e8),-(A7)
|
| The source EA is full-format, base/index suppressed, bd.W,
| preindexed memory-indirect, and od.W.  Word and byte siblings pin the
| same EA crack with the destination predecrement step for each size.

    .text
    .org 0

_start:
    | Exact ROM-shaped long push: pointer at $0db8, source at pointer+$e8.
    lea     0x00000db8, %a0
    move.l  #0x00116100, (%a0)
    lea     0x001161e8, %a0
    move.l  #0x40806cfa, (%a0)
    lea     0x00116000, %a7

    .word   0x2f30, 0x81e2, 0x0db8, 0x00e8
    bmi     _fail1
    beq     _fail1
    bvs     _fail1
    bcs     _fail1
    move.l  %a7, %d7
    cmp.l   #0x00115ffc, %d7
    bne     _fail1
    move.l  (%a7), %d0
    cmp.l   #0x40806cfa, %d0
    bne     _fail1

    | Word sibling: N set from the pushed word, destination steps by two.
    lea     0x00000dc0, %a0
    move.l  #0x00116200, (%a0)
    lea     0x00116204, %a0
    move.w  #0x8001, (%a0)
    lea     0x00117000, %a5

    .word   0x3b30, 0x81e2, 0x0dc0, 0x0004
    bpl     _fail2
    beq     _fail2
    bvs     _fail2
    bcs     _fail2
    move.l  %a5, %d7
    cmp.l   #0x00116ffe, %d7
    bne     _fail2
    move.w  (%a5), %d1
    cmp.w   #0x8001, %d1
    bne     _fail2

    | Byte sibling: zero byte sets Z; A7 byte predecrement steps by two.
    lea     0x00000dc4, %a0
    move.l  #0x00116302, (%a0)
    lea     0x00116300, %a0
    move.b  #0x00, (%a0)
    lea     0x00118000, %a7

    .word   0x1f30, 0x81e2, 0x0dc4, 0xfffe
    bne     _fail3
    bmi     _fail3
    bvs     _fail3
    bcs     _fail3
    move.l  %a7, %d7
    cmp.l   #0x00117ffe, %d7
    bne     _fail3
    move.b  (%a7), %d2
    cmp.b   #0x00, %d2
    bne     _fail3

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
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d7

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
