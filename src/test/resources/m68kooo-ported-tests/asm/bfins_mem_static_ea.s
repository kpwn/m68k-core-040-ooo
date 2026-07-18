| bfins_mem_static_ea.s -- static BFINS into memory EA forms
|
| Covers the Q700 ROM frontier:
|   40805f12: efea 0104 0010  bfins D0,16(A2){4:4}
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Simple (An) sibling: replace byte lane at offset 8.
    lea     0x00117000, %a0
    move.l  #0xaabbccdd, (%a0)
    move.l  #0x0000005a, %d1
    .word   0xefd0, 0x1208          | bfins D1,(A0){8:8}
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a0), %d2
    cmp.l   #0xaa5accdd, %d2
    bne     _fail

    | Exact ROM shape: D0 low nibble goes into bits 27:24.
    lea     0x00117100, %a2
    move.l  #0x12345678, 16(%a2)
    move.l  #0x0000000a, %d0
    .word   0xefea, 0x0104, 0x0010  | bfins D0,16(A2){4:4}
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  16(%a2), %d2
    cmp.l   #0x1a345678, %d2
    bne     _fail

    | Zero insert clears the field and sets Z.
    move.l  #0xffffffff, 16(%a2)
    moveq   #0, %d0
    .word   0xefea, 0x0104, 0x0010  | bfins D0,16(A2){4:4}
    bne     _fail
    bmi     _fail
    move.l  16(%a2), %d2
    cmp.l   #0xf0ffffff, %d2
    bne     _fail

_pass:
    lea     0xffff0000, %a0
    move.l  #0xc0ffee00, %d0
    move.l  %d0, (%a0)
    bra     _pass

_fail:
    lea     0xffff0000, %a0
    move.l  #0xdead0001, %d0
    move.l  %d0, (%a0)
    bra     _fail
