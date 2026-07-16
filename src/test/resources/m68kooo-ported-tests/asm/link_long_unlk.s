| link_long_unlk.s -- LINK.L An,#disp32 and existing UNLK restore
|
| Covers the Q700 ROM frontier:
|   40881482: 480c fffc ff62  link.l A4,#-196766
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Exact ROM long-displacement form.  LINK.L pushes old A4 at
    | -(A7), copies that new SP to A4, then applies the 32-bit frame
    | displacement to A7.
    lea     0x00180000, %a7
    move.l  #0x12345678, %a4
    .word   0x480c, 0xfffc, 0xff62  | link.l %a4,#-196766
    cmpa.l  #0x0017fffc, %a4
    bne     _fail
    cmpa.l  #0x0014ff5e, %a7
    bne     _fail
    move.l  0x0017fffc, %d0
    cmp.l   #0x12345678, %d0
    bne     _fail

    | Existing UNLK should unwind the long-displacement frame using A4.
    unlk    %a4
    cmpa.l  #0x00180000, %a7
    bne     _fail
    cmpa.l  #0x12345678, %a4
    bne     _fail

    | Small positive sibling for another address register.
    lea     0x00180100, %a7
    move.l  #0xa5a5f00d, %a2
    link.l  %a2, #12
    cmpa.l  #0x001800fc, %a2
    bne     _fail
    cmpa.l  #0x00180108, %a7
    bne     _fail
    move.l  0x001800fc, %d0
    cmp.l   #0xa5a5f00d, %d0
    bne     _fail
    unlk    %a2
    cmpa.l  #0x00180100, %a7
    bne     _fail
    cmpa.l  #0xa5a5f00d, %a2
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
    bra     _pass

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
    bra     _fail
