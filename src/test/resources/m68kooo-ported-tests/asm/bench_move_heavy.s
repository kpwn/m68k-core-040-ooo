| bench_move_heavy.s — Register renaming stress test
|
| IPC TARGET: ~1.8-2.0 (tests RAT + free list efficiency)
| LOOP COUNT: 50 iterations of 4 MOVEs each = 200 MOVE ops
|
| This benchmark stresses the Register Alias Table (RAT) and physical register
| free list by performing many MOVE operations with changing destination
| registers. Each MOVE allocates a new physical register (since destination
| is different each time).
|
| Register allocation pattern:
|   D0 = <value>
|   D1 = D0          (D1 allocated new phys reg; D0 renamed)
|   D2 = D1          (D2 allocated; D1 renamed)
|   D3 = D2          (D3 allocated; D2 renamed)
|   D4 = D3          (D4 allocated; D3 renamed)
|
| With 48 physical registers and only 8 architectural destinations, we should
| avoid free list stalls. If free list becomes empty, instruction dispatch
| blocks until a physical register is freed (at ROB commit).
|
| This test validates that:
|   1. RAT lookup is fast (no critical path)
|   2. Free list has sufficient capacity
|   3. Freeing of old physical regs happens on schedule

    .text
    .org 0

_start:
    move.l  #50, %d7            | loop counter
    move.l  #0x12345678, %d0    | init D0

_loop:
    | Block 1: move chain (D0 → D1 → D2 → D3 → D4 → D5)
    move.l  %d0, %d1
    move.l  %d1, %d2
    move.l  %d2, %d3
    move.l  %d3, %d4
    move.l  %d4, %d5
    move.l  %d5, %d6

    | Block 2: reverse move chain (D6 → D5 → D4 → D3 → D2 → D1)
    move.l  %d6, %d5
    move.l  %d5, %d4
    move.l  %d4, %d3
    move.l  %d3, %d2
    move.l  %d2, %d1

    | Loop control
    sub.l   #1, %d7
    bne     _loop

    | Prepare PASS sentinel
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)

_halt:
    stop    #0x2700
    bra     _halt
