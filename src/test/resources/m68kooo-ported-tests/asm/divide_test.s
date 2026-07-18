| divide_test.s — DIVS.L / DIVU.L SZ=1 (64÷32) directed tests.
|
| Un-DEFER'd by task #204 / B3: mul_div.v now accepts a 64-bit dividend
| via src_c (high, Dr) + src_a (low, Dq); the assembler emits a single
| uop with has_src_c=1 and uop_size=SZ_QUAD (PRM §4.43/§4.44).
|
| Semantics recap:
|   DIVU.L <ea>, Dr:Dq   — 64÷32 unsigned: dividend = {Dr,Dq},
|                          divisor = <ea>, Dq ← quotient, Dr ← remainder
|   DIVS.L <ea>, Dr:Dq   — signed variant.
|   V=1 on overflow (Dr:Dq unchanged), /0 → vec 5.
|
| NOTE: GNU as syntax — `divu.l` with a `:Dr:Dq` target selects SZ=1.

    .text
    .org 0

_start:
    | ── Test 1: DIVU.L 64/32, fits in 32 bits ──────────────────────
    | {D0,D1} = 0x00000002_00000000 (= 2^33), divisor D2 = 4.
    | expect: D1 = 2^33 / 4 = 0x80000000, D0 = 0.
    move.l  #0x00000002, %d0             | Dr high
    move.l  #0x00000000, %d1             | Dq low
    move.l  #0x00000004, %d2
    divu.l  %d2, %d0:%d1                  | D1=0x80000000, D0=0
    cmp.l   #0x80000000, %d1
    bne     _fail
    cmp.l   #0,          %d0
    bne     _fail

    | ── Test 2: DIVU.L with 64-bit dividend & non-zero rem ─────────
    | {D3,D4} = 15 (high=0, low=15).  divisor D5 = 7.
    | expect: D4 = 2, D3 = 1.
    move.l  #0,  %d3
    move.l  #15, %d4
    move.l  #7,  %d5
    divu.l  %d5, %d3:%d4                  | D4=2, D3=1
    cmp.l   #2, %d4
    bne     _fail
    cmp.l   #1, %d3
    bne     _fail

    | ── Test 3: DIVS.L 64/32 signed, negative dividend ─────────────
    | {D0,D1} = 0xFFFFFFFF_FFFFFFF1 = -15, divisor = +3.
    | expect: D1 = -5, D0 = 0.
    move.l  #0xFFFFFFFF, %d0
    move.l  #0xFFFFFFF1, %d1
    move.l  #3,          %d2
    divs.l  %d2, %d0:%d1                  | D1=-5, D0=0
    cmp.l   #-5, %d1
    bne     _fail
    cmp.l   #0,  %d0
    bne     _fail

    | ── Test 4: DIVU.L /0 — raises vec 5, handler writes PASS ─────
    | Install handler at vec-5 (0x14), set divisor = 0, divide.
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000014
    move.l  #0x00000000, %d0
    move.l  #0x12345678, %d1
    moveq   #0, %d2
    divu.l  %d2, %d0:%d1                  | /0 → vec 5
    | Fallthrough without trap = FAIL.
    bra     _fail

_handler:
    | ── PASS sentinel (set by /0 handler) ─────────────────────────
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d3
    move.l  %d3, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d3
    move.l  %d3, (%a0)
    bra     _halt
