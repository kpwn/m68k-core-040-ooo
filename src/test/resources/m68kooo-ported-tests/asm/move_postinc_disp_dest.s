| move_postinc_disp_dest.s -- MOVE.{W,L} (An)+,(d16,Am)
|
| Covers the Q700 ROM frontier:
|   215f 0004  move.l (%a7)+,4(%a0)

    .text
    .org 0

_start:
    | Exact ROM long opcode: positive long, source A7 advances by 4.
    lea     0x00108600, %a7
    lea     0x00108680, %a0
    move.l  #0x11223344, (%a7)
    move.l  #0xaaaaaaaa, 4(%a0)
    .word   0x215f, 0x0004
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00108604, %a7
    bne     _fail
    move.l  4(%a0), %d0
    cmp.l   #0x11223344, %d0
    bne     _fail

    | Word sibling with negative data and sign-extended destination offset.
    lea     0x00108700, %a5
    lea     0x00108780, %a2
    move.l  #0x80015555, (%a5)
    move.l  #0xaabbccdd, -4(%a2)
    .word   0x355d, 0xfffe      | move.w (A5)+,-2(A2)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00108702, %a5
    bne     _fail
    move.l  -4(%a2), %d1
    cmp.l   #0xaabb8001, %d1
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
