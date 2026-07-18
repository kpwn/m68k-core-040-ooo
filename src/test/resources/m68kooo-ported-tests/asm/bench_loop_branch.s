| bench_loop_branch.s — Branch prediction warmup and loop throughput
|
| IPC TARGET: ~1.8-2.0 (with correct branch prediction after warmup)
| LOOP COUNT: 50 iterations
|
| This benchmark measures:
|   1. Branch predictor warmup time
|   2. Tight loop throughput with predictable branches
|   3. Loop-carried dependency latency (sub.l then bne)
|
| Original (broken) version did add+sub of the same Dn in the loop —
| D0 never decremented, so BNE looped forever.  Fixed: use D1 as loop
| counter, D0 as accumulator that is actually updated per-iter.

    .text

_start:
    move.l  #50, %d1            | iteration counter
    moveq   #0,  %d0            | accumulator

_loop:
    add.l   #1, %d0             | useful work
    sub.l   #1, %d1             | decrement counter (sets Z)
    bne     _loop               | branch-back

    | Sanity: d0 should be 50 after the loop
    cmp.l   #50, %d0
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)

_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xBADBAD00, %d0
    move.l  %d0, (%a0)
_fhlt:
    bra     _fhlt
