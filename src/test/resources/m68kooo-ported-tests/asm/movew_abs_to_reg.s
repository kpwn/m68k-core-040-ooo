| movew_abs_to_reg.s -- MOVE.W (xxx).{W,L},Dn absolute-source forms.

    .text
    .org 0

_start:
    | Absolute-word source preserves D0[31:16] and sets N from bit 15.
    lea     0x00007000, %a0
    move.l  #0x8001ffff, (%a0)
    move.l  #0x11223344, %d0
    .word   0x3038, 0x7000      | move.w 0x7000.W,%d0
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x11228001, %d0
    bne     _fail

    | Absolute-long source preserves D1[31:16] and sets Z from a zero word.
    lea     0x00108010, %a1
    move.l  #0x00001234, (%a1)
    move.l  #0xaabbccdd, %d1
    .word   0x3239, 0x0010, 0x8010  | move.w 0x00108010.L,%d1
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0xaabb0000, %d1
    bne     _fail

_pass:
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, 0xFFFF0000
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, 0xFFFF0000
_fail_halt:
    bra     _fail_halt
