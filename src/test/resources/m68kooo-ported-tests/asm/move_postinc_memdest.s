| move_postinc_memdest.s -- MOVE.{B,W,L} (An)+,(Am)
|
| Covers the source-postincrement to plain memory-destination MOVE family.
| The long case is the Q700 ROM temporary-vector restore blocker:
|   2a9f  move.l (%a7)+,(%a5)

    .text
    .org 0

_start:
    | Byte sibling: A7 byte stack special case is covered elsewhere, but keep
    | this family test honest on CCRs, destination store, and source writeback.
    lea     0x00106000, %a3
    lea     0x00106100, %a2
    move.l  #0x7f000000, (%a3)
    move.l  #0xaaaaaaaa, (%a2)
    .word   0x149b              | move.b (%a3)+,(%a2)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00106001, %a3
    bne     _fail
    move.l  (%a2), %d0
    cmp.l   #0x7faaaaaa, %d0
    bne     _fail

    | Word sibling: copied word is negative, so N must set and V/C clear.
    lea     0x00106200, %a6
    lea     0x00106300, %a4
    move.l  #0x80015555, (%a6)
    move.l  #0xaaaaaaaa, (%a4)
    .word   0x389e              | move.w (%a6)+,(%a4)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00106202, %a6
    bne     _fail
    move.l  (%a4), %d1
    cmp.l   #0x8001aaaa, %d1
    bne     _fail

    | Exact ROM long opcode.
    lea     0x00106400, %a7
    lea     0x00106500, %a5
    move.l  #0x11223344, (%a7)
    move.l  #0xaaaaaaaa, (%a5)
    .word   0x2a9f              | move.l (%a7)+,(%a5)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00106404, %a7
    bne     _fail
    move.l  (%a5), %d2
    cmp.l   #0x11223344, %d2
    bne     _fail

    | Long zero value sets Z and still advances the source.
    move.l  #0x00000000, (%a7)
    .word   0x2a9f              | move.l (%a7)+,(%a5)
    bmi     _fail
    bne     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00106408, %a7
    bne     _fail
    move.l  (%a5), %d3
    cmp.l   #0x00000000, %d3
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
