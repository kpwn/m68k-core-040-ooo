| mmu_atc_load_then_use.s — first access fills ATC, second hits.
|
| Goal: do a load that triggers a walker fill (no DTT covers the
| target VA, MMU enabled, page table has a valid mapping).  Read
| memory once.  Read it AGAIN.  The second read should hit the ATC
| (no walker traffic).
|
| Direct architectural observability of ATC vs walker is limited
| from m68k assembly — there's no "ATC hit count" register.  We
| approximate by:
|   - Issue the FIRST load and capture the value.
|   - Issue the SECOND load and capture the value.
|   - Both must equal the seeded byte pattern.
|
| The "real" ATC test is at the harness level (cycle counts, AXI
| trace).  This test is a smoke-test that the SECOND load doesn't
| spuriously walk again with a stale page table — i.e. that the
| ATC is consulted before re-walking.  If the ATC were never
| installed, the second walk would still succeed (page table still
| valid) and this test would PASS — so this checks for the
| "regression where second access faults" failure mode rather than
| confirming ATC presence.
|
| Construction same as mmu_pagefault_rte_basic but with the leaf
| valid up-front, so no fault fires.
|
| PASS sentinel: 0xC0FFEE00 when both loads return the seeded pattern.
| FAIL sentinels:
|   0xDEAD0801 — first load returned wrong value
|   0xDEAD0802 — second load returned wrong value (ATC stale or walker
|                returned different result)
|   0xDEAD0803 — vec-2 fired (page-fault path -- mapping should be
|                valid)
|
| OBSERVED on main: depends on Phase-B walker integration; likely
| timeout or DEAD0803 today.

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
    | DTT1 supervisor pass-through for sentinel page.
    move.l  #0xFF00A000, %d0
    movec   %d0, %dtt1

    | URP/SRP.
    move.l  #0x00200000, %d0
    movec   %d0, %urp
    movec   %d0, %srp

    | Build VALID mapping for VA 0x80001000.
    | L1[0x40] -> L2 @ 0x210000.
    move.l  #0x0021000a, 0x00200100
    | L2[0x00] -> L3 @ 0x220000.
    move.l  #0x0022000a, 0x00210000
    | L3[0x01] = PFN 0x00300000, U=1, DT=01 (resident).
    move.l  #0x00300009, 0x00220004
    | Seed PFN with sentinel.
    move.l  #0xCAFEBABE, 0x00300000

    | Enable MMU 3-lvl 4K.
    move.l  #0x00008770, %d0
    movec   %d0, %tc

    | Drop to user, do two loads of the same VA.
    andi.w  #0xDFFF, %sr
    lea     0x00008000, %a7
    move.l  #0x80001000, %a1

    | First load -- triggers walker, ATC fill.
    move.l  (%a1), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail1

    | Second load -- should hit ATC.
    move.l  (%a1), %d1
    cmp.l   #0xCAFEBABE, %d1
    bne     _fail2

    trap    #0

_fail1:
    move.l  #0xDEAD0801, 0xFFFF0000
_halt1:
    bra     _halt1

_fail2:
    move.l  #0xDEAD0802, 0xFFFF0000
_halt2:
    bra     _halt2

_buserr:
    | Unexpected page-fault on a valid mapping.
    move.l  #0xDEAD0803, 0xFFFF0000
_halt_be:
    bra     _halt_be

_trap_h:
    move.l  #0xC0FFEE00, 0xFFFF0000
_done:
    bra     _done
