| movea_test.s — MOVEA.L tests
|
| MOVEA.L loads an address register without altering CCR.
| This is critical: any code that relies on CCR being stable across
| an address-computation sequence would break if MOVEA updated flags.
|
| Variants tested:
|   MOVEA.L #imm32, An   — immediate to address register, used as mem base
|   MOVEA.L Dn, An       — data register to address register, used as mem base
|
| We verify An values by using them as base addresses for stores/loads.
| We use a scratch area in the same RAM where the test binary resides.
|
| Key property: CCR must be preserved across MOVEA.
|   Test: set Z=1 (via CMP equal), do MOVEA #imm, check Z still 1.
|
| Triage note (2026-04-16, tests-triage agent):
|   Original test had ONE logic bug: the "MOVEA Dn,An must not alter
|   Z" segment placed a `move.l #0x00030000,%d6` BETWEEN the Z-setting
|   CMP and the checking BNE.  MOVE.L to Dn updates NZVC (writes
|   flags_wr=01110), so it wiped out CMP's Z=1 before BNE read it.
|   Hoisted the `move.l` above the CMP so only MOVEA sits between
|   CMP and BNE.
|
|   Even with that fix the test STILL fails due to the SAME CORE BUG
|   that breaks tst_flags/cmpi_test/addi_subi/addq_subq_quirk/neg_not/
|   immediate_logic: iq_int wakes up stale CDB broadcasts from uops
|   with has_dst=0.  See docs/core_gaps.md entry.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000

    .text
    .org 0

_start:
    | ── MOVEA.L #imm, An: verify using An as store address ──
    | Store a known value at 0x00010000, then load it back via MOVEA base
    movea.l #0x00010000, %a1    | A1 = 0x00010000 (scratch address)
    move.l  #0xBEEFCAFE, %d0
    move.l  %d0, (%a1)          | store 0xBEEFCAFE at 0x00010000
    move.l  (%a1), %d1          | reload via a1 base
    move.l  #0xBEEFCAFE, %d2
    cmp.l   %d2, %d1
    bne     _fail               | store+load roundtrip via MOVEA base

    | ── MOVEA.L Dn, An: verify via store ──
    move.l  #0x00010004, %d3    | D3 = scratch address + 4
    movea.l %d3, %a2            | A2 = 0x00010004
    move.l  #0x12345678, %d4
    move.l  %d4, (%a2)          | store at 0x00010004
    move.l  (%a2), %d5          | load back
    cmp.l   %d4, %d5
    bne     _fail               | roundtrip via MOVEA-from-Dn base

    | ── CCR preserved: MOVEA #imm must not alter Z flag ──
    | Establish Z=1 using equal CMP
    moveq   #5, %d0
    moveq   #5, %d1
    cmp.l   %d1, %d0            | Z=1
    | Now do MOVEA — must not change CCR
    movea.l #0x00020000, %a3    | A3 = 0x00020000; CCR must still have Z=1
    bne     _fail               | if Z cleared by MOVEA → FAIL

    | ── CCR preserved: MOVEA Dn,An must not alter Z flag ──
    | Original test bug: `move.l #imm32, %d6` between the CMP (Z=1)
    | and the BNE rewrites NZVC (0x00030000 → Z=0, N=0), so BNE saw
    | CMP-independent flags and wrongly took the branch.  Load the
    | source address BEFORE the CMP so only MOVEA sits between CMP
    | and BNE (and MOVEA does not update CCR).
    move.l  #0x00030000, %d6    | load address into data reg first (before CMP)
    moveq   #9, %d0
    moveq   #9, %d1
    cmp.l   %d1, %d0            | Z=1
    movea.l %d6, %a4            | A4 = 0x00030000; CCR unchanged
    bne     _fail               | Z must still be 1

    | ── CCR preserved: MOVEA with non-zero result must not set N=1 ──
    | Establish C=1 via unsigned subtract that borrows
    moveq   #0, %d0
    subi.l  #1, %d0             | D0 = 0xFFFFFFFF, C=1
    movea.l #0x00001234, %a5    | MOVEA — must not clear C
    bcc     _fail               | C must still be 1 (MOVEA didn't touch it)

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
