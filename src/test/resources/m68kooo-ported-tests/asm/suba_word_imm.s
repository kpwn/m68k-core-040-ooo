| suba_word_imm.s -- SUBA.W #imm,An immediate sign extension
|
| Covers the Q700 ROM frame setup hit:
|   40847138: 9afc 0032  suba.w #50,%a5
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | SUBA.W #$0032,A5 subtracts a sign-extended word and preserves CCR.
    move.l  #0x00010000, %a5
    moveq   #0, %d7
    tst.l   %d7
    .word   0x9afc, 0x0032      | suba.w #50,%a5
    bne     _fail1              | SUBA must preserve Z from TST
    cmpa.l  #0x0000ffce, %a5
    bne     _fail1

    | Negative word immediates must sign-extend before the address subtract.
    move.l  #0x00001000, %a5
    moveq   #-1, %d7
    tst.l   %d7
    .word   0x9afc, 0xfffe      | suba.w #-2,%a5
    bpl     _fail2              | SUBA must preserve N from TST
    beq     _fail2
    cmpa.l  #0x00001002, %a5
    bne     _fail2

    | Keep the existing long-immediate path covered after widening the branch.
    move.l  #0x00020000, %a5
    .word   0x9bfc, 0x0000, 0x0100  | suba.l #0x100,%a5
    cmpa.l  #0x0001ff00, %a5
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
