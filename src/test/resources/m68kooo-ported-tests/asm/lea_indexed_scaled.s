| lea_indexed_scaled.s -- LEA brief indexed word/scaled forms
|
| Covers the ROM memory-configuration form:
|   0x4084bd3a  4bf5 1400  lea (0,%a5,%d1.w*4),%a5
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | 1. Exact ROM shape: word index, scale x4, destination aliases base.
    move.l  #0x00100000, %a5
    move.l  #0x00000003, %d1
    moveq   #0, %d7
    tst.l   %d7
    lea     0(%a5, %d1.w*4), %a5
    bne     _fail1                 | LEA must preserve CCR
    cmpa.l  #0x0010000c, %a5
    bne     _fail1

    | 2. Word index sign-extension: D1.W=-2, scale x4, disp +0x20.
    move.l  #0x00200000, %a0
    move.l  #0x0000fffe, %d1
    lea     0x20(%a0, %d1.w*4), %a2
    cmpa.l  #0x00200018, %a2
    bne     _fail2

    | 3. Long index, scale x2.
    move.l  #0x00300000, %a0
    move.l  #0x00000007, %d2
    lea     4(%a0, %d2.l*2), %a3
    cmpa.l  #0x00300012, %a3
    bne     _fail3

    | 4. Address-register long index, scale x8, negative displacement.
    move.l  #0x00400000, %a0
    move.l  #0x00000005, %a4
    lea     -8(%a0, %a4.l*8), %a1
    cmpa.l  #0x00400020, %a1
    bne     _fail4

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d2
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d2
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d2
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d2
    bra     _fail

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d2, (%a0)
_halt_fail:
    bra     _halt_fail
