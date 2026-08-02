| bench_mixed_mem_dcache.s — D-CACHE-ENABLED variant of bench_mixed_mem.s
|
| Generated from tb/tests/asm/bench_mixed_mem.s by prepending the D-cache-enable
| prologue below.  The workload itself is byte-identical to the parent,
| so (bench_mixed_mem, bench_mixed_mem_dcache) is a like-for-like
| cache-bypass vs cache-enabled pair.  Do not edit the body here without
| making the same edit to the parent.
|
| See docs/bench_baseline.md — 'Cache-bypass vs D-cache-enabled' for what
| this pair is for and how to run it.
|
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
