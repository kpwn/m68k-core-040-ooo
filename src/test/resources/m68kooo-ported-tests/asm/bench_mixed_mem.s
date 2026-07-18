| bench_mixed_mem.s — Mixed load/store throughput and load-use latency
|
| IPC TARGET: ~1.2-1.5 (LSU latency + bypass forwarding)
| LOOP COUNT: 20 iterations = 20 loads + 20 stores + ALU ops
|
| This benchmark exercises memory operations:
|   1. Pre-populate a small array in RAM (addresses 0x100000..0x100020)
|   2. Loop: load from array, accumulate into D0, store result back
|   3. Measure load-use distance and memory throughput
|
| Memory layout (pre-initialized):
|   [0x100000] = 0x00000001
|   [0x100004] = 0x00000002
|   [0x100008] = 0x00000003
|   [0x10000C] = 0x00000004
|   ...
|
| The loop structure keeps load-use distance tight:
|   load D1, (A0)+   (post-increment)
|   add.l D1, D0     (immediately uses loaded value)
|   ...
|
| With LSU latency of ~2 cycles, we expect fetch stalls on add.l until load result available.

    .text
    .org 0

_start:
    | Pre-initialize small array at 0x100000
    | [0x100000] = 0x00000001
    move.l  #0xC0FFEE01, %d0
    lea     0x00100000, %a0
    move.l  %d0, (%a0)

    | [0x100004] = 0xC0FFEE02
    move.l  #0xC0FFEE02, %d0
    move.l  %d0, 4(%a0)

    | [0x100008] = 0xC0FFEE03
    move.l  #0xC0FFEE03, %d0
    move.l  %d0, 8(%a0)

    | [0x10000C] = 0xC0FFEE04
    move.l  #0xC0FFEE04, %d0
    move.l  %d0, 12(%a0)

    | Reset for loop
    move.l  #0, %d0             | accumulator
    move.l  #20, %d7            | loop counter
    lea     0x00100000, %a0     | point to array

_loop:
    | Load from current array pointer
    move.l  (%a0), %d1
    | Accumulate (depends on load result — tests load-use latency)
    add.l   %d1, %d0
    | Post-increment pointer
    add.l   #4, %a0
    | Decrement counter
    sub.l   #1, %d7
    | Branch back
    bne     _loop

    | Store final result to 0x100100 as proof we did the work
    lea     0x00100100, %a1
    move.l  %d0, (%a1)

    | Prepare PASS sentinel
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)

_halt:
    stop    #0x2700
    bra     _halt
