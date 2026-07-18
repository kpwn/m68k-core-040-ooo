| alu_basic.s — Basic ALU directed test
| Tests: MOVEQ, ADD, SUB, AND, OR, NEG, NOT, ADDQ, CMP, ADDX
| Signals PASS by writing 0xC0FFEE00 to 0xFFFF0000.
| Signals FAIL by writing anything else.
|
| (Historical note) Earlier rev used `moveq #0xFF, %d2` which binutils
| rejects (MOVEQ takes a signed 8-bit).  Rewritten here to use legal
| MOVEQ ranges + MOVE.L for the 0xFF case.

    .text

    | Basic MOVEQ
    moveq   #0, %d0
    moveq   #10, %d1
    moveq   #20, %d2

    | ADD
    add.l   %d1, %d0        | d0 = 0 + 10 = 10
    add.l   %d2, %d0        | d0 = 10 + 20 = 30

    | SUB
    moveq   #2, %d3
    sub.l   %d3, %d0        | d0 = 30 - 2 = 28

    | AND (preserve lower nibble only)
    moveq   #0x0F, %d4
    and.l   %d4, %d0        | d0 = 28 & 0xF = 12

    | OR
    moveq   #0x20, %d5
    or.l    %d5, %d0        | d0 = 12 | 32 = 44 = 0x2C

    | NEG
    neg.l   %d0             | d0 = -44 = 0xFFFFFFD4

    | NOT
    not.l   %d0             | d0 = ~0xFFFFFFD4 = 0x0000002B = 43

    | ADDQ
    addq.l  #1, %d0         | d0 = 44

    | CMP test (should set Z=1)
    moveq   #44, %d1
    cmp.l   %d1, %d0        | d0 - d1 = 0, sets Z=1
    bne     _fail

    | ADDX test: use MOVE.L for 0xFF (out of MOVEQ range)
    moveq   #1, %d0
    moveq   #1, %d1
    add.l   %d0, %d1        | X=0, C=0, d1=2
    move.l  #0xFF, %d2
    addx.l  %d0, %d2        | d2 = 0xFF + 1 + 0 = 0x100
    cmp.l   #0x100, %d2
    bne     _fail

    | Signal PASS
    move.l  #0xC0FFEE00, %d0
    move.l  #0xFFFF0000, %a0
    move.l  %d0, (%a0)

_halt:
    bra     _halt

_fail:
    move.l  #0xBADBAD00, %d0
    move.l  #0xFFFF0000, %a0
    move.l  %d0, (%a0)
_fhlt:
    bra     _fhlt
