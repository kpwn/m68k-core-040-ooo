| bench_wset_1k_dcache.s — D-CACHE-ENABLED variant of bench_wset_1k.s
|
| Generated from tb/tests/asm/bench_wset_1k.s by prepending the D-cache-enable
| prologue below.  The workload itself is byte-identical to the parent,
| so (bench_wset_1k, bench_wset_1k_dcache) is a like-for-like
| cache-bypass vs cache-enabled pair.  Do not edit the body here without
| making the same edit to the parent.
|
| See docs/bench_baseline.md — 'Cache-bypass vs D-cache-enabled' for what
| this pair is for and how to run it.
|
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

    | ─── D-cache-enable prologue (this is the *_dcache variant) ──────────
    | Everything from here to `_dc_prologue_end` exists only to turn the
    | L1-D cache ON.  The parent bench (bench_<name>.s) is byte-identical
    | below this block and runs with the D-cache BYPASSED, because:
    |
    |   rtl/core/mem/mmu.v:657   assign cache_inh_out = (!mmu_enable) ? 1'b1 : ...
    |
    | i.e. with TC.E==0 the MMU reports "cache inhibited" for every access
    | and rtl/core/m68k_core_memory.vh's dcache instance is a pass-through
    | no matter what CACR says.  So a cached measurement needs BOTH a
    | cacheable translation (TC.E=1 + a CM=copyback TTR) AND CACR.DE.
    |
    | DTT0 = 0x007FE020 : base 0x00 / mask 0x7F  -> VA 0x00000000-0x7FFFFFFF
    |                     E=1, S=11 (match user+super), CM=01 (copyback)
    |                     => CACHEABLE.  Covers the bench data arrays and
    |                     the 0x40800000 text image.
    move.l  #0x007FE020, %d7
    movec   %d7, %dtt0
    | DTT1 = 0x807FE060 : base 0x80 / mask 0x7F  -> VA 0x80000000-0xFFFFFFFF
    |                     E=1, S=11, CM=11 (noncacheable).
    |                     MUST stay non-cacheable: the PASS sentinel store
    |                     to 0xFFFF0000 is what ends the test, and tb_top.cpp
    |                     only sees it if it reaches the AXI write channel.
    |                     Cache it and the run deadlocks (the end-of-test
    |                     flush is triggered BY the sentinel).
    move.l  #0x807FE060, %d7
    movec   %d7, %dtt1
    | ITT0 mirrors DTT0 so the instruction side keeps a cacheable, walk-free
    | translation once TC.E goes high.
    move.l  #0x007FE020, %d7
    movec   %d7, %itt0
    | TC.E (bit 15).  No page tables are configured — every access the
    | bench makes is covered by DTT0/DTT1/ITT0, so no table walk can fire.
    move.l  #0x00008000, %d7
    movec   %d7, %tc
    | CACR.DE (bit 31) -> m68k_core_memory.vh:958 .cache_enable(arch_cacr_w[31])
    move.l  #0x80000000, %d7
    movec   %d7, %cacr
    | Restore the exact post-reset architectural state the parent bench
    | starts from: D7=0 and SR=0x2700 (S=1, IPL=7, CCR=0).  Without this
    | the variant would not be a like-for-like comparison.
    moveq   #0, %d7
    move.w  #0x2700, %sr
_dc_prologue_end:
    | ─── end D-cache-enable prologue; parent bench body follows verbatim ──
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
