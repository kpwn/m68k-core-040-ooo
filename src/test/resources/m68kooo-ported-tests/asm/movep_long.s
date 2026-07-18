| movep_long.s — MOVEP.L round-trip between Dn and 4-byte-strided memory.
|
| MOVEP.L writes 4 bytes (big-endian: MSB first) at offsets
| 0, 2, 4, 6 from (An+d16).  The inverse (mem→reg) reads the same
| 4 bytes into all 4 bytes of Dn (the whole register).
|
| To avoid addressing modes the decoder doesn't yet support, we
| verify the memory side via WORD readbacks and rely on sentinel
| bytes in the untouched lanes (odd offsets 7,9,11,13).
|
| Sequence:
|   1. Pre-fill 4 words at offsets 8, 10, 12, 14 with 0x00AA
|      (bytes: 0x00 0xAA at each even/odd pair).  A MOVEP.L writes
|      the 4 data bytes at offsets 8, 10, 12, 14 — bytes at 9, 11,
|      13, 15 retain the 0xAA sentinel.
|   2. D0 = 0x11223344.  MOVEP.L D0, 8(A0).
|   3. Readback words at A0+8, +10, +12, +14 — expect 0x11AA,
|      0x22AA, 0x33AA, 0x44AA.
|   4. Plant 4 source words at A0+0x20..0x26 with high byte varying.
|   5. MOVEP.L 0x20(A0),D1.  Expect D1 = 0xAABBCCDD.
|
| PASS: sentinel 0xC0FFEE00.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    lea     0x00020000, %a0
    lea     0x00020008, %a1
    lea     0x0002000a, %a2
    lea     0x0002000c, %a3
    lea     0x0002000e, %a4

    | Prefill: write 0x00AA at each word target — byte 9,11,13,15 become 0xAA.
    move.w  #0x00AA, (%a1)
    move.w  #0x00AA, (%a2)
    move.w  #0x00AA, (%a3)
    move.w  #0x00AA, (%a4)

    move.l  #0x11223344, %d0
    movep.l %d0, 8(%a0)

    move.w  (%a1), %d2
    andi.l  #0xFFFF, %d2
    cmp.l   #0x11AA, %d2
    bne     _fail
    move.w  (%a2), %d2
    andi.l  #0xFFFF, %d2
    cmp.l   #0x22AA, %d2
    bne     _fail
    move.w  (%a3), %d2
    andi.l  #0xFFFF, %d2
    cmp.l   #0x33AA, %d2
    bne     _fail
    move.w  (%a4), %d2
    andi.l  #0xFFFF, %d2
    cmp.l   #0x44AA, %d2
    bne     _fail

    | Plant source bytes at 0x20, 0x22, 0x24, 0x26 via word stores;
    | high byte of each word is the MOVEP source byte, low byte is don't-care.
    lea     0x00020020, %a1
    lea     0x00020022, %a2
    lea     0x00020024, %a3
    lea     0x00020026, %a4
    move.w  #0xAA00, (%a1)   | byte 0x20 = 0xAA
    move.w  #0xBB00, (%a2)   | byte 0x22 = 0xBB
    move.w  #0xCC00, (%a3)   | byte 0x24 = 0xCC
    move.w  #0xDD00, (%a4)   | byte 0x26 = 0xDD

    move.l  #0x99999999, %d1
    movep.l 0x20(%a0), %d1
    cmp.l   #0xAABBCCDD, %d1
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a1)
_halt_fail:
    bra     _halt_fail
