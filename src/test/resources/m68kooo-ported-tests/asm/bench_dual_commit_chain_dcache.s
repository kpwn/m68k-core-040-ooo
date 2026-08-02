| bench_dual_commit_chain_dcache.s — D-CACHE-ENABLED variant of bench_dual_commit_chain.s
|
| Generated from tb/tests/asm/bench_dual_commit_chain.s by prepending the D-cache-enable
| prologue below.  The workload itself is byte-identical to the parent,
| so (bench_dual_commit_chain, bench_dual_commit_chain_dcache) is a like-for-like
| cache-bypass vs cache-enabled pair.  Do not edit the body here without
| making the same edit to the parent.
|
| See docs/bench_baseline.md — 'Cache-bypass vs D-cache-enabled' for what
| this pair is for and how to run it.
|
| bench_dual_commit_chain.s — Directed stress for the 2-uops/cycle
| commit lane.
|
| IPC TARGET: measurable improvement over the single-commit baseline
| on a stream of plain INT µops that (a) have independent sources
| (CCR-bypass allows lane B to issue behind lane A without stall),
| (b) reach ROB head complete at the same cycle, and (c) pass the
| lane-B gate (UOP_INT, not branch/store/SYS, not dual-dst, no A7).
|
| Loop body: 8 independent ADDs per iteration across 4 register
| pairs — 4 hot pairs × (D1->D0, D3->D2, D5->D4, D7->D6) × 2 = 8
| µops per cycle burst.  With dual-retire, head-and-head+1 clear
| together as soon as they complete on the two ALUs.
|
| Register allocation:
|   D0/D1 independent
|   D2/D3 independent
|   D4/D5 independent
|   D6    loop counter
|   D7    scratch

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
    | Independent-chain seeds
    move.l  #0x00000001, %d0
    move.l  #0x00000002, %d1
    move.l  #0x00000003, %d2
    move.l  #0x00000004, %d3
    move.l  #0x00000005, %d4
    move.l  #0x00000006, %d5
    move.l  #0x00000007, %d7
    move.l  #100, %d6

_loop:
    | Eight independent ADDs — each writes CCR (lane B must still
    | retire behind lane A's CCR write) and each pair reads a disjoint
    | source, so issue + commit are both fully parallel.
    add.l   %d1, %d0
    add.l   %d3, %d2
    add.l   %d5, %d4
    add.l   %d1, %d0
    add.l   %d3, %d2
    add.l   %d5, %d4
    add.l   %d7, %d0
    add.l   %d7, %d2

    | Decrement + branch.  BNE reads CCR from the subq; subq is the
    | lane-A producer of CCR; the branch resolves on lane A's ALU.
    sub.l   #1, %d6
    bne     _loop

    | PASS sentinel
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)

_halt:
    bra     _halt
