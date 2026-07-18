| move_l_d8_pc_xn_src.s -- MOVE.{B,W,L} (d8,PC,Xn),Dn  (task #199 / A8)
|
| Exercises V2 PC-indexed source path: EA = pd_pc + 2 + sx(d8) + Xn*scale.
| Covers word/long operand sizes, brief W/L index widths, and scales 1..4.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Case 1: MOVE.L (d8,PC,D0.W),D1 with d0=0, long operand.
    | Shape: 0x223b 0x0020  move.l (0x20,PC,D0.W),D1 — disp points at tbl_l0.
    | At the opword PC = 0x40800000 (reset PC); after opword ext1 fetch
    | advances PC by 2; the PC used in (d8,PC,Xn) is pd_pc + 2 per PRM.
    moveq   #0, %d0
    move.l  #0xdeadbeef, %d1
    move.l  _tbl_l0_pc(%pc,%d0.w), %d1
    cmp.l   #0x11223344, %d1
    bne     _fail

    | Case 2: MOVE.W (d8,PC,D2.W),D3 word operand, d2=2 (scale x1, so
    | effective disp = disp + 2 bytes).
    moveq   #2, %d2
    move.l  #0x55667788, %d3
    move.w  _tbl_w0_pc(%pc,%d2.w), %d3
    and.l   #0x0000ffff, %d3
    cmp.l   #0x0000cafe, %d3
    bne     _fail

    | Case 3: MOVE.L (d8,PC,D4.L*4),D5 scale x4, long index.
    | d4 = 1  -> offset = 4 bytes.  Load tbl_l1.
    moveq   #1, %d4
    move.l  #0xdeadbeef, %d5
    move.l  _tbl_l0_pc2(%pc,%d4.l*4), %d5
    cmp.l   #0xaabbccdd, %d5
    bne     _fail

    | Case 4: MOVE.B (d8,PC,D6.W*2),D7 scale x2, byte operand.
    | d6 = 0 -> loads byte at tbl_b0.
    moveq   #0, %d6
    move.l  #0, %d7
    move.b  _tbl_b0_pc(%pc,%d6.w*2), %d7
    cmp.l   #0x0000007a, %d7
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
_tbl_w0_pc:
    .word   0x1111
    .word   0xcafe           | loaded by case 2 with d2=2
_tbl_l0_pc2:
    .long   0x00000000
    .long   0xaabbccdd        | loaded by case 3 with d4=1 scale 4
_tbl_b0_pc:
    .byte   0x7a, 0x55, 0xaa, 0x33
