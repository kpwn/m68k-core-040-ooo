| branch_cluster.s — Cluster of 8+ conditional branches back-to-back
|
| Sets up a known CCR state, then issues 8 Bcc forward-jumps each of
| which must take the correct direction.  All are forward — BTB misses
| cold, so each one is a potential mispredict.  After resolution they
| all fall through (no iteration).
|
| Stresses the BTB lookup + redirect rate when many branches are
| back-to-back in the pipeline.  Also stresses ROB branch-entry alloc
| (entries close together).
|
| Strategy: CMP sets flags, then 8 Bcc each to a chained _t0.._t7
| target; if any is mis-taken, we fall into _fail.

    .text
    .org 0

_start:
    | flags: 5 vs 5 → Z=1, C=0, N=0, V=0
    moveq   #5, %d0
    moveq   #5, %d1
    cmp.l   %d1, %d0

    beq     _t0            | Z=1 → taken
    bra     _fail
_t0:
    bne     _fail          | Z=1 → BNE not taken (fall-through)
    bhi     _fail          | C|Z=1 → BHI not taken
    bls     _t1            | C|Z=1 → BLS taken
    bra     _fail
_t1:
    bcc     _t2            | C=0 → BCC taken
    bra     _fail
_t2:
    bcs     _fail          | C=0 → BCS not taken
    bpl     _t3            | N=0 → BPL taken
    bra     _fail
_t3:
    bmi     _fail          | N=0 → BMI not taken
    bvc     _t4            | V=0 → BVC taken
    bra     _fail
_t4:
    bvs     _fail          | V=0 → BVS not taken
    bge     _t5            | (N^V)=0 → BGE taken
    bra     _fail
_t5:
    blt     _fail          | (N^V)=0 → BLT not taken
    | Change flags: 3 vs 7 signed → N=1, C=1 (borrow), V=0
    moveq   #3, %d0
    moveq   #7, %d1
    cmp.l   %d1, %d0       | 3 - 7
    bgt     _fail          | N=1,V=0 → (N^V)=1 → BGT not taken
    ble     _t6            | taken
    bra     _fail
_t6:
    blt     _t7            | (N^V)=1 → taken
    bra     _fail
_t7:
    bmi     _done          | N=1 → taken
    bra     _fail

_done:
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
