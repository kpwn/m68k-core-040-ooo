| rtr_ccr_only_and_user_mode.s — the two RTR corners rtr_returns_and_
| restores_ccr.s does not pin down.
|
| WHY THIS EXISTS (2026-07-29, RTR implementation)
| ------------------------------------------------------------------------
| RTR (0x4E77, PRM §4.174) is:
|     CCR <- (SP)+    ; WORD pop, ONLY bits 4..0 (X N Z V C) restored
|     PC  <- (SP)+    ; LONG pop
| The two things easiest to get wrong while implementing it are exactly
| the two things that separate RTR from RTE:
|
|   1. RTR is NOT privileged.  A supervisor gate copied from RTE turns
|      every user-mode RTR into a vec-8 privilege violation.
|   2. RTR restores the CCR ONLY.  The popped word's HIGH byte must be
|      DISCARDED — not written into SR[15:8].  An implementation that
|      routes the popped word through an SR restore silently flips
|      S / M / T / IPL.
|
| Case A pins (2) with an EXACT SR compare in supervisor mode: a popped
| word of 0xFFFF must leave SR == 0x271F (high byte still 0x27 from the
| MOVE #0x2700,SR, low byte all five flags set).
|
| Case B pins (1) AND (2) from user mode: the popped word 0x2004 has the
| S-bit position set.  After the RTR we must (a) not have trapped, (b)
| see Z set, and (c) STILL BE IN USER MODE — proven by a privileged
| CPUSHA trapping to vec 8, which is the PASS path.  If RTR leaked the
| popped 0x2000 into SR we would be in supervisor, CPUSHA would NOT
| trap, and we fall through to the 0xDEAD7716 sentinel.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD7710 — RTR trapped as privileged / undecoded (vec 8 in case A)
|   0xDEAD7714 — RTR raised ILLEGAL (vec 4) — no decoder row
|   0xDEAD7711 — case A: SR wrong after RTR (high byte or CCR bits)
|   0xDEAD7712 — case A: stack not balanced across RTR
|   0xDEAD7713 — case B: CCR not restored in user mode
|   0xDEAD7715 — case B: stack not balanced across RTR
|   0xDEAD7716 — case B: RTR leaked the popped high byte into SR (we
|                 came back as supervisor, so CPUSHA did not trap)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7

    | VBR = 0 at reset.  vec 8 (privilege) at 0x20, vec 4 (illegal) at
    | 0x10.  Both are FAIL handlers for case A — an RTR that trapped
    | either way is a decoder/privilege bug, and this makes it a loud
    | sentinel instead of a 200000-cycle timeout.
    move.l  #_fail_priv_a, 0x00000020
    move.l  #_fail_illegal, 0x00000010

    | S=1, IPL=7 (interrupts masked for determinism), T=0, CCR=0.
    move.w  #0x2700, %sr

    | ── Case A: supervisor — popped high byte must be discarded ────────
    move.l  %a7, %d6                | remember SP
    bsr     _sub_a
    | Flags first: the SR compare below is the real check, but the
    | balance check would clobber CCR, so do the SR read before it.
    move.w  %sr, %d7
    cmp.l   %a7, %d6
    bne     _fail_bal_a
    cmp.w   #0x271F, %d7            | high byte 0x27 intact, CCR = 0x1F
    bne     _fail_sr_a

    | ── Case B: user mode — RTR must be legal AND must not set S ───────
    move.l  #_pass_priv_b, 0x00000020   | vec 8 is now the PASS path
    move.l  #0x00011000, %a0
    move.l  %a0, %usp
    andi.w  #0xDFFF, %sr            | drop to user; A7 becomes USP

    move.l  %a7, %d6
    bsr     _sub_b
    bne     _fail_ccr_b             | planted CCR word 0x2004 has Z SET
    cmp.l   %a7, %d6
    bne     _fail_bal_b

    | Still user mode?  CPUSHA is supervisor-only, so it must trap to
    | vec 8 — which is _pass_priv_b.  Reaching the next instruction
    | means RTR wrote the popped 0x2000 into SR.S.
    cpusha  %dc

    move.l  #0xDEAD7716, %d1
    move.l  %d1, PASS_SENT
    bra     .

| ── Case A callee: push a CCR word whose HIGH byte is garbage ──────────
| Entered by BSR, so (A7) holds the return address.  0xFFFF sets all
| five CCR bits and fills the discarded high byte with 0xFF.
_sub_a:
    move.w  #0xFFFF, -(%a7)
    moveq   #1, %d0
    tst.l   %d0                     | live flags now Z clear, X clear
    rtr

| ── Case B callee: popped high byte carries the S-bit position ────────
_sub_b:
    move.w  #0x2004, -(%a7)         | Z set; 0x2000 must NOT reach SR
    moveq   #1, %d0
    tst.l   %d0                     | live flags now Z CLEAR
    rtr

| ── vec 8 in user mode after a correct RTR: the PASS path ─────────────
_pass_priv_b:
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, PASS_SENT
    bra     .

_fail_priv_a:
    move.l  #0xDEAD7710, %d1
    move.l  %d1, PASS_SENT
    bra     .
_fail_illegal:
    move.l  #0xDEAD7714, %d1
    move.l  %d1, PASS_SENT
    bra     .
_fail_sr_a:
    move.l  #0xDEAD7711, %d1
    move.l  %d1, PASS_SENT
    bra     .
_fail_bal_a:
    move.l  #0xDEAD7712, %d1
    move.l  %d1, PASS_SENT
    bra     .
_fail_ccr_b:
    move.l  #0xDEAD7713, %d1
    move.l  %d1, PASS_SENT
    bra     .
_fail_bal_b:
    move.l  #0xDEAD7715, %d1
    move.l  %d1, PASS_SENT
    bra     .
