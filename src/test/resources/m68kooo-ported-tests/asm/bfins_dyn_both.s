| bfins_dyn_both.s — BFINS with dyn offset AND dyn width (DO=1 AND DW=1).
| The 3-source case that D-8 deferred.  Task #112 lands it as a 2-µop
| crack: phase 0 packs Dn_offset+Dn_width into TMP1; phase 1 runs BFINS
| with src_a=Dy, src_b=Dn_insert, src_c=TMP1, imm[12:13]=11 (dyn-both
| via src_c).
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.
| FAIL sentinel: 0xDEADBEEF.

    .text
    .org 0
_start:
    | ---- BFINS D2 into D1{D3:D4}: offset=8, width=16 ----
    | D1 = 0xFF0000FF, D2 = 0x00001234, D3=8, D4=16.
    | Matches the width-only test: final = 0xFF1234FF.
    move.l  #0xFF0000FF, %d1
    move.l  #0x00001234, %d2
    move.l  #8,  %d3
    move.l  #16, %d4
    bfins   %d2, %d1{%d3:%d4}
    move.l  #0xFF1234FF, %d5
    cmp.l   %d5, %d1
    beq     1f
    bra     _fail
1:
    | ---- BFINS D2 into D1{D3:D4}: offset=0, width=0 (→32) ----
    | D1 = 0xDEADBEEF, D2 = 0xCAFEBABE; D3=0, D4=0 (effective width=32).
    | Identity: D1 = 0xCAFEBABE.
    move.l  #0xDEADBEEF, %d1
    move.l  #0xCAFEBABE, %d2
    moveq   #0, %d3
    moveq   #0, %d4
    bfins   %d2, %d1{%d3:%d4}
    move.l  #0xCAFEBABE, %d5
    cmp.l   %d5, %d1
    beq     2f
    bra     _fail
2:
    | ---- BFINS D2 into D1{D3:D4}: offset=28, width=8 (wrap) ----
    | D1=0, D2=0xAB; D3=28, D4=8.  ins_shl = 0xAB << 24 = 0xAB000000,
    | ROR 28 = (0xAB000000 >> 28) | (0xAB000000 << 4) =
    |         0x0000000A | 0xB0000000 = 0xB000000A.
    move.l  #0x00000000, %d1
    move.l  #0x000000AB, %d2
    move.l  #28, %d3
    move.l  #8,  %d4
    bfins   %d2, %d1{%d3:%d4}
    move.l  #0xB000000A, %d5
    cmp.l   %d5, %d1
    beq     3f
    bra     _fail
3:
    | ---- BFINS with D3=35 (offset masked to 3), D4=37 (width masked to 5) ----
    | Verify Musashi offset &= 31 and width ((raw-1)&31)+1 semantics.
    | offset=3, width=5.  D1 = 0xFFFFFFFF, D2 = 0x00.
    | mask_base = 0x1F << 27 = 0xF8000000, ROR 3 = 0x1F000000.
    | D1 &= ~0x1F000000 = 0xE0FFFFFF.  insert=0 → 0xE0FFFFFF.
    move.l  #0xFFFFFFFF, %d1
    move.l  #0x00000000, %d2
    move.l  #35, %d3
    move.l  #37, %d4
    bfins   %d2, %d1{%d3:%d4}
    move.l  #0xE0FFFFFF, %d5
    cmp.l   %d5, %d1
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
