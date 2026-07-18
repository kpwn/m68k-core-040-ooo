| mmu_pflusha_invalidate.s — PFLUSHA forces a re-walk on next access.
|
| Goal: load a page through the walker (ATC fill), PFLUSHA, then
| change the page table out from under the ATC.  The next load to
| the same VA must observe the NEW mapping (i.e. PFLUSHA actually
| invalidated the ATC entry).
|
| Construction:
|   - Map VA 0x80001000 -> PFN A (0x00300000) with seed 0xAAAAAAAA.
|   - First load reads 0xAAAAAAAA, ATC caches VA->PFN_A.
|   - Repoint VA 0x80001000 -> PFN B (0x00400000) with seed 0xBBBBBBBB.
|   - PFLUSHA.
|   - Second load must read 0xBBBBBBBB (re-walk to PFN B).  If ATC
|     stale, second load reads 0xAAAAAAAA.
|
| Without PFLUSHA the second load is allowed (per spec) to read either
| value depending on ATC residency.  With PFLUSHA the ABI guarantees
| the new mapping is observed.
|
| PASS sentinel: 0xC0FFEE00 when first==0xAAAAAAAA && second==0xBBBBBBBB.
| FAIL sentinels:
|   0xDEAD0601 — first load wrong (page-table setup broken)
|   0xDEAD0602 — second load == 0xAAAAAAAA (PFLUSHA didn't invalidate)
|   0xDEAD0603 — second load other value (stale walker)

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

    move.l  #0x007FA000, %d0
    movec   %d0, %dtt0
    move.l  #0xFF00A000, %d0
    movec   %d0, %dtt1

    move.l  #0x00200000, %d0
    movec   %d0, %urp
    movec   %d0, %srp

    | Mapping VA 0x80001000 -> PFN A.
    move.l  #0x0021000a, 0x00200100         | L1[0x40] -> L2
    move.l  #0x0022000a, 0x00210000         | L2[0x00] -> L3
    move.l  #0x00300009, 0x00220004         | L3[0x01] = PFN A, U, DT=01

    | Seed PFN A.
    move.l  #0xAAAAAAAA, 0x00300000

    | Seed PFN B (in case we miss PFLUSHA).
    move.l  #0xBBBBBBBB, 0x00400000

    move.l  #0x00008770, %d0
    movec   %d0, %tc

    | First load (drives walker fill).
    move.l  #0x80001000, %a1
    move.l  (%a1), %d0
    cmp.l   #0xAAAAAAAA, %d0
    bne     _fail1

    | Repoint VA 0x80001000 -> PFN B by overwriting the L3 leaf.
    move.l  #0x00400009, 0x00220004         | L3[0x01] = PFN B

    | Without PFLUSHA the ATC may serve the old PFN A.  Issue PFLUSHA.
    pflusha

    | Second load — must see PFN B's seed.
    move.l  (%a1), %d1
    cmp.l   #0xBBBBBBBB, %d1
    bne     _fail2

    trap    #0

_fail1:
    move.l  #0xDEAD0601, 0xFFFF0000
_halt1:
    bra     _halt1

_fail2:
    cmp.l   #0xAAAAAAAA, %d1
    beq     _fail2_stale
    move.l  #0xDEAD0603, 0xFFFF0000
_halt2:
    bra     _halt2
_fail2_stale:
    move.l  #0xDEAD0602, 0xFFFF0000
_halt2s:
    bra     _halt2s

_trap_h:
    move.l  #0xC0FFEE00, 0xFFFF0000
_done:
    bra     _done
