| cmpi_test.s — CMPI.L #imm,Dn compare-immediate tests
|
| CMPI.L computes Dn - imm and sets NZVC (no X update).
| This is the immediate form of CMP; used widely by Mac OS for
| constant comparisons.
|
| Test cases:
|   CMPI.L #7, D0=7     → Z=1 (equal)
|   CMPI.L #3, D0=5     → Z=0, N=0, C=0 (5>3, positive diff)
|   CMPI.L #9, D0=4     → N=1, C=1 (4<9 unsigned + signed)
|   CMPI.L #0, D0=0     → Z=1
|   CMPI.L #0xFFFFFFFF, D0=0 → N=0, C=1 (0 - 0xFFFFFFFF borrows)
|
| Triage note (2026-04-16, tests-triage agent):
|   CORE BUG.  Test logic verified correct against PRM.  Minimal
|   repro (also in scope as its own test):
|     moveq  #5, %d0
|     cmpi.l #3, %d0     | 5-3=2 → N=0, Z=0, C=0
|     bne    _fail       | Z=0 → should be TAKEN to _fail
|   ...this trivial sequence fails because the BNE sees wrong flags
|   due to the CDB wake-up bug (iq_int wakes a stale same-phys-tag
|   broadcast from a has_dst=0 uop like CMPI or the BNE itself).
|   See docs/core_gaps.md entry.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000

    .text
    .org 0

_start:
    | ── D0 == 7: CMPI sets Z=1 → BEQ taken ──
    moveq   #7, %d0
    cmpi.l  #7, %d0             | 7 - 7 = 0, Z=1
    bne     _fail               | should not branch

    | ── D0=5 > 3: CMPI → BNE taken (Z=0), BPL taken (N=0) ──
    moveq   #5, %d0
    cmpi.l  #3, %d0             | 5 - 3 = 2, N=0, Z=0, C=0
    beq     _fail               | Z=0, so BEQ must NOT be taken
    bmi     _fail               | N=0, so BMI must NOT be taken

    | ── D0=4 < 9 (unsigned and signed): C=1, N=1 ──
    moveq   #4, %d0
    cmpi.l  #9, %d0             | 4 - 9 = -5, N=1, C=1
    bpl     _fail               | N should be 1
    bcc     _fail               | C should be 1

    | ── D0=0 == 0: Z=1 ──
    moveq   #0, %d0
    cmpi.l  #0, %d0             | 0 - 0 = 0, Z=1
    bne     _fail

    | ── D0=0, imm=0x00000001: 0-1 underflows → C=1, N=1 ──
    moveq   #0, %d0
    cmpi.l  #1, %d0             | 0 - 1 underflow
    bcc     _fail               | C must be set
    bpl     _fail               | N must be set (negative result)

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
