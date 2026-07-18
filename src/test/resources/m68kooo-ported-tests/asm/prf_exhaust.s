| prf_exhaust.s — Deep dependency chain to exhaust the PRF free list
|
| With 48 physical int regs and 17 architectural slots, only ~32 phys
| regs are on the free pool.  A chain of 40 ADDQ's on D0 creates 40
| unique write renames; once the free list wraps, committed phys regs
| must be returned to the pool and the chain must still compute the
| correct scalar sum.
|
| If rename freeing (RAT commit) is broken or the free bitmap drifts on
| rollback, we expect either a hang (no free reg), or a wrong final D0.
|
| Math: D0 starts at 1, incremented 40× → expect 41.

    .text
    .org 0

_start:
    moveq   #1, %d0

    | 40 dependent increments — each issues after the prior commits
    | (or at least after its CDB broadcast).  All target D0 → forces
    | continuous rename alloc + retire of D0 physregs.
    addq.l  #1, %d0      | 2
    addq.l  #1, %d0      | 3
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0      | 10
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0      | 20
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0      | 30
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0
    addq.l  #1, %d0      | 40 increments done → D0 = 41

    cmp.l   #41, %d0
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d1
    move.l  %d1, (%a0)
_halt_fail:
    bra     _halt_fail
