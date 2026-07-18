| bf_dynamic_width.s — BFEXTU/BFSET with dynamic width (Do=0, Dw=1).
| Exercises the 1-µop dynamic-width path where the width comes from
| a Dn register (low 5 bits, 0 → 32), not the ext-word literal.
| Offset remains a static literal.  Musashi: width = ((w-1)&31)+1.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.
| FAIL sentinel: 0xDEADBEEF.

    .text
    .org 0
_start:
    | ---- BFEXTU with dynamic width = 8 ----
    | offset literal = 0, D5 = 8 → extract top 8 bits of D0.
    move.l  #0x12345678, %d0
    moveq   #8, %d5
    bfextu  %d0{0:%d5}, %d1
    moveq   #0x12, %d2
    cmp.l   %d2, %d1
    beq     1f
    bra     _fail
1:
    | ---- BFEXTU with dynamic width = 16 ----
    | offset 8, width 16 → extract mid 16 bits = 0x3456
    move.l  #0x12345678, %d0
    moveq   #16, %d5
    bfextu  %d0{8:%d5}, %d1
    move.l  #0x3456, %d2
    cmp.l   %d2, %d1
    beq     2f
    bra     _fail
2:
    | ---- BFEXTU with dynamic width = 0 (== 32) ----
    | Musashi: width = ((0-1) & 31) + 1 = 32.  Extract full reg.
    move.l  #0xDEADBEEF, %d0
    moveq   #0, %d5
    bfextu  %d0{0:%d5}, %d1
    move.l  #0xDEADBEEF, %d2
    cmp.l   %d2, %d1
    beq     3f
    bra     _fail
3:
    | ---- BFSET with dynamic width = 4 ----
    | D0 = 0, offset 0, width 4 → D0 = 0xF0000000 (top 4 bits set).
    moveq   #0, %d0
    moveq   #4, %d5
    bfset   %d0{0:%d5}
    move.l  #0xF0000000, %d3
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
