| romcodes_1000_1100_indexed_rmw_scale.s -- OR/AND indexed RMW scales
|
| Covers non-unit brief-index scales for memory-destination OR/AND cracks.
| The checks cover byte/word/long memory merge behavior plus NZVC results.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00124000, %a0

    | OR.B D1,(0,A0,D2.W*2): D2=2 targets byte at A0+4.
    moveq   #2, %d2
    move.l  #0x120000ff, 4(%a0)
    move.l  #0x0000000f, %d1
    or.b    %d1, 0(%a0,%d2.w*2)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  4(%a0), %d7
    cmp.l   #0x1f0000ff, %d7
    bne     _fail

    | OR.W D1,(0,A0,D2.L*4): D2=1 targets word at A0+4.
    moveq   #1, %d2
    move.l  #0x1200ffff, 4(%a0)
    move.l  #0x000000f0, %d1
    or.w    %d1, 0(%a0,%d2.l*4)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  4(%a0), %d7
    cmp.l   #0x12f0ffff, %d7
    bne     _fail

    | AND.L D1,(0,A0,D2.W*8): D2=1 targets long at A0+8.
    moveq   #1, %d2
    move.l  #0xf0f0f0f0, 8(%a0)
    move.l  #0x0ff00ff0, %d1
    and.l   %d1, 0(%a0,%d2.w*8)
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  8(%a0), %d7
    cmp.l   #0x00f000f0, %d7
    bne     _fail

    | AND.B D1,(0,A0,D2.L*4): zero byte result sets Z and preserves rest.
    moveq   #1, %d2
    move.l  #0xf0345678, 4(%a0)
    move.l  #0x0000000f, %d1
    and.b   %d1, 0(%a0,%d2.l*4)
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    move.l  4(%a0), %d7
    cmp.l   #0x00345678, %d7
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
