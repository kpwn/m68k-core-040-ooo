| divl_sz1_overflow.s — DIVU.L / DIVS.L SZ=1 overflow → V=1.
|
| Task #204 / B3: when the 64÷32 quotient doesn't fit in 32 bits PRM
| says V=1 and Dr:Dq are left unchanged.  mul_div.v's DONE state
| detects this via div_final_quot_hi != 0 (unsigned) or the signed
| edge case; output mux suppresses Dq/Dr writes by forwarding
| src_a_orig / src_c_orig on cdb0 / cdb_alu_hi respectively.

    .text
    .org 0

_start:
    | ── Test 1: DIVU.L SZ=1 unsigned overflow ────────────────────
    | {D0,D1} = 0x1_00000000 (= 2^32), divisor = 1.
    | true quotient = 2^32, doesn't fit in 32 bits → V=1.
    | Dr (D0) and Dq (D1) must remain 1 and 0 respectively.
    move.l  #0x00000001, %d0             | Dr sentinel
    move.l  #0x00000000, %d1             | Dq sentinel
    move.l  #1, %d2
    divu.l  %d2, %d0:%d1
    bvc     _fail                         | V must be set
    cmp.l   #0x00000001, %d0              | Dr preserved
    bne     _fail
    cmp.l   #0x00000000, %d1              | Dq preserved
    bne     _fail

    | ── Test 2: DIVS.L SZ=1 signed INT64_MIN / -1 → overflow ─────
    | {D3,D4} = 0x80000000_00000000 (INT64_MIN).  divisor = -1.
    | Mathematical quotient = +2^63, outside int32 → V=1.
    move.l  #0x80000000, %d3             | Dr sentinel
    move.l  #0x00000000, %d4             | Dq sentinel
    move.l  #-1, %d5
    divs.l  %d5, %d3:%d4
    bvc     _fail
    cmp.l   #0x80000000, %d3
    bne     _fail
    cmp.l   #0x00000000, %d4
    bne     _fail

    | ── Test 3: DIVS.L SZ=1 positive overflow ────────────────────
    | {D0,D1} = 0x00000001_00000000 (= 2^32).  divisor = 1.
    | Signed quotient = 2^32, outside int32 → V=1.
    move.l  #0x00000001, %d0             | Dr sentinel
    move.l  #0x00000000, %d1             | Dq sentinel
    divs.l  #1, %d0:%d1
    bvc     _fail
    cmp.l   #0x00000001, %d0
    bne     _fail
    cmp.l   #0x00000000, %d1
    bne     _fail

    | ── Test 4: DIVS.L SZ=1 overflow preserves prior N/Z/C ───────
    | Per Musashi: on overflow only V=1; N/Z/C inherit from the
    | prior CCR (PRM marks N/Z as "undefined" on overflow, but the
    | golden model leaves them untouched, so we must too).  Set
    | up the dividend, then assert N=1 via MOVEQ #-1 to a scratch
    | reg immediately before the divide.
    move.l  #0x00000001, %d0              | Dr=1 (high)
    move.l  #0x00000000, %d1              | Dq=0 (low) — dividend = 2^32
    moveq   #-1, %d6                       | sets N=1, Z=0 (last before divide)
    divs.l  #1, %d0:%d1                   | overflow → V=1, N preserved at 1
    bpl     _fail                          | N must still be 1 → BPL not taken
    bvc     _fail                          | V must be set
    cmp.l   #0x00000001, %d0
    bne     _fail
    cmp.l   #0x00000000, %d1
    bne     _fail

    | ── PASS ──────────────────────────────────────────────────────
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, (%a0)
    bra     _halt
