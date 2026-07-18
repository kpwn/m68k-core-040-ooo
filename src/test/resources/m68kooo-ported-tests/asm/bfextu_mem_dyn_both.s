| bfextu_mem_dyn_both.s — BFEXTU <mem>{Do:Dw},Dn with BOTH offset AND
| width dynamic.  Exercises the V2 task #205 (B4) memory + dyn-both
| 3-phase crack that resolves the "4-source hazard":
|   phase 0: ALU_BF_PACK src_a=Dn_off src_b=Dn_wid -> TMP2
|   phase 1: LOAD long (ea) -> TMP1
|   phase 2: ALU_BFEXTU src_a=TMP1, src_b=TMP2, imm=dyn_both -> Dn_dst
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.
| FAIL sentinel: 0xDEADBEEF.

    .text
    .org 0
_start:
    move.l  #0x40800200, %a0
    move.l  #0xdeadbeef, (%a0)

    | ---- BFEXTU (A0){D1:D2},D3 — offset=8, width=16 ----
    | field = 0xdeadbeef; offset=8 -> bits [23..], width=16 -> 0xadbe.
    moveq   #8,  %d1
    moveq   #16, %d2
    bfextu  (%a0){%d1:%d2}, %d3
    cmp.l   #0xadbe, %d3
    bne     _fail

    | ---- BFEXTU (A0){D1:D2},D3 — offset=0, width=32 ----
    | full word -> 0xdeadbeef.
    moveq   #0,  %d1
    moveq   #0,  %d2      | width=0 maps to 32 in 68020 encoding
    bfextu  (%a0){%d1:%d2}, %d3
    cmp.l   #0xdeadbeef, %d3
    bne     _fail

    | ---- BFEXTU 0(A0){D1:D2},D3 — offset=4, width=20; (d16,An) form ----
    | offset=4 -> bits [27..], width=20 -> bits [27..8]
    |   = bits [27..8] of 0xdeadbeef = 0xeadbe.
    moveq   #4,  %d1
    moveq   #20, %d2
    bfextu  0(%a0){%d1:%d2}, %d3
    cmp.l   #0xeadbe, %d3
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
