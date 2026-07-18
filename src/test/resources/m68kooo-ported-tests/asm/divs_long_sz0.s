| divs_long_sz0.s — DIVS.L SZ=0 single-dest (Dq == Dr form)
|
| PRM §4.43 DIVS.L SZ=0: 32÷32 → 32 quotient.  Remainder discarded
| when Dq == Dl (ext1[2:0] == ext1[14:12]).  CCR: N from quotient[31],
| Z from (quotient == 0), V=1 on overflow (e.g. INT_MIN/-1 if the
| 68020+ were to set V — but per PRM INT_MIN/-1 for 32÷32 is actually
| well-defined: quotient = 0x80000000).
|
| Corner covered:
|   - Signed division with negative quotient
|   - Remainder NOT written (single-dest Dq==Dl form)
|   - CCR N reflects quotient sign
|   - V clear for well-formed quotient
|
| D-5 V2 single-µop shape: ALU_DIVSL_Q / ALU_DIVUL_Q with src_a=Dl,
| src_b=Dy (or imm).  mul_div.v's is_divl_q lane owns the arithmetic.
| The dual-dest (Dq != Dr) encoding stays on legacy.

    .text
    .org 0

_start:
    | ── Test 1: DIVS.L reg-src negative quotient ──────────────────
    | -100 / +7 = -14 remainder -2.  Single-dest form: only -14 in Dq.
    move.l  #-100, %d0                | dividend (Dl)
    move.l  #7, %d1                   | divisor (Dy)
    divs.l  %d1, %d0                  | D0 = -14; N=1 Z=0 V=0
    bvs     _fail
    bpl     _fail                      | N must be set
    beq     _fail
    cmp.l   #-14, %d0
    bne     _fail

    | ── Test 2: DIVS.L with both negatives → positive quotient ────
    | -100 / -7 = +14 remainder -2.
    move.l  #-100, %d2
    move.l  #-7, %d3
    divs.l  %d3, %d2                  | D2 = +14; N=0 Z=0 V=0
    bvs     _fail
    bmi     _fail                      | N must be clear
    beq     _fail
    cmp.l   #14, %d2
    bne     _fail

    | ── Test 3: DIVS.L imm-src form ───────────────────────────────
    | -15 / -1 = +15  (imm32 source).
    move.l  #-15, %d4
    divs.l  #-1, %d4                  | D4 = +15
    bvs     _fail
    bmi     _fail
    beq     _fail
    cmp.l   #15, %d4
    bne     _fail

    | ── Test 4: DIVU.L reg-src ────────────────────────────────────
    | 100 / 7 = 14 rem 2 (unsigned).  Only quotient kept.
    move.l  #100, %d5
    move.l  #7, %d6
    divu.l  %d6, %d5                  | D5 = 14
    bvs     _fail
    cmp.l   #14, %d5
    bne     _fail

    | ── Test 5: DIVS.L zero result, Z=1, V=0 ──────────────────────
    | +3 / +7 (quotient = 0 remainder 3).
    move.l  #3, %d7
    move.l  #7, %d0
    divs.l  %d0, %d7                  | D7 = 0; Z=1
    bvs     _fail
    bmi     _fail
    bne     _fail                      | Z must be set
    cmp.l   #0, %d7
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
