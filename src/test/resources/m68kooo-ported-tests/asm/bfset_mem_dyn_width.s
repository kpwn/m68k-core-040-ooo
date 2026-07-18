| bfset_mem_dyn_width.s — BFSET <mem>{#off:Dw} with dynamic width.
| Exercises the V2 task #205 (B4) memory RMW dyn-single crack for
| BFCHG/BFCLR/BFSET.  3 phases: LOAD→ALU→STORE.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.
| FAIL sentinel: 0xDEADBEEF.

    .text
    .org 0
_start:
    move.l  #0x40800300, %a0
    move.l  #0x00000000, (%a0)

    | ---- BFSET (A0){#8:D1} — offset=8, width=16 ----
    | Set bits [23..8] of longword -> 0x00ffff00.
    moveq   #16, %d1
    bfset   (%a0){#8:%d1}
    move.l  (%a0), %d2
    cmp.l   #0x00ffff00, %d2
    bne     _fail

    | ---- BFCLR (A0){D1:#24} — dyn-off, clear bits [4..27] ----
    moveq   #4, %d1
    bfclr   (%a0){%d1:#24}
    move.l  (%a0), %d2
    | 0x00ffff00 with bits [27..4] cleared -> 0x00000000 | 0x00000000
    | offset=4 nibbles; field is 24 bits spanning 0x0ffffff0 mask -> cleared.
    cmp.l   #0x00000000, %d2
    bne     _fail

    | ---- BFSET (A0){#0:D1} — dyn-wid=8, set top byte ----
    moveq   #8, %d1
    bfset   (%a0){#0:%d1}
    move.l  (%a0), %d2
    cmp.l   #0xff000000, %d2
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
