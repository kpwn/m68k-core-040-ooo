| cmpb_indexed_word.s -- CMP.B (d8,An,Xn.W),Dn
|
| Covers the Q700 memory-sizing probe shape:
|   b230 2000    cmp.b (0,A0,D2.W),D1
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | D2.L has non-zero upper bits.  The effective address must use only
    | sign-extended D2.W, not the full longword.
    lea     _bytes,%a0
    move.l  #0x00010003,%d2
    moveq   #0x5a,%d1
_rom_shape_positive:
    .word   0xb230, 0x2000          | cmp.b (0,A0,D2.W),D1
    bne     _fail

    | Negative word index: D2.W = -1, so A0+(-1) lands back on _bytes+3.
    lea     _bytes,%a0
    addq.l  #4,%a0
    move.l  #0x1234ffff,%d2
    moveq   #0x5a,%d1
_rom_shape_negative:
    .word   0xb230, 0x2000          | cmp.b (0,A0,D2.W),D1
    bne     _fail

    | Exercise the byte compare flags on the same decoded EA form.
    moveq   #0,%d1
    .word   0xb230, 0x2000          | 0x00 - 0x5a => N=1, C=1
    bpl     _fail
    bcc     _fail

    | Regression for the older ROM long-index case in the same decoder arm:
    |   b232 2800    cmp.b (0,A2,D2.L),D1
    lea     _bytes,%a2
    moveq   #3,%d2
    moveq   #0x5a,%d1
    .word   0xb232, 0x2800          | cmp.b (0,A2,D2.L),D1
    bne     _fail

_pass:
    lea     0xFFFF0000,%a1
    move.l  #0xC0FFEE00,%d0
    move.l  %d0,(%a1)
    bra     _pass

_fail:
    lea     0xFFFF0000,%a1
    move.l  #0xDEAD0001,%d0
    move.l  %d0,(%a1)
    bra     _fail

    .align 2
_bytes:
    .byte   0x10, 0x20, 0x30, 0x5a
