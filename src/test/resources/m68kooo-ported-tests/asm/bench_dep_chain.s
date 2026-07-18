| bench_dep_chain.s — Long sequential dependency chain for ALU latency measurement
|
| IPC TARGET: ~0.8-1.0 (limited by ALU latency and in-flight instruction limit)
| LOOP COUNT: 1 iteration of 20 sequential ADDs = 20 total ALU ops
|
| This benchmark creates a long chain where each ADD depends on the previous:
|   D0 = D0 + D1
|   D0 = D0 + D1
|   ... (20 times)
|
| Since every operation depends on the previous result, only 1 instruction can
| be in-flight at a time, yielding ~1 IPC. This measures the effective ALU
| latency and branch to next ADD turnaround.
|
| Used to calibrate our understanding of ALU pipeline depth.

    .text
    .org 0

_start:
    | Initialize values
    move.l  #0x00000001, %d0
    move.l  #0x00000002, %d1

    | Sequential chain of 20 ADDs (each depends on previous)
    add.l   %d1, %d0
    add.l   %d1, %d0
    add.l   %d1, %d0
    add.l   %d1, %d0
    add.l   %d1, %d0
    add.l   %d1, %d0
    add.l   %d1, %d0
    add.l   %d1, %d0
    add.l   %d1, %d0
    add.l   %d1, %d0
    add.l   %d1, %d0
    add.l   %d1, %d0
    add.l   %d1, %d0
    add.l   %d1, %d0
    add.l   %d1, %d0
    add.l   %d1, %d0
    add.l   %d1, %d0
    add.l   %d1, %d0
    add.l   %d1, %d0
    add.l   %d1, %d0

    | Prepare PASS sentinel
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)

_halt:
    stop    #0x2700
    bra     _halt
