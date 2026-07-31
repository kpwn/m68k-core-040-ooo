| movem_idx_an_load.s — Phase-2 item #2: MOVEM.{W,L} (d8,An,Xn),<list>
| brief-format An-indexed LOAD source.  Prior to this fix,
| movem_ea_is_idx_an_f3 existed as a signal in decode.v but was never
| OR'd into v2_movem_ea_ok_load — only PC-indexed .L was admitted — so
| this shape fell through to vec-4 ILLEGAL.
|
| Exercises both MOVEM.L and MOVEM.W (d8,An,Xn) loads, with a
| non-zero-scale index to also cover the EA-compute crack's scale
| doubling phases, and a multi-register list to exercise the per-
| register load loop.
|
| PASS: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_unexpected_trap, 0x00000008    | vec 2 (bus error) = FAIL guard
    move.l  #_unexpected_trap, 0x00000010    | vec 4 (illegal) = FAIL guard

    lea     0x00020000, %a0

    | --- MOVEM.L (0,a0,d1.w),%d2-%d3 — scale x1 (no suffix).
    moveq   #0, %d1
    move.l  #0x11112222, (0,%a0,%d1.w)
    move.l  #0x33334444, (4,%a0,%d1.w)
    moveq   #0, %d2
    moveq   #0, %d3
    movem.l (0,%a0,%d1.w), %d2-%d3
    cmp.l   #0x11112222, %d2
    bne     _fail1
    cmp.l   #0x33334444, %d3
    bne     _fail1

    | --- MOVEM.L (0,a0,d4.l*4),%d5-%d6 — scaled index (x4), long index.
    move.l  #2, %d4
    move.l  #0xAABBCCDD, (8,%a0,%d4.l*4)     | a0 + 8 + 2*4 = a0+16
    move.l  #0xEEFF0011, (12,%a0,%d4.l*4)
    moveq   #0, %d5
    moveq   #0, %d6
    movem.l (8,%a0,%d4.l*4), %d5-%d6
    cmp.l   #0xAABBCCDD, %d5
    bne     _fail2
    cmp.l   #0xEEFF0011, %d6
    bne     _fail2

    | --- MOVEM.W (0,a1,d0.w*2),%d1-%d2 — .W load must sign-extend.
    | The indexed EA is computed ONCE (a1 + 2 + 1*2 = a1+4); the second
    | register then reads at (base + stride), i.e. a1+6 — NOT a second
    | independent indexed computation.
    lea     0x00020100, %a1
    moveq   #1, %d0
    move.w  #0xFFFE, (2,%a1,%d0.w*2)          | a1 + 2 + 1*2 = a1+4 → -2 sign-ext
    move.w  #0x0007, (6,%a1)                   | a1+4 + stride(2) = a1+6 →  7
    move.l  #0x11111111, %d1
    move.l  #0x22222222, %d2
    movem.w (2,%a1,%d0.w*2), %d1-%d2
    cmp.l   #-2, %d1
    bne     _fail3
    cmp.l   #7, %d2
    bne     _fail3

    | --- MOVEM.L An-indexed also covers an address register in the
    | list (renamed/live An must not corrupt the already-computed EA).
    lea     0x00020200, %a2
    moveq   #0, %d0
    move.l  #0x55556666, (0,%a2,%d0.w)
    move.l  #0x77778888, (4,%a2,%d0.w)
    lea     0x1, %a3
    moveq   #0, %d3
    movem.l (0,%a2,%d0.w), %d3/%a3
    cmp.l   #0x55556666, %d3
    bne     _fail4
    cmp.l   #0x77778888, %a3
    bne     _fail4

_pass:
    move.l  #0xC0FFEE00, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_unexpected_trap:
    move.l  #0xDEAD0000, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt

_fail2:
    move.l  #0xDEAD0002, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt

_fail3:
    move.l  #0xDEAD0003, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt

_fail4:
    move.l  #0xDEAD0004, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt
