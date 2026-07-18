| adda_word_mem_disp.s -- ADDA.W (d16,An),An sign-extension
|
| Covers the Q700 ROM frontier:
|   4088132a: d0e8 0010  adda.w 16(A0),A0
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Exact ROM opword with a positive word displacement payload.
    lea     0x00120000, %a0
    move.l  #0x001c0000, 16(%a0)
    moveq   #0, %d7
    tst.l   %d7
    .word   0xd0e8, 0x0010        | adda.w 16(%a0),%a0
    bne     _fail                 | ADDA must preserve CCR
    cmpa.l  #0x0012001c, %a0
    bne     _fail

    | Negative word source must sign-extend before the 32-bit address add.
    lea     0x00121000, %a1
    move.l  #0xfffe0000, 4(%a1)
    moveq   #-1, %d7
    tst.l   %d7
    .word   0xd2e9, 0x0004        | adda.w 4(%a1),%a1
    bpl     _fail
    beq     _fail
    cmpa.l  #0x00120ffe, %a1
    bne     _fail

    | Keep the existing long displaced-memory sibling covered.
    lea     0x00122000, %a2
    move.l  #0x00000100, 8(%a2)
    .word   0xd5ea, 0x0008        | adda.l 8(%a2),%a2
    cmpa.l  #0x00122100, %a2
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
    bra     _pass

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
    bra     _fail
