| movew_reg_to_postinc.s -- MOVE.W {Dn,An},(Am)+
|
| Covers the Q700 ROM frontier:
|   40881436: 32c1  move.w D1,(A1)+
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Exact ROM opword: D1 -> (A1)+, store low word and advance A1 by 2.
    lea     0x00130000, %a1
    move.l  #0xffffffff, (%a1)
    move.l  #0x0000000a, %d1
    .word   0x32c1                  | move.w %d1,(%a1)+
    bmi     _fail                   | MOVE.W flags from source word
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00130002, %a1
    bne     _fail
    move.l  0x00130000, %d0
    cmp.l   #0x000affff, %d0
    bne     _fail

    | Address-register direct source sibling.
    lea     0x00130100, %a2
    move.l  #0xffffffff, (%a2)
    lea     0x00137ffe, %a0
    .word   0x34c8                  | move.w %a0,(%a2)+
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00130102, %a2
    bne     _fail
    move.l  0x00130100, %d0
    cmp.l   #0x7ffeffff, %d0
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
