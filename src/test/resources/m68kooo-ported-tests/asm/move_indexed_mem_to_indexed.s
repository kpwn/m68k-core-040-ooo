| move_indexed_mem_to_indexed.s -- MOVE.L indexed memory to indexed memory
|
| Exercises the exact Q700 ROM frontier:
|   4088195e: 21b1 2000 2000  move.l (0,A1,D2.W),(0,A0,D2.W)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00116000, %a1
    lea     0x00116100, %a0
    moveq   #8, %d2
    move.l  #0x89abcdef, 8(%a1)
    move.l  #0x00000000, 8(%a0)

    .word   0x21b1, 0x2000, 0x2000
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail

    move.l  8(%a1), %d0
    cmp.l   #0x89abcdef, %d0
    bne     _fail
    move.l  8(%a0), %d1
    cmp.l   #0x89abcdef, %d1
    bne     _fail

    | Same exact encoding with zero data, to pin Z flag and the D2=0 path.
    lea     0x00116200, %a1
    lea     0x00116300, %a0
    moveq   #0, %d2
    move.l  #0x00000000, (%a1)
    move.l  #0xffffffff, (%a0)

    .word   0x21b1, 0x2000, 0x2000
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail

    move.l  (%a0), %d3
    cmp.l   #0x00000000, %d3
    bne     _fail

    | Source-indexed to plain address-indirect destination.  Exact Q700 ROM
    | frontier:
    |   4080092a: 26b0 3000  move.l (0,A0,D3.W),(A3)
    lea     0x00116400, %a0
    lea     0x00116500, %a3
    moveq   #8, %d3
    move.l  #0x89abcdef, 8(%a0)
    move.l  #0x00000000, (%a3)

    .word   0x26b0, 0x3000
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail

    move.l  (%a3), %d4
    cmp.l   #0x89abcdef, %d4
    bne     _fail

    | Word sibling, same brief D3.W source index to (A3).
    lea     0x00116600, %a0
    lea     0x00116700, %a3
    moveq   #6, %d3
    move.w  #0x8001, 6(%a0)
    move.w  #0x0000, (%a3)

    .word   0x36b0, 0x3000
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail

    move.w  (%a3), %d5
    cmp.w   #0x8001, %d5
    bne     _fail

    | Byte sibling with a zero result, to pin Z for memory-to-memory byte.
    lea     0x00116800, %a0
    lea     0x00116900, %a3
    moveq   #3, %d3
    move.b  #0x00, 3(%a0)
    move.b  #0xff, (%a3)

    .word   0x16b0, 0x3000
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail

    move.b  (%a3), %d6
    cmp.b   #0x00, %d6
    bne     _fail

    | Long-index sibling.  The -4 displacement plus D4.L reaches 8(A0).
    lea     0x00116a00, %a0
    lea     0x00116b00, %a3
    move.l  #0x0000000c, %d4
    move.l  #0x7fffffff, 8(%a0)
    move.l  #0x00000000, (%a3)

    .word   0x26b0, 0x48fc
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail

    move.l  (%a3), %d4
    cmp.l   #0x7fffffff, %d4
    bne     _fail

    | Source-indexed to absolute-short destination.  Exact Q700 ROM shape:
    |   40804112: 21f0 3000 0c00  move.l (0,A0,D3.W),0xc00.W
    lea     0x00116c00, %a0
    lea     0x00000c00, %a5
    moveq   #4, %d3
    move.l  #0x80000001, 4(%a0)
    move.l  #0x00000000, (%a5)

    .word   0x21f0, 0x3000, 0x0c00
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail

    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.l  (%a5), %d0
    cmp.l   #0x80000001, %d0
    bne     _fail

    | Word and byte siblings to absolute-short destinations.
    lea     0x00116d00, %a0
    lea     0x00000c08, %a5
    moveq   #6, %d3
    move.w  #0x7fff, 6(%a0)
    move.w  #0x0000, (%a5)

    .word   0x31f0, 0x3000, 0x0c08
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail

    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.w  (%a5), %d1
    cmp.w   #0x7fff, %d1
    bne     _fail

    lea     0x00116e00, %a0
    lea     0x00000c0a, %a5
    moveq   #2, %d3
    move.b  #0x00, 2(%a0)
    move.b  #0xff, (%a5)

    .word   0x11f0, 0x3000, 0x0c0a
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail

    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.b  (%a5), %d2
    cmp.b   #0x00, %d2
    bne     _fail

    | Absolute-long destination with one source extension before the address.
    lea     0x00116f00, %a0
    lea     0x00117000, %a5
    moveq   #8, %d3
    move.l  #0x12345678, 8(%a0)
    move.l  #0x00000000, (%a5)

    .word   0x23f0, 0x3000, 0x0011, 0x7000
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail

    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.l  (%a5), %d3
    cmp.l   #0x12345678, %d3
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
