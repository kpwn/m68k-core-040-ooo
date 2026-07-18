| ori_word_reg.s -- ORI.W #imm,Dn ROM handler regression
|
| Q700 ROM exception-vector stubs begin with ORI.W #class,D7 before
| branching to the shared handler.  The decoder must treat word-sized
| ORI-to-Dn as a normal ALU op, preserve the high 16 bits of Dn, and set
| flags from the word result only.

    .text
    .org 0

_start:
    move.l  #0x123400f0, %d7
    ori.w   #0x0300, %d7
    cmp.l   #0x123403f0, %d7
    bne     _fail

    move.l  #0xaaaa0000, %d7
    ori.w   #0x0000, %d7
    bne     _fail
    bmi     _fail
    cmp.l   #0xaaaa0000, %d7
    bne     _fail

    move.l  #0x12340000, %d7
    ori.w   #0x8000, %d7
    bpl     _fail
    cmp.l   #0x12348000, %d7
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
