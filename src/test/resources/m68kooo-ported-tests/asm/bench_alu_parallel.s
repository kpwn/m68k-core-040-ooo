| bench_alu_parallel.s — Maximum ALU throughput with minimal stalls
|
| IPC TARGET: ~1.9-2.0 (sustained dual-ALU throughput)
| LOOP COUNT: 40 iterations of 6 independent ADDs per iteration
|
| This benchmark maximizes ALU utilization by creating many independent
| instruction streams that can be dual-issued from the two ALUs:
|
| Loop structure:
|   add.l D0, D1      (pair A)
|   add.l D2, D3      (pair B)
|   add.l D4, D5      (pair C — extra op for 3-cycle fetch)
|   (loop control with sub + bne)
|
| With 3 independent ADD pairs and only 2 ALUs, some will queue in iq_int,
| but dispatch should remain at 1-2 ops/cycle. The goal is to measure if
| dual-issue dispatch is working correctly.
|
| Expected result:
|   - 40 iterations × 6 adds + loop overhead
|   - Total ops ~240-250
|   - Ideal cycles: 240/2 = 120 + small overhead
|   - Expected actual: 150-180 cycles (accounting for dispatch width, ROB, etc)

    .text
    .org 0

_start:
    | Initialize 6 independent value pairs
    move.l  #0x00000001, %d0
    move.l  #0x00000002, %d1
    move.l  #0x00000003, %d2
    move.l  #0x00000004, %d3
    move.l  #0x00000005, %d4
    move.l  #0x00000006, %d5
    move.l  #40, %d6            | loop counter

_loop:
    | ALU pair A: independent
    add.l   %d1, %d0
    | ALU pair B: independent
    add.l   %d3, %d2
    | ALU pair C: independent (may have to queue if ALU busy)
    add.l   %d5, %d4
    | Extra ADD to fill pipeline
    add.l   %d0, %d1

    | Loop control
    sub.l   #1, %d6
    bne     _loop

    | Prepare PASS sentinel
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)

_halt:
    stop    #0x2700
    bra     _halt
