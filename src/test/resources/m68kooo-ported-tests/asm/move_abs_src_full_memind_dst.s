| move_abs_src_full_memind_dst.s -- MOVE.{B,W,L} (xxx).{W,L},([bd.W,An],od)
|
| Covers absolute-source MOVE into a full-format memory-indirect
| destination.  The Q700 ROM frontier hit this shape at:
|   40806d0c: 21b8 0008 81e2 0cbc fff8
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Long, abs.W source, word outer displacement.
    lea     0x00000db8, %a0
    move.l  #0x11223344, (%a0)
    lea     0x00000cbc, %a0
    move.l  #0x00119000, (%a0)
    lea     0x00118ff8, %a0
    move.l  #0x00000000, (%a0)
    .word   0x21b8, 0x0db8, 0x81e2, 0x0cbc, 0xfff8
    bmi     _fail1
    beq     _fail1
    bvs     _fail1
    bcs     _fail1
    move.l  0x00118ff8, %d7
    cmp.l   #0x11223344, %d7
    bne     _fail1

    | Word sibling, abs.W source, word outer displacement.
    lea     0x00000dc0, %a0
    move.l  #0x8001aaaa, (%a0)
    lea     0x00000cc0, %a0
    move.l  #0x00119020, (%a0)
    lea     0x00119024, %a0
    move.l  #0x11223344, (%a0)
    .word   0x31b8, 0x0dc0, 0x81e2, 0x0cc0, 0x0004
    bpl     _fail2
    beq     _fail2
    bvs     _fail2
    bcs     _fail2
    move.l  0x00119024, %d7
    cmp.l   #0x80013344, %d7
    bne     _fail2

    | Byte sibling, abs.W source, null outer displacement.
    lea     0x00000dc4, %a0
    move.l  #0x00abcdef, (%a0)
    lea     0x00000cc4, %a0
    move.l  #0x00119040, (%a0)
    lea     0x00119040, %a0
    move.l  #0xaabbccdd, (%a0)
    .word   0x11b8, 0x0dc4, 0x81e1, 0x0cc4
    bne     _fail3
    bmi     _fail3
    bvs     _fail3
    bcs     _fail3
    move.l  0x00119040, %d7
    cmp.l   #0x00bbccdd, %d7
    bne     _fail3

    | Long, abs.L source, null outer displacement.
    lea     0x00119100, %a0
    move.l  #0xff00aa55, (%a0)
    lea     0x00000cc8, %a0
    move.l  #0x00119120, (%a0)
    lea     0x00119120, %a0
    move.l  #0x00000000, (%a0)
    .word   0x21b9, 0x0011, 0x9100, 0x81e1, 0x0cc8
    bpl     _fail4
    beq     _fail4
    bvs     _fail4
    bcs     _fail4
    move.l  0x00119120, %d7
    cmp.l   #0xff00aa55, %d7
    bne     _fail4

    | Long, abs.L source, word outer displacement.  This is a 12-byte
    | instruction and proves the ext5 offset path.
    lea     0x00119130, %a0
    move.l  #0x7badf00d, (%a0)
    lea     0x00000ccc, %a0
    move.l  #0x00119140, (%a0)
    lea     0x00119148, %a0
    move.l  #0x00000000, (%a0)
    .word   0x21b9, 0x0011, 0x9130, 0x81e2, 0x0ccc, 0x0008
    bmi     _fail5
    beq     _fail5
    bvs     _fail5
    bcs     _fail5
    move.l  0x00119148, %d7
    cmp.l   #0x7badf00d, %d7
    bne     _fail5

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
    bra     _fail
_fail5:
    move.l  #0xDEAD0005, %d7

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
