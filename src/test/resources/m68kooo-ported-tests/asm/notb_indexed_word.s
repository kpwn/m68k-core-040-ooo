| notb_indexed_word.s -- NOT.B register and indexed memory forms
|
| Covers the Q700 memory-sizing probe shapes:
|   4601        not.b D1
|   4630 2000   not.b (0,A0,D2.W)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Register byte NOT preserves upper 24 bits and updates NZVC only.
    move.l  #0x123456a5,%d1
_rom_shape_reg:
    .word   0x4601                  | not.b D1
    bmi     _fail
    beq     _fail
    bcs     _fail
    cmp.l   #0x1234565a,%d1
    bne     _fail

    | Memory word-index form.  The high half of D2 must not participate
    | in the EA calculation.
    move.l  #0x00100000,%a0
    move.l  #0x112233a5,%d0
    move.l  %d0,(%a0)
    move.l  #0x00010003,%d2
_rom_shape_mem_positive:
    .word   0x4630, 0x2000          | not.b (0,A0,D2.W)
    bmi     _fail
    beq     _fail
    bcs     _fail
    move.l  (%a0),%d0
    cmp.l   #0x1122335a,%d0
    bne     _fail

    | Negative word index: D2.W = -1, so A0+4-1 hits the same byte.
    move.l  #0x00100004,%a0
    move.l  #0x1234ffff,%d2
_rom_shape_mem_negative:
    .word   0x4630, 0x2000          | not.b (0,A0,D2.W)
    move.l  #0x00100000,%a1
    move.l  (%a1),%d0
    cmp.l   #0x112233a5,%d0
    bne     _fail

    | Regression for the long-index path in the same decoder arm.
    move.l  #0x00100000,%a2
    moveq   #3,%d2
    .word   0x4632, 0x2800          | not.b (0,A2,D2.L)
    move.l  (%a2),%d0
    cmp.l   #0x1122335a,%d0
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
