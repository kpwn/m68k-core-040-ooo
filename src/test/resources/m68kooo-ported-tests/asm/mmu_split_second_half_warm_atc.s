| mmu_split_second_half_warm_atc.s — the MISSING CELL of the split/ATC matrix.
|
| Byte-identical to mmu_split_second_half_rewalks.s except for ONE added
| dummy read of the page BEFORE the access under test, which leaves the
| ATC entry RESIDENT so the first half HITS instead of walking.
|
| WHY THIS CELL MATTERS (task #240).  The existing coverage is:
|
|                 first half WALKS        first half ATC HIT
|   same page     second_half_rewalks OK  <-- THIS FILE (was untested)
|   page cross    page_cross_cold_atc RED crosses_page_boundary OK
|
| The hardware failure is same-page with a WARM ATC: the corrupted push is
| a `bsrs` return address at A7=0x005EC842, i.e. the supervisor stack,
| whose page is touched constantly and is therefore always resident. So on
| HW the first half HITS; it does not walk. Every existing same-page test
| makes the first half walk, so this shape has never been exercised.
|
| Observed on HW (bitstream 0x1F67F746, System 7.5.3), halted on the
| resulting vec-3 with DDR and D-cache views agreeing byte-for-byte:
|     0x005EC840 = B6DB 4083     <- 0x842 got beat 1; 0x840 still POST pattern
|     0x005EC844 = B6DB 0405     <- beat 2 NEVER LANDED, still POST pattern
| The rts then pops 0x4083B6DB (odd) -> address error.
|
| If this test goes RED with 0xDEAD0A12 (second word untouched, seed
| 0x55667788 intact) it reproduces the hardware exactly.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0A11 — low word wrong.
|   0xDEAD0A12 — high word wrong; 0x55667788 = beat 2 never reached it.

_start:
    lea     0x00010000, %a7
    move.l  #_buserr, 0x00100008
    move.l  #_trap_h, 0x00100080
    move.l  #0x00100000, %d0
    movec   %d0, %vbr

    move.l  #0x4000C000, %d0
    movec   %d0, %itt0

    | DTT0 supervisor pass-through for low memory (page table + seed).
    move.l  #0x007FA000, %d0
    movec   %d0, %dtt0
    | DTT1 supervisor pass-through for the sentinel page.
    move.l  #0xFF00A000, %d0
    movec   %d0, %dtt1

    move.l  #0x00200000, %d0
    movec   %d0, %urp
    movec   %d0, %srp

    | VA 0x80001000 -> PA 0x00300000, 4K resident, writable.
    | L1[0x40] -> L2 @ 0x210000.
    move.l  #0x0021000a, 0x00200100
    | L2[0x00] -> L3 @ 0x220000.
    move.l  #0x0022000a, 0x00210000
    | L3[0x01] = PFN 0x00300000, U=1, M=1, DT=01 (resident, pre-modified
    | so the store does not take the extra set-M re-walk; the first-half
    | walk we care about is the cold-ATC one).
    move.l  #0x00300019, 0x00220004

    | Seed the two words the split store straddles.
    move.l  #0x11223344, 0x00300000
    move.l  #0x55667788, 0x00300004

    | Enable MMU, 3-level, 4K pages.  The ATC is empty at this point, so
    | the first D-side touch of VA 0x80001000 must run the table walker —
    | which is what arms req_wait_drop.
    move.l  #0x00008770, %d0
    movec   %d0, %tc

    andi.w  #0xDFFF, %sr
    lea     0x00008000, %a7

    | ---- THE ONE DELTA vs mmu_split_second_half_rewalks ----
    | Touch the page first so its ATC entry is RESIDENT.  The first half of
    | the split below then HITS the ATC instead of running the walker,
    | which is the hardware shape (supervisor stack, always warm).
    move.l  #0x80001000, %a4
    move.l  (%a4), %d7
    | ---------------------------------------------------------

    | THE ACCESS UNDER TEST: misaligned LONG store, offset 2 -> split.
    | First half HITS the warm ATC; second half must still be translated
    | on its own rather than inheriting the first half's result.
    move.l  #0xAABBCCDD, %d0
    move.l  #0x80001002, %a1
    move.l  %d0, (%a1)

    | Read both words back through the same mapping.
    move.l  #0x80001000, %a2
    move.l  (%a2), %d1
    cmp.l   #0x1122AABB, %d1
    bne     _fail_lo

    move.l  #0x80001004, %a3
    move.l  (%a3), %d2
    cmp.l   #0xCCDD7788, %d2
    bne     _fail_hi

    trap    #0

_fail_lo:
    move.l  #0xDEAD0A11, 0xFFFF0000
_halt_lo:
    bra     _halt_lo

_fail_hi:
    move.l  #0xDEAD0A12, 0xFFFF0000
_halt_hi:
    bra     _halt_hi

_buserr:
    move.l  #0xDEAD0903, 0xFFFF0000
_halt_be:
    bra     _halt_be

_trap_h:
    move.l  #0xC0FFEE00, 0xFFFF0000
_done:
    bra     _done
