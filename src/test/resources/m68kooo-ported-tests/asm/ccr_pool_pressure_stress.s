| ccr_pool_pressure_stress.s — CCR physical-register pool capacity stress
|
| Context (docs/agent_policy.md dead-code audit, task item 3, 2026-07-11):
| ccr_rat.v's PHYS_CCR_SLOTS = 16 (rtl/core/decode/uop_pkg.v).  ROB_DEPTH
| is now 64 (bumped from 32 by task #255/#232).  This test constructs the
| scenario the stale "sized for ROB=32" comment worried about: a single
| long-latency op (DIVU.L, ~34 cycles issue->complete per mul_div.v) sits
| at the ROB head, blocking in-order commit — and therefore blocking
| every CCR phys-slot free (frees only happen at commit) — while a run
| of independent, data-hazard-free flag-writing MOVEQ instructions try
| to dispatch behind it.  A single cold straight-line shot doesn't build
| enough front-end throughput to reach the 16-slot cap (icache/BPU are
| cold, front-end throughput is nowhere near 2-wide), so this loops the
| divide + flag-writer burst 8 times: after iteration 1 warms the icache
| line and trains the loop-closing branch in the BPU/BTB, later
| iterations dispatch close to steady-state 2-wide, giving the 20
| independent MOVEQs per iteration a real chance to outrun the 16-slot
| CCR pool while their iteration's DIVU.L is still in flight.
|
| %d7 is the DBRA outer-loop counter — deliberately kept OUTSIDE the
| flag-writer / divide register set (%d0-%d6) and untouched inside the
| loop body, so outer-loop control never perturbs the CCR-pressure
| pattern being stressed (DBcc reads/writes no condition codes at all).
|
| Confirmed via a temporary (removed after use) unconditional $display
| probe added to ccr_rat.v during this investigation (fires whenever
| `free_bm` — the CCR free-list bitmap — hits all-zero): see the
| investigation note in this file's report.
|
| This is a STRESS/CORRECTNESS test, not a perf regression gate: it only
| asserts that architectural state (each iteration's DIVU.L result and
| the final MOVEQ's value) comes out correct despite the CCR pool
| saturating and the front end stalling on it.  Renaming + the free-list
| reclaim must stay correct under this pressure — no state corruption,
| just backpressure.

    .text
    .org 0

_start:
    move.l  #7, %d7                   | outer loop count (DBRA: 8 iterations)

_outer:
    | Long-latency op anchoring the ROB head: DIVU.L single-dest
    | (Dq==Dr collapsed form, immediate divisor), ~34 cycles
    | issue->complete per mul_div.v.  Nothing below depends on its
    | result, so dispatch of everything that follows is limited only
    | by resource availability (ROB slots, CCR phys slots), not by
    | data hazards.
    move.l  #100000, %d6
    divu.l  #7, %d6                   | D6 = 100000 / 7 = 14285

    | 20 independent flag-writing MOVEQs (20 > PHYS_CCR_SLOTS=16).
    | Each is a WAW-only hazard on its destination (renaming handles
    | this trivially); none reads a register another writes, so all
    | 20 are execution-independent and limited purely by dispatch +
    | CCR-pool resource availability while the DIVU.L blocks commit.
    moveq   #1, %d0
    moveq   #2, %d1
    moveq   #3, %d2
    moveq   #4, %d3
    moveq   #5, %d4
    moveq   #6, %d5
    moveq   #7, %d0
    moveq   #8, %d1
    moveq   #9, %d2
    moveq   #10, %d3
    moveq   #11, %d4
    moveq   #12, %d5
    moveq   #13, %d0
    moveq   #14, %d1
    moveq   #15, %d2
    moveq   #16, %d3
    moveq   #17, %d4
    moveq   #18, %d5
    moveq   #19, %d0
    moveq   #20, %d5

    | Functional check every iteration: DIVU.L result must be intact
    | (commit ordering / free-list reclaim correctness under CCR-pool
    | pressure), and the last MOVEQ's value must have landed correctly.
    cmp.l   #14285, %d6
    bne     _fail
    cmp.l   #20, %d5
    bne     _fail

    dbra    %d7, _outer

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
    bra     _halt
