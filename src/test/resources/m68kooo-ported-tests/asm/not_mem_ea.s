| not_mem_ea.s -- NOT.{B,W,L} memory read-modify-write EA forms
|
| Covers the Q700 ROM frontier:
|   40805f0e: 46aa 0010   not.l 16(A2)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Plain (An): byte NOT preserves neighbouring bytes and sets N.
    lea     0x00116000, %a0
    move.l  #0x1122337f, (%a0)
    .word   0x4610                  | not.b (%a0)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a0), %d0
    cmp.l   #0xee22337f, %d0
    bne     _fail

    | Plain (An): word sibling, result zero sets Z.
    lea     0x00116010, %a1
    move.l  #0xffffaabb, (%a1)
    .word   0x4651                  | not.w (%a1)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a1), %d0
    cmp.l   #0x0000aabb, %d0
    bne     _fail

    | Exact ROM shape: d16(An), long.
    lea     0x00116100, %a2
    move.l  #0x00000000, 16(%a2)
    .word   0x46aa, 0x0010          | not.l 16(%a2)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  16(%a2), %d0
    cmp.l   #0xffffffff, %d0
    bne     _fail

    | Postincrement byte, with A7 byte-stack +2 rule.
    lea     0x00116200, %a7
    move.l  #0x55667700, (%a7)
    .word   0x461f                  | not.b (%a7)+
    cmp.l   #0x00116202, %a7
    bne     _fail
    move.l  0x00116200, %d0
    cmp.l   #0xaa667700, %d0
    bne     _fail

    | Predecrement long.
    lea     0x00116308, %a3
    move.l  #0x12345678, -4(%a3)
    .word   0x46a3                  | not.l -(%a3)
    cmp.l   #0x00116304, %a3
    bne     _fail
    move.l  (%a3), %d0
    cmp.l   #0xedcba987, %d0
    bne     _fail

    | Absolute word byte.
    move.l  #0xcafebabe, 0x7000
    .word   0x4638, 0x7000          | not.b 0x7000.W
    move.l  0x7000, %d0
    cmp.l   #0x35febabe, %d0
    bne     _fail

    | Absolute long word.
    move.l  #0x1234f0f0, 0x00116400
    .word   0x4679, 0x0011, 0x6400  | not.w 0x00116400.L
    move.l  0x00116400, %d0
    cmp.l   #0xedcbf0f0, %d0
    bne     _fail

_pass:
    lea     0xffff0000, %a0
    move.l  #0xc0ffee00, %d0
    move.l  %d0, (%a0)
    bra     _pass

_fail:
    lea     0xffff0000, %a0
    move.l  #0xdead0001, %d0
    move.l  %d0, (%a0)
    bra     _fail
