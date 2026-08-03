| split_load_compose_matrix.s — misaligned LOAD composition matrix.
|
| The same split machinery in lsu.v serves loads and stores, and
| m68k_mem_split_rdata() reassembles the two beats.  The hardware
| failure under investigation reads back a value composed entirely of
| stale memory, which is equally consistent with "the store lost both
| halves" and with "the load composed the wrong halves".  This test
| pins down the LOAD side independently, against a byte pattern where
| every one of the twelve source bytes is distinct, so any wrong lane,
| swapped half or inherited-translation bug produces a visibly wrong
| answer rather than a lucky match.
|
| Source pattern at X:  00 11 22 33 | 44 55 66 77 | 88 99 AA BB
|
| Three geometries, each with LONG@1, LONG@2, LONG@3, WORD@3 and an
| aligned control:
|   G1  X inside a single 32 B D-cache line   (region+0x00)
|   G2  X positioned so every split crosses a 32 B line boundary
|       (region+0x1C, splits land at 0x1D..0x22)
|   G3  X positioned so every split crosses a 4 KiB page boundary
|       (0x00120FFC, splits land at 0x00120FFD..0x00121002)
|
| Run with the D-cache ON (TC.E=1, copyback DTT0, CACR.DE=1) — the
| configuration the failing hardware runs in.  Sentinel 0x0B is the
| positive control that the cache really is enabled.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels 0xDEAD08nn: n1..n5 = LONG@1 / LONG@2 / LONG@3 /
| WORD@3 / aligned-control, with n = 1/2/3 for G1/G2/G3.
|   0B  CONTROL: CACR.DE did not stick — cache OFF, results void
|   0A  unexpected address error (vec 3)

    .text

    .equ G1, 0x00100000
    .equ G2, 0x0011001C              | 0x00110000 is 32 B aligned
    .equ G3, 0x00120FFC

_start:
    lea     0x00010000, %a7
    move.l  #_addr_err, 0x0000000C

    | ── D-cache ON ────────────────────────────────────────────────
    move.l  #0x4000C000, %d7
    movec   %d7, %itt0
    move.l  #0x0000E020, %d7
    movec   %d7, %dtt0
    move.l  #0xFF00E060, %d7
    movec   %d7, %dtt1
    move.l  #0x8000, %d7
    movec   %d7, %tc
    move.l  #0x80000000, %d7
    movec   %d7, %cacr
    movec   %cacr, %d6
    cmp.l   %d7, %d6
    bne     _f0B

    | ── G1: within one cache line ─────────────────────────────────
    lea     G1, %a0
    bsr     _seed
    move.l  0x00(%a0), %d1
    cmp.l   #0x00112233, %d1
    bne     _f15
    move.l  0x01(%a0), %d1
    cmp.l   #0x11223344, %d1
    bne     _f11
    move.l  0x02(%a0), %d1
    cmp.l   #0x22334455, %d1
    bne     _f12
    move.l  0x03(%a0), %d1
    cmp.l   #0x33445566, %d1
    bne     _f13
    move.w  0x03(%a0), %d1
    and.l   #0xFFFF, %d1
    cmp.l   #0x3344, %d1
    bne     _f14

    | ── G2: every split crosses a 32 B line boundary ──────────────
    lea     G2, %a0
    bsr     _seed
    move.l  0x00(%a0), %d1
    cmp.l   #0x00112233, %d1
    bne     _f25
    move.l  0x01(%a0), %d1
    cmp.l   #0x11223344, %d1
    bne     _f21
    move.l  0x02(%a0), %d1
    cmp.l   #0x22334455, %d1
    bne     _f22
    move.l  0x03(%a0), %d1
    cmp.l   #0x33445566, %d1
    bne     _f23
    move.w  0x03(%a0), %d1
    and.l   #0xFFFF, %d1
    cmp.l   #0x3344, %d1
    bne     _f24

    | ── G3: every split crosses a 4 KiB page boundary ─────────────
    lea     G3, %a0
    bsr     _seed
    move.l  0x00(%a0), %d1
    cmp.l   #0x00112233, %d1
    bne     _f35
    move.l  0x01(%a0), %d1
    cmp.l   #0x11223344, %d1
    bne     _f31
    move.l  0x02(%a0), %d1
    cmp.l   #0x22334455, %d1
    bne     _f32
    move.l  0x03(%a0), %d1
    cmp.l   #0x33445566, %d1
    bne     _f33
    move.w  0x03(%a0), %d1
    and.l   #0xFFFF, %d1
    cmp.l   #0x3344, %d1
    bne     _f34

    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, (%a2)
_halt:  bra _halt

_seed:
    move.l  #0x00112233, 0x00(%a0)
    move.l  #0x44556677, 0x04(%a0)
    move.l  #0x8899AABB, 0x08(%a0)
    rts

_f11:   move.l #0xDEAD0811, 0xFFFF0000
_h11:   bra _h11
_f12:   move.l #0xDEAD0812, 0xFFFF0000
_h12:   bra _h12
_f13:   move.l #0xDEAD0813, 0xFFFF0000
_h13:   bra _h13
_f14:   move.l #0xDEAD0814, 0xFFFF0000
_h14:   bra _h14
_f15:   move.l #0xDEAD0815, 0xFFFF0000
_h15:   bra _h15
_f21:   move.l #0xDEAD0821, 0xFFFF0000
_h21:   bra _h21
_f22:   move.l #0xDEAD0822, 0xFFFF0000
_h22:   bra _h22
_f23:   move.l #0xDEAD0823, 0xFFFF0000
_h23:   bra _h23
_f24:   move.l #0xDEAD0824, 0xFFFF0000
_h24:   bra _h24
_f25:   move.l #0xDEAD0825, 0xFFFF0000
_h25:   bra _h25
_f31:   move.l #0xDEAD0831, 0xFFFF0000
_h31:   bra _h31
_f32:   move.l #0xDEAD0832, 0xFFFF0000
_h32:   bra _h32
_f33:   move.l #0xDEAD0833, 0xFFFF0000
_h33:   bra _h33
_f34:   move.l #0xDEAD0834, 0xFFFF0000
_h34:   bra _h34
_f35:   move.l #0xDEAD0835, 0xFFFF0000
_h35:   bra _h35
_f0B:   move.l #0xDEAD080B, 0xFFFF0000
_h0B:   bra _h0B
_addr_err:
    move.l  #0xDEAD080A, 0xFFFF0000
_h0A:   bra _h0A
