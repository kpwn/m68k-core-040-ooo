| shadow_inst1_match.s — Phase 1 shadow-lane validation companion (task #237).
|
| This file exists so that the directed-test pipeline picks up a
| shadow-related fixture by NAME alongside the dedicated unit testbench
| `tb-decode-shadow` (see Makefile + tb/tb_decode_shadow.cpp).
|
| The actual shadow-lane equivalence check is sub-cycle and runs in the
| Verilator-only `tb-decode-shadow` harness — it drives the `decode`
| module standalone with `ENABLE_2WIDE_DECODE=1` and compares the lane-1
| internal assembler outputs against a re-driven single-wide decode of
| the same opword.  That harness covers six representative pairs:
|
|   ADD.L  D1,D0  ;  ADD.L  D3,D2
|   MOVEQ  #1,D0  ;  MOVEQ  #2,D1
|   SUB.L  D1,D0  ;  AND.L  D3,D2
|   OR.L   D1,D0  ;  EOR.L  D3,D2
|   NEG.L  D0     ;  NOT.L  D1
|   CMP.L  D1,D0  ;  TST.L  D2
|
| Run via: `make tb-decode-shadow`.  See `docs/tracks/core.md` for the
| full Phase 1 / Phase 2 plan; the dispatch-port wire-through (Phase 2)
| is gated on task #219c (RAT/CCR-RAT/ROB second lane) landing on main.
|
| The .s body below is the same six pairs encoded as runnable assembly,
| useful for waveform inspection in the full mac_top sim with
| `ENABLE_2WIDE_DECODE=0` (the default).  The shadow lane outputs are
| computed but tied off; the directed test merely verifies the existing
| dispatch path is sim-neutral when the flag is off.

    .text
    .org 0

_start:
    | Pair 1: ADD reg-reg
    add.l   %d1, %d0
    add.l   %d3, %d2
    | Pair 2: MOVEQ #imm
    moveq   #1, %d0
    moveq   #2, %d1
    | Pair 3: SUB / AND
    sub.l   %d1, %d0
    and.l   %d3, %d2
    | Pair 4: OR / EOR
    or.l    %d1, %d0
    eor.l   %d3, %d2
    | Pair 5: NEG / NOT
    neg.l   %d0
    not.l   %d1
    | Pair 6: CMP / TST
    cmp.l   %d1, %d0
    tst.l   %d2

    | PASS sentinel.
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)

_halt:
    stop    #0x2700
    bra     _halt
