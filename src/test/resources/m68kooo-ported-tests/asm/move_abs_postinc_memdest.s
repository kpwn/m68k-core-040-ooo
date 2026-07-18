| move_abs_postinc_memdest.s -- MOVE.{B,W,L} (xxx).{W,L},(An)+
|
| Covers the Q700 ROM frontier:
|   34f8 0326  move.w 0x0326.W,(A2)+

    .text
    .org 0

_start:
    | Byte from absolute-short to postincrement destination.
    lea     0x00107010, %a2
    move.l  #0x80017f02, 0x00007000
    move.l  #0xaaaaaaaa, (%a2)
    .word   0x14f8, 0x7000       | move.b 0x7000.W,(A2)+
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00107011, %a2
    bne     _fail
    move.l  0x00107010, %d0
    cmp.l   #0x80aaaaaa, %d0
    bne     _fail

    | Exact ROM word opcode: absolute-short source to postincrement dest.
    lea     0x00107020, %a2
    move.w  #0x8001, 0x00000326
    move.l  #0xaaaabbbb, (%a2)
    .word   0x34f8, 0x0326       | move.w 0x0326.W,(A2)+
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00107022, %a2
    bne     _fail
    move.l  0x00107020, %d1
    cmp.l   #0x8001bbbb, %d1
    bne     _fail

    | Long from absolute-long to postincrement destination.
    lea     0x00107050, %a3
    move.l  #0x11223344, 0x00107040
    move.l  #0xaaaaaaaa, (%a3)
    .word   0x26f9, 0x0010, 0x7040 | move.l 0x00107040.L,(A3)+
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00107054, %a3
    bne     _fail
    move.l  0x00107050, %d2
    cmp.l   #0x11223344, %d2
    bne     _fail

    | Byte postincrement still applies the A7 +2 rule.
    lea     0x00107060, %a7
    move.l  #0x00ffffff, 0x00007004
    move.l  #0x55555555, (%a7)
    .word   0x1ef8, 0x7004       | move.b 0x7004.W,(A7)+
    bmi     _fail
    bne     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00107062, %a7
    bne     _fail
    move.l  0x00107060, %d3
    cmp.l   #0x00555555, %d3
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
