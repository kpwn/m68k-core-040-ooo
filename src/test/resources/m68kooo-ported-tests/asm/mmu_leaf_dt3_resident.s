| mmu_leaf_dt3_resident.s — leaf (page) descriptor DT=3 (0b11) must
| resolve as RESIDENT, not fault.
|
| Per MC68040 User's Manual S3.2.2.3, PDT (Page Descriptor Type) field:
|   00       = invalid
|   01 or 11 = resident
|   10       = indirect (points at another page descriptor)
|
| i.e. DT=3 at the LEAF (page) level is a legal, standard "resident"
| encoding — functionally identical to DT=1 — NOT an unsupported or
| invalid encoding.  A real page-table image is free to use either
| resident encoding.  mmu_walker.v previously treated DT=3 at the leaf
| position as an "indirect descriptor" (grouped with DT=2, which really
| is the indirect encoding) and spuriously bus-errored the access.
|
| Construction mirrors mmu_atc_load_then_use.s (same VBR/ITT0/DTT0/DTT1
| and root/L1/L2/L3 address layout) but the leaf (L3) page descriptor
| uses DT=3 (low nibble 0xB: U=1,WP=0,DT=11) instead of the DT=1
| encoding (low nibble 0x9) used elsewhere.
|
| PASS sentinel: 0xC0FFEE00 when the load returns the seeded pattern
| through the DT=3 leaf mapping.
| FAIL sentinels:
|   0xDEAD0901 — load returned wrong value
|   0xDEAD0902 — vec-2 fired (page-fault path) — DT=3 leaf descriptor
|                was spuriously treated as invalid/indirect instead of
|                resident.

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
    | L1[0x40] -> L2 @ 0x210000.  DT=10 (normal table descriptor).
    move.l  #0x0021000a, 0x00200100
    | L2[0x00] -> L3 @ 0x220000.  DT=10 (normal table descriptor).
    move.l  #0x0022000a, 0x00210000
    | L3[0x01] = PFN 0x00300000, U=1, DT=11 (resident -- alt encoding,
    | this is the bug-under-test: must be treated identically to DT=01).
    move.l  #0x0030000b, 0x00220004
    | Seed PFN with sentinel.
    move.l  #0xCAFEBABE, 0x00300000

    | Enable MMU 3-lvl 4K.
    move.l  #0x00008770, %d0
    movec   %d0, %tc

    | Drop to user, load through the DT=3 leaf mapping.
    andi.w  #0xDFFF, %sr
    lea     0x00008000, %a7
    move.l  #0x80001000, %a1

    move.l  (%a1), %d0
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail1

    trap    #0

_fail1:
    move.l  #0xDEAD0901, 0xFFFF0000
_halt1:
    bra     _halt1

_buserr:
    | Unexpected page-fault -- DT=3 leaf descriptor should be resident.
    move.l  #0xDEAD0902, 0xFFFF0000
_halt_be:
    bra     _halt_be

_trap_h:
    move.l  #0xC0FFEE00, 0xFFFF0000
_done:
    bra     _done
