| sub_mem_modes.s -- SUB.{B,W,L} memory-source addressing forms
|
| Covers the Q700 ROM frontier opcode:
|   40800aa2: 969a    sub.l (%a2)+,%d3
|
| Also exercises predecrement, displacement, A7 byte postincrement,
| absolute-long, and PC-relative memory sources.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Long postincrement: exact ROM shape.
    lea     0x00107000, %a2
    move.l  #0x00000005, (%a2)
    move.l  #0x00000007, %d3
    sub.l   (%a2)+, %d3
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x00000002, %d3
    bne     _fail
    move.l  %a2, %d0
    cmp.l   #0x00107004, %d0
    bne     _fail

    | Long predecrement.
    lea     0x00107014, %a3
    move.l  #0x00000003, 0x00107010
    move.l  #0x00000004, %d2
    sub.l   -(%a3), %d2
    cmp.l   #0x00000001, %d2
    bne     _fail
    move.l  %a3, %d0
    cmp.l   #0x00107010, %d0
    bne     _fail

    | Word displacement: preserve D1 upper word and set flags from low word.
    lea     0x00107100, %a1
    move.l  #0x00010000, 8(%a1)
    move.l  #0xabcd0002, %d1
    sub.w   8(%a1), %d1
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0xabcd0001, %d1
    bne     _fail

    | Byte postincrement from A7 increments by 2 and sets borrow.
    lea     0x00107200, %a7
    move.l  #0x01000000, (%a7)
    move.l  #0x55550000, %d4
    sub.b   (%a7)+, %d4
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcc     _fail
    cmp.l   #0x555500ff, %d4
    bne     _fail
    move.l  %a7, %d0
    cmp.l   #0x00107202, %d0
    bne     _fail

    | Absolute-long memory source.
    lea     0x00107300, %a0
    move.l  #0x00000009, (%a0)
    move.l  #0x0000000a, %d5
    sub.l   0x00107300, %d5
    cmp.l   #0x00000001, %d5
    bne     _fail

    | PC-relative memory source.
    moveq   #10, %d6
    sub.l   _pc_subtrahend(%pc), %d6
    cmp.l   #0x00000003, %d6
    bne     _fail

    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a6)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a6
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a6)
_fail_halt:
    bra     _fail_halt

    .align 2
_pc_subtrahend:
    .long   0x00000007
