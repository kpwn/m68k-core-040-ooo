| movew_postinc_reg.s - MOVE.W (An)+,Dn
|
| Covers the ROM decode gap at 0x40847516:
|   3018    move.w (%a0)+,%d0
|
| Word MOVE to Dn is a partial-register write: Dn[15:0] is replaced,
| Dn[31:16] is preserved.  This regression checks the ROM-shaped
| postincrement path plus the adjacent (An) and (d16,An) load forms.

    .text
    .org 0

_start:
    lea     _data,%a0
    move.l  #0xCAFE0000,%d0

_rom_shape:
    .word   0x3018
    beq     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0xCAFE420D,%d0
    bne     _fail
    cmpi.w  #0x420d,%d0
    bne     _fail
    cmpa.l  #(_data + 2),%a0
    bne     _fail

    lea     _negative,%a1
    move.l  #0xFACE0000,%d1
    move.w  (%a1)+,%d1
    bpl     _fail
    beq     _fail
    cmp.l   #0xFACE8001,%d1
    bne     _fail
    cmpa.l  #(_negative + 2),%a1
    bne     _fail

    lea     _data,%a2
    move.l  #0x12340000,%d2
    move.w  (%a2),%d2
    cmp.l   #0x1234420D,%d2
    bne     _fail

    lea     _dispbase,%a3
    move.l  #0xABCD0000,%d3
    move.w  2(%a3),%d3
    cmp.l   #0xABCDBEEF,%d3
    bne     _fail

    move.l  #0xC0FFEE00,%d0
    lea     0xFFFF0000,%a1
    move.l  %d0,(%a1)
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF,%d0
    lea     0xFFFF0000,%a1
    move.l  %d0,(%a1)
_halt_fail:
    bra     _halt_fail

    .align 2
_data:
    .word   0x420d,0xbff3
_negative:
    .word   0x8001,0x1234
_dispbase:
    .word   0x1111,0xbeef
