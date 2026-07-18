| exc_msp_mode_round_trip.s — TRAP from M=1 supervisor → handler → RTE.
|
| Regression for task #10 audit bugs #3, #4, #6 (and partially #5):
|   #3 RTE M-aware restore — RTE pops from current stack, switches to
|      target stack per saved-SR.M, and updates the source-stack slot
|      to the post-pop top.
|   #4 IRQ-entry M-clear — N/A here (we use TRAP, not IRQ — TRAP keeps
|      M).  See exc_msp_irq_clears_m.s for the IRQ-clear version (TBD).
|   #5 MOVE-to-SR M-transition — partially: we use ORI/ANDI to SR to
|      flip M between phases.
|   #6 take_finalize SP-slot pin — sync exception entry from M=1 must
|      write the post-frame-push A7 into the MSP slot, not SSP.
|
| TIMELINE
| --------
| Phase 0 — cold boot: S=1, M=0, A7 = SSP_top (= ISP per PRM names).
|   - Set up vec 32 (TRAP #0) handler.
|   - MOVEC #MSP_BASE, MSP — give MSP a known value distinct from ISP.
|
| Phase 1 — flip to M=1.
|   - ORI.W #0x1000, %sr      | set M
|   - Verify A7 == MSP_BASE (the MSP slot value).
|
| Phase 2 — TRAP from M=1.
|   - TRAP #0
|   - Handler runs at M=1 (TRAP doesn't clear M).
|   - Handler reads A7, verifies it's MSP_BASE - 8 (sync-exc fmt-0 frame
|     pushed onto MSP, audit bug #6 makes this go to MSP slot, not SSP).
|   - Handler writes a marker (0xFEED) at A7+0 (clobbering SR fragment
|     in the saved frame's first 16 bits — but we'll RTE from a
|     hand-built fmt-0 frame instead).
|   - Actually simpler: handler does RTE directly.
|
| Phase 3 — back in M=1 main code.
|   - Verify A7 == MSP_BASE (post-RTE A7 returns to pre-trap MSP top).
|   - Verify MSP slot integrity: MOVEC MSP, %d0; check d0 == MSP_BASE.
|   - Verify ISP slot integrity: MOVEC ISP, %d0; check d0 == ISP_BASE.
|
| Phase 4 — flip back to M=0.
|   - ANDI.W #0xEFFF, %sr     | clear M
|   - Verify A7 == ISP_BASE (the ISP slot value).
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0701 — Phase 1 A7 != MSP_BASE (M-flip didn't switch stack)
|   0xDEAD0702 — Phase 2 handler A7 != MSP_BASE-8 (sync exc didn't push to MSP)
|   0xDEAD0703 — Phase 3 main A7 != MSP_BASE (RTE didn't restore MSP top)
|   0xDEAD0704 — Phase 3 MOVEC MSP != MSP_BASE (MSP slot corrupted)
|   0xDEAD0705 — Phase 3 MOVEC ISP != ISP_BASE (ISP slot corrupted)
|   0xDEAD0706 — Phase 4 A7 != ISP_BASE (M-clear didn't switch back)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ MSP_BASE,  0x00050000
    .equ ISP_BASE,  0x00040000

_start:
    | Cold-boot SSP (ISP per PRM) was set by tb harness; A7 = ISP slot.
    | Save it as ISP_BASE so we can verify later.
    move.l  %a7, %d7                 | live A7 = ISP top
    | We'll force it to ISP_BASE to make the verification deterministic.
    move.l  #ISP_BASE, %a7

    | Install vec-32 handler at VBR + 32*4 = 0x80.
    move.l  #_handler_trap0, 0x00000080

    | Initialize MSP via MOVEC.
    move.l  #MSP_BASE, %d0
    .short  0x4E7B, 0x0803            | MOVEC d0,MSP

    | Phase 1: ORI.W #0x1000, %sr — set M.
    .short  0x007C, 0x1000            | ORI.W #0x1000, %sr
    cmp.l   #MSP_BASE, %a7
    bne     _fail_phase1

    | Phase 2: TRAP #0 — fires vec 32.
    trap    #0

    | Phase 3: returned from TRAP via RTE.  A7 should be MSP_BASE again.
    cmp.l   #MSP_BASE, %a7
    bne     _fail_phase3_a7

    | Verify MSP slot still holds MSP_BASE.
    .short  0x4E7A, 0x0803            | MOVEC MSP,d0 (cr=0x803, dest D0)
    cmp.l   #MSP_BASE, %d0
    bne     _fail_phase3_msp

    | Verify ISP slot still holds ISP_BASE.
    .short  0x4E7A, 0x0804            | MOVEC ISP,d0 (cr=0x804, dest D0)
    cmp.l   #ISP_BASE, %d0
    bne     _fail_phase3_isp

    | Phase 4: ANDI.W #0xEFFF, %sr — clear M.
    .short  0x027C, 0xEFFF            | ANDI.W #0xEFFF, %sr
    cmp.l   #ISP_BASE, %a7
    bne     _fail_phase4

    | PASS
    lea     PASS_SENT, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

| ── TRAP #0 handler ─────────────────────────────────────────────────
| On entry: S=1, M=1 (TRAP doesn't clear M).  A7 = MSP_BASE - 8
| (fmt-0 frame: SR/PC = 4 bytes each, total 8 = "frame size 8").
_handler_trap0:
    | Verify A7 is MSP_BASE - 8.
    move.l  %a7, %d7
    cmp.l   #(MSP_BASE - 8), %d7
    bne     _fail_phase2
    rte

_fail_phase1:
    move.l  #0xDEAD0701, %d2
    bra     _do_fail
_fail_phase2:
    | We're in the trap handler — write directly without RTE.
    move.l  #0xDEAD0702, %d2
    bra     _do_fail
_fail_phase3_a7:
    move.l  #0xDEAD0703, %d2
    bra     _do_fail
_fail_phase3_msp:
    move.l  #0xDEAD0704, %d2
    bra     _do_fail
_fail_phase3_isp:
    move.l  #0xDEAD0705, %d2
    bra     _do_fail
_fail_phase4:
    move.l  #0xDEAD0706, %d2
_do_fail:
    lea     PASS_SENT, %a0
    move.l  %d2, (%a0)
_halt_fail:
    bra     _halt_fail
