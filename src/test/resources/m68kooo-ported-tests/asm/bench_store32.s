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
