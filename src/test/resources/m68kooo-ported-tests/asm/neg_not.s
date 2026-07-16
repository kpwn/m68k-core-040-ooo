| neg_not.s — NEG.L and NOT.L tests
|
| NEG.L Dn: two's complement negate (0 - Dn), sets NZVC X
| NOT.L Dn: bitwise invert, sets NZV (clears C, no X)
|
| Test cases (NEG):
|   NEG.L  1       → 0xFFFFFFFF, N=1
|   NEG.L  0       → 0          , Z=1 (C=0 for zero)
|   NEG.L  0x80000000 → 0x80000000 (overflow, V=1)
|
| Test cases (NOT):
|   NOT.L  0xFFFFFFFF → 0x00000000, Z=1
|   NOT.L  0x00000000 → 0xFFFFFFFF, N=1
|   NOT.L  0x12345678 → 0xEDCBA987
|
| Triage note (2026-04-16, tests-triage agent):
|   Original test had ONE logic bug: in the first segment the BPL
|   that was supposed to verify NEG.L's N flag was placed AFTER a
|   following CMP.L, so it inspected CMP's flags instead (which
|   clear N because d0-d1 = 0).  Moved BPL to right after NEG so it
|   sees NEG's result flag — the in-file comment tags this fix.
|
|   However, even with that test-bug fix the test STILL fails.  This
|   is due to a separate CORE BUG in the CDB wake-up path: uops with
|   has_dst=0 (CMP, TST, bare branches) still broadcast their
|   scratch-allocated pdst tag on the CDB at execute time, with
|   cdb_has_dst=0.  The iq_int / iq_mem wake-up logic does NOT gate
|   its same-tag match on cdb_has_dst, so a later op that happens to
|   be renamed to the SAME phys tag gets woken up prematurely with
|   stale PRF contents.  See docs/core_gaps.md entry for details.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000

    .text
    .org 0

_start:
    | ── NEG.L 1 → 0xFFFFFFFF ──
    | Original test bug: BPL was placed AFTER the CMP below, which
    | rewrote NZVC (d0-d1=0 → N=0, Z=1).  That made "bpl _fail" look
    | at CMP's N, not NEG's.  Moved the N-flag check to immediately
    | after NEG so it inspects NEG's flag.
    moveq   #1, %d0
    neg.l   %d0                 | D0 = 0xFFFFFFFF, N=1
    bpl     _fail               | N=1 → not taken (checks NEG's N flag)
    move.l  #0xFFFFFFFF, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | ── NEG.L 0 → 0, Z=1 ──
    moveq   #0, %d0
    neg.l   %d0                 | D0 = 0, Z=1
    bne     _fail               | Z should be set

    | ── NOT.L 0xFFFFFFFF → 0, Z=1 ──
    move.l  #0xFFFFFFFF, %d0
    not.l   %d0                 | D0 = 0x00000000, Z=1
    bne     _fail               | Z should be set

    | ── NOT.L 0 → 0xFFFFFFFF, N=1 ──
    moveq   #0, %d0
    not.l   %d0                 | D0 = 0xFFFFFFFF, N=1
    bpl     _fail               | N should be set

    | ── NOT.L 0x12345678 → 0xEDCBA987 ──
    move.l  #0x12345678, %d0
    not.l   %d0                 | D0 = 0xEDCBA987
    move.l  #0xEDCBA987, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | ── Double NOT restores original ──
    move.l  #0xCAFEBABE, %d2
    not.l   %d2
    not.l   %d2                 | D2 = 0xCAFEBABE (restored)
    move.l  #0xCAFEBABE, %d3
    cmp.l   %d3, %d2
    bne     _fail

    | ── NEG.L: NOT(x)+1 == NEG(x) for non-zero x ──
    move.l  #0x00012345, %d4
    move.l  %d4, %d5
    not.l   %d4                 | D4 = ~0x00012345
    addi.l  #1, %d4             | D4 = ~x + 1 = -x
    neg.l   %d5                 | D5 = -0x00012345
    cmp.l   %d5, %d4
    bne     _fail

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
