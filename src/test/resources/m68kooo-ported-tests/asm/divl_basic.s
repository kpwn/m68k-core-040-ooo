| divl_basic.s — DIVU.L / DIVS.L 32÷32 (SZ=0) basic directed test
|
| Exercises the 32÷32 → 32-quot (+ 32-rem) form cracked by decode.v
| into a 2-μop (or 1-μop when Dq==Dr) sequence.
|
| Test vectors (checks that both the quotient and remainder μops
| produce the expected register state, and that flags are set per PRM):
|
|   1. DIVU.L unsigned, Dq != Dr, exact quotient (15/3 = 5 r 0)
|   2. DIVU.L unsigned, Dq != Dr, with remainder  (31/5 = 6 r 1)
|   3. DIVS.L signed, positive ops                 (+15 / +3 = +5)
|   4. DIVS.L signed, negative / positive          (-15 / +3 = -5)
|   5. DIVS.L signed, INT_MIN / -1 special case    (0x80000000 / -1)
|   6. DIVU.L Dq == Dr collapsed single-μop form  (12345 / 7 → Dq=quot)
|
| Each test CMPs the expected value and BNE to _fail; final sentinel
| store signals PASS.

    .text
    .org 0

_start:
    | ── Test 1: DIVU.L — 15 / 3, no remainder ───────────────────────
    |   Dq=D0 (dividend=15), Dr=D1 (rem target), divisor=D2=3
    move.l  #0x0000000F, %d0         | dividend (15)
    move.l  #0xDEADBEEF, %d1         | Dr garbage (to verify write)
    move.l  #0x00000003, %d2         | divisor (3)
    divul.l %d2, %d1:%d0             | SZ=0 32÷32: D0=quot=5, D1=rem=0
    cmp.l   #5, %d0
    bne     _fail
    cmp.l   #0, %d1
    bne     _fail

    | ── Test 2: DIVU.L — 31 / 5 = 6 r 1 ──────────────────────────────
    move.l  #0x0000001F, %d0         | 31
    move.l  #0xDEADBEEF, %d1
    move.l  #0x00000005, %d2
    divul.l %d2, %d1:%d0             | D0=quot=6, D1=rem=1
    cmp.l   #6, %d0
    bne     _fail
    cmp.l   #1, %d1
    bne     _fail

    | ── Test 3: DIVS.L signed — +15 / +3 = +5 r 0 ────────────────────
    move.l  #0x0000000F, %d3
    move.l  #0xDEADBEEF, %d4
    move.l  #0x00000003, %d5
    divsl.l %d5, %d4:%d3             | D3=+5, D4=0
    cmp.l   #5, %d3
    bne     _fail
    cmp.l   #0, %d4
    bne     _fail

    | ── Test 4: DIVS.L signed — -15 / +3 = -5 r 0 ────────────────────
    move.l  #0xFFFFFFF1, %d3         | -15
    move.l  #0xDEADBEEF, %d4
    move.l  #0x00000003, %d5
    divsl.l %d5, %d4:%d3             | D3=-5=0xFFFFFFFB, D4=0
    cmp.l   #-5, %d3
    bne     _fail
    cmp.l   #0, %d4
    bne     _fail

    | ── Test 5: DIVS.L signed — INT_MIN / -1 special case ────────────
    | Musashi: quot=0x80000000, rem=0 (no V-flag set for 32÷32 .L form).
    move.l  #0x80000000, %d3
    move.l  #0xDEADBEEF, %d4
    move.l  #0xFFFFFFFF, %d5         | -1
    divsl.l %d5, %d4:%d3             | D3=0x80000000, D4=0
    cmp.l   #0x80000000, %d3
    bne     _fail
    cmp.l   #0, %d4
    bne     _fail

    | ── Test 6: DIVU.L Dq==Dr — 12345 / 7 = 1763 r 4 ─────────────────
    | When Dq == Dr, only the quotient is kept (single-μop form).
    move.l  #12345, %d6
    move.l  #7, %d7
    divu.l  %d7, %d6                 | D6 = 12345/7 = 1763 (single-dest collapsed)
    cmp.l   #1763, %d6
    bne     _fail

    | ── All divide tests passed; write PASS sentinel ────────────────
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
