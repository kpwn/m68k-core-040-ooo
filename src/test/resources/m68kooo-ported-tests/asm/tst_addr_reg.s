| tst_addr_reg.s -- 68020+ TST.W/L on address registers
|
| The Q700 ROM uses 4a48 (tst.w A0).  Address-register TST is valid on
| 68020+ for word and long sizes, but not byte.

    .text
    .org 0

_start:
    | Exact frontier shape: A0 low word is zero, so Z=1.
    lea     0x7fff0000, %a0
    .word   0x4a48              | tst.w %a0
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail

    | Word-size TST uses the low 16 bits and sets N from bit 15.
    lea     0x00008001, %a1
    .word   0x4a49              | tst.w %a1
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail

    | Long-size TST uses the full address register.
    lea     0x00123456, %a2
    .word   0x4a8a              | tst.l %a2
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail

    lea     0x80123456, %a3
    .word   0x4a8b              | tst.l %a3
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail

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
