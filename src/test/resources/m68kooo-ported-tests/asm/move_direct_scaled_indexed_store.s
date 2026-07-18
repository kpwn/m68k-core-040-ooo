| move_direct_scaled_indexed_store.s -- MOVE direct source to scaled indexed dest
|
| Covers the Q700 ROM frontier:
|   40805f00: 238a 0400  move.l A2,(0,A1,D0.W*4)
|
| Keep byte/word/long siblings covered so brief indexed destination scale
| handling does not advance one opcode form at a time.

    .text
    .org 0

_start:
    | Byte data-source sibling: D2.B -> (A1 + D0.W*4).
    lea     0x00116000, %a1
    moveq   #3, %d0
    move.l  #0x00000080, %d2
    lea     0x0011600c, %a0
    move.b  #0x00, (%a0)

    .word   0x1382, 0x0400
    bpl     _fail1
    beq     _fail1
    bvs     _fail1
    bcs     _fail1
    move.b  (%a0), %d3
    cmp.b   #0x80, %d3
    bne     _fail1

    | Word address-register-source sibling: A2.W -> (A1 + D0.W*4).
    lea     0x00116100, %a1
    moveq   #4, %d0
    movea.l #0x00118001, %a2
    lea     0x00116110, %a0
    move.w  #0x0000, (%a0)

    .word   0x338a, 0x0400
    bpl     _fail2
    beq     _fail2
    bvs     _fail2
    bcs     _fail2
    move.w  (%a0), %d3
    cmp.w   #0x8001, %d3
    bne     _fail2

    | Exact ROM long shape: A2 -> (A1 + D0.W*4).
    lea     0x00116200, %a1
    moveq   #5, %d0
    movea.l #0x408f0001, %a2
    lea     0x00116214, %a0
    move.l  #0x00000000, (%a0)

    .word   0x238a, 0x0400
    bmi     _fail3
    beq     _fail3
    bvs     _fail3
    bcs     _fail3
    move.l  (%a0), %d3
    cmp.l   #0x408f0001, %d3
    bne     _fail3

_pass:
    lea     0xffff0000, %a0
    move.l  #0xc0ffee00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xdead0001, %d7
    bra     _fail
_fail2:
    move.l  #0xdead0002, %d7
    bra     _fail
_fail3:
    move.l  #0xdead0003, %d7

_fail:
    lea     0xffff0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
