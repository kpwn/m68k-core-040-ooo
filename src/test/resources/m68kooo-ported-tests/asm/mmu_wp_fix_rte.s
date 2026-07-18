| mmu_wp_fix_rte.s — WP-violation → handler clears WP → RTE → retry
|
| DEFERRED: task #99 (Phase-B LSU+walker integration).  Phase-A's LSU
| doesn't yet route writes through the walker, so write-protect faults
| don't surface.  This test is the activation smoke-test for that path.
|
| SCENARIO: Supervisor code stores to a VA whose leaf descriptor has
| WP=1 → walker returns fault code 3 → exception.v pushes a fmt-2
| frame → handler clears WP in the PTE + PFLUSHes the ATC entry →
| RTE → store retries, now succeeds.
|
| Expected PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    move.l  #_buserr_hdlr, 0x00100008    | vec 2 (bus err)
    move.l  #_trap_hdlr,   0x00100080    | vec 32 done
    | Use Dn source (decoder MOVEC An-source bug).
    move.l  #0x00100000, %d0
    movec   %d0, %vbr

    move.l  #0x4000C000, %d0
    movec   %d0, %itt0

    | DTT0 — supervisor-only pass-through for low memory so the
    | handler can read/write page-table pages directly without
    | self-triggering the walker.  Covers [0, 0x80000000).
    move.l  #0x007FA000, %d0
    movec   %d0, %dtt0
    | DTT1 — supervisor-only pass-through for the sentinel region at
    | 0xFFFF0000.  Leaves the 0xA0000000 / 0xB0000000 range (VA + PFN
    | of this test) uncovered, so the WP fault still surfaces to the
    | walker and the PA is walker-resolved on retry.
    |   bits 31:24 = base    0xFF
    |   bits 23:16 = mask    0x00 (must exactly match)
    |   bit 15     = E       1
    |   bits 14:13 = s_fld   01 (supervisor only)
    | = 0xFF00A000
    move.l  #0xFF00A000, %d0
    movec   %d0, %dtt1

    move.l  #0x00200000, %d0
    movec   %d0, %urp
    movec   %d0, %srp

    | Build a WP=1 leaf for VA 0xA0000000.  L1[0x50] → L2, L2[0x00] → L3,
    | L3[0x00] = PFN 0x00400000 with WP bit set.  PFN is in low RAM so
    | the testbench mem_model backs it (the model covers [0, 0x4000000)).
    move.l  #0x0021000a, 0x00200140     | L1[0x50] = table4, used
    move.l  #0x0022000a, 0x00210000     | L2[0x00] = table4, used
    move.l  #0x0040000d, 0x00220000     | L3[0x00] = PFN 0x00400000 + WP
                                         | bit2=WP=1, DT[1:0]=01, U=bit3

    | Enable MMU 3-lvl 4K.
    | Phase-A MMU uses bit 15 for the E flag.
    move.l  #0x00008770, %d0
    movec   %d0, %tc

    | Supervisor does the store — should fault with vec 2 (WP).
    move.l  #0xA0000000, %a1
    move.l  #0x12345678, %d0
    move.l  %d0, (%a1)                  | ← WP fault → handler patches

    | After RTE, retry must read back 0x12345678.
    move.l  (%a1), %d1
    cmp.l   #0x12345678, %d1
    bne     _fail

    trap    #0

_fail:
    move.l  #0xDEADBEEF, 0xFFFF0000
_fail_halt:
    bra     _fail_halt

_buserr_hdlr:
    | Clear the WP bit (bit 2) in L3[0x00] + PFLUSH the stale entry.
    move.l  #0x00400009, 0x00220000     | PFN 0x00400000, WP=0, DT=01
    | PFLUSHN Ax — not encoded on our assembler path yet; use PFLUSHA.
    pflusha                              | invalidate whole ATC
    rte

_trap_hdlr:
    move.l  #0xC0FFEE00, 0xFFFF0000
_done:
    bra     _done
