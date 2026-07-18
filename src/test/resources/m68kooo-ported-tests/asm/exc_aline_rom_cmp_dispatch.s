| exc_aline_rom_cmp_dispatch.s -- A-line handler compares stacked opword
|
| Covers the Q700 ROM Toolbox dispatch sequence:
|   movea.l 10(sp),a2
|   move.w  (a2)+,d2
|   cmpi.w  #0xa800,d2
|   bcs     ...
|
| For opword 0xa051, CMPI.W computes 0xa051 - 0xa800, so C must be set
| and BCS must take the low A-line path.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000028
_aline_site:
    .short  0xa051

_fallthrough:
    move.l  #0xDEAD0000, %d7
    bra     _fail

_handler:
    move.l  %a2, -(%sp)
    move.l  %d2, -(%sp)
    movea.l 10(%sp), %a2
    cmp.l   #_aline_site, %a2
    bne     _fail_pc

    move.w  (%a2)+, %d2
    cmp.w   #0xa051, %d2
    bne     _fail_opword

    cmpi.w  #0xa800, %d2
    bcs     _pass

_fail_cmp_branch:
    move.l  #0xDEAD0003, %d7
    bra     _fail

_fail_pc:
    move.l  #0xDEAD0001, %d7
    bra     _fail

_fail_opword:
    move.l  #0xDEAD0002, %d7
    bra     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
