| byte_lane_indexed_mmu_on.s — Byte-lane test with MMU enabled
|
| Same recipe as byte_lane_indexed_dbf_loop.s, but with the MMU
| enabled via TT registers pointing low DRAM to itself.  This
| matches the Q700 environment where the MMU is enabled when the
| byte-lane RAM test runs (we observed live MMU CSRs in the
| running ROM per project_pt_corruption_root_cause_in_progress).
|
| If the byte-lane bug only manifests when MMU translation is on
| (e.g., page boundaries, ATC interaction with cache eviction),
| this test exposes it while the no-MMU variant does not.

    .text
    .org 0

_start:
    | Set up VBR for exception handlers (parked).
    lea     0x00010000, %a7
    move.l  #_panic, 0x00100008
    move.l  #_panic, 0x0010000C
    move.l  #0x00100000, %d0
    movec   %d0, %vbr

    | ITT0 — supervisor passthrough for 0x40000000.. (ROM region).
    | Format matches mmu_atc_load_then_use.s (E=1, supervisor, CM=00).
    move.l  #0x4000C000, %d0
    movec   %d0, %itt0

    | DTT0 — supervisor passthrough for 0x00000000.. (low DRAM).
    | Same format as the working ATC test.
    move.l  #0x007FA000, %d0
    movec   %d0, %dtt0

    | DTT1 — supervisor passthrough for sentinel 0xFFFFxxxx region.
    move.l  #0xFF00A000, %d0
    movec   %d0, %dtt1

    | URP/SRP — pointed at low DRAM root (never walked: TTs cover all).
    move.l  #0x00200000, %d0
    movec   %d0, %urp
    movec   %d0, %srp

    | Enable MMU 3-lvl 4K (same as working ATC test).
    move.l  #0x00008770, %d0
    movec   %d0, %tc

    | The byte-lane test, same as Q700 recipe.
    lea     0x00104000, %a0
    move.l  #0xAAAAAAAA, (%a0)
    moveq   #0, %d6
    move.l  #0x54696E61, %d1

    move.l  (%a0), %d3
    move.l  4(%a0), %d4

    move.l  %d1, (%a0)
    moveq   #3, %d2

.loop:
    move.l  #-1, 4(%a0)
    cmp.b   (0,%a0,%d2:w*1), %d1
    bne     .fail_set
    not.b   %d1
    not.b   (0,%a0,%d2:w*1)
    cmp.b   (0,%a0,%d2:w*1), %d1
    beq     .skip_fail
.fail_set:
    bset    %d2, %d6
.skip_fail:
    ror.l   #8, %d1
    dbra    %d2, .loop

    move.l  %d3, (%a0)
    move.l  %d4, 4(%a0)

    tst.l   %d6
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_fail:
    move.l  %d6, %d7
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
_halt_fail:
    bra     _halt_fail

_panic:
    | Any unexpected fault → fail with distinctive sentinel.
    move.l  #0xFA000099, %d7
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
_halt_panic:
    bra     _halt_panic
