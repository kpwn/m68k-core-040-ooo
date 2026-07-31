| mmu_atc_write_hit_sets_modified.s — task #165 / memory_model_review BUG-3
|
| A write that HITS an already-resident ATC entry whose M bit is clear
| must still cause the page descriptor in MEMORY to have its M
| (modified) bit set.
|
| MC68040 UM S3.3: "If the page is not write protected and the modified
| bit of the ATC entry is clear, a table search proceeds to set the
| modified bit in both the page descriptor in memory and in the ATC."
|
| Why it matters: Mac OS VM decides whether a page needs writing back
| before eviction by reading the descriptor's M bit.  A page that reads
| as clean while actually being dirty is discarded, silently losing
| every write to it — data loss with no error indication.
|
| Sequence (the middle step is the whole point):
|   1. Build a valid 3-level mapping for VA 0x80001000 with the leaf
|      descriptor U=1, M=0.
|   2. READ VA 0x80001000.  This forces the walker to run and install
|      an ATC entry.  Because the access is a read, both the ATC entry
|      and the descriptor keep M=0.
|   3. Assert the descriptor is still M=0 — a read must not set M, and
|      if it did, step 5 would pass for the wrong reason.
|   4. WRITE VA 0x80001000.  The entry is resident, so this HITS the
|      ATC.  On the pre-fix RTL no walk happens at all and the
|      descriptor stays M=0 forever.
|   5. Re-read the leaf descriptor and require M=1.
|   6. Confirm the store itself still landed correctly at the physical
|      page — the M-refresh re-walk must not swallow or misdirect it.
|
| Everything runs in supervisor mode: DTT0 gives supervisor
| pass-through for low memory (page tables + the physical page), so the
| descriptor and the physical page can both be inspected directly,
| while VA 0x80001000 sits above the DTT0 range and therefore genuinely
| goes through the ATC/walker.
|
| Address map:
|   0x00100000  VBR
|   0x00200000  URP/SRP root table   L1[0x40] @ 0x00200100
|   0x00210000  L2 table             L2[0x00] @ 0x00210000
|   0x00220000  L3 table             L3[0x01] @ 0x00220004  <- leaf
|   0x00300000  physical page backing VA 0x80001000
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD0901 — first (read) load returned the wrong value
|   0xDEAD0902 — the READ set M in the descriptor (setup invalid; the
|                subsequent M check would be meaningless)
|   0xDEAD0903 — BUG-3: write hit the ATC and left descriptor M clear
|   0xDEAD0904 — store did not land at the physical page
|   0xDEAD0905 — unexpected bus error / page fault on a valid mapping

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_buserr, 0x00100008
    move.l  #_trap_h, 0x00100080
    move.l  #0x00100000, %d0
    movec   %d0, %vbr

    | ITT0: instruction pass-through for the 0x40xxxxxx text region.
    move.l  #0x4000C000, %d0
    movec   %d0, %itt0

    | DTT0: supervisor data pass-through for 0x00000000-0x7FFFFFFF
    | (page tables + the physical page).  VA 0x80001000 is deliberately
    | ABOVE this range so it must use the ATC/walker.
    move.l  #0x007FA000, %d0
    movec   %d0, %dtt0
    | DTT1: supervisor data pass-through for the 0xFFxxxxxx sentinel.
    move.l  #0xFF00A000, %d0
    movec   %d0, %dtt1

    | Root pointer for both user and supervisor tables.
    move.l  #0x00200000, %d0
    movec   %d0, %urp
    movec   %d0, %srp

    | L1[0x40] -> L2 @ 0x00210000, U=1 (bit3), DT=10 (table).
    move.l  #0x0021000a, 0x00200100
    | L2[0x00] -> L3 @ 0x00220000, U=1 (bit3), DT=10 (table).
    move.l  #0x0022000a, 0x00210000
    | L3[0x01] = PFN 0x00300000, U=1 (bit3), M=0 (bit4 CLEAR), DT=01.
    move.l  #0x00300009, 0x00220004
    | Seed the physical page.
    move.l  #0xCAFEBABE, 0x00300000

    | Enable MMU: 3-level, 4 KB pages.
    move.l  #0x00008770, %d0
    movec   %d0, %tc

    move.l  #0x80001000, %a1

    | ── Step 2: READ.  Walker runs, ATC entry installed with M=0. ──
    move.l  (%a1), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail_read

    | ── Step 3: the read must NOT have set M in the descriptor. ──
    move.l  0x00220004, %d1
    btst    #4, %d1
    bne     _fail_read_set_m

    | ── Step 4: WRITE.  This hits the resident, M=0 ATC entry. ──
    move.l  #0x12345678, (%a1)

    | ── Step 5: the descriptor's M bit must now be set. ──
    move.l  0x00220004, %d2
    btst    #4, %d2
    beq     _fail_no_m

    | ── Step 6: and the store must actually have landed. ──
    move.l  0x00300000, %d3
    cmp.l   #0x12345678, %d3
    bne     _fail_store_lost

    trap    #0

_fail_read:
    move.l  #0xDEAD0901, 0xFFFF0000
_h1:
    bra     _h1

_fail_read_set_m:
    move.l  #0xDEAD0902, 0xFFFF0000
_h2:
    bra     _h2

_fail_no_m:
    move.l  #0xDEAD0903, 0xFFFF0000
_h3:
    bra     _h3

_fail_store_lost:
    move.l  #0xDEAD0904, 0xFFFF0000
_h4:
    bra     _h4

_buserr:
    move.l  #0xDEAD0905, 0xFFFF0000
_hbe:
    bra     _hbe

_trap_h:
    move.l  #0xC0FFEE00, 0xFFFF0000
_done:
    bra     _done
