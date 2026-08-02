| mmu_split_second_half_rewalks.s — a page-crossing-capable SPLIT access
| must translate its SECOND half independently of its first.
|
| lsu.v splits any misaligned LONG (m68k_mem_lane.vh:92 — `off != 2'b00`)
| and any WORD at offset 3 into two aligned beats, and deliberately
| translates each half separately: S_MMU_WAIT handles the first, then
| S_SPLIT_MMU_WAIT re-drives the MMU with `split_second_va = aligned(ea)
| + 4` "so split accesses crossing a page boundary see the second page's
| mapping/fault/cache mode" (lsu.v:936-940).
|
| The hazard this test pins: `mmu_req_valid_out` is held high across BOTH
| states (lsu.v:546-547), so the MMU never sees req_valid drop between
| the two halves.  mmu.v's `req_wait_drop` — set when a walker run is
| kicked (mmu.v:559) and cleared ONLY on `!req_valid` (mmu.v:529-531) —
| is therefore still 1 when the second half is presented.  That matters
| because:
|
|   last_pa_valid_for_req = last_ok & ((last_va == va_in) | req_wait_drop)
|                                                            ^^^^^^^^^^^^^
| (mmu.v:448-449).  The `req_wait_drop` disjunct deliberately bypasses
| the VA check — its stated job is to keep driving a completed result
| while the caller has not yet accepted it.  But across the
| S_MMU_WAIT -> S_SPLIT_MMU_WAIT transition the caller has ALREADY
| accepted, and `va_in` has changed to a different address.  So for the
| second half:
|
|   pa_out        = last_pa   (mmu.v:238)  -> the FIRST half's PA
|   cache_inh_out = w_out_ci  (mmu.v:698)  -> the FIRST half's cache mode
|   resp_ready    = 1         (mmu.v:730)  -> req_waiting_for_walk is
|                                             gated on ~req_wait_drop
|   walker kick   = blocked   (mmu.v:551)  -> also gated on ~req_wait_drop
|
| i.e. the second half is answered instantly with the first half's
| translation and never walks at all.  It only bites when the FIRST half
| actually ran the walker (req_wait_drop only sets on a walker kick), so
| an access whose page is already ATC-resident is unaffected — which is
| why this hides: it needs MMU-on AND a cold ATC AND a misaligned access,
| and the fuzzer runs with TC.E=0 so it can never generate it.
|
| Concretely, with the store below at VA 0x80001002:
|   first  half -> aligned VA 0x80001000, bytes 2..3   (walks, fills ATC)
|   second half -> VA 0x80001004, bytes 0..1
| If the second half inherits the first half's PA, `split_second_addr`
| becomes aligned(first-half PA) = the SAME word, so the high half of the
| store lands on top of the low half and VA 0x80001004 is never written.
|
| PASS sentinel: 0xC0FFEE00 — both words correct.
| FAIL sentinels:
|   0xDEAD0901 — low word wrong.  0xCCDDAABB here is the exact signature
|                of the second half writing into the first half's word.
|   0xDEAD0902 — high word wrong.  0x55667788 (untouched seed) here means
|                the second beat never reached VA 0x80001004.
|   0xDEAD0903 — unexpected bus error / page fault.

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

    | THE ACCESS UNDER TEST: misaligned LONG store, offset 2 -> split.
    | First half walks (cold ATC); second half must be translated on its
    | own rather than inheriting the first half's result.
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
    move.l  #0xDEAD0901, 0xFFFF0000
_halt_lo:
    bra     _halt_lo

_fail_hi:
    move.l  #0xDEAD0902, 0xFFFF0000
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
