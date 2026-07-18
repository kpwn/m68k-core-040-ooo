| roxl_roxr_basic.s — ROXL.L / ROXR.L with X chaining
|
| Hypothesis: ROXL/ROXR rotate a 33-bit value = {X, D}.  After the
| operation, C = X = the new X bit that fell off the opposite end.
|
| To prime X we use an LSL that sets C=X=1, then feed it into ROXL.
|
| Sequence:
|  1. D0 = 0x80000000, LSL.L #1, D0      → D0 = 0x00000000, X = 1
|  2. D1 = 0x00000000, ROXL.L #1, D1     → D1 = 0x00000001 (X=1 drops into LSB),
|                                           new X = C = 0
|  3. D2 = 0x80000000, LSL.L #1, D2      → D2 = 0, X = 1 (reset again)
|  4. D3 = 0x00000000, ROXR.L #1, D3     → D3 = 0x80000000, X = 0
|  5. After (4), ROXL.L #1, D3 should return D3 to 0 with X=1
|     (D3 was 0x80000000, X was 0 → {0,0x80000000} rotated left 1 =
|      {1, 0x00000000} — D3 becomes 0, new X = 1)

    .text
    .org 0

_start:
    | ── 1. Prime X=1 via LSL dropping a 1 out of bit31 ──
    move.l  #0x80000000, %d0
    lsl.l   #1, %d0                  | X <- 1, result 0

    | ── 2. ROXL.L #1 on zero pulls X=1 into LSB ──
    move.l  #0x00000000, %d1
    roxl.l  #1, %d1                  | D1 = 0x00000001, new X = 0
    move.l  #0x00000001, %d7
    cmp.l   %d7, %d1
    bne     _fail

    | Verify X is now 0: another ROXL of 0 must produce 0
    move.l  #0x00000000, %d2
    roxl.l  #1, %d2                  | D2 = 0 (X was 0)
    tst.l   %d2
    bne     _fail

    | ── 3. Re-prime X=1 ──
    move.l  #0x80000000, %d2
    lsl.l   #1, %d2                  | X <- 1

    | ── 4. ROXR.L #1 on zero pulls X=1 into MSB ──
    move.l  #0x00000000, %d3
    roxr.l  #1, %d3                  | D3 = 0x80000000, new X = 0
    move.l  #0x80000000, %d7
    cmp.l   %d7, %d3
    bne     _fail

    | ── 5. Further ROXL.L #1, D3: {X=0, 0x80000000} rotated-left-1 ──
    |        → new value = 0x00000000, new X = 1.
    roxl.l  #1, %d3
    tst.l   %d3
    bne     _fail                    | D3 must be 0

    | Verify X=1 now: ROXL #1 of 0 must produce 0x00000001
    move.l  #0x00000000, %d4
    roxl.l  #1, %d4
    move.l  #0x00000001, %d7
    cmp.l   %d7, %d4
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
