| move_full_memind_dst.s -- MOVE.{B,W,L} Dn,([bd.W,An],od.W)
|
| Covers the opposite direction from move_full_memind_src*.  ROM frontier
| traces already use full-format memory-indirect sources; destination
| siblings are high-risk because MOVE destination EAs use a different
| opcode layout and can be decoded asymmetrically from source EAs.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Long store: slot at A4+32 contains pointer, outer -8 stores at target.
    lea     0x00115000, %a4
    lea     0x00115020, %a0
    move.l  #0x00115108, (%a0)
    lea     0x00115100, %a0
    move.l  #0x00000000, (%a0)
    move.l  #0x89abcdef, %d0
    .word   0x2980, 0x0162, 0x0020, 0xfff8
    bpl     _fail1
    beq     _fail1
    bvs     _fail1
    bcs     _fail1
    move.l  0x00115100, %d7
    cmp.l   #0x89abcdef, %d7
    bne     _fail1

    | Word sibling: one halfword store, N set from the transferred word.
    lea     0x00115040, %a4
    lea     0x00115030, %a0
    move.l  #0x00115128, (%a0)
    lea     0x00115124, %a0
    move.l  #0x11223344, (%a0)
    move.l  #0xcafe8001, %d1
    .word   0x3981, 0x0162, 0xfff0, 0xfffc
    bpl     _fail2
    beq     _fail2
    bvs     _fail2
    bcs     _fail2
    move.l  0x00115124, %d7
    cmp.l   #0x80013344, %d7
    bne     _fail2

    | Byte sibling: one byte store, Z set from the transferred byte.
    lea     0x00115080, %a4
    lea     0x00115070, %a0
    move.l  #0x00115148, (%a0)
    lea     0x00115144, %a0
    move.l  #0xaabbccdd, (%a0)
    move.l  #0x12345600, %d2
    .word   0x1982, 0x0162, 0xfff0, 0xfffc
    bne     _fail3
    bmi     _fail3
    bvs     _fail3
    bcs     _fail3
    move.l  0x00115144, %d7
    cmp.l   #0x00bbccdd, %d7
    bne     _fail3

    | ROM-shaped long sibling: A0 source, base/index suppressed,
    | bd.W pointer in low RAM, od.W target displacement.
    lea     0x00000db8, %a1
    move.l  #0x00115200, (%a1)
    lea     0x001152e4, %a1
    move.l  #0x00000000, (%a1)
    move.l  #0x4088bc76, %a0
    .word   0x2188, 0x81e2, 0x0db8, 0x00e4
    beq     _fail4
    bmi     _fail4
    bvs     _fail4
    bcs     _fail4
    move.l  0x001152e4, %d7
    cmp.l   #0x4088bc76, %d7
    bne     _fail4

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
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d7

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
