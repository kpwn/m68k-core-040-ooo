| tst_abs_mem.s - TST absolute memory forms used by the Q700 ROM.
|
| Covers exact encodings for TST.{B,W,L} (xxx).W and TST.L (xxx).L.
| The ROM frontier at 0x408026a4 is:
|   4a38 0bff    tst.b 0x0bff.W
|
| PASS: 0xC0FFEE00 sentinel.
| FAIL: 0xDEADBEEF sentinel.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    lea     0x00000bff, %a0
    lea     0x00000c00, %a1

    move.b  #0x00, (%a0)
    .short  0x4a38, 0x0bff          | tst.b 0x0bff.W
    bne     _fail
    bmi     _fail

    move.b  #0x80, (%a0)
    .short  0x4a38, 0x0bff          | tst.b 0x0bff.W
    bpl     _fail
    beq     _fail

    move.w  #0x0000, (%a1)
    .short  0x4a78, 0x0c00          | tst.w 0x0c00.W
    bne     _fail
    bmi     _fail

    move.l  #0x80000000, 4(%a1)
    .short  0x4ab8, 0x0c04          | tst.l 0x0c04.W
    bpl     _fail
    beq     _fail

    move.l  #0x00000001, 4(%a1)
    .short  0x4ab9
    .long   0x00000c04              | tst.l 0x00000c04.L
    beq     _fail
    bmi     _fail

    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a2)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a2
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a2)
_halt_fail:
    bra     _halt_fail
