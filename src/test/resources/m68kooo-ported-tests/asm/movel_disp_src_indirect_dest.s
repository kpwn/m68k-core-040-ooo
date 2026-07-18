| movel_disp_src_indirect_dest.s -- MOVE.L (d16,An),(Am)
|
| Covers the ROM frontier instruction:
|   4080e9d0: 22ae 0008  move.l 8(A6),(A1)

    .text
    .org 0

_start:
    lea     0x00108000, %a6
    lea     0x00108100, %a1

    move.l  #0x80000001, 8(%a6)
    move.l  #0x11223344, (%a1)
    .word   0x22ae, 0x0008       | move.l 8(%a6),(%a1)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a1, %d0
    cmp.l   #0x00108100, %d0
    bne     _fail
    move.l  (%a1), %d1
    cmp.l   #0x80000001, %d1
    bne     _fail

    move.l  #0x00000000, 8(%a6)
    move.l  #0xa5a5a5a5, (%a1)
    .word   0x22ae, 0x0008       | move.l 8(%a6),(%a1)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  %a1, %d2
    cmp.l   #0x00108100, %d2
    bne     _fail
    move.l  (%a1), %d3
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
