| bench_fullpipe.s — Full pipeline stress: mixed ALU, memory, and branches
|
| IPC TARGET: ~1.6-1.9 (tests sustained multi-functional throughput)
| LOOP COUNT: 30 iterations with mixed ops
|
| This benchmark combines independent operations from multiple functional units:
|   1. ALU: ADD, CMP operations
|   2. LSU: LOAD, STORE operations
|   3. BRANCH: conditional branches based on comparisons
|
| Within each loop iteration, we interleave:
|   - Independent ADDs (can issue in parallel)
|   - Memory access (load-use chain to LSU)
|   - Comparison and conditional branch (CCR path)
|
| The intent is to keep all pipelines busy simultaneously:
|   - Both ALUs busy with ALU ops
|   - LSU handling load/store
|   - Branch unit resolving in-flight branches
|
| Loop counter is stored in memory and loaded/compared each iteration,
| simulating a typical program with mixed workloads.
|
| With proper OoO scheduling, we expect near-peak throughput.

    .text
    .org 0

_start:
    | Pre-initialize counter at 0x100200
    move.l  #30, %d0
    lea     0x00100200, %a5
    move.l  %d0, (%a5)          | mem[0x100200] = 30

    | Initialize work registers
    move.l  #0x00000001, %d0
    move.l  #0x00000002, %d1
    move.l  #0x00000004, %d2
    move.l  #0x00000008, %d3
    move.l  #0x12345678, %d4

_loop:
    | ─── ALU group: independent operations ────────────────────────────
    | These have no dependencies and can dual-issue
    add.l   %d1, %d0            | D0 += D1
    add.l   %d3, %d2            | D2 += D3 (independent)

    | ─── Memory group: load-use chain ────────────────────────────────
    | Load counter from memory, depends on LEA which is independent
    lea     0x00100200, %a0
    move.l  (%a0), %d4          | Load counter into D4

    | ─── ALU group: more operations ────────────────────────────────
    sub.l   #1, %d4             | Decrement counter (depends on load)
    cmp.l   #0, %d4             | Compare with 0 (depends on sub)

    | ─── Store: commit counter back ────────────────────────────────
    move.l  %d4, (%a0)          | Store updated counter

    | ─── Branch: depends on comparison ────────────────────────────
    bne     _loop               | Loop if counter != 0 (depends on cmp)

    | Prepare PASS sentinel
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)

_halt:
    stop    #0x2700
    bra     _halt
