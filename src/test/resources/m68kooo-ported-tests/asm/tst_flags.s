| tst_flags.s — TST.L Dn flag-setting tests
|
| TST.L computes Dn - 0 for flag purposes only (sets N and Z, clears V and C).
| Register is NOT modified. This is used to quickly check if a register is
| negative or zero without a compare immediate.
|
| Tests:
|   TST.L of 0     → Z=1, N=0
|   TST.L of -1    → Z=0, N=1
|   TST.L of +1    → Z=0, N=0
|   TST.L of 0x80000000 → N=1, Z=0
|   Verify register is unchanged after TST
|
| Triage note (2026-04-16, tests-triage agent):
|   CORE BUG.  All hand-computed flag values and branch directions
|   match the test's expectations; the test logic is correct.  But
|   the core fails mid-way through, due to a bug in the CDB wake-up
|   path (iq_int / iq_mem do not gate the phys-tag match on
|   cdb_has_dst, so CMP / TST / Bcc spurious broadcasts wake up
|   consumers of the same phys tag with stale PRF data).  See
|   docs/core_gaps.md entry.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000

    .text
    .org 0

_start:
    | ── TST.L 0 → Z=1 ──
    moveq   #0, %d0
    tst.l   %d0                 | Z=1, N=0
    bne     _fail               | Z must be set
    bmi     _fail               | N must be clear

    | ── TST.L -1 → N=1, Z=0 ──
    move.l  #0xFFFFFFFF, %d0
    tst.l   %d0                 | N=1, Z=0
    beq     _fail               | Z must be clear
    bpl     _fail               | N must be set

    | ── TST.L +1 → N=0, Z=0 ──
    moveq   #1, %d0
    tst.l   %d0                 | N=0, Z=0
    beq     _fail               | Z must be 0
    bmi     _fail               | N must be 0

    | ── TST.L 0x80000000 → N=1 ──
    move.l  #0x80000000, %d0
    tst.l   %d0                 | N=1, Z=0
    bpl     _fail               | N must be set
    beq     _fail               | Z must be clear

    | ── TST.L does not modify register ──
    move.l  #0xABCDEF01, %d1
    tst.l   %d1                 | flags updated, D1 unchanged
    move.l  #0xABCDEF01, %d2
    cmp.l   %d2, %d1
    bne     _fail               | D1 must be unchanged

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
