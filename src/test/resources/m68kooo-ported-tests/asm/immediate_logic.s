| immediate_logic.s — ORI.L, ANDI.L, EORI.L immediate logic tests
|
| Verifies the three immediate bitwise operations produce correct results
| and set NZVC flags appropriately (X is not modified).
|
| Test cases:
|   ORI.L  #0x0F0F0F0F, 0xF0F0F0F0 → 0xFFFFFFFF, N=1
|   ORI.L  #0, 0           → 0, Z=1
|   ANDI.L #0xFFFF0000, 0xDEADBEEF → 0xDEAD0000, N=1
|   ANDI.L #0, 0xFFFFFFFF  → 0, Z=1
|   EORI.L #0xFFFFFFFF, 0x12345678 → 0xEDCBA987
|   EORI.L #x, x           → 0, Z=1 (self-XOR)
|
| Triage note (2026-04-16, tests-triage agent):
|   Original test had TWO logic bugs: the BPL that was supposed to
|   verify ORI/ANDI's N flag was placed AFTER a following CMP.L, so
|   it inspected CMP's flags instead.  Both BPLs moved to immediately
|   after the ORI/ANDI they check.
|
|   Even with the test-bug fix the test STILL fails due to a CORE
|   BUG in the CDB wake-up path.  Minimal repro: `move.l #imm32,d0;
|   ori.l #imm,d0; move.l #imm32,d1; cmp.l d1,d0; bne tgt` — the
|   BNE mispredicts because iq_int accepts a stale CDB broadcast of
|   the physical tag now bound to d1.  See docs/core_gaps.md entry.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000

    .text
    .org 0

_start:
    | ── ORI.L: 0xF0F0F0F0 | 0x0F0F0F0F = 0xFFFFFFFF ──
    | Original test bug: BPL was placed AFTER the CMP below, which
    | rewrote NZVC with CMP's flags (d0==d1 → N=0, Z=1).  Moved the
    | ORI N-flag assertion to immediately after ORI so BPL inspects
    | ORI's flags, not CMP's.
    move.l  #0xF0F0F0F0, %d0
    ori.l   #0x0F0F0F0F, %d0    | D0 = 0xFFFFFFFF, N=1
    bpl     _fail               | N=1 → not taken (checks ORI's N flag)
    move.l  #0xFFFFFFFF, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | ── ORI.L: 0 | 0 = 0, Z=1 ──
    moveq   #0, %d0
    ori.l   #0, %d0             | D0 = 0, Z=1
    bne     _fail

    | ── ANDI.L: 0xDEADBEEF & 0xFFFF0000 = 0xDEAD0000 ──
    | Original test bug: BPL after CMP (see seg-1 note).  BPL is
    | supposed to verify ANDI's N flag; place it before CMP.
    move.l  #0xDEADBEEF, %d0
    andi.l  #0xFFFF0000, %d0    | D0 = 0xDEAD0000, N=1
    bpl     _fail               | N=1 → not taken (checks ANDI's N flag)
    move.l  #0xDEAD0000, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | ── ANDI.L: anything & 0 = 0, Z=1 ──
    move.l  #0xFFFFFFFF, %d0
    andi.l  #0, %d0             | D0 = 0
    bne     _fail               | Z must be 1

    | ── EORI.L: 0x12345678 ^ 0xFFFFFFFF = 0xEDCBA987 ──
    move.l  #0x12345678, %d0
    eori.l  #0xFFFFFFFF, %d0    | D0 = 0xEDCBA987
    move.l  #0xEDCBA987, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | ── EORI.L: self-XOR → 0, Z=1 ──
    move.l  #0xCAFEBABE, %d0
    eori.l  #0xCAFEBABE, %d0    | D0 = 0
    bne     _fail               | Z must be 1

    | ── ORI.L: result N=0 when MSB=0 ──
    moveq   #0, %d0
    ori.l   #0x7FFFFFFF, %d0    | D0 = 0x7FFFFFFF, N=0
    bmi     _fail               | N must be 0

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
