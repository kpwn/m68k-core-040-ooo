| cinv_line_basic.s — CINV DC, LINE, (An) drops a dirty line without writeback.
|
| Strategy:
|   1. Enable MMU + DTT0 covering 0x00xxxxxx as cacheable copyback,
|      ITT0 covering 0x4xxxxxxx as cacheable copyback (so the test
|      code itself stays cacheable).  Without TT setup the dcache
|      forces cache_inh=1 and CINV/CPUSH have nothing to operate on.
|   2. Enable CACR.DE, then dirty a cache line with V1 at addr X.
|   3. CINV DC, LINE, (A0=X) — drop V1.  Memory at X stays at initial
|      0xFFFFFFFF because CINV does NOT write back.
|   4. Reload addr X → cache miss → refill from memory → value should
|      be 0xFFFFFFFF.
|
| The tb mem model initialises all unmapped RAM bytes to 0xFF, so the
| "memory value for uninitialised address" is 0xFFFFFFFF.
|
| PASS: reload = 0xFFFFFFFF.
| FAIL: reload = V1 (CINV didn't invalidate the line).

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    | DTT0 = passthrough 0x00xxxxxx with cache mode = cacheable copyback.
    | Layout: base[31:24]=0x00, mask[23:16]=0x0F, E=1, S-field=11 (ignore),
    |         CM[6:5]=01 (copyback).  → 0x000F_E020
    move.l  #0x000FE020, %d7
    movec   %d7, %dtt0

    | ITT0 = passthrough 0x4xxxxxxx (test code) with cache mode = copyback.
    | base=0x40, mask=0x0F → 0x400FE020
    move.l  #0x400FE020, %d7
    movec   %d7, %itt0

    | DTT1 = passthrough 0xFFxxxxxx with cache mode = non-cacheable.
    | The test sentinel address (0xFFFF0000) lives there; without a
    | DTT covering it, an enabled MMU page-table-walks and faults
    | (URP/SRP are unset).  Layout: base=0xFF, mask=0x00, E=1,
    | S-field=11 (ignore), CM[6:5]=11 (non-cacheable).  → 0xFF00_E060
    move.l  #0xFF00E060, %d7
    movec   %d7, %dtt1

    | TC.E = 1 (MMU enable, 4K page).  Without this mmu.v forces
    | cache_inh=1 globally, defeating the dcache regardless of
    | DTT/ITT cache-mode bits.
    move.l  #0x8000, %d7
    movec   %d7, %tc

    | CACR.DE = enable D-cache (bit 31).  Re-read to confirm MOVEC plumbing.
    move.l  #0x80000000, %d7
    movec   %d7, %cacr
    movec   %cacr, %d6
    cmp.l   %d7, %d6
    bne     _fail

    | A0 = RAM address (line-aligned to be clean).
    move.l  #0x00011000, %a0

    | V1 = distinctive.  Writing to (A0) through the cache dirties the
    | line.  No writeback happens yet (write-back / copyback policy).
    move.l  #0xCAFEBABE, (%a0)

    | CINV DC, LINE, (A0): opword 0xF448.
    cinvl   %dc, (%a0)

    | Reload.  Line is invalid → cache miss → fill from memory → 0xFFFFFFFF.
    move.l  (%a0), %d0

    | Compare against 0xFFFFFFFF.
    move.l  #0xFFFFFFFF, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | PASS
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, (%a1)
_halt_fail:
    bra     _halt_fail
