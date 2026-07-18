| movem_predec_after_sr_push.s -- ROM A-trap wrapper prologue shape.
|
| The Q700 ROM path at 0x4080b16a does:
|   move.w  SR,-(SP)
|   movem.l D1/D3-D4/A0-A1/A5-A6,-(SP)
|
| That leaves SP == original - 2 - 7*4, i.e. word-aligned but not
| long-aligned.  Verify the full predecrement happens and the reversed
| register order lands at the expected unaligned long slots.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  %a7, %d7

    move.l  #0x11111111, %d1
    move.l  #0x33333333, %d3
    move.l  #0x44444444, %d4
    move.l  #0xA0A0A0A0, %a0
    move.l  #0xA1A1A1A1, %a1
    move.l  #0xA5A5A5A5, %a5
    move.l  #0xA6A6A6A6, %a6

    move.w  %sr, -(%a7)
    movem.l %d1/%d3-%d4/%a0-%a1/%a5-%a6, -(%a7)

    move.l  %d7, %d0
    sub.l   %a7, %d0
    cmp.l   #30, %d0
    bne     _fail

    | Predecrement MOVEM stores the reversed list from low to high:
    | D1, D3, D4, A0, A1, A5, A6, then the saved SR word above it.
    cmp.l   #0x11111111, (%a7)+
    bne     _fail
    cmp.l   #0x33333333, (%a7)+
    bne     _fail
    cmp.l   #0x44444444, (%a7)+
    bne     _fail
    cmp.l   #0xA0A0A0A0, (%a7)+
    bne     _fail
    cmp.l   #0xA1A1A1A1, (%a7)+
    bne     _fail
    cmp.l   #0xA5A5A5A5, (%a7)+
    bne     _fail
    cmp.l   #0xA6A6A6A6, (%a7)+
    bne     _fail

    addq.l  #2, %a7
    cmp.l   %d7, %a7
    bne     _fail

_pass:
    lea     0xffff0000, %a0
    move.l  #0xc0ffee00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xffff0000, %a0
    move.l  #0xdeadbeef, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
