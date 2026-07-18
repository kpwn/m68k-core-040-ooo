| adda_word_imm.s -- ADDA.W #imm,An immediate sign extension
|
| Covers the Q700 ROM VBR-vector helper hit:
|   40847a9a: dafc 0070  adda.w #112,%a5

    .text
    .org 0

_start:
    | ADDA.W #$0070,A5 adds a sign-extended word and preserves CCR.
    move.l  #0x00010000, %a5
    moveq   #0, %d7
    tst.l   %d7
    .word   0xdafc, 0x0070      | adda.w #112,%a5
    bne     _fail1              | ADDA must preserve Z from TST
    cmpa.l  #0x00010070, %a5
    bne     _fail1

    | Negative word immediates sign-extend before the address add.
    move.l  #0x00001000, %a5
    moveq   #-1, %d7
    tst.l   %d7
    .word   0xdafc, 0xfffe      | adda.w #-2,%a5
    bpl     _fail2              | ADDA must preserve N from TST
    beq     _fail2
    cmpa.l  #0x00000ffe, %a5
    bne     _fail2

    | Keep the existing long-immediate path covered after widening.
    move.l  #0x00020000, %a5
    .word   0xdbfc, 0x0000, 0x0100  | adda.l #0x100,%a5
    cmpa.l  #0x00020100, %a5
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
    bra     _fail

_fail:
    move.l  %d0, 0xFFFF0000
_fail_halt:
    bra     _fail_halt
