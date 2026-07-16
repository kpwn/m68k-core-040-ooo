| eor_mem_rmw.s -- EOR.{B,W,L} Dn,<mem> read-modify-write
|
| Covers exact ROM-style memory destination opwords plus byte/word/long
| siblings across the same EA family:
|   b392  eor.l D1,(A2)
|   b592  eor.l D2,(A2)
|   EOR.{B,W,L} Dn,(An)/(An)+/-(An)/(d16,An)/(xxx).W/(xxx).L
|
| Checks that the memory operand is read, XORed, and written back, that
| the source Dn is preserved, that NZVC reflect the result, and that X is
| not written by EOR.

    .text
    .org 0

_start:
    lea     0x00106000, %a2

    | -- b392: negative nonzero result, C/V cleared, X preserved -------
    move.l  #0xffffffff, %d0
    moveq   #1, %d7
    add.l   %d7, %d0            | X=C=1

    move.l  #0x55aa55aa, (%a2)
    move.l  #0xff000000, %d1
    .word   0xb392              | eor.l D1,(A2)
    bpl     _fail               | N must be set from 0xaaaa55aa
    beq     _fail
    bvs     _fail
    bcs     _fail               | EOR clears C while preserving X

    moveq   #0, %d6
    moveq   #0, %d7
    addx.l  %d7, %d6            | consumes X preserved by EOR
    cmp.l   #1, %d6
    bne     _fail

    move.l  (%a2), %d3
    cmp.l   #0xaaaa55aa, %d3
    bne     _fail
    cmp.l   #0xff000000, %d1
    bne     _fail

    | -- b592: zero result, N/C/V cleared, X preserved -----------------
    move.l  #0xffffffff, %d0
    moveq   #1, %d7
    add.l   %d7, %d0            | X=C=1 again

    move.l  #0x12345678, (%a2)
    move.l  #0x12345678, %d2
    .word   0xb592              | eor.l D2,(A2)
    bne     _fail               | Z must be set
    bmi     _fail
    bvs     _fail
    bcs     _fail

    moveq   #0, %d6
    moveq   #0, %d7
    addx.l  %d7, %d6
    cmp.l   #1, %d6
    bne     _fail

    move.l  (%a2), %d3
    cmp.l   #0, %d3
    bne     _fail
    cmp.l   #0x12345678, %d2
    bne     _fail

    | -- Byte sibling: EOR.B D3,(A1)+, post-increment by 1 ------------
    lea     0x00106010, %a1
    move.l  #0xa5000000, (%a1)
    move.l  #0x000000ff, %d3
    eor.b   %d3, (%a1)+
    bmi     _fail               | 0xa5 ^ 0xff = 0x5a, so N must clear
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00106011, %a1
    bne     _fail
    move.b  -1(%a1), %d4
    cmpi.b  #0x5a, %d4
    bne     _fail
    cmp.l   #0x000000ff, %d3
    bne     _fail

    | -- Word sibling: EOR.W D4,-(A2), pre-decrement by 2 -------------
    lea     0x00106020, %a2
    move.l  #0x12340000, (%a2)
    lea     0x00106022, %a2
    move.l  #0x0000ffff, %d4
    eor.w   %d4, -(%a2)
    bpl     _fail               | 0x1234 ^ 0xffff = 0xedcb, N set
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00106020, %a2
    bne     _fail
    move.w  (%a2), %d5
    cmpi.w  #0xedcb, %d5
    bne     _fail

    | -- Long sibling: EOR.L D5,(d16,A3) ------------------------------
    lea     0x00106040, %a3
    move.l  #0x00ff00ff, 0x14(%a3)
    move.l  #0xff00ff00, %d5
    eor.l   %d5, 0x14(%a3)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  0x14(%a3), %d6
    cmp.l   #0xffffffff, %d6
    bne     _fail

    | -- Absolute word destination: EOR.W D6,(0x6100).W ---------------
    move.l  #0x00001234, %d0
    .word   0x31c0, 0x6100      | move.w D0,(0x6100).W
    move.l  #0x000000ff, %d6
    .word   0xbd78, 0x6100      | eor.w D6,(0x6100).W
    move.w  0x00006100, %d0
    cmpi.w  #0x12cb, %d0
    bne     _fail

    | -- Absolute long destination: EOR.B D7,(0x00106110).L -----------
    move.l  #0x80000000, 0x00106110
    move.l  #0x00000080, %d7
    .word   0xbf39, 0x0010, 0x6110  | eor.b D7,(0x00106110).L
    bne     _fail               | 0x80 ^ 0x80 = 0, Z set
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.b  0x00106110, %d0
    cmpi.b  #0, %d0
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
