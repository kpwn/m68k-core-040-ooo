| movel_abs_to_reg.s -- MOVE.L (xxx).{W,L},Dn absolute-source forms.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Absolute-word source loads the full long and sets N from bit 31.
    lea     0x00001240, %a0
    move.l  #0x80000001, (%a0)
    move.l  #0x11223344, %d0
    .word   0x2038, 0x1240      | move.l 0x1240.W,%d0
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x80000001, %d0
    bne     _fail

    | Absolute-long source loads zero and sets Z.
    lea     0x00101244, %a1
    move.l  #0x00000000, (%a1)
    move.l  #0xaabbccdd, %d1
    .word   0x2239, 0x0010, 0x1244  | move.l 0x00101244.L,%d1
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x00000000, %d1
    bne     _fail

    | Positive non-zero absolute-long source clears N/Z/V/C.
    lea     0x00101248, %a2
    move.l  #0x2468ace0, (%a2)
    move.l  #0xdeadbeef, %d2
    .word   0x2439, 0x0010, 0x1248  | move.l 0x00101248.L,%d2
    beq     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x2468ace0, %d2
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
