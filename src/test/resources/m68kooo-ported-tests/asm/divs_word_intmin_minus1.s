| divs_word_intmin_minus1.s — DIVS.W INT_MIN / -1, and how it differs
| from the .L sibling.
|
| BUG FIXED HERE (silent wrong answer, no trap):
| mul_div.v only ever short-circuited INT_MIN/-1 for DIVS.**L**
| (`divsl_special`, gated on is_divl_signed).  DIVS.W fell through to
| the generic divider, formed raw quotient 0x8000_0000, tripped
| divw_s_overflow, and returned the DESTINATION UNCHANGED with V=1.
| Measured pre-fix: D2 = 0x80000000, SR = 0x270A.  Musashi gives
| D2 = 0x00000000, SR = 0x2704.  Nothing trapped; the wrong value just
| flowed on.
|
| Musashi v4.60 is the golden model here — tb/models/musashi/m68k_in.c,
| `M68KMAKE_OP(divs, 16, ., d)` at line 4383 and `(divs, 16, ., .)` at
| line 4421.  Both do:
|     if (*r_dst == 0x80000000 && src == -1) {
|         FLAG_Z = 0;            /* Musashi: FLAG_Z == 0 means Z SET   */
|         FLAG_N = NFLAG_CLEAR;
|         FLAG_V = VFLAG_CLEAR;
|         FLAG_C = CFLAG_CLEAR;
|         *r_dst = 0;            /* the WHOLE 32-bit register          */
|         return;
|     }
|
| So the two widths give DIFFERENT architectural results, and this test
| pins both so that a "fix" which copies the .L arm onto .W fails:
|
|   DIVS.W  INT_MIN / -1  ->  dest = 0x00000000, Z=1, N=0, V=0, C=0
|   DIVS.L  INT_MIN / -1  ->  dest = 0x80000000, Z=0, N=1, V=0, C=0
|
| X is preserved by both (DIV does not write X).
|
| Also pinned: DIVU.W has NO such special case in Musashi
| (`M68KMAKE_OP(divu, 16, ., d)`, line 4450) — any quotient >= 0x10000
| is a plain overflow — and near-miss operands must still take the
| ordinary overflow / normal paths.

    .text
    .org 0

_start:
    | ── Test 1: DIVS.W INT_MIN / -1, register source ───────────────
    | X and C pre-set so a "flags untouched" regression is visible:
    | X must survive, C must be cleared.
    move.l  #0x80000000, %d2
    move.w  #-1, %d3
    move.w  #0x0011, %ccr                 | X=1, C=1 — set LAST, right
                                          | before the divide
    divs.w  %d3, %d2
    bvs     _fail                          | V must be CLEAR (pre-fix: set)
    bmi     _fail                          | N must be clear
    bne     _fail                          | Z must be SET
    bcs     _fail                          | C must be clear
    tst.l   %d2
    bne     _fail                          | whole 32-bit dest must be 0
    | X survives: ADDX #0 style check — SUBX %d0,%d0 leaves -1 if X set.
    moveq   #0, %d0
    subx.l  %d0, %d0                       | D0 = 0-0-X = -1 iff X was 1
    cmp.l   #-1, %d0
    bne     _fail

    | ── Test 2: same operands, immediate source ────────────────────
    move.l  #0x80000000, %d4
    divs.w  #-1, %d4
    bvs     _fail
    bne     _fail                          | Z set
    tst.l   %d4
    bne     _fail

    | ── Test 3: the .L sibling keeps its OWN, different answer ─────
    | DIVS.L INT_MIN/-1 -> 0x80000000 with N set, Z clear, V clear.
    | If the .W arm were applied to .L this would read 0 / Z set.
    move.l  #0x80000000, %d5
    move.l  #-1, %d6
    divs.l  %d6, %d5
    bvs     _fail                          | V clear (Musashi: no ovf check)
    beq     _fail                          | Z must be CLEAR
    bpl     _fail                          | N must be SET
    cmp.l   #0x80000000, %d5
    bne     _fail

    | ── Test 4: near miss — INT_MIN / +1 still overflows ───────────
    | Quotient -2^31 does not fit a signed 16-bit quotient.
    move.l  #0x80000000, %d0
    move.w  #1, %d1
    divs.w  %d1, %d0
    bvc     _fail                          | V must be SET
    cmp.l   #0x80000000, %d0
    bne     _fail                          | dest preserved on overflow

    | ── Test 5: near miss — (INT_MIN+1) / -1 still overflows ───────
    | Quotient +2^31-1 is still far too wide for 16 bits.
    move.l  #0x80000001, %d0
    move.w  #-1, %d1
    divs.w  %d1, %d0
    bvc     _fail
    cmp.l   #0x80000001, %d0
    bne     _fail

    | ── Test 6: -32768 / -1 overflows (+32768 needs 17 signed bits) ─
    move.l  #0xFFFF8000, %d0
    move.w  #-1, %d1
    divs.w  %d1, %d0
    bvc     _fail
    cmp.l   #0xFFFF8000, %d0
    bne     _fail

    | ── Test 7: -32767 / -1 = +32767 rem 0 — must NOT overflow ─────
    move.l  #0xFFFF8001, %d0
    move.w  #-1, %d1
    divs.w  %d1, %d0
    bvs     _fail
    cmp.l   #0x00007FFF, %d0               | rem 0 in high half, quot 0x7FFF
    bne     _fail

    | ── Test 8: DIVU.W 0x80000000 / 0xFFFF is ordinary, not special ─
    | Unsigned: 2147483648 / 65535 = 32768 rem 32768 -> 0x8000_8000.
    | The new signed short-circuit must not fire for DIVU.
    move.l  #0x80000000, %d0
    move.w  #0xFFFF, %d1
    divu.w  %d1, %d0
    bvs     _fail
    cmp.l   #0x80008000, %d0
    bne     _fail

    | ── Test 9: DIVU.W 0xFFFFFFFF / 0xFFFF overflows (quot 0x10000) ─
    move.l  #0xFFFFFFFF, %d0
    move.w  #0xFFFF, %d1
    divu.w  %d1, %d0
    bvc     _fail
    cmp.l   #0xFFFFFFFF, %d0
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
