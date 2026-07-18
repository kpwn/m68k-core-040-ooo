| divl_sz1_signed_neg.s — DIVS.L SZ=1 64÷32 signed with negatives.
|
| Task #204 / B3 directed scenarios.  All signed combinations:
|   1. negative dividend, positive divisor
|   2. positive dividend, negative divisor
|   3. both negative → positive quotient
|   4. +imm divisor with negative dividend

    .text
    .org 0

_start:
    | ── Test 1: -15 / +3 = -5, rem 0 ─────────────────────────────
    | {D0,D1} = 0xFFFFFFFF_FFFFFFF1 (= -15 in 64-bit)
    move.l  #0xFFFFFFFF, %d0             | high (Dr)
    move.l  #0xFFFFFFF1, %d1             | low  (Dq)
    move.l  #3, %d2
    divs.l  %d2, %d0:%d1
    cmp.l   #-5, %d1
    bne     _fail
    cmp.l   #0, %d0
    bne     _fail

    | ── Test 2: +15 / -3 = -5, rem 0 ─────────────────────────────
    move.l  #0,  %d3
    move.l  #15, %d4
    move.l  #-3, %d5
    divs.l  %d5, %d3:%d4
    cmp.l   #-5, %d4
    bne     _fail
    cmp.l   #0, %d3
    bne     _fail

    | ── Test 3: -100 / -7 = +14, rem -2 ──────────────────────────
    | Remainder sign follows dividend sign per PRM.
    move.l  #0xFFFFFFFF, %d3
    move.l  #-100, %d4
    move.l  #-7,   %d5
    divs.l  %d5, %d3:%d4
    cmp.l   #14, %d4
    bne     _fail
    cmp.l   #-2, %d3
    bne     _fail

    | ── Test 4: 64-bit negative dividend / small positive divisor ──
    | {D0,D1} = 0xFFFF_FFFF_FFFF_FFFE = -2 (64-bit).  /1 = -2 rem 0.
    move.l  #0xFFFFFFFF, %d0
    move.l  #0xFFFFFFFE, %d1
    divs.l  #1, %d0:%d1
    cmp.l   #-2, %d1
    bne     _fail
    cmp.l   #0, %d0
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
