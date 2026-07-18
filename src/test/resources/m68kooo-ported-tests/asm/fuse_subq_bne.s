| fuse_subq_bne.s — test SUBQ.L #1,Dn + BNE fusion (task #113)
|
| The fused op decrements Dn by 1 AND resolves the BNE in one cycle.
| CCR is still written correctly (NZVCX).  Also tests that a subsequent
| CCR-reading Bcc sees the post-fuse CCR.

    .text
    .org 0

_start:
    | ── Test 1: SUBQ #1,D0 + BNE (Dn=3, takes 3 times, exits on 0) ──
    moveq   #3, %d0         | loop count
    moveq   #0, %d1         | iteration counter

_loop1:
    addq.l  #1, %d1         | increment iteration counter (non-fused — BNE below)
    subq.l  #1, %d0         | fused with BNE below
    bne     _loop1

    | After loop: D0=0, D1=3.  CCR should be Z=1 (from final SUBQ).
    cmp.l   %d0, %d0        | fused with BEQ — Z=1
    beq     _t1_ok
    bra     _fail
_t1_ok:
    | Verify D1 == 3 via CMP + BNE
    moveq   #3, %d2
    cmp.l   %d2, %d1        | fused; Z=1 if equal
    bne     _fail
    beq     _t2_ok
    bra     _fail
_t2_ok:

    | ── Test 2: SUBQ #1,Dn + BEQ (takes when Dn==1, condition becomes Z=1 after sub) ──
    moveq   #1, %d3         | Dn = 1 → after sub = 0 → Z=1 → BEQ takes
    subq.l  #1, %d3
    beq     _t3_ok
    bra     _fail
_t3_ok:

    | ── Test 3: SUBQ #1,Dn + BMI (takes when Dn==0 → result=-1, N=1) ──
    moveq   #0, %d4
    subq.l  #1, %d4
    bmi     _t4_ok
    bra     _fail
_t4_ok:

    | ── Test 4: SUBQ #1,Dn + BPL (takes when Dn==2 → result=1, N=0) ──
    moveq   #2, %d5
    subq.l  #1, %d5
    bpl     _pass
    bra     _fail

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
