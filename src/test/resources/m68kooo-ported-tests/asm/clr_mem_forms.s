| clr_mem_forms.s -- CLR memory operands for ROM ASC setup
|
| Covers the post-checksum Q700 ROM shapes:
|   4080706e: 422b 0801    clr.b 0x0801(%a3)
|   4080709a: 4218         clr.b (%a0)+
|   0000ffe6: 4274 4000    clr.w (0,%a4,%d4.w)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00103000, %a3
    move.l  #0x11223344, 0x20(%a3)
    move.l  #0x55667788, 0x24(%a3)
    move.l  #0x99aabbcc, 0x28(%a3)

    | ROM byte displacement: clear one byte, preserve its neighbours,
    | and expose CLR flags before any later instruction overwrites them.
    clr.b   0x21(%a3)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  0x20(%a3), %d0
    cmp.l   #0x11003344, %d0
    bne     _fail

    | Word and long displaced forms use the same crack, with size carried
    | into the zero store.
    clr.w   0x24(%a3)
    bne     _fail
    bmi     _fail
    move.l  0x24(%a3), %d1
    cmp.l   #0x00007788, %d1
    bne     _fail

    clr.l   0x28(%a3)
    bne     _fail
    bmi     _fail
    move.l  0x28(%a3), %d2
    cmp.l   #0x00000000, %d2
    bne     _fail

    | Plain indirect memory form.
    lea     0x00103100, %a2
    move.l  #0xaabbccdd, (%a2)
    clr.w   (%a2)
    bne     _fail
    bmi     _fail
    move.l  (%a2), %d3
    cmp.l   #0x0000ccdd, %d3
    bne     _fail

    | Byte postincrement for a normal address register increments by one.
    lea     0x00103200, %a0
    move.l  #0xee123456, (%a0)
    clr.b   (%a0)+
    bne     _fail
    bmi     _fail
    cmpa.l  #0x00103201, %a0
    bne     _fail
    lea     0x00103200, %a1
    move.l  (%a1), %d4
    cmp.l   #0x00123456, %d4
    bne     _fail

    | A7 byte postincrement has the 68k stack-byte rule: advance by two.
    lea     0x00103300, %a7
    move.l  #0xddabcdef, (%a7)
    clr.b   (%a7)+
    bne     _fail
    bmi     _fail
    cmpa.l  #0x00103302, %a7
    bne     _fail
    lea     0x00103300, %a1
    move.l  (%a1), %d5
    cmp.l   #0x00abcdef, %d5
    bne     _fail

    | Brief indexed destinations.  The word case is the exact ROM-frontier
    | opcode that previously decoded as illegal.
    lea     0x00103400, %a4
    move.l  #0x11223344, 4(%a4)
    moveq   #4, %d4
    .word   0x4274, 0x4000        | clr.w (0,%a4,%d4.w)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  4(%a4), %d0
    cmp.l   #0x00003344, %d0
    bne     _fail

    | Byte and long siblings use the same indexed address crack with the
    | operand size carried into the zero store.
    move.l  #0x55667788, 12(%a4)
    moveq   #8, %d6
    clr.b   4(%a4,%d6.l)
    bne     _fail
    bmi     _fail
    move.l  12(%a4), %d1
    cmp.l   #0x00667788, %d1
    bne     _fail

    move.l  #0x99aabbcc, 20(%a4)
    moveq   #20, %d7
    clr.l   0(%a4,%d7.w)
    bne     _fail
    bmi     _fail
    move.l  20(%a4), %d2
    cmp.l   #0x00000000, %d2
    bne     _fail

    | Absolute destinations.  The byte absolute-short form is the Q700
    | ROM frontier at 0x40803e10.
    lea     0x00000cb0, %a0
    move.l  #0xaabbccdd, (%a0)
    .word   0x4238, 0x0cb0        | clr.b 0x0cb0.w
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.l  (%a0), %d0
    cmp.l   #0x00bbccdd, %d0
    bne     _fail

    lea     0x00103500, %a0
    move.l  #0x11223344, (%a0)
    .word   0x4239, 0x0010, 0x3500 | clr.b 0x00103500.l
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.l  (%a0), %d1
    cmp.l   #0x00223344, %d1
    bne     _fail

    lea     0x00000cc0, %a0
    move.l  #0x55667788, (%a0)
    .word   0x4278, 0x0cc0        | clr.w 0x0cc0.w
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.l  (%a0), %d2
    cmp.l   #0x00007788, %d2
    bne     _fail

    lea     0x00103510, %a0
    move.l  #0x99aabbcc, (%a0)
    .word   0x4279, 0x0010, 0x3510 | clr.w 0x00103510.l
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.l  (%a0), %d3
    cmp.l   #0x0000bbcc, %d3
    bne     _fail

    lea     0x00000cd0, %a0
    move.l  #0xdeadbeef, (%a0)
    .word   0x42b8, 0x0cd0        | clr.l 0x0cd0.w
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.l  (%a0), %d4
    bne     _fail

    lea     0x00103520, %a0
    move.l  #0xcafebabe, (%a0)
    .word   0x42b9, 0x0010, 0x3520 | clr.l 0x00103520.l
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    moveq   #32, %d7
1:  subq.l  #1, %d7
    bne     1b
    move.l  (%a0), %d5
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
