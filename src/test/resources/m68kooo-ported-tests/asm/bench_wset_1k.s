| bench_wset_1k.s — cache-RESIDENT working set (D-cache BYPASS variant)
|
| Purpose: the existing bench_*.s corpus barely touches data memory, so
| it cannot show what the L1-D cache is worth.  This bench does: it walks
| a 1 KiB array (32 lines of 32 B — one quarter of the 4 KiB / 4-way
| D-cache) eight times over.  After the first pass every access is a hit,
| so the cached twin (bench_wset_1k_dcache) should be near-immune to DDR
| latency while THIS one — which runs with the D-cache bypassed, exactly
| like every other legacy bench — pays a full memory round trip per load.
|
| This is the BYPASS half of the pair.  Run both:
|     tools/bench_run.sh --sweep "1 5 10 20 40" bench_wset_1k bench_wset_1k_dcache
|
| Array: 0x00200000 .. 0x002003FC (256 longs)
| Work : 8 passes x 256 loads = 2048 loads, accumulated into D0.
| PASS  is unconditional — this is a timing bench, not a correctness test;
| the accumulate exists only to keep the loads from being dead code.

    .text
    .org 0

_start:
    | ── fill the array once so the loads read defined data ────────────
    lea     0x00200000, %a0
    move.l  #256, %d1
    move.l  #0x11111111, %d2
_fill:
    move.l  %d2, (%a0)+
    add.l   #1, %d2
    sub.l   #1, %d1
    bne     _fill

    move.l  #0, %d0             | accumulator
    move.l  #8, %d3             | pass counter

_pass:
    lea     0x00200000, %a0
    move.l  #256, %d1
_walk:
    move.l  (%a0)+, %d4
    add.l   %d4, %d0
    sub.l   #1, %d1
    bne     _walk
    sub.l   #1, %d3
    bne     _pass

    | Park the accumulator somewhere real so nothing is dead.
    lea     0x00200800, %a1
    move.l  %d0, (%a1)

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)

_halt:
    stop    #0x2700
    bra     _halt
