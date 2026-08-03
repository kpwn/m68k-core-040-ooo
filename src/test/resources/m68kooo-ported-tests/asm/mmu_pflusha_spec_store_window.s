| mmu_pflusha_spec_store_window.s — regression lock: a STORE younger than
| PFLUSHA must never commit through a translation PFLUSHA was meant to kill.
|
| This test PASSES on main.  It is a guard, not a bug repro — see
| docs/uarch_decisions.md #21 for the full investigation.
|
| Why the guard is needed
| -----------------------
| Nothing in the MMU or the LSU orders a younger memory op against
| PFLUSHA.  Specifically:
|
|   * PFLUSHA does NOT serialise the pipeline.  It is absent from
|     commit.v's `head_sys_flush_after_retire` (commit.v:1735-1762) and
|     its retire only pulses mmu_pflush_all_req (commit.v:4938).  The
|     mmu_pflush_all_w terms in m68k_core_flush.vh gate a D-CACHE flush,
|     not a pipeline flush.
|   * MOVEC to TC/SRP/URP/ITTx/DTTx does NOT clear the ATC — mmu.v:308
|     drives pflush_all only from pflush_all_req|pflush_asid_req; a CR
|     write just bumps translate_epoch (retires an in-flight WALK).
|   * Translation is SPECULATIVE and execute-time.  The LSU translates in
|     S_MMU_WAIT and latches dc_addr <= mmu_pa_in (lsu.v:986), then parks
|     in S_ST_BUF; the commit-time launch reuses that latched PA verbatim
|     (lsu.v:1269-1276).  There is no commit-time re-translation.
|   * iq_mem does not hold stores at the ROB head (the MMIO fence at
|     iq_mem.v:735-750 exempts stores).
|
| The ONLY thing that closes the window is a privilege-mode side effect:
| PFLUSHA decodes with requires_supervisor=1
| (decode_uop_assemble.v:18754), and any requires_supervisor uop sets
| q_rob_drain_req, which blocks dispatch until rob_empty
| (m68k_core_fetch.vh:640-645, 684, 785).  PFLUSHA therefore dispatches
| into an EMPTY ROB, is instantly the head, and — UOP_SYS entries being
| complete at dispatch (rob.v:1129) — retires before any younger uop can
| even reach iq_mem.
|
| So: if anyone narrows the requires_supervisor ROB-drain, makes an
| ATC-invalidating op non-privileged, or lets a privileged uop dispatch on
| decode lane 1 (today blocked by decode.v:7431), this test is what
| catches the resulting wrong-physical-address store.
|
| Proven capable of firing: with ONLY `requires_supervisor = 1'b0`
| patched into the PFLUSHA row of decode_uop_assemble.v, the store
| translates 128 cycles AHEAD of the ATC invalidate and lands at the old
| PFN — this test goes RED with 0xDEAD0702.
|
| Construction
| ------------
|   VA 0x80001000 -> PFN_OLD (0x00300000), leaf M=1 so a WRITE hit does
|     not need a re-walk (mmu.v:435 atc_write_hit_needs_mod) — without
|     M=1 the store would re-walk, pick up the new leaf, and the test
|     would pass for the wrong reason.
|   Warm the ATC with a load.
|   Repoint the leaf to PFN_NEW (0x00400000).
|   A chain of dependent DIVUs in front of PFLUSHA holds the ROB head, so
|     that IF PFLUSHA were dispatchable into a non-empty ROB the younger
|     store (operands long since ready) would issue out of order and
|     translate against the stale ATC.
|   PFLUSHA.
|   move.l %d2,(%a1)  <- must land at PFN_NEW.
|
| Both physical pages are read back through DTT0 (transparent 0x00000000-
| 0x7FFFFFFF), so the check itself never goes through the ATC.
|
| DTT1 is 0xFF00E060 (CM=11, non-cacheable/serialised) so the 0xFFFF0000
| sentinel can never be absorbed by the D-cache.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD0701 — warm load wrong (page-table setup broken, not the hazard)
|   0xDEAD0702 — PFN_NEW did not receive the store  (hazard fired)
|   0xDEAD0703 — PFN_OLD was clobbered by the store (hazard fired)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.l  #_trap_h, 0x00100080
    move.l  #0x00100000, %d0
    movec   %d0, %vbr

    move.l  #0x4000C000, %d0
    movec   %d0, %itt0

    | DTT0: transparent 0x00000000-0x7FFFFFFF (page tables + both PFNs).
    move.l  #0x007FA000, %d0
    movec   %d0, %dtt0
    | DTT1: 0xFF000000-0xFFFFFFFF, CM=11 non-cacheable (sentinel page).
    move.l  #0xFF00E060, %d0
    movec   %d0, %dtt1

    move.l  #0x00200000, %d0
    movec   %d0, %urp
    movec   %d0, %srp

    | Three-level table for VA 0x80001000 (TIA=7, TIB=7, 4K pages).
    move.l  #0x0021000a, 0x00200100         | L1[0x40] -> L2 @0x00210000
    move.l  #0x0022000a, 0x00210000         | L2[0x00] -> L3 @0x00220000
    | L3[0x01] = PFN_OLD, U=1, M=1, PDT=01.  M=1 matters: without it an
    | ATC write hit sets atc_write_hit_needs_mod and forces a re-walk,
    | which would silently pick up the new leaf and hide the hazard.
    move.l  #0x00300019, 0x00220004

    move.l  #0x11111111, 0x00300000         | PFN_OLD marker
    move.l  #0x22222222, 0x00400000         | PFN_NEW marker

    move.l  #0x00008770, %d0
    movec   %d0, %tc

    | Warm the ATC: VA 0x80001000 -> PFN_OLD.
    move.l  #0x80001000, %a1
    move.l  (%a1), %d0
    cmp.l   #0x11111111, %d0
    bne     _fail1

    | Install the NEW mapping under the (still-resident) ATC entry.
    move.l  #0x00400019, 0x00220004

    | Store payload ready well ahead of the PFLUSHA so the store is
    | issue-ready the cycle it reaches iq_mem.
    move.l  #0xCAFEBABE, %d2

    | ── Would-be speculative window ─────────────────────────────────
    | Dependent DIVUs serialise in the sequential divide FSM and hold the
    | ROB head for >100 cycles.  On main this is inert: PFLUSHA's
    | requires_supervisor drain makes it wait for rob_empty, so the DIVUs
    | all retire BEFORE PFLUSHA dispatches.  Remove that drain and this
    | chain is what opens the 128-cycle window the positive control
    | measured.  Nothing here touches %a1/%d2, so the younger store's
    | operands stay ready and iq_mem could issue it out of order.
    move.l  #0x00FF0001, %d3
    divu.w  #0x0007, %d3
    and.l   #0x0000FFFF, %d3
    or.l    #0x00FF0000, %d3
    divu.w  #0x0007, %d3
    and.l   #0x0000FFFF, %d3
    or.l    #0x00FF0000, %d3
    divu.w  #0x0007, %d3
    and.l   #0x0000FFFF, %d3
    or.l    #0x00FF0000, %d3
    divu.w  #0x0007, %d3

    pflusha

    | The store under test.  Must commit to PFN_NEW.
    move.l  %d2, (%a1)

    | ── Verify through DTT0 (no ATC involvement) ────────────────────
    move.l  0x00400000, %d4
    move.l  0x00300000, %d5

    cmp.l   #0xCAFEBABE, %d4
    bne     _fail_new
    cmp.l   #0x11111111, %d5
    bne     _fail_old

    trap    #0

_fail1:
    move.l  #0xDEAD0701, 0xFFFF0000
_h1:
    bra     _h1

_fail_new:
    move.l  #0xDEAD0702, 0xFFFF0000
_h2:
    bra     _h2

_fail_old:
    move.l  #0xDEAD0703, 0xFFFF0000
_h3:
    bra     _h3

_trap_h:
    move.l  #0xC0FFEE00, 0xFFFF0000
_done:
    bra     _done
