| eori_imm_ccr.s — task #236 / F4 — EORI #imm.B,CCR via V2 sysop family.
|
| Verifies SYS_EORI_CCR retire path: arch CCR ← arch CCR ^ imm[7:0].
| User-mode legal.  Single-µop UOP_SYS / SYS_EORI_CCR.
|
| Pipeline note: see ori_imm_ccr.s.
| CCR layout: [X(4) N(3) Z(2) V(1) C(0)].
|
| Sequence:
|   1. MOVE.W #0x001F,CCR  — set X=N=Z=V=C=1.
|      EORI    #0x1F,CCR   — flip all 5 → all 0.
|      Bcc:                — Z=0 (BEQ no), V=0 (BVS no), N=0 (BMI no),
|                            C=0 (BCS no).
|   2. MOVE.W #0x0000,CCR  — clear.
|      EORI    #0x05,CCR   — flip Z + C → Z=1, C=1.
|      Bcc:                — Z=1 (BNE no), C=1 (BCC no).
|   3. MOVE.W #0x0001,CCR  — C=1.
|      EORI    #0x01,CCR   — toggle C → C=0.
|      Bcc:                — C=0 (BCS no).
|   4. MOVE.W #0x0000,CCR  — clear.
|      EORI    #0x10,CCR   — flip X → X=1.  Verify via ADDX.
|   5. Pipeline-alive arithmetic.
|
| PASS: 0xC0FFEE00.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    | Phase 1: flip all 5 from set to clear.
    move.w  #0x001F, %ccr            | X=N=Z=V=C=1.
    .word   0x0A3C, 0x001F           | EORI #0x1F,CCR — all → 0.
    beq     _fail                    | Z=0 → BEQ must NOT.
    bvs     _fail                    | V=0.
    bmi     _fail                    | N=0.
    bcs     _fail                    | C=0.

    | Phase 2: clear, then flip Z+C.
    move.w  #0x0000, %ccr
    .word   0x0A3C, 0x0005           | EORI #0x05,CCR — set Z + C.
    bne     _fail                    | Z=1 → BNE must NOT.
    bcc     _fail                    | C=1 → BCC must NOT.

    | Phase 3: toggle C off.
    move.w  #0x0001, %ccr            | C=1.
    .word   0x0A3C, 0x0001           | EORI #0x01,CCR — toggle C → C=0.
    bcs     _fail                    | C=0 → BCS must NOT.

    | Phase 4: flip X.  Verify via ADDX.
    move.w  #0x0000, %ccr
    .word   0x0A3C, 0x0010           | EORI #0x10,CCR — set X.
    moveq   #0, %d0
    moveq   #0, %d1
    addx.l  %d0, %d1
    cmp.l   #1, %d1
    bne     _fail                    | X=1 → d1 = 0+0+1 = 1.

    | Phase 5: pipeline-alive arithmetic.
    move.l  #0x12345678, %d2
    add.l   #0x11111111, %d2
    cmp.l   #0x23456789, %d2
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
