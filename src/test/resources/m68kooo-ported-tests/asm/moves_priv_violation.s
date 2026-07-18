| moves_priv_violation.s — user-mode MOVES must trap vec 8.
|
| Mechanic: install a vec-8 handler that writes the PASS sentinel,
| drop to user mode via ANDI.W, then execute MOVES.L D0,(A0).  If
| the core correctly privilege-checks at commit, the MOVES is
| diverted to vec 8 BEFORE any memory write happens, and the
| handler fires.  If the check is missing, the MOVES writes to
| memory and control falls through to _fail.
|
| Modelled on exc_privilege.s.  Vec 8 lives at 0x00000020.
|
| PASS: handler writes 0xC0FFEE00 to 0xFFFF0000.
| FAIL: either the MOVES goes through silently or a wrong handler
|       fires.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_priv_handler,  0x00000020    | vec 8
    move.l  #_wrong_handler, 0x00000010    | vec 4 — must NOT fire
    move.l  #0xCAFEBABE, %d7               | pre-drop sentinel

    lea     0x00030000, %a0
    move.l  #0xA5A5A5A5, %d0

    | Drop to user mode.
    andi.w  #0xDFFF, %sr

    | Privileged op.  Expect vec 8 before the write happens.
    moves.l %d0, (%a0)

    | If we reach here the privilege check was missing.
_fail_fallthrough:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, (%a1)
_halt_fail:
    bra     _halt_fail

_wrong_handler:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEE4, %d2
    move.l  %d2, (%a1)
_halt_wh:
    bra     _halt_wh

_priv_handler:
    cmp.l   #0xCAFEBABE, %d7         | sentinel must survive (MOVES must NOT have run)
    bne     _fail_h
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_h:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEE7, %d2
    move.l  %d2, (%a1)
_halt_fh:
    bra     _halt_fh
