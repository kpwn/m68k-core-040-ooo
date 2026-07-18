| fpu_fdiv_flush_survive.s — a correct-path, OLDER-tagged in-flight FDIV
| must survive an unrelated YOUNGER branch misprediction's flush.
|
| Root cause under test (fixed in fpu_div.v): the iterative FDIV FSM
| aborted its ENTIRE in-flight 54-cycle computation on ANY flush_en,
| with no tag-aware outside-keep check -- unlike mul_div.v's int-side
| DIV FSM, which compares the in-flight op's captured ROB tag against
| the flush's keep boundary before deciding to squash.  Since this
| FDIV's own ROB tag is strictly OLDER than the mispredicting branch
| (i.e. inside the flush's keep set), a correct implementation must let
| it keep computing across the flush and retire normally.  The buggy
| behaviour silently kills it instead -- the destination FP register
| never gets written and the producing ROB entry never completes,
| which stalls the ROB head forever (this is a liveness bug, not just
| a performance one).
|
| Mechanism:
|   1. FP1 := 8.0, FP2 := 2.0.
|   2. FDIV.X FP2,FP1  (FP1 := FP1/FP2 = 4.0) issues first -- an OLDER
|      ROB tag, 54-cycle iterative latency in fpu_div.v.
|   3. A handful of cheap integer instructions later (a CMP + a cold
|      forward BEQ that a no-history bimodal predictor predicts
|      not-taken but is actually always taken -- same proven idiom as
|      mispredict.s), a guaranteed misprediction fires on a YOUNGER ROB
|      tag while the FDIV above is still well inside its 54-cycle
|      window (the CMP/Bcc chain resolves in a handful of cycles on the
|      independent int pipe).
|   4. Post-fix, the FDIV's tag is inside the flush's keep set (older
|      than the branch) so it must survive; verify FP1 == 4.0 via
|      FMOVE.S FP1,(A0).  The wrong-path fall-through also carries a
|      poison store as a sanity net on ordinary mispredict recovery.
|
| Encodings (bit positions cross-checked against fpu_fsqrt_basic.s /
| fpu_fdiv_wrongpath_prf_stale.s, both already-landed/known-good):
|   FMOVE.S Dn,FPn      0xF200 | ext = 0x4000 | (Dn<<10) | (FPn<<7)
|   FMOVE.S FPn,(A0)    0xF210 | ext = 0x6400 | (FPn<<7)
|   FDIV.X  FPm,FPn     0xF200 | ext = (FPm<<10) | (FPn<<7) | 0x20
|                       (FPn := FPn / FPm)
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0F01 — F-line trap (unexpected decode hole)
|   0xDEAD0F04 — FP1 != 4.0 after the flush: the older, correct-path
|                 FDIV was killed (or corrupted) by the unrelated
|                 younger misprediction -- the bug this test guards
|                 against.
|   0xBAD00BAD — wrong-path poison committed: ordinary misprediction
|                 recovery itself is broken (unrelated pre-existing
|                 invariant; sanity net, not the bug under test).

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ SCRATCH,   0x00020000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C
    lea     SCRATCH, %a0

    | FP1 := 8.0
    move.l  #0x41000000, %d0
    .short  0xF200, 0x4080          | FMOVE.S D0,FP1  (Dn=0,FPn=1)
    | FP2 := 2.0
    move.l  #0x40000000, %d0
    .short  0xF200, 0x4100          | FMOVE.S D0,FP2  (Dn=0,FPn=2)

    | FDIV.X FP2,FP1  ->  FP1 := FP1 / FP2 = 4.0.  OLDER tag, in flight
    | for 54 cycles inside fpu_div.v.
    .short  0xF200, 0x08A0          | FDIV.X FP2,FP1

    | -- A few cheap int instructions later, force a cold, guaranteed
    | misprediction on a YOUNGER branch while the FDIV above is still
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
    | Verify the OLDER FDIV survived the younger branch's flush.
    .short  0xF210, 0x6480          | FMOVE.S FP1,(A0)
    move.l  (%a0), %d1
    cmp.l   #0x40800000, %d1
    bne     _fail_4

    move.l  #0xC0FFEE00, PASS_SENT
    bra     .

_fail_4:
    move.l  #0xDEAD0F04, PASS_SENT
    bra     .

_fline:
    move.l  #0xDEAD0F01, PASS_SENT
    bra     .
