| itlb_walk_vs_older_store_pflusha.s -- an ITLB table walk vs an OLDER store to the leaf descriptor it reads.
|
| PFLUSHA between the store and the jump (the documented descriptor-update protocol).
| PASS is architecturally REQUIRED. (2026-10-10 audit: the ITLB walker has no SQ ordering
| check, so the SAME program with no sync, with NOP, or with CPUSHA instead of PFLUSHA
| executes the STALE page here. The UM does not guarantee otherwise: 7.7 lets writes defer
| behind later reads (only operand reads are collision-checked) and documents NOP as an
| execution/bus synchronizer, not a prefetch restart; 4.x names CPUSHA as the way to repeat
| prefetch; 3.x requires a PFLUSH after a translation change (CPUSHA does not flush the ATC). PFLUSH here
| waits for the SQ to drain (ExceptionUnit S_DRAIN) and poisons/clears every walk and
| ATC entry made before it, which is what this regression lock pins.)
|
| SHAPE: the leaf store is ready but UNCOMMITTED behind an older unrelated divide chain,
| and `jmp (%a1)` (a1 ready long before) redirects fetch into VA 0x80001000 while the
| store is still in P3/the SQ. VA 0x80001000 was never touched, so no ATC entry exists.
| Its leaf first maps PFN 0x40802000 (code that FAILS); the program rewrites the leaf to
| PFN 0x40801000 (code that PASSES). Both code pages are inside the program image, so the
| instruction agent backs them. Descriptors live in write-through DTT0 memory.
| PASS 0xC0FFEE00.  FAIL: 0x57A1E1F0 = the stale leaf was walked and the stale page
| executed; 0xDEAD0B02 bus error; 0xDEAD0B03 address error.
    .text
    .org 0
_start:
    lea     0x00010000, %a7
    move.l  #0x00100000, %d0
    movec   %d0, %vbr
    move.l  #_trap_h, 0x00100080
    move.l  #_buserr, 0x00100008
    move.l  #_adderr, 0x0010000C
    move.l  #0x4000C000, %d0
    movec   %d0, %itt0
    move.l  #0x007FA000, %d0
    movec   %d0, %dtt0
    move.l  #0xFF00A000, %d0
    movec   %d0, %dtt1
    move.l  #0x00200000, %d0
    movec   %d0, %urp
    movec   %d0, %srp
    move.l  #0x0021000a, 0x00200100
    move.l  #0x0022000a, 0x00210000
    move.l  #0x40802009, 0x00220004         | L3[1] = stale code page (FAIL)
    pflusha
    move.l  #0x80008000, %d0
    movec   %d0, %cacr                      | DE | IE
    move.l  #0x00008000, %d0
    movec   %d0, %tc
    move.l  #0x80001000, %a1
    move.l  #0x40801009, %d2                | new leaf: PASS code page, U set
    move.l  #1, %d3
    move.l  #7, %d4
    divu.l  %d3, %d4                        | slow, UNRELATED: holds retirement back
    divu.l  %d3, %d4
    divu.l  %d3, %d4
    divu.l  %d3, %d4
    lea     0x00220004, %a2
    move.l  %d2, (%a2)                      | leaf := PASS page  (older store)
    pflusha
    jmp     (%a1)                         | younger fetch through the new leaf
_buserr:
    move.l  #0xDEAD0B02, 0xFFFF0000
_h2: bra _h2
_adderr:
    move.l  #0xDEAD0B03, 0xFFFF0000
_h3: bra _h3
_trap_h:
    move.l  #0xC0FFEE00, 0xFFFF0000
_h4: bra _h4
    .org 0x1000
_pass_page:
    trap    #0
_h5: bra _h5
    .org 0x2000
_stale_page:
    move.l  #0x57A1E1F0, 0xFFFF0000
_h6: bra _h6
