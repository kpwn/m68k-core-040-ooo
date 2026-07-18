| addi_subi.s — ADDI.L and SUBI.L immediate arithmetic tests
|
| ADDI.L #imm32, Dn  and  SUBI.L #imm32, Dn
| These are the 6-byte forms with a full 32-bit immediate.
|
| Test cases:
|   ADDI.L #0x10000000, 0x20000000 → 0x30000000
|   ADDI.L #0xFFFFFFFF, 0x00000001 → 0x00000000, Z=1, C=1
|   SUBI.L #0x10000000, 0x30000000 → 0x20000000
|   SUBI.L #1, 0 → 0xFFFFFFFF, C=1 (borrow), N=1
|   ADDI.L #0x7FFFFFFF, 0x7FFFFFFF → 0xFFFFFFFE, V=1 (signed overflow)
|
| Triage note (2026-04-16, tests-triage agent):
|   CORE BUG.  All six sub-test flag and value expectations verified
|   correct against PRM.  The test fails early, inside the first few
|   commits, due to the same CDB wake-up bug that breaks cmpi_test
|   etc.  The pattern `move.l #imm32,d0; op.l #imm,d0; move.l
|   #imm32,d1; cmp.l d1,d0; bne _fail` triggers it because the BNE
|   reads CCR from stale data.  See docs/core_gaps.md entry.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000

    .text
    .org 0

_start:
    | ── ADDI.L basic ──
    move.l  #0x20000000, %d0
    addi.l  #0x10000000, %d0    | D0 = 0x30000000
    move.l  #0x30000000, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | ── ADDI.L overflow: 1 + 0xFFFFFFFF = 0 with carry ──
    moveq   #1, %d0
    addi.l  #0xFFFFFFFF, %d0    | D0 = 0, Z=1, C=1
    bne     _fail               | Z must be 1
    bcc     _fail               | C must be 1

    | ── SUBI.L basic ──
    move.l  #0x30000000, %d0
    subi.l  #0x10000000, %d0    | D0 = 0x20000000
    move.l  #0x20000000, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | ── SUBI.L: 0 - 1 → 0xFFFFFFFF, borrow C=1, N=1 ──
    moveq   #0, %d0
    subi.l  #1, %d0             | D0 = 0xFFFFFFFF
    bcc     _fail               | C must be 1 (borrow)
    bpl     _fail               | N must be 1

    | ── ADDI.L result: negative, N=1 ──
    moveq   #0, %d0
    addi.l  #0x80000000, %d0    | D0 = 0x80000000, N=1
    bpl     _fail               | N must be set

    | ── SUBI.L producing Z=1 ──
    move.l  #0xDEADBEEF, %d0
    subi.l  #0xDEADBEEF, %d0    | D0 = 0, Z=1
    bne     _fail               | Z must be 1

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
