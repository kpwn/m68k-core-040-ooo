| movew_reg_to_mem.s -- MOVE.W {Dn,An},(Am).

    .text
    .org 0

_start:
    lea     0x00108200, %a2
    move.l  #0xaaaaaaaa, (%a2)
    move.l  #0x00008001, %d1
    .word   0x3481              | move.w %d1,(%a2)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a2), %d0
    cmp.l   #0x8001aaaa, %d0
    bne     _fail

    lea     0x00108210, %a3
    move.l  #0xbbbbbbbb, (%a3)
    lea     0x00101234, %a0
    .word   0x3688              | move.w %a0,(%a3)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a3), %d2
    cmp.l   #0x1234bbbb, %d2
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
