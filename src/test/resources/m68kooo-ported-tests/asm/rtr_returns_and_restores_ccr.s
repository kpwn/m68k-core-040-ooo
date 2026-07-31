| rtr_returns_and_restores_ccr.s — RTR must return AND restore the CCR.
|
| WHY THIS EXISTS (2026-07-29, ioResult investigation)
| ------------------------------------------------------------------------
| docs/isa_status.md marks RTR as "BR_RTR opcode reserved in uop_pkg.v but
| no decoder row.  Rare in real code" — but Mac OS's File Manager uses it
| in the standard interrupt-safe wrapper idiom:
|
|     0002FCD4  move.w  SR,-(A7)
|     0002FCD6  movem.l D0-D2/A0-A1,-(A7)
|     0002FCDC  move.w  #$0040,-(A7)
|     0002FCE0  A88F                      ; toolbox call
|     0002FCE4  movem.l (A7)+,D0-D2/A0-A1
|     0002FCE8  4E77                      ; RTR  <-- undecoded on this CPU
|
| On hardware this RTR is REACHED and executes, no vector-4 trap fires, and
| control never returns to the caller — the signature of a silent fall-through
| into whatever code follows.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD7701 — RTR fell through instead of returning
|   0xDEAD7702 — RTR returned to the wrong address
|   0xDEAD7703 — RTR did not restore the CCR from the stack
|   0xDEAD7704 — RTR disturbed the high byte of SR (supervisor bits)
|   0xDEAD7705 — stack not balanced across RTR

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.w  #0x2000, %sr

    | ── Case 1: RTR returns at all, and to the right place ─────────────
    move.l  %a7, %d6                | remember SP
    bsr     use_rtr
    | if RTR fell through, control never gets here
    cmp.l   %a7, %d6
    bne     fail_bal

    | ── Case 2: RTR restored the CCR word we planted (Z set) ───────────
    move.w  %sr, %d7
    andi.w  #0x0004, %d7            | isolate Z
    beq     fail_ccr                | planted CCR had Z SET

    | ── Case 3: supervisor bits untouched (RTR restores CCR only) ──────
    move.w  %sr, %d7
    andi.w  #0x2000, %d7            | S bit must still be set
    beq     fail_sup

    move.l  #0xC0FFEE00, %d1
    move.l  %d1, PASS_SENT
    bra     .

| ── the Mac OS wrapper idiom: push CCR, do work, RTR ───────────────────
| Entered by BSR, so (A7) holds the return address.  Push a CCR word on
| top of it, clobber the live flags, then RTR — which must pop the CCR
| AND the return address.
use_rtr:
    move.w  #0x0004, -(%a7)         | planted CCR: Z set, others clear
    moveq   #1, %d0
    tst.l   %d0                     | live flags now Z CLEAR — must be undone
    rtr

fail_ret:
    move.l  #0xDEAD7702, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_ccr:
    move.l  #0xDEAD7703, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_sup:
    move.l  #0xDEAD7704, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_bal:
    move.l  #0xDEAD7705, %d1
    move.l  %d1, PASS_SENT
    bra     .
