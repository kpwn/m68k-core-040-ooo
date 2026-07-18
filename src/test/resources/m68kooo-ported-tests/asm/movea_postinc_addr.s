| movea_postinc_addr.s -- MOVEA.{W,L} (An)+,Am
|
| Q700 memory-sizing code reaches:
|   4084bb8e: 205f    movea.l (%a7)+,%a0
|   40800922: 365a    movea.w (%a2)+,%a3
|
| The ROM uses this to pop longword entries from a ROM-resident table.
| The first table entry can be 0xffffffff; the following ADDQ/BEQ must
| see that sentinel and branch instead of falling into memory probing.

    .text
    .org 0

_start:
    lea     _table,%a7

_rom_shape:
    .word   0x205f                  | movea.l (%a7)+,%a0
    cmpa.l  #0xffffffff,%a0
    bne     _fail
    cmpa.l  #(_table + 4),%a7
    bne     _fail

    move.l  %a0,%d2
    addq.l  #1,%d2
    bne     _fail

    .word   0x205f                  | movea.l (%a7)+,%a0
    cmpa.l  #0x00123456,%a0
    bne     _fail
    cmpa.l  #(_table + 8),%a7
    bne     _fail

    | MOVEA.W (An)+ sign-extends the word and increments by 2.
    lea     0x00103000,%a2
    move.l  #0x80011234,(%a2)
    .word   0x365a                  | movea.w (%a2)+,%a3
    cmpa.l  #0x00103002,%a2
    bne     _fail
    cmpa.l  #0xffff8001,%a3
    bne     _fail

    | Positive-word case; MOVEA must preserve CCR.
    lea     0x00103004,%a2
    move.l  #0x1234ffff,(%a2)
    moveq   #7,%d0
    moveq   #7,%d1
    cmp.l   %d1,%d0                 | Z=1
    .word   0x365a                  | movea.w (%a2)+,%a3
    bne     _fail
    cmpa.l  #0x00001234,%a3
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
_table:
    .long   0xffffffff
    .long   0x00123456
