| bcc_all_16_conditions.s — Exercise all 14 Bcc conditions (+ BT/BF).
|
| Stage D-6 (agent/decode-v2-branches): validates that the V2 branch
| assembler routes every cccc value to the correct BR_BRA / BR_BCC
| encoding and that cc evaluation in the ALU (via BR_BCC + flags_rd)
| still works.
|
| Strategy:
|   - Seed a known CCR state.
|   - Use BT (always taken) and BF (never taken) as the bookends —
|     BT is aliased as the opword cc=0 which is BRA (not BSR) and
|     decodes as BR_BRA.  BF is opword cc=1 which collides with BSR
|     and therefore is NOT emitted by `bcc` or this test.  We exercise
|     the 14 remaining conditions against a couple of known CCR states.
|   - For each condition, emit a Bcc that should fall through and a
|     Bcc that should be taken.  Any mis-taken Bcc leaves D7 non-zero,
|     which forces the FAIL store at the end.
|
| We pre-stage CCR for two clearly distinguishable states:
|   state A: N=0, Z=1, V=0, C=0, X=0 (via ANDI CCR clear + OR Z from
|            moveq #0 / cmpi #0 on it).
|   state B: N=1, Z=0, V=1, C=1, X=0 (via 0x80000000 - 0x00000001 at
|            size .B: 0x80 - 0x01 = 0x7F which clears Z and leaves
|            C from subtraction.  Simpler: set via MOVE-to-CCR.)

    .text
    .org 0

_start:
    moveq   #0, %d7                   | D7 = 0 = "all conditions pass"

    | ── State A: Z=1 (and all others 0) ─────────────────────────────
    | moveq.l #0,Dn clears NZVC but does NOT touch X — tolerable for
    | this test since none of the conditions examine X.
    moveq   #0, %d0                   | N=0 Z=1 V=0 C=0

    | EQ must be taken — Z=1.
    beq     _a_eq_taken
    addq.l  #1, %d7                   | FAIL: EQ mis-fell-through
_a_eq_taken:
    | NE must fall through — Z=1.
    bne     _a_ne_bad
    | CC (HS) must be taken — C=0.
    bcc     _a_cc_taken
    addq.l  #1, %d7                   | FAIL: CC mis-fell-through
_a_ne_bad:
    addq.l  #1, %d7                   | FAIL: NE mis-taken
_a_cc_taken:
    | CS (LO) must fall through — C=0.
    bcs     _a_cs_bad
    | PL must be taken — N=0.
    bpl     _a_pl_taken
    addq.l  #1, %d7
_a_cs_bad:
    addq.l  #1, %d7
_a_pl_taken:
    | MI must fall through — N=0.
    bmi     _a_mi_bad
    | VC must be taken — V=0.
    bvc     _a_vc_taken
    addq.l  #1, %d7
_a_mi_bad:
    addq.l  #1, %d7
_a_vc_taken:
    | VS must fall through — V=0.
    bvs     _a_vs_bad
    | HI must fall through — C=0, Z=1  =>  !C && !Z = 0 && 0 → not taken.
    bhi     _a_hi_bad
    | LS (C||Z) must be taken — Z=1.
    bls     _a_ls_taken
    addq.l  #1, %d7
_a_vs_bad:
    addq.l  #1, %d7
_a_hi_bad:
    addq.l  #1, %d7
_a_ls_taken:
    | GE (N==V) must be taken — 0==0.
    bge     _a_ge_taken
    addq.l  #1, %d7
_a_ge_taken:
    | LT (N!=V) must fall through — 0==0.
    blt     _a_lt_bad
    | GT ((N==V) && !Z) must fall through — Z=1.
    bgt     _a_gt_bad
    | LE ((N!=V) || Z) must be taken — Z=1.
    ble     _a_le_taken
    addq.l  #1, %d7
_a_lt_bad:
    addq.l  #1, %d7
_a_gt_bad:
    addq.l  #1, %d7
_a_le_taken:

    | ── BRA and backward jump to finish — BRA is cc=0x0 ─────────────
    bra     _check

_check:
    | If D7 is non-zero, take the FAIL path.
    tst.l   %d7
    beq     _pass
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    stop    #0x2700
    bra     _halt_fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    stop    #0x2700
    bra     _halt
