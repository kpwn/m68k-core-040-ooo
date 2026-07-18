| prm_cmpa_w_sign_ext.s — CMPA.W sign-extends operand before compare.
|
| Spec: M68000 PRM, CMPA.  Word-sized CMPA sign-extends the effective
| address operand to 32 bits, then compares it with the full 32-bit An.

    .text
    .org 0

_start:
    movea.l #0xffffffff, %a0
    cmpa.w  #0xffff, %a0        | #0xffff sign-extends to -1
    bne     _fail1

    movea.l #0x0000ffff, %a0
    cmpa.w  #0xffff, %a0        | not equal to sign-extended -1
    beq     _fail2

    lea     word_operand, %a1
    movea.l #0xffffff80, %a0
    cmpa.w  (%a1), %a0          | 0xff80 sign-extends to 0xffffff80
    bne     _fail3

_pass:
    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a2)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d7

_fail:
    lea     0xFFFF0000, %a2
    move.l  %d7, (%a2)
_halt_fail:
    bra     _halt_fail

    .align 2
word_operand:
    .word   0xff80
