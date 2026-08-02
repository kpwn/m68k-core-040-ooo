| bench_fullpipe_dcache.s — D-CACHE-ENABLED variant of bench_fullpipe.s
|
| Generated from tb/tests/asm/bench_fullpipe.s by prepending the D-cache-enable
| prologue below.  The workload itself is byte-identical to the parent,
| so (bench_fullpipe, bench_fullpipe_dcache) is a like-for-like
| cache-bypass vs cache-enabled pair.  Do not edit the body here without
| making the same edit to the parent.
|
| See docs/bench_baseline.md — 'Cache-bypass vs D-cache-enabled' for what
| this pair is for and how to run it.
|
| bench_fullpipe.s — Full pipeline stress: mixed ALU, memory, and branches
|
| IPC TARGET: ~1.6-1.9 (tests sustained multi-functional throughput)
| LOOP COUNT: 30 iterations with mixed ops
|
| This benchmark combines independent operations from multiple functional units:
|   1. ALU: ADD, CMP operations
|   2. LSU: LOAD, STORE operations
|   3. BRANCH: conditional branches based on comparisons
|
| Within each loop iteration, we interleave:
|   - Independent ADDs (can issue in parallel)
|   - Memory access (load-use chain to LSU)
|   - Comparison and conditional branch (CCR path)
|
| The intent is to keep all pipelines busy simultaneously:
|   - Both ALUs busy with ALU ops
|   - LSU handling load/store
|   - Branch unit resolving in-flight branches
|
| Loop counter is stored in memory and loaded/compared each iteration,
| simulating a typical program with mixed workloads.
|
| With proper OoO scheduling, we expect near-peak throughput.

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
    | Pre-initialize counter at 0x100200
    move.l  #30, %d0
    lea     0x00100200, %a5
    move.l  %d0, (%a5)          | mem[0x100200] = 30

    | Initialize work registers
    move.l  #0x00000001, %d0
    move.l  #0x00000002, %d1
    move.l  #0x00000004, %d2
    move.l  #0x00000008, %d3
    move.l  #0x12345678, %d4

_loop:
    | ─── ALU group: independent operations ────────────────────────────
    | These have no dependencies and can dual-issue
    add.l   %d1, %d0            | D0 += D1
    add.l   %d3, %d2            | D2 += D3 (independent)

    | ─── Memory group: load-use chain ────────────────────────────────
    | Load counter from memory, depends on LEA which is independent
    lea     0x00100200, %a0
    move.l  (%a0), %d4          | Load counter into D4

    | ─── ALU group: more operations ────────────────────────────────
    sub.l   #1, %d4             | Decrement counter (depends on load)
    cmp.l   #0, %d4             | Compare with 0 (depends on sub)

    | ─── Store: commit counter back ────────────────────────────────
    move.l  %d4, (%a0)          | Store updated counter

    | ─── Branch: depends on comparison ────────────────────────────
    bne     _loop               | Loop if counter != 0 (depends on cmp)

    | Prepare PASS sentinel
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)

_halt:
    stop    #0x2700
    bra     _halt
