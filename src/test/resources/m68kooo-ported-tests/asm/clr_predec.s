| clr_predec.s -- CLR.{B,W,L} -(An).

    .text
    .org 0

_start:
    lea     0x00108601, %a1
    move.l  #0xaaaaaaaa, -1(%a1)
    .word   0x4221              | clr.b -(%a1)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a1), %d0
    cmp.l   #0x00aaaaaa, %d0
    bne     _fail

    | CLR.B through A7 predecrements by two.
    lea     0x00108612, %a7
    move.l  #0xbbbbbbbb, -2(%a7)
    .word   0x4227              | clr.b -(%a7)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a7), %d1
    cmp.l   #0x00bbbbbb, %d1
    bne     _fail

    lea     0x00108622, %a2
    move.l  #0xcccccccc, -2(%a2)
    .word   0x4262              | clr.w -(%a2)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a2), %d2
    cmp.l   #0x0000cccc, %d2
    bne     _fail

    | CLR.W through A7 predecrements by two.
    lea     0x00108642, %a7
    move.l  #0xeeeeeeee, -2(%a7)
    .word   0x4267              | clr.w -(%a7)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a7), %d4
    cmp.l   #0x0000eeee, %d4
    bne     _fail
    move.l  %a7, %d5
    cmp.l   #0x00108640, %d5
    bne     _fail

    lea     0x00108634, %a3
    move.l  #0xdddddddd, -4(%a3)
    .word   0x42a3              | clr.l -(%a3)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a3), %d3
    cmp.l   #0x00000000, %d3
    bne     _fail

    | CLR.L through A7 predecrements by four.
    lea     0x00108664, %a7
    move.l  #0xffffffff, -4(%a7)
    .word   0x42a7              | clr.l -(%a7)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a7), %d6
    cmp.l   #0x00000000, %d6
    bne     _fail
    move.l  %a7, %d7
    cmp.l   #0x00108660, %d7
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
