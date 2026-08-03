| bench_call_leaf_dcache.s — D-CACHE-ENABLED variant of bench_call_leaf.s
|
| Generated from tb/tests/asm/bench_call_leaf.s by prepending the D-cache-enable
| prologue below.  The workload itself is byte-identical to the parent,
| so (bench_call_leaf, bench_call_leaf_dcache) is a like-for-like
| cache-bypass vs cache-enabled pair.  Do not edit the body here without
| making the same edit to the parent.
|
| See docs/bench_baseline.md — 'Cache-bypass vs D-cache-enabled' for what
| this pair is for and how to run it.
|
| bench_call_leaf.s — repeated SHORT-LEAF calls, the canonical RAS workload
|
| WHY THIS BENCH EXISTS
|   Every other bench_*.s in the set is straight-line or loop-branch code
|   with ZERO subroutine calls, so the whole return-address-prediction
|   path is unmeasured by `make bench`.  Task #223 (move the RAS push
|   from ROB retire to dispatch) needed a cycle number, not a mispredict
|   rate, and there was nothing in the rig that could produce one.
|
| SHAPE
|   200 iterations x 4 calls to a 2-instruction leaf = 800 calls.  The
|   leaf is deliberately as short as a callee can usefully be: its `rts`
|   dispatches within a couple of cycles of its own `bsr`, which is
|   exactly the window in which a retire-time RAS push has not landed yet.
|
|   The four calls per iteration are to the SAME leaf from FOUR distinct
|   call sites, so the BTB cannot rescue the return either (one BTB entry
|   per RTS PC, four different correct targets).
|
| INSTRUCTION MIX: 800 BSR + 800 RTS + ~2400 ALU ops.
| DETERMINISM: no memory traffic beyond the call stack itself.

    .text
    .org 0

    .equ ITERS, 200

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
    bsr     _leaf
    bsr     _leaf
    bsr     _leaf
    bsr     _leaf
    subq.l  #1, %d2
    bne     _loop

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)

_halt:
    stop    #0x2700
    bra     _halt

    | ── the leaf ──────────────────────────────────────────────────
    | One ALU op, then return.  Nothing here is allowed to grow: the
    | point of the bench is the call/return turnaround, not the callee.
_leaf:
    addq.l  #1, %d3
    rts
