| move_postinc_abs_store.s -- MOVE.{B,W,L} (An)+,(xxx).{W,L}
|
| Covers source-postincrement memory copies to absolute destinations,
| including the Q700 ROM shape:
|   408012a2: 31df 0b22  move.w (A7)+,0x0b22.W
| Long forms verify destination data directly.  Byte/word forms verify
| decode, flags, and postincrement; byte-lane absolute-store readback is
| already covered by move_abs_store and move_abs_mem_to_abs.

    .text
    .global _start
_start:
    | Long source to absolute-short destination, flags from copied value.
    lea     0x00108000, %a0
    lea     0x00007200, %a1
    move.l  #0x80000001, (%a0)
    move.l  #0xaaaaaaaa, (%a1)
    .word   0x21d8, 0x7200       | move.l (%a0)+,0x7200.w
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a0, %d0
    cmp.l   #0x00108004, %d0
    bne     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.l  (%a1), %d1
    cmp.l   #0x80000001, %d1
    bne     _fail

    | Long source to absolute-long destination, zero flags.
    lea     0x00108010, %a0
    lea     0x00108030, %a1
    move.l  #0x00000000, (%a0)
    move.l  #0xaaaaaaaa, (%a1)
    .word   0x23d8, 0x0010, 0x8030 | move.l (%a0)+,0x00108030.l
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a0, %d2
    cmp.l   #0x00108014, %d2
    bne     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.l  (%a1), %d3
    bne     _fail

    | Word source through A7 to absolute-short destination, exact ROM form.
    lea     0x00108040, %a7
    lea     0x00007212, %a1
    move.l  #0x80011234, (%a7)
    .word   0x31df, 0x7212       | move.w (%a7)+,0x7212.w
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a7, %d4
    cmp.l   #0x00108042, %d4
    bne     _fail

    | Word source to absolute-long destination, zero flags.
    lea     0x00108050, %a0
    lea     0x00108072, %a1
    move.l  #0x00001234, (%a0)
    .word   0x33d8, 0x0010, 0x8072 | move.w (%a0)+,0x00108072.l
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a0, %d6
    cmp.l   #0x00108052, %d6
    bne     _fail

    | Byte source through A7 to absolute-short destination: A7 advances by 2.
    lea     0x00108080, %a7
    lea     0x00007221, %a1
    move.l  #0x80000000, (%a7)
    .word   0x11df, 0x7221       | move.b (%a7)+,0x7221.w
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a7, %d0
    cmp.l   #0x00108082, %d0
    bne     _fail

    | Byte source to absolute-long destination: non-A7 advances by 1.
    lea     0x00108090, %a0
    lea     0x001080b2, %a1
    move.l  #0x00000000, (%a0)
    .word   0x13d8, 0x0010, 0x80b2 | move.b (%a0)+,0x001080b2.l
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a0, %d2
    cmp.l   #0x00108091, %d2
    bne     _fail

_pass:
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, 0xFFFF0000
_pass_halt:
    bra     _pass_halt

_fail:
    move.l  #0xDEAD0000, %d0
    move.l  %d0, 0xFFFF0000
_fail_halt:
    bra     _fail_halt
