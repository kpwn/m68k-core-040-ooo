| bfchg_mem_dyn_both.s — BFCHG <mem>{Do:Dw} with BOTH offset AND
| width dynamic.  Exercises the V2 task #205 (B4) memory RMW dyn-both
| 4-phase crack:
|   phase 0: ALU_BF_PACK Dn_off, Dn_wid → TMP2
|   phase 1: LOAD long <ea>             → TMP1
|   phase 2: ALU_BFCHG src_a=TMP1, src_b=TMP2 → TMP1
|   phase 3: STORE long TMP1            → <ea>
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.
| FAIL sentinel: 0xDEADBEEF.

    .text
    .org 0
_start:
    move.l  #0x40800300, %a0
    move.l  #0x12345678, (%a0)

    | ---- BFCHG (A0){D1:D2} — offset=8, width=16 ----
    | field bits [23..8] = 0x3456; toggled = ~0x3456 = 0xCBA9.
    | result = 0x12CBA978.
    moveq   #8,  %d1
    moveq   #16, %d2
    bfchg   (%a0){%d1:%d2}
    move.l  (%a0), %d3
    cmp.l   #0x12CBA978, %d3
    bne     _fail

    | ---- BFCHG again with same offset/width — should restore original ----
    bfchg   (%a0){%d1:%d2}
    move.l  (%a0), %d3
    cmp.l   #0x12345678, %d3
    bne     _fail

    | ---- BFCHG (A0){D1:D2} — offset=0 width=32 (full long) ----
    moveq   #0, %d1
    moveq   #0, %d2       | width 0 == 32
    bfchg   (%a0){%d1:%d2}
    move.l  (%a0), %d3
    cmp.l   #0xedcba987, %d3
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
