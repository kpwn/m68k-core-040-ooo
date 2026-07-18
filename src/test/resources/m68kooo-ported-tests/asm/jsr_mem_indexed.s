| jsr_mem_indexed.s -- JSR mode-6 indexed and full memory-indirect forms
|
| Covers the Q700 timer-delay frontier:
|   40809a04: 4eb0 25a1 0400  jsr @($400,D2.W*4)@(0)
|
| Also covers the adjacent null-bd form at 40809a22 and a brief An-indexed
| sibling.  Each call verifies RTS return and the pushed return PC.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | Brief mode-6 sibling: target = A0 + D2.W*4 + 4.
    lea     _sub_brief - 16, %a0
    moveq   #3, %d2
    moveq   #0, %d0
_jsr_brief:
    .word   0x4eb0, 0x2404
_after_brief:
    cmp.l   #0x11112222, %d0
    bne     _fail1
    cmpa.l  #0x00010000, %a7
    bne     _fail1
    move.l  -4(%a7), %d1
    cmp.l   #_after_brief, %d1
    bne     _fail1

    | Exact frontier: base suppressed, D2.W*4, bd.W=$0400, null od.
    lea     0x0000040c, %a1
    move.l  #_sub_full_bd, (%a1)
    moveq   #3, %d2
    moveq   #0, %d0
_jsr_full_bd:
    .word   0x4eb0, 0x25a1, 0x0400
_after_full_bd:
    cmp.l   #0x33334444, %d0
    bne     _fail2
    cmpa.l  #0x00010000, %a7
    bne     _fail2
    move.l  -4(%a7), %d1
    cmp.l   #_after_full_bd, %d1
    bne     _fail2

    | Adjacent ROM shape: base suppressed, D2.W*4, null bd, null od.
    lea     0x00000800, %a1
    move.l  #_sub_full_null, (%a1)
    move.l  #0x00000200, %d2
    moveq   #0, %d0
_jsr_full_null:
    .word   0x4eb0, 0x2591
_after_full_null:
    cmp.l   #0x55556666, %d0
    bne     _fail3
    cmpa.l  #0x00010000, %a7
    bne     _fail3
    move.l  -4(%a7), %d1
    cmp.l   #_after_full_null, %d1
    bne     _fail3

    | Base-present plus word outer displacement.
    lea     0x00001200, %a3
    lea     0x0000122c, %a1
    move.l  #_sub_full_od + 4, (%a1)
    moveq   #3, %d2
    moveq   #0, %d0
_jsr_full_od:
    .word   0x4eb3, 0x2522, 0x0020, 0xfffc
_after_full_od:
    cmp.l   #0x77778888, %d0
    bne     _fail4
    cmpa.l  #0x00010000, %a7
    bne     _fail4
    move.l  -4(%a7), %d1
    cmp.l   #_after_full_od, %d1
    bne     _fail4

    | New ROM frontier: base and index suppressed, bd.W=$06f4, null od.
    lea     0x000006f4, %a1
    move.l  #_sub_full_suppressed, (%a1)
    moveq   #0, %d0
_jsr_full_suppressed:
    .word   0x4eb0, 0x81e1, 0x06f4
_after_full_suppressed:
    cmp.l   #0x9999aaaa, %d0
    bne     _fail5
    cmpa.l  #0x00010000, %a7
    bne     _fail5
    move.l  -4(%a7), %d1
    cmp.l   #_after_full_suppressed, %d1
    bne     _fail5

    | Base-present/index-suppressed sibling with word outer displacement.
    lea     0x00001600, %a4
    lea     0x00001620, %a1
    move.l  #_sub_full_suppressed_od + 8, (%a1)
    moveq   #0, %d0
_jsr_full_suppressed_od:
    .word   0x4eb4, 0x0162, 0x0020, 0xfff8
_after_full_suppressed_od:
    cmp.l   #0xbbbbcccc, %d0
    bne     _fail6
    cmpa.l  #0x00010000, %a7
    bne     _fail6
    move.l  -4(%a7), %d1
    cmp.l   #_after_full_suppressed_od, %d1
    bne     _fail6

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_sub_brief:
    move.l  #0x11112222, %d0
    rts

_sub_full_bd:
    move.l  #0x33334444, %d0
    rts

_sub_full_null:
    move.l  #0x55556666, %d0
    rts

_sub_full_od:
    move.l  #0x77778888, %d0
    rts

_sub_full_suppressed:
    move.l  #0x9999aaaa, %d0
    rts

_sub_full_suppressed_od:
    move.l  #0xbbbbcccc, %d0
    rts

_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d7
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d7
    bra     _fail
_fail5:
    move.l  #0xDEAD0005, %d7
    bra     _fail
_fail6:
    move.l  #0xDEAD0006, %d7

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
