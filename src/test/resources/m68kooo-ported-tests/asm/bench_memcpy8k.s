| bench_memcpy8k.s — 8 KiB longword block copy (D-cache BYPASS variant)
|
| bench_stride32 and bench_store32 are deliberately adversarial (zero
| reuse, one access per line).  This one is the opposite end and the
| realistic one: a straight longword block copy, which is what Mac OS
| actually spends its memory bandwidth on (BlockMove, QuickDraw blits).
|
| Every 32 B line is touched eight times: one miss and seven hits on the
| source side, one write-allocate miss and seven hits on the destination
| side.  So misses are ~1/8 of accesses and the cache is doing its job —
| which is exactly the regime where a refill/writeback speedup still has
| to show up to be worth having.
|
| Source 0x00200000 (8 KiB), destination 0x00210000 (8 KiB).  Together 4x
| the 4 KiB cache and streamed, so there is no cross-pass reuse and the
| destination victims are dirty from the second pass over each set on.
|
| This is the BYPASS half of the pair; see bench_memcpy8k_dcache.
|
| PASS: sentinel 0xC0FFEE00.

    .text
    .org 0

_start:
    | Seed the source with a checkable pattern.
    lea     0x00200000, %a0
    move.l  #0x00010001, %d0
    move.l  #2048, %d1
_seed:
    move.l  %d0, (%a0)+
    add.l   #0x00010001, %d0
    sub.l   #1, %d1
    bne     _seed

    | The copy itself.
    lea     0x00200000, %a0
    lea     0x00210000, %a1
    move.l  #2048, %d1
_copy:
    move.l  (%a0)+, (%a1)+
    sub.l   #1, %d1
    bne     _copy

    | Spot-check the first and last longword of the destination.
    lea     0x00210000, %a2
    move.l  (%a2), %d2
    cmp.l   #0x00010001, %d2
    bne     _fail
    move.l  -4(%a1), %d2
    cmp.l   #(0x00010001*2048), %d2
    bne     _fail

    lea     0xFFFF0000, %a3
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a3)
_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a3
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a3)
_halt_fail:
    stop    #0x2700
    bra     _halt_fail
