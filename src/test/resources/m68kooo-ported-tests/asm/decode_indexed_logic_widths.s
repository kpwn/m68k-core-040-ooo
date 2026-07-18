| decode_indexed_logic_widths.s -- indexed logic decode sibling audit
|
| Covers newly widened brief-indexed decode forms:
|   OR.{B,W,L}  (d8,An,Xn.{W,L}*scale),Dn
|   OR.{B,W,L}  Dn,(d8,An,Xn.W)
|   EOR.{B,W,L} Dn,(d8,An,Xn.W)
|   TST.W/L     (d8,An,Xn.{W,L}*scale)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | OR.W (0,A0,D4.W*2),D1: word-index source with scale.
    lea     0x00121000, %a0
    move.l  #0x00f01234, 4(%a0)
    moveq   #2, %d4
    move.l  #0xffff000f, %d1
    .word   0x8270, 0x4200
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0xffff00ff, %d1
    bne     _fail

    | OR.B (3,A1,D5.L*4),D2: long-index source, byte merge.
    lea     0x00121100, %a1
    move.l  #0x00000080, 4(%a1)
    moveq   #1, %d5
    move.l  #0x12345670, %d2
    .word   0x8431, 0x5c03
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x123456f0, %d2
    bne     _fail

    | OR.L (-4,A2,A3.L*8),D0: address-register long index with scale.
    lea     0x00121200, %a2
    move.l  #0x00ff00ff, 12(%a2)
    movea.l #2, %a3
    move.l  #0xff000000, %d0
    .word   0x80b2, 0xbefc
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0xffff00ff, %d0
    bne     _fail

    | OR indexed memory destinations across B/W/L.
    lea     0x00122000, %a0
    moveq   #4, %d2
    move.l  #0x120000ff, 4(%a0)
    move.l  #0x0000000f, %d1
    .word   0x8330, 0x2000       | or.b D1,(0,A0,D2.W)
    move.l  4(%a0), %d6
    cmp.l   #0x1f0000ff, %d6
    bne     _fail

    move.l  #0x1200ffff, 4(%a0)
    move.l  #0x000000f0, %d1
    .word   0x8370, 0x2000       | or.w D1,(0,A0,D2.W)
    move.l  4(%a0), %d6
    cmp.l   #0x12f0ffff, %d6
    bne     _fail

    move.l  #0x120000f0, 4(%a0)
    move.l  #0x00f0ff00, %d1
    .word   0x83b0, 0x2000       | or.l D1,(0,A0,D2.W)
    move.l  4(%a0), %d6
    cmp.l   #0x12f0fff0, %d6
    bne     _fail

    | EOR indexed memory destinations across B/W/L.
    lea     0x00123000, %a0
    moveq   #4, %d2
    move.l  #0xa5000000, 4(%a0)
    move.l  #0x000000ff, %d3
    .word   0xb730, 0x2000       | eor.b D3,(0,A0,D2.W)
    move.l  4(%a0), %d6
    cmp.l   #0x5a000000, %d6
    bne     _fail

    move.l  #0x12340000, 4(%a0)
    move.l  #0x0000ffff, %d4
    .word   0xb970, 0x2000       | eor.w D4,(0,A0,D2.W)
    move.l  4(%a0), %d6
    cmp.l   #0xedcb0000, %d6
    bne     _fail

    move.l  #0x00ff00ff, 4(%a0)
    move.l  #0xff00ff00, %d5
    .word   0xbbb0, 0x2000       | eor.l D5,(0,A0,D2.W)
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  4(%a0), %d6
    cmp.l   #0xffffffff, %d6
    bne     _fail

    | TST.W (0,A0,D2.W): word indexed sibling.
    lea     0x00124000, %a0
    moveq   #4, %d2
    move.w  #0x8001, 4(%a0)
    .word   0x4a70, 0x2000
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail

    | TST.L (0,A1,D3.L*4): long indexed sibling with non-unit scale.
    lea     0x00124100, %a1
    moveq   #1, %d3
    move.l  #0x00000000, 4(%a1)
    .word   0x4ab1, 0x3c00
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail

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
