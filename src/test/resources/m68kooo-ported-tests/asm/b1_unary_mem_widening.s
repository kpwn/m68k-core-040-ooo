| b1_unary_mem_widening.s — V2 B1: unary mem-dst non-indexed for CLR/NOT/TST.
|
| Exercises corners that the legacy catch-all tests didn't cover:
|   * TST (xxx).L (long abs, not just .W)
|   * NOT (xxx).L (long abs)
|   * CLR (xxx).L (long abs)
|   * NOT (An)+ with A7 special-case (+2 byte stride)
|   * NOT -(An) byte/word/long on regular An
|   * NOT -(A7) byte (A7 -2 rule)
|   * CLR -(A7) byte (A7 -2 rule)
|   * Flag-only non-zero result for NOT mem (sets N)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | TST.B (xxx).L  — long-form absolute, value 0x80 sets N.
    lea     0x00120000, %a0
    move.b  #0x80, (%a0)
    .word   0x4a39, 0x0012, 0x0000  | tst.b 0x00120000.L
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail

    | TST.W (xxx).L — value 0 sets Z.
    move.w  #0x0000, (%a0)
    .word   0x4a79, 0x0012, 0x0000  | tst.w 0x00120000.L
    bne     _fail
    bmi     _fail

    | TST.L (xxx).L — value 0xFFFFFFFF sets N.
    move.l  #0xffffffff, (%a0)
    .word   0x4ab9, 0x0012, 0x0000  | tst.l 0x00120000.L
    bpl     _fail

    | NOT.L (xxx).L — long abs.  NOT(0)=0xFFFFFFFF: N=1, Z=0.
    move.l  #0x00000000, (%a0)
    .word   0x46b9, 0x0012, 0x0000  | not.l 0x00120000.L
    beq     _fail
    bpl     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a0), %d0
    cmp.l   #0xffffffff, %d0
    bne     _fail

    | CLR.L (xxx).L — long abs.
    move.l  #0xa5a5a5a5, (%a0)
    .word   0x42b9, 0x0012, 0x0000  | clr.l 0x00120000.L
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a0), %d0
    cmp.l   #0x00000000, %d0
    bne     _fail

    | NOT.B (An)+ on A7 — A7 advances by 2 (byte-stack rule).
    lea     0x00120200, %a7
    move.l  #0x00000000, (%a7)
    .word   0x461f                  | not.b (%a7)+
    cmp.l   #0x00120202, %a7
    bne     _fail
    move.l  0x00120200, %d0
    cmp.l   #0xff000000, %d0
    bne     _fail

    | NOT.W (An)+ on A0 — A0 advances by 2.
    lea     0x00120300, %a0
    move.w  #0x00ff, (%a0)
    .word   0x4658                  | not.w (%a0)+
    cmp.l   #0x00120302, %a0
    bne     _fail
    move.w  0x00120300, %d0
    cmp.w   #0xff00, %d0
    bne     _fail

    | NOT.L (An)+ on A1.
    lea     0x00120400, %a1
    move.l  #0x55aa55aa, (%a1)
    .word   0x4699                  | not.l (%a1)+
    cmp.l   #0x00120404, %a1
    bne     _fail
    move.l  0x00120400, %d0
    cmp.l   #0xaa55aa55, %d0
    bne     _fail

    | NOT.B -(An) regular An (decrement by 1).
    lea     0x00120501, %a2
    move.b  #0xa5, -1(%a2)
    .word   0x4622                  | not.b -(%a2)
    cmp.l   #0x00120500, %a2
    bne     _fail
    move.b  0x00120500, %d0
    cmp.b   #0x5a, %d0
    bne     _fail

    | NOT.W -(An).
    lea     0x00120602, %a3
    move.w  #0x1234, -2(%a3)
    .word   0x4663                  | not.w -(%a3)
    cmp.l   #0x00120600, %a3
    bne     _fail
    move.w  0x00120600, %d0
    cmp.w   #0xedcb, %d0
    bne     _fail

    | NOT.L -(An).
    lea     0x00120704, %a4
    move.l  #0x12345678, -4(%a4)
    .word   0x46a4                  | not.l -(%a4)
    cmp.l   #0x00120700, %a4
    bne     _fail
    move.l  0x00120700, %d0
    cmp.l   #0xedcba987, %d0
    bne     _fail

    | NOT.B -(A7) — A7 -2 rule.
    lea     0x00120802, %a7
    move.b  #0x00, -2(%a7)
    .word   0x4627                  | not.b -(%a7)
    cmp.l   #0x00120800, %a7
    bne     _fail
    move.b  0x00120800, %d0
    cmp.b   #0xff, %d0
    bne     _fail

    | CLR.B -(A7) — A7 -2 rule.
    lea     0x00120902, %a7
    move.b  #0xaa, -2(%a7)
    .word   0x4227                  | clr.b -(%a7)
    cmp.l   #0x00120900, %a7
    bne     _fail
    move.b  0x00120900, %d0
    cmp.b   #0x00, %d0
    bne     _fail

    | All passed.
    move.l  #0xc0ffee00, %d0
    move.l  %d0, 0xffff0000
    bra     .

_fail:
    move.l  #0xdeadbeef, %d0
    move.l  %d0, 0xffff0000
    bra     .
