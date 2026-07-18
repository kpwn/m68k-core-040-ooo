| negx_mem.s — NEG / NEGX with non-indexed memory EA must decode + run.
|
| Sibling of the TST -(An) gap.  The V2 unary-mem non-indexed assembler
| (decode_uop_assemble.v `unary_mem_dst_is_nonidx`) originally covered
| only CLR/NOT/TST; NEG/NEGX mem-dst non-indexed had no V2 owner and the
| legacy .vh rows were retired ("DEAD"), so NEG.W (An) / NEG.W -(An) /
| NEGX.W (An) etc. illegal-trapped (vec-4) despite being valid 68040
| data-alterable EAs.
|
| NEG  <ea>: ea = 0 - ea          ; sets X/N/Z/V/C.
| NEGX <ea>: ea = 0 - ea - X      ; X=0 here so value-equal to NEG.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0004  vec-4 illegal-instruction trap — THE BUG
|   0xDEAD0E61  NEG.W (A0) wrong result
|   0xDEAD0E62  NEG.W -(A0) wrong result / A0 not decremented
|   0xDEAD0E63  NEG.W (A0)+ wrong result / A0 not incremented
|   0xDEAD0E64  NEG.L -(A0) wrong result / A0 not decremented
|   0xDEAD0E65  NEGX.W (A0) wrong result
|   0xDEAD0E66  NEGX.W -(A0) wrong result / A0 not decremented

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_illegal, 0x00000010        | vector 4 (illegal instr) @ 0x10

    | ── NEG.W (A0) ── plain (An)
    move.l  #0x00020002, %a0
    move.w  #0x0005, (%a0)
    neg.w   (%a0)                         | mem.W[0x20002] = -5 = 0xFFFB
    move.w  (%a0), %d1
    cmp.w   #0xFFFB, %d1
    bne     _f1

    | ── NEG.W -(A0) ── predecrement
    move.l  #0x00020004, %a0
    move.w  #0x0005, -2(%a0)             | seed word at 0x20002
    neg.w   -(%a0)                        | A0 → 0x20002, mem.W = 0xFFFB
    cmp.l   #0x00020002, %a0
    bne     _f2
    move.w  (%a0), %d1
    cmp.w   #0xFFFB, %d1
    bne     _f2

    | ── NEG.W (A0)+ ── postincrement
    move.l  #0x00020002, %a0
    move.w  #0x0005, (%a0)
    neg.w   (%a0)+                        | A0 → 0x20004, mem.W[0x20002]=0xFFFB
    cmp.l   #0x00020004, %a0
    bne     _f3
    move.w  -2(%a0), %d1
    cmp.w   #0xFFFB, %d1
    bne     _f3

    | ── NEG.L -(A0) ── predecrement, long
    move.l  #0x00020008, %a0
    move.l  #0x00000005, -4(%a0)         | seed long at 0x20004
    neg.l   -(%a0)                        | A0 → 0x20004, mem.L = 0xFFFFFFFB
    cmp.l   #0x00020004, %a0
    bne     _f4
    move.l  (%a0), %d1
    cmp.l   #0xFFFFFFFB, %d1
    bne     _f4

    | ── NEGX.W (A0) ── plain (An), X=0 → result = -dst
    move.l  #0x00020002, %a0
    move.w  #0x0005, (%a0)
    move.w  #0, %ccr                      | clear X
    negx.w  (%a0)
    move.w  (%a0), %d1
    cmp.w   #0xFFFB, %d1
    bne     _f5

    | ── NEGX.W -(A0) ── predecrement, X=0
    move.l  #0x00020004, %a0
    move.w  #0x0005, -2(%a0)
    move.w  #0, %ccr                      | clear X
    negx.w  -(%a0)                        | A0 → 0x20002, mem.W = 0xFFFB
    cmp.l   #0x00020002, %a0
    bne     _f6
    move.w  (%a0), %d1
    cmp.w   #0xFFFB, %d1
    bne     _f6

    | PASS
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_f1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0E61, %d0
    move.l  %d0, (%a0)
_h1:
    bra     _h1

_f2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0E62, %d0
    move.l  %d0, (%a0)
_h2:
    bra     _h2

_f3:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0E63, %d0
    move.l  %d0, (%a0)
_h3:
    bra     _h3

_f4:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0E64, %d0
    move.l  %d0, (%a0)
_h4:
    bra     _h4

_f5:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0E65, %d0
    move.l  %d0, (%a0)
_h5:
    bra     _h5

_f6:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0E66, %d0
    move.l  %d0, (%a0)
_h6:
    bra     _h6

_illegal:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0004, %d0             | vec-4 illegal-instruction trap — THE BUG
    move.l  %d0, (%a0)
_h7:
    bra     _h7
