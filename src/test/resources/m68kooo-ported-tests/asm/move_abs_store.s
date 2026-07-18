| move_abs_store.s -- MOVE.{B,W,L} direct register source to absolute memory
|
| Covers the Q700 ROM frontier:
|   40800126: 31c2 0b22  move.w D2,$0b22.W

    .text
    .org 0

_start:
    lea     0x00001200, %a0

    | MOVE.W Dn,(xxx).W writes the low word and sets NZVC from Dn.W.
    move.l  #0xffffffff, (%a0)
    move.l  #0x12348001, %d2
    .word   0x31c2, 0x1202      | move.w %d2,0x1202.W
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a0), %d0
    cmp.l   #0xffff8001, %d0
    bne     _fail

    | MOVE.W An,(xxx).W uses An's low word and positive flags.
    move.l  #0xffffffff, (%a0)
    lea     0x00005678, %a3
    .word   0x31cb, 0x1200      | move.w %a3,0x1200.W
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a0), %d0
    cmp.l   #0x5678ffff, %d0
    bne     _fail

    | MOVE.W Dn,(xxx).L also handles zero-result flags.
    lea     0x00001204, %a1
    move.l  #0xffffffff, (%a1)
    moveq   #0, %d4
    .word   0x33c4, 0x0000, 0x1204  | move.w %d4,0x00001204.L
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a1), %d0
    cmp.l   #0x0000ffff, %d0
    bne     _fail

    | MOVE.B Dn,(xxx).W and MOVE.B Dn,(xxx).L keep byte lane steering.
    lea     0x00001208, %a2
    move.l  #0xffffffff, (%a2)
    move.l  #0x00000080, %d3
    .word   0x11c3, 0x1209      | move.b %d3,0x1209.W
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a2), %d0
    cmp.l   #0xff80ffff, %d0
    bne     _fail

    moveq   #0, %d3
    .word   0x13c3, 0x0000, 0x120a  | move.b %d3,0x0000120a.L
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a2), %d0
    cmp.l   #0xff8000ff, %d0
    bne     _fail

    | Existing MOVE.L absolute-store forms stay covered with the same test.
    lea     0x00001210, %a4
    move.l  #0x2468ace0, %d5
    .word   0x21c5, 0x1210      | move.l %d5,0x1210.W
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a4), %d0
    cmp.l   #0x2468ace0, %d0
    bne     _fail

    lea     0x00123456, %a5
    .word   0x23cd, 0x0000, 0x1214  | move.l %a5,0x00001214.L
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    lea     0x00001214, %a4
    move.l  (%a4), %d0
    cmp.l   #0x00123456, %d0
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
