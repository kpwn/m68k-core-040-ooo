| priv_usp_isolation.s — USP/SSP isolation across a mode switch.
|
| Sequence:
|   1. Supervisor: set A7 = 0x00010000 (SSP).
|   2. Drop to user: ANDI.W #$DFFF,SR.  SSP captures 0x00010000;
|      A7 is loaded from stored USP (= 0 at reset).
|   3. User: set A7 = 0x00008000 (USP).  The a7_mirror path in
|      commit.v writes USP <- 0x00008000 one cycle later.
|   4. User pushes a marker via BSR (pushes ret_pc to -(A7) then
|      branches).  After the BSR, A7 should be 0x00007FFC.
|   5. Privileged STOP → vec 8 handler.  In the handler we're in
|      supervisor, so A7 is the SSP stack with the exception frame
|      pushed.  Use MOVE USP,An to recover the saved USP and check
|      it equals 0x00007FFC.
|
| DEFERRED: MOVE USP decodes as SYS_NOP in phase 2.1 (see decode.v
| comments around the 0x4E68 block).  The PRF transfer that reads
| USP into An is not wired.  This test will fail (or time out)
| until the movec-vbr agent lands the data-transfer path.
|
| Vec 8 lives at 0x00000020.

    .text
    .org 0

_start:
    lea     0x00010000, %a7              | SSP base
    move.l  #_priv_handler, 0x00000020   | vec 8 handler
    move.l  #_bsr_target, %d7            | remember expected path

    | Drop to user mode.  SSP <- 0x00010000; A7 <- USP (0).
    andi.w  #0xDFFF, %sr

    | User mode.  Establish a user stack at 0x00008000.
    lea     0x00008000, %a7

    | Push something via BSR.  A7 becomes 0x00007FFC.
    bsr     _bsr_target

    | If BSR returned (it must not, since the callee STOPs which
    | is privileged → traps), fall through to FAIL.
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_bsr_target:
    | Still in user mode.  Trigger privilege-violation.
    stop    #0x2000
    bra     _fail                        | must not execute

_priv_handler:
    | We're in supervisor now.  A7 is SSP + frame.  Recover the
    | saved USP via MOVE USP,A0 (privileged).
    move    %usp, %a0
    cmp.l   #0x00007FFC, %a0
    bne     _fail_h

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_fail_h:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEE8, %d0
    move.l  %d0, (%a1)
_halt_fh:
    bra     _halt_fh
