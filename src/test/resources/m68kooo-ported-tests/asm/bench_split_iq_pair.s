| bench_split_iq_pair.s — Split-IQ pair throughput (task #254 / I3)
|
| Purpose: every cycle the front-end can dispatch up to 2 µops, and
| iq_int + iq_mem are independent hardware.  This benchmark alternates
| MEM and INT µops with NO RAW between them so each pair is a SPLIT-IQ
| pair (one MEM + one INT) that I3's routing must dispatch in a single
| cycle (lane-1 → the OTHER IQ's primary port).
|
| Pre-I3 (H5) behaviour: split-IQ pairs were silently dropped at
| dispatch — `l1_split_iq` in decode.v + `q_d_will_fire = q_d_valid &&
| q_d_same_iq` in m68k_core_fetch.vh demoted lane-1 to no-fire when
| the lanes targeted different IQs, halving the achievable lane-1
| firing rate on workloads with mixed mem / int patterns.
|
| Post-I3: the pair fires.  `[METRICS] lane1_fires=` should be
| ~2x higher than the baseline (H5) value on this workload.
|
| The loop carries no cross-iteration RAW (D1, D5 written but never
| read) so the OoO scheduler can't serialise on a chain — only the
| loop counter D7 chains between iterations.

    .text
    .org 0

_start:
    | Pre-populate working array at 0x00100000.
    move.l  #0xC0FFEE01, %d0
    lea     0x00100000, %a0
    move.l  %d0, (%a0)
    move.l  #0xC0FFEE02, %d0
    move.l  %d0, 4(%a0)
    move.l  #0xC0FFEE03, %d0
    move.l  %d0, 8(%a0)
    move.l  #0xC0FFEE04, %d0
    move.l  %d0, 12(%a0)

    | Reset for loop
    move.l  #20, %d7
    lea     0x00100000, %a0

_loop:
    | Pair-1: MEM (load) + INT (no RAW) — split-IQ pair (lane-0 MEM,
    | lane-1 INT) — pre-I3 was dropped, post-I3 fires.
    move.l  (%a0), %d0
    add.l   #5, %d1
    | Pair-2: MEM (load) + INT (no RAW) — split-IQ pair
    move.l  4(%a0), %d2
    eor.l   %d3, %d5
    | Loop control
    sub.l   #1, %d7
    bne     _loop

    | PASS sentinel
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)

_halt:
    stop    #0x2700
    bra     _halt
