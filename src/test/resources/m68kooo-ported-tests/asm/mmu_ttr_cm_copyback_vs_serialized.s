| mmu_ttr_cm_copyback_vs_serialized.s — TTR CM[6:5] decode discrimination.
|
| Regression test for the mmu.v ttr_ci bit-position bug: the TTR
| cache-mode field lives at bits [6:5] (MC68040 UM §3.1.2, same
| attribute layout as the page descriptor — cf. mmu_walker.v reading
| r_data[6]).  mmu.v used to read bit 5 (CM[0]) as the cache-inhibit
| flag, which classified:
|     CM=01 (attrs 0x20, cacheable copyback)      as NON-cacheable
|     CM=10 (attrs 0x40, non-cacheable serialized) as CACHEABLE
| — exactly backwards.  These two encodings differ ONLY in which of
| bit 6 / bit 5 is set, so this test discriminates the decode
| directly through the DTT path with the D-cache enabled:
|
| Phase 1 — DTT0 CM=01 (0x000F_E020, copyback):
|   Write V1 to X through the cache (dirties the line, no writeback),
|   CINV the line, reload.  Cacheable-copyback ⇒ the write dies with
|   the invalidated line ⇒ reload sees memory default 0xFFFFFFFF.
|   Under the old bug the write bypassed the cache straight to memory
|   and the reload saw V1.
|
| Phase 2 — DTT0 reprogrammed to CM=10 (0x000F_E040, serialized):
|   Write V2 to Y (different line).  Non-cacheable ⇒ write goes
|   straight to memory; CINV is a no-op; reload sees V2.  Under the
|   old bug the write was cached, CINV dropped it, reload saw
|   0xFFFFFFFF.
|
| FAIL sentinels:
|   0xDEAD0CB0  CACR readback mismatch
|   0xDEAD0CB1  Phase 1: CM=01 treated as non-cacheable (old-bug mode)
|   0xDEAD0CB2  Phase 2: CM=10 treated as cacheable    (old-bug mode)

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    | DTT0 = passthrough 0x00xxxxxx, CM[6:5]=01 (cacheable copyback).
    move.l  #0x000FE020, %d7
    movec   %d7, %dtt0

    | ITT0 = passthrough 0x4xxxxxxx (test code), copyback.
    move.l  #0x400FE020, %d7
    movec   %d7, %itt0

    | DTT1 = passthrough 0xFFxxxxxx (sentinel), CM[6:5]=11 non-cacheable.
    move.l  #0xFF00E060, %d7
    movec   %d7, %dtt1

    | TC.E = 1 (4K pages); TTs cover everything we touch.
    move.l  #0x8000, %d7
    movec   %d7, %tc

    | CACR.DE = 1 (D-cache enable).
    move.l  #0x80000000, %d7
    movec   %d7, %cacr
    movec   %cacr, %d6
    cmp.l   %d7, %d6
    bne     _fail_cacr

    | ── Phase 1: CM=01 must be CACHEABLE (copyback) ──────────────────
    move.l  #0x00011000, %a0
    move.l  #0xCAFEBABE, (%a0)          | dirty the line in-cache
    cinvl   %dc, (%a0)                  | drop it — NO writeback
    move.l  (%a0), %d0                  | refill from memory
    move.l  #0xFFFFFFFF, %d1
    cmp.l   %d1, %d0
    bne     _fail_p1                    | saw V1 ⇒ write bypassed cache

    | ── Phase 2: CM=10 must be NON-cacheable (serialized) ────────────
    move.l  #0x000FE040, %d7
    movec   %d7, %dtt0
    move.l  #0x00012000, %a1
    move.l  #0xC0DED00D, (%a1)          | must go straight to memory
    cinvl   %dc, (%a1)                  | no-op if truly uncached
    move.l  (%a1), %d0
    cmp.l   #0xC0DED00D, %d0
    bne     _fail_p2                    | saw 0xFFFFFFFF ⇒ write was cached

    | PASS
    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a2)
_halt:
    bra     _halt

_fail_cacr:
    move.l  #0xDEAD0CB0, %d3
    bra     _fail
_fail_p1:
    move.l  #0xDEAD0CB1, %d3
    bra     _fail
_fail_p2:
    move.l  #0xDEAD0CB2, %d3
    bra     _fail
_fail:
    lea     0xFFFF0000, %a2
    move.l  %d3, (%a2)
_halt_fail:
    bra     _halt_fail
