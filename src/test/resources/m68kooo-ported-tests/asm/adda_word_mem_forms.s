| adda_word_mem_forms.s -- ADDA.W memory-source forms
|
| Covers the current Q700 ROM frontier:
|   40800834: d0d4  adda.w (A4),A0
| and the immediately adjacent postincrement sibling:
|   40800840: d0dc  adda.w (A4)+,A0
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Exact ROM indirect form, positive word, CCR preserved.
    lea     0x00109000, %a4
    move.l  #0x00000012, %d1
    move.w  %d1, (%a4)
    lea     0x00001000, %a0
    move.l  #0x00000000, %d0
    tst.l   %d0
    .word   0xd0d4              | adda.w (%a4),%a0
    bne     _fail1a
    cmpa.l  #0x00001012, %a0
    bne     _fail1b
    cmpa.l  #0x00109000, %a4
    bne     _fail1c

    | Indirect form must sign-extend the fetched word.
    lea     0x00109010, %a4
    move.l  #0x0000fffe, %d1
    move.w  %d1, (%a4)
    lea     0x00001000, %a0
    move.l  #0x80000000, %d0
    tst.l   %d0
    .word   0xd0d4              | adda.w (%a4),%a0
    bpl     _fail2
    cmpa.l  #0x00000ffe, %a0
    bne     _fail2

    | Exact ROM postincrement form, source A4 advances by two bytes.
    lea     0x00109020, %a4
    move.l  #0x00000004, %d1
    move.w  %d1, (%a4)
    lea     0x00002000, %a0
    move.l  #0x00000000, %d0
    tst.l   %d0
    .word   0xd0dc              | adda.w (%a4)+,%a0
    bne     _fail3
    cmpa.l  #0x00002004, %a0
    bne     _fail3
    cmpa.l  #0x00109022, %a4
    bne     _fail3

    | Predecrement form reads after subtracting two bytes.
    lea     0x00109030, %a4
    move.l  #0x00000008, %d1
    move.w  %d1, (%a4)
    lea     0x00109032, %a4
    lea     0x00003000, %a0
    .word   0xd0e4              | adda.w -(%a4),%a0
    cmpa.l  #0x00003008, %a0
    bne     _fail4
    cmpa.l  #0x00109030, %a4
    bne     _fail4

    | Existing displaced-word shape stays covered in the same family test.
    lea     0x00109040, %a4
    move.l  #0x0000000a, %d1
    move.w  %d1, 6(%a4)
    lea     0x00004000, %a0
    .word   0xd0ec, 0x0006      | adda.w 6(%a4),%a0
    cmpa.l  #0x0000400a, %a0
    bne     _fail5

    | Absolute-short source.
    move.l  #0x0000000c, %d1
    .word   0x31c1, 0x0900      | move.w %d1,0x0900.W
    lea     0x00005000, %a0
    .word   0xd0f8, 0x0900      | adda.w 0x0900.W,%a0
    cmpa.l  #0x0000500c, %a0
    bne     _fail6

    | Absolute-long source with sign extension.
    move.l  #0x0000fff0, %d1
    .word   0x33c1, 0x0010, 0x9100  | move.w %d1,0x00109100.L
    lea     0x00006000, %a0
    .word   0xd0f9, 0x0010, 0x9100  | adda.w 0x00109100.L,%a0
    cmpa.l  #0x00005ff0, %a0
    bne     _fail7

    | PC-relative word source.
    lea     0x00007000, %a0
    adda.w  _pc_word(%pc), %a0
    cmpa.l  #0x00006ffe, %a0
    bne     _fail8

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a0)
    bra     _pass

_fail1a:
    move.l  #0xDEAD0001, %d1
    bra     _fail
_fail1b:
    move.l  #0xDEAD0011, %d1
    bra     _fail
_fail1c:
    move.l  #0xDEAD0021, %d1
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d1
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d1
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d1
    bra     _fail
_fail5:
    move.l  #0xDEAD0005, %d1
    bra     _fail
_fail6:
    move.l  #0xDEAD0006, %d1
    bra     _fail
_fail7:
    move.l  #0xDEAD0007, %d1
    bra     _fail
_fail8:
    move.l  #0xDEAD0008, %d1
    bra     _fail

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d1, (%a0)
    bra     _fail

    .align 2
_pc_word:
    .word   0xfffe
