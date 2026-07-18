| bfextu_static_dn.s — Stage D-8 directed: static BFEXTU on Dn.
|
| Corner caught during D-8 implementation: the V2 decoder initially
| used ext[15] and ext[11] as the Do/Dw bits (wrong PRM reading); the
| PRM actual layout is ext[11]=Do, ext[5]=Dw.  This test exercises the
| static-only path with all four relevant bits = 0 so a regression in
| the static-gate would still route the op to V2 but emit the wrong
| imm packing if Dw/Do bits shifted.
|
| Scenarios:
|   1. BFEXTU D0{#7:#5},D1 — extract bits [7..11] from D0 into D1
|      D0 = 0xAAAA_AAAA → top 4 = bits [24..27] of the mnemonic-ordering.
|      PRM: offset N numbers bits from MSB (bit 31).  For BFEXTU
|      D0{#7:#5},D1 with D0=0xAAAA_AAAA = 1010_1010_...1010:
|        bit 7..11 from MSB = bits [24..20] of D0 (MSB=bit 31) =
|        D0[24:20] = 01010 (alternating).
|        Extracted value zero-extended to D1 = 0x0000_000A (5-bit 01010).
|      Verify N=0 (MSB of positioned field = 0 since offset 7 aligns
|      bit 24 = 0 into MSB of rotated).
|
|   2. BFEXTU D0{#0:#32},D1 — full-width extract.  D0 = 0x12345678.
|      D1 = D0 = 0x12345678.  N=0 (bit 31 of D0 = 0), Z=0.
|
|   3. BFEXTU D0{#16:#16},D1 — lower half.  D0 = 0xAABBCCDD.
|      D1 = 0x0000_CCDD.  N=1 (bit 16 of D0 = C[3] = 1).
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.
| FAIL sentinel: 0xDEAD_BEEF.

    .text
    .org 0
_start:
    | Scenario 1 — offset 7 width 5, D0 = 0xAAAAAAAA.
    move.l  #0xaaaaaaaa, %d0
    bfextu  %d0{#7:#5}, %d1
    move.l  #0x0000000a, %d2
    cmp.l   %d2, %d1
    beq     1f
    bra     _fail

1:
    | Scenario 2 — offset 0 width 32, D0 = 0x12345678.
    move.l  #0x12345678, %d0
    bfextu  %d0{#0:#32}, %d1
    move.l  #0x12345678, %d2
    cmp.l   %d2, %d1
    beq     2f
    bra     _fail

2:
    | Scenario 3 — offset 16 width 16, D0 = 0xAABBCCDD.
    move.l  #0xaabbccdd, %d0
    bfextu  %d0{#16:#16}, %d1
    move.l  #0x0000ccdd, %d2
    cmp.l   %d2, %d1
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
