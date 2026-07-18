| jsr_pc_indexed.s -- JSR (d8,PC,Xn.{W,L}) brief forms
|
| Covers the Q700 ROM shape:
|   4ebb e8f8    jsr (-8,PC,A6.L)
|
| Also covers the word-index sibling through D3.W.  Each call verifies
| that RTS returned to the instruction after the extension word and that
| the return address was pushed at the old SP-4.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | Exact ROM extension shape: target = (PC_ext - 8) + A6.L.
    move.l  #(_sub_long - _jsr_long + 6), %a6
    move.l  #0, %d0
_jsr_long:
    .word   0x4ebb, 0xe8f8
_after_long:
    cmp.l   #0x11112222, %d0
    bne     _fail
    cmpa.l  #0x00010000, %a7
    bne     _fail
    move.l  -4(%a7), %d1
    cmp.l   #_after_long, %d1
    bne     _fail

    | Word-index sibling: target = (PC_ext + 2) + D3.W.
    move.w  #(_sub_word - (_jsr_word + 4)), %d3
    move.l  #0, %d2
_jsr_word:
    .word   0x4ebb, 0x3002
_after_word:
    cmp.l   #0x33334444, %d2
    bne     _fail
    cmpa.l  #0x00010000, %a7
    bne     _fail
    move.l  -4(%a7), %d4
    cmp.l   #_after_word, %d4
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d5
    move.l  %d5, (%a0)
_halt:
    bra     _halt

_sub_long:
    move.l  #0x11112222, %d0
    rts

_sub_word:
    move.l  #0x33334444, %d2
    rts

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d5
    move.l  %d5, (%a0)
_halt_fail:
    bra     _halt_fail
