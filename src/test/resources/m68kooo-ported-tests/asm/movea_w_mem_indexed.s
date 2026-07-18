| movea_w_mem_indexed.s -- MOVEA.W memory-source and indexed forms
|
| Covers the current Q700 ROM gap classes for MOVEA.W:
|   - (An)
|   - -(An)
|   - (d16,An)
|   - (xxx).W / (xxx).L
|   - (d8,An,Xn) brief indexed
|   - (d8,PC,Xn) brief indexed
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | (An) source: sign-extend the loaded word, preserve CCR.
    lea     0x00016000, %a0
    move.w  #0x8001, (%a0)
    moveq   #5, %d6
    cmp.l   %d6, %d6
    movea.w (%a0), %a1
    bne     _fail
    cmpa.l  #0xffff8001, %a1
    bne     _fail

    | -(An) source: predecrement by 2, then sign-extend.
    lea     0x00017002, %a2
    move.w  #0x8002, -2(%a2)
    moveq   #6, %d6
    cmp.l   %d6, %d6
    movea.w -(%a2), %a3
    bne     _fail
    cmpa.l  #0x00017000, %a2
    bne     _fail
    cmpa.l  #0xffff8002, %a3
    bne     _fail

    | (d16,An) source: displacement is folded into the address.
    lea     0x00018000, %a4
    move.w  #0x8003, 2(%a4)
    moveq   #7, %d6
    cmp.l   %d6, %d6
    movea.w 2(%a4), %a5
    bne     _fail
    cmpa.l  #0xffff8003, %a5
    bne     _fail

    | Absolute short and long sources.
    move.w  #0x8004, 0x00005000
    move.w  #0x8005, 0x00115000

    moveq   #8, %d6
    cmp.l   %d6, %d6
    movea.w 0x00005000, %a0
    bne     _fail
    cmpa.l  #0xffff8004, %a0
    bne     _fail

    moveq   #9, %d6
    cmp.l   %d6, %d6
    movea.w 0x00115000, %a1
    bne     _fail
    cmpa.l  #0xffff8005, %a1
    bne     _fail

    | Brief indexed (d8,An,Xn) source with a scaled word index.
    lea     0x00019000, %a6
    moveq   #2, %d0
    move.w  #0x8006, 4(%a6)
    moveq   #10, %d6
    cmp.l   %d6, %d6
    movea.w (0,%a6,%d0.w*2), %a2
    bne     _fail
    cmpa.l  #0xffff8006, %a2
    bne     _fail

    | Brief indexed (d8,PC,Xn) source with the same scale shape.
    moveq   #2, %d3
    moveq   #11, %d6
    cmp.l   %d6, %d6
_pc_shape:
    .word   0x367b, 0x3200        | movea.w (0,PC,D3.W*2),A3
    .word   0x6002                | bra.s _pc_check
_pc_table:
    .word   0x8007

_pc_check:
    bne     _fail
    cmpa.l  #0xffff8007, %a3
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
