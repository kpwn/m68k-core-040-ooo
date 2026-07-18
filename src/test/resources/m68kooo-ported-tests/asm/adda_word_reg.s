| adda_word_reg.s -- ADDA.W direct register sources
|
| Covers the Q700 ROM frontier:
|   408814a0: d0c0  adda.w D0,A0

    .text
    .org 0

_start:
    | Exact frontier form: ADDA.W D0,A0 sign-extends the low source word.
    lea     0x0014fe90, %a0
    move.l  #0x00000012, %d0
    moveq   #-1, %d7
    tst.l   %d7
    .word   0xd0c0              | adda.w %d0,%a0
    bpl     _fail1              | ADDA must preserve N/Z/V/C from TST
    beq     _fail1
    cmpa.l  #0x0014fea2, %a0
    bne     _fail1

    | Negative data-register word sources sign-extend before the long add.
    lea     0x00001000, %a2
    move.l  #0x0000ffe7, %d1
    moveq   #0, %d7
    tst.l   %d7
    .word   0xd4c1              | adda.w %d1,%a2
    bne     _fail2
    cmpa.l  #0x00000fe7, %a2
    bne     _fail2

    | Address-register direct source uses the low word and sign-extends it.
    move.l  #0x0000fffe, %a3
    lea     0x00002000, %a4
    cmp.l   %d7, %d7
    .word   0xd8cb              | adda.w %a3,%a4
    bne     _fail3
    cmpa.l  #0x00001ffe, %a4
    bne     _fail3

_pass:
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, 0xFFFF0000
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d0
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d0
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d0

_fail:
    move.l  %d0, 0xFFFF0000
_fail_halt:
    bra     _fail_halt
