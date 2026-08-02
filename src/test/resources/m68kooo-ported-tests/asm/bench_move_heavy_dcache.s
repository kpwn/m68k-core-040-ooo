| bench_move_heavy_dcache.s — D-CACHE-ENABLED variant of bench_move_heavy.s
|
| Generated from tb/tests/asm/bench_move_heavy.s by prepending the D-cache-enable
| prologue below.  The workload itself is byte-identical to the parent,
| so (bench_move_heavy, bench_move_heavy_dcache) is a like-for-like
| cache-bypass vs cache-enabled pair.  Do not edit the body here without
| making the same edit to the parent.
|
| See docs/bench_baseline.md — 'Cache-bypass vs D-cache-enabled' for what
| this pair is for and how to run it.
|
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
