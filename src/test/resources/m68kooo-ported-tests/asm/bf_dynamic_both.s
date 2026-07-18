| bf_dynamic_both.s — BFEXTU with both Do=1 AND Dw=1.
| Exercises the 2-µop crack: phase 0 packs {Dn_offset, Dn_width} into
| TMP1 via ALU_BF_PACK; phase 1 reads TMP1 as src_b with imm[12]=1.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.
| FAIL sentinel: 0xDEADBEEF.

    .text
    .org 0
_start:
    | ---- BFEXTU with dynamic offset=8 and width=8 ----
    | Extract byte [8:16) of D0 = 0x12345678 → 0x34.
    move.l  #0x12345678, %d0
    moveq   #8, %d4
    moveq   #8, %d5
    bfextu  %d0{%d4:%d5}, %d1
    moveq   #0x34, %d2
    cmp.l   %d2, %d1
    beq     1f
    bra     _fail
1:
    | ---- BFEXTU dynamic off=16, wid=16 → bottom half of D0 ----
    move.l  #0x12345678, %d0
    moveq   #16, %d4
    moveq   #16, %d5
    bfextu  %d0{%d4:%d5}, %d1
    move.l  #0x5678, %d2
    cmp.l   %d2, %d1
    beq     2f
    bra     _fail
2:
    | ---- BFCLR dynamic off=0, wid=8 → clear top byte ----
    move.l  #0xFFFFFFFF, %d0
    moveq   #0, %d4
    moveq   #8, %d5
    bfclr   %d0{%d4:%d5}
    move.l  #0x00FFFFFF, %d3
    cmp.l   %d3, %d0
    beq     _pass
    bra     _fail

_pass:
    move.l  #0xC0FFEE00, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt
