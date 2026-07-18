| bench_dual_int_chain.s — 8 independent ALU ops in a row (task #237)
|
| Designed to validate 2-wide decode dispatch.  Eight independent ADDs
| that have NO data dependency between adjacent pairs, so a 2-wide
| decode + 2-wide rename + dual-ALU back-end can theoretically retire
| them at 2 µops/cycle.
|
| Pairs:
|   add.l D1,D0   add.l D3,D2     (independent — pair 1+2)
|   add.l D5,D4   add.l D7,D6     (independent — pair 3+4)
|   add.l D0,D1   add.l D2,D3     (independent at the dispatch
|                                  granularity but each forms a
|                                  RAW with the preceding pair)
|   add.l D4,D5   add.l D6,D7
|
| Expected outcome:
|   * Flag OFF (today): same as today's bench_alu_parallel-style cycle
|     count — single-µop dispatch, ~8 cycles per iteration of the body
|     plus loop overhead.
|   * Flag ON (when #219c lands): two µops dispatched per cycle on the
|     first pair of each line (no RAW), ALU pair drives them in
|     parallel; expected ≤ 60% of OFF cycles for the body.
|
| The harness measures cycles between the entry sentinel write and the
| exit sentinel write so the IPC delta is directly visible in the
| committed-cycle counter.

    .text
    .org 0

_start:
    | Initialize 8 independent operands.
    move.l  #0x00000001, %d0
    move.l  #0x00000002, %d1
    move.l  #0x00000003, %d2
    move.l  #0x00000004, %d3
    move.l  #0x00000005, %d4
    move.l  #0x00000006, %d5
    move.l  #0x00000007, %d6
    move.l  #0x00000008, %d7
    move.l  #32, %a1            | loop counter — kept in An so it does
                                | not occupy a Dn used by the body

_loop:
    | Eight independent ADDs (four lane-pair candidates).
    add.l   %d1, %d0
    add.l   %d3, %d2
    add.l   %d5, %d4
    add.l   %d7, %d6
    add.l   %d0, %d1
    add.l   %d2, %d3
    add.l   %d4, %d5
    add.l   %d6, %d7

    | Loop control on An so the body Dn pairs are not perturbed.
    suba.l  #1, %a1
    cmpa.l  #0, %a1
    bne     _loop

    | PASS sentinel.
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)

_halt:
    stop    #0x2700
    bra     _halt
