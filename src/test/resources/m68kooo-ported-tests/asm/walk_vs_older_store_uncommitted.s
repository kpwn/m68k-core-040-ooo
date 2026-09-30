| walk_vs_older_store_uncommitted.s -- a DTLB table walk must observe an OLDER store to the descriptor it reads.
|
| DEFECT (found 2026-09-30, shipping-LSU corpus triage; OPEN): a younger load that
| misses the DTLB launches its table walk SPECULATIVELY, and the walker's descriptor
| reads are not ordered against older stores that have not yet executed / committed /
| drained. The walk reads the STALE leaf, installs it in the ATC, and the load COMMITS
| through it. An in-order MC68040 cannot do this: the store precedes the load.
| Gating walk launch on SQ-empty does NOT fix it (measured): the older store has not
| even entered the SQ when the walk launches, so the ordering needed is ROB-level.
|
| SHAPE: the store is ready but UNCOMMITTED behind an older unrelated divide chain.
| Fails on EVERY configuration, the legacy default DUT included.
|
| AsWritten posture (the program enables DE/IE and TC itself; descriptors live in
| write-through DTT0 memory). VA 0x80001000 is never touched before, so there is no
| ATC entry. Its leaf first maps PFN C; the program rewrites the leaf to PFN A and
| immediately loads the VA.
| PASS 0xC0FFEE00.  FAIL: the loaded value itself -- 0xCCCCCCCC = the stale leaf was
| walked; 0xDEAD0B02 bus error; 0xDEAD0B03 address error.
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
    move.l  #0x00500009, 0x00220004         | L3[1] = PFN C
    move.l  #0xAAAAAAAA, 0x00300000         | PFN A seed
    move.l  #0xCCCCCCCC, 0x00500000         | PFN C seed
    move.l  #0x80008000, %d0
    movec   %d0, %cacr                      | DE | IE
    move.l  #0x00008000, %d0
    movec   %d0, %tc
    move.l  #0x80001000, %a1
    move.l  #0x00300009, %d2
    move.l  #1, %d3
    move.l  #7, %d4
    divu.l  %d3, %d4                        | slow, UNRELATED: holds retirement back
    divu.l  %d3, %d4
    divu.l  %d3, %d4
    divu.l  %d3, %d4
    lea     0x00220004, %a2
    move.l  %d2, (%a2)                      | leaf := PFN A  (older store)
    move.l  (%a1), %d0                      | younger load through the new leaf
    cmp.l   #0xAAAAAAAA, %d0
    bne     _fail
    trap    #0
_fail:
    move.l  %d0, 0xFFFF0000
_h1: bra _h1
_buserr:
    move.l  #0xDEAD0B02, 0xFFFF0000
_h2: bra _h2
_adderr:
    move.l  #0xDEAD0B03, 0xFFFF0000
_h3: bra _h3
_trap_h:
    move.l  #0xC0FFEE00, 0xFFFF0000
_h4: bra _h4
