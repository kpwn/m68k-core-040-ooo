| exc_addr_error_odd_rtd.s — RTD #d popping an ODD return address leaves the stack
| pointer and the stack untouched, in SUPERVISOR and in USER mode.
|
| Same restart contract as RTS (exc_addr_error_odd_rts_user.s): the frame PC is the
| RTD and no architectural state has changed. RTD is cracked [pop load][A7 adjust]
| [ibranch]; with the A7 adjust as its own A7-writing uop it RETIRES before the
| ibranch raises vector 3 and A7 has already moved by 4+d. CPU_RTD_PRECISE_A7 moves
| the A7 commit onto the ibranch (the adjust computes T1). Expected to FAIL with
| CPU_RTD_PRECISE_A7=0 (pre-existing RTD defect).
|
| PASS: 3 stages trapped with the exact format-$2 frame, SP/USP and the stacked
| return address unchanged, and a good RTD deallocates correctly.  FAIL: 0xBADBAD00.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler3, 0x0000000C  | vector 3 (address error)
    moveq   #0, %d6

    | ── Stage 1: supervisor RTD #8 ────────────────────────────────────
    move.l  #0x22222222, -(%a7)
    move.l  #0x11111111, -(%a7)
    lea     _s1_odd+1, %a3
    lea     _s1_rtd, %a4
    lea     _s1_cont, %a5
    move.l  %a3, -(%a7)
    move.l  %a7, %a6
_s1_rtd:
    rtd     #8
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
    move.l  (%a7)+, %d0
    cmp.l   #0x22222222, %d0
    bne     _fail

    | ── Stage 2: supervisor RTD with a NEGATIVE displacement ──────────
    lea     _s2_odd+1, %a3
    lea     _s2_rtd, %a4
    lea     _s2_cont, %a5
    move.l  %a3, -(%a7)
    move.l  %a7, %a6
_s2_rtd:
    rtd     #-16
_s2_odd:
    nop
_s2_cont:
    cmp.l   %a6, %a7
    bne     _fail
    move.l  (%a7)+, %d0
    cmp.l   %a3, %d0
    bne     _fail

    | ── Stage 3: USER-mode RTD #12 ────────────────────────────────────
    move.l  #0x00008000, %a0
    move.l  %a0, %usp
    andi.w  #0xDFFF, %sr
    lea     _s3_odd+1, %a3
    lea     _s3_rtd, %a4
    lea     _s3_cont, %a5
    move.l  %a3, -(%a7)
    move.l  %a7, %a6
_s3_rtd:
    rtd     #12
_s3_odd:
    nop
_s3_cont:
    cmp.l   %a6, %a7
    bne     _fail
    move.l  (%a7)+, %d0
    cmp.l   %a3, %d0
    bne     _fail
    | a GOOD rtd: call a callee that pops 8 bytes of arguments
    move.l  #1, -(%a7)
    move.l  #2, -(%a7)
    bsr     _callee
    cmp.l   #0x00008000, %a7
    bne     _fail

    cmp.l   #3, %d6
    bne     _fail
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a1)
_halt:
    bra     _halt

_callee:
    rtd     #8

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xBADBAD00, %d1
    move.l  %d1, (%a1)
_fhlt:
    bra     _fhlt

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
    addq.l  #1, %d6
    move.l  %a5, 2(%a7)
    rte
