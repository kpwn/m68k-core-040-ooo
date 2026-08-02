| bench_cmp_branch_dcache.s — D-CACHE-ENABLED variant of bench_cmp_branch.s
|
| Generated from tb/tests/asm/bench_cmp_branch.s by prepending the D-cache-enable
| prologue below.  The workload itself is byte-identical to the parent,
| so (bench_cmp_branch, bench_cmp_branch_dcache) is a like-for-like
| cache-bypass vs cache-enabled pair.  Do not edit the body here without
| making the same edit to the parent.
|
| See docs/bench_baseline.md — 'Cache-bypass vs D-cache-enabled' for what
| this pair is for and how to run it.
|
| bench_cmp_branch.s — CMP + Bcc back-to-back throughput
|
| IPC TARGET: ~1.0-1.2 (limited by CCR stall mechanism)
| LOOP COUNT: 30 iterations = 30 CMPs + 30 BNEs
|
| This benchmark exercises the condition code register (CCR) data path:
|   1. CMP produces N/Z/V/C flags
|   2. BNE immediately reads those flags to determine branch direction
|   3. Without CCR renaming, BNE must wait for CMP to commit (CCR stall)
|
| Tight sequence:
|   cmp.l D0, D1
|   bne _target    (must wait for Z flag from CMP)
|
| According to CLAUDE.md, the CCR stall mechanism in iq_int prevents a
| flag-reader from issuing until all in-flight flag-writers have committed.
| This creates a structural serialization.
|
| Expected behavior:
|   - First few iterations warm up the predictor
|   - CMP throughput: 1 per cycle (ALU can handle it)
|   - BNE throughput: 1 per cycle (after CCR stall is resolved)
|   - Overall: 2 ops per cycle if both can issue; 1.5-1.8 with stalling
|
| This test validates CCR hazard detection and stall mechanism efficiency.

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
    move.l  #30, %d6            | loop counter
    move.l  #0x00000001, %d0    | D0 = 1
    move.l  #0x00000002, %d1    | D1 = 2

_loop:
    | Compare D0 vs D1 (sets flags; D0 != D1, so Z=0)
    cmp.l   %d1, %d0

    | Branch if not equal (depends on Z flag from CMP)
    bne     _branch_taken

    | Should not reach here (D0 != D1, so Z=0, bne taken)
    move.l  #0xDEADBEEF, %d2
    bra     _halt_fail

_branch_taken:
    | Decrement counter and loop
    sub.l   #1, %d6
    bne     _loop

    | Prepare PASS sentinel
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)

_halt:
    stop    #0x2700
    bra     _halt

_halt_fail:
    stop    #0x2700
    bra     _halt_fail
