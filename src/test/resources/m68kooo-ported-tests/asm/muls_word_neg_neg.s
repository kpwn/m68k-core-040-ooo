| muls_word_neg_neg.s — MULS.W with both operands negative (Stage D-5)
|
| PRM §4.140 MULS.W: 16×16 → 32-bit signed product.  Top 32 bits of
| destination Dn must hold the signed product (not just low 16).  CCR
| NZVC per product: N = result[31], Z = (result==0), V = 0 (fits always
| in .W form), C = 0.  X unchanged.
|
| Corner covered: two negative operands should yield a positive product.
| D-5 migration uses a 3-µop crack for reg-src MULS.W (pre-extend dst
| Dn.W → TMP1 via ALU_MOV SZ_WORD, pre-extend src Dm.W → TMP2, then
| MULS TMP1,TMP2 → Dn at SZ_LONG).  Upper 16 bits of the source operands
| must NOT leak through to the multiplier — legacy decoder got this
| wrong in early drafts by using src_a/src_b directly at SZ_LONG.

    .text
    .org 0

_start:
    | ── Test 1: MULS.W #-3, D0  (imm form) ─────────────────────────
    | Load D0 with dirty upper bits — MULS.W must read only low word
    | of D0 (interpreted as signed -5) and sign-extend.  Product = +15.
    move.l  #0xAAAAFFFB, %d0                | upper = 0xAAAA, low = -5
    muls.w  #-3, %d0                         | D0 = +15 = 0x0000000F
    bvs     _fail                             | V must be clear
    bmi     _fail                             | N must be clear (result +)
    beq     _fail                             | Z must be clear
    cmp.l   #15, %d0
    bne     _fail

    | ── Test 2: MULS.W D1, D2  (reg form) ──────────────────────────
    | Both D1.W and D2.W are negative 16-bit; upper 16 bits are dirty
    | and must be ignored.  Product = +15 again (−5 × −3).
    move.l  #0x5555FFFD, %d1                | upper 0x5555, low -3
    move.l  #0xA5A5FFFB, %d2                | upper 0xA5A5, low -5
    muls.w  %d1, %d2                         | D2 = +15
    bvs     _fail
    bmi     _fail
    beq     _fail
    cmp.l   #15, %d2
    bne     _fail

    | ── Test 3: MULS.W producing a large negative product ─────────
    |   0x7FFF × 0xFFFF (= -1) = -32767 = 0xFFFF8001
    |   Upper 16 bits of result must be sign-extended (0xFFFF).
    |   N=1, Z=0, V=0, C=0.
    move.l  #0x00007FFF, %d3
    move.l  #0x0000FFFF, %d4                | -1 in .W
    muls.w  %d4, %d3                         | D3 = 0xFFFF8001
    bvs     _fail
    bpl     _fail                             | N must be set
    beq     _fail
    cmp.l   #0xFFFF8001, %d3
    bne     _fail

    | ── Test 4: MULS.W zero product, Z must be set ────────────────
    move.l  #0xDEAD0000, %d5                | low word = 0
    move.l  #0xBEEF1234, %d6                | low word = 0x1234
    muls.w  %d6, %d5                         | D5 = 0
    bvs     _fail
    bmi     _fail
    bne     _fail                             | Z must be set
    cmp.l   #0, %d5
    bne     _fail

    | ── Test 5: MULS.W with both negative (deep corner) ───────────
    |   0x8000 × 0x8000 = -32768 × -32768 = +1073741824 = 0x40000000.
    move.l  #0x12348000, %d7                | low -32768
    move.l  #0xAAAA8000, %d0                | low -32768
    muls.w  %d7, %d0                         | D0 = 0x40000000
    bvs     _fail
    bmi     _fail                             | N must be clear (result +)
    beq     _fail
    cmp.l   #0x40000000, %d0
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
