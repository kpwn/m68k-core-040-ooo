| move_predec_src_to_reg.s -- MOVE.{B,W} -(An),Dn source predecrement.

    .text
    .org 0

_start:
    | Byte predecrement through a normal address register steps by one.
    lea     0x00108110, %a1
    move.l  #0x80ffffff, (%a1)
    lea     1(%a1), %a1
    move.l  #0x11223344, %d0
    .word   0x1021              | move.b -(%a1),%d0
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x11223380, %d0
    bne     _fail
    move.l  %a1, %d1
    cmp.l   #0x00108110, %d1
    bne     _fail

    | Byte predecrement through A7 steps by two.
    lea     0x00108120, %a7
    move.l  #0x00000000, (%a7)
    lea     2(%a7), %a7
    move.l  #0xaabbccdd, %d2
    .word   0x1427              | move.b -(%a7),%d2
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0xaabbcc00, %d2
    bne     _fail
    move.l  %a7, %d3
    cmp.l   #0x00108120, %d3
    bne     _fail

    | Word predecrement steps by two and merges the low word into D4.
    lea     0x00108130, %a2
    move.l  #0x8001ffff, (%a2)
    lea     2(%a2), %a2
    move.l  #0x55667788, %d4
    .word   0x3822              | move.w -(%a2),%d4
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x55668001, %d4
    bne     _fail
    move.l  %a2, %d5
    cmp.l   #0x00108130, %d5
    bne     _fail

    | Word predecrement through A7 uses the normal two-byte word step.
    lea     0x00108140, %a7
    move.l  #0x7fff0000, (%a7)
    lea     2(%a7), %a7
    move.l  #0xdeadbeef, %d6
    .word   0x3c27              | move.w -(%a7),%d6
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0xdead7fff, %d6
    bne     _fail
    move.l  %a7, %d7
    cmp.l   #0x00108140, %d7
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
