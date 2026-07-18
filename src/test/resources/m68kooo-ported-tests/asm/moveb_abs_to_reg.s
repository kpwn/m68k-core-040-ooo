| moveb_abs_to_reg.s -- MOVE.B (xxx).{W,L},Dn absolute-source forms.
|
| Covers the Q700 ROM frontier:
|   40804254: 1038 0260  move.b 0x0260.W,D0
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | MOVE.B (xxx).W,D0 preserves D0[31:8] and sets N from the byte.
    lea     0x00001200, %a0
    move.l  #0x80ffffff, (%a0)
    move.l  #0x11223344, %d0
    .word   0x1038, 0x1200      | move.b 0x1200.W,%d0
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x11223380, %d0
    bne     _fail

    | MOVE.B (xxx).L,D1 preserves D1[31:8] and sets Z from a zero byte.
    lea     0x00101204, %a1
    move.l  #0x00ffffff, (%a1)
    move.l  #0xaabbccdd, %d1
    .word   0x1239, 0x0010, 0x1204  | move.b 0x00101204.L,%d1
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0xaabbcc00, %d1
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
_fail_halt:
    bra     _fail_halt
