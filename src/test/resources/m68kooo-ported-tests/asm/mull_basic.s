| mull_basic.s — MULU.L / MULS.L 32×32→32 (SZ=0) basic directed test
|
| Exercises the SZ=0 (truncated to low 32 bits of product) forms
| cracked by decode.v as a single μop (ALU_MULUL / ALU_MULSL).
|
| Flag checks (PRM): MUL.L SZ=0 sets NZVC, leaves X alone.
|   MULU.L: V = (hi != 0)                      — unsigned fit-in-32
|   MULS.L: V = (hi != sign-extension of lo)   — signed fit-in-32
|   N = lo[31], Z = (lo == 0), C = 0
|
| IMPORTANT: V/N/Z are tested BEFORE any CMP — CMP clobbers all of
| NZVC.  Where a MUL result value also needs checking we do the
| flag check first (Bcc), then CMP the result value.

    .text
    .org 0

_start:
    | ── Test 1: MULU.L positive, no overflow ──────────────────────
    move.l  #5, %d0
    move.l  #3, %d1
    mulu.l  %d1, %d0                 | D0 = 15; N=0 Z=0 V=0 C=0
    bvs     _fail                     | V must be clear
    bmi     _fail                     | N must be clear
    beq     _fail                     | Z must be clear (15 != 0)
    cmp.l   #15, %d0
    bne     _fail

    | ── Test 2: MULS.L signed, negative result ────────────────────
    move.l  #7, %d0
    move.l  #-5, %d1
    muls.l  %d1, %d0                 | D0 = -35 = 0xFFFFFFDD; N=1 Z=0 V=0
    bvs     _fail                     | V must be clear
    bpl     _fail                     | N must be set
    beq     _fail                     | Z must be clear
    cmp.l   #-35, %d0
    bne     _fail

    | ── Test 3: MULU.L overflow (V=1, low=0 → Z=1) ────────────────
    move.l  #0x10000, %d0
    move.l  #0x10000, %d1
    mulu.l  %d1, %d0                 | prod=0x1_00000000, lo=0
                                      | N=0 Z=1 V=1 C=0
    bvc     _fail                     | V must be SET
    bne     _fail                     | Z must be SET
    bmi     _fail                     | N must be clear
    cmp.l   #0, %d0
    bne     _fail

    | ── Test 4: MULS.L signed overflow ────────────────────────────
    move.l  #0x40000, %d0            | 2^18
    move.l  #0x40000, %d1
    muls.l  %d1, %d0                 | prod=2^36
    bvc     _fail                     | V must be set

    | ── Test 5: MULU.L by zero → result 0, Z=1, V=0 ──────────────
    move.l  #0xDEADBEEF, %d0
    moveq   #0, %d1
    mulu.l  %d1, %d0                 | D0=0; Z=1 V=0 N=0
    bvs     _fail                     | V must be clear
    bmi     _fail                     | N must be clear
    bne     _fail                     | Z must be set
    cmp.l   #0, %d0
    bne     _fail

    | ── Test 6: MULS.L two negatives → positive ───────────────────
    move.l  #-3, %d0
    move.l  #-5, %d1
    muls.l  %d1, %d0                 | 15; V=0 N=0 Z=0
    bvs     _fail
    bmi     _fail
    beq     _fail
    cmp.l   #15, %d0
    bne     _fail

    | ── All PASS ───────────────────────────────────────────────────
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
    bra     _halt
