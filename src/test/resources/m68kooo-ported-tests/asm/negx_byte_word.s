| negx_byte_word.s — NEGX.B / NEGX.W Dn directed tests
|
| V2 decode-unary-reg landed NEGX.B / NEGX.W alongside the existing
| NEGX.L — the ALU's byte/word sized NEGX paths were added in the same
| commit.  PRM §4.84:
|   result = 0 - Dn.{B,W} - X ; C=X = borrow ; V set if sign-min ;
|   N from result's sign ; Z sticky — cleared only if sized result is
|   nonzero, preserved otherwise.
| Upper bits of Dn are preserved outside the addressed byte/word.
|
| Covered corners:
|   - NEGX.W with X=0 and non-zero word → upper 16 preserved.
|   - NEGX.B with X=0 and non-zero byte → upper 24 preserved.
|   - NEGX.B sign-min corner (0x80) → V=1.
|   - NEGX.W with X=1 from NEG.L borrow → 0 - 0x3344 - 1 = 0xCCBB.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000

    .text
    .org 0

_start:
    | ── NEGX.W D7: X=0, word = 0x3344 → 0xCCBC, upper preserved ──
    moveq   #0, %d6
    sub.l   %d6, %d6               | X=0
    move.l  #0x11223344, %d7
    negx.w  %d7
    move.l  #0x1122CCBC, %d0
    cmp.l   %d0, %d7
    bne     _fail

    | ── NEGX.B D0: X=0, byte = 0x05 → 0xFB, upper preserved ──
    moveq   #0, %d6
    sub.l   %d6, %d6               | X=0
    move.l  #0xAABBCC05, %d0
    negx.b  %d0
    move.l  #0xAABBCCFB, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | ── NEGX.B sign-min corner: 0x80 → 0x80, V=1 ──
    moveq   #0, %d6
    sub.l   %d6, %d6               | X=0
    move.l  #0xCAFE0080, %d5
    negx.b  %d5                    | V should be set
    bvc     _fail
    move.l  #0xCAFE0080, %d1
    cmp.l   %d1, %d5
    bne     _fail

    | ── NEGX.W with X=1 from preceding NEG.L borrow ──
    move.l  #0x00000001, %d2
    neg.l   %d2                    | d2 = 0xFFFFFFFF, X=C=1
    move.l  #0x11223344, %d3       | does not touch X
    negx.w  %d3                    | d3.w = 0 - 0x3344 - 1 = 0xCCBB
    move.l  #0x1122CCBB, %d4
    cmp.l   %d4, %d3
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    stop    #0x2700
    bra     _halt_fail
