| bfffo_dn_zero_field.s — Stage D-8 directed: BFFFO zero-field corner.
|
| Per PRM §4.32: if the bitfield contains no 1 bit, BFFFO returns
| offset + width (the bit position just past the end of the field).
| This is a common corner bug for naive priority-encoder implementations
| that return 0 or width-1 instead of offset+width.
|
| Scenarios:
|   1. BFFFO D0{#16:#16},D1 with D0=0 → D1 = 16 + 16 = 32.
|   2. BFFFO D0{#0:#8},D1  with D0=0x00FFFFFF → D1 = 8  (no bit in top 8).
|   3. BFFFO D0{#0:#8},D1  with D0=0x40000000 → D1 = 1  (bit 30 from MSB
|      is bit 1 in field starting at offset 0, since offset 0 = MSB).
|   4. BFFFO D0{#28:#4},D1 with D0=0x0000000F → D1 = 28 (first 1 at
|      bit 28 from MSB = bit 3 from LSB).
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0
_start:
    | Scenario 1 — all-zero field, width 16, offset 16: returns 32.
    moveq   #0, %d0
    bfffo   %d0{#16:#16}, %d1
    move.l  #32, %d2
    cmp.l   %d2, %d1
    beq     1f
    bra     _fail

1:
    | Scenario 2 — top byte all zero, width 8: returns 8.
    move.l  #0x00ffffff, %d0
    bfffo   %d0{#0:#8}, %d1
    move.l  #8, %d2
    cmp.l   %d2, %d1
    beq     2f
    bra     _fail

2:
    | Scenario 3 — bit 30 set (offset 1 from MSB), width 8: returns 1.
    move.l  #0x40000000, %d0
    bfffo   %d0{#0:#8}, %d1
    move.l  #1, %d2
    cmp.l   %d2, %d1
    beq     3f
    bra     _fail

3:
    | Scenario 4 — bit 28..31 set, width 4 at offset 28: returns 28.
    move.l  #0x0000000f, %d0
    bfffo   %d0{#28:#4}, %d1
    move.l  #28, %d2
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
