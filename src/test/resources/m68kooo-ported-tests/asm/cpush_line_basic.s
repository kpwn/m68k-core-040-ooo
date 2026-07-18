| cpush_line_basic.s — CPUSH DC, LINE, (An) writes a dirty line back.
|
| Strategy:
|   1. Enable MMU + DTT0 covering 0x00xxxxxx as cacheable copyback,
|      ITT0 covering 0x4xxxxxxx as cacheable copyback.  Without TT
|      setup the dcache forces cache_inh=1 and CINV/CPUSH have
|      nothing to operate on.
|   2. Enable CACR.DE, then dirty a RAM line by storing V1 at addr X.
|   3. CPUSH DC, LINE, (A0=X) — writeback V1 to memory, leave line
|      valid + clean.
|   4. Dirty the SAME cache line again with V3 at addr X.
|   5. CINV DC, LINE, (A0=X) — drop V3 without writeback.
|   6. Reload addr X — must see V1 (CPUSH'd to memory).  If we saw V3,
|      CINV failed (didn't drop).  If we saw 0xFFFFFFFF, CPUSH failed
|      (didn't write back).
|
| This single test exercises both CPUSH LINE writeback and CINV LINE
| drop-without-writeback.  RAM starts at 0xFFFFFFFF per the tb mem
| model (uninit bytes).
|
| PASS: sentinel 0xC0FFEE00.
| FAIL: sentinel 0xDEADBEEF.

    .text
    .org 0

_start:
    | Supervisor stack.
    lea     0x00020000, %a7

    | DTT0 = passthrough 0x00xxxxxx with cache mode = cacheable copyback.
    move.l  #0x000FE020, %d7
    movec   %d7, %dtt0

    | ITT0 = passthrough 0x4xxxxxxx with cache mode = cacheable copyback.
    move.l  #0x400FE020, %d7
    movec   %d7, %itt0

    | DTT1 = passthrough 0xFFxxxxxx (test sentinel) non-cacheable.
    move.l  #0xFF00E060, %d7
    movec   %d7, %dtt1

    | TC.E = 1 (MMU enable, 4K page).  Required to honour TT cache-mode.
    move.l  #0x8000, %d7
    movec   %d7, %tc

    | CACR.DE = enable D-cache.
    move.l  #0x80000000, %d7
    movec   %d7, %cacr
    movec   %cacr, %d6
    cmp.l   %d7, %d6
    bne     _fail

    | A0 = cache-line-aligned RAM address.
    move.l  #0x00010000, %a0

    | V1 = 0x11223344 — the "pushed" value we expect to find in memory.
    move.l  #0x11223344, (%a0)

    | CPUSH DC, LINE, (A0): opword 0xF468.
    cpushl  %dc, (%a0)

    | Dirty the line again with V3 = 0xBADC0DE5 — this write lands in
    | cache, NOT in memory, because we'll CINV before eviction.
    move.l  #0xBADC0DE5, (%a0)

    | CINV DC, LINE, (A0): opword 0xF448.  Drops V3 without writeback.
    cinvl   %dc, (%a0)

    | Reload.  Cache miss → refill from memory.  If CPUSH worked, memory
    | has V1.  If CPUSH didn't write back, memory has 0xFFFFFFFF.  If
    | CINV didn't invalidate, we'd see V3 from the still-cached dirty line.
    move.l  (%a0), %d1

    | Compare against V1.
    move.l  #0x11223344, %d2
    cmp.l   %d2, %d1
    bne     _fail

    | PASS
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
_halt_fail:
    bra     _halt_fail
