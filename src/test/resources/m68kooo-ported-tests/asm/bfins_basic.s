| bfins_basic.s — BFINS Dn, Dy{off:wid} static forms (decode-iv-d).
| Verifies insert-into-bitfield semantics match Musashi:
|   mask  = MASK_OUT_ABOVE_32(0xFFFFFFFF << (32 - width))
|   mask  = ROR_32(mask, offset)
|   ins   = MASK_OUT_ABOVE_32(insert << (32 - width))
|   ins   = ROR_32(ins, offset)
|   Dy &= ~mask; Dy |= ins
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.
| FAIL sentinel: 0xDEADBEEF.

    .text
    .org 0
_start:
    | ---- BFINS full-reg: offset=0, width=32 → Dy = Dn (identity) ----
    move.l  #0x00000000, %d1
    move.l  #0xDEADBEEF, %d2
    bfins   %d2, %d1{0:32}
    move.l  #0xDEADBEEF, %d3
    cmp.l   %d3, %d1
    beq     1f
    bra     _fail
1:
    | ---- BFINS high byte: offset=0, width=8 ----
    | insert 0xAB into top 8 bits of D1=0x0 → D1 = 0xAB000000
    move.l  #0x00000000, %d1
    move.l  #0x000000AB, %d2
    bfins   %d2, %d1{0:8}
    move.l  #0xAB000000, %d3
    cmp.l   %d3, %d1
    beq     2f
    bra     _fail
2:
    | ---- BFINS middle bits: offset=8, width=16 ----
    | D1 = 0xFF00_00FF, insert 0x1234 at [8:24) → D1 = 0xFF12_34FF
    move.l  #0xFF0000FF, %d1
    move.l  #0x00001234, %d2
    bfins   %d2, %d1{8:16}
    move.l  #0xFF1234FF, %d3
    cmp.l   %d3, %d1
    beq     3f
    bra     _fail
3:
    | ---- BFINS wrap-around: offset=28, width=8 ----
    | Musashi rotates right by offset — offset 28, width 8 spans bits
    | [28:32) ∪ [0:4) in the register.  Start D1 = 0, insert 0xAB →
    | ins_base = 0xAB << 24 = 0xAB000000, rotated right 28 →
    | 0xAB000000 >> 28 | 0xAB000000 << 4 = 0x0000_000A | 0xB000_0000
    |                                    = 0xB000_000A
    move.l  #0x00000000, %d1
    move.l  #0x000000AB, %d2
    bfins   %d2, %d1{28:8}
    move.l  #0xB000000A, %d3
    cmp.l   %d3, %d1
    beq     4f
    bra     _fail
4:
    | ---- BFINS preserves other bits ----
    | D1 = 0x1111_FFFF_FFFF_1111 (only low 32 — D1 is 32-bit)
    | Actually: D1 = 0xFFFF_0000, insert 0xAA at [16:8) → D1 = 0xFFAA_0000
    move.l  #0xFFFF0000, %d1
    move.l  #0x000000AA, %d2
    bfins   %d2, %d1{8:8}
    move.l  #0xFFAA0000, %d3
    cmp.l   %d3, %d1
    beq     5f
    bra     _fail
5:
    | ---- BFINS insert=0 → clears the field ----
    move.l  #0xFFFFFFFF, %d1
    move.l  #0x00000000, %d2
    bfins   %d2, %d1{4:8}
    | mask_base = 0xFF << 24 = 0xFF000000, ROR by 4 = 0x0FF00000
    | (ROR_32(0xFF000000, 4) = (0xFF000000 >> 4) | (0xFF000000 << 28) = 0x0FF00000)
    | D1 = 0xFFFFFFFF & ~0x0FF00000 = 0xF00FFFFF
    move.l  #0xF00FFFFF, %d3
    cmp.l   %d3, %d1
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
