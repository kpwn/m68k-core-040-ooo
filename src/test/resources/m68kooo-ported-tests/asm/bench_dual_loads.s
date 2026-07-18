| bench_dual_loads.s — Independent parallel LOAD throughput
|
| Purpose: exercises back-to-back independent LOADs from different
|   addresses with NO dependency between them.  Today (single-LSU)
|   this must serialise 1 LOAD / cycle through the LSU FSM + dcache.
|   Post-task-#217 (dual-LSU lane) it should reach ~2 LOADs / cycle
|   when both lanes accept independent loads.
|
| LOOP COUNT: 40 iterations × 4 independent loads = 160 loads.
|
| Memory layout (pre-initialised by the prologue):
|   [0x00100000..0x0010000C] = 0xC0FFEE01..0xC0FFEE04
|   [0x00100100..0x0010010C] = 0xDEADBEE1..0xDEADBEE4
|
| Register allocation:
|   A0 = base pointer #1
|   A1 = base pointer #2
|   D0..D3 = load destinations (independent)
|   D4     = scratch accumulator (to keep loads live)
|   D7     = loop counter

    .text
    .org 0

_start:
    | Pre-populate array #1 at 0x100000
    move.l  #0xC0FFEE01, %d0
    lea     0x00100000, %a0
    move.l  %d0, (%a0)
    move.l  #0xC0FFEE02, %d0
    move.l  %d0, 4(%a0)
    move.l  #0xC0FFEE03, %d0
    move.l  %d0, 8(%a0)
    move.l  #0xC0FFEE04, %d0
    move.l  %d0, 12(%a0)

    | Pre-populate array #2 at 0x100100
    move.l  #0xDEADBEE1, %d0
    lea     0x00100100, %a1
    move.l  %d0, (%a1)
    move.l  #0xDEADBEE2, %d0
    move.l  %d0, 4(%a1)
    move.l  #0xDEADBEE3, %d0
    move.l  %d0, 8(%a1)
    move.l  #0xDEADBEE4, %d0
    move.l  %d0, 12(%a1)

    | Reset for loop
    lea     0x00100000, %a0
    lea     0x00100100, %a1
    move.l  #0, %d4
    move.l  #40, %d7

_loop:
    | 4 independent LOADs per iter.  No RAW chain between them —
    | each writes a different destination, all read from
    | independent offsets.  A dual-LSU can schedule 2/cycle.
    move.l  0(%a0), %d0
    move.l  4(%a0), %d1
    move.l  0(%a1), %d2
    move.l  4(%a1), %d3

    | Keep the loads alive: accumulate XOR so OoO scheduler
    | can't prune them.
    eor.l   %d0, %d4
    eor.l   %d1, %d4
    eor.l   %d2, %d4
    eor.l   %d3, %d4

    | Decrement counter, branch back
    sub.l   #1, %d7
    bne     _loop

    | Final sanity: store accumulator for visibility
    lea     0x00100200, %a0
    move.l  %d4, (%a0)

    | PASS sentinel
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)

_halt:
    stop    #0x2700
    bra     _halt
