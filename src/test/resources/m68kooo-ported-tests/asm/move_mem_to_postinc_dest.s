| move_mem_to_postinc_dest.s -- MOVE.{B,W} memory source to postincrement destinations.

    .text
    .org 0

_start:
    | MOVE.B (A1),(A2)+
    lea     0x00108300, %a1
    lea     0x00108310, %a2
    move.l  #0x80000000, (%a1)
    move.l  #0xaaaaaaaa, (%a2)
    .word   0x14d1              | move.b (%a1),(%a2)+
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  0x00108310, %d0
    cmp.l   #0x80aaaaaa, %d0
    bne     _fail
    move.l  %a2, %d1
    cmp.l   #0x00108311, %d1
    bne     _fail

    | MOVE.B (A1),(A7)+ uses the A7 byte +2 rule.
    lea     0x00108320, %a1
    lea     0x00108330, %a7
    move.l  #0x00000000, (%a1)
    move.l  #0xbbbbbbbb, (%a7)
    .word   0x1ed1              | move.b (%a1),(%a7)+
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  0x00108330, %d2
    cmp.l   #0x00bbbbbb, %d2
    bne     _fail
    move.l  %a7, %d3
    cmp.l   #0x00108332, %d3
    bne     _fail

    | MOVE.W (A3),(A4)+
    lea     0x00108340, %a3
    lea     0x00108350, %a4
    move.l  #0x8001ffff, (%a3)
    move.l  #0xcccccccc, (%a4)
    .word   0x38d3              | move.w (%a3),(%a4)+
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  0x00108350, %d4
    cmp.l   #0x8001cccc, %d4
    bne     _fail
    move.l  %a4, %d5
    cmp.l   #0x00108352, %d5
    bne     _fail

    | MOVE.W (A1),(A7)+ postincrements A7 by the word step.
    lea     0x00108380, %a1
    lea     0x00108390, %a7
    move.l  #0x7fff0000, (%a1)
    move.l  #0xeeeeeeee, (%a7)
    .word   0x3ed1              | move.w (%a1),(%a7)+
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  0x00108390, %d6
    cmp.l   #0x7fffeeee, %d6
    bne     _fail
    move.l  %a7, %d7
    cmp.l   #0x00108392, %d7
    bne     _fail

    | MOVE.W (A5)+,(A6)+ increments both sides.
    lea     0x00108360, %a5
    lea     0x00108370, %a6
    move.l  #0x00001234, (%a5)
    move.l  #0xdddddddd, (%a6)
    .word   0x3cdd              | move.w (%a5)+,(%a6)+
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  0x00108370, %d6
    cmp.l   #0x0000dddd, %d6
    bne     _fail
    move.l  %a5, %d7
    cmp.l   #0x00108362, %d7
    bne     _fail
    move.l  %a6, %d7
    cmp.l   #0x00108372, %d7
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
