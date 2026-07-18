| fuse_addq_beq.s — test ADDQ.L #1,Dn + Bcc fusion (task #113)
|
| Less common than SUBQ+BNE in loops, but hand-written code or toolbox
| callers do use ADDQ.L #1,Dn followed by Bcc.  Our fusion only fires
| for #1 (the common counter-increment idiom).

    .text
    .org 0

_start:
    | ── Test 1: ADDQ #1,D0 + BNE (D0=-1 → 0 → Z=1 → BNE not taken) ──
    moveq   #-1, %d0
    addq.l  #1, %d0
    bne     _fail          | Z=1 → not taken
    | Now verify we fell through with Z=1
    beq     _t1_ok
    bra     _fail
_t1_ok:

    | ── Test 2: ADDQ #1,D0 + BEQ (D0=0 → 1 → Z=0 → BEQ not taken) ──
    moveq   #0, %d1
    addq.l  #1, %d1
    beq     _fail          | Z=0 → not taken
    bne     _t2_ok         | Z=0 → taken
    bra     _fail
_t2_ok:

    | ── Test 3: ADDQ #1,D0 + BMI (D0=max_int → overflow → N=1, V=1) ──
    move.l  #0x7FFFFFFF, %d2
    addq.l  #1, %d2
    bmi     _t3_ok         | N=1 → taken
    bra     _fail
_t3_ok:

    | ── Test 4: ADDQ #1,D0 + BVS (same overflow → V=1 → BVS takes) ──
    move.l  #0x7FFFFFFF, %d3
    addq.l  #1, %d3
    bvs     _pass          | V=1 → taken
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
