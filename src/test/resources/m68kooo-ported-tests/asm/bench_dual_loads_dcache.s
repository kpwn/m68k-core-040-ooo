| bench_dual_loads_dcache.s — D-CACHE-ENABLED variant of bench_dual_loads.s
|
| Generated from tb/tests/asm/bench_dual_loads.s by prepending the D-cache-enable
| prologue below.  The workload itself is byte-identical to the parent,
| so (bench_dual_loads, bench_dual_loads_dcache) is a like-for-like
| cache-bypass vs cache-enabled pair.  Do not edit the body here without
| making the same edit to the parent.
|
| See docs/bench_baseline.md — 'Cache-bypass vs D-cache-enabled' for what
| this pair is for and how to run it.
|
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
