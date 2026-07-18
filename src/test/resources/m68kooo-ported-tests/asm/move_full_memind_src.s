| move_full_memind_src.s -- MOVE.{B,W,L} ([bd.W,An],od),Dn
|
| Covers the Q700 ROM frontier:
|   2034 8162 fff0 fffc    move.l ([%a4-16],-4),%d0
|   2034 8161 fff0         move.l ([%a4-16],0),%d0
|
| The extension shape is full-format, base present, index suppressed,
| word base displacement, preindexed memory-indirect, and either null or
| word outer displacement.  Byte/word siblings verify the same EA path and
| Dn merge.

    .text
    .org 0

_start:
    | Long exact frontier: slot at A4-16 contains pointer, outer -4 loads data.
    lea     0x00114020, %a4
    lea     0x00114010, %a6
    move.l  #0x00114108, (%a6)
    lea     0x00114104, %a6
    move.l  #0x89abcdef, (%a6)
    moveq   #0, %d0
    .word   0x2034, 0x8162, 0xfff0, 0xfffc
    bpl     _fail1
    beq     _fail1
    bvs     _fail1
    bcs     _fail1
    cmp.l   #0x89abcdef, %d0
    bne     _fail1

    | Long no-outer frontier: slot at A4-16 contains the final pointer.
    lea     0x00114028, %a4
    lea     0x00114018, %a6
    move.l  #0x00114150, (%a6)
    lea     0x00114150, %a6
    move.l  #0x00000039, (%a6)
    moveq   #0, %d0
    .word   0x2034, 0x8161, 0xfff0
    bmi     _fail4
    beq     _fail4
    bvs     _fail4
    bcs     _fail4
    cmp.l   #0x00000039, %d0
    bne     _fail4

    | Word sibling preserves D1[31:16] and sets N from the loaded word.
    lea     0x00114040, %a4
    lea     0x00114030, %a6
    move.l  #0x00114128, (%a6)
    lea     0x00114124, %a6
    move.l  #0x8001c0de, (%a6)
    move.l  #0xcafe0000, %d1
    .word   0x3234, 0x8162, 0xfff0, 0xfffc
    bpl     _fail2
    beq     _fail2
    bvs     _fail2
    bcs     _fail2
    cmp.l   #0xcafe8001, %d1
    bne     _fail2

    | Byte sibling preserves D2[31:8] and sets Z from a zero byte.
    lea     0x00114060, %a4
    lea     0x00114050, %a6
    move.l  #0x00114148, (%a6)
    lea     0x00114144, %a6
    move.l  #0x00abcdef, (%a6)
    move.l  #0x13579bdf, %d2
    .word   0x1434, 0x8162, 0xfff0, 0xfffc
    bne     _fail3
    bvs     _fail3
    bcs     _fail3
    cmp.l   #0x13579b00, %d2
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
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d7

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
