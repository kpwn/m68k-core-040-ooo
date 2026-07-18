| bfins_dyn_offset_only.s — BFINS with dynamic offset (DO=1), static width.
| Task #112 (A5) V2 bitfield dynamic landing.  Exercises the 1-µop,
| three-read BFINS shape: Dy on src_a, Dn_insert on src_b, Dn_offset
| on src_c; imm carries the static width literal.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.
| FAIL sentinel: 0xDEADBEEF.

    .text
    .org 0
_start:
    | ---- BFINS D2 into D1{D3:#8}, offset 8, width 8 ----
    | D1 = 0xFF00_00FF, D2 = 0x1234_56AB, D3 = 8.
    | Insert 8 low bits of D2 (= 0xAB) at [8:16).
    | mask_base = 0xFF << 24 = 0xFF000000, ROR by 8 = 0x00FF0000.
    | D1 &= ~0x00FF0000 = 0xFF0000FF; ins = ROR(0xAB<<24, 8) = 0x00AB0000.
    | D1 |= 0x00AB0000 = 0xFFAB00FF.
    move.l  #0xFF0000FF, %d1
    move.l  #0x123456AB, %d2
    move.l  #8, %d3
    bfins   %d2, %d1{%d3:#8}
    move.l  #0xFFAB00FF, %d4
    cmp.l   %d4, %d1
    beq     1f
    bra     _fail
1:
    | ---- BFINS D2 into D1{D3:#4}, offset 28, width 4 (wrap) ----
    | D1 = 0, D2 = 0x0000_000F, D3 = 28.
    | ins_shl = 0xF << 28 = 0xF0000000; ROR 28 = (0xF0000000 >> 28) |
    |          (0xF0000000 << 4) = 0x0000000F.  D1 = 0x0000000F.
    move.l  #0x00000000, %d1
    move.l  #0x0000000F, %d2
    move.l  #28, %d3
    bfins   %d2, %d1{%d3:#4}
    move.l  #0x0000000F, %d4
    cmp.l   %d4, %d1
    beq     2f
    bra     _fail
2:
    | ---- BFINS with dynamic offset = 0 (boundary) ----
    | D1 = 0x00000000, D2 = 0xAB, D3 = 0, width = 8 → top 8 bits.
    | ins_shl = 0xAB << 24 = 0xAB000000, ROR 0 = 0xAB000000.
    move.l  #0x00000000, %d1
    move.l  #0x000000AB, %d2
    moveq   #0, %d3
    bfins   %d2, %d1{%d3:#8}
    move.l  #0xAB000000, %d4
    cmp.l   %d4, %d1
    beq     3f
    bra     _fail
3:
    | ---- BFINS dyn offset that wraps (offset mod 32) ----
    | D3 = 40 → offset & 31 = 8.  D1 = 0x0, D2 = 0x55, width 8.
    | Same semantics as first case with different base → 0x00550000.
    move.l  #0x00000000, %d1
    move.l  #0x00000055, %d2
    move.l  #40, %d3
    bfins   %d2, %d1{%d3:#8}
    move.l  #0x00550000, %d4
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
