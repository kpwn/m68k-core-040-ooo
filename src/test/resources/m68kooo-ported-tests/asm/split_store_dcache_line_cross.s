| split_store_dcache_line_cross.s — split store whose two beats fall in
| DIFFERENT D-cache lines.
|
| The D-cache is 4 KB / 4-way / 32 B lines / 32 sets, so a split whose
| two aligned beats straddle a 32-byte boundary touches two different
| SETS.  Every other split test in the suite keeps both beats inside one
| line, where a broken second beat could still be masked by the first
| beat's line already being resident and dirty.
|
| Geometry (BASE is 32 B aligned):
|   LONG @ BASE+0x1D (1 mod 4) — bytes 0x1D..0x20, crosses at 0x20
|   LONG @ BASE+0x5E (2 mod 4) — bytes 0x5E..0x61, crosses at 0x60
|   LONG @ BASE+0x9F (3 mod 4) — bytes 0x9F..0xA2, crosses at 0xA0
|   WORD @ BASE+0xDF (3 mod 4) — bytes 0xDF..0xE0, crosses at 0xE0
| Each case gets its own 64-byte (two-line) region so the cases cannot
| mask each other.
|
| Both containing longs are pre-seeded (0xA1A2A3A4 / 0xB1B2B3B4) and
| read back with aligned loads, so an unwritten lane always shows a
| recognisable seed byte.
|
| THE D-CACHE IS DELIBERATELY TURNED ON.  mmu.v forces cache_inh=1
| globally while TC.E=0, so a test that leaves the MMU disabled runs
| entirely uncached and never touches the cache line logic at all —
| which would make "line crossing" a vacuous claim.  We therefore set
| TC.E=1, an ITT0/DTT0/DTT1 pass-through set with DTT0 in COPYBACK
| cache mode, and CACR.DE=1, exactly as cinv_line_basic.s does.  Fail
| sentinel 0x0B is the positive control that the cache really is on.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels 0xDEAD04nn:
|   01/02 LONG@1 lo/hi   03/04 LONG@2 lo/hi
|   05/06 LONG@3 lo/hi   07/08 WORD@3 lo/hi
|   09    CONTROL: seed not observable
|   0A    unexpected address error (vec 3)
|   0B    CONTROL: CACR.DE did not read back — cache is NOT enabled and
|         every "pass" below would be a false negative

    .text

    .equ BASE, 0x00100000            | 32 B aligned

_start:
    lea     0x00010000, %a7
    move.l  #_addr_err, 0x0000000C

    | ── Turn the D-cache ON (see header) ──────────────────────────
    | ITT0: 0x40xxxxxx pass-through for the instruction stream.
    move.l  #0x4000C000, %d7
    movec   %d7, %itt0
    | DTT0: 0x00xxxxxx pass-through, E=1, S=ignore, CM=01 (copyback).
    move.l  #0x0000E020, %d7
    movec   %d7, %dtt0
    | DTT1: 0xFFxxxxxx pass-through, CM=11 (non-cacheable) for the
    | sim-magic sentinel page.
    move.l  #0xFF00E060, %d7
    movec   %d7, %dtt1
    | TC.E = 1, 4 K pages.  Without this mmu.v forces cache_inh=1.
    move.l  #0x8000, %d7
    movec   %d7, %tc
    | CACR.DE = 1 (bit 31), and prove it stuck.
    move.l  #0x80000000, %d7
    movec   %d7, %cacr
    movec   %cacr, %d6
    cmp.l   %d7, %d6
    bne     _f0B

    lea     BASE, %a0

    | ── Seed the eight containing longs ───────────────────────────
    move.l  #0xA1A2A3A4, 0x1C(%a0)
    move.l  #0xB1B2B3B4, 0x20(%a0)
    move.l  #0xA1A2A3A4, 0x5C(%a0)
    move.l  #0xB1B2B3B4, 0x60(%a0)
    move.l  #0xA1A2A3A4, 0x9C(%a0)
    move.l  #0xB1B2B3B4, 0xA0(%a0)
    move.l  #0xA1A2A3A4, 0xDC(%a0)
    move.l  #0xB1B2B3B4, 0xE0(%a0)

    | CONTROL
    move.l  0x5C(%a0), %d1
    cmp.l   #0xA1A2A3A4, %d1
    bne     _f09
    move.l  0x60(%a0), %d1
    cmp.l   #0xB1B2B3B4, %d1
    bne     _f09

    | ── LONG @ 1 (mod 4), line-crossing ───────────────────────────
    move.l  #0x11223344, %d0
    move.l  %d0, 0x1D(%a0)
    move.l  0x1C(%a0), %d1
    cmp.l   #0xA1112233, %d1
    bne     _f01
    move.l  0x20(%a0), %d1
    cmp.l   #0x44B2B3B4, %d1
    bne     _f02

    | ── LONG @ 2 (mod 4), line-crossing — the HW shape ────────────
    move.l  #0x55667788, %d0
    move.l  %d0, 0x5E(%a0)
    move.l  0x5C(%a0), %d1
    cmp.l   #0xA1A25566, %d1
    bne     _f03
    move.l  0x60(%a0), %d1
    cmp.l   #0x7788B3B4, %d1
    bne     _f04

    | ── LONG @ 3 (mod 4), line-crossing ───────────────────────────
    move.l  #0x99AABBCC, %d0
    move.l  %d0, 0x9F(%a0)
    move.l  0x9C(%a0), %d1
    cmp.l   #0xA1A2A399, %d1
    bne     _f05
    move.l  0xA0(%a0), %d1
    cmp.l   #0xAABBCCB4, %d1
    bne     _f06

    | ── WORD @ 3 (mod 4), line-crossing ───────────────────────────
    move.w  #0xDEAD, %d0
    move.w  %d0, 0xDF(%a0)
    move.l  0xDC(%a0), %d1
    cmp.l   #0xA1A2A3DE, %d1
    bne     _f07
    move.l  0xE0(%a0), %d1
    cmp.l   #0xADB2B3B4, %d1
    bne     _f08

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, (%a1)
_halt:  bra _halt

_f01:   move.l #0xDEAD0401, 0xFFFF0000
_h01:   bra _h01
_f02:   move.l #0xDEAD0402, 0xFFFF0000
_h02:   bra _h02
_f03:   move.l #0xDEAD0403, 0xFFFF0000
_h03:   bra _h03
_f04:   move.l #0xDEAD0404, 0xFFFF0000
_h04:   bra _h04
_f05:   move.l #0xDEAD0405, 0xFFFF0000
_h05:   bra _h05
_f06:   move.l #0xDEAD0406, 0xFFFF0000
_h06:   bra _h06
_f07:   move.l #0xDEAD0407, 0xFFFF0000
_h07:   bra _h07
_f08:   move.l #0xDEAD0408, 0xFFFF0000
_h08:   bra _h08
_f09:   move.l #0xDEAD0409, 0xFFFF0000
_h09:   bra _h09
_f0B:   move.l #0xDEAD040B, 0xFFFF0000
_h0B:   bra _h0B
_addr_err:
    move.l  #0xDEAD040A, 0xFFFF0000
_h0A:   bra _h0A
