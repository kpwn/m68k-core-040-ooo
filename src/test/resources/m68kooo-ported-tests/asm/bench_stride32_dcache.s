| bench_stride32_dcache.s — D-CACHE-ENABLED variant of bench_stride32.s
|
| Generated from tb/tests/asm/bench_stride32.s by prepending the D-cache-enable
| prologue below.  The workload itself is byte-identical to the parent,
| so (bench_stride32, bench_stride32_dcache) is a like-for-like
| cache-bypass vs cache-enabled pair.  Do not edit the body here without
| making the same edit to the parent.
|
| See docs/bench_baseline.md — 'Cache-bypass vs D-cache-enabled' for what
| this pair is for and how to run it.
|
| bench_stride32.s — zero-reuse 32 B stride walk (D-cache BYPASS variant)
|
| The deliberate ADVERSARIAL counterpart to bench_wset_1k.  One 4-byte
| load per 32 B D-cache line, walking 16 KiB linearly: no temporal reuse
| (each line is touched once) and no spatial reuse (only one word of each
| 32 B line is ever read).  This is the pattern where a write-back cache
| with no critical-word-first and no early restart LOSES to a bypass — it
| fetches 32 B and hands back 4.
|
| Keeping this pair honest matters: bench_wset_1k alone would make the
| cache look unconditionally good.  Publish both or publish neither.
|
| This is the BYPASS half of the pair; see bench_stride32_dcache.
|
| Array: 0x00200000 .. 0x00203FFF (16 KiB = 512 lines)
| Work : 512 loads, one per line, accumulated into D0.

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
    move.l  #0, %d0             | accumulator
    move.l  #512, %d1           | line counter
    lea     0x00200000, %a0

_walk:
    move.l  (%a0), %d4
    add.l   %d4, %d0
    add.l   #32, %a0            | next 32 B line
    sub.l   #1, %d1
    bne     _walk

    lea     0x00204000, %a1
    move.l  %d0, (%a1)

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)

_halt:
    stop    #0x2700
    bra     _halt
