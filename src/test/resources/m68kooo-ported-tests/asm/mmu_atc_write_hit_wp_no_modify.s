| mmu_atc_write_hit_wp_no_modify.s — task #165 corner case for BUG-3
|
| Companion to mmu_atc_write_hit_sets_modified.s.  That test requires a
| write which HITS a resident ATC entry with M=0 to go set the M bit in
| the page descriptor.  This test pins the corner that fix is most
| likely to break: the write-protected page.
|
| MC68040 UM S3.3 orders the two checks explicitly — "If a write or
| read-modify-write access results in an ATC hit but the page is write
| protected, the access is aborted and an access error exception is
| taken", and only "if the page is NOT write protected and the modified
| bit of the ATC entry is clear" does a table search proceed to set M.
|
| So on a WP page the M-refresh must not fire.  Two ways to get this
| wrong: (a) the M=0 condition forces a table search that reports
| success instead of the write-protect fault, silently letting the
| store through; (b) a "targeted M writeback" fires ahead of the
| permission check and marks a read-only page dirty.
|
| Sequence:
|   1. Build a valid 3-level mapping for VA 0x80002000 whose leaf
|      descriptor has WP=1 (bit 2), U=1, M=0.
|   2. READ VA 0x80002000 — permitted on a WP page, and installs the
|      ATC entry so the following write is a genuine hit.
|   3. WRITE VA 0x80002000 — must take a bus error (vec 2).
|   4. In the handler, require that the descriptor's M bit is STILL
|      clear and that the physical page still holds its seed value.
|
| The handler writes the sentinel directly rather than RTE-ing, so no
| resume-PC fixup is needed.
|
| Address map:
|   0x00100000  VBR
|   0x00200000  root table           L1[0x40] @ 0x00200100
|   0x00210000  L2 table             L2[0x00] @ 0x00210000
|   0x00220000  L3 table             L3[0x02] @ 0x00220008  <- leaf
|   0x00400000  physical page backing VA 0x80002000
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD0A01 — the read of the WP page returned the wrong value
|   0xDEAD0A02 — write to the WP page did NOT fault (fell through)
|   0xDEAD0A03 — fault fired but the descriptor's M bit got set anyway
|   0xDEAD0A04 — fault fired but the store leaked into the page

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_buserr, 0x00100008
    move.l  #0x00100000, %d0
    movec   %d0, %vbr

    | ITT0: instruction pass-through for the 0x40xxxxxx text region.
    move.l  #0x4000C000, %d0
    movec   %d0, %itt0
    | DTT0: supervisor data pass-through for 0x00000000-0x7FFFFFFF.
    move.l  #0x007FA000, %d0
    movec   %d0, %dtt0
    | DTT1: supervisor data pass-through for the 0xFFxxxxxx sentinel.
    move.l  #0xFF00A000, %d0
    movec   %d0, %dtt1

    move.l  #0x00200000, %d0
    movec   %d0, %urp
    movec   %d0, %srp

    | L1[0x40] -> L2 @ 0x00210000, U=1, DT=10.
    move.l  #0x0021000a, 0x00200100
    | L2[0x00] -> L3 @ 0x00220000, U=1, DT=10.
    move.l  #0x0022000a, 0x00210000
    | L3[0x02] = PFN 0x00400000, WP=1 (bit2), U=1 (bit3), M=0, DT=01.
    move.l  #0x0040000d, 0x00220008
    | Seed the physical page.
    move.l  #0xFEEDFACE, 0x00400000

    | Enable MMU: 3-level, 4 KB pages.
    move.l  #0x00008770, %d0
    movec   %d0, %tc

    move.l  #0x80002000, %a1

    | ── Step 2: READ.  Allowed on a WP page; installs the ATC entry. ──
    move.l  (%a1), %d0
    cmp.l   #0xFEEDFACE, %d0
    bne     _fail_read

    | ── Step 3: WRITE.  Must abort with an access error. ──
    move.l  #0xB16B00B5, (%a1)

    | Falling through here means the WP fault never fired.
    move.l  #0xDEAD0A02, 0xFFFF0000
_h_nofault:
    bra     _h_nofault

_fail_read:
    move.l  #0xDEAD0A01, 0xFFFF0000
_h1:
    bra     _h1

_buserr:
    | Expected.  The descriptor must still read M=0 ...
    move.l  0x00220008, %d1
    btst    #4, %d1
    bne     _fail_m_set
    | ... and the store must not have leaked into the page.
    move.l  0x00400000, %d2
    cmp.l   #0xFEEDFACE, %d2
    bne     _fail_store_leaked
    move.l  #0xC0FFEE00, 0xFFFF0000
_done:
    bra     _done

_fail_m_set:
    move.l  #0xDEAD0A03, 0xFFFF0000
_h3:
    bra     _h3

_fail_store_leaked:
    move.l  #0xDEAD0A04, 0xFFFF0000
_h4:
    bra     _h4
