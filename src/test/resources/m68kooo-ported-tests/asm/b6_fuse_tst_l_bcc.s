| b6_fuse_tst_l_bcc.s — test TST.L Dn + Bcc fusion overlay.
|
| The fusion overlay converts an unfused TST.L Dn + Bcc pair into a
| single ALU_TST_BR µop in V2's unary_fire block (mirrors the legacy
| decode_0100.vh §721-749 fused row before B6 retirement).  The pair
| exercises the predecode peek-window's fuse_fire_tstl signal and the
| F3-staged overlay in decode.v.
|
| The macro semantics must be bit-identical to TST.L Dn followed by
| an unfused Bcc — flags written: NZVC (no X); the Bcc takes/falls
| through based on those flags.

    .text
    .org 0

_start:
    | ── Test 1: TST.L D0 (==0) + BEQ — should branch ──
    moveq   #0, %d0
    tst.l   %d0
    beq     _t1_ok
    bra     _fail
_t1_ok:

    | ── Test 2: TST.L D0 (==0) + BNE — should NOT branch ──
    moveq   #0, %d0
    tst.l   %d0
    bne     _fail
    | fall through

    | ── Test 3: TST.L D1 (==-1) + BMI — N=1, should branch ──
    moveq   #-1, %d1
    tst.l   %d1
    bmi     _t3_ok
    bra     _fail
_t3_ok:

    | ── Test 4: TST.L D2 (==1) + BPL — N=0, should branch ──
    moveq   #1, %d2
    tst.l   %d2
    bpl     _t4_ok
    bra     _fail
_t4_ok:

    | ── Test 5: TST.L D3 (==1) + BNE.L (32-bit branch displacement) ──
    | Use a far branch that requires .L form so len_bytes covers the
    | extended Bcc length (8 bytes total: TST.L=2 + Bcc.L=6).
    moveq   #1, %d3
    tst.l   %d3
    bne.w   _t5_ok                 | .W form (4 bytes Bcc) → 6 total
    bra     _fail
_t5_ok:

    | ── Test 6: TST.L D4 (==-1) + BLT — N=1 V=0 -> N^V=1 → branch ──
    moveq   #-1, %d4
    tst.l   %d4
    blt     _t6_ok
    bra     _fail
_t6_ok:

_pass:
    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a6)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a6
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a6)
_halt_fail:
    bra     _halt_fail
