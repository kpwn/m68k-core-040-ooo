| bench_store32_dcache.s — D-CACHE-ENABLED variant of bench_store32.s
|
| Generated from tb/tests/asm/bench_store32.s by prepending the D-cache-enable
| prologue below.  The workload itself is byte-identical to the parent,
| so (bench_store32, bench_store32_dcache) is a like-for-like
| cache-bypass vs cache-enabled pair.  Do not edit the body here without
| making the same edit to the parent.
|
| See docs/bench_baseline.md — 'Cache-bypass vs D-cache-enabled' for what
| this pair is for and how to run it.
|
| bench_store32.s — zero-reuse 32 B stride STORE walk (D-cache BYPASS variant)
|
| The write-side counterpart to bench_stride32.s.  bench_stride32 walks a
| 16 KiB array with one LOAD per 32 B line; this one does one STORE per
| line over a region twice the size of the cache, three times over, so
| that from the second pass onward every miss has to write a DIRTY victim
| line back before it can start its own refill.
|
| Why it exists: bench_stride32 / bench_wset_1k are both load-only, so
| neither of them can see the D-cache writeback path at all.  A dirty
| miss is the expensive case — the writeback is strictly ordered ahead of
| the fill and there is no victim buffer — and until this bench there was
| nothing in the corpus that measured it.
|
| This cache is write-back + write-allocate (dcache.v:43-54), so a store
| miss costs exactly a load miss PLUS the victim writeback.
|
| Array: 0x00200000 .. 0x00201FFF (8 KiB = 256 lines, 2x the 4 KiB cache)
| Work : 3 passes x 256 stores = 768 store misses; 640 of them evict a
|        dirty victim (the first 128 land in still-invalid ways).
|
| This is the BYPASS half of the pair; see bench_store32_dcache.
|
| Run it with BOTH latency knobs — the writeback half is invisible unless
| +ddr_write_delay is set:
|   build/sim/Vmac_top +test=bench_store32_dcache +timeout=8000000 \
|                      +ddr_read_delay=40 +ddr_write_delay=40 +ifetch_delay=40
|
| PASS: sentinel 0xC0FFEE00.

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
    move.l  #0x5A5A0000, %d0    | store payload
    move.l  #3, %d2             | pass counter
_pass:
    lea     0x00200000, %a0
    move.l  #256, %d1           | line counter
_walk:
    move.l  %d0, (%a0)          | one store per 32 B line
    add.l   #32, %a0            | next 32 B line
    sub.l   #1, %d1
    bne     _walk
    sub.l   #1, %d2
    bne     _pass

    | Read back the last line written.  On the cached variant this hits,
    | so it costs nothing; on either variant it catches a refill or
    | writeback that corrupted data.
    lea     -32(%a0), %a0
    move.l  (%a0), %d3
    cmp.l   %d0, %d3
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
_halt_fail:
    stop    #0x2700
    bra     _halt_fail
