| bench_btb_loop.s — BTB warmup + steady-state loop throughput
|
| Runs a 100-iteration backward-BNE loop to measure cycles-per-iter after
| BPU warmup.  With the phase-1 BTB wired in, taken-branch cost should
| collapse from the pre-BTB ~10-cycle commit-redirect to a 1-cycle decode
| redirect.  The body is kept tiny (SUBQ + BNE) so the dominant term is
| branch latency, not data-dependency latency.
|
| D0 = 100 (loop counter)
| _loop:
|     SUBQ.L #1, %d0       ; D0 -= 1; Z set when D0 reaches 0
|     BNE    _loop         ; taken 99 times, falls through on iter 100
|
| On PASS: writes 0xC0FFEE00 to 0xFFFF0000.  No FAIL path — a miscount
| would make D0 wrong at exit but doesn't flip the sentinel, so this is
| purely an IPC measurement (check the "after N cycles" line).

    .text
    .org 0

_start:
    move.l  #100, %d0

_loop:
    subq.l  #1, %d0
    bne     _loop

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a0)

_halt:
    stop    #0x2700
    bra     _halt
