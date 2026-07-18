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
