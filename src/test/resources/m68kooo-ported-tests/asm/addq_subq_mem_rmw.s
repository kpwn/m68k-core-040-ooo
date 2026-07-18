| addq_subq_mem_rmw.s -- ADDQ/SUBQ memory-destination read-modify-write
|
| Exercises the exact Q700 ROM frontier:
|   40881902: 59ac fff0  subq.l #4,-16(A4)
| plus byte/word/long siblings for (d16,An), full-format memory-indirect,
| and a plain (An) smoke.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | -- Exact ROM long form: SUBQ.L #4,-16(A4) ----------------------
    lea     0x00114010, %a4
    move.l  #0x00000020, -16(%a4)
    .word   0x59ac, 0xfff0        | subq.l #4,-16(%a4)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  -16(%a4), %d0
    cmp.l   #0x0000001c, %d0
    bne     _fail

    | -- Long sibling: 0 - 1 => 0xffffffff, borrow and negative -------
    move.l  #0x00000000, -12(%a4)
    subq.l  #1, -12(%a4)
    bcc     _fail
    bpl     _fail
    beq     _fail
    bvs     _fail
    move.l  -12(%a4), %d1
    cmp.l   #0xffffffff, %d1
    bne     _fail

    | -- Word sibling: 0xfff8 + 8 => 0x0000, carry and zero ----------
    lea     0x00114040, %a1
    move.l  #0x0000fff8, 4(%a1)
    addq.w  #8, 6(%a1)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcc     _fail
    move.w  6(%a1), %d2
    cmpi.w  #0x0000, %d2
    bne     _fail

    | -- Byte sibling: 1 - 2 => 0xff, borrow and negative ------------
    lea     0x00114080, %a0
    move.l  #0x00000001, (%a0)
    subq.b  #2, 3(%a0)
    bcc     _fail
    bpl     _fail
    beq     _fail
    bvs     _fail
    move.b  3(%a0), %d3
    cmpi.b  #0xff, %d3
    bne     _fail

    | -- Plain (An) memory RMW: ADDQ.L #3,(A2) -----------------------
    lea     0x001140c0, %a2
    move.l  #0x00000005, (%a2)
    addq.l  #3, (%a2)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a2), %d4
    cmp.l   #0x00000008, %d4
    bne     _fail

    | -- Full-format memory-indirect exact ROM word form --------------
    | 5270 81e2 0cbc ffee: addq.w #1,([0x0cbc],-18)
    lea     0x00000cbc, %a6
    lea     0x00114200, %a1
    move.l  %a1, (%a6)
    move.l  #0x11227fff, -20(%a1)
    lea     0x0badf00d, %a0
    .word   0x5270, 0x81e2, 0x0cbc, 0xffee
    bpl     _fail
    beq     _fail
    bvc     _fail
    bcs     _fail
    move.w  -18(%a1), %d5
    cmpi.w  #0x8000, %d5
    bne     _fail

    | -- Full-format byte sibling: subq.b #1,([0x0cd0],-5) ----------
    lea     0x00000cd0, %a6
    lea     0x00114240, %a1
    move.l  %a1, (%a6)
    move.l  #0x11223300, -8(%a1)
    .word   0x5330, 0x81e2, 0x0cd0, 0xfffb
    bcc     _fail
    bpl     _fail
    beq     _fail
    bvs     _fail
    move.b  -5(%a1), %d5
    cmpi.b  #0xff, %d5
    bne     _fail

    | -- Full-format long sibling: addq.l #8,([0x0cd4],-4) ----------
    lea     0x00000cd4, %a6
    lea     0x00114280, %a1
    move.l  %a1, (%a6)
    move.l  #0xfffffff8, -4(%a1)
    .word   0x50b0, 0x81e2, 0x0cd4, 0xfffc
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcc     _fail
    move.l  -4(%a1), %d5
    cmp.l   #0x00000000, %d5
    bne     _fail

    | -- Full-format remaining ADDQ.B sibling ------------------------
    lea     0x00000cd8, %a6
    lea     0x001142c0, %a1
    move.l  %a1, (%a6)
    move.l  #0x1122337f, -4(%a1)
    .word   0x5230, 0x81e2, 0x0cd8, 0xffff
    bpl     _fail
    beq     _fail
    bvc     _fail
    bcs     _fail
    move.b  -1(%a1), %d5
    cmpi.b  #0x80, %d5
    bne     _fail

    | -- Full-format remaining SUBQ.W sibling ------------------------
    lea     0x00000cdc, %a6
    lea     0x00114300, %a1
    move.l  %a1, (%a6)
    move.l  #0x11228000, -4(%a1)
    .word   0x5370, 0x81e2, 0x0cdc, 0xfffe
    bmi     _fail
    beq     _fail
    bvc     _fail
    bcs     _fail
    move.w  -2(%a1), %d5
    cmpi.w  #0x7fff, %d5
    bne     _fail

    | -- Full-format remaining SUBQ.L sibling ------------------------
    lea     0x00000ce0, %a6
    lea     0x00114340, %a1
    move.l  %a1, (%a6)
    move.l  #0x00000000, -4(%a1)
    .word   0x51b0, 0x81e2, 0x0ce0, 0xfffc
    bcc     _fail
    bpl     _fail
    beq     _fail
    bvs     _fail
    move.l  -4(%a1), %d5
    cmp.l   #0xfffffff8, %d5
    bne     _fail

_pass:
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
