| mmu_pagefault_rte_basic.s — end-to-end MMU page-fault + RTE recovery
|
| DEFERRED: staged for task #100 (MMU stress tests).  Will ACTIVATE once
| task #99 (Phase-B wiring of the walker to LSU + exception.v) lands.
| Today Phase-A of the walker is standalone; the LSU's translate path
| is still the phase-2 stub so this test hangs on load of an unmapped
| page (the stub returns pa=va with no fault, and the walker is never
| kicked).
|
| SCENARIO: User-mode code touches a VA backed by an invalid page
| descriptor → walker faults → exception.v pushes a format-7 frame →
| handler patches the PTE → RTE → user retries, now succeeds.
|
| Expected PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0

_start:
    | ── supervisor bootstrap ────────────────────────────────────
    lea     0x00010000, %a7              | SSP

    | Vector table at 0x100000.
    move.l  #_buserr_hdlr, 0x00100008    | vec 2  bus err (page fault)
    move.l  #_trap_hdlr,   0x00100080    | vec 32 TRAP #0 (test-done)

    | VBR
    | Decoder currently has an An-source bug in MOVEC (arch_src_a maps
    | An→TMP0 instead of the correct 8..15 range); use Dn until that
    | decoder fix lands.  Tracked via task 99 report.
    move.l  #0x00100000, %d0
    movec   %d0, %vbr

    | ITT0 for ROM/code space.
    move.l  #0x4000C000, %d0
    movec   %d0, %itt0

    | DTT0 — supervisor-only pass-through covering ALL D-side
    | supervisor accesses.  Needed so the page-fault handler can
    | read/write page-table pages, seed backing pages, AND access
    | the sentinel at 0xFFFF0000 without self-triggering the walker.
    | User-mode traffic (arch_sr.S=0 — the faulting load at VA
    | 0x80001000) is NOT covered because s_fld=01 gates on supervisor.
    |   bits 31:24 = base    0x00
    |   bits 23:16 = mask    0xFF (all addresses match)
    |   bit 15     = E       1
    |   bits 14:13 = s_fld   01 (supervisor only)
    | = 0x00FFA000
    move.l  #0x00FFA000, %d0
    movec   %d0, %dtt0

    | URP = SRP = 0x200000 (1 MB into memory)
    move.l  #0x00200000, %d0
    movec   %d0, %urp
    movec   %d0, %srp

    | Build an invalid-leaf mapping for target VA 0x80001000.
    | L1 at 0x200000, L2 at 0x210000, L3 at 0x220000.
    | Install L1 slot for VA[31:25]=0x40 → point at L2 (resident).
    move.l  #0x0021000a, 0x00200100     | L1[0x40] = table4, used
    | Install L2 slot for VA[24:18]=0x0 → point at L3.
    move.l  #0x0022000a, 0x00210000     | L2[0x00] = table4, used
    | Install L3 leaf at VA[17:12]=0x01 as INVALID (type=00).
    move.l  #0x00000000, 0x00220004     | L3[0x01] = invalid

    | TC enable, 4K pages, 3-lvl, TIA=7, TIB=7.
    | Phase-A MMU uses bit 15 for the E flag (not real-68040 bit 31).
    move.l  #0x00008770, %d0
    movec   %d0, %tc

    | Drop to user and touch the page.
    andi.w  #0xDFFF, %sr
    lea     0x00008000, %a7
    move.l  #0x80001000, %a1
    move.l  (%a1), %d0                  | ← triggers page fault

    | After RTE, re-try succeeds.
    move.l  (%a1), %d1
    cmp.l   #0xCAFEBABE, %d1
    bne     _fail

    trap    #0

_fail:
    move.l  #0xDEADBEEF, 0xFFFF0000
_fail_halt:
    bra     _fail_halt

| ── Bus-error handler ──────────────────────────────────────────
| On entry: supervisor mode, SSP holds a format-7 access-error frame.
| The common prefix is [SR, PC, vec_offset]. Handler replaces the
| invalid PTE at the faulting VA with a valid one, then RTE.
_buserr_hdlr:
    | Patch L3[0x01] with a valid resident page @ PFN 0x00300000.
    | Low-region backing page so the seeding store is routed via the
    | cacheable part of the dcache (flush-on-MMU-event ensures its
    | writeback is visible to the walker on retry and to the user-
    | mode load afterwards).
    move.l  #0x00300009, 0x00220004     | DT[1:0]=01 resident, U=bit3
    pflusha                              | make the updated leaf visible
    | Seed the page so the follow-up read sees 0xCAFEBABE.
    move.l  #0xCAFEBABE, 0x00300000
    rte

| ── TRAP #0: test done ─────────────────────────────────────────
_trap_hdlr:
    move.l  #0xC0FFEE00, 0xFFFF0000
_done:
    bra     _done
