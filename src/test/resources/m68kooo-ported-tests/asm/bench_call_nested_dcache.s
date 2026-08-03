| bench_call_nested_dcache.s — D-CACHE-ENABLED variant of bench_call_nested.s
|
| Generated from tb/tests/asm/bench_call_nested.s by prepending the D-cache-enable
| prologue below.  The workload itself is byte-identical to the parent,
| so (bench_call_nested, bench_call_nested_dcache) is a like-for-like
| cache-bypass vs cache-enabled pair.  Do not edit the body here without
| making the same edit to the parent.
|
| See docs/bench_baseline.md — 'Cache-bypass vs D-cache-enabled' for what
| this pair is for and how to run it.
|
| bench_call_nested.s — 3-deep nested call/return chain (RAS depth workload)
|
| WHY THIS BENCH EXISTS
|   Companion to bench_call_leaf.s.  A single-level leaf call warms up
|   and can be predicted from one RAS entry; the case that keeps a
|   retire-time-pushed RAS persistently WRONG (not merely cold) is the
|   NESTED one — an inner call whose push has not landed by the time its
|   own `rts` dispatches, so the inner return pops the OUTER frame's
|   address and lands somewhere real but unrelated.
|
| SHAPE
|   150 iterations of  _lvl1 -> _lvl2 -> _lvl3, mixing BSR and JSR so
|   both crack shapes (PC-relative BSR and absolute-target JSR) are on
|   the measured path.  450 calls, 450 returns, RAS depth 3.
|
| DETERMINISM: no memory traffic beyond the call stack itself.

    .text
    .org 0

    .equ ITERS, 150

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
    lea     0x00020000, %a7

    moveq   #0, %d3
    move.l  #ITERS, %d2

_loop:
    bsr     _lvl1
    subq.l  #1, %d2
    bne     _loop

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)

_halt:
    stop    #0x2700
    bra     _halt

    | ── the call chain ────────────────────────────────────────────
_lvl1:
    addq.l  #1, %d3
    jsr     _lvl2
    addq.l  #1, %d3
    rts

_lvl2:
    addq.l  #1, %d3
    bsr     _lvl3
    rts

_lvl3:
    addq.l  #1, %d3
    rts
