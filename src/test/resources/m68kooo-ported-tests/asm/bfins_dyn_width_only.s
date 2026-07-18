| bfins_dyn_width_only.s — BFINS with static offset, dynamic width (DW=1).
| Task #112 (A5) V2 bitfield dynamic landing.  Exercises the 1-µop,
| three-read BFINS shape: Dy on src_a, Dn_insert on src_b, Dn_width
| on src_c; imm carries the static offset literal.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.
| FAIL sentinel: 0xDEADBEEF.

    .text
    .org 0
_start:
    | ---- BFINS D2 into D1{#4:D3}, offset 4, width 8 ----
    | D1 = 0xFFFFFFFF, D2 = 0x00, D3 = 8.
    | mask_base = 0xFF << 24 = 0xFF000000, ROR 4 = 0x0FF00000.
    | D1 &= ~0x0FF00000 = 0xF00FFFFF.  insert=0 → D1 = 0xF00FFFFF.
    move.l  #0xFFFFFFFF, %d1
    move.l  #0x00000000, %d2
    move.l  #8, %d3
    bfins   %d2, %d1{#4:%d3}
    move.l  #0xF00FFFFF, %d4
    cmp.l   %d4, %d1
    beq     1f
    bra     _fail
1:
    | ---- BFINS D2 into D1{#8:D3}, offset 8, width 16 ----
    | D1 = 0xFF0000FF, D2 = 0x1234, D3 = 16.
    | mask_base = 0xFFFF << 16 = 0xFFFF0000, ROR 8 = 0x00FFFF00.
    | D1 &= ~0x00FFFF00 = 0xFF0000FF.
    | ins_shl = 0x1234 << 16 = 0x12340000, ROR 8 = 0x00123400.
    | D1 = 0xFF0000FF | 0x00123400 = 0xFF1234FF.
    move.l  #0xFF0000FF, %d1
    move.l  #0x00001234, %d2
    move.l  #16, %d3
    bfins   %d2, %d1{#8:%d3}
    move.l  #0xFF1234FF, %d4
    cmp.l   %d4, %d1
    beq     2f
    bra     _fail
2:
    | ---- BFINS dyn width=0 → Musashi effective 32 ----
    | D1 = 0xDEADBEEF, D2 = 0xCAFEBABE, D3=0 (→32), offset 0.
    | mask_base = 0xFFFFFFFF << 0 = 0xFFFFFFFF, ROR 0 = 0xFFFFFFFF.
    | D1 &= 0 = 0.  ins_shl = D2 << 0 = D2.  D1 = 0xCAFEBABE.
    move.l  #0xDEADBEEF, %d1
    move.l  #0xCAFEBABE, %d2
    moveq   #0, %d3
    bfins   %d2, %d1{#0:%d3}
    move.l  #0xCAFEBABE, %d4
    cmp.l   %d4, %d1
    beq     3f
    bra     _fail
3:
    | ---- BFINS dyn width=1, offset 16 ----
    | D1 = 0, D2 = 1, D3=1, offset=16.  Insert 1 bit at position 16.
    | ins_shl = 1 << 31 = 0x80000000, ROR 16 = 0x00008000.
    move.l  #0x00000000, %d1
    move.l  #0x00000001, %d2
    moveq   #1, %d3
    bfins   %d2, %d1{#16:%d3}
    move.l  #0x00008000, %d4
    cmp.l   %d4, %d1
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
