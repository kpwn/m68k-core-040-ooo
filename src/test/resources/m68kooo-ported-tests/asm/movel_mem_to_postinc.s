| movel_mem_to_postinc.s -- MOVE.L (An),(Am)+
|
| Covers the Q700 ROM coalescer frontier:
|   40800ab0: 24d3    move.l (%a3),(%a2)+
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Exact ROM shape: source is plain (A3), destination is (A2)+.
    lea     0x00108000, %a3
    lea     0x00108100, %a2
    move.l  #0x12345678, (%a3)
    move.l  #0xaaaaaaaa, (%a2)
    .word   0x24d3              | move.l (%a3),(%a2)+
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00108000, %a3
    bne     _fail
    cmpa.l  #0x00108104, %a2
    bne     _fail
    move.l  -4(%a2), %d0
    cmp.l   #0x12345678, %d0
    bne     _fail

    | Negative source verifies MOVE.L flags come from the copied value.
    move.l  #0x80000000, (%a3)
    .word   0x24d3              | move.l (%a3),(%a2)+
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  -4(%a2), %d1
    cmp.l   #0x80000000, %d1
    bne     _fail
    cmpa.l  #0x00108108, %a2
    bne     _fail

    | Zero source sets Z and clears N/V/C.
    move.l  #0x00000000, (%a3)
    .word   0x24d3              | move.l (%a3),(%a2)+
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  -4(%a2), %d2
    bne     _fail
    cmpa.l  #0x0010810c, %a2
    bne     _fail

    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a6)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a6
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a6)
_fail_halt:
    bra     _fail_halt
