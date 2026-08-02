| bench_dc_prologue_cal.s — calibration null-bench (BYPASS half)
|
| Every bench_*_dcache variant carries a fixed prologue (5 MOVEC pairs +
| an SR restore) that the parent bench does not.  That prologue costs real
| cycles, and on the short legacy benches it is a large fraction of the
| total — so a naive `bench_X_dcache - bench_X` delta reports the prologue,
| not the cache.
|
| This pair exists purely to measure that constant:
|
|     prologue_cost = bench_dc_prologue_cal_dcache - bench_dc_prologue_cal
|
| Subtract it before drawing any conclusion from a *_dcache delta.  It is
| latency-independent (MOVEC touches no memory), so one measurement covers
| the whole sweep.
|
| Body: nothing but the PASS sentinel.

    .text
    .org 0

_start:
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)

_halt:
    stop    #0x2700
    bra     _halt
