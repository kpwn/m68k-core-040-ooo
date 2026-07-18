| clr_postinc_queue_init.s -- ROM queue-init shape used at 4080999e
|
| Covers:
|   clr.w   (A1)+
|   clr.l   (A1)+
|   clr.l   (A1)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00103802, %a2
    move.l  %a2, %a1
    move.l  #0x11112222, -4(%a1)
    move.l  #0xffffffff, (%a1)
    move.l  #0xffffffff, 4(%a1)
    move.l  #0xffffffff, 8(%a1)
    move.l  #0x33334444, 12(%a1)

    clr.w   (%a1)+
    clr.l   (%a1)+
    clr.l   (%a1)

    cmpa.l  #0x00103808, %a1
    bne     _fail
    cmp.l   #0x11112222, -4(%a2)
    bne     _fail
    cmp.w   #0x0000, (%a2)
    bne     _fail
    cmp.l   #0x00000000, 2(%a2)
    bne     _fail
    cmp.l   #0x00000000, 6(%a2)
    bne     _fail
    cmp.w   #0xffff, 10(%a2)
    bne     _fail
    cmp.l   #0x33334444, 12(%a2)
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
