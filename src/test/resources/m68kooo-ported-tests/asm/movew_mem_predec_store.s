| movew_mem_predec_store.s -- MOVE.W (An),-(Am) and (d16,An),-(Am)

    .text
    .org 0

_start:
    lea     0x00107110, %a1
    lea     0x00107124, %a2

    move.l  #0x80015555, (%a1)
    move.l  #0xaaaaaaaa, -4(%a2)
    .word   0x3511              | move.w (%a1),-(%a2)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a2, %d0
    cmp.l   #0x00107122, %d0
    bne     _fail
    move.l  -2(%a2), %d1
    cmp.l   #0xaaaa8001, %d1
    bne     _fail

    | Displaced zero source sets Z and steps by 2.
    move.l  #0x00001234, 8(%a1)
    move.l  #0xaaaaaaaa, -4(%a2)
    .word   0x3529, 0x0008       | move.w 8(%a1),-(%a2)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a2, %d2
    cmp.l   #0x00107120, %d2
    bne     _fail
    move.l  (%a2), %d3
    cmp.l   #0x00008001, %d3
    bne     _fail

    | Absolute-word source: current ROM frontier at 0x40801292.
    lea     0x00007140, %a0
    lea     0x00107160, %a7
    move.l  #0x80010000, (%a0)
    move.l  #0xaaaaaaaa, -4(%a7)
    .word   0x3f38, 0x7140       | move.w 0x7140.w,-(%a7)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a7, %d4
    cmp.l   #0x0010715e, %d4
    bne     _fail
    move.w  (%a7), %d5
    cmp.w   #0x8001, %d5
    bne     _fail

    | Absolute-long source uses the same predecrement crack.
    lea     0x00107170, %a0
    lea     0x00107190, %a2
    move.l  #0x00000000, (%a0)
    move.l  #0xaaaaaaaa, -4(%a2)
    .word   0x3539, 0x0010, 0x7170 | move.w 0x00107170.l,-(%a2)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a2, %d6
    cmp.l   #0x0010718e, %d6
    bne     _fail
    move.w  (%a2), %d7
    cmp.w   #0x0000, %d7
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
