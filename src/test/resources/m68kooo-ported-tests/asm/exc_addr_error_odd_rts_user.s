| exc_addr_error_odd_rts_user.s — RTS popping an ODD return address leaves the
| stack pointer and the stack untouched, in SUPERVISOR and in USER mode.
|
| 68040 address error = restart semantics: the frame PC is the RTS itself and the
| RTS has not updated any architectural state. Under CPU_RTS_SPLIT_A7 the RTS is
| cracked [pop load][A7 += 4][ibranch] and the A7 add RETIRES before the ibranch
| raises vector 3, so the exception sequencer must give the 4 bytes back: on the
| active supervisor stack (frame at pre-RTS SP - 12) or on the USP (user mode).
| exc_addr_error_odd_branch.s covers the supervisor case at one depth; this adds
| the USER-mode (USP restore) case and a second supervisor depth.
|
| Per stage: A3 = expected odd target, A4 = expected fault PC, A5 = resume PC.
| PASS: 3 stages trapped with the exact format-$2 frame, SP/USP and the stacked
| return address unchanged.  FAIL: 0xBADBAD00.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler3, 0x0000000C  | vector 3 (address error)
    moveq   #0, %d6

    | ── Stage 1: supervisor RTS, odd return address two longs deep ───
    move.l  #0x11111111, -(%a7)
    lea     _s1_odd+1, %a3
    lea     _s1_rts, %a4
    lea     _s1_cont, %a5
    move.l  %a3, -(%a7)
    move.l  %a7, %a6                | pre-RTS SP
_s1_rts:
    rts
_s1_odd:
    nop
_s1_cont:
    cmp.l   %a6, %a7
    bne     _fail
    move.l  (%a7)+, %d0
    cmp.l   %a3, %d0
    bne     _fail
    move.l  (%a7)+, %d0
    cmp.l   #0x11111111, %d0
    bne     _fail
    cmp.l   #0x00010000, %a7
    bne     _fail

    | ── Stage 2: USER-mode RTS (USP) ──────────────────────────────────
    move.l  #0x00008000, %a0
    move.l  %a0, %usp
    andi.w  #0xDFFF, %sr            | drop to user mode
    lea     _s2_odd+1, %a3
    lea     _s2_rts, %a4
    lea     _s2_cont, %a5
    move.l  %a3, -(%a7)             | user stack: 0x7FFC
    move.l  %a7, %a6
_s2_rts:
    rts
_s2_odd:
    nop
_s2_cont:
    cmp.l   %a6, %a7                | USP unchanged by the faulting RTS
    bne     _fail
    move.l  (%a7)+, %d0
    cmp.l   %a3, %d0
    bne     _fail
    cmp.l   #0x00008000, %a7
    bne     _fail

    | ── Stage 3: a second user-mode RTS, then a GOOD one ─────────────
    lea     _s3_odd+1, %a3
    lea     _s3_rts, %a4
    lea     _s3_cont, %a5
    move.l  %a3, -(%a7)
_s3_rts:
    rts
_s3_odd:
    nop
_s3_cont:
    addq.l  #4, %a7
    bsr     _s3_leaf                | an ordinary call/return still works
    cmp.l   #0x00008000, %a7
    bne     _fail

    cmp.l   #3, %d6
    bne     _fail
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a1)
_halt:
    bra     _halt

_s3_leaf:
    rts

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xBADBAD00, %d1
    move.l  %d1, (%a1)
_fhlt:
    bra     _fhlt

| ── Vector-3 handler: assert the format-$2 frame, check ISP, resume at A5 ──
_handler3:
    move.w  6(%a7), %d7
    cmp.w   #0x200C, %d7
    bne     _fail
    move.l  2(%a7), %d7
    cmp.l   %a4, %d7
    bne     _fail
    move.l  8(%a7), %d7
    cmp.l   %a3, %d7
    bne     _fail
    | the frame sits exactly 12 bytes below the pre-fault supervisor SP
    cmp.l   #1, %d6
    bcc     _h_user
    move.l  %a6, %d7                | stage 1: supervisor, pre-RTS SP in A6
    sub.l   #12, %d7
    cmp.l   %a7, %d7
    bne     _fail
    bra     _h_ok
_h_user:
    cmp.l   #0x0000FFF4, %a7        | user stages: empty ISP (0x10000) - 12
    bne     _fail
_h_ok:
    addq.l  #1, %d6
    move.l  %a5, 2(%a7)
    rte
