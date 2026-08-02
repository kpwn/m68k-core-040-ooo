| bench_dual_alu_movem_dcache.s — D-CACHE-ENABLED variant of bench_dual_alu_movem.s
|
| Generated from tb/tests/asm/bench_dual_alu_movem.s by prepending the D-cache-enable
| prologue below.  The workload itself is byte-identical to the parent,
| so (bench_dual_alu_movem, bench_dual_alu_movem_dcache) is a like-for-like
| cache-bypass vs cache-enabled pair.  Do not edit the body here without
| making the same edit to the parent.
|
| See docs/bench_baseline.md — 'Cache-bypass vs D-cache-enabled' for what
| this pair is for and how to run it.
|
| bench_dual_alu_movem.s — Stress lane-B with MOVEM multi-µop cracks.
|
| MOVEM.L (d16,A5),Dn/... cracks into up to 17 µops per macro-instruction
| inside decode.v.  All those µops hit the IQ in quick succession and
| can parallelise across ALU lane A + lane B if the data is independent.
|
| This benchmark proves lane B is live: MOVEM register-list loads fill
| the IQ past decode-width-1 so lane B has candidates to pick.

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
    | Seed a region with known data (16 longs at 0x40810000).
    lea     0x40810000, %a5
    move.l  #0x11111111, 0(%a5)
    move.l  #0x22222222, 4(%a5)
    move.l  #0x33333333, 8(%a5)
    move.l  #0x44444444, 12(%a5)
    move.l  #0x55555555, 16(%a5)
    move.l  #0x66666666, 20(%a5)
    move.l  #0x77777777, 24(%a5)
    move.l  #0x88888888, 28(%a5)

    moveq   #20, %d7              | loop counter

_loop:
    | MOVEM.L from (A5) into D0-D6,A0 — 8 registers worth of moves
    | cracked into 8 LOAD µops inside the IQ.  Each iteration also
    | runs a handful of ADD.L dependent pairs that can parallelise
    | with the later MOVEM µops on lane B.
    movem.l 0(%a5), %d0-%d6/%a0

    | Some independent ADD work that can issue in parallel with the
    | tail of the MOVEM cracks.
    add.l   %d1, %d0
    add.l   %d3, %d2
    add.l   %d5, %d4

    subq.l  #1, %d7
    bne     _loop

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
