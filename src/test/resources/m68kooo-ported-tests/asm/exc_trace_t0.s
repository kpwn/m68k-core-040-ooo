| exc_trace_t0.s — change-of-flow trace (SR.T0=1), vec 9.
|
| Per 68040 PRM: when T0 is set (and T1=0), trace fires only on
| change-of-flow instructions (branches, BSR, RTS, RTE, JMP, JSR,
| TRAP#n, NOP, SR-modifying, etc.) — not on plain ALU ops.
|
| Construction:
|   1. Install vec-9 handler that increments COUNTER and clears T0.
|   2. Set T0 = 1 (SR = 0x6000 — S=1, T0=1, IPL=0).
|   3. Execute `addq.l #1, %d3` — NOT change-of-flow; trace must NOT
|      fire (COUNTER stays 0).
|   4. Execute `bra _next` — change-of-flow taken branch; trace MUST
|      fire after this branch commits.
|   5. Handler runs once (COUNTER=1) and clears T0 in stacked SR; RTE
|      returns to mainline at _next with T0=0.
|
| Note: harness FAILs on any non-PASS write to PASS_SENT (0xFFFF0000).
| Do NOT pre-write that address.
|
| PASS sentinel: 0xC0FFEE00 (counter == 1, T0 cleared).
| FAIL sentinels:
|   0xDEAD0A01 — counter != 1 (trace fired wrong number of times)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ COUNTER,   0x00000400

_start:
    lea     0x00010000, %a7
    move.l  #_trace_h, 0x00000024     | vec 9
    move.l  #0, COUNTER.l

    | Set SR = 0x6000: S=1, T1=0, T0=1, IPL=0.
    move.w  #0x6000, %sr

    | Plain ALU op — must NOT trace under T0.
    addq.l  #1, %d3

    | Change-of-flow taken branch — MUST trace after commit.
    bra     _next

_next:
    | Counter must be exactly 1: trace fired once on the bra.
    move.l  COUNTER.l, %d0
    cmp.l   #1, %d0
    bne     _fail

    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0A01, %d2
    move.l  %d2, (%a1)
_halt_f:
    bra     _halt_f

_trace_h:
    addq.l  #1, COUNTER.l
    | Clear T0 in stacked SR (sp+0) so RTE returns without tracing.
    move.w  (%a7), %d0
    andi.w  #0xBFFF, %d0     | clear bit 14 (T0)
    move.w  %d0, (%a7)
    rte
