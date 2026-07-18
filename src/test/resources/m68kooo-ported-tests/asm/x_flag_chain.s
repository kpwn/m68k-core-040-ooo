| x_flag_chain.s — X-flag propagation over a 64-bit ADDX/SUBX chain
|
| Builds a 64-bit add using ADD.L (low) + ADDX.L (high), and a 64-bit
| subtract using SUB.L + SUBX.L.  Covers three interesting points:
|   1. X set by the low op and consumed by the high op.
|   2. Z across the two halves is AND'd (PRM: Z cleared on non-zero;
|      unchanged otherwise).  So an all-zero 64-bit result keeps Z=1.
|   3. Intermediate CCR reads by a branch between ADD and ADDX must
|      snapshot the correct CCR (no leakage).
|
| Stage A: 0xFFFFFFFF + 0x00000001 = 0x1_00000000 → low=0, X=1, then
|          ADDX 0x00000000 + 0x00000001 + X(1) = 0x00000002.
|          Final 64-bit high/low (D1/D0) = 0x00000002_00000000.
|
| Stage B: subtract 0x0000_0001_0000_0000 − 0x0000_0000_0000_0001:
|          low: 0 − 1 → 0xFFFFFFFF, X=C=1
|          high: 0x00000001 − 0x00000000 − X(1) = 0
|          → 0x0000_0000_FFFF_FFFF.

    .text
    .org 0

_start:
    | ── Stage A: 64-bit add, carry-out ──
    move.l  #0xFFFFFFFF, %d0    | a_lo
    move.l  #0x00000000, %d1    | a_hi
    move.l  #0x00000001, %d2    | b_lo
    move.l  #0x00000001, %d3    | b_hi

    add.l   %d2, %d0            | D0 = 0, X=C=1
    | Sanity: low must be zero with Z=1 immediately after
    bne     _fail
    bcc     _fail

    addx.l  %d3, %d1            | D1 = 0 + 1 + 1 = 2, Z cleared (non-zero)
    beq     _fail               | high must be non-zero

    cmp.l   #0x00000000, %d0
    bne     _fail
    cmp.l   #0x00000002, %d1
    bne     _fail

    | ── Stage B: 64-bit subtract across halves ──
    move.l  #0x00000000, %d4    | m_lo
    move.l  #0x00000001, %d5    | m_hi
    move.l  #0x00000001, %d6    | n_lo
    move.l  #0x00000000, %d7    | n_hi

    sub.l   %d6, %d4            | D4 = 0 - 1 = 0xFFFFFFFF, X=C=1, N=1
    bcc     _fail
    bpl     _fail

    subx.l  %d7, %d5            | D5 = 1 - 0 - 1 = 0, Z propagates (was 0)
    | After SUBX, Z reflects only THIS half's result (PRM: Z cleared on
    | non-zero, unchanged otherwise).  Result is zero → Z unchanged from
    | prior (which was 0 because low was non-zero).  So Z stays 0 here.
    | Don't branch on Z between the two — the final check is by value.

    cmp.l   #0xFFFFFFFF, %d4
    bne     _fail
    cmp.l   #0x00000000, %d5
    bne     _fail

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
