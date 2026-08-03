| split_store_page_cross_nommu.s — split store across a 4 KiB boundary
| with the MMU OFF.
|
| mmu_split_crosses_page_boundary.s / mmu_split_page_cross_cold_atc.s
| cover the translated case.  This one isolates the NON-translated half
| of the problem: the two beats still cross a 4 KiB boundary (which the
| AXI fabric and the dcache eviction/refill path both care about) but
| there is no MMU, no ATC and no walker in the picture at all.  If this
| passes and the MMU variants fail, the defect is in translation, not in
| the split datapath.
|
| Three separate page boundaries so the cases cannot mask each other:
|   LONG @ 0x00100FFD (1 mod 4) — spans 0x00100FFD..0x00101000
|   LONG @ 0x00102FFE (2 mod 4) — spans 0x00102FFE..0x00103001  [HW shape]
|   LONG @ 0x00104FFF (3 mod 4) — spans 0x00104FFF..0x00105002
|   WORD @ 0x00106FFF (3 mod 4) — spans 0x00106FFF..0x00107000
|
| Both containing longs pre-seeded, read back aligned.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels 0xDEAD05nn:
|   01/02 LONG@1 lo/hi   03/04 LONG@2 lo/hi
|   05/06 LONG@3 lo/hi   07/08 WORD@3 lo/hi
|   09    CONTROL: seed not observable
|   0A    unexpected address error (vec 3)

    .text

_start:
    lea     0x00010000, %a7
    move.l  #_addr_err, 0x0000000C

    | ── Seed the containing longs on both sides of each boundary ──
    move.l  #0xA1A2A3A4, 0x00100FFC
    move.l  #0xB1B2B3B4, 0x00101000
    move.l  #0xA1A2A3A4, 0x00102FFC
    move.l  #0xB1B2B3B4, 0x00103000
    move.l  #0xA1A2A3A4, 0x00104FFC
    move.l  #0xB1B2B3B4, 0x00105000
    move.l  #0xA1A2A3A4, 0x00106FFC
    move.l  #0xB1B2B3B4, 0x00107000

    | CONTROL
    move.l  0x00102FFC, %d1
    cmp.l   #0xA1A2A3A4, %d1
    bne     _f09
    move.l  0x00103000, %d1
    cmp.l   #0xB1B2B3B4, %d1
    bne     _f09

    | ── LONG @ 1 (mod 4) across the page boundary ─────────────────
    move.l  #0x11223344, %d0
    move.l  #0x00100FFD, %a1
    move.l  %d0, (%a1)
    move.l  0x00100FFC, %d1
    cmp.l   #0xA1112233, %d1
    bne     _f01
    move.l  0x00101000, %d1
    cmp.l   #0x44B2B3B4, %d1
    bne     _f02

    | ── LONG @ 2 (mod 4) across the page boundary — HW shape ──────
    move.l  #0x55667788, %d0
    move.l  #0x00102FFE, %a1
    move.l  %d0, (%a1)
    move.l  0x00102FFC, %d1
    cmp.l   #0xA1A25566, %d1
    bne     _f03
    move.l  0x00103000, %d1
    cmp.l   #0x7788B3B4, %d1
    bne     _f04

    | ── LONG @ 3 (mod 4) across the page boundary ─────────────────
    move.l  #0x99AABBCC, %d0
    move.l  #0x00104FFF, %a1
    move.l  %d0, (%a1)
    move.l  0x00104FFC, %d1
    cmp.l   #0xA1A2A399, %d1
    bne     _f05
    move.l  0x00105000, %d1
    cmp.l   #0xAABBCCB4, %d1
    bne     _f06

    | ── WORD @ 3 (mod 4) across the page boundary ─────────────────
    move.w  #0xDEAD, %d0
    move.l  #0x00106FFF, %a1
    move.w  %d0, (%a1)
    move.l  0x00106FFC, %d1
    cmp.l   #0xA1A2A3DE, %d1
    bne     _f07
    move.l  0x00107000, %d1
    cmp.l   #0xADB2B3B4, %d1
    bne     _f08

    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, (%a2)
_halt:  bra _halt

_f01:   move.l #0xDEAD0501, 0xFFFF0000
_h01:   bra _h01
_f02:   move.l #0xDEAD0502, 0xFFFF0000
_h02:   bra _h02
_f03:   move.l #0xDEAD0503, 0xFFFF0000
_h03:   bra _h03
_f04:   move.l #0xDEAD0504, 0xFFFF0000
_h04:   bra _h04
_f05:   move.l #0xDEAD0505, 0xFFFF0000
_h05:   bra _h05
_f06:   move.l #0xDEAD0506, 0xFFFF0000
_h06:   bra _h06
_f07:   move.l #0xDEAD0507, 0xFFFF0000
_h07:   bra _h07
_f08:   move.l #0xDEAD0508, 0xFFFF0000
_h08:   bra _h08
_f09:   move.l #0xDEAD0509, 0xFFFF0000
_h09:   bra _h09
_addr_err:
    move.l  #0xDEAD050A, 0xFFFF0000
_h0A:   bra _h0A
