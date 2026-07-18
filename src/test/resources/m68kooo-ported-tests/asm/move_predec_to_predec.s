| move_predec_to_predec.s -- MOVE.{B,W,L} -(An),-(Am)
|
| Covers the ROM frontier:
|   4080caf0: 1320  move.b -(A0),-(A1)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Exact byte frontier.  Both address registers predecrement by one.
    lea     0x00107b01, %a0
    lea     0x00107c01, %a1
    move.l  #0x80bbccdd, -1(%a0)
    move.l  #0x11223344, -1(%a1)
    .word   0x1320              | move.b -(A0),-(A1)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00107b00, %a0
    bne     _fail
    cmpa.l  #0x00107c00, %a1
    bne     _fail
    move.l  (%a1), %d0
    cmp.l   #0x80223344, %d0
    bne     _fail

    | Word sibling.
    lea     0x00107d02, %a2
    lea     0x00107e02, %a3
    move.l  #0x8001ccdd, -2(%a2)
    move.l  #0x11223344, -2(%a3)
    .word   0x3722              | move.w -(A2),-(A3)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00107d00, %a2
    bne     _fail
    cmpa.l  #0x00107e00, %a3
    bne     _fail
    move.l  (%a3), %d0
    cmp.l   #0x80013344, %d0
    bne     _fail

    | Long sibling.
    lea     0x00107f04, %a4
    lea     0x00108004, %a5
    move.l  #0x01020304, -4(%a4)
    move.l  #0xaaaaaaaa, -4(%a5)
    .word   0x2b24              | move.l -(A4),-(A5)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00107f00, %a4
    bne     _fail
    cmpa.l  #0x00108000, %a5
    bne     _fail
    move.l  (%a5), %d0
    cmp.l   #0x01020304, %d0
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
