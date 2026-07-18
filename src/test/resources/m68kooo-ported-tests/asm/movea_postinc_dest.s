| movea_postinc_dest.s -- MOVEA.L (An)+,Am address-register destinations
|
| Exercises the postincrement source + address-register destination
| writeback path with final register-state checks:
|   - source A0 increments by 4 while A4 receives the loaded long
|   - source A1 increments by 4 while A5 receives the loaded long
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00101000, %a0
    lea     0x00102000, %a1

    move.l  #0x11223344, (%a0)
    move.l  #0x55667788, (%a1)

    movea.l (%a0)+, %a4
    cmpa.l  #0x00101004, %a0
    bne     _fail
    cmpa.l  #0x11223344, %a4
    bne     _fail

    movea.l (%a1)+, %a5
    cmpa.l  #0x00102004, %a1
    bne     _fail
    cmpa.l  #0x55667788, %a5
    bne     _fail

_pass:
    lea     0xFFFF0000, %a3
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a3)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a3
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a3)
    bra     _fail
