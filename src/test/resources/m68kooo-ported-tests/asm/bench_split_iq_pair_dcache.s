| bench_split_iq_pair_dcache.s — D-CACHE-ENABLED variant of bench_split_iq_pair.s
|
| Generated from tb/tests/asm/bench_split_iq_pair.s by prepending the D-cache-enable
| prologue below.  The workload itself is byte-identical to the parent,
| so (bench_split_iq_pair, bench_split_iq_pair_dcache) is a like-for-like
| cache-bypass vs cache-enabled pair.  Do not edit the body here without
| making the same edit to the parent.
|
| See docs/bench_baseline.md — 'Cache-bypass vs D-cache-enabled' for what
| this pair is for and how to run it.
|
| bench_split_iq_pair.s — Split-IQ pair throughput (task #254 / I3)
|
| Purpose: every cycle the front-end can dispatch up to 2 µops, and
| iq_int + iq_mem are independent hardware.  This benchmark alternates
| MEM and INT µops with NO RAW between them so each pair is a SPLIT-IQ
| pair (one MEM + one INT) that I3's routing must dispatch in a single
| cycle (lane-1 → the OTHER IQ's primary port).
|
| Pre-I3 (H5) behaviour: split-IQ pairs were silently dropped at
| dispatch — `l1_split_iq` in decode.v + `q_d_will_fire = q_d_valid &&
| q_d_same_iq` in m68k_core_fetch.vh demoted lane-1 to no-fire when
| the lanes targeted different IQs, halving the achievable lane-1
| firing rate on workloads with mixed mem / int patterns.
|
| Post-I3: the pair fires.  `[METRICS] lane1_fires=` should be
| ~2x higher than the baseline (H5) value on this workload.
|
| The loop carries no cross-iteration RAW (D1, D5 written but never
| read) so the OoO scheduler can't serialise on a chain — only the
| loop counter D7 chains between iterations.

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
    | Pre-populate working array at 0x00100000.
    move.l  #0xC0FFEE01, %d0
    lea     0x00100000, %a0
    move.l  %d0, (%a0)
    move.l  #0xC0FFEE02, %d0
    move.l  %d0, 4(%a0)
    move.l  #0xC0FFEE03, %d0
    move.l  %d0, 8(%a0)
    move.l  #0xC0FFEE04, %d0
    move.l  %d0, 12(%a0)

    | Reset for loop
    move.l  #20, %d7
    lea     0x00100000, %a0

_loop:
    | Pair-1: MEM (load) + INT (no RAW) — split-IQ pair (lane-0 MEM,
    | lane-1 INT) — pre-I3 was dropped, post-I3 fires.
    move.l  (%a0), %d0
    add.l   #5, %d1
    | Pair-2: MEM (load) + INT (no RAW) — split-IQ pair
    move.l  4(%a0), %d2
    eor.l   %d3, %d5
    | Loop control
    sub.l   #1, %d7
    bne     _loop

    | PASS sentinel
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)

_halt:
    stop    #0x2700
    bra     _halt
