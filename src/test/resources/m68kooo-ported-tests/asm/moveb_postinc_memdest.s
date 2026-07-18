| moveb_postinc_memdest.s — MOVE.B (An)+ to memory destinations
|
| Covers the Q700 RAM-resident logo path shapes:
|   - MOVE.B (An)+,(d16,Am)  opword 0x155b
|   - MOVE.B (An)+,(Am)      opword 0x149b
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    move.l  #0x803f0000, 0x00100080
    move.l  #0x00100080, %a3
    move.l  #0x00100100, %a2

    move.b  (%a3)+, 0x20(%a2)
    bpl     _fail
    cmpa.l  #0x00100081, %a3
    bne     _fail
    move.b  0x20(%a2), %d0
    and.l   #0xff, %d0
    cmp.l   #0x80, %d0
    bne     _fail

    move.b  (%a3)+, (%a2)
    bmi     _fail
    beq     _fail
    cmpa.l  #0x00100082, %a3
    bne     _fail
    move.b  (%a2), %d1
    and.l   #0xff, %d1
    cmp.l   #0x3f, %d1
    bne     _fail

_pass:
    lea     0xffff0000, %a0
    move.l  #0xc0ffee00, %d2
    move.l  %d2, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xffff0000, %a0
    move.l  #0xdeadbeef, %d2
    move.l  %d2, (%a0)
_halt_fail:
    bra     _halt_fail
