| mmu_split_page_cross_cold_atc.s — RED. Split access across a page
| boundary takes a SPURIOUS vec-2 bus error when the ATC is cold.
|
| DEFER'd (tb/tests/deferred.txt) as a deliberate red test — see that
| file for the full write-up and the candidate mechanism.
|
| This file is byte-identical to mmu_split_crosses_page_boundary.s
| except for ONE added `pflusha` immediately before the access under
| test (plus staying in supervisor mode so PFLUSHA is legal).  That
| companion test PASSES.  So the entire delta between green and red is
| "were the two ATC entries resident".  The three in-test controls below
| pass in the failing run, which rules out the page-table setup, both
| mappings, the M bit and writability as explanations.
|
| lsu.v splits any misaligned LONG (m68k_mem_lane.vh:92) into two aligned
| beats and translates them separately, with this stated reason
| (lsu.v:936-940):
|
|   "First half translated.  Translate the second half independently
|    before launching any cache beat so split accesses crossing a page
|    boundary see the second page's mapping/fault/cache mode."
|
| Nothing in the directed suite actually crossed a page boundary with a
| split access, so that claim was untested.  The companion test
| mmu_split_second_half_rewalks.s covers the same-page case; this one
| covers the case the comment is actually about, where the two halves
| resolve to physically DISCONTIGUOUS pages and inheriting the first
| half's translation is unmistakable.
|
| VA layout:
|   page A: VA 0x80001000 -> PA 0x00300000
|   page B: VA 0x80002000 -> PA 0x00310000   (deliberately not adjacent)
|
| The access is a LONG store at VA 0x80001FFE:
|   first  half -> aligned VA 0x80001FFC (page A), bytes 2..3 -> AA BB
|   second half ->         VA 0x80002000 (page B), bytes 0..1 -> CC DD
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0A01 — page-A word wrong.  0xCCDDAABB here means the second
|                half was written through page A's translation, i.e. it
|                inherited the first half's PA instead of walking its own.
|   0xDEAD0A02 — page-B word wrong.  0x55667788 (untouched seed) here
|                means the second beat never reached page B at all.
|   0xDEAD0A03 — unexpected bus error / page fault.
|   0xDEAD0A04 — CONTROL: aligned read of page A's last long failed.
|   0xDEAD0A05 — CONTROL: aligned read of page B's first long failed.
|   0xDEAD0A06 — CONTROL: aligned write+readback of page B failed.
| The three controls exist so a failure of the real check cannot be
| blamed on the page-table setup: they exercise both mappings with
| ordinary aligned accesses first, and leave both ATC entries resident.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.l  #_buserr, 0x00100008
    move.l  #_trap_h, 0x00100080
    move.l  #0x00100000, %d0
    movec   %d0, %vbr

    move.l  #0x4000C000, %d0
    movec   %d0, %itt0

    | DTT0 supervisor pass-through for low memory (page table + seeds).
    move.l  #0x007FA000, %d0
    movec   %d0, %dtt0
    | DTT1 supervisor pass-through for the sentinel page.
    move.l  #0xFF00A000, %d0
    movec   %d0, %dtt1

    move.l  #0x00200000, %d0
    movec   %d0, %urp
    movec   %d0, %srp

    | L1[0x40] -> L2 @ 0x210000.
    move.l  #0x0021000a, 0x00200100
    | L2[0x00] -> L3 @ 0x220000.
    move.l  #0x0022000a, 0x00210000
    | L3[0x01] = page A -> PFN 0x00300000, U=1, M=1, DT=01.
    move.l  #0x00300019, 0x00220004
    | L3[0x02] = page B -> PFN 0x00310000, U=1, M=1, DT=01.
    move.l  #0x00310019, 0x00220008

    | Seed the last long of page A and the first long of page B.
    move.l  #0x11223344, 0x00300FFC
    move.l  #0x55667788, 0x00310000

    | Enable MMU, 3-level, 4K pages.  ATC is cold: the first half's
    | translation must run the table walker, and the second half's must
    | run its own for a DIFFERENT page.
    move.l  #0x00008770, %d0
    movec   %d0, %tc

    | Stay in SUPERVISOR mode: PFLUSHA below is privileged.  The leaf
    | descriptors are not supervisor-only (S bit clear), so supervisor
    | accesses walk exactly the same tables a user access would.

    | CONTROLS FIRST.  Prove both pages are mapped, readable and
    | writable with ORDINARY ALIGNED accesses, and leave both ATC
    | entries resident.  If these pass and the split below still
    | faults, the fault is specific to the split path and not to the
    | page-table setup.
    move.l  #0x80001FFC, %a2
    move.l  (%a2), %d1
    cmp.l   #0x11223344, %d1
    bne     _fail_ctl_a
    move.l  #0x80002000, %a3
    move.l  (%a3), %d2
    cmp.l   #0x55667788, %d2
    bne     _fail_ctl_b
    | Aligned WRITE to page B, then read back — proves page B is
    | writable through this mapping (M bit, WP, everything).
    move.l  #0x99AABBCC, (%a3)
    move.l  (%a3), %d2
    cmp.l   #0x99AABBCC, %d2
    bne     _fail_ctl_w
    | Restore page B's seed for the real check below.
    move.l  #0x55667788, (%a3)

    | THE ONE VARIABLE UNDER TEST: drop both ATC entries again, so the
    | split store below runs with a COLD ATC while everything else --
    | page tables, seeds, mappings -- is byte-identical to
    | mmu_split_crosses_page_boundary.s, which passes with them warm.
    pflusha

    | THE ACCESS UNDER TEST: misaligned LONG store straddling the
    | page-A / page-B boundary.
    move.l  #0xAABBCCDD, %d0
    move.l  #0x80001FFE, %a1
    move.l  %d0, (%a1)

    | Read both halves back through their own mappings.
    move.l  #0x80001FFC, %a2
    move.l  (%a2), %d1
    cmp.l   #0x1122AABB, %d1
    bne     _fail_a

    move.l  #0x80002000, %a3
    move.l  (%a3), %d2
    cmp.l   #0xCCDD7788, %d2
    bne     _fail_b

    trap    #0

_fail_a:
    move.l  #0xDEAD0A01, 0xFFFF0000
_halt_a:
    bra     _halt_a

_fail_b:
    move.l  #0xDEAD0A02, 0xFFFF0000
_halt_b:
    bra     _halt_b

_fail_ctl_a:
    move.l  #0xDEAD0A04, 0xFFFF0000
_halt_ca:
    bra     _halt_ca

_fail_ctl_b:
    move.l  #0xDEAD0A05, 0xFFFF0000
_halt_cb:
    bra     _halt_cb

_fail_ctl_w:
    move.l  #0xDEAD0A06, 0xFFFF0000
_halt_cw:
    bra     _halt_cw

_buserr:
    move.l  #0xDEAD0A03, 0xFFFF0000
_halt_be:
    bra     _halt_be

_trap_h:
    move.l  #0xC0FFEE00, 0xFFFF0000
_done:
    bra     _done
