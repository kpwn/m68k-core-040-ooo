| addx_chain.s — ADDX.L chain using X flag between two 32-bit halves
|
| Hypothesis: ADD.L sets X=C=<carry>.  ADDX.L then consumes X to carry
| that bit into the high half of a 64-bit sum.  Both stages also update
| Z: low-half Z reflects low result, high-half ADDX Z is AND'ed with the
| prior Z (PRM: "Z is cleared if result non-zero; unchanged otherwise").
|
| Compute (0x00000001_FFFFFFFF) + (0x00000000_00000001) = 0x00000002_00000000
|   Low:  0xFFFFFFFF + 0x00000001 = 0x00000000  (with X=C=1)
|   High (ADDX): 0x00000001 + 0x00000000 + X(1) = 0x00000002
|
| Layout:
|   D0 = a_lo = 0xFFFFFFFF   D1 = a_hi = 0x00000001
|   D2 = b_lo = 0x00000001   D3 = b_hi = 0x00000000
|   ADD.L  D2, D0   → D0 = 0x00000000, X=1
|   ADDX.L D3, D1   → D1 = 0x00000002
|
| Verify D0 = 0 and D1 = 2.  Also spot-check ADDX propagation of X=0:
|   Second round: reset with non-overflowing low add and verify D1 high
|   does NOT increment.

    .text
    .org 0

_start:
    | ── First chain: low overflow propagates to high ──
    move.l  #0xFFFFFFFF, %d0
    move.l  #0x00000001, %d1
    move.l  #0x00000001, %d2
    move.l  #0x00000000, %d3
    add.l   %d2, %d0                 | D0 = 0, X=C=1
    addx.l  %d3, %d1                 | D1 = 1 + 0 + 1 = 2

    tst.l   %d0
    bne     _fail                    | D0 must be 0
    move.l  #0x00000002, %d7
    cmp.l   %d7, %d1
    bne     _fail

    | ── Second chain: non-overflowing low add → X=0 ──
    | 0x00000000_00000001 + 0x00000000_00000002 = 0x00000000_00000003
    move.l  #0x00000001, %d0
    move.l  #0x00000000, %d1
    move.l  #0x00000002, %d2
    move.l  #0x00000000, %d3
    add.l   %d2, %d0                 | D0 = 3, X=C=0
    addx.l  %d3, %d1                 | D1 = 0 + 0 + 0 = 0

    move.l  #0x00000003, %d7
    cmp.l   %d7, %d0
    bne     _fail
    tst.l   %d1
    bne     _fail                    | high must remain 0

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
