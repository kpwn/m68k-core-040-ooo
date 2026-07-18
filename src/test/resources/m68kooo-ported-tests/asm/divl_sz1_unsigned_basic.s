| divl_sz1_unsigned_basic.s — DIVU.L SZ=1 64÷32 unsigned basic cases.
|
| Task #204 / B3 directed scenarios.  Each case CMPs both Dq (quotient)
| and Dr (remainder) against expected values; final PASS sentinel only
| fires if every step matched.

    .text
    .org 0

_start:
    | ── Test 1: 64-bit dividend fits in 32-bit quotient ─────────────
    | {D0,D1} = 0x0000_0001_0000_0000 (= 2^32), divisor D2 = 2.
    | expect D1 = 2^31 = 0x80000000, D0 = 0.
    move.l  #0x00000001, %d0
    move.l  #0x00000000, %d1
    move.l  #2, %d2
    divu.l  %d2, %d0:%d1
    cmp.l   #0x80000000, %d1
    bne     _fail
    cmp.l   #0, %d0
    bne     _fail

    | ── Test 2: dividend fully in high half, remainder non-zero ──────
    | {D3,D4} = 0xDEADBEEF_00000000.  divisor = 0x10000.
    | Expected quotient = dividend / 0x10000 = 0x0000DEADBEEF0000
    | truncated to 32 bits = 0xDEADBEEF * 0x10000 / 0x10000 wait.
    | Let's pick a cleaner value: {D3,D4} = 0x0000_0100_0000_0009
    | divisor = 4.  quotient = 0x0040_0000_0000 / — no too big.
    | Use {D3,D4} = 0x0000_0003_0000_0001 (= 12884901889) / 5
    | = 2576980377 r 4 = 0x99999999 r 4.
    move.l  #0x00000003, %d3
    move.l  #0x00000001, %d4
    move.l  #5, %d5
    divu.l  %d5, %d3:%d4
    cmp.l   #0x99999999, %d4
    bne     _fail
    cmp.l   #4, %d3
    bne     _fail

    | ── Test 3: Low dividend only (high = 0), 1 / 1 ───────────────
    move.l  #0, %d6
    move.l  #1, %d7
    moveq   #1, %d0
    divu.l  %d0, %d6:%d7
    cmp.l   #1, %d7
    bne     _fail
    cmp.l   #0, %d6
    bne     _fail

    | ── Test 4: #imm32 divisor (exercises imm-src emit path) ──────
    | {D3,D4} = 0x00000000_FFFFFFFF / 2 = 0x7FFFFFFF rem 1.
    move.l  #0, %d3
    move.l  #0xFFFFFFFF, %d4
    divu.l  #2, %d3:%d4
    cmp.l   #0x7FFFFFFF, %d4
    bne     _fail
    cmp.l   #1, %d3
    bne     _fail

    | ── PASS ──────────────────────────────────────────────────────
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
