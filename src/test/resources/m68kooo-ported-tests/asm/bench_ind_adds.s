| bench_ind_adds.s — Independent parallel ADD chains for IPC measurement
|
| IPC TARGET: ~1.8 (dual-issue ALU with minimal stalls)
| LOOP COUNT: 50 iterations of 4 independent ADD pairs = 400 total ALU ops
|
| This benchmark creates independent chains of ADD operations that have no
| register dependencies between them. With dual-issue ALU and perfect issue
| rate, should sustain ~2 IPC (2 ALUs × 1 cycle).
|
| Register allocation:
|   D0:D1 = pair 0 (independent of all others)
|   D2:D3 = pair 1 (independent of all others)
|   D4:D5 = pair 2 (independent of all others)
|   D6:D7 = loop counter
|
| Expected to measure OoO core effectiveness at pure ALU throughput.

    .text
    .org 0

_start:
    | Initialize base values
    move.l  #0x00000001, %d0
    move.l  #0x00000002, %d1
    move.l  #0x00000003, %d2
    move.l  #0x00000004, %d3
    move.l  #0x00000005, %d4
    move.l  #0x00000006, %d5
    move.l  #50, %d6            | loop counter

_loop:
    | Pair 0: D0 += D1
    add.l   %d1, %d0
    | Pair 1: D2 += D3 (independent)
    add.l   %d3, %d2
    | Pair 2: D4 += D5 (independent)
    add.l   %d5, %d4

    | Decrement loop counter and branch
    sub.l   #1, %d6
    bne     _loop

    | Prepare PASS sentinel
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)

_halt:
    stop    #0x2700
    bra     _halt
