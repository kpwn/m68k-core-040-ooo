| negx_after_neg.s — 64-bit negate using NEG.L + NEGX.L
|
| Hypothesis: NEG.L computes 0 − a and sets X=C = (a != 0).  NEGX.L then
| computes 0 − a − X, so the (NEG,NEGX) pair correctly negates a 64-bit
| value laid out as (low=D0, high=D1).
|
| Case A: negate 0x00000001_00000002
|   NEG.L D0  with D0 = 2 → D0 = 0xFFFFFFFE, X = 1
|   NEGX.L D1 with D1 = 1 → D1 = 0 − 1 − 1 = 0xFFFFFFFE
|   Verify: 0x00000001_00000002 + 0xFFFFFFFE_FFFFFFFE = 0x0_00000000_00000000
|
| Case B: negate 0x00000005_00000000 (low already zero → X=0 after NEG)
|   NEG.L D0  with D0 = 0 → D0 = 0, X = 0
|   NEGX.L D1 with D1 = 5 → D1 = 0 − 5 − 0 = 0xFFFFFFFB
|   Verify: 0x00000005_00000000 + 0xFFFFFFFB_00000000 = 0x1_00000000_00000000
|
| The NEGX Z-propagation rule (Z unchanged if result nonzero, else AND'd
| with prior Z) is exercised but not independently checked here — we
| only test numeric result correctness for both X=0 and X=1 cases.

    .text
    .org 0

_start:
    | ── Case A ──
    move.l  #0x00000002, %d0
    move.l  #0x00000001, %d1
    neg.l   %d0                      | D0 = 0xFFFFFFFE, X = 1
    negx.l  %d1                      | D1 = 0 - 1 - 1 = 0xFFFFFFFE

    move.l  #0xFFFFFFFE, %d7
    cmp.l   %d7, %d0
    bne     _fail
    move.l  #0xFFFFFFFE, %d7
    cmp.l   %d7, %d1
    bne     _fail

    | ── Case B: NEG on 0 leaves X=0 ──
    move.l  #0x00000000, %d0
    move.l  #0x00000005, %d1
    neg.l   %d0                      | D0 = 0, X = 0
    negx.l  %d1                      | D1 = 0 - 5 - 0 = 0xFFFFFFFB

    tst.l   %d0
    bne     _fail
    move.l  #0xFFFFFFFB, %d7
    cmp.l   %d7, %d1
    bne     _fail

_pass:
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
