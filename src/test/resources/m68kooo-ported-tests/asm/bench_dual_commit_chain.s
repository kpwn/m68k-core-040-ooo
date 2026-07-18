| bench_dual_commit_chain.s — Directed stress for the 2-uops/cycle
| commit lane.
|
| IPC TARGET: measurable improvement over the single-commit baseline
| on a stream of plain INT µops that (a) have independent sources
| (CCR-bypass allows lane B to issue behind lane A without stall),
| (b) reach ROB head complete at the same cycle, and (c) pass the
| lane-B gate (UOP_INT, not branch/store/SYS, not dual-dst, no A7).
|
| Loop body: 8 independent ADDs per iteration across 4 register
| pairs — 4 hot pairs × (D1->D0, D3->D2, D5->D4, D7->D6) × 2 = 8
| µops per cycle burst.  With dual-retire, head-and-head+1 clear
| together as soon as they complete on the two ALUs.
|
| Register allocation:
|   D0/D1 independent
|   D2/D3 independent
|   D4/D5 independent
|   D6    loop counter
|   D7    scratch

    .text
    .org 0

_start:
    | Independent-chain seeds
    move.l  #0x00000001, %d0
    move.l  #0x00000002, %d1
    move.l  #0x00000003, %d2
    move.l  #0x00000004, %d3
    move.l  #0x00000005, %d4
    move.l  #0x00000006, %d5
    move.l  #0x00000007, %d7
    move.l  #100, %d6

_loop:
    | Eight independent ADDs — each writes CCR (lane B must still
    | retire behind lane A's CCR write) and each pair reads a disjoint
    | source, so issue + commit are both fully parallel.
    add.l   %d1, %d0
    add.l   %d3, %d2
    add.l   %d5, %d4
    add.l   %d1, %d0
    add.l   %d3, %d2
    add.l   %d5, %d4
    add.l   %d7, %d0
    add.l   %d7, %d2

    | Decrement + branch.  BNE reads CCR from the subq; subq is the
    | lane-A producer of CCR; the branch resolves on lane A's ALU.
    sub.l   #1, %d6
    bne     _loop

    | PASS sentinel
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)

_halt:
    bra     _halt
