| fpu_fcmp_fpcc_sign_of_zero.s — FCMP.FPCC must carry the SIGN OF ZERO.
|
| Why this test exists (task #155):
|
| FCMP does not set FPSR.FPCC.N from a "destination < source" predicate.  It
| sets N from the SIGN BIT of the difference dst-src, exactly like every other
| FP result.  The two only disagree when that difference is a NEGATIVE ZERO —
| and then N and Z are BOTH set.  The RTL used to compute N as a pure
| less-than, so it reported Z alone and lost the sign.  `make fuzz-deep` was
| RED with 15 FPSR-only mismatches (rtl 0x04000000 vs musashi 0x0c000000),
| every one of them containing an FCMP.
|
| The sign of that zero is ROUNDING-MODE DEPENDENT, which is the part that is
| easy to get wrong and impossible to see with FPCR left at its reset value.
| Musashi/softfloat's exact-cancellation path is literally
|     return packFloatx80( float_rounding_mode == float_round_down, 0, 0 );
| so ANY pair of equal same-signed operands — ordinary nonzero numbers
| included — compares to a NEGATIVE zero while FPCR.RND selects
| round-toward-minus-infinity (FPCR[5:4] == 2'b10), and to a positive zero in
| the other three modes.  Cases 1/2 below are the same compare run under two
| rounding modes and they must differ.
|
| Mixed-sign zeros take softfloat's ADD path instead, which has no
| rounding-mode term and keeps the IEEE sign of the destination:
|     (-0.0) - (+0.0) = -0.0   ->  N + Z
|     (+0.0) - (-0.0) = +0.0   ->  Z
|
| Infinities bypass the subtraction and take Musashi's explicit is_inf()
| table: equal infinities compare as a zero carrying the operands' sign, and
| the I bit is NEVER set by a compare — not for equal infinities, not for
| inf-vs-finite.  Cases 7-9 pin that down so a future "either operand is
| infinite -> set I" shortcut cannot come back.
|
| FPSR condition-code byte: bit 27 = N, 26 = Z, 25 = I, 24 = NAN.
| Note the gas operand order: `fcmp.x %fpS, %fpD` computes fpD - fpS.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEADFC21 — equal +numbers, round-to-nearest: expected Z alone
|   0xDEADFC22 — equal +numbers, round-to-minus-inf: expected N+Z
|   0xDEADFC23 — equal +zeros,   round-to-minus-inf: expected N+Z
|   0xDEADFC24 — (-0.0)-(+0.0): expected N+Z
|   0xDEADFC25 — (+0.0)-(-0.0): expected Z alone
|   0xDEADFC26 — equal -numbers, round-to-nearest: expected Z alone
|   0xDEADFC27 — 1.0 vs 2.0: expected N alone
|   0xDEADFC28 — +inf vs +inf: expected Z alone (I must stay clear)
|   0xDEADFC29 — -inf vs -inf: expected N+Z    (I must stay clear)
|   0xDEADFC2A — +inf vs finite: expected all clear (I must stay clear)
|   0xDEADFC2B — NaN operand: expected NAN alone

    .text
    .org 0

    .equ PASS_SENT,  0xFFFF0000
    .equ FPCC_MASK,  0x0F000000
    .equ FPCC_N,     0x08000000
    .equ FPCC_Z,     0x04000000
    .equ FPCC_NAN,   0x01000000
    .equ FPCR_RN,    0x00000000     | RND = 00, round to nearest
    .equ FPCR_RM,    0x00000020     | RND = 10, round toward -infinity

_start:
    lea     0x00010000, %a7

    | ── case 1: equal positive numbers, round-to-nearest → +0.0 → Z ────
    move.l  #FPCR_RN, %d0
    fmove.l %d0, %fpcr
    move.l  #0x40490FDB, %d0        | +3.14159265 single
    fmove.s %d0, %fp0
    fmove.s %d0, %fp1
    fcmp.x  %fp0, %fp1              | fp1 - fp0 = +0.0
    fmove.l %fpsr, %d1
    and.l   #FPCC_MASK, %d1
    cmp.l   #FPCC_Z, %d1
    bne     fail_21

    | ── case 2: SAME compare, round-toward-minus-inf → -0.0 → N+Z ─────
    move.l  #FPCR_RM, %d0
    fmove.l %d0, %fpcr
    move.l  #0x40490FDB, %d0
    fmove.s %d0, %fp0
    fmove.s %d0, %fp1
    fcmp.x  %fp0, %fp1              | fp1 - fp0 = -0.0 under RM
    fmove.l %fpsr, %d1
    and.l   #FPCC_MASK, %d1
    cmp.l   #(FPCC_N+FPCC_Z), %d1
    bne     fail_22

    | ── case 3: equal +zeros under RM also cancel to -0.0 → N+Z ───────
    move.l  #0x00000000, %d0        | +0.0
    fmove.s %d0, %fp0
    fmove.s %d0, %fp1
    fcmp.x  %fp0, %fp1
    fmove.l %fpsr, %d1
    and.l   #FPCC_MASK, %d1
    cmp.l   #(FPCC_N+FPCC_Z), %d1
    bne     fail_23

    | ── back to round-to-nearest for the rest ─────────────────────────
    move.l  #FPCR_RN, %d0
    fmove.l %d0, %fpcr

    | ── case 4: (-0.0) - (+0.0) = -0.0 → N+Z (no rounding-mode term) ──
    move.l  #0x00000000, %d0        | +0.0 → source
    fmove.s %d0, %fp0
    move.l  #0x80000000, %d0        | -0.0 → destination
    fmove.s %d0, %fp1
    fcmp.x  %fp0, %fp1              | fp1 - fp0 = (-0.0) - (+0.0) = -0.0
    fmove.l %fpsr, %d1
    and.l   #FPCC_MASK, %d1
    cmp.l   #(FPCC_N+FPCC_Z), %d1
    bne     fail_24

    | ── case 5: (+0.0) - (-0.0) = +0.0 → Z alone ──────────────────────
    move.l  #0x80000000, %d0        | -0.0 → source
    fmove.s %d0, %fp0
    move.l  #0x00000000, %d0        | +0.0 → destination
    fmove.s %d0, %fp1
    fcmp.x  %fp0, %fp1
    fmove.l %fpsr, %d1
    and.l   #FPCC_MASK, %d1
    cmp.l   #FPCC_Z, %d1
    bne     fail_25

    | ── case 6: equal NEGATIVE numbers, RN → +0.0 → Z alone ───────────
    move.l  #0xC0490FDB, %d0        | -3.14159265 single
    fmove.s %d0, %fp0
    fmove.s %d0, %fp1
    fcmp.x  %fp0, %fp1
    fmove.l %fpsr, %d1
    and.l   #FPCC_MASK, %d1
    cmp.l   #FPCC_Z, %d1
    bne     fail_26

    | ── case 7: ordinary ordered compare, dst < src → N alone ─────────
    move.l  #0x40000000, %d0        | +2.0 → source
    fmove.s %d0, %fp0
    move.l  #0x3F800000, %d0        | +1.0 → destination
    fmove.s %d0, %fp1
    fcmp.x  %fp0, %fp1              | 1.0 - 2.0 = -1.0
    fmove.l %fpsr, %d1
    and.l   #FPCC_MASK, %d1
    cmp.l   #FPCC_N, %d1
    bne     fail_27

    | ── case 8: +inf vs +inf → Z alone, I MUST stay clear ─────────────
    move.l  #0x7F800000, %d0        | +inf
    fmove.s %d0, %fp0
    fmove.s %d0, %fp1
    fcmp.x  %fp0, %fp1
    fmove.l %fpsr, %d1
    and.l   #FPCC_MASK, %d1
    cmp.l   #FPCC_Z, %d1
    bne     fail_28

    | ── case 9: -inf vs -inf → N+Z, I MUST stay clear ─────────────────
    move.l  #0xFF800000, %d0        | -inf
    fmove.s %d0, %fp0
    fmove.s %d0, %fp1
    fcmp.x  %fp0, %fp1
    fmove.l %fpsr, %d1
    and.l   #FPCC_MASK, %d1
    cmp.l   #(FPCC_N+FPCC_Z), %d1
    bne     fail_29

    | ── case 10: +inf destination vs finite source → all clear ────────
    move.l  #0x3F800000, %d0        | +1.0 → source
    fmove.s %d0, %fp0
    move.l  #0x7F800000, %d0        | +inf → destination
    fmove.s %d0, %fp1
    fcmp.x  %fp0, %fp1
    fmove.l %fpsr, %d1
    and.l   #FPCC_MASK, %d1
    cmp.l   #0x00000000, %d1
    bne     fail_2a

    | ── case 11: quiet NaN operand → NAN alone ────────────────────────
    move.l  #0x7FC00000, %d0        | +qNaN
    fmove.s %d0, %fp0
    move.l  #0x3F800000, %d0        | +1.0
    fmove.s %d0, %fp1
    fcmp.x  %fp0, %fp1
    fmove.l %fpsr, %d1
    and.l   #FPCC_MASK, %d1
    cmp.l   #FPCC_NAN, %d1
    bne     fail_2b

    move.l  #0xC0FFEE00, %d0
    move.l  %d0, PASS_SENT
    bra     .

fail_21:
    move.l  #0xDEADFC21, %d0
    move.l  %d0, PASS_SENT
    bra     .
fail_22:
    move.l  #0xDEADFC22, %d0
    move.l  %d0, PASS_SENT
    bra     .
fail_23:
    move.l  #0xDEADFC23, %d0
    move.l  %d0, PASS_SENT
    bra     .
fail_24:
    move.l  #0xDEADFC24, %d0
    move.l  %d0, PASS_SENT
    bra     .
fail_25:
    move.l  #0xDEADFC25, %d0
    move.l  %d0, PASS_SENT
    bra     .
fail_26:
    move.l  #0xDEADFC26, %d0
    move.l  %d0, PASS_SENT
    bra     .
fail_27:
    move.l  #0xDEADFC27, %d0
    move.l  %d0, PASS_SENT
    bra     .
fail_28:
    move.l  #0xDEADFC28, %d0
    move.l  %d0, PASS_SENT
    bra     .
fail_29:
    move.l  #0xDEADFC29, %d0
    move.l  %d0, PASS_SENT
    bra     .
fail_2a:
    move.l  #0xDEADFC2A, %d0
    move.l  %d0, PASS_SENT
    bra     .
fail_2b:
    move.l  #0xDEADFC2B, %d0
    move.l  %d0, PASS_SENT
    bra     .
