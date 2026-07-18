| mull_sz0_overflow.s — MULS.L / MULU.L SZ=0 overflow → V=1
|
| PRM §4.141 MULS.L SZ=0: 32×32 → low 32 bits of signed product; V=1
| if the truncated result is not the sign-extension of the full 64-bit
| product (i.e. the product didn't fit in 32 bits).  N/Z from the low
| 32 bits, C=0, X unchanged.
|
| PRM §4.141 MULU.L SZ=0: 32×32 → low 32 bits of unsigned product;
| V=1 if the high 32 bits are non-zero (product >= 2^32).
|
| Corner covered: truncation with V reflecting the dropped upper word.
| D-5 V2 single-µop shape drives alu_op=ALU_MULSL/ALU_MULUL directly;
| the V flag emerges from mul_div.v's overflow detection.

    .text
    .org 0

_start:
    | ── Test 1: MULS.L overflow (positive × positive too big) ─────
    | 0x10000 × 0x10000 = 0x1_0000_0000 → low32 = 0, V=1, Z=1.
    move.l  #0x00010000, %d0
    muls.l  #0x00010000, %d0
    bvc     _fail                     | V must be set
    bne     _fail                     | Z must be set (low32 == 0)
    bmi     _fail                     | N must be clear
    cmp.l   #0, %d0
    bne     _fail

    | ── Test 2: MULU.L overflow, same pattern (unsigned) ──────────
    move.l  #0x00010000, %d1
    mulu.l  #0x00010000, %d1
    bvc     _fail                     | V must be set
    bne     _fail                     | Z must be set
    bmi     _fail                     | N must be clear
    cmp.l   #0, %d1
    bne     _fail

    | ── Test 3: MULS.L negative × large → V=1, sign mismatch ──────
    | -2 × 0x80000000 (−2^31) = +2^32 = 0x1_0000_0000.
    | low32 = 0, high = 1.  Signed overflow because real product is
    | positive and out-of-range for signed 32-bit.  V=1, Z=1.
    move.l  #-2, %d2
    muls.l  #0x80000000, %d2
    bvc     _fail                     | V must be set
    bne     _fail                     | Z must be set (low32 == 0)
    cmp.l   #0, %d2
    bne     _fail

    | ── Test 4: MULS.L SZ=0 no-overflow sanity (positive × positive
    |           fits in signed 32-bit) — V must be CLEAR. ─────────
    move.l  #5, %d3
    muls.l  #7, %d3
    bvs     _fail                     | V must be clear
    cmp.l   #35, %d3
    bne     _fail

    | ── Test 5: MULU.L SZ=0 no-overflow sanity ────────────────────
    |   0xFFFF × 0xFFFF = 0xFFFE0001 (32 bits, no overflow).  V=0.
    move.l  #0x0000FFFF, %d4
    mulu.l  #0x0000FFFF, %d4
    bvs     _fail                     | V must be clear
    cmp.l   #0xFFFE0001, %d4
    bne     _fail

    | ── PASS sentinel ─────────────────────────────────────────────
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
    bra     _halt
