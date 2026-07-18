| addq_subq_quirk.s — ADDQ.L / SUBQ.L tests including "0 encodes as 8" quirk
|
| On the 68040, ADDQ/SUBQ encode the immediate in 3 bits (op[11:9]).
| Encoding 000 means +8 (not +0). This test explicitly exercises that.
| Also verifies values 1-7, and the Dn destination (CCR update).
|
| Expected results:
|   ADDQ.L #8, D0=0  → D0=8  (000 = 8 quirk)
|   ADDQ.L #1, D0=0  → D0=1
|   ADDQ.L #7, D0=0  → D0=7
|   SUBQ.L #8, D0=8  → D0=0, Z=1  (000 = 8 quirk on SUBQ too)
|   SUBQ.L #1, D0=10 → D0=9
|   ADDQ.L #4, A1    → A1 advances (no CCR change, verified by flag state)
|
| Triage note (2026-04-16, tests-triage agent):
|   CORE BUG.  The "ADDQ/SUBQ-to-An does not update CCR" verification
|   at the end of the test is especially valuable (it's the property
|   that lets address-arithmetic sit between a CMP and a Bcc without
|   breaking the branch), and decode.v does set flags_wr=0 for that
|   case — so that decode path is correct.  However the earlier
|   sub-tests run into the same CDB wake-up bug that breaks the rest
|   of the new triage batch (repro: see cmpi_test.s note) so the
|   core gives up well before it reaches the An-CCR-preservation
|   checks.  See docs/core_gaps.md entry.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000

    .text
    .org 0

_start:
    | ── ADDQ.L #8 (encoding 000 → value 8) ──
    moveq   #0, %d0
    addq.l  #8, %d0             | D0 = 8 (uses 000 encoding)
    moveq   #8, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | ── ADDQ.L #1 ──
    moveq   #0, %d0
    addq.l  #1, %d0             | D0 = 1
    moveq   #1, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | ── ADDQ.L #7 ──
    moveq   #0, %d0
    addq.l  #7, %d0             | D0 = 7
    moveq   #7, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | ── SUBQ.L #8 (000 encoding → 8): 8-8=0, Z=1 ──
    moveq   #8, %d0
    subq.l  #8, %d0             | D0 = 0, Z=1
    bne     _fail               | Z must be 1

    | ── SUBQ.L #1 ──
    moveq   #10, %d0
    subq.l  #1, %d0             | D0 = 9
    moveq   #9, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | ── SUBQ.L #4 → N=1 (underflow) ──
    moveq   #2, %d0
    subq.l  #4, %d0             | D0 = -2 = 0xFFFFFFFE, N=1
    bpl     _fail               | N must be set

    | ── ADDQ.L #2 chain: 0+1+2+3+4+5+6+7+8 = 36 ──
    moveq   #0, %d0
    addq.l  #1, %d0             | D0 = 1
    addq.l  #2, %d0             | D0 = 3
    addq.l  #3, %d0             | D0 = 6
    addq.l  #4, %d0             | D0 = 10
    addq.l  #5, %d0             | D0 = 15
    addq.l  #6, %d0             | D0 = 21
    addq.l  #7, %d0             | D0 = 28
    addq.l  #8, %d0             | D0 = 36
    moveq   #36, %d1
    cmp.l   %d1, %d0
    bne     _fail               | sum must be 36

    | ── ADDQ.L to An: no CCR side-effect ──
    | Establish Z=1 via self-subtract
    moveq   #5, %d0
    sub.l   %d0, %d0            | D0 = 0, Z=1
    | ADDQ to address register — must not clear Z
    lea     0x00001000, %a1
    addq.l  #4, %a1             | A1 = 0x1004, CCR unchanged
    beq     _ccr_ok             | Z must still be 1
    bra     _fail
_ccr_ok:

    | ── SUBQ.L to An: no CCR side-effect ──
    moveq   #7, %d0
    sub.l   %d0, %d0            | D0 = 0, Z=1
    subq.l  #4, %a1             | A1 = 0x1000, CCR unchanged
    beq     _ccr_ok2
    bra     _fail
_ccr_ok2:

    | ── PASS ──────────────────────────────────────────────────────────────
_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)          | PASS sentinel
_halt:
    stop    #0x2700
    bra     _halt

    | ── FAIL ──────────────────────────────────────────────────────────────
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)          | FAIL sentinel
_halt_fail:
    stop    #0x2700
    bra     _halt_fail
