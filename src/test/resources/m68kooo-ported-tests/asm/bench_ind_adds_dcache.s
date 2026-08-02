| bench_ind_adds_dcache.s — D-CACHE-ENABLED variant of bench_ind_adds.s
|
| Generated from tb/tests/asm/bench_ind_adds.s by prepending the D-cache-enable
| prologue below.  The workload itself is byte-identical to the parent,
| so (bench_ind_adds, bench_ind_adds_dcache) is a like-for-like
| cache-bypass vs cache-enabled pair.  Do not edit the body here without
| making the same edit to the parent.
|
| See docs/bench_baseline.md — 'Cache-bypass vs D-cache-enabled' for what
| this pair is for and how to run it.
|
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
