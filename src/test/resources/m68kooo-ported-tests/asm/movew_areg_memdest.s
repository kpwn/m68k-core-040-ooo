| movew_areg_memdest.s -- MOVE.W An,<memory destination>
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Indirect destination stores An[15:0] and sets N from bit 15.
    lea     0x00117000, %a3
    move.l  #0xffffffff, (%a3)
    lea     0x00008001, %a0
    move.w  %a0, (%a3)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a3), %d0
    cmp.l   #0x8001ffff, %d0
    bne     _fail

    | Postincrement destination advances by two bytes.
    lea     0x00117010, %a4
    move.l  #0xffffffff, (%a4)
    lea     0x00001234, %a1
    move.w  %a1, (%a4)+
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00117012, %a4
    bne     _fail
    move.l  0x00117010, %d0
    cmp.l   #0x1234ffff, %d0
    bne     _fail

    | Displacement destination writes only the addressed word lane.
    lea     0x00117020, %a5
    move.l  #0xffffffff, 4(%a5)
    lea     0x00000000, %a2
    move.w  %a2, 4(%a5)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  4(%a5), %d0
    cmp.l   #0x0000ffff, %d0
    bne     _fail

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
