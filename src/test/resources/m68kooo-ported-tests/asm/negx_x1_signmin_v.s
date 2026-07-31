| negx_x1_signmin_v.s — NEGX V flag at the sign-min boundary WITH X=1
|
| Root-cause regression for the 2026-07-22 fuzz "dropped write" family
| (seeds 571/591/1103 + 1688623072): the ALU computed NEGX's V flag with
| NEG's rule (V = src == 0x80/0x8000/0x80000000), which ignores X.
|
| NEGX is 0 - src - X — a SUBX with a zero minuend — so signed overflow
| is src[msb] & result[msb] (Musashi: FLAG_V = src & res).  The two
| rules agree everywhere EXCEPT src == sign-min with X=1:
|     0 - 0x80 - 1 = 0x7F   → no overflow, V must be 0
| but the old rule still claimed V=1.  The wrong V flipped every
| following signed branch (blt/bge/bgt taken the wrong way), so the RTL
| skipped/executed whole wrong blocks — the fuzz diffs showed the
| skipped block's stores as silently "dropped" memory writes.
|
| Covered corners (each size, both X values at the boundary):
|   - NEGX.B 0x80 with X=1 → 0x7F, V=0, N=0 (and blt must fall through)
|   - NEGX.W 0x8000 with X=1 → 0x7FFF, V=0
|   - NEGX.L 0x80000000 with X=1 → 0x7FFFFFFF, V=0
|   - NEGX.W 0x8000 with X=0 → 0x8000, V=1 (boundary keeps overflowing)
|   - NEGX.L 0x80000000 with X=0 → V=1
| (the X=0 byte case is already covered by negx_byte_word.s)
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000

    .text
    .org 0

_start:
    | ── NEGX.B 0x80, X=1 → 0x7F, V=0, N=0; upper bits preserved ──
    move.l  #0x00000001, %d6
    neg.l   %d6                    | X=1 (borrow), d6=0xFFFFFFFF
    move.l  #0xCAFE0080, %d0
    negx.b  %d0                    | 0 - 0x80 - 1 = 0x7F
    bvs     _fail                  | old bug: V=1 here
    blt     _fail                  | the fuzz-observed flip: N^V must be 0
    move.l  #0xCAFE007F, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | ── NEGX.W 0x8000, X=1 → 0x7FFF, V=0 ──
    move.l  #0x00000001, %d6
    neg.l   %d6                    | X=1
    move.l  #0xBEEF8000, %d2
    negx.w  %d2
    bvs     _fail
    move.l  #0xBEEF7FFF, %d1
    cmp.l   %d1, %d2
    bne     _fail

    | ── NEGX.L 0x80000000, X=1 → 0x7FFFFFFF, V=0 ──
    move.l  #0x00000001, %d6
    neg.l   %d6                    | X=1
    move.l  #0x80000000, %d3
    negx.l  %d3
    bvs     _fail
    move.l  #0x7FFFFFFF, %d1
    cmp.l   %d1, %d3
    bne     _fail

    | ── NEGX.W 0x8000, X=0 → 0x8000, V=1 (boundary still overflows) ──
    moveq   #0, %d6
    sub.l   %d6, %d6               | X=0
    move.l  #0xBEEF8000, %d4
    negx.w  %d4
    bvc     _fail
    move.l  #0xBEEF8000, %d1
    cmp.l   %d1, %d4
    bne     _fail

    | ── NEGX.L 0x80000000, X=0 → 0x80000000, V=1 ──
    moveq   #0, %d6
    sub.l   %d6, %d6               | X=0
    move.l  #0x80000000, %d5
    negx.l  %d5
    bvc     _fail
    move.l  #0x80000000, %d1
    cmp.l   %d1, %d5
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
