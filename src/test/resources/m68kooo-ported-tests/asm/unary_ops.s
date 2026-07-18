| unary_ops.s — Test for SWAP, NEG.L, NOT.L, EXT.L
|
| EXT.L test: D1 = 0x0000ABCD, ext.l → 0xFFFFABCD
| Compare with expected; branch to FAIL if wrong.
| SWAP test: D2 = 0x12345678, swap → 0x56781234
| NOT.L test on D3: D3 = 0x00000000, not.l → 0xFFFFFFFF
| NEG.L test on D4: D4 = 0x00000001, neg.l → 0xFFFFFFFF
|
| PASS = store 0xC0FFEE00 to 0xFFFF0000

    .text
    .org 0

_start:
    | ── EXT.L test ──────────────────────────────────────────────────
    move.l  #0x0000ABCD, %d1        | D1 = 0x0000ABCD (word sign bit set)
    ext.l   %d1                      | D1 = 0xFFFFABCD
    move.l  #0xFFFFABCD, %d0
    cmp.l   %d0, %d1
    bne     _fail

    | ── SWAP test ────────────────────────────────────────────────────
    move.l  #0x12345678, %d2
    swap    %d2                      | D2 = 0x56781234
    move.l  #0x56781234, %d0
    cmp.l   %d0, %d2
    bne     _fail

    | ── NOT.L test ───────────────────────────────────────────────────
    move.l  #0x00000000, %d3
    not.l   %d3                      | D3 = 0xFFFFFFFF
    move.l  #0xFFFFFFFF, %d0
    cmp.l   %d0, %d3
    bne     _fail

    | ── NEG.L test ───────────────────────────────────────────────────
    move.l  #0x00000001, %d4
    neg.l   %d4                      | D4 = 0xFFFFFFFF
    move.l  #0xFFFFFFFF, %d0
    cmp.l   %d0, %d4
    bne     _fail

    | ── PASS ─────────────────────────────────────────────────────────
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt:
    stop    #0x2700
    bra     _halt
