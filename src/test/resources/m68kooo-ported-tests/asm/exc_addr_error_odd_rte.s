| exc_addr_error_odd_rte.s — RTE to an odd resume PC raises vec 3
|
| An RTE whose exception frame carries an odd PC must raise Address
| Error (vector 3) instead of resuming at the odd address (the 68040 PC
| is always even — M68040UM §8.2.3).  This project's CONSERVATIVE model
| (documented in commit.v's take_rte_finalize): the malformed frame is
| left IN PLACE and the popped SR/A7 are NOT applied — exactly the
| vec-14 format-error shape — so the handler can fix the in-place frame
| and retry the RTE.  (Real silicon completes the RTE and faults on the
| prefetch; Musashi as configured has address-error emulation compiled
| out entirely and can't adjudicate the difference — see
| exc_addr_error_odd_branch.s header.)
|
| The vec-3 frame pushed for this case (format $2, offset 0x00C) carries
| the odd resume PC in BOTH the PC field (SP+2) and the instruction-
| address field (SP+8).
|
| Flow:
|   1. build a fake format-$0 frame whose PC field is odd
|   2. RTE through it → vec 3 fires, fake frame still on the stack
|   3. handler asserts the vec-3 frame fields, patches the FAKE frame's
|      PC to _resume_ok, patches its OWN frame PC to _the_rte, RTEs
|   4. _the_rte re-executes: RTE now succeeds through the fixed fake
|      frame → _resume_ok → PASS if the handler ran exactly once.
|
| PASS: D6 == 1 and the retried RTE lands at _resume_ok.
| FAIL: frame mismatch, or the first RTE resumes at the odd PC.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler3, 0x0000000C  | vector 3 (address error)
    moveq   #0, %d6

    lea     _odd_resume, %a3
    addq.l  #1, %a3                 | odd resume PC

    | Build a fake format-$0 frame: SR / PC(odd) / fmt-vec word.
    move.w  #0x0000, -(%a7)         | format $0, vec offset 0
    move.l  %a3, -(%a7)             | PC field = odd
    move.w  %sr, -(%a7)             | SR = current (stay supervisor)
_the_rte:
    rte

_odd_resume:
    nop                             | never executed (target region)

    | If the first RTE "succeeds" we end up somewhere in the stream
    | above/below the odd PC — any path that reaches here without the
    | handler having run exactly once is a FAIL via the D6 check.
_resume_ok:
    cmp.l   #1, %d6
    bne     _fail
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xBADBAD00, %d1
    move.l  %d1, (%a1)
_fhlt:
    bra     _fhlt

| ── Vector-3 handler ─────────────────────────────────────────────────
| At entry the stack is:
|   A7+0  : vec-3 format-$2 frame (12 bytes)
|   A7+12 : the untouched fake format-$0 frame (8 bytes)
_handler3:
    | format/vector word: format $2, vector offset 0x00C
    move.w  6(%a7), %d7
    cmp.w   #0x200C, %d7
    bne     _fail
    | frame PC = the odd resume PC
    move.l  2(%a7), %d7
    cmp.l   %a3, %d7
    bne     _fail
    | instruction-address field = the odd resume PC
    move.l  8(%a7), %d7
    cmp.l   %a3, %d7
    bne     _fail
    addq.l  #1, %d6
    | Fix the FAKE frame in place (12 bytes above): PC ← _resume_ok.
    lea     _resume_ok, %a1
    move.l  %a1, 14(%a7)
    | Resume at _the_rte to re-execute the (now fixed) RTE.
    lea     _the_rte, %a1
    move.l  %a1, 2(%a7)
    rte
