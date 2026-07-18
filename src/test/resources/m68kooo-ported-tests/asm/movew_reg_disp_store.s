| movew_reg_disp_store.s -- MOVE.W direct register source to (d16,An)
|
| Covers the Q700 ROM frame-status store:
|   3b40 0008  move.w %d0,8(%a5)

    .text
    .org 0

_start:
    lea     0x00102000, %a5

    move.l  #0xaaaa5555, 8(%a5)
    move.l  #0x00000003, %d0
    .word   0x3b40, 0x0008      | move.w %d0,8(%a5)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  8(%a5), %d2
    cmp.l   #0x00035555, %d2
    bne     _fail

    | Negative displacement and N flag from the moved word.
    move.l  #0x11223344, -4(%a5)
    move.l  #0x00008001, %d1
    .word   0x3b41, 0xfffe      | move.w %d1,-2(%a5)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  -4(%a5), %d3
    cmp.l   #0x11228001, %d3
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
