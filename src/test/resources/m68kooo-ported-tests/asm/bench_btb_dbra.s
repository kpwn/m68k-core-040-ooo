| bench_btb_dbra.s — BTB loop throughput without CCR serialization
|
| Same shape as bench_btb_loop but uses DBRA (DBF), which does not read
| the CCR — so the CCR-pending stall (which gates Bcc issue on an in-
| flight flag-writer) does not throttle the branch.  This isolates the
| pure branch-redirect cost from the CCR serialization hit.
|
| DBRA counts from 99 down through -1, giving 100 iterations.  Body is
| empty (no data ops), so steady-state cost per iter should be just the
| BTB-predicted backward branch = ~1 decode-redirect cycle plus the
| DBcc's own ALU pass.
|
| On PASS: writes 0xC0FFEE00 to 0xFFFF0000.

    .text
    .org 0

_start:
    move.l  #99, %d0

_loop:
    dbra    %d0, _loop

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a0)

_halt:
    stop    #0x2700
    bra     _halt
