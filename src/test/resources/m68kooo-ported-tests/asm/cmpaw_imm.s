| cmpaw_imm.s -- CMPA.W #imm16,An
|
| Covers the Q700 ROM block-list scan instruction:
|   0xb0fc 0xffff  cmpa.w #-1,a0

    .text
    .org 0

_start:
    | Exact ROM blocker: A0 == sign_extend(0xffff) should set Z.
    movea.l #0xffffffff, %a0
    .word   0xb0fc, 0xffff        | cmpa.w #-1,%a0
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail

    | A0 below -1 gives a negative result and a borrow.
    movea.l #0xfffffffe, %a0
    .word   0xb0fc, 0xffff        | cmpa.w #-1,%a0
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcc     _fail

    | A0 above zero against #0 gives a positive result with no borrow.
    movea.l #0x00000001, %a0
    .word   0xb0fc, 0x0000        | cmpa.w #0,%a0
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_fail_halt:
    bra     _fail_halt
