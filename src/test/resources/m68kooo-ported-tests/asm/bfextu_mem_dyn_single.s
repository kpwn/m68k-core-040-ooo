| bfextu_mem_dyn_single.s — BFEXTU <mem>{Do:Dw},Dn with exactly one
| dynamic side (dyn-off-only OR dyn-wid-only).  Exercises the V2 task
| #205 (B4) memory + single-dynamic crack:
|   phase 0: LOAD long (ea) -> TMP1
|   phase 1: ALU_BFEXTU src_a=TMP1, src_b=Dn_dyn -> Dn_dst
|
| Before B4 these shapes dropped to legacy (decode_1110.vh) only for
| the narrow ROM-frontier (An)/dyn-wid combo; (d16,An) / (xxx).W /
| dyn-off were not covered at all.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.
| FAIL sentinel: 0xDEADBEEF.

    .text
    .org 0
_start:
    | Setup: memory long at (A0) = 0x12345678, at (d16,A1) = 0xAABBCCDD.
    move.l  #0x40800200, %a0
    move.l  #0x12345678, (%a0)
    move.l  #0x40800204, %a1       | base, disp will reach a1+0x10=0x40800214
    move.l  #0xaabbccdd, 0x10(%a1)

    | ---- BFEXTU (A0){#8:D2},D3 — dyn-wid (width=16, offset=8) ----
    | field = 0x12345678, offset=8 -> bits [23..], width=16 -> bits [23..8]
    |      = 0x3456.
    moveq   #16, %d2
    bfextu  (%a0){#8:%d2}, %d3
    cmp.l   #0x3456, %d3
    bne     _fail

    | ---- BFEXTU (A0){D2:#12},D3 — dyn-off (offset=4, width=12) ----
    | field = 0x12345678, offset=4 -> bits [27..], width=12 -> bits [27..16]
    |      = 0x234.
    moveq   #4, %d2
    bfextu  (%a0){%d2:#12}, %d3
    cmp.l   #0x234, %d3
    bne     _fail

    | ---- BFEXTU 16(A1){D2:#8},D3 — dyn-off with (d16,An) EA ----
    | field = 0xAABBCCDD, offset=0, width=8 -> top byte 0xAA.
    moveq   #0, %d2
    bfextu  16(%a1){%d2:#8}, %d3
    cmp.l   #0xaa, %d3
    bne     _fail

    | ---- BFEXTU 0x40800200.l{#0:D2},D3 — dyn-wid with abs.L ----
    | field = 0x12345678, offset=0, width=24 -> top 24 bits 0x123456.
    moveq   #24, %d2
    bfextu  0x40800200{#0:%d2}, %d3
    cmp.l   #0x123456, %d3
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
