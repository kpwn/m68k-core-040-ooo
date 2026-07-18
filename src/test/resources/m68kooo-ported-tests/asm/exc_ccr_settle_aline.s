| exc_ccr_settle_aline.s — CCR must reflect the boundary instruction at
| synchronous-exception entry (CCR-settle race, task #41).
|
| arch_ccr_val (= ccr_prf[crat_tag] in ccr_rat.v) lags a retiring
| flag-writer by one cycle: ccr_commit_en pulses cycle X, crat_tag
| advances on that pulse, so the committed CCR is only observable from
| X+1.  If exception entry samples arch_ccr_val DURING cycle X it
| captures the CCR of the instruction BEFORE the boundary.
|
| Repro: MOVE.W #imm clears C (C=0); CMP.W sets C=1; an A-line opword
| immediately follows so the vec-10 trap takes the boundary right after
| the CMP retires.  The handler inspects the stacked SR's C bit — it
| MUST be 1 (the CMP's result), not 0 (the stale MOVE.W result).
|
| The sync-exception (take_exc) path naturally has enough retire-to-fire
| latency that arch_ccr_val is already settled; this test locks that in.
| The IRQ (take_irq_fire_q) path does NOT — see exc_irq_ccr_settle.s.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0001  Stacked CCR has C==0 — stale (pre-CMP) CCR saved ← BUG
|   0xDEAD0003  A-line trap never fired (fallthrough)

    .text
    .org 0

_start:
    lea     0x00010000, %a7              | SSP
    move.l  #_handler, 0x00000028        | vector 10 (A-line) @ 0x28
    move.w  #0x2000, %sr                 | S=1, IPL=0

    | Boundary pair: MOVE.W clears C, CMP.W sets C.  The A-line opword
    | immediately follows so exception entry samples the CCR in the
    | CMP's commit-settle window.
    move.w  #0x1000, %d2                 | D2.w=0x1000; MOVE clears V,C → C=0
    cmp.w   #0x2000, %d2                 | 0x1000-0x2000 → borrow → C=1

_aline_site:
    .short  0xa05d                       | A-line opword → decode → vec 10

_fallthrough:
    | A-line trap never fired.
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0003, %d0
    move.l  %d0, (%a0)
_halt_fall:
    bra     _halt_fall

_handler:
    | Format $0 frame: word 0 = saved SR.  Its low 5 bits are the CCR.
    move.w  (%a7), %d0                   | D0 = stacked SR
    andi.w  #0x0001, %d0                 | isolate C (CCR bit 0)
    beq     _fail_c0                     | C==0 → stale CCR saved → BUG

    | C==1 — the CMP's result was correctly captured.
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail_c0:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
