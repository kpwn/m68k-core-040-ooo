| fpu_fsqrt_flush_survive.s — a correct-path, OLDER-tagged in-flight
| FSQRT must survive an unrelated YOUNGER branch misprediction's flush.
|
| Sibling of fpu_fdiv_flush_survive.s: fpu_sqrt.v had the exact same
| abort-on-any-flush_en bug as fpu_div.v (56-cycle iterative digit-
| recurrence FSM, unconditionally reset to ST_IDLE on any flush_en with
| no tag-aware outside-keep check).  See that test's header for the
| full root-cause writeup; this test just swaps FDIV for FSQRT.
|
| Mechanism:
|   1. FP0 := 4.0.
|   2. FSQRT.X FP0,FP1  (FP1 := sqrt(FP0) = 2.0) issues first -- an
|      OLDER ROB tag, 56-cycle iterative latency in fpu_sqrt.v.
|   3. A handful of cheap integer instructions later, a cold forward
|      BEQ (no-history bimodal predictor predicts not-taken, actually
|      always taken -- same proven idiom as mispredict.s) forces a
|      guaranteed misprediction on a YOUNGER ROB tag while the FSQRT
|      above is still well inside its 56-cycle window.
|   4. Post-fix, the FSQRT's tag is inside the flush's keep set (older
|      than the branch) so it must survive; verify FP1 == 2.0 via
|      FMOVE.S FP1,(A0).
|
| Encodings (cross-checked against fpu_fsqrt_basic.s):
|   FMOVE.S Dn,FPn      0xF200 | ext = 0x4000 | (Dn<<10) | (FPn<<7)
|   FMOVE.S FPn,(A0)    0xF210 | ext = 0x6400 | (FPn<<7)
|   FSQRT.X FPm,FPn     0xF200 | ext = (FPm<<10) | (FPn<<7) | 0x04
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0F01 — F-line trap (unexpected decode hole)
|   0xDEAD0F05 — FP1 != 2.0 after the flush: the older, correct-path
|                 FSQRT was killed (or corrupted) by the unrelated
|                 younger misprediction -- the bug this test guards
|                 against.
|   0xBAD00BAD — wrong-path poison committed: ordinary misprediction
|                 recovery itself is broken (unrelated pre-existing
|                 invariant; sanity net, not the bug under test).
| ENCODING CORRECTED 2026-09-12: the FMOVE.S loads below carried source specifier
| 000 = LONG WORD INTEGER (ext 0x40xx) instead of 001 = SINGLE (ext 0x44xx), so the
| bit pattern was loaded as a 32-bit integer and every downstream value was wrong.
| Same defect as fpu_fint_basic et al (commit 328de7d9). The core is correct.
|

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ SCRATCH,   0x00020000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C
    lea     SCRATCH, %a0

    | FP0 := 4.0
    move.l  #0x40800000, %d0
    .short  0xF200, 0x4400          | FMOVE.S D0,FP0  (Dn=0,FPn=0)

    | FSQRT.X FP0,FP1  ->  FP1 := sqrt(FP0) = 2.0.  OLDER tag, in
    | flight for 56 cycles inside fpu_sqrt.v.
    .short  0xF200, 0x0084          | FSQRT.X FP0,FP1

    | -- A few cheap int instructions later, force a cold, guaranteed
    | misprediction on a YOUNGER branch while the FSQRT above is still
    | mid-iteration.
    moveq   #0x42, %d1
    moveq   #0x42, %d2
    cmp.l   %d2, %d1                | equal -> Z=1
    lea     0x00100000, %a2
    lea     0x00100010, %a3
    lea     0x00100020, %a4
    beq     _joined                 | Z=1 -> taken; cold predictor says NTKN

    | wrong-path poison (must NOT commit)
    move.l  #0xBAD00BAD, %d5
    move.l  %d5, PASS_SENT
    bra     .

_joined:
    | Verify the OLDER FSQRT survived the younger branch's flush.
    .short  0xF210, 0x6480          | FMOVE.S FP1,(A0)
    move.l  (%a0), %d1
    cmp.l   #0x40000000, %d1
    bne     _fail_5

    move.l  #0xC0FFEE00, PASS_SENT
    bra     .

_fail_5:
    move.l  #0xDEAD0F05, PASS_SENT
    bra     .

_fline:
    move.l  #0xDEAD0F01, PASS_SENT
    bra     .
