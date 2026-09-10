| movem_idx_an_store.s — task movem-idx-an-store-2026-09-11:
| MOVEM.{W,L} <list>,(d8,An,Xn) brief-format An-indexed STORE.
|
| `(d8,An,Xn)` (EA mode 110) is a CONTROL ALTERABLE mode, so
| `MOVEM <list>,(d8,An,Xn)` is a real, legal MC68040 instruction
| (Musashi's own movem_re_* EA mask is `A+-DXWL`, i.e. it includes
| mode 6).  Until this task OperationDecoder.scala admitted mode 6
| for the LOAD direction only, so every store of this shape fell
| through to a vec-4 ILLEGAL — a divergence from silicon, and exactly
| the kind of latent ROM/OS failure the 040 campaign is closing.
| `movem_idx_unimpl_traps.s` used to PIN that trap; it no longer does.
|
| This is the STORE-direction mirror of movem_idx_an_load.s and covers
| the same axes: scale x1 and x4, .w and .l index registers, a
| negative d8, a multi-register list with an ODD tail (the FSM emits
| two transfers per cycle, so a 3-element list exercises the tail
| path), .W truncation, and the in-list-base/in-list-index case (a
| STORE writes no architectural register, so both the base An and the
| index Dn must be stored UNCHANGED and the EA must use their
| pre-instruction values).
|
| PASS: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_unexpected_trap, 0x00000008    | vec 2 (bus error) = FAIL guard
    move.l  #_unexpected_trap, 0x00000010    | vec 4 (illegal) = FAIL guard

    lea     0x00020000, %a0

    | --- (1) MOVEM.L %d2-%d3,(0,a0,d1.w) — scale x1, .w index, EA = a0+0.
    moveq   #0, %d1
    move.l  #0x11112222, %d2
    move.l  #0x33334444, %d3
    movem.l %d2-%d3, (0,%a0,%d1.w)
    move.l  (0,%a0), %d4
    cmp.l   #0x11112222, %d4
    bne     _fail1
    move.l  (4,%a0), %d4
    cmp.l   #0x33334444, %d4
    bne     _fail1

    | --- (2) MOVEM.L %d5-%d6,(8,a0,d7.l*4) — scaled long index: a0+8+2*4 = a0+16.
    move.l  #2, %d7
    move.l  #0xAABBCCDD, %d5
    move.l  #0xEEFF0011, %d6
    movem.l %d5-%d6, (8,%a0,%d7.l*4)
    move.l  (16,%a0), %d4
    cmp.l   #0xAABBCCDD, %d4
    bne     _fail2
    move.l  (20,%a0), %d4
    cmp.l   #0xEEFF0011, %d4
    bne     _fail2

    | --- (3) MOVEM.W %d1-%d2,(2,a1,d0.w*2) — .W stores truncate to 16 bits.
    | The indexed EA is computed ONCE (a1 + 2 + 1*2 = a1+4); the second
    | register lands at (EA + stride) = a1+6, NOT at a second independent
    | indexed computation.
    lea     0x00020100, %a1
    moveq   #1, %d0
    move.l  #0x1111FFFE, %d1
    move.l  #0x22220007, %d2
    movem.w %d1-%d2, (2,%a1,%d0.w*2)
    moveq   #0, %d4
    move.w  (4,%a1), %d4
    cmp.l   #0x0000FFFE, %d4
    bne     _fail3
    moveq   #0, %d4
    move.w  (6,%a1), %d4
    cmp.l   #0x00000007, %d4
    bne     _fail3

    | --- (4) the base An AND the index Dn are THEMSELVES in the stored list.
    | A store writes no register, so both must be written to memory with
    | their pre-instruction values and A2 must survive unchanged (mode 6
    | is a control mode: no auto-update).
    lea     0x00020200, %a2
    moveq   #0, %d0
    movem.l %d0/%a2, (0,%a2,%d0.l)
    move.l  (0,%a2), %d4
    cmp.l   #0, %d4
    bne     _fail4
    move.l  (4,%a2), %d4
    cmp.l   #0x00020200, %d4
    bne     _fail4
    cmp.l   #0x00020200, %a2
    bne     _fail4

    | --- (5) NEGATIVE d8 + a 3-register list (odd tail: the FSM emits two
    | transfers per cycle, so 3 elements exercise the single-transfer tail).
    lea     0x00020300, %a3
    move.l  #0x10, %d0
    move.l  #0x0A0A0A0A, %d1
    move.l  #0x0B0B0B0B, %d2
    move.l  #0x0C0C0C0C, %d3
    movem.l %d1-%d3, (-8,%a3,%d0.l)          | a3 - 8 + 0x10 = a3+8
    move.l  (8,%a3), %d4
    cmp.l   #0x0A0A0A0A, %d4
    bne     _fail5
    move.l  (12,%a3), %d4
    cmp.l   #0x0B0B0B0B, %d4
    bne     _fail5
    move.l  (16,%a3), %d4
    cmp.l   #0x0C0C0C0C, %d4
    bne     _fail5

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

_fail5:
    move.l  #0xDEAD0005, %d7
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt
