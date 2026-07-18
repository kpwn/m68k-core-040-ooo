| bfextu_dyn_both_mem.s — BFEXTU <mem>{Do:Dw},Dn.  Exercises the
| memory-src + dynamic offset + dynamic width corner.  V2 task #112
| currently owns only Dn-direct dynamic forms; memory-src with DO=1
| AND DW=1 stays on legacy until a follow-up EA-compute crack grows.
| This test covers the narrow ROM-frontier dynamic-width path (legacy
| BFEXTU (An){0:Dw},Dn) which HAS been implemented — width in Dn, but
| offset forced to 0.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.
| FAIL sentinel: 0xDEADBEEF.

    .text
    .org 0
_start:
    | Set up a memory word at (A0) to extract from.
    move.l  #0x40800100, %a0
    move.l  #0x12345678, (%a0)

    | ---- BFEXTU (A0){0:D1},D2 — static offset=0, dynamic width=16 ----
    | field = 0x12345678, extract top 16 bits = 0x1234.
    moveq   #16, %d1
    bfextu  (%a0){#0:%d1}, %d2
    move.l  #0x1234, %d3
    cmp.l   %d3, %d2
    beq     1f
    bra     _fail
1:
    | ---- BFEXTU (A0){0:D1},D2 — dynamic width=8, top byte = 0x12 ----
    moveq   #8, %d1
    bfextu  (%a0){#0:%d1}, %d2
    moveq   #0x12, %d3
    cmp.l   %d3, %d2
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
