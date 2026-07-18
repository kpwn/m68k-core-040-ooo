| romcodes_1000_1100_pc_indexed_src.s -- OR/AND PC-indexed source EAs
|
| Covers brief PC-indexed source-to-Dn forms in opcode groups 1000/1100:
|   OR.{B,W}  (d8,PC,Xn),Dn
|   AND.{B,L} (d8,PC,Xn),Dn
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | OR.B table(PC,D4.L*4),D2: low-byte merge and N from byte result.
    moveq   #1, %d4
    move.l  #0x12345670, %d2
    bra     _or_b_shape
_or_b_tab:
    .byte   0x01, 0x02, 0x03, 0x04, 0x80, 0x06, 0x07, 0x08
_or_b_shape:
    or.b    _or_b_tab(%pc,%d4.l*4), %d2
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x123456f0, %d2
    bne     _fail

    | OR.W table(PC,D5.W*2),D3: word merge preserves upper 16 bits.
    moveq   #1, %d5
    move.l  #0xffff000f, %d3
    bra     _or_w_shape
    .balign 2
_or_w_tab:
    .word   0x0000, 0x00f0
_or_w_shape:
    or.w    _or_w_tab(%pc,%d5.w*2), %d3
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0xffff00ff, %d3
    bne     _fail

    | AND.L table(PC,D6.L*4),D0: long source and clear V/C.
    moveq   #1, %d6
    move.l  #0x12345678, %d0
    bra     _and_l_shape
    .balign 2
_and_l_tab:
    .long   0xffffffff, 0x00ff00ff
_and_l_shape:
    and.l   _and_l_tab(%pc,%d6.l*4), %d0
    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x00340078, %d0
    bne     _fail

    | AND.B table(PC,D7.W*2),D1: zero result, low-byte merge only.
    moveq   #1, %d7
    move.l  #0xabcdef10, %d1
    bra     _and_b_shape
_and_b_tab:
    .byte   0xff, 0x55, 0x0f, 0xaa
_and_b_shape:
    and.b   _and_b_tab(%pc,%d7.w*2), %d1
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0xabcdef00, %d1
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
