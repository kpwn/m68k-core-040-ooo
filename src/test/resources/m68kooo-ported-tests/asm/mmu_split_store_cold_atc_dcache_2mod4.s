| mmu_split_store_cold_atc_dcache_2mod4.s — the full hardware
| configuration for a 2-mod-4 split STORE, in one test.
|
| The existing MMU split tests each cover part of it and each leave the
| D-cache OFF:
|   mmu_split_second_half_rewalks.s   real page tables, COLD ATC,
|                                     2 mod 4, both halves in ONE page,
|                                     but no CACR.DE → dcache bypassed
|   mmu_split_crosses_page_boundary.s warm ATC, page-crossing
|   mmu_split_page_cross_cold_atc.s   cold ATC + page-crossing (DEFER'd
|                                     red, task #213)
| and the split_store_* family added alongside this one enables the
| D-cache but reaches the MMU only through TTR pass-through, never the
| table walker.
|
| Nothing covered the intersection, which is exactly what the failing
| hardware runs: MMU on with REAL PAGE TABLES, D-cache ENABLED and in
| copyback mode, a 2-mod-4 misaligned LONG store, a COLD ATC so the
| first half runs the table walker (which is what arms mmu.v's
| `req_wait_drop`), and both halves in the SAME page but in DIFFERENT
| 32-byte cache lines.
|
| The hardware address 0x005F9F9E is 0x1E mod 0x20, so the real failing
| access crosses a cache line; VA 0x8000101E reproduces that offset.
|
| Both containing longs are pre-seeded with 0xA1A2A3A4 / 0xB1B2B3B4 and
| both lines are pushed out of the cache before the access, so neither
| the store's landing nor the seed can be faked by cache residency.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0B01 — low containing long wrong.  0xA1A2A3A4 here (untouched
|                seed) means the FIRST beat never landed.  0x5566B3B4
|                or similar means the SECOND beat inherited the first
|                half's translation (the task #213 mechanism).
|   0xDEAD0B02 — high containing long wrong.  0xB1B2B3B4 here means the
|                SECOND beat never landed.
|   0xDEAD0B03 — post-push re-read wrong: a beat landed in the cache but
|                did not survive the writeback to memory.
|   0xDEAD0B04 — CONTROL: seed not observable through the VA mapping.
|   0xDEAD0B05 — CONTROL: CACR.DE did not read back — the D-cache is
|                NOT enabled and this test's premise is void.
|   0xDEAD0B06 — unexpected bus error / page fault (vec 2).

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_buserr, 0x00100008
    move.l  #0x00100000, %d0
    movec   %d0, %vbr

    move.l  #0x4000C000, %d0
    movec   %d0, %itt0
    | DTT0 supervisor pass-through for low memory (page tables).
    move.l  #0x007FA000, %d0
    movec   %d0, %dtt0
    | DTT1 pass-through for the sentinel page, CM=11 NON-CACHEABLE.
    | The other mmu_split_* tests use 0xFF00A000 (CM=00, cacheable
    | writethrough) and get away with it only because they never set
    | CACR.DE.  With the D-cache actually on, a cacheable sentinel page
    | swallows the PASS write and the test times out having proved
    | nothing — found by this test's own control ladder.
    move.l  #0xFF00E060, %d0
    movec   %d0, %dtt1

    move.l  #0x00200000, %d0
    movec   %d0, %urp
    movec   %d0, %srp

    | VA 0x80001000 -> PA 0x00300000, 4 K, resident, U=1, M=1,
    | CM = 01 (CACHEABLE COPYBACK — the mode Mac OS runs RAM in).
    move.l  #0x0021000a, 0x00200100      | L1[0x40] -> L2 @ 0x210000
    move.l  #0x0022000a, 0x00210000      | L2[0x00] -> L3 @ 0x220000
    move.l  #0x00300039, 0x00220004      | L3[0x01] = PFN|CM01|M|U|DT01

    | Enable MMU: 3-level, 4 K pages.
    move.l  #0x00008770, %d0
    movec   %d0, %tc
    | Enable the D-cache and prove it stuck.
    move.l  #0x80000000, %d1
    movec   %d1, %cacr
    movec   %cacr, %d2
    cmp.l   %d1, %d2
    bne     _fail_ctl_de

    | ── Seed both containing longs THROUGH THE VA MAPPING ─────────
    move.l  #0x8000101C, %a2             | low  containing long
    move.l  #0x80001020, %a3             | high containing long (next line)
    move.l  #0xA1A2A3A4, (%a2)
    move.l  #0xB1B2B3B4, (%a3)
    move.l  (%a2), %d1
    cmp.l   #0xA1A2A3A4, %d1
    bne     _fail_ctl_seed

    | ── Cold everything: push both lines to memory, drop the ATC ──
    cpushl  %dc, (%a2)
    cpushl  %dc, (%a3)
    pflusha

    | ── THE ACCESS UNDER TEST ─────────────────────────────────────
    | LONG store at VA 0x8000101E: 2 mod 4, 0x1E mod 0x20 (crosses a
    | 32 B cache line), both halves in the same 4 K page, cold ATC so
    | the first half must run the table walker.
    move.l  #0x55667788, %d0
    move.l  #0x8000101E, %a1
    move.l  %d0, (%a1)

    | ── Read back through the same mapping ────────────────────────
    move.l  (%a2), %d1
    cmp.l   #0xA1A25566, %d1
    bne     _fail_lo
    move.l  (%a3), %d1
    cmp.l   #0x7788B3B4, %d1
    bne     _fail_hi

    | ── And again after forcing both lines out to real memory ─────
    cpushl  %dc, (%a2)
    cpushl  %dc, (%a3)
    move.l  (%a2), %d1
    cmp.l   #0xA1A25566, %d1
    bne     _fail_push
    move.l  (%a3), %d1
    cmp.l   #0x7788B3B4, %d1
    bne     _fail_push

    move.l  #0xC0FFEE00, 0xFFFF0000
_done:  bra _done

_fail_lo:
    move.l  #0xDEAD0B01, 0xFFFF0000
_h01:   bra _h01
_fail_hi:
    move.l  #0xDEAD0B02, 0xFFFF0000
_h02:   bra _h02
_fail_push:
    move.l  #0xDEAD0B03, 0xFFFF0000
_h03:   bra _h03
_fail_ctl_seed:
    move.l  #0xDEAD0B04, 0xFFFF0000
_h04:   bra _h04
_fail_ctl_de:
    move.l  #0xDEAD0B05, 0xFFFF0000
_h05:   bra _h05
_buserr:
    move.l  #0xDEAD0B06, 0xFFFF0000
_h06:   bra _h06
