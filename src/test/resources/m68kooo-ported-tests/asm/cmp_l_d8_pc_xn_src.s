| cmp_l_d8_pc_xn_src.s -- CMP.{W,L} (d8,PC,Xn),Dn  (task #199 / A8)
|
| Exercises V2 ALU-family indexed-src crack for PC-indexed source.
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Case 1: CMP.L (d8,PC,D0.W),D1 equal -> Z=1.
    moveq   #0, %d0
    move.l  #0x11223344, %d1
    cmp.l   _tbl_l0_pc(%pc,%d0.w), %d1
    bne     _fail

    | Case 2: CMP.L (d8,PC,D2.L*4),D3 not-equal, signed greater.
    moveq   #1, %d2
    move.l  #0x7fffffff, %d3
    cmp.l   _tbl_l_pc(%pc,%d2.l*4), %d3
    ble     _fail             | d3 > mem  -> GT

    | Case 3: CMP.W (d8,PC,D4.W),D5 word-size signed comparison.
    moveq   #0, %d4
    move.l  #0x0000cafe, %d5
    cmp.w   _tbl_w0_pc(%pc,%d4.w), %d5
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

    .align 2
_tbl_l0_pc:
    .long   0x11223344
_tbl_l_pc:
    .long   0x00000000
    .long   0x00112233        | d2=1 scale 4 -> this
_tbl_w0_pc:
    .word   0xcafe
