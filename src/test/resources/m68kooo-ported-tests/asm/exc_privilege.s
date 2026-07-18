| exc_privilege.s — variant of priv_violation that issues multiple
| privileged ops in sequence; verify that only the FIRST raises
| vec 8, since after vec 8 the handler halts (no RTE) and the rest
| of the sequence must not execute.
|
| Mechanic: drop to user mode, then run STOP immediately followed
| by ANDI.L #imm32, SR (also privileged) and MOVEC (also privileged).
| All three are privileged; only the FIRST (STOP) must fire vec 8.
| Handler checks a D7 sentinel pre-set in supervisor: if later
| privileged ops ran before the handler fired, they'd have had a
| chance to change arch state — but since privilege violation is
| in-order at commit, the handler entry point is deterministic.
|
| PASS: handler fires once, sees D7 = 0xCAFEBABE (pre-drop value).
| FAIL: handler sees anything else OR control falls through the
|       STOP without raising vec 8 (handler never fires).
|
| Vec 8 lives at 0x00000020.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_priv_handler, 0x00000020   | vec 8 handler
    move.l  #_wrong_handler, 0x00000010  | vec 4 (illegal) — must NOT fire
    move.l  #0xCAFEBABE, %d7             | pre-drop sentinel

    | Drop to user mode
    andi.w  #0xDFFF, %sr

    | Three privileged ops back-to-back.  All should be gated by the
    | same SR.S=0 check at commit; vec 8 fires on the FIRST one.
    stop    #0x2000                      | privileged #1 — should trap
    stop    #0x2000                      | privileged #2 — must not run
    stop    #0x2000                      | privileged #3 — must not run

    | If we fall through, it's a catastrophic miss.
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_wrong_handler:
    | Vec 4 should NOT fire for STOP.
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEE4, %d0
    move.l  %d0, (%a0)
_halt_wh:
    bra     _halt_wh

_priv_handler:
    cmp.l   #0xCAFEBABE, %d7         | verify pre-drop sentinel intact
    bne     _fail_h
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail_h:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEE7, %d0
    move.l  %d0, (%a0)
_halt_fh:
    bra     _halt_fh
