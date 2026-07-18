| bit_ops_mem_abs.s -- static bit ops on absolute byte memory
|
| Covers the Q700 ROM frontier:
|   40800a30: 0838 0001 0dd1  btst #1,0x0dd1.W
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Exact ROM shape: BTST #1,(xxx).W sees a set bit and leaves memory alone.
    lea     0x00000dd1, %a0
    move.b  #0x02, (%a0)
    .word   0x0838, 0x0001, 0x0dd1
    beq     _fail
    move.b  (%a0), %d0
    cmp.b   #0x02, %d0
    bne     _fail

    | BTST #1,(xxx).W sees a clear bit and sets Z.
    move.b  #0x00, (%a0)
    .word   0x0838, 0x0001, 0x0dd1
    bne     _fail
    move.b  (%a0), %d0
    cmp.b   #0x00, %d0
    bne     _fail

    | BCLR #0,(xxx).W clears a set bit and reports old bit set.
    lea     0x00000dd2, %a1
    move.b  #0x03, (%a1)
    .word   0x08b8, 0x0000, 0x0dd2
    beq     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.b  (%a1), %d1
    cmp.b   #0x02, %d1
    bne     _fail

    | BSET #3,(xxx).W sets a clear bit and reports old bit clear.
    lea     0x00000dd3, %a2
    move.b  #0x00, (%a2)
    .word   0x08f8, 0x0003, 0x0dd3
    bne     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.b  (%a2), %d2
    cmp.b   #0x08, %d2
    bne     _fail

    | BCHG #7,(xxx).L toggles a set bit and reports old bit set.
    lea     0x00118100, %a3
    move.b  #0x80, (%a3)
    .word   0x0879, 0x0007, 0x0011, 0x8100
    beq     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.b  (%a3), %d3
    cmp.b   #0x00, %d3
    bne     _fail

    | BSET #5,(xxx).L on an already-set bit leaves the byte unchanged and Z=0.
    lea     0x00118104, %a4
    move.b  #0x20, (%a4)
    .word   0x08f9, 0x0005, 0x0011, 0x8104
    beq     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.b  (%a4), %d4
    cmp.b   #0x20, %d4
    bne     _fail

_pass:
    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a6)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a6
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a6)
_fail_halt:
    bra     _fail_halt
