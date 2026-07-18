| priv_user_stop_traps.s — STOP from user mode traps vec 8 (not halts).
|
| Goal: priv_violation.s already covers STOP from user mode triggering
| vec 8 once.  This test layers on:
|   1. STOP fires vec 8.
|   2. Handler clears the privilege violation, advances PC past STOP
|      (4 bytes — 1 opword + 1 immediate), RTEs back to user mode.
|   3. Mainline continues past STOP — proves vec 8 was a regular
|      exception, NOT a halt.  Without this proof we can't tell whether
|      STOP did the right thing or simply hung the core.
|   4. A second user-mode op (NOP) retires.  Sentinel write via TRAP.
|
| Difference vs priv_violation: that test halts inside the vec-8
| handler.  This one VERIFIES that recovery from STOP-priv is possible
| and the user code resumes correctly.
|
| PASS sentinel: 0xC0FFEE00 when post-STOP NOP retires.
| FAIL sentinels:
|   0xDEAD0521 — vec 8 didn't fire
|   0xDEAD0522 — RTE returned but skipped wrong amount (PC mismatch)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ STOP_FIRED, 0x00000900
    .equ POST_STOP,  0x00000904

_start:
    lea     0x00010000, %a7
    move.l  #0, STOP_FIRED.l
    move.l  #0, POST_STOP.l
    move.l  #_priv_h, 0x00000020
    move.l  #_trap_dispatch, 0x00000080

    move.l  #0x00008000, %a0
    move    %a0, %usp
    andi.w  #0xDFFF, %sr

_user_entry:
    stop    #0x2000                       | privileged in user mode
_after_stop:
    move.l  #1, POST_STOP.l               | proves we got past STOP
    trap    #0

_priv_h:
    | Skip the STOP (4 bytes: opword 0x4E72 + 1 immediate word).
    move.l  #1, STOP_FIRED.l
    move.l  2(%a7), %d3
    addq.l  #4, %d3
    move.l  %d3, 2(%a7)
    rte

_trap_dispatch:
    | In supervisor mode now.
    move.l  STOP_FIRED.l, %d0
    cmp.l   #1, %d0
    bne     _fail_no_priv
    move.l  POST_STOP.l, %d0
    cmp.l   #1, %d0
    bne     _fail_skip
    move.l  #0xC0FFEE00, 0xFFFF0000
_done:
    bra     _done

_fail_no_priv:
    move.l  #0xDEAD0521, 0xFFFF0000
_halt_np:
    bra     _halt_np

_fail_skip:
    move.l  #0xDEAD0522, 0xFFFF0000
_halt_sk:
    bra     _halt_sk
